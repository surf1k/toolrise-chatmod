package space.toolrise.chatmod.auto;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import space.toolrise.chatmod.TrChatMod;
import space.toolrise.chatmod.chat.FlaggedMessage;
import space.toolrise.chatmod.chat.RankPolicy;
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
 *   <li>потолок строгости — самое тяжёлое автомат не берёт;</li>
 *   <li>кулдаун на игрока — один инцидент не превращается в серию мутов;</li>
 *   <li>лимит в минуту — сломанное правило не выкосит весь сервер;</li>
 *   <li>ник обязан быть в списке онлайн — не наказываем разбор строки чата;</li>
 *   <li>эхо плагина наказаний игнорируется — иначе автомат зациклится сам на себе.</li>
 * </ul>
 *
 * <p>Всё, за что автомат не взялся, остаётся модератору обычным путём: сообщение
 * подсвечено красным, мут выдаётся кликом. Отдельной очереди для этого нет.
 */
public final class AutoModerator implements AutoModeration {

    /**
     * Наказание, назначенное автоматом, но ещё не отправленное: идёт отсчёт
     * паузы, за которую человек может выдать его сам.
     */
    public record Pending(FlaggedMessage message,
                          String nick,
                          String time,
                          String reason,
                          String ruleLabel,
                          long dueAt,
                          long reservedAt) {
        public int secondsLeft(long now) {
            return (int) Math.max(0, (dueAt - now + 999) / 1000);
        }
    }

    private final TrChatMod mod;
    private final Map<String, Long> lastPunishedAt = new HashMap<>();
    private final Deque<Long> issuedTimes = new ArrayDeque<>();
    private final List<Pending> pending = new ArrayList<>();
    private List<Pattern> skipLinePatterns = List.of();
    private List<Pattern> punishBroadcastPatterns = List.of();

    private int issuedCount;
    private int skippedCount;

    public AutoModerator(TrChatMod mod) {
        this.mod = mod;
        recompile();
    }

    /** Компилирует то, что зависит от конфига. */
    private void recompile() {
        this.skipLinePatterns = compile(mod.config().autoSkipLinePatterns, "autoSkipLinePatterns");
        this.punishBroadcastPatterns =
                compile(mod.config().autoPunishBroadcastPatterns, "autoPunishBroadcastPatterns");
    }

    private static List<Pattern> compile(List<String> raw, String what) {
        List<Pattern> patterns = new ArrayList<>();
        for (String s : raw) {
            try {
                patterns.add(Pattern.compile(s));
            } catch (PatternSyntaxException e) {
                TrChatMod.LOGGER.warn("{}: не скомпилирован «{}» ({})", what, s, e.getDescription());
            }
        }
        return List.copyOf(patterns);
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

    /** Сколько наказаний сейчас ждёт отправки. */
    public int pendingCount() {
        return pending.size();
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
        if (!active) {
            announce(Component.literal("Автомат выключен")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
            return;
        }

        if (config.autoDryRun) {
            // Тест — залипающее состояние, поэтому выход из него всегда рядом,
            // одним кликом: иначе легко решить, что автомат сломан.
            announce(Component.literal("АВТОМАТ ВКЛЮЧЁН, НО В ТЕСТОВОМ РЕЖИМЕ")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withBold(true))
                    .append(Component.literal(" — команды не уходят ")
                            .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withBold(false)))
                    .append(Component.literal("[включить по-настоящему]")
                            .withStyle(Style.EMPTY
                                    .withColor(ChatFormatting.GREEN)
                                    .withBold(false)
                                    .withUnderlined(true)
                                    .withClickEvent(new ClickEvent.RunCommand(
                                            "/" + TrChatMod.COMMAND + " auto live")))));
            return;
        }

        announce(Component.literal("АВТОМАТ ВКЛЮЧЁН")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withBold(true)));
        announce(Component.literal("пауза перед выдачей: " + config.autoDelaySeconds + " сек"
                        + ", потолок: " + ceiling().display()
                        + ", лимит: " + config.autoMaxPerMinute + "/мин")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY)));
    }

    // ------------------------------------------------------------- решение

    /** Главный вход: вызывается на каждое помеченное сообщение. */
    @Override
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
            skip(message, "не удалось определить ник");
            return;
        }
        if (config.autoRequireOnlineNick && !message.nickKnown()) {
            skip(message, "ник «" + nick + "» не найден в списке онлайн");
            return;
        }
        if (config.autoSkipRules.contains(primary.ruleId())) {
            skip(message, "правило " + primary.ruleId() + " исключено из автомата");
            return;
        }
        // Ранг может сам ограничить срок: медийщикам больше 10 минут нельзя.
        MuteDuration wanted = RankPolicy.cap(primary.mute(), message.prefix(), config);
        if (wanted.compareTo(ceiling()) > 0) {
            skip(message, "мут " + wanted.display()
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
            skip(message, "превышен лимит " + config.autoMaxPerMinute + " наказаний в минуту");
            return;
        }

        String time = PunishmentExecutor.timeToken(wanted, config);
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

        int delay = Math.max(0, config.autoDelaySeconds);
        if (delay == 0) {
            send(message, nick, time, reason, primary.shortLabel(), key, now);
            return;
        }

        // Пауза для людей: у обычных модераторов норма и зарплата считаются по
        // наказаниям, поэтому автомат даёт им время выдать наказание самим.
        Pending item = new Pending(message, nick, time, reason, primary.shortLabel(),
                now + delay * 1000L, now);
        pending.add(item);
        // Отменить отложенную выдачу можно, выдав мут самому: ручная выдача
        // снимает то, что автомат держит на этого игрока.
        announce(Component.literal("через " + delay + " сек: ")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))
                .append(Component.literal(nick + " — " + primary.shortLabel() + " — " + time)
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE))));
    }

    private void send(FlaggedMessage message, String nick, String time, String reason,
                      String ruleLabel, String key, long reservedAt) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            boolean ok = mod.executor().mute(message, nick, time, reason, ruleLabel, true);
            if (ok) {
                issuedCount++;
            } else {
                // Команда не ушла — откатываем бронь, иначе игрок «съест» кулдаун зря.
                lastPunishedAt.remove(key);
                issuedTimes.remove(reservedAt);
            }
        });
    }

    // ----------------------------------------------------- отложенная выдача

    /** Вызывается каждый тик клиента: отправляет то, у чего вышла пауза. */
    @Override
    public void tick() {
        if (pending.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Pending> due = new ArrayList<>();
        pending.removeIf(p -> {
            if (p.dueAt() <= now) {
                due.add(p);
                return true;
            }
            return false;
        });
        for (Pending p : due) {
            if (!isActive()) {
                continue;
            }
            send(p.message(), p.nick(), p.time(), p.reason(), p.ruleLabel(),
                    p.nick().toLowerCase(Locale.ROOT), p.reservedAt());
        }
    }

    /** Снимает отложенную выдачу по игроку — например, когда его наказал человек. */
    @Override
    public void cancelFor(String nick, String why) {
        if (nick == null || nick.isBlank() || pending.isEmpty()) {
            return;
        }
        String key = nick.toLowerCase(Locale.ROOT);
        List<Pending> hit = new ArrayList<>();
        for (Pending p : pending) {
            if (p.nick().toLowerCase(Locale.ROOT).equals(key)) {
                hit.add(p);
            }
        }
        hit.forEach(p -> drop(p, why));
    }

    /**
     * Смотрит строку чата: если это объявление о наказании и в нём есть игрок,
     * которого автомат держит на паузе — значит, человек успел раньше.
     */
    @Override
    public void observeChatLine(String line) {
        if (pending.isEmpty() || line == null || line.isBlank()
                || !mod.config().autoCancelIfPunishedByOther) {
            return;
        }
        boolean looksLikePunishment = false;
        for (Pattern p : punishBroadcastPatterns) {
            if (p.matcher(line).find()) {
                looksLikePunishment = true;
                break;
            }
        }
        if (!looksLikePunishment) {
            return;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        for (Pending p : List.copyOf(pending)) {
            if (lower.contains(p.nick().toLowerCase(Locale.ROOT))) {
                drop(p, "игрока уже наказал модератор");
            }
        }
    }

    private void drop(Pending item, String why) {
        pending.remove(item);
        // Бронь освобождаем целиком: наказания от автомата не было, значит оно
        // не должно занимать ни кулдаун игрока, ни слот лимита в минуту.
        lastPunishedAt.remove(item.nick().toLowerCase(Locale.ROOT));
        issuedTimes.remove(item.reservedAt());
        skippedCount++;
        announce(Component.literal("не выдаю " + item.nick() + " — " + why)
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
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

    /**
     * Автомат за это не берётся. Отдельной очереди нет: сообщение уже подсвечено
     * красным и кликабельно, поэтому достаточно сказать, почему решение за человеком.
     */
    private void skip(FlaggedMessage message, String reason) {
        skippedCount++;
        announce(Component.literal("решай сам: ")
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

    // -------------------------------------------------------------- прочее

    @Override
    public void reset() {
        pending.clear();
        lastPunishedAt.clear();
        issuedTimes.clear();
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
