package dev.donut.client.namehider;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.donut.client.render.GuiBlurRegion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Blurs the complete rendered sidebar without changing any scoreboard data. */
public final class ScoreboardBlur {
    private static final RenderPipeline MARKER = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("donut", "pipeline/scoreboard_boundary")).build());
    private static final GuiBlurRegion region = new GuiBlurRegion("scoreboard");
    private static boolean requested;
    private static int left, top, right, bottom;

    private ScoreboardBlur() { }

    public static RenderPipeline markerPipeline() { return MARKER; }

    public static void beginFrame() { requested = false; region.clear(); }

    public static void beginSidebar() {
        left = top = Integer.MAX_VALUE;
        right = bottom = Integer.MIN_VALUE;
    }

    public static void include(int x1, int y1, int x2, int y2) {
        if (!NameHiderController.blurScoreboard()) return;
        left = Math.min(left, Math.min(x1, x2));
        right = Math.max(right, Math.max(x1, x2));
        top = Math.min(top, Math.min(y1, y2));
        bottom = Math.max(bottom, Math.max(y1, y2));
    }

    public static void mark(GuiGraphics graphics) {
        if (!NameHiderController.blurScoreboard() || right <= left || bottom <= top) return;
        region.setBounds(graphics, left, top, right - left, bottom - top);
        requested = region.isActive();
        if (requested) {
            graphics.nextStratum();
            graphics.fill(MARKER, 0, 0, 1, 1, 0);
            graphics.nextStratum();
        }
    }

    public static void beforeRender() { if (!requested) region.release(); }

    public static void apply() {
        if (!requested) return;
        requested = false;
        var scene = Minecraft.getInstance().getMainRenderTarget().getColorTextureView();
        if (scene != null) region.apply(scene, 1.4f);
    }

    public static void close() { requested = false; region.release(); }
}
