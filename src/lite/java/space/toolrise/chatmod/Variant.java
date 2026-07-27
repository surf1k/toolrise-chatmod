package space.toolrise.chatmod;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import space.toolrise.chatmod.auto.AutoModeration;
import space.toolrise.chatmod.evidence.Evidence;

/**
 * Лёгкая версия: подсветка нарушения, звук, подсказка с правилом и сроком,
 * мут по клику. Всё остальное — автомат, очередь, протоколы, игнор-лист,
 * клавиши, индикатор на экране — сюда не компилируется вообще.
 */
public final class Variant {

    /** Есть ли в этой сборке всё, что сложнее подсветки и мута по клику. */
    public static final boolean FULL = false;

    public static final String NAME = "лёгкая";

    private Variant() {
    }

    public static AutoModeration auto(TrChatMod mod) {
        return null;
    }

    public static Evidence evidence(TrChatMod mod) {
        return null;
    }

    public static void registerExtras(TrChatMod mod) {
    }

    public static void extendCommands(LiteralArgumentBuilder<FabricClientCommandSource> root, TrChatMod mod) {
    }

    public static int help(FabricClientCommandSource source) {
        source.sendFeedback(TrChatMod.prefix()
                .append(Component.literal("лёгкая версия: клик по красному сообщению — мут")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE))));
        source.sendFeedback(Component.literal("  ЛКМ — окно выдачи, Ctrl+ЛКМ — сразу, Shift+ЛКМ — ник в чат")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
        return 1;
    }

    public static String statusSuffix(TrChatMod mod) {
        return "";
    }
}
