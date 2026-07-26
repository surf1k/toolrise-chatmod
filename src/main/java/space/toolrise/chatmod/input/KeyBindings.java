package space.toolrise.chatmod.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import space.toolrise.chatmod.TrChatMod;

/**
 * Кнопки мода. Главная — переключатель автомата: нажал и ушёл.
 */
public final class KeyBindings {

    public static final String TOGGLE_AUTO_KEY = "key.trchatmod.toggle_auto";
    public static final String OPEN_QUEUE_KEY = "key.trchatmod.open_queue";

    private static KeyMapping toggleAuto;
    private static KeyMapping openQueue;

    private KeyBindings() {
    }

    public static void register(TrChatMod mod) {
        toggleAuto = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                TOGGLE_AUTO_KEY,
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_F8,
                KeyMapping.Category.MULTIPLAYER));

        openQueue = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                OPEN_QUEUE_KEY,
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_F9,
                KeyMapping.Category.MULTIPLAYER));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleAuto.consumeClick()) {
                mod.autoModerator().toggle();
            }
            while (openQueue.consumeClick()) {
                mod.showQueue();
            }
        });
    }

    public static KeyMapping toggleAuto() {
        return toggleAuto;
    }
}
