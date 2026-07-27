package space.toolrise.chatmod.punish;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import space.toolrise.chatmod.TrChatMod;
import space.toolrise.chatmod.chat.FlaggedMessage;
import space.toolrise.chatmod.chat.RankPolicy;
import space.toolrise.chatmod.config.ModConfig;
import space.toolrise.chatmod.evidence.Evidence;
import space.toolrise.chatmod.rules.MuteDuration;
import space.toolrise.chatmod.rules.Violation;

import java.nio.file.Path;

/**
 * Отправка команды наказания.
 *
 * <p>Команда уходит ровно один раз: либо по явному действию модератора, либо
 * по решению автомата, который включает сам модератор.
 */
public final class PunishmentExecutor {

    private final TrChatMod mod;

    public PunishmentExecutor(TrChatMod mod) {
        this.mod = mod;
    }

    /** Выдаёт мут по данным помеченного сообщения, беря рекомендованное правило. */
    public boolean muteRecommended(FlaggedMessage message) {
        Violation primary = message.result().primary();
        if (primary == null) {
            return false;
        }
        ModConfig config = mod.config();
        MuteDuration duration = RankPolicy.cap(primary.mute(), message.prefix(), config);
        String time = timeToken(duration, config);
        String reason = config.buildReason(primary.ruleId(), primary.ruleName(), "");
        return mute(message, message.nick(), time, reason, primary.shortLabel(), false);
    }

    public boolean mute(FlaggedMessage message, String nick, String time, String reason, String ruleInfo) {
        return mute(message, nick, time, reason, ruleInfo, false);
    }

    /**
     * Выдаёт мут заданными параметрами.
     *
     * @param message  исходное сообщение (для протокола), может быть {@code null}
     * @param nick     ник нарушителя
     * @param time     срок в том виде, в каком его ждёт серверная команда
     * @param reason   причина
     * @param ruleInfo что записать в протокол в колонку «правило»
     * @param auto     выдано автоматом, а не человеком — попадёт в протокол и в текст в чате
     */
    public boolean mute(FlaggedMessage message, String nick, String time, String reason,
                        String ruleInfo, boolean auto) {
        Minecraft client = Minecraft.getInstance();
        if (nick == null || nick.isBlank()) {
            feedback(Component.literal("Не указан ник — мут не выдан.")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            return false;
        }
        ClientPacketListener connection = client.getConnection();
        if (connection == null) {
            feedback(Component.literal("Нет подключения к серверу.")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            return false;
        }

        String command = mod.config().buildCommand(nick, time, reason);
        if (command.isBlank()) {
            feedback(Component.literal("Пустой шаблон команды в config.json.")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            return false;
        }
        if (command.startsWith("/")) {
            command = command.substring(1);
        }

        if (!auto && mod.auto() != null) {
            // Человек успел раньше — снимаем то, что автомат собирался выдать сам.
            mod.auto().cancelFor(nick, "модератор выдал вручную");
        }

        connection.sendCommand(command);
        TrChatMod.LOGGER.info("Отправлена команда ({}): /{}", auto ? "авто" : "вручную", command);

        feedback(Component.literal(auto ? "Автомут: " : "Мут выдан: ")
                .withStyle(Style.EMPTY.withColor(auto ? ChatFormatting.AQUA : ChatFormatting.GREEN))
                .append(Component.literal(nick).withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE)))
                .append(Component.literal(" — " + time + " — " + reason)
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))));

        Evidence evidence = mod.evidence();
        if (evidence != null) {
            Path proof = evidence.record(message, nick, time, reason, ruleInfo, auto, command);
            if (proof != null) {
                // Путь к папке игры зависит от лаунчера, поэтому не заставляем его искать:
                // рядом с протоколом всегда лежит кнопка, которая её открывает.
                feedback(Component.literal("протокол: " + proof.getFileName() + " ")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_GRAY))
                        .append(Component.literal("[открыть папку]")
                                .withStyle(Style.EMPTY
                                        .withColor(ChatFormatting.AQUA)
                                        .withUnderlined(true)
                                        .withClickEvent(new ClickEvent.RunCommand(
                                                "/" + TrChatMod.COMMAND + " proof")))));
            }
        }
        return true;
    }

    public static String timeToken(MuteDuration duration, ModConfig config) {
        return duration.isPermanent() ? config.permanentToken : duration.raw();
    }

    private static void feedback(Component component) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui != null) {
            client.gui.getChat().addMessage(TrChatMod.prefix().append(component));
        }
    }
}
