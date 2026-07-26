package space.toolrise.chatmod.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import space.toolrise.chatmod.TrChatMod;
import space.toolrise.chatmod.config.ModConfig;
import space.toolrise.chatmod.rules.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Превращает обычное сообщение чата в красное и кликабельное.
 *
 * <p>Компонент разбирается на плоские куски (текст + стиль), поэтому исходное
 * оформление сервера не мешает: цвет мы задаём сами, а клик и подсказку вешаем
 * на корневой компонент — дочерние наследуют их автоматически.
 */
public final class ChatDecorator {

    public record Part(String text, Style style) {
    }

    private ChatDecorator() {
    }

    public static List<Part> flatten(Component component) {
        List<Part> parts = new ArrayList<>();
        component.visit((FormattedText.StyledContentConsumer<Object>) (style, string) -> {
            if (!string.isEmpty()) {
                parts.add(new Part(string, style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return parts;
    }

    public static String plainText(Component component) {
        StringBuilder sb = new StringBuilder();
        for (Part p : flatten(component)) {
            sb.append(p.text());
        }
        return sb.toString();
    }

    /** Основной вход: пересобирает сообщение с подсветкой, ховером и кликом. */
    public static Component decorate(Component original, FlaggedMessage message, ModConfig config) {
        Style rootStyle = interactiveStyle(message);
        MutableComponent root = Component.empty().withStyle(rootStyle);

        String marker = buildMarker(message, config);
        if (!marker.isEmpty()) {
            root.append(Component.literal(marker)
                    .withStyle(s -> s.withColor(ChatFormatting.RED).withBold(true)));
        }

        List<Part> parts = flatten(original);
        if (config.highlightMode == ModConfig.HighlightMode.FULL_LINE) {
            for (Part p : parts) {
                root.append(Component.literal(p.text())
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            }
            appendIgnoreButton(root, message, config);
            return root;
        }

        // Режим WORDS: красим только сработавшие участки, остальное сохраняем как было.
        List<Violation.Span> spans = shiftSpans(message);
        int cursor = 0;
        for (Part p : parts) {
            int partStart = cursor;
            int partEnd = cursor + p.text().length();
            cursor = partEnd;

            int local = 0;
            while (local < p.text().length()) {
                int absolute = partStart + local;
                Violation.Span hit = spanAt(spans, absolute);
                int sliceEnd;
                if (hit != null) {
                    sliceEnd = Math.min(partEnd, hit.end());
                } else {
                    sliceEnd = partEnd;
                    for (Violation.Span s : spans) {
                        if (s.start() > absolute && s.start() < sliceEnd) {
                            sliceEnd = s.start();
                        }
                    }
                }
                String slice = p.text().substring(local, sliceEnd - partStart);
                if (!slice.isEmpty()) {
                    Style style = hit != null
                            ? p.style().withColor(ChatFormatting.RED).withUnderlined(true)
                            : p.style();
                    root.append(Component.literal(slice).withStyle(style));
                }
                local = sliceEnd - partStart;
            }
        }
        appendIgnoreButton(root, message, config);
        return root;
    }

    /**
     * Кнопка «убрать этого игрока из проверки» прямо в строке чата. Свой
     * ClickEvent на дочернем элементе перекрывает клик всего сообщения.
     */
    private static void appendIgnoreButton(MutableComponent root, FlaggedMessage message, ModConfig config) {
        if (!config.showIgnoreButton || message.nick() == null || message.nick().isBlank()) {
            return;
        }
        root.append(Component.literal(" [игнор]")
                .withStyle(Style.EMPTY
                        .withColor(ChatFormatting.DARK_GRAY)
                        .withUnderlined(false)
                        .withClickEvent(new ClickEvent.RunCommand(
                                "/" + TrChatMod.COMMAND + " ignore add " + message.nick()))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                                "Больше не проверять " + message.nick())))));
    }

    /** Отдельная строка-предупреждение — когда исходное сообщение трогать нельзя. */
    public static Component warningLine(FlaggedMessage message, ModConfig config) {
        Violation primary = message.result().primary();
        String rule = primary == null ? "?" : primary.shortLabel();
        String mute = primary == null ? "?" : primary.mute().display();
        return Component.literal("[!] ")
                .withStyle(s -> s.withColor(ChatFormatting.RED).withBold(true))
                .append(Component.literal(rule + " — " + message.nickOrUnknown() + " — мут " + mute + " ")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.RED).withBold(false)))
                .append(Component.literal("[нажми]")
                        .withStyle(Style.EMPTY.withColor(ChatFormatting.YELLOW).withUnderlined(true)))
                .withStyle(interactiveStyle(message));
    }

    public static Component tooltip(FlaggedMessage message) {
        MutableComponent tip = Component.empty();
        boolean first = true;
        for (Violation v : message.result().violations()) {
            if (!first) {
                line(tip, "");
            }
            first = false;
            line(tip, colored(v.ruleId() + " " + v.ruleName(), ChatFormatting.RED));
            line(tip, colored("мут: ", ChatFormatting.GRAY)
                    .append(colored(v.mute().display(), ChatFormatting.WHITE)));
            if (!v.evidence().isEmpty()) {
                line(tip, colored("сработало: ", ChatFormatting.GRAY)
                        .append(colored(String.join(", ", v.evidence()), ChatFormatting.WHITE)));
            }
        }
        line(tip, "");
        MutableComponent player = colored("игрок: ", ChatFormatting.GRAY)
                .append(colored(message.nickOrUnknown(), ChatFormatting.WHITE));
        if (!message.nickKnown()) {
            player.append(colored(" (ник не подтверждён)", ChatFormatting.DARK_GRAY));
        }
        line(tip, player);
        line(tip, "");
        line(tip, colored("ЛКМ", ChatFormatting.YELLOW).append(colored(" — окно выдачи мута", ChatFormatting.GRAY)));
        line(tip, colored("Ctrl+ЛКМ", ChatFormatting.YELLOW).append(colored(" — выдать сразу", ChatFormatting.GRAY)));
        line(tip, colored("Shift+ЛКМ", ChatFormatting.YELLOW).append(colored(" — вставить ник в чат", ChatFormatting.GRAY)));
        return tip;
    }

    private static MutableComponent colored(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(color));
    }

    private static void line(MutableComponent target, Component content) {
        if (!target.getSiblings().isEmpty() || !target.getString().isEmpty()) {
            target.append(Component.literal("\n"));
        }
        target.append(content);
    }

    private static void line(MutableComponent target, String content) {
        line(target, Component.literal(content));
    }

    private static Style interactiveStyle(FlaggedMessage message) {
        return Style.EMPTY
                .withClickEvent(new ClickEvent.RunCommand("/" + TrChatMod.COMMAND + " punish " + message.id()))
                .withHoverEvent(new HoverEvent.ShowText(tooltip(message)))
                .withInsertion(message.nickOrUnknown());
    }

    private static String buildMarker(FlaggedMessage message, ModConfig config) {
        String prefix = config.markerPrefix == null ? "" : config.markerPrefix;
        if (prefix.isEmpty() || !config.showRuleIdInMarker) {
            return prefix;
        }
        Violation primary = message.result().primary();
        if (primary == null) {
            return prefix;
        }
        int close = prefix.lastIndexOf(']');
        if (close > 0) {
            return prefix.substring(0, close) + " " + primary.ruleId() + prefix.substring(close);
        }
        return prefix + primary.ruleId() + " ";
    }

    /** Переводит участки из координат тела сообщения в координаты всей строки. */
    private static List<Violation.Span> shiftSpans(FlaggedMessage message) {
        List<Violation.Span> out = new ArrayList<>();
        int shift = message.bodyStart();
        int max = message.fullLine().length();
        for (Violation.Span s : message.result().mergedSpans()) {
            int start = Math.min(max, s.start() + shift);
            int end = Math.min(max, s.end() + shift);
            if (end > start) {
                out.add(new Violation.Span(start, end));
            }
        }
        return out;
    }

    private static Violation.Span spanAt(List<Violation.Span> spans, int index) {
        for (Violation.Span s : spans) {
            if (index >= s.start() && index < s.end()) {
                return s;
            }
        }
        return null;
    }
}
