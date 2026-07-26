package space.toolrise.chatmod.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import space.toolrise.chatmod.TrChatMod;
import space.toolrise.chatmod.chat.FlaggedMessage;
import space.toolrise.chatmod.gui.PunishScreen;
import space.toolrise.chatmod.rules.ScanResult;
import space.toolrise.chatmod.rules.Violation;
import space.toolrise.chatmod.text.TextNormalizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Клиентские команды мода. Отдельно важна {@code punish <id>} — именно её
 * вызывает клик по подсвеченному сообщению.
 */
public final class ModCommands {

    private ModCommands() {
    }

    public static void register(TrChatMod mod) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            dispatcher.register(build(TrChatMod.COMMAND, mod));
            dispatcher.register(build("trmod", mod));
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> build(String name, TrChatMod mod) {
        return ClientCommandManager.literal(name)
                .executes(ctx -> help(ctx.getSource()))
                .then(ClientCommandManager.literal("punish")
                        .then(ClientCommandManager.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> punish(mod, IntegerArgumentType.getInteger(ctx, "id")))))
                .then(ClientCommandManager.literal("reload")
                        .executes(ctx -> {
                            String report = mod.reload();
                            info(ctx.getSource(), "Перезагружено: " + report);
                            return 1;
                        }))
                .then(ClientCommandManager.literal("toggle")
                        .executes(ctx -> {
                            mod.config().enabled = !mod.config().enabled;
                            mod.configManager().saveConfig();
                            info(ctx.getSource(), "Проверка чата " + (mod.config().enabled ? "включена" : "выключена"));
                            return 1;
                        }))
                .then(ClientCommandManager.literal("test")
                        .then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> test(mod, ctx.getSource(),
                                        StringArgumentType.getString(ctx, "text")))))
                .then(ClientCommandManager.literal("parse")
                        .then(ClientCommandManager.argument("line", StringArgumentType.greedyString())
                                .executes(ctx -> parse(mod, ctx.getSource(),
                                        StringArgumentType.getString(ctx, "line")))))
                .then(ClientCommandManager.literal("list")
                        .executes(ctx -> list(mod, ctx.getSource())))
                .then(ClientCommandManager.literal("ignore")
                        .executes(ctx -> ignoreList(mod, ctx.getSource()))
                        .then(ClientCommandManager.literal("list")
                                .executes(ctx -> ignoreList(mod, ctx.getSource())))
                        .then(ClientCommandManager.literal("add")
                                .then(ClientCommandManager.argument("nick", StringArgumentType.word())
                                        .suggests(ONLINE_PLAYERS)
                                        .executes(ctx -> ignoreAdd(mod, ctx.getSource(),
                                                StringArgumentType.getString(ctx, "nick")))))
                        .then(ClientCommandManager.literal("remove")
                                .then(ClientCommandManager.argument("nick", StringArgumentType.word())
                                        .suggests(IGNORED_PLAYERS)
                                        .executes(ctx -> ignoreRemove(mod, ctx.getSource(),
                                                StringArgumentType.getString(ctx, "nick")))))
                        .then(ClientCommandManager.literal("clear")
                                .executes(ctx -> {
                                    int n = mod.config().ignoredNicks.size();
                                    mod.config().ignoredNicks.clear();
                                    mod.configManager().saveConfig();
                                    info(ctx.getSource(), "Игнор-лист очищен (" + n + ")");
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("proof")
                        .executes(ctx -> {
                            java.nio.file.Path dir = mod.evidence().directory();
                            info(ctx.getSource(), "Протоколы наказаний: " + dir);
                            try {
                                java.nio.file.Files.createDirectories(dir);
                                net.minecraft.util.Util.getPlatform().openFile(dir.toFile());
                            } catch (Exception e) {
                                TrChatMod.LOGGER.warn("Папку протоколов открыть не удалось", e);
                            }
                            return 1;
                        }))
                .then(ClientCommandManager.literal("config")
                        .executes(ctx -> {
                            info(ctx.getSource(), "Файлы: " + mod.configManager().directory());
                            return 1;
                        }))
                .then(ClientCommandManager.literal("auto")
                        .executes(ctx -> autoStatus(mod, ctx.getSource()))
                        .then(ClientCommandManager.literal("on").executes(ctx -> {
                            mod.autoModerator().setActive(true);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("off").executes(ctx -> {
                            mod.autoModerator().setActive(false);
                            return 1;
                        }))
                        // «Работай по-настоящему» одной командой: снимает тест и включает автомат.
                        .then(ClientCommandManager.literal("live").executes(ctx -> {
                            setDryRun(mod, ctx.getSource(), false);
                            mod.autoModerator().setActive(true);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("delay")
                                .executes(ctx -> {
                                    info(ctx.getSource(), "Пауза перед выдачей: "
                                            + mod.config().autoDelaySeconds + " сек");
                                    return 1;
                                })
                                .then(ClientCommandManager.argument("seconds",
                                                IntegerArgumentType.integer(0, 3600))
                                        .executes(ctx -> setDelay(mod, ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "seconds")))))
                        .then(ClientCommandManager.literal("dry")
                                .executes(ctx -> setDryRun(mod, ctx.getSource(), !mod.config().autoDryRun))
                                .then(ClientCommandManager.literal("on")
                                        .executes(ctx -> setDryRun(mod, ctx.getSource(), true)))
                                .then(ClientCommandManager.literal("off")
                                        .executes(ctx -> setDryRun(mod, ctx.getSource(), false)))))
                .then(ClientCommandManager.literal("queue")
                        .executes(ctx -> {
                            mod.showQueue();
                            return 1;
                        })
                        .then(ClientCommandManager.literal("clear").executes(ctx -> {
                            mod.autoModerator().clearQueue();
                            info(ctx.getSource(), "Очередь очищена");
                            return 1;
                        })))
                .then(ClientCommandManager.literal("cancel")
                        .executes(ctx -> cancelAll(mod, ctx.getSource()))
                        .then(ClientCommandManager.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    int id = IntegerArgumentType.getInteger(ctx, "id");
                                    boolean ok = mod.autoModerator().cancel(id);
                                    info(ctx.getSource(), ok
                                            ? "Отложенная выдача #" + id + " снята"
                                            : "Нечего отменять: #" + id + " уже отправлено или не найдено");
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("pending")
                        .executes(ctx -> pendingList(mod, ctx.getSource())))
                .then(ClientCommandManager.literal("undo")
                        .executes(ctx -> undo(mod, ctx.getSource(), 1))
                        .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 32))
                                .executes(ctx -> undo(mod, ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "count")))));
    }

    private static int punish(TrChatMod mod, int id) {
        FlaggedMessage message = mod.store().get(id);
        Minecraft client = Minecraft.getInstance();
        if (message == null) {
            chat(Component.literal("Сообщение #" + id + " уже не в памяти — выдай мут вручную.")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            return 0;
        }
        boolean instant = mod.config().ctrlClickInstantMute && client.hasControlDown();
        if (instant || !mod.config().confirmBeforeMute) {
            mod.executor().muteRecommended(message);
            return 1;
        }
        client.execute(() -> client.setScreen(new PunishScreen(client.screen, message)));
        return 1;
    }

    private static int test(TrChatMod mod, FabricClientCommandSource source, String text) {
        ScanResult result = mod.engine().scan(text, null, null, System.currentTimeMillis());
        if (result.isClean()) {
            info(source, "Нарушений не найдено.");
            return 1;
        }
        info(source, "Найдено нарушений: " + result.violations().size());
        for (Violation v : result.violations()) {
            source.sendFeedback(Component.literal("  " + v.ruleId() + " " + v.ruleName()
                            + " — мут " + v.mute().display()
                            + (v.evidence().isEmpty() ? "" : " — " + String.join(", ", v.evidence())))
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
        }
        return 1;
    }

    /** Показывает, как мод разбирает строку чата — этим настраиваются chatPatterns. */
    private static int parse(TrChatMod mod, FabricClientCommandSource source, String line) {
        var parsed = mod.parser().parse(TextNormalizer.stripFormatting(line));
        info(source, "Разбор строки:");
        source.sendFeedback(grey("  ник:   " + (parsed.nick() == null ? "НЕ НАЙДЕН" : parsed.nick())));
        source.sendFeedback(grey("  текст: " + parsed.body()));
        if (parsed.nick() == null) {
            source.sendFeedback(Component.literal("  сообщение без ника не проверяется "
                            + "(requireKnownSender: true)")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW)));
            return 1;
        }
        return test(mod, source, parsed.body());
    }

    private static int list(TrChatMod mod, FabricClientCommandSource source) {
        var recent = mod.store().recent(10);
        if (recent.isEmpty()) {
            info(source, "Помеченных сообщений нет.");
            return 1;
        }
        info(source, "Последние помеченные сообщения:");
        for (FlaggedMessage m : recent) {
            Violation primary = m.result().primary();
            source.sendFeedback(Component.literal("  #" + m.id() + " " + m.nickOrUnknown()
                            + " — " + (primary == null ? "?" : primary.shortLabel())
                            + " — " + shorten(m.body()))
                    .withStyle(Style.EMPTY
                            .withColor(ChatFormatting.RED)
                            .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(
                                    "/" + TrChatMod.COMMAND + " punish " + m.id()))));
        }
        return 1;
    }

    /** Ники игроков онлайн — для подсказок при вводе команды. */
    private static final SuggestionProvider<FabricClientCommandSource> ONLINE_PLAYERS =
            (ctx, builder) -> suggest(onlineNames(), builder);

    private static final SuggestionProvider<FabricClientCommandSource> IGNORED_PLAYERS =
            (ctx, builder) -> suggest(TrChatMod.get().config().ignoredNicks, builder);

    private static CompletableFuture<Suggestions> suggest(List<String> names, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(names, builder);
    }

    private static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            for (PlayerInfo info : connection.getOnlinePlayers()) {
                String name = info.getProfile().name();
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** Добавляет игрока в игнор — мод перестаёт его проверять совсем. */
    public static int ignoreAdd(TrChatMod mod, FabricClientCommandSource source, String nick) {
        String clean = nick == null ? "" : nick.strip();
        if (clean.isEmpty()) {
            info(source, "Не указан ник.");
            return 0;
        }
        boolean already = mod.config().ignoredNicks.stream()
                .anyMatch(n -> n.equalsIgnoreCase(clean));
        if (already) {
            info(source, clean + " уже в игнор-листе");
            return 1;
        }
        mod.config().ignoredNicks.add(clean);
        mod.configManager().saveConfig();
        source.sendFeedback(Component.literal("[ChatMod] ")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD))
                .append(Component.literal(clean + " в игноре — его сообщения больше не проверяются ")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)))
                .append(Component.literal("[вернуть]")
                        .withStyle(Style.EMPTY
                                .withColor(ChatFormatting.AQUA)
                                .withUnderlined(true)
                                .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(
                                        "/" + TrChatMod.COMMAND + " ignore remove " + clean)))));
        return 1;
    }

    private static int ignoreRemove(TrChatMod mod, FabricClientCommandSource source, String nick) {
        boolean removed = mod.config().ignoredNicks.removeIf(n -> n.equalsIgnoreCase(nick.strip()));
        mod.configManager().saveConfig();
        info(source, removed
                ? nick + " убран из игнор-листа — снова проверяется"
                : nick + " и не был в игнор-листе");
        return 1;
    }

    private static int ignoreList(TrChatMod mod, FabricClientCommandSource source) {
        List<String> list = mod.config().ignoredNicks;
        if (list.isEmpty()) {
            info(source, "Игнор-лист пуст. Добавить: /" + TrChatMod.COMMAND + " ignore add <ник>");
            return 1;
        }
        info(source, "В игноре: " + list.size());
        for (String nick : list) {
            source.sendFeedback(Component.literal("  " + nick + " ")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE))
                    .append(Component.literal("[вернуть]")
                            .withStyle(Style.EMPTY
                                    .withColor(ChatFormatting.AQUA)
                                    .withUnderlined(true)
                                    .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(
                                            "/" + TrChatMod.COMMAND + " ignore remove " + nick)))));
        }
        return 1;
    }

    private static int setDryRun(TrChatMod mod, FabricClientCommandSource source, boolean dry) {
        mod.config().autoDryRun = dry;
        mod.configManager().saveConfig();
        source.sendFeedback(Component.literal("[ChatMod] ")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD))
                .append(dry
                        ? Component.literal("Тестовый режим ВКЛЮЧЁН — команды не отправляются")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW))
                        : Component.literal("Тестовый режим выключен — автомат наказывает по-настоящему")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN))));
        return 1;
    }

    private static int setDelay(TrChatMod mod, FabricClientCommandSource source, int seconds) {
        mod.config().autoDelaySeconds = seconds;
        mod.configManager().saveConfig();
        info(source, seconds == 0
                ? "Пауза выключена — автомат выдаёт сразу"
                : "Пауза перед выдачей: " + seconds + " сек (за это время модератор может выдать сам)");
        return 1;
    }

    private static int autoStatus(TrChatMod mod, FabricClientCommandSource source) {
        var auto = mod.autoModerator();
        var config = mod.config();
        info(source, "Автомат: " + (auto.isActive() ? "ВКЛЮЧЁН" : "выключен")
                + (auto.isDryRun() ? " (тестовый режим)" : ""));
        source.sendFeedback(grey("  потолок: " + config.autoMaxMute
                + " — всё строже уходит в очередь"));
        source.sendFeedback(grey("  пауза перед выдачей: " + config.autoDelaySeconds
                + " сек, ждёт сейчас: " + auto.pending().size()));
        source.sendFeedback(grey("  лимит: " + config.autoMaxPerMinute + "/мин, кулдаун на игрока: "
                + config.autoCooldownSeconds + " сек"));
        source.sendFeedback(grey("  выдано: " + auto.issuedCount()
                + ", пропущено: " + auto.skippedCount()
                + ", в очереди: " + auto.queueSize()));
        return 1;
    }

    private static int cancelAll(TrChatMod mod, FabricClientCommandSource source) {
        int n = 0;
        for (var p : mod.autoModerator().pending()) {
            if (mod.autoModerator().cancel(p.message().id())) {
                n++;
            }
        }
        info(source, n == 0 ? "Отложенных выдач нет." : "Снято отложенных выдач: " + n);
        return 1;
    }

    private static int pendingList(TrChatMod mod, FabricClientCommandSource source) {
        var list = mod.autoModerator().pending();
        if (list.isEmpty()) {
            info(source, "Отложенных выдач нет.");
            return 1;
        }
        long now = System.currentTimeMillis();
        info(source, "Ждут отправки: " + list.size());
        for (var p : list) {
            source.sendFeedback(Component.literal("  " + p.nick() + " — " + p.ruleLabel()
                            + " — через " + p.secondsLeft(now) + " сек ")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE))
                    .append(Component.literal("[отменить]")
                            .withStyle(Style.EMPTY
                                    .withColor(ChatFormatting.AQUA)
                                    .withUnderlined(true)
                                    .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand(
                                            "/" + TrChatMod.COMMAND + " cancel " + p.message().id())))));
        }
        return 1;
    }

    private static int undo(TrChatMod mod, FabricClientCommandSource source, int count) {
        int done = mod.autoModerator().undo(count);
        info(source, done == 0
                ? "Нечего отменять."
                : "Снято автомутов: " + done);
        return 1;
    }

    private static int help(FabricClientCommandSource source) {
        info(source, "Команды:");
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " auto [on|off|dry] — автомат (кнопка F8)"));
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " queue [clear] — очередь на ручную проверку (F9)"));
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " auto delay <сек> — пауза перед выдачей"));
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " pending — что автомат сейчас держит на паузе"));
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " cancel [id] — снять отложенную выдачу"));
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " undo [n] — снять последние автомуты"));
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " reload — перечитать config.json и rules.json"));
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " toggle — включить/выключить проверку"));
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " test <текст> — прогнать текст через правила"));
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " parse <строка> — как мод разбирает строку чата"));
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " list — последние помеченные сообщения"));
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " ignore add|remove|list <ник> — не проверять игрока"));
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " proof — открыть папку протоколов наказаний"));
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " config — путь к файлам настроек"));
        return 1;
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static Component grey(String text) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY));
    }

    private static void info(FabricClientCommandSource source, String text) {
        source.sendFeedback(Component.literal("[ChatMod] ")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD))
                .append(Component.literal(text).withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE))));
    }

    private static void chat(Component component) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui != null) {
            client.gui.getChat().addMessage(Component.literal("[ChatMod] ")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD))
                    .append(component));
        }
    }
}
