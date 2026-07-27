package space.toolrise.chatmod.evidence;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import space.toolrise.chatmod.TrChatMod;
import space.toolrise.chatmod.chat.FlaggedMessage;
import space.toolrise.chatmod.config.ModConfig;
import space.toolrise.chatmod.rules.Violation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Протокол наказания: подтверждение, что мут выдан по делу.
 *
 * <p>Пишется в {@code .minecraft/trmod/ГГГГ-ММ-ДД/}. Основное доказательство —
 * текстовый файл: сообщение-нарушение, все реплики игрока за окно (для флуда там
 * видны все повторы) и кусок чата вокруг события. Скриншот прикладывается
 * дополнительно и не всегда полезен: чат в игре гаснет через несколько секунд,
 * а автомат выдаёт мут с задержкой — к этому моменту нужной строки на экране
 * уже может не быть. У текстового протокола такой проблемы нет.
 *
 * <p>Здесь же ведётся {@code punishments.csv} — по нему считают норму.
 */
public final class EvidenceRecorder implements Evidence {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("HH-mm-ss");
    private static final DateTimeFormatter LINE_STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter CSV_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String CSV_HEADER =
            "время;источник;модератор;нарушитель;правило;срок;команда;сообщение";

    private final TrChatMod mod;
    private final ChatLogBuffer chatLog;

    public EvidenceRecorder(TrChatMod mod) {
        this.mod = mod;
        this.chatLog = new ChatLogBuffer(mod.config().chatLogBufferSize);
    }

    /** В буфер кладём вообще всё: протоколу нужен контекст, а не только нарушения. */
    @Override
    public void observe(long time, String line, String nick) {
        chatLog.add(time, line, nick);
    }

    @Override
    public void clear() {
        chatLog.clear();
    }

    /** Корень протоколов: {@code .minecraft/trmod}. */
    @Override
    public Path directory() {
        ModConfig config = mod.config();
        String name = config.evidenceDir == null || config.evidenceDir.isBlank()
                ? "trmod" : config.evidenceDir.strip();
        return Minecraft.getInstance().gameDirectory.toPath().resolve(name);
    }

    /**
     * Сохраняет протокол по выданному наказанию и строку в журнал.
     *
     * @return путь к файлу протокола или {@code null}
     */
    @Override
    public Path record(FlaggedMessage message, String nick, String time,
                       String reason, String ruleInfo, boolean auto, String command) {
        ModConfig config = mod.config();
        if (config.logPunishments) {
            log(auto ? "авто" : "вручную", nick, ruleInfo, time, command,
                    message == null ? "" : message.body());
        }
        if (!config.saveEvidence) {
            return null;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            Path dir = directory().resolve(text(DAY, now));
            Files.createDirectories(dir);

            String base = text(FILE_STAMP, now) + "_" + safe(nick) + "_" + safe(ruleInfo);
            Path file = dir.resolve(base + ".txt");
            Files.writeString(file, build(message, nick, time, reason, ruleInfo, auto, command, now),
                    StandardCharsets.UTF_8);

            if (config.saveScreenshot) {
                screenshot(dir.resolve(base + ".png"), config.screenshotDelayMillis);
            }
            return file;
        } catch (Exception e) {
            TrChatMod.LOGGER.error("Не удалось сохранить протокол наказания", e);
            return null;
        }
    }

    private String build(FlaggedMessage message, String nick, String time, String reason,
                         String ruleInfo, boolean auto, String command, LocalDateTime now) {
        ModConfig config = mod.config();
        StringBuilder sb = new StringBuilder();
        String moderator = moderator();

        sb.append("ПРОТОКОЛ НАКАЗАНИЯ\n");
        sb.append("=".repeat(60)).append('\n');
        sb.append("время:      ").append(now).append('\n');
        sb.append("модератор:  ").append(moderator).append('\n');
        sb.append("нарушитель: ").append(nick).append('\n');
        sb.append("правило:    ").append(ruleInfo).append('\n');
        sb.append("срок:       ").append(time).append('\n');
        sb.append("причина:    ").append(reason).append('\n');
        sb.append("источник:   ").append(auto ? "автомат" : "модератор вручную").append('\n');
        sb.append("команда:    /").append(command).append('\n');

        if (message != null) {
            sb.append('\n').append("СООБЩЕНИЕ-НАРУШЕНИЕ\n");
            sb.append("-".repeat(60)).append('\n');
            if (message.prefix() != null && !message.prefix().isBlank()) {
                sb.append("ранг/канал: ").append(message.prefix().strip()).append('\n');
            }
            sb.append(message.fullLine()).append('\n');

            sb.append('\n').append("ЧТО СРАБОТАЛО\n");
            sb.append("-".repeat(60)).append('\n');
            for (Violation v : message.result().violations()) {
                sb.append(v.ruleId()).append(' ').append(v.ruleName())
                        .append(" — мут ").append(v.mute().display()).append('\n');
                if (!v.evidence().isEmpty()) {
                    sb.append("    ").append(String.join(", ", v.evidence())).append('\n');
                }
            }
        }

        // Для флуда и спама важно показать все повторы, а не одну строку.
        int window = Math.max(60, config.evidenceWindowSeconds);
        List<ChatLogBuffer.Line> own = chatLog.byPlayer(nick, window, System.currentTimeMillis());
        if (!own.isEmpty()) {
            sb.append('\n').append("ВСЕ РЕПЛИКИ ИГРОКА ЗА ПОСЛЕДНИЕ ").append(window)
                    .append(" СЕК (").append(own.size()).append(")\n");

            // Окно показа шире окна флуда. Без пометки протокол читается как
            // «повторы за две минуты», то есть как доказательство не того,
            // за что выдан мут. Помечаем ровно то, что посчитал детектор.
            boolean floodCase = message != null && hasFloodEvidence(message);
            int floodWindow = mod.engine().data().settings.spamWindowSeconds;
            int floodNeeded = mod.engine().data().settings.spamRepeatCount;
            long at = System.currentTimeMillis();
            if (floodCase) {
                int inWindow = ChatLogBuffer.countInWindow(own, at, floodWindow);
                sb.append("Флуд считается по окну ").append(floodWindow)
                        .append(" сек, порог ").append(floodNeeded)
                        .append(". Знаком * помечено попавшее в окно: ")
                        .append(inWindow).append(" из ").append(own.size()).append('\n');
            }
            sb.append("-".repeat(60)).append('\n');
            for (ChatLogBuffer.Line l : own) {
                boolean counted = floodCase && ChatLogBuffer.inWindow(l.time(), at, floodWindow);
                sb.append(counted ? "* " : "  ")
                        .append('[').append(stamp(l.time())).append("] ")
                        .append(l.line()).append('\n');
            }
        }

        List<ChatLogBuffer.Line> context = chatLog.tail(config.evidenceContextLines);
        if (!context.isEmpty()) {
            sb.append('\n').append("ЧАТ ВОКРУГ СОБЫТИЯ (").append(context.size()).append(" строк)\n");
            sb.append("-".repeat(60)).append('\n');
            for (ChatLogBuffer.Line l : context) {
                boolean mine = l.nick() != null && nick != null && l.nick().equalsIgnoreCase(nick);
                sb.append(mine ? ">> " : "   ")
                        .append('[').append(stamp(l.time())).append("] ")
                        .append(l.line()).append('\n');
            }
        }
        return sb.toString();
    }

    /** Строка в {@code config/trchatmod/punishments.csv} — по нему считают норму. */
    private void log(String source, String nick, String ruleInfo, String time, String command, String body) {
        Path file = mod.configManager().directory().resolve("punishments.csv");
        String row = String.join(";",
                text(CSV_STAMP, LocalDateTime.now()),
                csv(source),
                csv(moderator()),
                csv(nick),
                csv(ruleInfo),
                csv(time),
                csv("/" + command),
                csv(body));
        try {
            Files.createDirectories(file.getParent());
            if (Files.notExists(file)) {
                Files.writeString(file, CSV_HEADER + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            }
            Files.writeString(file, row + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            TrChatMod.LOGGER.error("Не удалось записать журнал наказаний", e);
        }
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(';', ',').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String moderator() {
        return Minecraft.getInstance().player == null
                ? "?" : Minecraft.getInstance().player.getGameProfile().name();
    }

    /** Сработал ли детектор повторов — по нему решаем, показывать ли окно флуда. */
    private static boolean hasFloodEvidence(FlaggedMessage message) {
        for (Violation v : message.result().violations()) {
            for (String e : v.evidence()) {
                if (e.contains("одно и то же")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Кадр берётся не сразу: команда мута уходит в том же тике, в котором мы сюда
     * попали, и на снимке без паузы чат ещё не успевает отрисовать ни само событие,
     * ни строку подтверждения — получается кадр «ни о чём». Пауза в секунду это чинит.
     */
    private static void screenshot(Path target, int delayMillis) {
        if (delayMillis <= 0) {
            capture(target);
            return;
        }
        CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS)
                .execute(() -> capture(target));
    }

    private static void capture(Path target) {
        Minecraft client = Minecraft.getInstance();
        // Захват кадра возможен только на рендер-потоке.
        client.execute(() -> {
            try {
                Screenshot.takeScreenshot(client.getMainRenderTarget(), (NativeImage image) -> {
                    try (image) {
                        image.writeToFile(target);
                    } catch (IOException e) {
                        TrChatMod.LOGGER.warn("Скриншот не сохранён: {}", e.toString());
                    }
                });
            } catch (Exception e) {
                TrChatMod.LOGGER.warn("Скриншот не сделан: {}", e.toString());
            }
        });
    }

    private static String stamp(long millis) {
        return text(LINE_STAMP, LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()));
    }

    private static String text(DateTimeFormatter formatter, TemporalAccessor moment) {
        StringBuilder sb = new StringBuilder();
        formatter.formatTo(moment, sb);
        return sb.toString();
    }

    /** Приводит кусок имени файла к безопасному виду. */
    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String s = value.strip().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}._-]+", "-");
        return s.length() > 40 ? s.substring(0, 40) : s;
    }
}
