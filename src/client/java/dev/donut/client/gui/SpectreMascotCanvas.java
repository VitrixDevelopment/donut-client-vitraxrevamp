// SPDX-License-Identifier: GPL-3.0-only
// Native rendering adapter for Spectre's mascot visuals by 4ldenz.
// See docs/spectre-visual-port.md and resources/licenses/spectre-GPL-3.0.txt.
package dev.donut.client.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

import java.util.Arrays;

/** Adapts the unchanged procedural drawing commands to one ordered native GUI mesh. */
final class SpectreMascotCanvas implements SpectreMascotRig.Canvas, GuiElementRenderState {
    private final Matrix3x2fc pose;
    private final ScreenRectangle scissor;
    private final float x, y, scale, alpha;
    private float[] positions = new float[8192];
    private int[] colors = new int[4096];
    private int size;
    private float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
    private float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
    private ScreenRectangle bounds;

    SpectreMascotCanvas(GuiGraphics g, float x, float y, float scale, float alpha) {
        pose = new Matrix3x2f(g.pose());
        scissor = g.scissorStack.peek();
        this.x = x;
        this.y = y;
        this.scale = scale;
        this.alpha = Math.clamp(alpha, 0, 1);
    }

    @Override
    public void polygon(float[] xy, int argb) {
        int color = color(argb);
        if ((color >>> 24) == 0) return;
        int[] triangles = SpectreMascotMesh.triangles(xy);
        // Like the source adapter, fill complete triangles without internal AA fringes.
        for (int i = 0; i < triangles.length; i += 3) {
            int a = triangles[i] * 2, b = triangles[i + 1] * 2, c = triangles[i + 2] * 2;
            triangle(xy[a], xy[a + 1], xy[b], xy[b + 1], xy[c], xy[c + 1], color);
        }
    }

    @Override
    public void line(float[] xy, int argb, float width) {
        int color = color(argb);
        if ((color >>> 24) == 0 || width <= 0) return;
        // The source uses a 12-segment disc at each point and a thick segment between.
        for (int i = 0; i < xy.length; i += 2) {
            float cx = xy[i], cy = xy[i + 1], r = width / 2;
            for (int j = 0; j < 12; j++) {
                double a = j * Math.PI / 6, b = (j + 1) * Math.PI / 6;
                triangle(cx, cy, cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r,
                        cx + (float) Math.cos(b) * r, cy + (float) Math.sin(b) * r, color);
            }
            if (i + 2 >= xy.length) continue;
            float ex = xy[i + 2], ey = xy[i + 3], dx = ex - cx, dy = ey - cy;
            float length = (float) Math.hypot(dx, dy);
            if (length < .0001f) continue;
            float nx = -dy / length * r, ny = dx / length * r;
            vertex(cx + nx, cy + ny, color);
            vertex(ex + nx, ey + ny, color);
            vertex(ex - nx, ey - ny, color);
            vertex(cx - nx, cy - ny, color);
        }
    }

    private int color(int argb) {
        return UiPaint.withOpacity((Math.round((argb >>> 24) * alpha) << 24) | (argb & 0xFFFFFF));
    }

    private void triangle(float ax, float ay, float bx, float by, float cx, float cy, int color) {
        // ImGui accepts either contour winding. Minecraft's GUI pipeline culls
        // the opposite face, so normalize every source triangle to the same
        // winding used by SmoothGeometry (including mirrored eyes and props).
        if ((bx - ax) * (cy - ay) - (by - ay) * (cx - ax) > 0) {
            float swapX = bx, swapY = by;
            bx = cx; by = cy;
            cx = swapX; cy = swapY;
        }
        vertex(ax, ay, color);
        vertex(bx, by, color);
        vertex(cx, cy, color);
        vertex(cx, cy, color);
    }

    private void vertex(float px, float py, int color) {
        if (size == colors.length) {
            colors = Arrays.copyOf(colors, colors.length * 2);
            positions = Arrays.copyOf(positions, positions.length * 2);
        }
        px = x + px * scale;
        py = y + py * scale;
        positions[size * 2] = px;
        positions[size * 2 + 1] = py;
        colors[size++] = color;
        minX = Math.min(minX, px); minY = Math.min(minY, py);
        maxX = Math.max(maxX, px); maxY = Math.max(maxY, py);
    }

    void submit(GuiGraphics g) {
        if (size == 0) return;
        int bx = (int) Math.floor(minX), by = (int) Math.floor(minY);
        bounds = new ScreenRectangle(bx, by, (int) Math.ceil(maxX) - bx,
                (int) Math.ceil(maxY) - by).transformMaxBounds(pose);
        if (scissor != null) bounds = scissor.intersection(bounds);
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            g.guiRenderState.submitGuiElement(this);
        }
    }

    @Override
    public void buildVertices(VertexConsumer consumer) {
        for (int i = 0; i < size; i++) {
            consumer.addVertexWith2DPose(pose, positions[i * 2], positions[i * 2 + 1]).setColor(colors[i]);
        }
    }

    @Override public RenderPipeline pipeline() { return RenderPipelines.GUI; }
    @Override public TextureSetup textureSetup() { return TextureSetup.noTexture(); }
    @Override public ScreenRectangle scissorArea() { return scissor; }
    @Override public ScreenRectangle bounds() { return bounds; }
}
