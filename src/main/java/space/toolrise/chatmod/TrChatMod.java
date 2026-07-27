package space.toolrise.chatmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.toolrise.chatmod.auto.AutoModeration;
import space.toolrise.chatmod.chat.ChatHandler;
import space.toolrise.chatmod.chat.ChatParser;
import space.toolrise.chatmod.chat.FlaggedMessageStore;
import space.toolrise.chatmod.command.ModCommands;
import space.toolrise.chatmod.config.ConfigManager;
import space.toolrise.chatmod.config.ModConfig;
import space.toolrise.chatmod.evidence.Evidence;
import space.toolrise.chatmod.punish.PunishmentExecutor;
import space.toolrise.chatmod.rules.MessageHistory;
import space.toolrise.chatmod.rules.RuleEngine;

/**
 * Клиентский мод модерации чата для ToolRise.
 *
 * <p>Ничего не отправляет на сервер сам по себе: проверка сообщений идёт локально,
 * команда мута уходит только по явному действию модератора — а в полной версии
 * ещё и по решению автомата, который включает сам модератор.
 *
 * <p>Чем полная версия отличается от лёгкой, целиком описано в {@code Variant}:
 * он один разный в {@code src/full} и {@code src/lite}.
 */
public final class TrChatMod implements ClientModInitializer {

    public static final String MOD_ID = "trchatmod";
    public static final String MOD_NAME = "ToolRise ChatMod";
    public static final String COMMAND = "trchatmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private static TrChatMod instance;

    private final ConfigManager configManager = new ConfigManager();
    private final MessageHistory history = new MessageHistory();
    private FlaggedMessageStore store = new FlaggedMessageStore(200);
    private ChatParser parser = new ChatParser(new ModConfig().chatPatterns);
    private PunishmentExecutor executor;
    private ChatHandler chatHandler;
    private AutoModeration auto;
    private Evidence evidence;

    public static TrChatMod get() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;

        String report = configManager.load();
        LOGGER.info("Конфигурация загружена: {}", report);

        ModConfig config = configManager.config();
        parser = new ChatParser(config.chatPatterns);
        store.setCapacity(config.maxStoredMessages);

        evidence = Variant.evidence(this);
        executor = new PunishmentExecutor(this);
        auto = Variant.auto(this);
        chatHandler = new ChatHandler(this);
        chatHandler.register();
        ModCommands.register(this);
        Variant.registerExtras(this);

        if (auto != null) {
            ClientTickEvents.END_CLIENT_TICK.register(client -> auto.tick());
        }

        // Один раз при заходе показываем, что реально включено. Без этого легко
        // обновить мод и не заметить, что правила читаются из старого конфига.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> showStatus());

        // История спама, помеченные сообщения и счётчики автомата относятся
        // к конкретному серверу.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            history.clear();
            store.clear();
            if (evidence != null) {
                evidence.clear();
            }
            if (auto != null) {
                auto.reset();
            }
        });

        LOGGER.info("Настройки: {}", configManager.directory().toAbsolutePath());
        LOGGER.info("{} ({}) запущен. Правил: {}, паттернов: {}",
                MOD_NAME, Variant.NAME,
                configManager.engine().ruleCount(), configManager.engine().patternCount());
    }

    /** Короткая строка «что сейчас включено» — печатается при заходе на сервер. */
    public void showStatus() {
        Minecraft client = Minecraft.getInstance();
        if (client.gui == null) {
            return;
        }
        var settings = configManager.engine().data().settings;
        int matThreshold = configManager.ruleData().rules.stream()
                .filter(r -> "2.9".equals(r.id))
                .mapToInt(r -> r.minMatches)
                .findFirst().orElse(1);

        client.gui.getChat().addMessage(prefix()
                .append(Component.literal("правила v" + configManager.ruleData().schema
                                + " · флуд " + settings.spamRepeatCount + "/" + settings.spamWindowSeconds + "с"
                                + " · мат от " + matThreshold
                                + Variant.statusSuffix(this))
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))));
    }

    public static MutableComponent prefix() {
        return Component.literal("[ChatMod] ").withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD));
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public ModConfig config() {
        return configManager.config();
    }

    public RuleEngine engine() {
        return configManager.engine();
    }

    public MessageHistory history() {
        return history;
    }

    public FlaggedMessageStore store() {
        return store;
    }

    public ChatParser parser() {
        return parser;
    }

    public PunishmentExecutor executor() {
        return executor;
    }

    /** Автомат или {@code null} — в лёгкой версии его нет. */
    public AutoModeration auto() {
        return auto;
    }

    /** Протоколы наказаний или {@code null} — в лёгкой версии их нет. */
    public Evidence evidence() {
        return evidence;
    }
}
