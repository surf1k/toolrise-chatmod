package space.toolrise.chatmod.tools;

import com.google.gson.Gson;
import space.toolrise.chatmod.chat.ChatParser;
import space.toolrise.chatmod.chat.RankPolicy;
import space.toolrise.chatmod.config.ModConfig;
import space.toolrise.chatmod.evidence.ChatLogBuffer;
import space.toolrise.chatmod.rules.MessageHistory;
import space.toolrise.chatmod.rules.MuteDuration;
import space.toolrise.chatmod.rules.RuleData;
import space.toolrise.chatmod.rules.RuleEngine;
import space.toolrise.chatmod.rules.ScanResult;
import space.toolrise.chatmod.rules.Violation;
import space.toolrise.chatmod.text.NickMasker;
import space.toolrise.chatmod.text.NormalizedText;
import space.toolrise.chatmod.text.TextNormalizer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Консольная утилита проверки правил. Нужна затем, чтобы правки rules.json
 * можно было проверить машинно, а не на глаз: что правило ловит, что оно
 * ломает и почему конкретный обход проскочил.
 *
 * <p>Запуск из корня проекта:
 * <pre>
 *   ./gradlew -q rules --args="verify"
 *   ./gradlew -q rules --args="scan путь/к/latest.log"
 *   ./gradlew -q rules --args="norm х.у.й"
 * </pre>
 *
 * <p>Отчёт всегда пишется в файл в UTF-8 ({@code --out}, по умолчанию
 * {@code build/rules-report.txt}), а в консоль уходит только ASCII-сводка:
 * консоль Windows кириллицу коверкает.
 */
public final class RulesCli {

    private static final Path DEFAULT_RULES =
            Path.of("src", "main", "resources", "trchatmod", "default_rules.json");
    private static final Path DEFAULT_CASES = Path.of("tools", "testcases.txt");
    private static final Path DEFAULT_OUT = Path.of("build", "rules-report.txt");

    private static final Pattern CHAT_LINE = Pattern.compile("\\[CHAT]\\s?(.*)$");
    private static final Pattern MOD_MARKER = Pattern.compile("^\\[!\\s*[\\d.]*]\\s*");
    private static final Pattern MOD_FEEDBACK = Pattern.compile("^\\[ChatMod]");

    private RulesCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }

        List<String> rest = new ArrayList<>(List.of(args));
        String command = rest.remove(0);
        Path rulesPath = takeOption(rest, "--rules", DEFAULT_RULES);
        Path casesPath = takeOption(rest, "--cases", DEFAULT_CASES);
        Path outPath = takeOption(rest, "--out", DEFAULT_OUT);

        RuleEngine engine = loadEngine(rulesPath);
        Report report = new Report(outPath);
        report.line("rules:   " + rulesPath.toAbsolutePath());
        report.line("правил:  " + engine.ruleCount() + ", паттернов: " + engine.patternCount());
        for (String w : engine.compileWarnings()) {
            report.line("ВНИМАНИЕ: " + w);
        }
        report.line("");

        int exit;
        switch (command) {
            case "verify" -> exit = verify(engine, casesPath, report);
            case "check" -> exit = check(engine, requirePath(rest, "check <файл>"), report);
            case "scan" -> exit = scan(engine, rest, report);
            case "test" -> exit = test(engine, String.join(" ", rest), report);
            case "norm" -> exit = norm(String.join(" ", rest), report);
            case "flood" -> exit = flood(engine, report);
            case "nicks" -> exit = nicks(engine, report);
            case "ranks" -> exit = ranks(report);
            case "proof" -> exit = proof(report);
            default -> {
                usage();
                exit = 2;
            }
        }

        report.close();
        System.out.println("report: " + outPath.toAbsolutePath());
        System.exit(exit);
    }

    // ------------------------------------------------------------- команды

    /** Прогоняет корпус testcases.txt. Единственный способ убедиться, что правка ничего не сломала. */
    private static int verify(RuleEngine engine, Path casesPath, Report report) throws IOException {
        if (Files.notExists(casesPath)) {
            report.line("Файл с тестами не найден: " + casesPath.toAbsolutePath());
            System.out.println("VERIFY ERROR: cases file not found");
            return 2;
        }
        int total = 0;
        int failed = 0;
        for (String raw : Files.readAllLines(casesPath, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int sep = line.indexOf('|');
            if (sep < 0) {
                report.line("ПЛОХАЯ СТРОКА (нет '|'): " + line);
                failed++;
                continue;
            }
            String expect = line.substring(0, sep).strip();
            String text = line.substring(sep + 1).strip();
            if (text.isEmpty()) {
                continue;
            }
            total++;

            ScanResult result = engine.scan(text, "Tester", null, System.currentTimeMillis());
            List<String> ids = ruleIds(result);

            boolean ok;
            if (expect.equalsIgnoreCase("CLEAN")) {
                ok = result.isClean();
            } else if (expect.toUpperCase().startsWith("FLAG")) {
                String wanted = expect.length() > 4 ? expect.substring(4).strip() : "";
                ok = wanted.isEmpty() ? !result.isClean() : ids.contains(wanted);
            } else if (expect.toUpperCase().startsWith("NOT")) {
                // Ловит не пропуск, а перебор: «сынок ты тварь» — это 2.1 на
                // 30 минут, и правило про родню не должно поднимать его до 4 часов.
                ok = !ids.contains(expect.substring(3).strip());
            } else {
                report.line("НЕИЗВЕСТНОЕ ОЖИДАНИЕ «" + expect + "»: " + text);
                failed++;
                continue;
            }

            if (!ok) {
                failed++;
                report.line("FAIL  ожидалось " + expect + ", получено " + ids + "  ::  " + text);
                for (Violation v : result.violations()) {
                    report.line("        " + v.ruleId() + " " + v.evidence());
                }
            }
        }
        report.line("");
        report.line("тестов: " + total + ", провалено: " + failed);
        System.out.println("VERIFY " + (failed == 0 ? "PASS" : "FAIL") + " total=" + total + " failed=" + failed);
        return failed == 0 ? 0 : 1;
    }

    /** Прогоняет произвольный файл: по строке на сообщение. */
    private static int check(RuleEngine engine, Path file, Report report) throws IOException {
        if (Files.notExists(file)) {
            System.out.println("CHECK ERROR: file not found");
            report.line("Файл не найден: " + file.toAbsolutePath());
            return 2;
        }
        int flagged = 0;
        int total = 0;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String text = raw.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            total++;
            ScanResult result = engine.scan(text, "Tester", null, System.currentTimeMillis());
            if (result.isClean()) {
                report.line("CLEAN            | " + text);
            } else {
                flagged++;
                report.line("FLAG " + pad(String.join(",", ruleIds(result)), 11) + " | " + text);
            }
        }
        report.line("");
        report.line("строк: " + total + ", помечено: " + flagged);
        System.out.println("CHECK total=" + total + " flagged=" + flagged);
        return 0;
    }

    /**
     * Разбирает лог Minecraft и показывает сообщения чата, которые правила
     * НЕ поймали. Это и есть список кандидатов на обход.
     */
    private static int scan(RuleEngine engine, List<String> files, Report report) throws IOException {
        if (files.isEmpty()) {
            System.out.println("SCAN ERROR: no log files given");
            report.line("Не указан ни один лог.");
            return 2;
        }
        ChatParser parser = new ChatParser(new ModConfig().chatPatterns);

        Map<String, Integer> missed = new LinkedHashMap<>();
        Map<String, Integer> caught = new LinkedHashMap<>();
        Set<String> alreadyMarked = new LinkedHashSet<>();
        int chatLines = 0;

        for (String name : files) {
            Path path = Path.of(name);
            if (Files.notExists(path)) {
                report.line("Пропущен (нет файла): " + path.toAbsolutePath());
                continue;
            }
            for (String raw : readMaybeGzip(path)) {
                Matcher m = CHAT_LINE.matcher(raw);
                if (!m.find()) {
                    continue;
                }
                String line = m.group(1).strip();
                if (line.isEmpty() || MOD_FEEDBACK.matcher(line).find()) {
                    continue;
                }
                chatLines++;

                Matcher marker = MOD_MARKER.matcher(line);
                if (marker.find()) {
                    line = marker.replaceFirst("");
                    alreadyMarked.add(line);
                }

                ChatParser.Parsed parsed = parser.parse(line);
                String body = parsed.body();
                // Ник в отчёте важен: если он не распознан, мод такое сообщение
                // вообще не проверяет (requireKnownSender), а наказывать некого.
                String label = pad(parsed.nick() == null ? "<без ника>" : parsed.nick(), 18) + body;
                ScanResult result = engine.scan(body, parsed.nick(), null, System.currentTimeMillis());
                if (result.isClean()) {
                    missed.merge(label, 1, Integer::sum);
                } else {
                    caught.merge(label, 1, Integer::sum);
                }
            }
        }

        report.line("строк чата: " + chatLines
                + ", уникальных пойманных: " + caught.size()
                + ", уникальных НЕ пойманных: " + missed.size());
        report.line("помечено модом в самом логе: " + alreadyMarked.size());
        report.line("");
        report.line("=== НЕ ПОЙМАННЫЕ (кандидаты на обход) ===");
        missed.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> report.line("x" + e.getValue() + "  " + e.getKey()));
        report.line("");
        report.line("=== ПОЙМАННЫЕ (для контроля ложных срабатываний) ===");
        caught.forEach((label, n) -> report.line("x" + n + "  " + label));

        System.out.println("SCAN chatLines=" + chatLines
                + " missed=" + missed.size() + " caught=" + caught.size());
        return 0;
    }

    private static int test(RuleEngine engine, String text, Report report) {
        if (text.isBlank()) {
            System.out.println("TEST ERROR: empty text");
            return 2;
        }
        ScanResult result = engine.scan(text, "Tester", null, System.currentTimeMillis());
        report.line("текст: " + text);
        if (result.isClean()) {
            report.line("нарушений нет");
            System.out.println("TEST clean");
            return 0;
        }
        for (Violation v : result.violations()) {
            report.line("  " + v.ruleId() + " " + v.ruleName()
                    + " — мут " + v.mute().display()
                    + " — сработало: " + v.evidence());
        }
        System.out.println("TEST flagged=" + ruleIds(result));
        return 0;
    }

    /**
     * Проверка детектора флуда. Он зависит от истории сообщений, поэтому обычным
     * корпусом {@code verify} не проверяется — а наказывает автомат по нему сам,
     * так что корректность важна.
     */
    private static int flood(RuleEngine engine, Report report) {
        RuleData.Settings s = engine.data().settings;
        report.line("определение флуда: " + s.spamRepeatCount
                + " и более одинаковых сообщений за " + s.spamWindowSeconds + " сек");
        report.line("служебные реплики " + s.spamLoosePhrases
                + ": порог " + s.spamLooseRepeatCount + " за то же окно");
        report.line("");

        int failed = 0;
        failed += floodCase(engine, report, "2 одинаковых в пределах окна — ещё не флуд",
                new String[]{"привет", "привет"},
                new long[]{0, 10_000}, false);
        failed += floodCase(engine, report, "3 одинаковых в пределах окна",
                new String[]{"привет", "привет", "привет"},
                new long[]{0, 10_000, 20_000}, true);
        failed += floodCase(engine, report, "4 одинаковых в пределах окна",
                new String[]{"привет", "привет", "привет", "привет"},
                new long[]{0, 15_000, 30_000, 50_000}, true);
        failed += floodCase(engine, report, "3 одинаковых, но растянутые за окно",
                new String[]{"привет", "привет", "привет"},
                new long[]{0, 61_000, 130_000}, false);
        failed += floodCase(engine, report, "4 разных сообщения",
                new String[]{"привет", "как дела", "го пвп", "я в аду"},
                new long[]{0, 10_000, 20_000, 30_000}, false);
        failed += floodCase(engine, report, "4 коротких «да» — не флуд",
                new String[]{"да", "да?", "да", "да"},
                new long[]{0, 10_000, 20_000, 30_000}, false);
        failed += floodCase(engine, report, "5 коротких «да» — всё равно не флуд",
                new String[]{"да", "да", "да", "да", "да"},
                new long[]{0, 5_000, 10_000, 15_000, 20_000}, false);
        failed += floodCase(engine, report, "4 одинаковых с разным регистром и знаками",
                new String[]{"кто продаст сет", "КТО ПРОДАСТ СЕТ", "кто продаст сет!!", "Кто продаст сет..."},
                new long[]{0, 10_000, 20_000, 30_000}, true);


        // Служебные реплики: «спек» — это просьба к модератору, и мут за третий
        // повтор был бы наказанием за обращение к самому же модератору.
        failed += floodCase(engine, report, "«спек» 5 раз — не флуд",
                repeat("спек", 5), everySeconds(5, 5), false);
        failed += floodCase(engine, report, "«спек» 30 раз — всё ещё не флуд",
                repeat("спек", 30), everySeconds(30, 2), false);
        failed += floodCase(engine, report, "«спек» 31 раз за минуту — флуд",
                repeat("спек", 31), everySeconds(31, 1), true);
        failed += floodCase(engine, report, "«spec» латиницей 31 раз — флуд",
                repeat("spec", 31), everySeconds(31, 1), true);
        failed += floodCase(engine, report, "«СПЕК» и «с п е к» — тот же порог",
                repeat("СПЕК", 20), everySeconds(20, 2), false);
        failed += silentCase(engine, report, "«спек» 20 раз не ловится вообще ничем",
                repeat("спек", 20), everySeconds(20, 1));
        failed += floodCase(engine, report, "«дайте спек» — обычная реплика, порог 3",
                repeat("дайте спек", 3), everySeconds(3, 10), true);

        report.line("");
        report.line("провалено: " + failed);
        System.out.println("FLOOD " + (failed == 0 ? "PASS" : "FAIL") + " failed=" + failed);
        return failed == 0 ? 0 : 1;
    }

    private static String[] repeat(String message, int times) {
        String[] out = new String[times];
        java.util.Arrays.fill(out, message);
        return out;
    }

    private static long[] everySeconds(int times, int stepSeconds) {
        long[] out = new long[times];
        for (int i = 0; i < times; i++) {
            out[i] = i * stepSeconds * 1000L;
        }
        return out;
    }

    /** Ни одно правило не должно сработать — ни повторы, ни поток сообщений. */
    private static int silentCase(RuleEngine engine, Report report, String name,
                                  String[] messages, long[] offsets) {
        MessageHistory history = new MessageHistory();
        long base = 1_000_000_000L;
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < messages.length; i++) {
            long now = base + offsets[i];
            String norm = TextNormalizer.normalize(messages[i],
                    TextNormalizer.Alphabet.CYRILLIC, TextNormalizer.Mode.SQUEEZE).value();
            history.record("Flooder", norm, now);
            for (Violation v : engine.scan(messages[i], "Flooder", history, now).violations()) {
                hits.add(v.ruleId() + " " + String.join(", ", v.evidence()));
            }
        }
        boolean ok = hits.isEmpty();
        report.line((ok ? "ok    " : "FAIL  ") + name
                + " -> " + (ok ? "чисто" : "сработало: " + hits));
        return ok ? 0 : 1;
    }

    private static int floodCase(RuleEngine engine, Report report, String name,
                                 String[] messages, long[] offsets, boolean expected) {
        MessageHistory history = new MessageHistory();
        long base = 1_000_000_000L;
        boolean detected = false;
        for (int i = 0; i < messages.length; i++) {
            long now = base + offsets[i];
            String norm = TextNormalizer.normalize(messages[i],
                    TextNormalizer.Alphabet.CYRILLIC, TextNormalizer.Mode.SQUEEZE).value();
            history.record("Flooder", norm, now);
            for (Violation v : engine.scan(messages[i], "Flooder", history, now).violations()) {
                if (String.join(" ", v.evidence()).contains("одно и то же")) {
                    detected = true;
                }
            }
        }
        boolean ok = detected == expected;
        report.line((ok ? "ok    " : "FAIL  ") + name
                + " -> " + (detected ? "флуд" : "не флуд")
                + ", ожидалось " + (expected ? "флуд" : "не флуд"));
        return ok ? 0 : 1;
    }

    /**
     * Проверка того, что ники игроков не считаются нарушением: за ник с матом
     * отвечает его владелец по пункту 3.7, а не тот, кто к нему обратился.
     */
    private static int nicks(RuleEngine engine, Report report) {
        List<String> online = List.of("Dj_pIdOrAs", "Japatuxa123", "SuKa_777", "Player_01");
        report.line("игроки онлайн: " + online);
        report.line("");

        int failed = 0;
        failed += nickCase(engine, report, online, "Dj_pIdOrAs бекни мидасу", false);
        failed += nickCase(engine, report, online, "привет SuKa_777", false);
        failed += nickCase(engine, report, online, "Dj_pIdOrAs ты тварь", true);
        failed += nickCase(engine, report, online, "ты пидорас", true);
        failed += nickCase(engine, report, online, "Japatuxa123 го пвп", false);

        report.line("");
        report.line("провалено: " + failed);
        System.out.println("NICKS " + (failed == 0 ? "PASS" : "FAIL") + " failed=" + failed);
        return failed == 0 ? 0 : 1;
    }

    private static int nickCase(RuleEngine engine, Report report, List<String> online,
                                String body, boolean expected) {
        String masked = NickMasker.mask(body, online);
        ScanResult result = engine.scan(masked, "Tester", null, System.currentTimeMillis());
        boolean flagged = !result.isClean();
        boolean ok = flagged == expected;
        report.line((ok ? "ok    " : "FAIL  ") + pad(body, 30)
                + "-> " + pad(masked, 30)
                + (flagged ? "нарушение " + ruleIds(result) : "чисто")
                + ", ожидалось " + (expected ? "нарушение" : "чисто"));
        return ok ? 0 : 1;
    }

    /**
     * Проверка рангов. Главное здесь — донатные D.ADMIN и D.OWNER: поиск
     * подстроки «ADMIN» принял бы их за состав и перестал бы проверять донатеров.
     */
    private static int ranks(Report report) {
        ModConfig config = new ModConfig();
        report.line("ранги состава: " + config.staffRanks);
        report.line("потолки по рангу: " + config.rankMuteLimits);
        report.line("");

        int failed = 0;
        failed += staffCase(report, config, "L | Vampires | ORGANIZATOR ", true);
        failed += staffCase(report, config, "G | клан | MODER ", true);
        failed += staffCase(report, config, "G | клан | ST.HELPER ", true);
        failed += staffCase(report, config, "G | клан | DEVELOPER ", true);
        failed += staffCase(report, config, "G | Без клана | D.ADMIN ", false);
        failed += staffCase(report, config, "L | The_Sopranos | D.OWNER ", false);
        failed += staffCase(report, config, "G | Без клана | ХОЗЯИН ", false);
        failed += staffCase(report, config, "L | клан | VLASTELIN ", false);
        failed += staffCase(report, config, "G | клан | SHULKER ", false);
        report.line("");
        failed += capCase(report, config, "L | клан | YOUTUBE ", "4h", "10m");
        failed += capCase(report, config, "L | клан | TIKTOK ", "7d", "10m");
        failed += capCase(report, config, "L | клан | YOUTUBE ", "10m", "10m");
        failed += capCase(report, config, "L | клан | VLASTELIN ", "4h", "4h");
        failed += capCase(report, config, "G | Без клана | D.ADMIN ", "permanent", "permanent");

        report.line("");
        report.line("провалено: " + failed);
        System.out.println("RANKS " + (failed == 0 ? "PASS" : "FAIL") + " failed=" + failed);
        return failed == 0 ? 0 : 1;
    }

    private static int staffCase(Report report, ModConfig config, String prefix, boolean expected) {
        boolean staff = RankPolicy.isStaff(prefix, config);
        boolean ok = staff == expected;
        report.line((ok ? "ok    " : "FAIL  ") + pad(prefix.strip(), 34)
                + (staff ? "состав — не проверяем" : "обычный игрок — проверяем")
                + ", ожидалось " + (expected ? "состав" : "обычный игрок"));
        return ok ? 0 : 1;
    }

    private static int capCase(Report report, ModConfig config, String prefix,
                               String wanted, String expected) {
        MuteDuration capped = RankPolicy.cap(MuteDuration.parse(wanted), prefix, config);
        boolean ok = capped.raw().equals(expected);
        report.line((ok ? "ok    " : "FAIL  ") + pad(prefix.strip(), 34)
                + pad(wanted, 12) + "-> " + pad(capped.raw(), 12)
                + "ожидалось " + expected);
        return ok ? 0 : 1;
    }

    /**
     * Проверка буфера чата: за флуд в протокол обязаны попасть все повторы,
     * иначе наказание нечем подтвердить.
     */
    private static int proof(Report report) {
        ChatLogBuffer buffer = new ChatLogBuffer(100);
        long base = 1_000_000_000L;
        buffer.add(base, "Flooder » продам сет", "Flooder");
        buffer.add(base + 5_000, "Other » привет", "Other");
        buffer.add(base + 20_000, "Flooder » продам сет", "Flooder");
        buffer.add(base + 40_000, "Flooder » продам сет", "Flooder");
        buffer.add(base + 200_000, "Flooder » это было давно", "Flooder");
        long now = base + 45_000;

        int failed = 0;
        List<ChatLogBuffer.Line> own = buffer.byPlayer("Flooder", 60, now);
        failed += expect(report, "все 3 повтора за окно 60 сек", own.size(), 3);

        List<ChatLogBuffer.Line> other = buffer.byPlayer("flooder", 60, now);
        failed += expect(report, "ник без учёта регистра", other.size(), 3);

        List<ChatLogBuffer.Line> narrow = buffer.byPlayer("Flooder", 30, now);
        failed += expect(report, "окно 30 сек отсекает раннее", narrow.size(), 2);

        // Именно этот случай пришёл из боя: 4 повтора за 1:47, но в минутное
        // окно попадают только последние три — протокол обязан это показывать.
        long realBase = 2_000_000_000L;
        ChatLogBuffer real = new ChatLogBuffer(100);
        real.add(realBase, "famik14 » паркур", "famik14");
        real.add(realBase + 66_000, "famik14 » паркур", "famik14");
        real.add(realBase + 86_000, "famik14 » паркур", "famik14");
        real.add(realBase + 107_000, "famik14 » паркур", "famik14");
        long realNow = realBase + 107_000;
        List<ChatLogBuffer.Line> shown = real.byPlayer("famik14", 120, realNow);
        failed += expect(report, "показываем за 120 сек все 4", shown.size(), 4);
        failed += expect(report, "в минутное окно попали 3",
                ChatLogBuffer.countInWindow(shown, realNow, 60), 3);

        failed += expect(report, "контекст берёт все строки", buffer.tail(40).size(), 5);
        failed += expect(report, "контекст ограничен запросом", buffer.tail(2).size(), 2);

        report.line("");
        report.line("провалено: " + failed);
        System.out.println("PROOF " + (failed == 0 ? "PASS" : "FAIL") + " failed=" + failed);
        return failed == 0 ? 0 : 1;
    }

    private static int expect(Report report, String what, int actual, int expected) {
        boolean ok = actual == expected;
        report.line((ok ? "ok    " : "FAIL  ") + pad(what, 40) + actual + ", ожидалось " + expected);
        return ok ? 0 : 1;
    }

    /** Показывает все нормализованные варианты — так видно, почему обход не совпал. */
    private static int norm(String text, Report report) {
        if (text.isBlank()) {
            System.out.println("NORM ERROR: empty text");
            return 2;
        }
        report.line("исходник: " + text);
        report.line("");
        for (NormalizedText nt : TextNormalizer.allVariants(text)) {
            report.line(pad(nt.variant().folding().name(), 20)
                    + pad(nt.variant().mode().name(), 9)
                    + "-> " + nt.value());
        }
        System.out.println("NORM ok");
        return 0;
    }

    // ------------------------------------------------------------ утилиты

    private static RuleEngine loadEngine(Path rulesPath) throws IOException {
        if (Files.notExists(rulesPath)) {
            throw new IOException("Файл правил не найден: " + rulesPath.toAbsolutePath());
        }
        try (Reader r = Files.newBufferedReader(rulesPath, StandardCharsets.UTF_8)) {
            RuleData data = new Gson().fromJson(r, RuleData.class);
            if (data == null) {
                throw new IOException("Файл правил пустой или не разобран: " + rulesPath);
            }
            if (data.settings == null) {
                data.settings = new RuleData.Settings();
            }
            if (data.exceptions == null) {
                data.exceptions = new RuleData.Exceptions();
            }
            return RuleEngine.compile(data);
        }
    }

    private static List<String> readMaybeGzip(Path path) throws IOException {
        if (path.getFileName().toString().endsWith(".gz")) {
            try (var in = new java.util.zip.GZIPInputStream(Files.newInputStream(path));
                 var reader = new java.io.BufferedReader(
                         new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().toList();
            }
        }
        return Files.readAllLines(path, StandardCharsets.UTF_8);
    }

    private static List<String> ruleIds(ScanResult result) {
        List<String> ids = new ArrayList<>();
        for (Violation v : result.violations()) {
            ids.add(v.ruleId());
        }
        return ids;
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s + " " : s + " ".repeat(width - s.length());
    }

    private static Path takeOption(List<String> args, String name, Path fallback) {
        int i = args.indexOf(name);
        if (i < 0 || i + 1 >= args.size()) {
            return fallback;
        }
        Path value = Path.of(args.get(i + 1));
        args.remove(i + 1);
        args.remove(i);
        return value;
    }

    private static Path requirePath(List<String> rest, String what) {
        if (rest.isEmpty()) {
            throw new IllegalArgumentException("Не хватает аргумента: " + what);
        }
        return Path.of(rest.get(0));
    }

    private static void usage() {
        System.out.println("""
                RulesCli - proverka pravil ToolRise ChatMod

                  verify                 progon korpusa tools/testcases.txt
                  check <file>           progon proizvolnogo faila (stroka = soobschenie)
                  scan <log> [log...]    razbor logov Minecraft, spisok NE poimannyh soobschenii
                  test <text>            odin tekst
                  norm <text>            vse normalizovannye varianty teksta
  flood                  proverka detektora fluda (zavisit ot istorii)
  nicks                  proverka: niki igrokov ne schitayutsya narusheniem
  ranks                  proverka: sostav ne trogaem, mediynym potolok muta
  proof                  proverka bufera chata dlya protokolov nakazaniy

                Opcii: --rules <path> --cases <path> --out <path>
                Otchet pishetsya v fail v UTF-8 (--out, default build/rules-report.txt).
                """);
    }

    /** Пишет отчёт в файл в UTF-8: консоль Windows кириллицу портит. */
    private static final class Report implements AutoCloseable {
        private final BufferedWriter writer;

        Report(Path path) throws IOException {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        }

        void line(String text) {
            try {
                writer.write(text);
                writer.newLine();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }
}
