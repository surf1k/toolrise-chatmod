package space.toolrise.chatmod.rules;

import space.toolrise.chatmod.text.NormalizedText;
import space.toolrise.chatmod.text.TextNormalizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Компилирует rules.json в готовые к поиску структуры и проверяет по ним сообщения.
 *
 * <p>Порядок проверки одного сообщения:
 * <ol>
 *   <li>текст нормализуется в 4 варианта (см. {@link TextNormalizer});</li>
 *   <li>собираются участки, попавшие под исключения;</li>
 *   <li>по каждому включённому правилу ищутся фразы, комбо и срабатывают детекторы;</li>
 *   <li>совпадение, целиком накрытое исключением, отбрасывается.</li>
 * </ol>
 */
public final class RuleEngine {

    private static final int REGEX_FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    private static final int MAX_BODY_LENGTH = 512;
    private static final int EVIDENCE_LIMIT = 6;

    /** Скомпилированный паттерн. {@code alphabet == null} — искать во всех вариантах. */
    private record CompiledPattern(Pattern pattern, TextNormalizer.Alphabet alphabet, String source) {
    }

    private record CompiledCombo(List<CompiledPattern> a, List<CompiledPattern> b) {
    }

    private record CompiledRule(String id,
                                String name,
                                String description,
                                MuteDuration mute,
                                int minMatches,
                                List<CompiledPattern> phrases,
                                List<CompiledCombo> combos,
                                Set<Detector> detectors) {
    }

    private static final Pattern URL_PATTERN = Pattern.compile(
            "\\b(?:https?://)?(?:[a-z0-9-]{1,63}\\.)+"
                    + "(?:ru|com|net|org|su|рф|online|fun|space|store|site|club|xyz|pw|gg|me|io|top|link|"
                    + "shop|life|team|host|world|info|biz|by|ua|kz|tv|cc|app|dev|pro|vip|win|fan|mc)"
                    + "(?:/\\S*)?", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern IP_PATTERN = Pattern.compile(
            "\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b");

    private static final Pattern INVITE_PATTERN = Pattern.compile(
            "\\b(?:айпи|ip|ип)\\s*[:\\-]?\\s*\\S{4,}"
                    + "|\\bзаходи(те)?\\s+на\\b"
                    + "|\\bмой\\s+сервер\\b"
                    + "|\\bновый\\s+(майн)?сервер\\b"
                    + "|\\bприглашаю\\s+на\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern OWN_CHANNEL_PATTERN = Pattern.compile(
            "youtube|youtu\\.be|twitch|tiktok|t\\.me|telegram|instagram|vk\\.com|vk\\.cc|boosty|donationalerts"
                    + "|\\bмой\\s+(канал|стрим|ютуб|тикток)\\b|\\bподпи(шись|сывайтесь)\\b|\\bстрим\\s+(тут|здесь|у\\s+меня)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private final RuleData data;
    private final List<CompiledRule> rules;
    private final List<CompiledPattern> exceptions;
    private final List<String> compileWarnings;

    /**
     * Служебные реплики вроде «спек» в нормализованном виде. Их повторы считаются
     * по своему, куда более высокому порогу: в игре это просьба к модератору,
     * а не флуд, и мут за третий повтор был бы наказанием за обращение к тебе же.
     */
    private final Set<String> spamLoose;

    private RuleEngine(RuleData data,
                       List<CompiledRule> rules,
                       List<CompiledPattern> exceptions,
                       List<String> compileWarnings) {
        this.data = data;
        this.rules = rules;
        this.exceptions = exceptions;
        this.compileWarnings = compileWarnings;
        this.spamLoose = normalizedSet(data.settings.spamLoosePhrases);
    }

    /**
     * Фразы приводятся к тому же виду, в котором сравниваются сообщения, — поэтому
     * «спек» ловится и как «СПЕК», и как «с п е к», и как «спеккк», а «spec»
     * достаточно написать латиницей один раз.
     */
    private static Set<String> normalizedSet(List<String> phrases) {
        Set<String> out = new HashSet<>();
        if (phrases == null) {
            return out;
        }
        for (String phrase : phrases) {
            if (phrase == null || phrase.isBlank()) {
                continue;
            }
            String norm = TextNormalizer
                    .normalize(phrase, TextNormalizer.Alphabet.CYRILLIC, TextNormalizer.Mode.SQUEEZE)
                    .value();
            if (!norm.isBlank()) {
                out.add(norm);
            }
        }
        return out;
    }

    public static RuleEngine compile(RuleData data) {
        List<String> warnings = new ArrayList<>();
        Compiler compiler = new Compiler(data, warnings);

        List<CompiledPattern> exceptions = new ArrayList<>();
        exceptions.addAll(compiler.compileAll(data.exceptions.phrases));
        exceptions.addAll(compiler.compileAll(data.exceptions.technicalPhrases));

        List<CompiledRule> compiled = new ArrayList<>();
        for (RuleData.RuleDef def : data.rules) {
            if (!def.enabled) {
                continue;
            }
            Set<Detector> detectors = new LinkedHashSet<>();
            for (String d : def.detectors) {
                Detector detector = Detector.fromString(d);
                if (detector == null) {
                    warnings.add("Правило " + def.id + ": неизвестный детектор «" + d + "»");
                } else {
                    detectors.add(detector);
                }
            }
            List<CompiledCombo> combos = new ArrayList<>();
            for (RuleData.Combo combo : def.combos) {
                List<CompiledPattern> a = compiler.compileAll(combo.a);
                List<CompiledPattern> b = compiler.compileAll(combo.b);
                if (!a.isEmpty() && !b.isEmpty()) {
                    combos.add(new CompiledCombo(a, b));
                }
            }
            compiled.add(new CompiledRule(
                    def.id,
                    def.name,
                    def.description,
                    MuteDuration.parse(def.mute),
                    Math.max(1, def.minMatches),
                    compiler.compileAll(def.phrases),
                    combos,
                    detectors));
        }
        return new RuleEngine(data, compiled, exceptions, warnings);
    }

    public RuleData data() {
        return data;
    }

    public List<String> compileWarnings() {
        return compileWarnings;
    }

    public int ruleCount() {
        return rules.size();
    }

    public int patternCount() {
        int n = exceptions.size();
        for (CompiledRule r : rules) {
            n += r.phrases().size();
            for (CompiledCombo c : r.combos()) {
                n += c.a().size() + c.b().size();
            }
        }
        return n;
    }

    /**
     * Проверяет тело сообщения.
     *
     * @param body    текст сообщения без ника и префиксов, без кодов форматирования
     * @param sender  ник отправителя (может быть {@code null}) — нужен детекторам спама
     * @param history история чата; может быть {@code null}, тогда спам/флуд не проверяются
     */
    public ScanResult scan(String body, String sender, MessageHistory history, long nowMillis) {
        if (body == null || body.isBlank()) {
            return ScanResult.CLEAN;
        }
        String text = body.length() > MAX_BODY_LENGTH ? body.substring(0, MAX_BODY_LENGTH) : body;
        List<NormalizedText> variants = TextNormalizer.allVariants(text);

        List<Violation.Span> exceptionSpans = new ArrayList<>();
        for (CompiledPattern p : exceptions) {
            collect(p, variants, exceptionSpans, null, text);
        }

        List<Violation> found = new ArrayList<>();
        for (CompiledRule rule : rules) {
            List<Violation.Span> spans = new ArrayList<>();
            List<String> evidence = new ArrayList<>();

            for (CompiledPattern p : rule.phrases()) {
                collect(p, variants, spans, evidence, text);
            }
            dropExcepted(spans, exceptionSpans);

            for (CompiledCombo combo : rule.combos()) {
                List<Violation.Span> aSpans = new ArrayList<>();
                List<String> aEvidence = new ArrayList<>();
                for (CompiledPattern p : combo.a()) {
                    collect(p, variants, aSpans, aEvidence, text);
                }
                dropExcepted(aSpans, exceptionSpans);
                if (aSpans.isEmpty()) {
                    continue;
                }
                List<Violation.Span> bSpans = new ArrayList<>();
                List<String> bEvidence = new ArrayList<>();
                for (CompiledPattern p : combo.b()) {
                    collect(p, variants, bSpans, bEvidence, text);
                }
                dropExcepted(bSpans, exceptionSpans);
                if (bSpans.isEmpty()) {
                    continue;
                }
                spans.addAll(aSpans);
                spans.addAll(bSpans);
                addEvidence(evidence, aEvidence);
                addEvidence(evidence, bEvidence);
            }

            for (Detector detector : rule.detectors()) {
                String note = runDetector(detector, text, sender, history, nowMillis);
                if (note != null) {
                    evidence.add(note);
                    if (spans.isEmpty()) {
                        spans.add(new Violation.Span(0, text.length()));
                    }
                }
            }

            // Считаем именно непересекающиеся участки: два паттерна, поймавшие одно
            // и то же слово, — это одно нарушение, а не два.
            List<Violation.Span> merged = mergeSpans(spans);
            if (merged.size() >= rule.minMatches()) {
                found.add(new Violation(
                        rule.id(),
                        rule.name(),
                        rule.mute(),
                        List.copyOf(evidence.size() > EVIDENCE_LIMIT ? evidence.subList(0, EVIDENCE_LIMIT) : evidence),
                        merged));
            }
        }

        if (found.isEmpty()) {
            return ScanResult.CLEAN;
        }
        found.sort((x, y) -> y.mute().compareTo(x.mute()));
        return new ScanResult(List.copyOf(found));
    }

    // ------------------------------------------------------------------ поиск

    private static void collect(CompiledPattern p,
                                List<NormalizedText> variants,
                                List<Violation.Span> out,
                                List<String> evidence,
                                String body) {
        for (NormalizedText nt : variants) {
            if (nt.isEmpty()) {
                continue;
            }
            if (p.alphabet() != null && nt.variant().alphabet() != p.alphabet()) {
                continue;
            }
            Matcher m = p.pattern().matcher(nt.value());
            while (m.find()) {
                int start = nt.toSourceStart(m.start());
                int end = nt.toSourceEnd(m.end());
                if (end <= start) {
                    continue;
                }
                Violation.Span span = new Violation.Span(start, Math.min(end, body.length()));
                if (!out.contains(span)) {
                    out.add(span);
                    if (evidence != null) {
                        evidence.add(snippet(body, span));
                    }
                }
            }
        }
    }

    /** Склеивает пересекающиеся участки: нужно, чтобы считать нарушения, а не совпадения паттернов. */
    private static List<Violation.Span> mergeSpans(List<Violation.Span> spans) {
        if (spans.size() < 2) {
            return List.copyOf(spans);
        }
        List<Violation.Span> sorted = new ArrayList<>(spans);
        sorted.sort(java.util.Comparator.comparingInt(Violation.Span::start));
        List<Violation.Span> merged = new ArrayList<>();
        for (Violation.Span s : sorted) {
            if (!merged.isEmpty()) {
                Violation.Span last = merged.get(merged.size() - 1);
                if (s.start() < last.end()) {
                    merged.set(merged.size() - 1,
                            new Violation.Span(last.start(), Math.max(last.end(), s.end())));
                    continue;
                }
            }
            merged.add(s);
        }
        return List.copyOf(merged);
    }

    private static void dropExcepted(List<Violation.Span> spans, List<Violation.Span> exceptionSpans) {
        if (exceptionSpans.isEmpty() || spans.isEmpty()) {
            return;
        }
        spans.removeIf(s -> exceptionSpans.stream().anyMatch(ex -> ex.contains(s)));
    }

    private static void addEvidence(List<String> target, List<String> source) {
        for (String s : source) {
            if (!target.contains(s)) {
                target.add(s);
            }
        }
    }

    private static String snippet(String body, Violation.Span span) {
        int s = Math.max(0, Math.min(span.start(), body.length()));
        int e = Math.max(s, Math.min(span.end(), body.length()));
        String raw = body.substring(s, e).trim();
        return raw.length() > 40 ? raw.substring(0, 40) + "…" : raw;
    }

    // ------------------------------------------------------------- детекторы

    private String runDetector(Detector detector,
                               String body,
                               String sender,
                               MessageHistory history,
                               long nowMillis) {
        RuleData.Settings s = data.settings;
        return switch (detector) {
            case CAPS -> detectCaps(body, s);
            case REPEAT_CHARS -> detectRepeat(body, s);
            case SPAM_REPEAT -> detectSpam(body, sender, history, s, nowMillis);
            case FLOOD_RATE -> detectFlood(sender, history, s, nowMillis);
            case AD_THIRD_PARTY -> detectAd(body, s, false);
            case AD_OWN_CHANNEL -> detectAd(body, s, true);
        };
    }

    private static String detectCaps(String body, RuleData.Settings s) {
        int letters = 0;
        int upper = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) {
                    upper++;
                }
            }
        }
        if (letters < s.capsMinLength) {
            return null;
        }
        double ratio = (double) upper / letters;
        return ratio >= s.capsRatio
                ? "капс: " + Math.round(ratio * 100) + "% заглавных из " + letters
                : null;
    }

    /** Псевдографика: рамки, блоки, фигуры. Ими сервер рисует объявления, а не игроки флудят. */
    private static boolean isDecoration(char c) {
        return c >= '─' && c <= '◿';
    }

    private static String detectRepeat(String body, RuleData.Settings s) {
        int run = 1;
        char prev = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = Character.toLowerCase(body.charAt(i));
            if (Character.isWhitespace(c) || isDecoration(c)) {
                run = 1;
                prev = 0;
                continue;
            }
            if (c == prev) {
                run++;
                if (run >= s.repeatCharThreshold) {
                    return "повтор символа «" + c + "» " + run + "+ раз подряд";
                }
            } else {
                run = 1;
                prev = c;
            }
        }
        return null;
    }

    private String detectSpam(String body,
                              String sender,
                              MessageHistory history,
                              RuleData.Settings s,
                              long nowMillis) {
        if (history == null || sender == null || sender.isBlank()) {
            return null;
        }
        String norm = TextNormalizer.normalize(body, TextNormalizer.Alphabet.CYRILLIC, TextNormalizer.Mode.SQUEEZE).value();
        if (norm.isBlank() || norm.length() < Math.max(0, s.spamMinLength)) {
            return null;
        }
        boolean loose = spamLoose.contains(norm);
        int threshold = loose ? Math.max(1, s.spamLooseRepeatCount) : s.spamRepeatCount;
        int n = history.repeatCount(sender, norm, s.spamWindowSeconds, nowMillis);
        return n >= threshold
                ? "одно и то же сообщение " + n + " раз за " + s.spamWindowSeconds + " сек"
                : null;
    }

    private String detectFlood(String sender,
                               MessageHistory history,
                               RuleData.Settings s,
                               long nowMillis) {
        if (history == null || sender == null || sender.isBlank()) {
            return null;
        }
        // Служебные реплики не считаются и здесь: иначе «спек» ловился бы по
        // потоку сообщений раньше, чем дошёл бы до своего порога повторов.
        int n = history.messageCount(sender, s.floodWindowSeconds, nowMillis, spamLoose);
        return n >= s.floodMessageCount
                ? n + " сообщений за " + s.floodWindowSeconds + " сек"
                : null;
    }

    private String detectAd(String body, RuleData.Settings s, boolean ownChannel) {
        String lower = body.toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();

        Matcher url = URL_PATTERN.matcher(lower);
        while (url.find()) {
            hits.add(url.group());
        }
        Matcher ip = IP_PATTERN.matcher(lower);
        while (ip.find()) {
            hits.add(ip.group());
        }

        hits.removeIf(h -> isWhitelisted(h, s));

        if (ownChannel) {
            for (String h : hits) {
                if (OWN_CHANNEL_PATTERN.matcher(h).find()) {
                    return "ссылка на свой канал: " + h;
                }
            }
            Matcher m = OWN_CHANNEL_PATTERN.matcher(lower);
            if (m.find() && !isWhitelisted(m.group(), s)) {
                return "реклама канала: " + m.group();
            }
            return null;
        }

        for (String h : hits) {
            if (!OWN_CHANNEL_PATTERN.matcher(h).find()) {
                return "сторонняя ссылка: " + h;
            }
        }
        Matcher invite = INVITE_PATTERN.matcher(lower);
        if (invite.find()) {
            return "приглашение на сторонний проект: " + invite.group().trim();
        }
        return null;
    }

    private static boolean isWhitelisted(String hit, RuleData.Settings s) {
        for (String allowed : s.adWhitelist) {
            if (allowed != null && !allowed.isBlank() && hit.contains(allowed.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ компиляция

    private static final class Compiler {
        private final RuleData data;
        private final List<String> warnings;

        Compiler(RuleData data, List<String> warnings) {
            this.data = data;
            this.warnings = warnings;
        }

        List<CompiledPattern> compileAll(List<String> raw) {
            List<CompiledPattern> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String entry : raw) {
                compileEntry(entry, out, seen, new HashSet<>());
            }
            return out;
        }

        private void compileEntry(String entry,
                                  List<CompiledPattern> out,
                                  Set<String> seenRegex,
                                  Set<String> groupStack) {
            if (entry == null || entry.isBlank()) {
                return;
            }
            String e = entry.trim();

            if (e.startsWith("@")) {
                String group = e.substring(1);
                if (!groupStack.add(group)) {
                    warnings.add("Циклическая ссылка на группу @" + group);
                    return;
                }
                List<String> members = data.groups.get(group);
                if (members == null) {
                    warnings.add("Группа @" + group + " не найдена");
                } else {
                    for (String member : members) {
                        compileEntry(member, out, seenRegex, groupStack);
                    }
                }
                groupStack.remove(group);
                return;
            }

            if (e.startsWith("re:")) {
                String regex = e.substring(3);
                if (seenRegex.add("re|" + regex)) {
                    try {
                        out.add(new CompiledPattern(Pattern.compile(regex, REGEX_FLAGS), null, e));
                    } catch (PatternSyntaxException ex) {
                        warnings.add("Некорректная регулярка «" + regex + "»: " + ex.getDescription());
                    }
                }
                return;
            }

            int tolerance = Math.max(0, data.settings.suffixTolerance);
            for (TextNormalizer.Alphabet alphabet : TextNormalizer.Alphabet.values()) {
                List<String> words = TextNormalizer.normalizePhraseWords(e, alphabet);
                if (words.isEmpty()) {
                    continue;
                }
                StringBuilder sb = new StringBuilder("\\b");
                for (int i = 0; i < words.size(); i++) {
                    if (i > 0) {
                        sb.append("\\s*");
                    }
                    sb.append(Pattern.quote(words.get(i)));
                }
                if (tolerance > 0) {
                    sb.append("\\w{0,").append(tolerance).append('}');
                }
                sb.append("\\b");
                String regex = sb.toString();
                if (seenRegex.add(alphabet + "|" + regex)) {
                    out.add(new CompiledPattern(Pattern.compile(regex, REGEX_FLAGS), alphabet, e));
                }
            }
        }
    }
}
