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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Locale;

/**
 * Протокол наказания: подтверждение, что мут выдан по делу.
 *
 * <p>Пишется в {@code .minecraft/trmod/ГГГГ-ММ-ДД/}. Основное доказательство —
 * текстовый файл: сообщение-нарушение, все реплики игрока за окно (для флуда там
 * видны все повторы) и кусок чата вокруг события. Скриншот прикладывается
 * дополнительно и не всегда полезен: чат в игре гаснет через несколько секунд,
 * а автомат выдаёт мут с задержкой — к этому моменту нужной строки на экране
 * уже может не быть. У текстового протокола такой проблемы нет.
 */
public final class EvidenceRecorder {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("HH-mm-ss");
    private static final DateTimeFormatter LINE_STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TrChatMod mod;

    public EvidenceRecorder(TrChatMod mod) {
        this.mod = mod;
    }

    /** Корень протоколов: {@code .minecraft/trmod}. */
    public Path directory() {
        ModConfig config = mod.config();
        String name = config.evidenceDir == null || config.evidenceDir.isBlank()
                ? "trmod" : config.evidenceDir.strip();
        return Minecraft.getInstance().gameDirectory.toPath().resolve(name);
    }

    /**
     * Сохраняет протокол по выданному наказанию.
     *
     * @return путь к файлу протокола или {@code null}
     */
    public Path record(FlaggedMessage message, String nick, String time,
                       String reason, String ruleInfo, boolean auto, String command) {
        ModConfig config = mod.config();
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
                screenshot(dir.resolve(base + ".png"));
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
        String moderator = Minecraft.getInstance().player == null
                ? "?" : Minecraft.getInstance().player.getGameProfile().name();

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
        List<ChatLogBuffer.Line> own = mod.chatLog().byPlayer(nick, window, System.currentTimeMillis());
        if (!own.isEmpty()) {
            sb.append('\n').append("ВСЕ РЕПЛИКИ ИГРОКА ЗА ПОСЛЕДНИЕ ").append(window)
                    .append(" СЕК (").append(own.size()).append(")\n");

            // Окно показа шире окна флуда. Без пометки протокол читается как
            // «четыре повтора за две минуты», то есть как доказательство не того,
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

        List<ChatLogBuffer.Line> context = mod.chatLog().tail(config.evidenceContextLines);
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

    private static void screenshot(Path target) {
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
