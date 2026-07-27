package space.toolrise.chatmod.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import space.toolrise.chatmod.TrChatMod;
import space.toolrise.chatmod.Variant;
import space.toolrise.chatmod.chat.FlaggedMessage;
import space.toolrise.chatmod.punish.PunishmentExecutor;
import space.toolrise.chatmod.rules.Violation;

import java.util.ArrayList;
import java.util.List;

/**
 * Окно выдачи мута: показывает сообщение, найденные правила и позволяет
 * поправить ник, срок и причину перед отправкой команды.
 */
public final class PunishScreen extends Screen {

    private static final int FIELD_WIDTH = 220;
    private static final int ROW_HEIGHT = 22;

    private final Screen parent;
    private final FlaggedMessage message;
    private final List<Violation> violations;

    private Violation selected;
    private EditBox nickBox;
    private EditBox timeBox;
    private EditBox reasonBox;
    private final List<Button> ruleButtons = new ArrayList<>();
    private List<FormattedCharSequence> messageLines = List.of();

    public PunishScreen(Screen parent, FlaggedMessage message) {
        super(Component.literal("Выдача мута"));
        this.parent = parent;
        this.message = message;
        this.violations = message.result().violations();
        this.selected = message.result().primary();
    }

    @Override
    protected void init() {
        ruleButtons.clear();
        int cx = this.width / 2;
        int left = cx - FIELD_WIDTH / 2;
        int y = 56;

        messageLines = this.font.split(
                Component.literal(message.body()).withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)),
                FIELD_WIDTH);
        y += Math.min(messageLines.size(), 4) * 10 + 10;

        for (Violation violation : violations) {
            Button button = Button.builder(ruleLabel(violation), b -> select(violation))
                    .bounds(left, y, FIELD_WIDTH, 20)
                    .build();
            ruleButtons.add(button);
            addRenderableWidget(button);
            y += ROW_HEIGHT;
        }
        y += 6;

        nickBox = new EditBox(this.font, left, y, FIELD_WIDTH, 20, Component.literal("Ник"));
        nickBox.setMaxLength(32);
        nickBox.setValue(message.nick() == null ? "" : message.nick());
        nickBox.setHint(Component.literal("ник нарушителя"));
        addRenderableWidget(nickBox);
        y += ROW_HEIGHT + 8;

        timeBox = new EditBox(this.font, left, y, 70, 20, Component.literal("Срок"));
        timeBox.setMaxLength(16);
        addRenderableWidget(timeBox);

        reasonBox = new EditBox(this.font, left + 78, y, FIELD_WIDTH - 78, 20, Component.literal("Причина"));
        reasonBox.setMaxLength(96);
        addRenderableWidget(reasonBox);
        y += ROW_HEIGHT + 12;

        addRenderableWidget(Button.builder(
                        Component.literal("Выдать мут").withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN)),
                        b -> confirm())
                .bounds(left, y, FIELD_WIDTH / 2 - 3, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Отмена"), b -> onClose())
                .bounds(left + FIELD_WIDTH / 2 + 3, y, FIELD_WIDTH / 2 - 3, 20)
                .build());
        y += ROW_HEIGHT;

        // Игнор-лист есть только в полной версии.
        if (Variant.FULL) {
            addRenderableWidget(Button.builder(
                            Component.literal("Не проверять этого игрока")
                                    .withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)),
                            b -> ignorePlayer())
                    .bounds(left, y, FIELD_WIDTH, 20)
                    .build());
        }

        applySelection();
        setInitialFocus(nickBox);
    }

    private void select(Violation violation) {
        this.selected = violation;
        applySelection();
    }

    private void applySelection() {
        if (selected == null) {
            return;
        }
        TrChatMod mod = TrChatMod.get();
        var capped = space.toolrise.chatmod.chat.RankPolicy.cap(
                selected.mute(), message.prefix(), mod.config());
        timeBox.setValue(PunishmentExecutor.timeToken(capped, mod.config()));
        reasonBox.setValue(mod.config().buildReason(selected.ruleId(), selected.ruleName(), ""));
        for (int i = 0; i < ruleButtons.size(); i++) {
            Violation v = violations.get(i);
            ruleButtons.get(i).setMessage(ruleLabel(v));
            ruleButtons.get(i).active = v != selected;
        }
    }

    private Component ruleLabel(Violation violation) {
        boolean active = violation == selected;
        ChatFormatting color = active ? ChatFormatting.YELLOW : ChatFormatting.WHITE;
        String prefix = active ? "> " : "  ";
        return Component.literal(prefix + violation.ruleId() + " " + violation.ruleName()
                        + " — " + violation.mute().display())
                .withStyle(Style.EMPTY.withColor(color));
    }

    private void confirm() {
        TrChatMod mod = TrChatMod.get();
        String nick = nickBox.getValue().trim();
        String time = timeBox.getValue().trim();
        String reason = reasonBox.getValue().trim();
        String ruleInfo = selected == null ? "вручную" : selected.shortLabel();
        mod.executor().mute(message, nick, time, reason, ruleInfo);
        onClose();
    }

    private void ignorePlayer() {
        String nick = nickBox.getValue().trim();
        if (nick.isEmpty()) {
            return;
        }
        TrChatMod mod = TrChatMod.get();
        mod.config().ignoredNicks.add(nick);
        mod.configManager().saveConfig();
        onClose();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta) {
        super.render(guiGraphics, mouseX, mouseY, delta);
        int cx = this.width / 2;
        int left = cx - FIELD_WIDTH / 2;

        guiGraphics.drawCenteredString(this.font, this.title, cx, 18, 0xFFFF5555);

        String who = "Игрок: " + message.nickOrUnknown()
                + (message.nickKnown() ? "" : " (не в списке онлайн)");
        guiGraphics.drawString(this.font, Component.literal(who), left, 34, 0xFFFFFFFF);

        int y = 48;
        for (int i = 0; i < messageLines.size() && i < 4; i++) {
            guiGraphics.drawString(this.font, messageLines.get(i), left, y, 0xFFAAAAAA);
            y += 10;
        }

        if (nickBox != null) {
            guiGraphics.drawString(this.font, Component.literal("Ник"),
                    left, nickBox.getY() - 10, 0xFFAAAAAA);
        }
        if (timeBox != null) {
            guiGraphics.drawString(this.font, Component.literal("Срок"),
                    left, timeBox.getY() - 10, 0xFFAAAAAA);
            guiGraphics.drawString(this.font, Component.literal("Причина"),
                    left + 78, timeBox.getY() - 10, 0xFFAAAAAA);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
