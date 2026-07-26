package space.toolrise.chatmod.text;

/**
 * Нормализованный вариант строки вместе с картой «индекс в нормализованной строке →
 * индекс в исходной». Карта нужна, чтобы подсветить в чате именно тот кусок текста,
 * который сработал, а не приблизительное место.
 */
public final class NormalizedText {
    private final String source;
    private final String value;
    /** map[i] — индекс в source для символа value[i]; длина = value.length() + 1 (последний = source.length()). */
    private final int[] map;
    private final TextNormalizer.Variant variant;

    NormalizedText(String source, String value, int[] map, TextNormalizer.Variant variant) {
        this.source = source;
        this.value = value;
        this.map = map;
        this.variant = variant;
    }

    public String source() {
        return source;
    }

    public String value() {
        return value;
    }

    public TextNormalizer.Variant variant() {
        return variant;
    }

    public boolean isEmpty() {
        return value.isEmpty();
    }

    /** Начало совпадения в координатах исходной строки. */
    public int toSourceStart(int normStart) {
        if (map.length == 0) {
            return 0;
        }
        int i = Math.max(0, Math.min(normStart, map.length - 1));
        return map[i];
    }

    /** Конец (exclusive) совпадения в координатах исходной строки. */
    public int toSourceEnd(int normEndExclusive) {
        if (map.length == 0) {
            return 0;
        }
        int i = Math.max(0, Math.min(normEndExclusive, map.length - 1));
        return map[i];
    }
}
