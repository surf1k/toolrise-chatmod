package space.toolrise.chatmod.auto;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import space.toolrise.chatmod.TrChatMod;
import space.toolrise.chatmod.chat.FlaggedMessage;
import space.toolrise.chatmod.config.ModConfig;
import space.toolrise.chatmod.punish.PunishmentExecutor;
import space.toolrise.chatmod.rules.MuteDuration;
import space.toolrise.chatmod.rules.Violation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Автоматическая выдача мутов.
 *
 * <p>Включается кнопкой и дальше работает без человека: пока клиент подключён
 * к серверу, каждое помеченное сообщение проходит через набор предохранителей и,
 * если все они пройдены, наказывается само.
 *
 * <p>Предохранители существуют потому, что цена ошибки несимметрична: пропущенное
 * нарушение — это одно грубое сообщение в чате, а ложное срабатывание — невиновный
 * игрок с мутом, которого никто не снимет, пока модератор спит. Поэтому:
 * <ul>
 *   <li>потолок строгости — самое тяжёлое уходит человеку в очередь;</li>
 *   <li>кулдаун на игрока — один инцидент не превращается в серию мутов;</li>
 *   <li>лимит в минуту — сломанное правило не выкосит весь сервер;</li>
 *   <li>ник обязан быть в списке онлайн — не наказываем разбор строки чата;</li>
 *   <li>эхо плагина наказаний игнорируется — иначе автомат зациклится сам на себе.</li>
 * </ul>
 */
public final class AutoModerator {

    /** Запись о выданном автомате наказании — для {@code /trchatmod undo}. */
    public record Issued(String nick, String ruleId, String time, long at) {
    }

    private static final int UNDO_HISTORY = 32;

    private final TrChatMod mod;
    private final Map<String, Long> lastPunishedAt = new HashMap<>();
    private final Deque<Long> issuedTimes = new ArrayDeque<>();
    private final Deque<Issued> issued = new ArrayDeque<>();
    private final List<PendingReview> queue = new ArrayList<>();
    private List<Pattern> skipLinePatterns = List.of();

    private int issuedCount;
    private int skippedCount;

    public AutoModerator(TrChatMod mod) {
        this.mod = mod;
        recompile();
    }

    /** Перечитывает то, что зависит от конфига. Вызывается после /trchatmod reload. */
    public void recompile() {
        List<Pattern> patterns = new ArrayList<>();
        for (String raw : mod.config().autoSkipLinePatterns) {
            try {
                patterns.add(Pattern.compile(raw));
            } catch (PatternSyntaxException e) {
                TrChatMod.LOGGER.warn("autoSkipLinePatterns: не скомпилирован «{}» ({})", raw, e.getDescription());
            }
        }
        this.skipLinePatterns = List.copyOf(patterns);
    }

    // ------------------------------------------------------------ состояние

    public boolean isActive() {
        return mod.config().autoMode;
    }

    public boolean isDryRun() {
        return mod.config().autoDryRun;
    }

    public int issuedCount() {
        return issuedCount;
    }

    public int queueSize() {
        return queue.size();
    }

    public List<PendingReview> queue() {
        return List.copyOf(queue);
    }

    public void clearQueue() {
        queue.clear();
    }

    /** @return новое состояние */
    public boolean toggle() {
        setActive(!isActive());
        return isActive();
    }

    public void setActive(boolean active) {
        ModConfig config = mod.config();
        if (config.autoMode == active) {
            return;
        }
        config.autoMode = active;
        mod.configManager().saveConfig();
        announce(active
                ? Component.literal("АВТОМАТ ВКЛЮЧЁН")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withBold(true))
                .append(Component.literal(config.autoDryRun ? " (тестовый режим, команды не уходят)" : "")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withBold(false)))
                : Component.literal("Автомат выключен")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
        if (active && !config.autoDryRun) {
            announce(Component.literal("потолок: " + ceiling().display()
                            + ", лимит: " + config.autoMaxPerMinute + "/мин"
                            + ", кулдаун: " + config.autoCooldownSeconds + " сек")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)));
        }
    }

    // ------------------------------------------------------------- решение

    /** Главный вход: вызывается на каждое помеченное сообщение. */
    public void handle(FlaggedMessage message) {
        if (!isActive()) {
            return;
        }
        try {
            decide(message);
        } catch (Exception e) {
            TrChatMod.LOGGER.error("Сбой автомата", e);
        }
    }

    private void decide(FlaggedMessage message) {
        ModConfig config = mod.config();
        Violation primary = message.result().primary();
        if (primary == null) {
            return;
        }

        for (Pattern p : skipLinePatterns) {
            if (p.matcher(message.fullLine()).find()) {
                skippedCount++;
                return;
            }
        }

        String nick = message.nick();
        if (nick == null || nick.isBlank()) {
            enqueue(message, "не удалось определить ник");
            return;
        }
        if (config.autoRequireOnlineNick && !message.nickKnown()) {
            enqueue(message, "ник «" + nick + "» не найден в списке онлайн");
            return;
        }
        if (config.autoSkipRules.contains(primary.ruleId())) {
            enqueue(message, "правило " + primary.ruleId() + " исключено из автомата");
            return;
        }
        if (primary.mute().compareTo(ceiling()) > 0) {
            enqueue(message, "мут " + primary.mute().display()
                    + " строже потолка " + ceiling().display());
            return;
        }

        long now = System.currentTimeMillis();
        Long last = lastPunishedAt.get(nick.toLowerCase(Locale.ROOT));
        if (last != null && now - last < config.autoCooldownSeconds * 1000L) {
            skippedCount++;
            return;
        }
        if (!withinRateLimit(now, config)) {
            enqueue(message, "превышен лимит " + config.autoMaxPerMinute + " наказаний в минуту");
            return;
        }

        String time = PunishmentExecutor.timeToken(primary.mute(), config);
        String reason = config.buildReason(primary.ruleId(), primary.ruleName(), "");

        if (config.autoDryRun) {
            announce(Component.literal("[ТЕСТ] выдал бы: ")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW))
                    .append(Component.literal("/" + config.buildCommand(nick, time, reason))
                            .withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE))));
            skippedCount++;
            return;
        }

        // Слот занимаем сразу, а не в отложенной задаче: во флуде десяток сообщений
        // приходит в одном тике, и отложенный учёт пропустил бы их все разом.
        String key = nick.toLowerCase(Locale.ROOT);
        lastPunishedAt.put(key, now);
        issuedTimes.addLast(now);

        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            boolean ok = mod.executor().mute(message, nick, time, reason, primary.shortLabel(), true);
            if (ok) {
                issued.addLast(new Issued(nick, primary.ruleId(), time, System.currentTimeMillis()));
                while (issued.size() > UNDO_HISTORY) {
                    issued.removeFirst();
                }
                issuedCount++;
            } else {
                // Команда не ушла — откатываем бронь, иначе игрок «съест» кулдаун зря.
                lastPunishedAt.remove(key);
                issuedTimes.remove(now);
            }
        });
    }

    private boolean withinRateLimit(long now, ModConfig config) {
        while (!issuedTimes.isEmpty() && now - issuedTimes.peekFirst() > 60_000L) {
            issuedTimes.removeFirst();
        }
        return issuedTimes.size() < Math.max(1, config.autoMaxPerMinute);
    }

    private MuteDuration ceiling() {
        return MuteDuration.parse(mod.config().autoMaxMute);
    }

    private void enqueue(FlaggedMessage message, String reason) {
        queue.add(new PendingReview(message, reason, System.currentTimeMillis()));
        while (queue.size() > 64) {
            queue.remove(0);
        }
        announce(Component.literal("на проверку: ")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW))
                .append(Component.literal(message.nickOrUnknown() + " — " + reason + " ")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)))
                .append(Component.literal("[выдать]")
                        .withStyle(Style.EMPTY
                                .withColor(ChatFormatting.AQUA)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand(
                                        "/" + TrChatMod.COMMAND + " punish " + message.id())))));
    }

    // ---------------------------------------------------------------- undo

    /** Снимает последние {@code count} автоматических мутов. @return сколько снято */
    public int undo(int count) {
        ModConfig config = mod.config();
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            return 0;
        }
        int done = 0;
        for (int i = 0; i < count && !issued.isEmpty(); i++) {
            Issued last = issued.removeLast();
            String command = config.buildUnmuteCommand(last.nick());
            if (command.isBlank()) {
                continue;
            }
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            client.getConnection().sendCommand(command);
            lastPunishedAt.remove(last.nick().toLowerCase(Locale.ROOT));
            TrChatMod.LOGGER.info("Отмена автомута: /{}", command);
            done++;
        }
        return done;
    }

    public List<Issued> recentIssued() {
        return List.copyOf(issued);
    }

    // -------------------------------------------------------------- прочее

    public void reset() {
        lastPunishedAt.clear();
        issuedTimes.clear();
        issued.clear();
        queue.clear();
        issuedCount = 0;
        skippedCount = 0;
    }

    public int skippedCount() {
        return skippedCount;
    }

    /**
     * Пишет в чат отложенно: решение принимается в момент разбора входящего
     * сообщения, и синхронная вставка встала бы в чат раньше самого нарушения.
     */
    private static void announce(Component component) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.gui != null) {
                client.gui.getChat().addMessage(Component.literal("[Авто] ")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD))
                        .append(component));
            }
        });
    }
}
