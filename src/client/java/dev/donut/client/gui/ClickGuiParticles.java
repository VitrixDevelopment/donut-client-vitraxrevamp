package dev.donut.client.gui;

import net.minecraft.client.gui.GuiGraphics;

import static dev.donut.client.gui.UiPaint.alpha;
import static dev.donut.client.gui.UiPaint.blend;
import static dev.donut.client.gui.UiPaint.round;

/** A small, fixed field of dust motes outside the click GUI's reading area. */
final class ClickGuiParticles {
    private static final int COUNT = 24;
    private static final double TAU = Math.PI * 2;
    private final float[] along = new float[COUNT];
    private final float[] depth = new float[COUNT];
    private final float[] size = new float[COUNT];
    private final double[] phase = new double[COUNT];
    private final double[] speed = new double[COUNT];
    private double seconds;

    ClickGuiParticles() {
        // Every new screen gets the same quiet, evenly distributed field. No spawning,
        // random-number generation, or particle collections are needed while drawing.
        for (int i = 0; i < COUNT; i++) {
            along[i] = (i / 4 + .5f) / (COUNT / 4f) + (sample(i, 1) - .5f) * .07f;
            depth[i] = .20f + sample(i, 2) * .60f;
            size[i] = .85f + sample(i, 3) * .70f;
            phase[i] = sample(i, 4) * TAU;
            speed[i] = .10 + sample(i, 5) * .10;
        }
    }

    /** Draw in viewport coordinates before the panel, inside its opening/closing fade. */
    void render(GuiGraphics g, int viewportWidth, int viewportHeight,
                float panelX, float panelY, float panelW, float panelH,
                int accent, double frameSeconds) {
        seconds += Math.clamp(frameSeconds, 0, .1);
        if (viewportWidth <= 0 || viewportHeight <= 0 || panelW <= 0 || panelH <= 0) return;
        double entrance = Math.min(1, seconds / .8);
        entrance = entrance * entrance * (3 - 2 * entrance);
        int tint = blend(0xE9E1C5, accent, .22);
        float rightMargin = Math.max(0, viewportWidth - panelX - panelW);
        float bottomMargin = Math.max(0, viewportHeight - panelY - panelH);
        for (int i = 0; i < COUNT; i++) {
            double angle = phase[i] + seconds * speed[i];
            float drift = (float) Math.sin(angle) * 5;
            float lift = (float) Math.cos(angle * .73) * 7;
            float x, y;
            switch (i % 4) {
                case 0 -> { x = panelX * depth[i] + drift; y = panelY + panelH * along[i] + lift; }
                case 1 -> { x = panelX + panelW + rightMargin * depth[i] + drift; y = panelY + panelH * along[i] + lift; }
                case 2 -> { x = panelX + panelW * along[i] + drift; y = panelY * depth[i] + lift; }
                default -> { x = panelX + panelW * along[i] + drift; y = panelY + panelH + bottomMargin * depth[i] + lift; }
            }
            // Resizing to a tight viewport never puts motes over controls or text.
            if (x < 3 || y < 3 || x > viewportWidth - 3 || y > viewportHeight - 3
                    || (x > panelX - 8 && x < panelX + panelW + 8
                    && y > panelY - 8 && y < panelY + panelH + 8)) continue;
            double opacity = entrance * (.09 + .035 * Math.sin(angle * .61));
            float diameter = size[i];
            round(g, x - 2, y - 2, 4, 4, 2, alpha(tint, opacity * .15));
            round(g, x - diameter / 2, y - diameter / 2,
                    diameter, diameter, diameter / 2, alpha(tint, opacity));
        }
    }

    private static float sample(int index, int channel) {
        int bits = (index + 1) * 0x45D9F3B ^ channel * 0x119DE1F3;
        bits = (bits ^ (bits >>> 16)) * 0x45D9F3B;
        bits ^= bits >>> 16;
        return (bits & 0xFFFFFF) / (float) 0x1000000;
    }
}
