package space.toolrise.chatmod.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Результат проверки одного сообщения. */
public record ScanResult(List<Violation> violations) {

    public static final ScanResult CLEAN = new ScanResult(List.of());

    public boolean isClean() {
        return violations.isEmpty();
    }

    /** Самое строгое из найденных нарушений — его мут предлагается по умолчанию. */
    public Violation primary() {
        return violations.stream()
                .max(Comparator.comparing(Violation::mute))
                .orElse(null);
    }

    public MuteDuration recommendedMute() {
        Violation v = primary();
        return v == null ? null : v.mute();
    }

    /** Все подсвечиваемые участки текста, отсортированные и склеенные. */
    public List<Violation.Span> mergedSpans() {
        List<Violation.Span> all = new ArrayList<>();
        for (Violation v : violations) {
            all.addAll(v.spans());
        }
        all.sort(Comparator.comparingInt(Violation.Span::start));
        List<Violation.Span> merged = new ArrayList<>();
        for (Violation.Span s : all) {
            if (!merged.isEmpty()) {
                Violation.Span last = merged.get(merged.size() - 1);
                if (s.start() <= last.end()) {
                    merged.set(merged.size() - 1, new Violation.Span(last.start(), Math.max(last.end(), s.end())));
                    continue;
                }
            }
            merged.add(s);
        }
        return merged;
    }
}
