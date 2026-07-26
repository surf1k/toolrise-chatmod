package space.toolrise.chatmod.chat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Помеченные сообщения, доступные для клика. Кольцевой буфер:
 * старые записи вытесняются, чтобы память не росла за длинную сессию.
 */
public final class FlaggedMessageStore {

    private final LinkedHashMap<Integer, FlaggedMessage> messages = new LinkedHashMap<>();
    private int nextId = 1;
    private int capacity;

    public FlaggedMessageStore(int capacity) {
        this.capacity = Math.max(16, capacity);
    }

    public synchronized void setCapacity(int capacity) {
        this.capacity = Math.max(16, capacity);
        trim();
    }

    public synchronized int nextId() {
        return nextId++;
    }

    public synchronized void put(FlaggedMessage message) {
        messages.put(message.id(), message);
        trim();
    }

    public synchronized FlaggedMessage get(int id) {
        return messages.get(id);
    }

    /** Последние N записей, свежие первыми. */
    public synchronized List<FlaggedMessage> recent(int limit) {
        List<FlaggedMessage> all = new ArrayList<>(messages.values());
        List<FlaggedMessage> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) {
            out.add(all.get(i));
        }
        return out;
    }

    public synchronized void clear() {
        messages.clear();
    }

    public synchronized int size() {
        return messages.size();
    }

    private void trim() {
        while (messages.size() > capacity) {
            Map.Entry<Integer, FlaggedMessage> oldest = messages.entrySet().iterator().next();
            messages.remove(oldest.getKey());
        }
    }
}
