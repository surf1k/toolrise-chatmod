package space.toolrise.chatmod.rules;

/** Встроенные проверки, которые нельзя выразить списком фраз. */
public enum Detector {
    /** Капс: доля заглавных букв выше порога. */
    CAPS,
    /** 8+ одинаковых символов подряд. */
    REPEAT_CHARS,
    /** Одно и то же сообщение от игрока N раз за окно времени. */
    SPAM_REPEAT,
    /** Слишком много сообщений от игрока за окно времени. */
    FLOOD_RATE,
    /** Ссылка на сторонний сервер / IP. */
    AD_THIRD_PARTY,
    /** Ссылка на свой канал (YouTube, Twitch, TikTok, Telegram и т.п.). */
    AD_OWN_CHANNEL;

    public static Detector fromString(String s) {
        if (s == null) {
            return null;
        }
        try {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
