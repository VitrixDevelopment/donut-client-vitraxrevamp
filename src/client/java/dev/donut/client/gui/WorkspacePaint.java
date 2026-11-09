// SPDX-License-Identifier: GPL-3.0-only
// Native typography bridge for the Spectre visual port. Reference: Spectre by 4ldenz.
// See docs/spectre-visual-port.md and resources/licenses/spectre-GPL-3.0.txt.
package dev.donut.client.gui;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/** Geist typography used by the Spectre-derived module workspace. */
final class WorkspacePaint {
    private static final FontDescription BODY = resource("workspace");
    private static final FontDescription SEMIBOLD = resource("workspace_semibold");
    private static final FontDescription SMALL = resource("workspace_small");
    private static final FontDescription TITLE = resource("workspace_title");
    private static float fontScale = 1;

    private WorkspacePaint() { }

    /** Restore the returned scale in a finally block after rendering the workspace. */
    static float pushScale(float scale) {
        float previous = fontScale;
        fontScale = Float.isFinite(scale) && scale > 0 ? scale : 1;
        return previous;
    }

    static void popScale(float previous) {
        fontScale = previous;
    }

    static void text(GuiGraphics g, Font font, String value, int x, int y, int color) {
        draw(g, font, value, x, y, color, BODY, 16);
    }

    static void strong(GuiGraphics g, Font font, String value, int x, int y, int color) {
        draw(g, font, value, x, y, color, SEMIBOLD, 17);
    }

    static void smallText(GuiGraphics g, Font font, String value, float x, float y, int color) {
        draw(g, font, value, x, y, color, SMALL, 13);
    }

    static void title(GuiGraphics g, Font font, String value, float x, float y, int color) {
        draw(g, font, value, x, y, color, TITLE, 24);
    }

    static int width(Font font, String value) {
        return scaledWidth(font, value, BODY);
    }

    static int strongWidth(Font font, String value) {
        return scaledWidth(font, value, SEMIBOLD);
    }

    static int smallWidth(Font font, String value) {
        return scaledWidth(font, value, SMALL);
    }

    static int titleWidth(Font font, String value) {
        return scaledWidth(font, value, TITLE);
    }

    static void right(GuiGraphics g, Font font, String value, int right, int y, int color) {
        text(g, font, value, right - width(font, value), y, color);
    }

    /** Wrap body text in workspace coordinates and return its consumed line-box height. */
    static int wrapped(GuiGraphics g, Font font, String value, int x, int y, int maxWidth, int color) {
        if (value.isEmpty() || maxWidth <= 0) return 0;
        int unscaledWidth = Math.max(1, (int) Math.floor(maxWidth / fontScale));
        List<FormattedCharSequence> lines = font.split(label(value, BODY), unscaledWidth);
        int faded = UiPaint.withOpacity(color);
        if ((faded >>> 24) >= 4) {
            g.pose().pushMatrix();
            try {
                g.pose().translate(x, y);
                g.pose().scale(fontScale, fontScale);
                g.pose().translate(0, baselineOffset(16));
                for (int i = 0; i < lines.size(); i++) {
                    g.drawString(font, lines.get(i), 0, i * 16, faded, false);
                }
            } finally {
                g.pose().popMatrix();
            }
        }
        return (int) Math.ceil(lines.size() * 16 * fontScale);
    }

    private static void draw(GuiGraphics g, Font font, String value, float x, float y,
                             int color, FontDescription face, int size) {
        int faded = UiPaint.withOpacity(color);
        // Minecraft interprets nearly transparent text as an unspecified opaque color.
        if (value.isEmpty() || (faded >>> 24) < 4) return;
        g.pose().pushMatrix();
        try {
            g.pose().translate(x, y);
            g.pose().scale(fontScale, fontScale);
            g.pose().translate(0, baselineOffset(size));
            g.drawString(font, label(value, face), 0, 0, faded, false);
        } finally {
            g.pose().popMatrix();
        }
    }

    private static int scaledWidth(Font font, String value, FontDescription face) {
        return (int) Math.ceil(font.width(label(value, face)) * fontScale);
    }

    private static float baselineOffset(int size) {
        // Geist hhea ascender is 1005/1000 em. ImGui positions glyphs below that
        // ascender; Minecraft's TTF glyphs instead use a fixed seven-pixel baseline.
        return (float) Math.ceil(size * 1.005) - 7;
    }

    private static Component label(String value, FontDescription face) {
        return Component.literal(value).withStyle(style -> style.withFont(face));
    }

    private static FontDescription resource(String name) {
        return new FontDescription.Resource(Identifier.fromNamespaceAndPath("donut", name));
    }
}
