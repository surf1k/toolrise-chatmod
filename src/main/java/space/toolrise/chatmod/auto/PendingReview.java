package space.toolrise.chatmod.auto;

import space.toolrise.chatmod.chat.FlaggedMessage;

/**
 * Нарушение, которое автомат сознательно не стал наказывать сам.
 *
 * @param message исходное помеченное сообщение (по нему можно кликнуть в чате)
 * @param reason  почему автомат отказался — это видит человек в /trchatmod queue
 * @param time    когда это случилось
 */
public record PendingReview(FlaggedMessage message, String reason, long time) {
}
