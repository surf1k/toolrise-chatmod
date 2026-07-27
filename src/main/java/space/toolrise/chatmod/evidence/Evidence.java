package space.toolrise.chatmod.evidence;

import space.toolrise.chatmod.chat.FlaggedMessage;

import java.nio.file.Path;

/**
 * Доказательная часть: буфер чата, протокол на каждое наказание и журнал.
 * Реализация есть только в полной версии (см. {@code src/full}), в лёгкой
 * {@code TrChatMod.evidence()} возвращает {@code null}.
 */
public interface Evidence {

    /** Каждая строка чата — протоколу нужен контекст, а не только нарушения. */
    void observe(long time, String line, String nick);

    /** Корень протоколов внутри папки игры. */
    Path directory();

    /**
     * Сохраняет протокол по выданному наказанию и пишет строку в журнал.
     *
     * @return путь к файлу протокола или {@code null}
     */
    Path record(FlaggedMessage message, String nick, String time,
                String reason, String ruleInfo, boolean auto, String command);

    /** Смена сервера: буфер чата чужого сервера в протоколе не нужен. */
    void clear();
}
