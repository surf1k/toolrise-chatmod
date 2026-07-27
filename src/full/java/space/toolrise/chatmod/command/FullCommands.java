package space.toolrise.chatmod.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Util;
import space.toolrise.chatmod.TrChatMod;
import space.toolrise.chatmod.Variant;
import space.toolrise.chatmod.auto.AutoModerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Команды полной версии: автомат, пауза, игнор-лист и папка протоколов. */
public final class FullCommands {

    private FullCommands() {
    }

    public static void extend(LiteralArgumentBuilder<FabricClientCommandSource> root, TrChatMod mod) {
        root
                .then(ClientCommandManager.literal("auto")
                        .executes(ctx -> autoStatus(mod, ctx.getSource()))
                        .then(ClientCommandManager.literal("on").executes(ctx -> {
                            auto().setActive(true);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("off").executes(ctx -> {
                            auto().setActive(false);
                            return 1;
                        }))
                        // «Работай по-настоящему» одной командой: снимает тест и включает автомат.
                        .then(ClientCommandManager.literal("live").executes(ctx -> {
                            setDryRun(mod, ctx.getSource(), false);
                            auto().setActive(true);
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("dry")
                                .executes(ctx -> setDryRun(mod, ctx.getSource(), !mod.config().autoDryRun))
                                .then(ClientCommandManager.literal("on")
                                        .executes(ctx -> setDryRun(mod, ctx.getSource(), true)))
                                .then(ClientCommandManager.literal("off")
                                        .executes(ctx -> setDryRun(mod, ctx.getSource(), false)))))
                .then(ClientCommandManager.literal("delay")
                        .executes(ctx -> {
                            ModCommands.info(ctx.getSource(), "Пауза перед выдачей: "
                                    + mod.config().autoDelaySeconds + " сек");
                            return 1;
                        })
                        .then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(0, 3600))
                                .executes(ctx -> setDelay(mod, ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "seconds")))))
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
                                    ModCommands.info(ctx.getSource(), "Игнор-лист очищен (" + n + ")");
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("proof")
                        .executes(ctx -> proof(mod, ctx.getSource())));
    }

    public static int help(FabricClientCommandSource source) {
        ModCommands.info(source, "Команды:");
        source.sendFeedback(ModCommands.grey("  /" + TrChatMod.COMMAND
                + " auto [on|off|live|dry] — автомат (кнопка F8)"));
        source.sendFeedback(ModCommands.grey("  /" + TrChatMod.COMMAND
                + " delay [сек] — пауза перед выдачей"));
        source.sendFeedback(ModCommands.grey("  /" + TrChatMod.COMMAND
                + " ignore add|remove|list|clear <ник> — не проверять игрока"));
        source.sendFeedback(ModCommands.grey("  /" + TrChatMod.COMMAND
                + " proof — открыть папку протоколов наказаний"));
        return 1;
    }

    /** Добавляет игрока в игнор — мод перестаёт его проверять совсем. */
    private static int ignoreAdd(TrChatMod mod, FabricClientCommandSource source, String nick) {
        String clean = nick == null ? "" : nick.strip();
        if (clean.isEmpty()) {
            ModCommands.info(source, "Не указан ник.");
            return 0;
        }
        boolean already = mod.config().ignoredNicks.stream().anyMatch(n -> n.equalsIgnoreCase(clean));
        if (already) {
            ModCommands.info(source, clean + " уже в игнор-листе");
            return 1;
        }
        mod.config().ignoredNicks.add(clean);
        mod.configManager().saveConfig();
        source.sendFeedback(TrChatMod.prefix()
                .append(Component.literal(clean + " в игноре — его сообщения больше не проверяются ")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)))
                .append(Component.literal("[вернуть]")
                        .withStyle(Style.EMPTY
                                .withColor(ChatFormatting.AQUA)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.RunCommand(
                                        "/" + TrChatMod.COMMAND + " ignore remove " + clean)))));
        return 1;
    }

    private static int ignoreRemove(TrChatMod mod, FabricClientCommandSource source, String nick) {
        boolean removed = mod.config().ignoredNicks.removeIf(n -> n.equalsIgnoreCase(nick.strip()));
        mod.configManager().saveConfig();
        ModCommands.info(source, removed
                ? nick + " убран из игнор-листа — снова проверяется"
                : nick + " и не был в игнор-листе");
        return 1;
    }

    private static int ignoreList(TrChatMod mod, FabricClientCommandSource source) {
        List<String> list = mod.config().ignoredNicks;
        if (list.isEmpty()) {
            ModCommands.info(source, "Игнор-лист пуст. Добавить: /" + TrChatMod.COMMAND + " ignore add <ник>");
            return 1;
        }
        ModCommands.info(source, "В игноре: " + list.size());
        for (String nick : list) {
            source.sendFeedback(Component.literal("  " + nick + " ")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE))
                    .append(Component.literal("[вернуть]")
                            .withStyle(Style.EMPTY
                                    .withColor(ChatFormatting.AQUA)
                                    .withUnderlined(true)
                                    .withClickEvent(new ClickEvent.RunCommand(
                                            "/" + TrChatMod.COMMAND + " ignore remove " + nick)))));
        }
        return 1;
    }

    private static int proof(TrChatMod mod, FabricClientCommandSource source) {
        if (mod.evidence() == null) {
            return 0;
        }
        Path dir = mod.evidence().directory();
        ModCommands.info(source, "Протоколы наказаний: " + dir);
        try {
            Files.createDirectories(dir);
            Util.getPlatform().openFile(dir.toFile());
        } catch (Exception e) {
            TrChatMod.LOGGER.warn("Папку протоколов открыть не удалось", e);
        }
        return 1;
    }

    private static int setDryRun(TrChatMod mod, FabricClientCommandSource source, boolean dry) {
        mod.config().autoDryRun = dry;
        mod.configManager().saveConfig();
        source.sendFeedback(TrChatMod.prefix()
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
        ModCommands.info(source, seconds == 0
                ? "Пауза выключена — автомат выдаёт сразу"
                : "Пауза перед выдачей: " + seconds + " сек (за это время модератор может выдать сам)");
        return 1;
    }

    private static int autoStatus(TrChatMod mod, FabricClientCommandSource source) {
        AutoModerator auto = auto();
        var config = mod.config();
        ModCommands.info(source, "Автомат: " + (auto.isActive() ? "ВКЛЮЧЁН" : "выключен")
                + (auto.isDryRun() ? " (тестовый режим)" : ""));
        source.sendFeedback(ModCommands.grey("  потолок: " + config.autoMaxMute
                + " — всё строже остаётся модератору"));
        source.sendFeedback(ModCommands.grey("  пауза перед выдачей: " + config.autoDelaySeconds
                + " сек, ждёт сейчас: " + auto.pendingCount()));
        source.sendFeedback(ModCommands.grey("  лимит: " + config.autoMaxPerMinute + "/мин, кулдаун на игрока: "
                + config.autoCooldownSeconds + " сек"));
        source.sendFeedback(ModCommands.grey("  выдано: " + auto.issuedCount()
                + ", пропущено: " + auto.skippedCount()));
        return 1;
    }

    private static AutoModerator auto() {
        return Variant.autoModerator();
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
}
