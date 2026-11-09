package dev.donut.client.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

import java.util.OptionalInt;

/**
 * Scene-backed frosted glass, submitted through the normal GUI renderer so scissor,
 * GUI layers, fractional scales, and screen fades work just like regular widgets.
 * A shared two-pass half-resolution blur is captured before GUI composition; no
 * panel ever reads from the framebuffer to which it is being drawn.
 */
public final class GlassPanel {
    // Standard vertex attributes carry per-panel data, allowing GUI batch merging.
    private static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0)
            .add("UV1", VertexFormatElement.UV1)
            .add("UV2", VertexFormatElement.UV2)
            .build();
    private static final RenderPipeline GLASS = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
                    .withLocation(id("pipeline/glass"))
                    .withVertexShader(id("core/glass"))
                    .withFragmentShader(id("core/glass"))
                    .withSampler("Sampler0")
                    .withVertexFormat(FORMAT, VertexFormat.Mode.QUADS)
                    .withCull(false)
                    .withDepthWrite(false)
                    .build());
    private static final RenderPipeline BLUR_HORIZONTAL = blurPipeline("glass_blur_horizontal", true);
    private static final RenderPipeline BLUR_VERTICAL = blurPipeline("glass_blur_vertical", false);

    private static GpuTexture horizontal, blurred;
    private static GpuTextureView horizontalView, blurredView;
    private static boolean requested;

    private GlassPanel() { }

    /** Register shaders before the first resource reload; creates no GPU resources. */
    public static void initialize() { }

    /** Color RGB is the glass tint, and alpha is the caller's animation opacity. */
    public static void draw(GuiGraphics g, float x, float y, float w, float h,
                            float radius, int color, float frost) {
        drawGradient(g, x, y, w, h, radius, color, color, frost);
    }

    public static void drawGradient(GuiGraphics g, float x, float y, float w, float h,
                                    float radius, int topColor, int bottomColor, float frost) {
        if (w <= 0 || h <= 0 || ((topColor | bottomColor) >>> 24) == 0) return;
        Panel state = new Panel(g, x, y, w, h, radius, topColor, bottomColor, frost);
        if (state.bounds == null || state.bounds.width() <= 0 || state.bounds.height() <= 0) return;
        requested = true;
        g.guiRenderState.submitGuiElement(state);
    }

    /** Called by GuiRendererMixin after extraction, before its GUI render passes. */
    public static void prepareScene() {
        if (!requested) {
            // Release the large textures on the first frame after the menu closes.
            releaseTextures();
            return;
        }
        requested = false;
        RenderSystem.assertOnRenderThread();
        GpuTextureView scene = Minecraft.getInstance().getMainRenderTarget().getColorTextureView();
        if (scene == null) return;
        int w = Math.max(1, (scene.getWidth(0) + 1) / 2);
        int h = Math.max(1, (scene.getHeight(0) + 1) / 2);
        if (blurred == null || blurred.getWidth(0) != w || blurred.getHeight(0) != h) {
            releaseTextures();
            int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
            var device = RenderSystem.getDevice();
            horizontal = device.createTexture("Donut glass horizontal", usage, TextureFormat.RGBA8, w, h, 1, 1);
            horizontalView = device.createTextureView(horizontal);
            blurred = device.createTexture("Donut glass scene", usage, TextureFormat.RGBA8, w, h, 1, 1);
            blurredView = device.createTextureView(blurred);
        }
        blur(BLUR_HORIZONTAL, scene, horizontalView);
        blur(BLUR_VERTICAL, horizontalView, blurredView);
    }

    /** Called when the GUI renderer closes, before Minecraft shuts down its GPU. */
    public static void close() {
        requested = false;
        releaseTextures();
    }

    private static void blur(RenderPipeline pipeline, GpuTextureView source, GpuTextureView destination) {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Donut glass frost", destination, OptionalInt.empty())) {
            pass.setPipeline(pipeline);
            pass.bindTexture("Sampler0", source, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(0, 3);
        }
    }

    private static void releaseTextures() {
        if (horizontalView != null) horizontalView.close();
        if (blurredView != null) blurredView.close();
        if (horizontal != null) horizontal.close();
        if (blurred != null) blurred.close();
        horizontalView = blurredView = null;
        horizontal = blurred = null;
    }

    private static RenderPipeline blurPipeline(String name, boolean horizontal) {
        return RenderPipelines.register(RenderPipeline.builder()
                .withLocation(id("pipeline/" + name))
                .withVertexShader("core/screenquad")
                .withFragmentShader(id("core/glass_blur"))
                .withShaderDefine(horizontal ? "HORIZONTAL" : "VERTICAL")
                .withSampler("Sampler0")
                .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .withCull(false)
                .build());
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("donut", path);
    }

    private static final class Panel implements GuiElementRenderState {
        private final Matrix3x2fc pose;
        private final ScreenRectangle scissor, bounds;
        private final float x, y, w, h, fringe;
        private final int topColor, bottomColor, packedWidth, packedHeight, packedRadius, packedFrost;

        private Panel(GuiGraphics g, float x, float y, float w, float h,
                      float radius, int topColor, int bottomColor, float frost) {
            this.pose = new Matrix3x2f(g.pose());
            this.scissor = g.scissorStack.peek();
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.topColor = topColor; this.bottomColor = bottomColor;
            double scale = Math.min(Math.hypot(pose.m00(), pose.m01()), Math.hypot(pose.m10(), pose.m11()))
                    * Minecraft.getInstance().getWindow().getGuiScale();
            this.fringe = (float) (1 / Math.max(.01, scale));
            packedWidth = Math.clamp(Math.round(w * 16), 1, 32767);
            packedHeight = Math.clamp(Math.round(h * 16), 1, 32767);
            packedRadius = Math.clamp(Math.round(Math.clamp(radius, 0, Math.min(w, h) / 2) * 64), 0, 32767);
            packedFrost = Math.round(Math.clamp(frost, 0, 1) * 1024);
            int left = (int) Math.floor(x - fringe), top = (int) Math.floor(y - fringe);
            ScreenRectangle box = new ScreenRectangle(left, top,
                    (int) Math.ceil(x + w + fringe) - left, (int) Math.ceil(y + h + fringe) - top)
                    .transformMaxBounds(pose);
            bounds = scissor == null ? box : scissor.intersection(box);
        }

        @Override public void buildVertices(VertexConsumer consumer) {
            vertex(consumer, -fringe, -fringe);
            vertex(consumer, -fringe, h + fringe);
            vertex(consumer, w + fringe, h + fringe);
            vertex(consumer, w + fringe, -fringe);
        }

        private void vertex(VertexConsumer consumer, float u, float v) {
            consumer.addVertexWith2DPose(pose, x + u, y + v).setColor(v < h / 2 ? topColor : bottomColor)
                    .setUv(u, v).setUv1(packedWidth, packedHeight).setUv2(packedRadius, packedFrost);
        }

        @Override public RenderPipeline pipeline() { return GLASS; }
        @Override public TextureSetup textureSetup() {
            return TextureSetup.singleTexture(blurredView,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        }
        @Override public ScreenRectangle scissorArea() { return scissor; }
        @Override public ScreenRectangle bounds() { return bounds; }
    }
}
