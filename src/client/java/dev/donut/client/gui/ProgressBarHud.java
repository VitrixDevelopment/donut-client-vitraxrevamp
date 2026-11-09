package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ProgressBarSettings;
import dev.donut.client.progress.ProgressTimers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

/** Three compact, independently shaded milestones for the streaming overlay. */
public final class ProgressBarHud {
    public static final int WIDTH = 216;
    public static final int HEX_HEIGHT = 80;
    public static final int HEIGHT = 132;
    public static final int TIMER_X = 14;
    public static final int TIMER_Y = 78;
    public static final int TIMER_WIDTH = 56;
    public static final int TIMER_HEIGHT = 18;
    public static final int TIMER_SPACING = 66;
    public static final int COUNTER_X = 15;
    public static final int COUNTER_Y = 102;
    public static final int COUNTER_WIDTH = 186;
    public static final int COUNTER_HEIGHT = 24;
    private static final int MARGIN = 4;
    private static final Identifier[] ICONS = {
            icon("grass_block"), icon("netherrack"), icon("end_portal_frame")
    };
    private static final int[] COLORS = {0xFF9AE6B2, 0xFFFF987F, 0xFFC3ADFF};

    private ProgressBarHud() { }

    /** Register the pipeline before Minecraft's first shader resource reload. */
    public static void initialize() {
        ProgressBarShader.initialize();
    }

    public static void render(GuiGraphics graphics, ClientConfig config, int completedStages) {
        Bounds bounds = layout(graphics.guiWidth(), graphics.guiHeight(), config);
        completedStages = Math.clamp(completedStages, 0, 3);
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(bounds.x(), bounds.y());
            graphics.pose().scale(bounds.scale(), bounds.scale());
            if (config.progressBar.shinyOutline) {
                ProgressBarShader.panel(graphics, 0, 0, WIDTH, HEIGHT, ICONS[0]);
            }
            for (int i = 0; i < ICONS.length; i++) {
                ProgressBarShader.hexagon(graphics, 8 + i * 66, 0, 68, HEX_HEIGHT,
                        COLORS[i], ICONS[i], i < completedStages);
            }
            renderTimers(graphics, config);
            renderCounter(graphics, config);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private static void renderTimers(GuiGraphics graphics, ClientConfig config) {
        Font font = Minecraft.getInstance().font;
        float textScale = .80f;
        for (int i = 0; i < ICONS.length; i++) {
            float x = TIMER_X + i * TIMER_SPACING;
            ProgressBarShader.timer(graphics, x, TIMER_Y, TIMER_WIDTH, TIMER_HEIGHT, ICONS[0]);
            String time = ProgressTimers.formattedTime(config, i);
            int color = ProgressTimers.isExpired(config, i) ? 0xFFFF9A9F
                    : ProgressTimers.isRunning(config, i) ? 0xFFEDF2FC : 0xFFABB5C6;
            float textX = x + (TIMER_WIDTH - UiPaint.width(font, time) * textScale) / 2f;
            hudText(graphics, font, time, textX, TIMER_Y + TIMER_HEIGHT / 2f,
                    textScale, false, color);
        }
    }

    private static void renderCounter(GuiGraphics graphics, ClientConfig config) {
        ProgressBarShader.counter(graphics, COUNTER_X, COUNTER_Y, COUNTER_WIDTH, COUNTER_HEIGHT,
                ICONS[0], config.progressBar.shinyOutline);
        Font font = Minecraft.getInstance().font;
        String label = "Bases Found:";
        String value = Integer.toString(Math.clamp(config.progressBar.basesFound,
                0, ProgressBarSettings.MAX_BASES_FOUND));
        float labelScale = .85f, valueScale = 1.05f, gap = 8;
        int labelWidth = font.width(UiPaint.label(label).copy().withStyle(style -> style.withBold(true)));
        int valueWidth = font.width(UiPaint.label(value).copy().withStyle(style -> style.withBold(true)));
        float width = labelWidth * labelScale + gap + valueWidth * valueScale;
        float x = COUNTER_X + (COUNTER_WIDTH - width) / 2f;
        float centerY = COUNTER_Y + COUNTER_HEIGHT / 2f;
        hudText(graphics, font, label, x, centerY, labelScale, true, 0xFFF5F7FF);
        x += labelWidth * labelScale + gap;
        hudText(graphics, font, value, x, centerY, valueScale, true, 0xFFFF5555);
    }

    private static void hudText(GuiGraphics graphics, Font font, String text, float x, float centerY,
                                float scale, boolean bold, int color) {
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(x, centerY - font.lineHeight * scale / 2f);
            graphics.pose().scale(scale, scale);
            if (bold) UiPaint.strong(graphics, font, text, 0, 0, color);
            else UiPaint.text(graphics, font, text, 0, 0, color);
        } finally {
            graphics.pose().popMatrix();
        }
    }

    public static Bounds layout(int guiWidth, int guiHeight, ClientConfig config) {
        float marginX = Math.min(MARGIN, Math.max(0, guiWidth) / 2f);
        float marginY = Math.min(MARGIN, Math.max(0, guiHeight) / 2f);
        float availableWidth = Math.max(0, guiWidth - marginX * 2);
        float availableHeight = Math.max(0, guiHeight - marginY * 2);
        double requestedScale = Double.isFinite(config.progressBar.scale)
                ? Math.clamp(config.progressBar.scale, ProgressBarSettings.MIN_SCALE, ProgressBarSettings.MAX_SCALE) : 1;
        float scale = (float) Math.max(0, Math.min(requestedScale,
                Math.min(availableWidth / WIDTH, availableHeight / HEIGHT)));
        float width = WIDTH * scale, height = HEIGHT * scale;
        double nx = normalized(config.progressBar.x, .5);
        double ny = normalized(config.progressBar.y, .12);
        return new Bounds(marginX + (float) (Math.max(0, availableWidth - width) * nx),
                marginY + (float) (Math.max(0, availableHeight - height) * ny), width, height, scale);
    }

    /** Store a dragged top-left corner using exactly the same travel as layout(). */
    public static void moveTo(ClientConfig config, int guiWidth, int guiHeight, double x, double y) {
        Bounds bounds = layout(guiWidth, guiHeight, config);
        float marginX = Math.min(MARGIN, Math.max(0, guiWidth) / 2f);
        float marginY = Math.min(MARGIN, Math.max(0, guiHeight) / 2f);
        double travelX = Math.max(0, guiWidth - marginX * 2 - bounds.width());
        double travelY = Math.max(0, guiHeight - marginY * 2 - bounds.height());
        config.progressBar.x = travelX > 0 ? normalized((x - marginX) / travelX, .5) : .5;
        config.progressBar.y = travelY > 0 ? normalized((y - marginY) / travelY, .12) : .12;
    }

    private static double normalized(double value, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, 0, 1) : fallback;
    }

    private static Identifier icon(String name) {
        return Identifier.fromNamespaceAndPath("donut", "textures/gui/progress/" + name + ".png");
    }

    public record Bounds(float x, float y, float width, float height, float scale) {
        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height;
        }
    }
}
