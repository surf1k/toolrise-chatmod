package space.toolrise.chatmod.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Нормализация текста для поиска нарушений. Задача — свести к общему виду типовые
 * способы обхода фильтра: смешение кириллицы и латиницы («cyka», «пидoрас» с латинской o),
 * транслит («shalava», «idi nahui»), цифры вместо букв («х0хол»), растягивание букв
 * («хуууй»), разделители («х.у.й»).
 *
 * <p>Для каждого сообщения строится шесть вариантов: три способа сведения букв
 * ({@link Folding}) × два режима разделителей ({@link Mode}). Паттерн ищется во всех
 * вариантах своего алфавита, совпадения объединяются по координатам исходной строки.
 */
public final class TextNormalizer {

    /** К какому алфавиту приведён текст — по нему выбирается, какие паттерны применять. */
    public enum Alphabet {
        CYRILLIC,
        LATIN
    }

    /** Способ сведения букв. */
    public enum Folding {
        /** Латинские двойники → кириллица: «cyka» → «сука». */
        CYRILLIC_HOMOGLYPH(Alphabet.CYRILLIC),
        /** Транслит: «shalava» → «шалава», «nahui» → «нахуи». */
        TRANSLIT(Alphabet.CYRILLIC),
        /** Кириллические двойники → латиница: «fuсk» с русской «с» → «fuck». */
        LATIN_HOMOGLYPH(Alphabet.LATIN);

        private final Alphabet alphabet;

        Folding(Alphabet alphabet) {
            this.alphabet = alphabet;
        }

        public Alphabet alphabet() {
            return alphabet;
        }

        public static Folding forAlphabet(Alphabet alphabet) {
            return alphabet == Alphabet.LATIN ? LATIN_HOMOGLYPH : CYRILLIC_HOMOGLYPH;
        }
    }

    /** Что делать с разделителями. */
    public enum Mode {
        /** Пунктуация → пробел, пробелы схлопываются. Обычный текст. */
        KEEP,
        /** Пунктуация удаляется, пробелы остаются. Ловит «т_в_а_р_ь короче», «х.у.й ты». */
        STRIP,
        /** Пунктуация и пробелы удаляются полностью. Ловит «х у й» и «т в а р ь». */
        SQUEEZE
    }

    public record Variant(Folding folding, Mode mode) {
        public Alphabet alphabet() {
            return folding.alphabet();
        }
    }

    public static final List<Variant> ALL_VARIANTS = buildVariants();

    private static List<Variant> buildVariants() {
        List<Variant> list = new ArrayList<>();
        for (Folding f : Folding.values()) {
            for (Mode m : Mode.values()) {
                list.add(new Variant(f, m));
            }
        }
        return List.copyOf(list);
    }

    private static final Map<Character, Character> TO_CYRILLIC = new HashMap<>();
    private static final Map<Character, Character> TO_LATIN = new HashMap<>();
    private static final Map<Character, Character> TRANSLIT_SINGLE = new HashMap<>();
    private static final Map<String, Character> TRANSLIT_MULTI = new LinkedHashMap<>();
    private static final Map<Character, Character> DIGITS_CYRILLIC = new HashMap<>();
    private static final Map<Character, Character> DIGITS_LATIN = new HashMap<>();

    private static final int MAX_DIGRAPH = 3;

    static {
        // Латинские буквы, визуально совпадающие с кириллическими.
        TO_CYRILLIC.put('a', 'а');
        TO_CYRILLIC.put('b', 'в');
        TO_CYRILLIC.put('c', 'с');
        TO_CYRILLIC.put('e', 'е');
        TO_CYRILLIC.put('h', 'н');
        TO_CYRILLIC.put('i', 'и');
        TO_CYRILLIC.put('k', 'к');
        TO_CYRILLIC.put('m', 'м');
        TO_CYRILLIC.put('o', 'о');
        TO_CYRILLIC.put('p', 'р');
        TO_CYRILLIC.put('t', 'т');
        TO_CYRILLIC.put('u', 'и');
        TO_CYRILLIC.put('x', 'х');
        TO_CYRILLIC.put('y', 'у');

        // Кириллица, визуально совпадающая с латиницей.
        TO_LATIN.put('а', 'a');
        TO_LATIN.put('в', 'b');
        TO_LATIN.put('с', 'c');
        TO_LATIN.put('е', 'e');
        TO_LATIN.put('ё', 'e');
        TO_LATIN.put('н', 'h');
        TO_LATIN.put('и', 'i');
        TO_LATIN.put('к', 'k');
        TO_LATIN.put('м', 'm');
        TO_LATIN.put('о', 'o');
        TO_LATIN.put('р', 'p');
        TO_LATIN.put('т', 't');
        TO_LATIN.put('у', 'y');
        TO_LATIN.put('х', 'x');

        // Транслит: сначала сочетания (длинные вперёд), потом одиночные буквы.
        TRANSLIT_MULTI.put("sch", 'щ');
        TRANSLIT_MULTI.put("shh", 'щ');
        TRANSLIT_MULTI.put("sh", 'ш');
        TRANSLIT_MULTI.put("ch", 'ч');
        TRANSLIT_MULTI.put("zh", 'ж');
        TRANSLIT_MULTI.put("kh", 'х');
        TRANSLIT_MULTI.put("ts", 'ц');
        TRANSLIT_MULTI.put("yu", 'ю');
        TRANSLIT_MULTI.put("ya", 'я');
        TRANSLIT_MULTI.put("yo", 'ё');
        TRANSLIT_MULTI.put("ju", 'ю');
        TRANSLIT_MULTI.put("ja", 'я');
        TRANSLIT_MULTI.put("jo", 'ё');

        //              a б ц д е ф г х и ж к л м н о п к р с т у в в х ы з
        String latin = "abcdefghijklmnopqrstuvwxyz";
        String cyril = "абцдефгхижклмнопкрстуввхыз";
        for (int i = 0; i < latin.length(); i++) {
            TRANSLIT_SINGLE.put(latin.charAt(i), cyril.charAt(i));
        }

        // Цифры вместо букв — применяются только рядом с буквой.
        DIGITS_CYRILLIC.put('0', 'о');
        DIGITS_CYRILLIC.put('1', 'и');
        DIGITS_CYRILLIC.put('3', 'е');
        DIGITS_CYRILLIC.put('4', 'ч');
        DIGITS_CYRILLIC.put('6', 'б');
        DIGITS_CYRILLIC.put('9', 'д');
        DIGITS_LATIN.put('0', 'o');
        DIGITS_LATIN.put('1', 'i');
        DIGITS_LATIN.put('3', 'e');
        DIGITS_LATIN.put('4', 'a');
        DIGITS_LATIN.put('5', 's');
        DIGITS_LATIN.put('7', 't');
    }

    private TextNormalizer() {
    }

    /** Убирает коды форматирования Minecraft (§a, §x§f§f...) — они мешают поиску. */
    public static String stripFormatting(String input) {
        if (input == null || input.indexOf('§') < 0) {
            return input == null ? "" : input;
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '§' && i + 1 < input.length()) {
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static List<NormalizedText> allVariants(String input) {
        List<NormalizedText> list = new ArrayList<>(ALL_VARIANTS.size());
        for (Variant v : ALL_VARIANTS) {
            list.add(normalize(input, v));
        }
        return list;
    }

    public static NormalizedText normalize(String input, Variant variant) {
        return normalize(input, variant.folding(), variant.mode());
    }

    public static NormalizedText normalize(String input, Alphabet alphabet, Mode mode) {
        return normalize(input, Folding.forAlphabet(alphabet), mode);
    }

    public static NormalizedText normalize(String input, Folding folding, Mode mode) {
        String src = input == null ? "" : input;
        Map<Character, Character> digits =
                folding == Folding.LATIN_HOMOGLYPH ? DIGITS_LATIN : DIGITS_CYRILLIC;

        StringBuilder out = new StringBuilder(src.length());
        int[] map = new int[src.length() + 1];
        char lastLetter = 0;

        int i = 0;
        while (i < src.length()) {
            char raw = Character.toLowerCase(src.charAt(i));
            int consumed = 1;
            char c;

            if (Character.isLetter(raw)) {
                Character folded = null;
                if (folding == Folding.TRANSLIT && raw >= 'a' && raw <= 'z') {
                    int digraph = matchDigraph(src, i);
                    if (digraph > 0) {
                        folded = TRANSLIT_MULTI.get(lower(src, i, digraph));
                        consumed = digraph;
                    }
                    if (folded == null) {
                        folded = TRANSLIT_SINGLE.get(raw);
                        consumed = 1;
                    }
                } else if (folding == Folding.CYRILLIC_HOMOGLYPH) {
                    folded = TO_CYRILLIC.get(raw);
                } else if (folding == Folding.LATIN_HOMOGLYPH) {
                    folded = TO_LATIN.get(raw);
                }
                c = folded == null ? raw : folded;
                // Схлопываем растянутые буквы: «хуууй» → «хуй», «приииивет» → «привет».
                if (c == lastLetter) {
                    i += consumed;
                    continue;
                }
                lastLetter = c;
            } else if (Character.isDigit(raw)) {
                Character asLetter = neighbourIsLetter(src, i) ? digits.get(raw) : null;
                if (asLetter != null) {
                    c = asLetter;
                    if (c == lastLetter) {
                        i++;
                        continue;
                    }
                    lastLetter = c;
                } else {
                    c = raw;
                    lastLetter = 0;
                }
            } else {
                lastLetter = 0;
                boolean space = Character.isWhitespace(raw);
                // Пробел выживает везде кроме SQUEEZE; пунктуация — только в KEEP.
                boolean keepAsSpace = space ? mode != Mode.SQUEEZE : mode == Mode.KEEP;
                if (!keepAsSpace || out.length() == 0 || out.charAt(out.length() - 1) == ' ') {
                    i++;
                    continue;
                }
                c = ' ';
            }

            map[out.length()] = i;
            out.append(c);
            i += consumed;
        }

        while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
            out.setLength(out.length() - 1);
        }
        map[out.length()] = src.length();

        int[] trimmed = new int[out.length() + 1];
        System.arraycopy(map, 0, trimmed, 0, trimmed.length);
        return new NormalizedText(src, out.toString(), trimmed, new Variant(folding, mode));
    }

    /** @return длина найденного латинского сочетания или 0 */
    private static int matchDigraph(String src, int at) {
        for (int len = MAX_DIGRAPH; len >= 2; len--) {
            if (at + len > src.length()) {
                continue;
            }
            if (TRANSLIT_MULTI.containsKey(lower(src, at, len))) {
                return len;
            }
        }
        return 0;
    }

    private static String lower(String src, int at, int len) {
        return src.substring(at, at + len).toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean neighbourIsLetter(String s, int i) {
        if (i > 0 && Character.isLetter(s.charAt(i - 1))) {
            return true;
        }
        return i + 1 < s.length() && Character.isLetter(s.charAt(i + 1));
    }

    /**
     * Приводит фразу из базы правил к тому же виду, в котором ищется текст.
     * Возвращает слова фразы; между ними в регулярке ставится {@code \s*}.
     */
    public static List<String> normalizePhraseWords(String phrase, Alphabet alphabet) {
        NormalizedText nt = normalize(phrase, Folding.forAlphabet(alphabet), Mode.KEEP);
        List<String> words = new ArrayList<>();
        for (String w : nt.value().split("\\s+")) {
            if (!w.isBlank()) {
                words.add(w);
            }
        }
        return words;
    }
}
