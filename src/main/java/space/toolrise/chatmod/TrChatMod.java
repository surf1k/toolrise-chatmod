package space.toolrise.chatmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.toolrise.chatmod.auto.AutoModerator;
import space.toolrise.chatmod.auto.PendingReview;
import space.toolrise.chatmod.chat.ChatHandler;
import space.toolrise.chatmod.hud.AutoHud;
import space.toolrise.chatmod.input.KeyBindings;
import space.toolrise.chatmod.chat.ChatParser;
import space.toolrise.chatmod.chat.FlaggedMessageStore;
import space.toolrise.chatmod.command.ModCommands;
import space.toolrise.chatmod.config.ConfigManager;
import space.toolrise.chatmod.config.ModConfig;
import space.toolrise.chatmod.evidence.ChatLogBuffer;
import space.toolrise.chatmod.evidence.EvidenceRecorder;
import space.toolrise.chatmod.punish.PunishmentExecutor;
import space.toolrise.chatmod.rules.MessageHistory;
import space.toolrise.chatmod.rules.RuleEngine;

import java.util.List;

/**
 * Клиентский мод модерации чата для ToolRise.
 *
 * <p>Ничего не отправляет на сервер сам по себе: проверка сообщений идёт локально,
 * команда мута уходит только по явному действию модератора.
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
    private AutoModerator autoModerator;
    private ChatLogBuffer chatLog = new ChatLogBuffer(300);
    private EvidenceRecorder evidence;

    public static TrChatMod get() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;

        String report = configManager.load();
        LOGGER.info("Конфигурация загружена: {}", report);

        rebuildRuntime();

        evidence = new EvidenceRecorder(this);
        executor = new PunishmentExecutor(this);
        autoModerator = new AutoModerator(this);
        chatHandler = new ChatHandler(this);
        chatHandler.register();
        ModCommands.register(this);
        KeyBindings.register(this);
        AutoHud.register(this);

        ClientTickEvents.END_CLIENT_TICK.register(client -> autoModerator.tick());

        // История спама/флуда и счётчики автомата относятся к конкретному серверу.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            history.clear();
            store.clear();
            chatLog.clear();
            autoModerator.reset();
        });

        LOGGER.info("Протоколы наказаний: {}", evidence.directory().toAbsolutePath());
        LOGGER.info("{} запущен. Правил: {}, паттернов: {}, автомат: {}",
                MOD_NAME, configManager.engine().ruleCount(), configManager.engine().patternCount(),
                config().autoMode ? "включён" : "выключен");
    }

    /** Показывает очередь на ручную проверку. Вызывается кнопкой и командой. */
    public void showQueue() {
        Minecraft client = Minecraft.getInstance();
        if (client.gui == null) {
            return;
        }
        List<PendingReview> queue = autoModerator.queue();
        if (queue.isEmpty()) {
            client.gui.getChat().addMessage(prefix()
                    .append(Component.literal("очередь пуста")
                            .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY))));
            return;
        }
        client.gui.getChat().addMessage(prefix()
                .append(Component.literal("на ручную проверку: " + queue.size())
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE))));
        for (PendingReview review : queue) {
            client.gui.getChat().addMessage(Component.literal("  " + review.message().nickOrUnknown() + " ")
                    .withStyle(Style.EMPTY.withColor(ChatFormatting.WHITE))
                    .append(Component.literal("— " + review.reason() + " ")
                            .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)))
                    .append(Component.literal("[выдать]")
                            .withStyle(Style.EMPTY
                                    .withColor(ChatFormatting.AQUA)
                                    .withUnderlined(true)
                                    .withClickEvent(new ClickEvent.RunCommand(
                                            "/" + COMMAND + " punish " + review.message().id())))));
        }
    }

    private static MutableComponent prefix() {
        return Component.literal("[Авто] ").withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD));
    }

    /** Перечитывает конфиг и правила с диска. Возвращает отчёт для чата. */
    public String reload() {
        String report = configManager.load();
        rebuildRuntime();
        if (autoModerator != null) {
            autoModerator.recompile();
        }
        return report;
    }

    private void rebuildRuntime() {
        ModConfig config = configManager.config();
        parser = new ChatParser(config.chatPatterns);
        if (store == null) {
            store = new FlaggedMessageStore(config.maxStoredMessages);
        } else {
            store.setCapacity(config.maxStoredMessages);
        }
        chatLog.setCapacity(config.chatLogBufferSize);
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

    public AutoModerator autoModerator() {
        return autoModerator;
    }

    public ChatLogBuffer chatLog() {
        return chatLog;
    }

    public EvidenceRecorder evidence() {
        return evidence;
    }
}
