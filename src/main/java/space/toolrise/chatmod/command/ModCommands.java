package space.toolrise.chatmod.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import space.toolrise.chatmod.TrChatMod;
import space.toolrise.chatmod.Variant;
import space.toolrise.chatmod.chat.FlaggedMessage;
import space.toolrise.chatmod.gui.PunishScreen;

/**
 * Общая часть команд. Здесь только {@code punish <id>} — именно её вызывает клик
 * по подсвеченному сообщению, без неё мод бесполезен. Всё остальное добавляет
 * полная версия через {@code Variant.extendCommands}.
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
        LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommandManager.literal(name)
                .executes(ctx -> Variant.help(ctx.getSource()))
                .then(ClientCommandManager.literal("punish")
                        .then(ClientCommandManager.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> punish(mod, IntegerArgumentType.getInteger(ctx, "id")))));
        Variant.extendCommands(root, mod);
        return root;
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

    public static Component grey(String text) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY));
    }

    public static void info(FabricClientCommandSource source, String text) {
        source.sendFeedback(TrChatMod.prefix()
                .append(Component.literal(text).withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE))));
    }

    private static void chat(Component component) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui != null) {
            client.gui.getChat().addMessage(TrChatMod.prefix().append(component));
        }
    }
}
