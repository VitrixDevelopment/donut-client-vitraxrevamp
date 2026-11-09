// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre's workspace mascot by 4ldenz for Minecraft's native GUI.
// See docs/spectre-visual-port.md and resources/licenses/spectre-GPL-3.0.txt.
package dev.donut.client.gui;

import net.minecraft.client.gui.GuiGraphics;

/** Spectre's procedural workspace ghost, rendered through Minecraft's native GUI pipeline. */
public final class WorkspaceMascot {
    private static final SpectreMascotRig RIG = new SpectreMascotRig();
    private static final SpectreMascotRig BRAND_RIG = new SpectreMascotRig();

    private WorkspaceMascot() { }

    /** Uses the source's exact 256-unit framing and exact-module animation identity. */
    public static void draw(GuiGraphics g, float x, float y, float size, String moduleId) {
        draw(g, x, y, size, moduleId, false);
    }

    public static void draw(GuiGraphics g, float x, float y, float size,
                            String moduleId, boolean reducedMotion) {
        if (size <= 0) return;
        SpectreMascotCanvas canvas = new SpectreMascotCanvas(g, x, y, size / 256f, 1);
        RIG.draw(canvas, moduleId == null ? "idle" : moduleId,
                System.nanoTime() / 1_000_000L, reducedMotion);
        canvas.submit(g);
    }

    /** Same neutral, phone-holding character and brand framing as WorkspaceMascot.drawBrand. */
    public static void drawBrand(GuiGraphics g, float cx, float cy, float size, float alpha) {
        drawBrand(g, cx, cy, size, alpha, false);
    }

    public static void drawBrand(GuiGraphics g, float cx, float cy, float size, float alpha, boolean reducedMotion) {
        if (size <= 0 || alpha <= 0) return;
        float scale = size / 120f;
        SpectreMascotCanvas canvas = new SpectreMascotCanvas(g,
                cx - 128 * scale, cy - 142 * scale, scale, alpha);
        BRAND_RIG.draw(canvas, "idle", System.nanoTime() / 1_000_000L, reducedMotion);
        canvas.submit(g);
    }
}
