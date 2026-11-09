package dev.donut.client.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
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

/** Antialiased SDF badges and an optional animated metal frame; no framebuffer copies. */
final class ProgressBarShader {
    private static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV0", VertexFormatElement.UV0)
            .add("UV1", VertexFormatElement.UV1)
            .add("UV2", VertexFormatElement.UV2)
            .build();
    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
                    .withLocation(id("pipeline/progress_bar"))
                    .withVertexShader(id("core/progress_bar"))
                    .withFragmentShader(id("core/progress_bar"))
                    .withSampler("Sampler0")
                    .withVertexFormat(FORMAT, VertexFormat.Mode.QUADS)
                    .withCull(false)
                    .withDepthWrite(false)
                    .build());

    private ProgressBarShader() { }

    static void initialize() { }

    static void panel(GuiGraphics graphics, float x, float y, float width, float height, Identifier texture) {
        submit(graphics, x, y, width, height, 0xFFFFFFFF, texture, 1);
    }

    static void hexagon(GuiGraphics graphics, float x, float y, float width, float height,
                        int color, Identifier texture, boolean completed) {
        submit(graphics, x, y, width, height, color, texture, completed ? 2 : 0);
    }

    static void counter(GuiGraphics graphics, float x, float y, float width, float height,
                        Identifier texture, boolean shinyOutline) {
        submit(graphics, x, y, width, height, 0xFFFFFFFF, texture, shinyOutline ? 12 : 4);
    }

    static void timer(GuiGraphics graphics, float x, float y, float width, float height, Identifier texture) {
        submit(graphics, x, y, width, height, 0xFFFFFFFF, texture, 16);
    }

    private static void submit(GuiGraphics graphics, float x, float y, float width, float height,
                               int color, Identifier texture, int flags) {
        if (width <= 0 || height <= 0) return;
        Element element = new Element(graphics, x, y, width, height, color, texture, flags);
        if (element.bounds != null && element.bounds.width() > 0 && element.bounds.height() > 0) {
            graphics.guiRenderState.submitGuiElement(element);
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("donut", path);
    }

    private static final class Element implements GuiElementRenderState {
        private final Matrix3x2fc pose;
        private final ScreenRectangle scissor, bounds;
        private final TextureSetup texture;
        private final float x, y, width, height, fringe;
        private final int color, packedWidth, packedHeight, animation, flags;

        private Element(GuiGraphics graphics, float x, float y, float width, float height,
                        int color, Identifier texture, int flags) {
            pose = new Matrix3x2f(graphics.pose());
            scissor = graphics.scissorStack.peek();
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.color = color;
            this.flags = flags;
            double scale = Math.min(Math.hypot(pose.m00(), pose.m01()), Math.hypot(pose.m10(), pose.m11()))
                    * Minecraft.getInstance().getWindow().getGuiScale();
            fringe = (float) (1 / Math.max(.01, scale));
            packedWidth = Math.clamp(Math.round(width * 16), 1, 32767);
            packedHeight = Math.clamp(Math.round(height * 16), 1, 32767);
            // All animation cycles divide sixteen seconds, avoiding a visible wrap.
            animation = (int) (Math.floorMod(System.nanoTime(), 16_000_000_000L) * 1024 / 1_000_000_000L);
            GpuTextureView view = Minecraft.getInstance().getTextureManager().getTexture(texture).getTextureView();
            this.texture = TextureSetup.singleTexture(view,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            int left = (int) Math.floor(x - fringe), top = (int) Math.floor(y - fringe);
            ScreenRectangle box = new ScreenRectangle(left, top,
                    (int) Math.ceil(x + width + fringe) - left,
                    (int) Math.ceil(y + height + fringe) - top).transformMaxBounds(pose);
            bounds = scissor == null ? box : scissor.intersection(box);
        }

        @Override public void buildVertices(VertexConsumer consumer) {
            vertex(consumer, -fringe, -fringe);
            vertex(consumer, -fringe, height + fringe);
            vertex(consumer, width + fringe, height + fringe);
            vertex(consumer, width + fringe, -fringe);
        }

        private void vertex(VertexConsumer consumer, float u, float v) {
            consumer.addVertexWith2DPose(pose, x + u, y + v).setColor(color)
                    .setUv(u, v).setUv1(packedWidth, packedHeight).setUv2(animation, flags);
        }

        @Override public RenderPipeline pipeline() { return PIPELINE; }
        @Override public TextureSetup textureSetup() { return texture; }
        @Override public ScreenRectangle scissorArea() { return scissor; }
        @Override public ScreenRectangle bounds() { return bounds; }
    }
}
