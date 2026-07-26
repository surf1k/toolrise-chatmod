package space.toolrise.chatmod.rules;

import java.util.List;

/**
 * Найденное нарушение одного правила.
 *
 * @param ruleId      «2.1»
 * @param ruleName    «Оскорбления»
 * @param mute        рекомендованный срок мута
 * @param evidence    что именно сработало (для ховера и лога)
 * @param spans       позиции в тексте сообщения — по ним красится подсветка в режиме WORDS
 */
public record Violation(String ruleId,
                        String ruleName,
                        MuteDuration mute,
                        List<String> evidence,
                        List<Span> spans) {

    /** Полуинтервал [start, end) в координатах текста сообщения. */
    public record Span(int start, int end) {
        public boolean contains(Span other) {
            return start <= other.start && end >= other.end;
        }

        public boolean overlaps(Span other) {
            return start < other.end && other.start < end;
        }
    }

    public String shortLabel() {
        return ruleId + " " + ruleName;
    }
}
