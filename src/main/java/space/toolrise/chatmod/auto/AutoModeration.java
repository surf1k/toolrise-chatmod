package space.toolrise.chatmod.auto;

import space.toolrise.chatmod.chat.FlaggedMessage;

/**
 * То, что общий код знает про автомат. Реализация есть только в полной версии
 * (см. {@code src/full}), в лёгкой автомата нет вообще и {@code TrChatMod.auto()}
 * возвращает {@code null}.
 */
public interface AutoModeration {

    /** Вызывается на каждое помеченное сообщение. */
    void handle(FlaggedMessage message);

    /** Строка чата целиком: по объявлению «X замучен» автомат отходит в сторону. */
    void observeChatLine(String line);

    /** Человек выдал мут сам — снять то, что автомат держал на паузе для этого игрока. */
    void cancelFor(String nick, String why);

    /** Каждый тик клиента: отправить то, у чего вышла пауза. */
    void tick();

    /** Смена сервера: счётчики и отложенные выдачи сбрасываются. */
    void reset();
}
