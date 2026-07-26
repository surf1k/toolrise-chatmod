package space.toolrise.chatmod.chat;

import space.toolrise.chatmod.rules.ScanResult;

/**
 * Помеченное сообщение, по которому можно кликнуть.
 *
 * @param id        номер, который зашивается в ClickEvent
 * @param nick      ник нарушителя (может быть {@code null}, если распознать не удалось)
 * @param nickKnown ник подтверждён списком игроков онлайн
 * @param body      текст сообщения без префиксов
 * @param bodyStart смещение тела внутри {@code fullLine}
 * @param fullLine  вся строка чата как она пришла (без кодов форматирования)
 * @param result    что нашёл движок правил
 * @param time      момент получения, мс
 */
public record FlaggedMessage(int id,
                             String nick,
                             boolean nickKnown,
                             String body,
                             int bodyStart,
                             String fullLine,
                             ScanResult result,
                             long time) {

    public String nickOrUnknown() {
        return nick == null || nick.isBlank() ? "?" : nick;
    }
}
