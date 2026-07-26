package space.toolrise.chatmod.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Отражение файла rules.json «как есть». Никакой логики — только данные,
 * которые GSON читает с диска. Компиляция в рабочие структуры — в {@link RuleEngine}.
 */
public class RuleData {

    public int schema = 1;
    public Settings settings = new Settings();
    public Map<String, List<String>> groups = new LinkedHashMap<>();
    public Exceptions exceptions = new Exceptions();
    public List<RuleDef> rules = new ArrayList<>();

    public static class Settings {
        /** Сколько лишних символов допускается в конце слова (морфология: «мразь» → «мразью»). */
        public int suffixTolerance = 2;
        public int capsMinLength = 8;
        public double capsRatio = 0.7;
        public int repeatCharThreshold = 8;
        /** Флуд по определению проекта: 4 и более одинаковых сообщений за минуту. */
        public int spamWindowSeconds = 60;
        public int spamRepeatCount = 4;
        /** Отдельный детектор быстрого потока разных сообщений — не то же самое, что флуд. */
        public int floodWindowSeconds = 10;
        public int floodMessageCount = 8;
        /** Домены/ссылки проекта — они не считаются рекламой. */
        public List<String> adWhitelist = new ArrayList<>();
    }

    public static class Exceptions {
        public List<String> phrases = new ArrayList<>();
        public List<String> technicalPhrases = new ArrayList<>();
    }

    public static class RuleDef {
        public String id = "?";
        public String name = "";
        public String description = "";
        /** «10m», «4h», «7d», «permanent». */
        public String mute = "10m";
        public boolean enabled = true;
        /**
         * Сколько отдельных совпадений нужно, чтобы это считалось нарушением.
         * Для 2.9 стоит 6: одно матерное слово по правилам проекта не наказывается.
         */
        public int minMatches = 1;
        public List<String> phrases = new ArrayList<>();
        public List<Combo> combos = new ArrayList<>();
        public List<String> detectors = new ArrayList<>();
    }

    /** Срабатывает, только если в сообщении есть что-то из {@code a} И что-то из {@code b}. */
    public static class Combo {
        public List<String> a = new ArrayList<>();
        public List<String> b = new ArrayList<>();
    }
}
