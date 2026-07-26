package space.toolrise.chatmod.rules;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Срок мута из правил: «10m», «4h», «7d» или «permanent».
 *
 * @param raw     как записано в rules.json — именно это уходит в команду мута
 * @param seconds длительность в секундах; {@code -1} = навсегда
 */
public record MuteDuration(String raw, long seconds) implements Comparable<MuteDuration> {

    public static final MuteDuration PERMANENT = new MuteDuration("permanent", -1L);

    private static final Pattern PATTERN = Pattern.compile("^(\\d+)\\s*([smhdwмчдн]?\\w*)$");

    public static MuteDuration parse(String value) {
        if (value == null || value.isBlank()) {
            return new MuteDuration("10m", 600L);
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("perm") || v.equals("forever") || v.equals("навсегда") || v.equals("-1")) {
            return PERMANENT;
        }
        Matcher m = PATTERN.matcher(v);
        if (!m.matches()) {
            return new MuteDuration(value, 600L);
        }
        long amount = Long.parseLong(m.group(1));
        String unit = m.group(2);
        long mult = switch (unit.isEmpty() ? "m" : unit.substring(0, 1)) {
            case "s", "с" -> 1L;
            case "h", "ч" -> 3600L;
            case "d", "д" -> 86_400L;
            case "w", "н" -> 604_800L;
            default -> 60L;
        };
        return new MuteDuration(value.trim(), amount * mult);
    }

    public boolean isPermanent() {
        return seconds < 0;
    }

    /** Человекочитаемый вид для GUI и подсказок. */
    public String display() {
        if (isPermanent()) {
            return "навсегда";
        }
        long s = seconds;
        if (s % 86_400 == 0 && s >= 86_400) {
            return (s / 86_400) + " д";
        }
        if (s % 3600 == 0 && s >= 3600) {
            return (s / 3600) + " ч";
        }
        if (s % 60 == 0 && s >= 60) {
            return (s / 60) + " мин";
        }
        return s + " сек";
    }

    /** Больше = строже. Навсегда — самый строгий. */
    @Override
    public int compareTo(MuteDuration other) {
        if (isPermanent() && other.isPermanent()) {
            return 0;
        }
        if (isPermanent()) {
            return 1;
        }
        if (other.isPermanent()) {
            return -1;
        }
        return Long.compare(seconds, other.seconds);
    }
}
