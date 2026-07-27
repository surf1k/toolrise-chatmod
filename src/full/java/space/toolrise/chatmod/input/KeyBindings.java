package space.toolrise.chatmod.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import space.toolrise.chatmod.TrChatMod;
import space.toolrise.chatmod.Variant;

/**
 * Кнопка полной версии — переключатель автомата: нажал и ушёл.
 */
public final class KeyBindings {

    public static final String TOGGLE_AUTO_KEY = "key.trchatmod.toggle_auto";

    private KeyBindings() {
    }

    public static void register(TrChatMod mod) {
        KeyMapping toggleAuto = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                TOGGLE_AUTO_KEY,
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_F8,
                KeyMapping.Category.MULTIPLAYER));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleAuto.consumeClick()) {
                Variant.autoModerator().toggle();
            }
        });
    }
}
