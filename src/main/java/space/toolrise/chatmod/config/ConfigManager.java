package space.toolrise.chatmod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import space.toolrise.chatmod.TrChatMod;
import space.toolrise.chatmod.rules.RuleData;
import space.toolrise.chatmod.rules.RuleEngine;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Загрузка и сохранение конфигурации. Оба файла лежат в {@code config/trchatmod/}:
 * {@code config.json} — настройки мода, {@code rules.json} — база правил.
 * Правила при первом запуске копируются из мода, дальше редактируются пользователем.
 */
public final class ConfigManager {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final String DEFAULT_RULES_RESOURCE = "/trchatmod/default_rules.json";

    /**
     * Версия базы правил, которую несёт мод. Если у пользователя схема ниже —
     * файл устарел: он был создан прошлой версией мода и не знает про новые поля
     * (например, про порог minMatches у 2.9). Старый файл сохраняется рядом,
     * на его место кладётся свежий.
     */
    private static final int RULES_SCHEMA = 2;

    private final Path directory;
    private final Path configFile;
    private final Path rulesFile;

    private ModConfig config = new ModConfig();
    private RuleData ruleData = new RuleData();
    private RuleEngine engine = RuleEngine.compile(new RuleData());

    public ConfigManager() {
        this.directory = FabricLoader.getInstance().getConfigDir().resolve(TrChatMod.MOD_ID);
        this.configFile = directory.resolve("config.json");
        this.rulesFile = directory.resolve("rules.json");
    }

    public ModConfig config() {
        return config;
    }

    public RuleEngine engine() {
        return engine;
    }

    public RuleData ruleData() {
        return ruleData;
    }

    public Path directory() {
        return directory;
    }

    /** Полная перезагрузка с диска. Возвращает текст отчёта для чата. */
    public String load() {
        StringBuilder report = new StringBuilder();
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            TrChatMod.LOGGER.error("Не удалось создать папку конфигурации", e);
            return "не удалось создать папку " + directory;
        }

        // config.json
        if (Files.notExists(configFile)) {
            config = new ModConfig();
            config.configVersion = ModConfig.CURRENT_VERSION;
            saveConfig();
            report.append("создан config.json; ");
        } else {
            try (Reader r = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                ModConfig loaded = GSON.fromJson(r, ModConfig.class);
                config = loaded == null ? new ModConfig() : loaded;
                normalise(config);
                String migrated = migrateConfig(config);
                if (migrated != null) {
                    saveConfig();
                    report.append(migrated).append("; ");
                }
            } catch (IOException | JsonSyntaxException e) {
                TrChatMod.LOGGER.error("Ошибка чтения config.json, используются значения по умолчанию", e);
                config = new ModConfig();
                config.configVersion = ModConfig.CURRENT_VERSION;
                report.append("config.json битый, взяты значения по умолчанию; ");
            }
        }

        // rules.json
        if (Files.notExists(rulesFile)) {
            report.append(writeDefaultRules() ? "создан rules.json; " : "не удалось создать rules.json; ");
        }

        RuleData data = readRules();
        if (data == null) {
            data = readDefaultRules();
            report.append("rules.json не прочитан, взята встроенная база; ");
        } else if (data.schema < RULES_SCHEMA) {
            // Старый файл нельзя просто дополнить: он не знает про новые поля,
            // и мод молча продолжал бы работать по прошлым правилам.
            int oldSchema = data.schema;
            String backup = backupRules(oldSchema);
            if (writeDefaultRules()) {
                RuleData fresh = readRules();
                if (fresh != null) {
                    data = fresh;
                }
                report.append("rules.json обновлён (схема ").append(oldSchema)
                        .append(" → ").append(RULES_SCHEMA).append("), старый сохранён как ")
                        .append(backup).append("; ");
                TrChatMod.LOGGER.warn("rules.json устарел (схема {}) и заменён свежим; прежний файл: {}",
                        oldSchema, backup);
            }
        }
        ruleData = data;
        engine = RuleEngine.compile(data);

        report.append("правил: ").append(engine.ruleCount())
                .append(", паттернов: ").append(engine.patternCount());
        if (!engine.compileWarnings().isEmpty()) {
            report.append(", предупреждений: ").append(engine.compileWarnings().size());
            engine.compileWarnings().forEach(w -> TrChatMod.LOGGER.warn("rules.json: {}", w));
        }
        return report.toString();
    }

    /** Кладёт на место rules.json свежую копию из мода. */
    private boolean writeDefaultRules() {
        try (InputStream in = ConfigManager.class.getResourceAsStream(DEFAULT_RULES_RESOURCE)) {
            if (in == null) {
                throw new IOException("ресурс " + DEFAULT_RULES_RESOURCE + " не найден в моде");
            }
            Files.copy(in, rulesFile, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            TrChatMod.LOGGER.error("Не удалось записать rules.json", e);
            return false;
        }
    }

    /** Сохраняет прежний rules.json рядом. @return имя файла-копии */
    private String backupRules(int schema) {
        String name = "rules.v" + schema + ".json.bak";
        Path backup = directory.resolve(name);
        for (int i = 2; Files.exists(backup) && i < 100; i++) {
            name = "rules.v" + schema + "." + i + ".json.bak";
            backup = directory.resolve(name);
        }
        try {
            Files.copy(rulesFile, backup, StandardCopyOption.REPLACE_EXISTING);
            return name;
        } catch (IOException e) {
            TrChatMod.LOGGER.error("Не удалось сохранить копию rules.json", e);
            return "(копию сделать не удалось)";
        }
    }

    /**
     * Подтягивает значения по умолчанию, которые изменились в новых версиях мода.
     * Трогает только те поля, ради которых поднята версия, — остальные настройки
     * пользователя остаются как есть.
     *
     * @return текст для отчёта или {@code null}, если миграция не понадобилась
     */
    private static String migrateConfig(ModConfig config) {
        if (config.configVersion >= ModConfig.CURRENT_VERSION) {
            return null;
        }
        int from = config.configVersion;
        ModConfig defaults = new ModConfig();

        if (from < 1) {
            // Появился разбор формата ToolRise («ник » текст») и причина мута
            // сократилась до номера правила — старые значения это перекрывали.
            config.chatPatterns = new java.util.ArrayList<>(defaults.chatPatterns);
            config.reasonTemplate = defaults.reasonTemplate;
        }

        config.configVersion = ModConfig.CURRENT_VERSION;
        TrChatMod.LOGGER.info("config.json обновлён с версии {} до {}", from, ModConfig.CURRENT_VERSION);
        return "config.json обновлён (версия " + from + " → " + ModConfig.CURRENT_VERSION
                + "): обновлены chatPatterns и reasonTemplate";
    }

    private RuleData readRules() {
        if (Files.notExists(rulesFile)) {
            return null;
        }
        try (Reader r = Files.newBufferedReader(rulesFile, StandardCharsets.UTF_8)) {
            RuleData data = GSON.fromJson(r, RuleData.class);
            if (data == null) {
                return null;
            }
            if (data.settings == null) {
                data.settings = new RuleData.Settings();
            }
            if (data.exceptions == null) {
                data.exceptions = new RuleData.Exceptions();
            }
            return data;
        } catch (IOException | JsonSyntaxException e) {
            TrChatMod.LOGGER.error("Ошибка чтения rules.json", e);
            return null;
        }
    }

    private RuleData readDefaultRules() {
        try (InputStream in = ConfigManager.class.getResourceAsStream(DEFAULT_RULES_RESOURCE)) {
            if (in == null) {
                return new RuleData();
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                RuleData data = GSON.fromJson(r, RuleData.class);
                return data == null ? new RuleData() : data;
            }
        } catch (IOException | JsonSyntaxException e) {
            TrChatMod.LOGGER.error("Встроенная база правил не читается", e);
            return new RuleData();
        }
    }

    public void saveConfig() {
        try {
            Files.createDirectories(directory);
            Files.writeString(configFile, GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException e) {
            TrChatMod.LOGGER.error("Не удалось сохранить config.json", e);
        }
    }

    private static void normalise(ModConfig c) {
        if (c.chatPatterns == null || c.chatPatterns.isEmpty()) {
            c.chatPatterns = new ModConfig().chatPatterns;
        }
        if (c.ignoredNicks == null) {
            c.ignoredNicks = new java.util.ArrayList<>();
        }
        if (c.highlightMode == null) {
            c.highlightMode = ModConfig.HighlightMode.FULL_LINE;
        }
        if (c.muteCommand == null || c.muteCommand.isBlank()) {
            c.muteCommand = new ModConfig().muteCommand;
        }
        if (c.reasonTemplate == null || c.reasonTemplate.isBlank()) {
            c.reasonTemplate = new ModConfig().reasonTemplate;
        }
        if (c.permanentToken == null || c.permanentToken.isBlank()) {
            c.permanentToken = "perm";
        }
        if (c.maxStoredMessages < 16) {
            c.maxStoredMessages = 16;
        }
    }
}
