package space.toolrise.chatmod.config;

import java.util.ArrayList;
import java.util.List;

/** Настройки мода. Файл: {@code config/trchatmod/config.json}. */
public class ModConfig {

    /**
     * Версия формата настроек. Увеличивается, когда меняются значения по умолчанию,
     * которые уже лежат в файле пользователя: без этого старый config.json навсегда
     * перекрывает новые дефолты, и обновление мода ничего не меняет.
     */
    public static final int CURRENT_VERSION = 1;

    /** 0 — файл создан до появления версий. Ставится при сохранении. */
    public int configVersion = 0;

    public enum HighlightMode {
        /** Красить всё сообщение целиком. */
        FULL_LINE,
        /** Красить только сработавшие слова, остальное оставить как есть. */
        WORDS
    }

    /** Общий выключатель — можно снять на время, не удаляя мод. */
    public boolean enabled = true;

    /** Не проверять свои собственные сообщения. */
    public boolean ignoreOwnMessages = true;

    /** Не проверять сообщения ников из groups.staffNicks в rules.json. */
    public boolean ignoreStaff = true;

    /** Ники, которые не проверяются никогда (например, боты). */
    public List<String> ignoredNicks = new ArrayList<>();

    public HighlightMode highlightMode = HighlightMode.FULL_LINE;

    /** Метка перед нарушением. Пусто — не добавлять. */
    public String markerPrefix = "[!] ";

    /** Дописывать в метку номер правила: {@code [!2.1]}. */
    public boolean showRuleIdInMarker = true;

    /** Звук при обнаружении нарушения. */
    public boolean playSound = true;

    /** ЛКМ открывает окно подтверждения. Если false — ЛКМ сразу выдаёт мут. */
    public boolean confirmBeforeMute = true;

    /** Ctrl+ЛКМ выдаёт рекомендованный мут мгновенно, минуя окно. */
    public boolean ctrlClickInstantMute = true;

    /**
     * Шаблон команды. Плейсхолдеры: {@code {player}}, {@code {time}}, {@code {reason}}.
     * Ведущий слэш не нужен.
     */
    public String muteCommand = "mute {player} {time} {reason}";

    /** Чем заменить {@code {time}} для вечного мута. */
    public String permanentToken = "perm";

    /**
     * Шаблон причины. Плейсхолдеры: {@code {ruleId}}, {@code {ruleName}}, {@code {ruleDesc}}.
     * По умолчанию — только номер правила: сервер ждёт «/mute игрок 4h 2.4».
     */
    public String reasonTemplate = "{ruleId}";

    /** Шаблон снятия мута для {@code /trchatmod undo}. Плейсхолдер: {@code {player}}. */
    public String unmuteCommand = "unmute {player}";

    // ------------------------------------------------------------ автомат

    /** Автоматическая выдача мутов. Включается кнопкой (по умолчанию F8) или /trchatmod auto. */
    public boolean autoMode = false;

    /** Тестовый прогон: автомат всё считает и пишет в чат, но команду не отправляет. */
    public boolean autoDryRun = false;

    /**
     * Потолок для автомата. Всё, что строже, уходит в очередь на ручную проверку
     * (/trchatmod queue). Поставь {@code permanent}, чтобы автомат выдавал вообще всё.
     */
    public String autoMaxMute = "4h";

    /** Номера правил, которые автомат не трогает никогда, даже в пределах потолка. */
    public List<String> autoSkipRules = new ArrayList<>();

    /** Не наказывать автоматом, если ника нет в списке игроков онлайн. */
    public boolean autoRequireOnlineNick = true;

    /** Не наказывать одного и того же игрока чаще, чем раз в столько секунд. */
    public int autoCooldownSeconds = 90;

    /** Предохранитель: больше стольких автомутов в минуту — остальное в очередь. */
    public int autoMaxPerMinute = 6;

    /** Строки чата, по которым автомат не срабатывает никогда (эхо самого плагина наказаний). */
    public List<String> autoSkipLinePatterns = new ArrayList<>(List.of(
            "(?i)\\b(замучен|размучен|выдан\\s+мут|получил\\s+мут|muted|unmuted|наказан)\\b",
            "(?i)\\b(забанен|выдан\\s+бан|banned)\\b"
    ));

    /** Писать выданные наказания в config/trchatmod/punishments.csv. */
    public boolean logPunishments = true;

    /** Проверять системные сообщения (чат серверных плагинов приходит именно так). */
    public boolean scanSystemMessages = true;

    /** Проверять подписанный чат игроков (ванильный формат). */
    public boolean scanPlayerChat = true;

    /**
     * Для подписанного чата: перерисовать сообщение целиком (true) или оставить
     * оригинал и добавить отдельную кликабельную строку-предупреждение (false).
     */
    public boolean replacePlayerChat = true;

    /** Сколько последних помеченных сообщений держать в памяти для клика. */
    public int maxStoredMessages = 200;

    /**
     * Проверять только сообщения, у которых удалось определить отправителя.
     *
     * <p>Системные сообщения сервера — рамки из «═», объявления, реклама кейсов —
     * ника не имеют, а значит наказать за них всё равно некого. Отключать стоит,
     * только если шаблоны чата не настроены и ничего не ловится.
     */
    public boolean requireKnownSender = true;

    /**
     * Как из строки чата достать ник и текст. Проверяются по порядку, первый
     * подошедший выигрывает. Обязательны именованные группы {@code nick} и {@code msg}.
     *
     * <p>Первый шаблон — формат ToolRise:
     * {@code [17:14:28] G | Без клана | VLASTELIN carabulca » текст}.
     */
    public List<String> chatPatterns = new ArrayList<>(List.of(
            "^.*?(?<nick>[A-Za-z0-9_]{3,16})\\s*»\\s*(?<msg>.+)$",
            "^\\[[^\\]]{1,32}\\]\\s*<(?<nick>[A-Za-z0-9_]{3,16})>\\s*(?<msg>.+)$",
            "^<(?<nick>[A-Za-z0-9_]{3,16})>\\s*(?<msg>.+)$",
            "^\\[[^\\]]{1,32}\\]\\s*(?<nick>[A-Za-z0-9_]{3,16})\\s*[:>]\\s*(?<msg>.+)$",
            "^(?<nick>[A-Za-z0-9_]{3,16})\\s*[:>]\\s*(?<msg>.+)$",
            "^.{0,48}?(?<nick>[A-Za-z0-9_]{3,16})\\s*[:>]\\s+(?<msg>.+)$"
    ));

    /** Подстановка значений в шаблон команды. */
    public String buildCommand(String player, String time, String reason) {
        return muteCommand
                .replace("{player}", player == null ? "" : player)
                .replace("{time}", time == null ? "" : time)
                .replace("{reason}", reason == null ? "" : reason)
                .trim();
    }

    public String buildUnmuteCommand(String player) {
        return unmuteCommand.replace("{player}", player == null ? "" : player).trim();
    }

    public String buildReason(String ruleId, String ruleName, String ruleDesc) {
        return reasonTemplate
                .replace("{ruleId}", ruleId == null ? "" : ruleId)
                .replace("{ruleName}", ruleName == null ? "" : ruleName)
                .replace("{ruleDesc}", ruleDesc == null ? "" : ruleDesc)
                .trim();
    }
}
