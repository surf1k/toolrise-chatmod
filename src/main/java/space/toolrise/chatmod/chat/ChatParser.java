package space.toolrise.chatmod.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Разбирает строку чата на ник отправителя и тело сообщения.
 *
 * <p>Формат чата задаёт серверный плагин, поэтому шаблоны лежат в конфиге и
 * проверяются по порядку. Если ни один не подошёл — проверяется вся строка,
 * а ник остаётся неизвестным (его можно будет ввести руками в окне выдачи мута).
 */
public final class ChatParser {

    /**
     * @param nick      ник отправителя или {@code null}
     * @param body      текст сообщения
     * @param bodyStart смещение тела внутри исходной строки — нужно для точной подсветки
     */
    public record Parsed(String nick, String body, int bodyStart) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatParser.class);

    private final List<Pattern> patterns = new ArrayList<>();

    public ChatParser(List<String> rawPatterns) {
        for (String raw : rawPatterns) {
            try {
                patterns.add(Pattern.compile(raw, Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS));
            } catch (PatternSyntaxException e) {
                LOGGER.warn("Шаблон чата не скомпилирован: {} ({})", raw, e.getDescription());
            }
        }
    }

    public Parsed parse(String plainLine) {
        if (plainLine == null || plainLine.isEmpty()) {
            return new Parsed(null, "", 0);
        }
        for (Pattern p : patterns) {
            Matcher m = p.matcher(plainLine);
            if (!m.matches()) {
                continue;
            }
            String nick = group(m, "nick");
            String msg = group(m, "msg");
            if (msg == null || msg.isBlank()) {
                continue;
            }
            int start = -1;
            try {
                start = m.start("msg");
            } catch (IllegalArgumentException ignored) {
                // группы msg нет — оставим 0
            }
            return new Parsed(nick, msg, Math.max(start, 0));
        }
        return new Parsed(null, plainLine, 0);
    }

    private static String group(Matcher m, String name) {
        try {
            return m.group(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
