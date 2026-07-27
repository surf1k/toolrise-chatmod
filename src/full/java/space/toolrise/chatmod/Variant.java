package space.toolrise.chatmod;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import space.toolrise.chatmod.auto.AutoModeration;
import space.toolrise.chatmod.auto.AutoModerator;
import space.toolrise.chatmod.command.FullCommands;
import space.toolrise.chatmod.evidence.Evidence;
import space.toolrise.chatmod.evidence.EvidenceRecorder;
import space.toolrise.chatmod.hud.AutoHud;
import space.toolrise.chatmod.input.KeyBindings;

/**
 * Полная версия: автомат, очередь на ручную проверку, протоколы наказаний,
 * игнор-лист. Лёгкая версия — тот же класс в {@code src/lite}, только пустой.
 *
 * <p>Больше файлов между сборками не различается: всё, чего в лёгкой версии нет,
 * лежит в {@code src/full} и в её jar просто не компилируется.
 */
public final class Variant {

    /** Есть ли в этой сборке всё, что сложнее подсветки и мута по клику. */
    public static final boolean FULL = true;

    public static final String NAME = "полная";

    private static AutoModerator auto;

    private Variant() {
    }

    public static AutoModeration auto(TrChatMod mod) {
        auto = new AutoModerator(mod);
        return auto;
    }

    /** Автомат конкретным типом — для HUD, клавиш и команд полной версии. */
    public static AutoModerator autoModerator() {
        return auto;
    }

    public static Evidence evidence(TrChatMod mod) {
        return new EvidenceRecorder(mod);
    }

    public static void registerExtras(TrChatMod mod) {
        KeyBindings.register(mod);
        AutoHud.register(mod);
    }

    public static void extendCommands(LiteralArgumentBuilder<FabricClientCommandSource> root, TrChatMod mod) {
        FullCommands.extend(root, mod);
    }

    public static int help(FabricClientCommandSource source) {
        return FullCommands.help(source);
    }

    /** Хвост строки состояния при заходе на сервер. */
    public static String statusSuffix(TrChatMod mod) {
        return " · автомат " + (mod.config().autoMode
                ? (mod.config().autoDryRun ? "ТЕСТ" : "ВКЛ")
                : "выкл");
    }
}
