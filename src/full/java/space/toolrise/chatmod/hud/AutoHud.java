package space.toolrise.chatmod.hud;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import space.toolrise.chatmod.TrChatMod;
import space.toolrise.chatmod.Variant;
import space.toolrise.chatmod.auto.AutoModerator;

/**
 * Индикатор автомата в углу экрана. Нужен ровно для одного: чтобы нельзя было
 * случайно оставить автовыдачу мутов включённой и не заметить этого.
 */
public final class AutoHud {

    private static final Identifier ID = Identifier.fromNamespaceAndPath(TrChatMod.MOD_ID, "auto_status");

    private static final int COLOR_ON = 0xFF55FF55;
    private static final int COLOR_DRY = 0xFFFFFF55;
    private static final int COLOR_DIM = 0xFFAAAAAA;
    private static final int BACKGROUND = 0x90000000;

    private AutoHud() {
    }

    public static void register(TrChatMod mod) {
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, ID,
                (context, tickCounter) -> render(mod, context));
    }

    private static void render(TrChatMod mod, GuiGraphics context) {
        AutoModerator auto = Variant.autoModerator();
        if (auto == null || !auto.isActive()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.options.hideGui || client.screen != null && client.screen.isPauseScreen()) {
            return;
        }

        Font font = client.font;
        boolean dry = auto.isDryRun();
        Component title = Component.literal(dry ? "● АВТОМАТ (ТЕСТ)" : "● АВТОМАТ");
        int waiting = auto.pendingCount();
        Component stats = Component.literal("выдано " + auto.issuedCount()
                + (waiting > 0 ? " · ждёт " + waiting : ""));

        int width = Math.max(font.width(title), font.width(stats));
        int x = context.guiWidth() - width - 6;
        int y = 4;

        context.fill(x - 3, y - 2, x + width + 3, y + 20, BACKGROUND);
        context.drawString(font, title, x, y, dry ? COLOR_DRY : COLOR_ON);
        context.drawString(font, stats, x, y + 10, COLOR_DIM);
    }
}
