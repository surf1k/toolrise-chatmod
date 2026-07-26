package space.toolrise.chatmod.chat;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import space.toolrise.chatmod.TrChatMod;
import space.toolrise.chatmod.config.ModConfig;
import space.toolrise.chatmod.rules.ScanResult;
import space.toolrise.chatmod.text.NickMasker;
import space.toolrise.chatmod.text.TextNormalizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Подписка на входящие сообщения чата и их подсветка.
 *
 * <p>Чат серверных плагинов приходит как системное сообщение — его можно
 * переписать «на лету» через {@code MODIFY_GAME}. Подписанный ванильный чат
 * так изменить нельзя, поэтому он либо отменяется и добавляется заново уже
 * красным, либо остаётся как есть с отдельной строкой-предупреждением.
 */
public final class ChatHandler {

    private final TrChatMod mod;
    private Component pendingReplacement;

    public ChatHandler(TrChatMod mod) {
        this.mod = mod;
    }

    public void register() {
        ClientReceiveMessageEvents.MODIFY_GAME.register(this::onGameMessage);
        ClientReceiveMessageEvents.ALLOW_CHAT.register(this::onAllowChat);
        ClientReceiveMessageEvents.CHAT_CANCELED.register(this::onChatCanceled);
        ClientReceiveMessageEvents.CHAT.register(this::onChat);
    }

    // ------------------------------------------------------- системные сообщения

    private Component onGameMessage(Component message, boolean overlay) {
        ModConfig config = mod.config();
        if (overlay || !config.enabled || !config.scanSystemMessages) {
            return message;
        }
        try {
            String plain = ChatDecorator.plainText(message);
            FlaggedMessage flagged = analyze(plain, null);
            if (flagged == null) {
                return message;
            }
            notifyFound();
            return ChatDecorator.decorate(message, flagged, config);
        } catch (Exception e) {
            TrChatMod.LOGGER.error("Ошибка при проверке системного сообщения", e);
            return message;
        }
    }

    // ---------------------------------------------------------- подписанный чат

    private boolean onAllowChat(Component message,
                                net.minecraft.network.chat.PlayerChatMessage signedMessage,
                                GameProfile sender,
                                net.minecraft.network.chat.ChatType.Bound params,
                                java.time.Instant receptionTimestamp) {
        ModConfig config = mod.config();
        pendingReplacement = null;
        if (!config.enabled || !config.scanPlayerChat || !config.replacePlayerChat) {
            return true;
        }
        try {
            String plain = ChatDecorator.plainText(message);
            FlaggedMessage flagged = analyze(plain, sender == null ? null : sender.name());
            if (flagged == null) {
                return true;
            }
            pendingReplacement = ChatDecorator.decorate(message, flagged, config);
            notifyFound();
            return false;
        } catch (Exception e) {
            TrChatMod.LOGGER.error("Ошибка при проверке сообщения игрока", e);
            pendingReplacement = null;
            return true;
        }
    }

    private void onChatCanceled(Component message,
                                net.minecraft.network.chat.PlayerChatMessage signedMessage,
                                GameProfile sender,
                                net.minecraft.network.chat.ChatType.Bound params,
                                java.time.Instant receptionTimestamp) {
        Component replacement = pendingReplacement;
        pendingReplacement = null;
        if (replacement == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.gui != null) {
            client.gui.getChat().addMessage(replacement);
        }
    }

    private void onChat(Component message,
                        net.minecraft.network.chat.PlayerChatMessage signedMessage,
                        GameProfile sender,
                        net.minecraft.network.chat.ChatType.Bound params,
                        java.time.Instant receptionTimestamp) {
        ModConfig config = mod.config();
        if (!config.enabled || !config.scanPlayerChat || config.replacePlayerChat) {
            return;
        }
        try {
            String plain = ChatDecorator.plainText(message);
            FlaggedMessage flagged = analyze(plain, sender == null ? null : sender.name());
            if (flagged == null) {
                return;
            }
            notifyFound();
            Minecraft client = Minecraft.getInstance();
            if (client.gui != null) {
                client.gui.getChat().addMessage(ChatDecorator.warningLine(flagged, config));
            }
        } catch (Exception e) {
            TrChatMod.LOGGER.error("Ошибка при проверке сообщения игрока", e);
        }
    }

    // ------------------------------------------------------------------ разбор

    /** @return помеченное сообщение или {@code null}, если нарушений нет либо проверять не нужно. */
    private FlaggedMessage analyze(String plain, String senderHint) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        ModConfig config = mod.config();
        String clean = TextNormalizer.stripFormatting(plain);

        // Даже чистая строка может быть объявлением «X замучен» — по нему
        // автомат снимает свою отложенную выдачу.
        if (mod.autoModerator() != null) {
            mod.autoModerator().observeChatLine(clean);
        }

        ChatParser.Parsed parsed = mod.parser().parse(clean);
        String nick = senderHint != null && !senderHint.isBlank() ? senderHint : parsed.nick();
        String body = parsed.body();
        int bodyStart = parsed.bodyStart();

        // Ник знаем от сервера, а шаблон не сработал — отрежем часть до ника вручную.
        if (senderHint != null && parsed.nick() == null) {
            int idx = clean.indexOf(senderHint);
            if (idx >= 0) {
                int from = idx + senderHint.length();
                while (from < clean.length() && isSeparator(clean.charAt(from))) {
                    from++;
                }
                if (from < clean.length()) {
                    body = clean.substring(from);
                    bodyStart = from;
                }
            }
        }

        // Не удалось понять, кто это сказал — значит и наказывать некого.
        // Так отсекаются рамки, объявления и реклама сервера.
        if (config.requireKnownSender && (nick == null || nick.isBlank())) {
            return null;
        }
        if (isIgnored(nick, config)) {
            return null;
        }
        // Ранг стоит в строке чата перед ником — по нему видно состав.
        // Список ников в rules.json устаревает, ранг — нет.
        if (RankPolicy.isStaff(parsed.prefix(), config)) {
            return null;
        }

        long now = System.currentTimeMillis();
        if (nick != null && !nick.isBlank()) {
            String normalized = TextNormalizer
                    .normalize(body, TextNormalizer.Alphabet.CYRILLIC, TextNormalizer.Mode.SQUEEZE)
                    .value();
            mod.history().record(nick, normalized, now);
        }

        // Чужие ники из текста убираем: за ник с матом отвечает его владелец
        // по пункту 3.7, а не тот, кто к игроку обратился.
        String scanned = config.ignoreNicknamesInText ? maskOnlineNicks(body) : body;

        ScanResult result = mod.engine().scan(scanned, nick, mod.history(), now);
        if (result.isClean()) {
            return null;
        }

        int id = mod.store().nextId();
        FlaggedMessage flagged = new FlaggedMessage(
                id, nick, isOnline(nick), body, bodyStart, clean, parsed.prefix(), result, now);
        mod.store().put(flagged);
        if (mod.autoModerator() != null) {
            mod.autoModerator().handle(flagged);
        }
        return flagged;
    }

    private boolean isIgnored(String nick, ModConfig config) {
        if (nick == null || nick.isBlank()) {
            return false;
        }
        String lower = nick.toLowerCase(Locale.ROOT);

        if (config.ignoreOwnMessages) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null
                    && client.player.getGameProfile().name().toLowerCase(Locale.ROOT).equals(lower)) {
                return true;
            }
        }
        for (String ignored : config.ignoredNicks) {
            if (ignored != null && ignored.toLowerCase(Locale.ROOT).equals(lower)) {
                return true;
            }
        }
        if (config.ignoreStaff) {
            List<String> staff = mod.configManager().ruleData().groups.get("staffNicks");
            if (staff != null) {
                for (String s : staff) {
                    if (s != null && s.toLowerCase(Locale.ROOT).equals(lower)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Заменяет ники подключённых игроков на заглушки той же длины. */
    private static String maskOnlineNicks(String body) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return body;
        }
        List<String> names = new ArrayList<>();
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            String name = info.getProfile().name();
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return NickMasker.mask(body, names);
    }

    private static boolean isOnline(String nick) {
        if (nick == null || nick.isBlank()) {
            return false;
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.getPlayerInfoIgnoreCase(nick) != null;
    }

    private static boolean isSeparator(char c) {
        return Character.isWhitespace(c) || c == ':' || c == '>' || c == '»' || c == '-' || c == '|';
    }

    private void notifyFound() {
        if (!mod.config().playSound) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.getSoundManager() != null) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.4F));
        }
    }
}
