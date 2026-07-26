package space.toolrise.chatmod.chat;

import space.toolrise.chatmod.config.ModConfig;
import space.toolrise.chatmod.rules.MuteDuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Решения, зависящие от ранга игрока. Ранг стоит в строке чата перед ником:
 * {@code G | Без клана | ORGANIZATOR KBAC1K » текст}.
 *
 * <p>Ранг сравнивается <b>целым словом</b>, а не куском строки, и это принципиально:
 * на сервере есть донатные ранги {@code D.ADMIN} и {@code D.OWNER}. Поиск подстроки
 * «ADMIN» принял бы донатера за администратора и перестал его проверять.
 */
public final class RankPolicy {

    private RankPolicy() {
    }

    /** Разбивает префикс строки чата на отдельные слова-ранги. */
    public static List<String> tokens(String prefix) {
        List<String> out = new ArrayList<>();
        if (prefix == null || prefix.isBlank()) {
            return out;
        }
        for (String token : prefix.split("[\\s|\\[\\]()»<>]+")) {
            String t = token.strip().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** Игрок из состава — таких мод не проверяет вообще. */
    public static boolean isStaff(String prefix, ModConfig config) {
        if (!config.ignoreStaffRanks || config.staffRanks.isEmpty()) {
            return false;
        }
        List<String> tokens = tokens(prefix);
        for (String rank : config.staffRanks) {
            if (rank != null && tokens.contains(rank.strip().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Потолок мута для ранга: медийщикам больше 10 минут выдавать нельзя.
     *
     * @return самый строгий разрешённый срок или {@code null}, если ограничений нет
     */
    public static MuteDuration muteLimit(String prefix, ModConfig config) {
        if (config.rankMuteLimits == null || config.rankMuteLimits.isEmpty()) {
            return null;
        }
        List<String> tokens = tokens(prefix);
        MuteDuration limit = null;
        for (Map.Entry<String, String> entry : config.rankMuteLimits.entrySet()) {
            String rank = entry.getKey();
            if (rank == null || !tokens.contains(rank.strip().toLowerCase(Locale.ROOT))) {
                continue;
            }
            MuteDuration candidate = MuteDuration.parse(entry.getValue());
            // Если рангов несколько, действует самый мягкий потолок.
            if (limit == null || candidate.compareTo(limit) < 0) {
                limit = candidate;
            }
        }
        return limit;
    }

    /** Применяет потолок ранга к сроку. */
    public static MuteDuration cap(MuteDuration wanted, String prefix, ModConfig config) {
        MuteDuration limit = muteLimit(prefix, config);
        if (limit == null || wanted == null) {
            return wanted;
        }
        return wanted.compareTo(limit) > 0 ? limit : wanted;
    }

    /** Название ранга, из-за которого сработало ограничение — для текста в чате. */
    public static String limitingRank(String prefix, ModConfig config) {
        if (config.rankMuteLimits == null) {
            return null;
        }
        List<String> tokens = tokens(prefix);
        for (String rank : config.rankMuteLimits.keySet()) {
            if (rank != null && tokens.contains(rank.strip().toLowerCase(Locale.ROOT))) {
                return rank;
            }
        }
        return null;
    }
}
