package space.toolrise.chatmod.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import space.toolrise.chatmod.TrChatMod;
import space.toolrise.chatmod.chat.FlaggedMessage;
import space.toolrise.chatmod.gui.PunishScreen;
import space.toolrise.chatmod.rules.ScanResult;
import space.toolrise.chatmod.rules.Violation;
import space.toolrise.chatmod.text.TextNormalizer;

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
                        .then(ClientCommandManager.literal("dry").executes(ctx -> {
                            mod.config().autoDryRun = !mod.config().autoDryRun;
                            mod.configManager().saveConfig();
                            info(ctx.getSource(), "Тестовый режим автомата "
                                    + (mod.config().autoDryRun ? "включён — команды не отправляются" : "выключен"));
                            return 1;
                        })))
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

    private static int autoStatus(TrChatMod mod, FabricClientCommandSource source) {
        var auto = mod.autoModerator();
        var config = mod.config();
        info(source, "Автомат: " + (auto.isActive() ? "ВКЛЮЧЁН" : "выключен")
                + (auto.isDryRun() ? " (тестовый режим)" : ""));
        source.sendFeedback(grey("  потолок: " + config.autoMaxMute
                + " — всё строже уходит в очередь"));
        source.sendFeedback(grey("  лимит: " + config.autoMaxPerMinute + "/мин, кулдаун на игрока: "
                + config.autoCooldownSeconds + " сек"));
        source.sendFeedback(grey("  выдано: " + auto.issuedCount()
                + ", пропущено: " + auto.skippedCount()
                + ", в очереди: " + auto.queueSize()));
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
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " undo [n] — снять последние автомуты"));
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " reload — перечитать config.json и rules.json"));
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " toggle — включить/выключить проверку"));
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " test <текст> — прогнать текст через правила"));
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " parse <строка> — как мод разбирает строку чата"));
        source.sendFeedback(grey("  /" + TrChatMod.COMMAND + " list — последние помеченные сообщения"));
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
