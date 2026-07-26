package space.toolrise.chatmod.text;

import java.util.Collection;
import java.util.Locale;

/**
 * Закрывает ники игроков в тексте сообщения перед проверкой правил.
 *
 * <p>Ник вроде {@code Dj_pIdOrAs} содержит мат, но говорит его не тот, кто к
 * игроку обратился. За такой ник отвечает его владелец по пункту 3.7 (бан), а
 * собеседник не виноват — значит, из проверяемого текста ник надо убрать.
 *
 * <p>Ник заменяется на такое же количество символов-заглушек, чтобы позиции
 * остального текста не сдвинулись: по ним подсвечивается нарушение в чате.
 */
public final class NickMasker {

    /**
     * Заглушка — пробел. Любой другой символ пришлось бы отдельно исключать
     * из детектора повторов: десять одинаковых знаков подряд сами по себе
     * нарушение по 2.8. Пробелы же и нормализатор схлопывает, и детектор
     * повторов пропускает. Пользователь заглушку не видит: подсветка и журнал
     * работают с исходным текстом.
     */
    private static final char MASK = ' ';

    private static final int MIN_NICK_LENGTH = 3;

    private NickMasker() {
    }

    public static String mask(String body, Collection<String> nicks) {
        if (body == null || body.isEmpty() || nicks == null || nicks.isEmpty()) {
            return body == null ? "" : body;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        char[] out = body.toCharArray();
        boolean changed = false;

        for (String nick : nicks) {
            if (nick == null || nick.length() < MIN_NICK_LENGTH) {
                continue;
            }
            String needle = nick.toLowerCase(Locale.ROOT);
            int from = 0;
            while (true) {
                int at = lower.indexOf(needle, from);
                if (at < 0) {
                    break;
                }
                // Ник должен быть отдельным словом, а не куском другого.
                if (!isNickChar(charAt(lower, at - 1)) && !isNickChar(charAt(lower, at + needle.length()))) {
                    for (int i = at; i < at + needle.length(); i++) {
                        out[i] = MASK;
                    }
                    changed = true;
                }
                from = at + needle.length();
            }
        }
        return changed ? new String(out) : body;
    }

    private static char charAt(String s, int index) {
        return index < 0 || index >= s.length() ? ' ' : s.charAt(index);
    }

    /** Символы, из которых состоят ники Minecraft. */
    private static boolean isNickChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
