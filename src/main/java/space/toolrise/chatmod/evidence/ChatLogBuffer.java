package space.toolrise.chatmod.evidence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Последние строки чата в памяти. Нужны для протокола наказания: одно
 * сообщение мало что доказывает, а за флуд нужно показать все повторы разом.
 */
public final class ChatLogBuffer {

    /**
     * @param time момент получения, мс
     * @param line строка чата без кодов форматирования
     * @param nick отправитель, если удалось распознать
     */
    public record Line(long time, String line, String nick) {
    }

    private final Deque<Line> lines = new ArrayDeque<>();
    private int capacity;

    public ChatLogBuffer(int capacity) {
        this.capacity = Math.max(32, capacity);
    }

    public synchronized void setCapacity(int capacity) {
        this.capacity = Math.max(32, capacity);
        trim();
    }

    public synchronized void add(long time, String line, String nick) {
        if (line == null || line.isBlank()) {
            return;
        }
        lines.addLast(new Line(time, line, nick));
        trim();
    }

    /** Последние {@code count} строк, старые первыми — в порядке чтения. */
    public synchronized List<Line> tail(int count) {
        List<Line> all = new ArrayList<>(lines);
        int from = Math.max(0, all.size() - Math.max(1, count));
        return List.copyOf(all.subList(from, all.size()));
    }

    /**
     * Все строки конкретного игрока в окне {@code [now - windowSeconds, now]}.
     * Верхняя граница нужна, чтобы в протокол не попало то, что сказано уже
     * после наказания: это не доказательство, а путаница.
     */
    public synchronized List<Line> byPlayer(String nick, int windowSeconds, long now) {
        List<Line> out = new ArrayList<>();
        if (nick == null || nick.isBlank()) {
            return out;
        }
        String key = nick.toLowerCase(Locale.ROOT);
        long from = now - windowSeconds * 1000L;
        for (Line l : lines) {
            if (l.time() < from || l.time() > now) {
                continue;
            }
            boolean mine = l.nick() != null && l.nick().toLowerCase(Locale.ROOT).equals(key);
            if (mine) {
                out.add(l);
            }
        }
        return out;
    }

    public synchronized void clear() {
        lines.clear();
    }

    /** Попадает ли момент в окно {@code [now - windowSeconds, now]}. */
    public static boolean inWindow(long time, long now, int windowSeconds) {
        return time >= now - windowSeconds * 1000L && time <= now;
    }

    /** Сколько строк из списка попало в окно — именно это число видит детектор флуда. */
    public static int countInWindow(List<Line> lines, long now, int windowSeconds) {
        int n = 0;
        for (Line l : lines) {
            if (inWindow(l.time(), now, windowSeconds)) {
                n++;
            }
        }
        return n;
    }

    private void trim() {
        while (lines.size() > capacity) {
            lines.removeFirst();
        }
    }
}
