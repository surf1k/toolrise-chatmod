package space.toolrise.chatmod.rules;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Короткая история чата по игрокам. Нужна детекторам спама и флуда:
 * одно сообщение само по себе о них ничего не говорит.
 */
public final class MessageHistory {

    private record Entry(long timeMillis, String normalized) {
    }

    private static final int MAX_PER_PLAYER = 32;
    private static final long FORGET_AFTER_MILLIS = 5 * 60_000L;

    private final Map<String, Deque<Entry>> byPlayer = new HashMap<>();

    public synchronized void record(String sender, String normalizedBody, long nowMillis) {
        if (sender == null || sender.isBlank()) {
            return;
        }
        Deque<Entry> deque = byPlayer.computeIfAbsent(key(sender), k -> new ArrayDeque<>());
        deque.addLast(new Entry(nowMillis, normalizedBody));
        while (deque.size() > MAX_PER_PLAYER) {
            deque.removeFirst();
        }
        cleanup(nowMillis);
    }

    /** Сколько раз ровно это сообщение встречалось за окно (включая текущее). */
    public synchronized int repeatCount(String sender, String normalizedBody, int windowSeconds, long nowMillis) {
        Deque<Entry> deque = byPlayer.get(key(sender));
        if (deque == null) {
            return 0;
        }
        long from = nowMillis - windowSeconds * 1000L;
        int n = 0;
        for (Entry e : deque) {
            if (e.timeMillis() >= from && e.normalized().equals(normalizedBody)) {
                n++;
            }
        }
        return n;
    }

    /** Сколько всего сообщений от игрока за окно (включая текущее). */
    public synchronized int messageCount(String sender, int windowSeconds, long nowMillis) {
        Deque<Entry> deque = byPlayer.get(key(sender));
        if (deque == null) {
            return 0;
        }
        long from = nowMillis - windowSeconds * 1000L;
        int n = 0;
        for (Entry e : deque) {
            if (e.timeMillis() >= from) {
                n++;
            }
        }
        return n;
    }

    public synchronized void clear() {
        byPlayer.clear();
    }

    private void cleanup(long nowMillis) {
        long from = nowMillis - FORGET_AFTER_MILLIS;
        byPlayer.values().forEach(d -> {
            while (!d.isEmpty() && d.peekFirst().timeMillis() < from) {
                d.removeFirst();
            }
        });
        byPlayer.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    private static String key(String sender) {
        return sender.toLowerCase(Locale.ROOT);
    }
}
