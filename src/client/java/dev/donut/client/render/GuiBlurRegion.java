package dev.donut.client.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;


/** A cached, bounded native HUD blur, shared by hotbar and sidebar privacy. */
public final class GuiBlurRegion {
    private static final RenderPipeline HORIZONTAL = pipeline("hotbar_horizontal", "HORIZONTAL", false);
    private static final RenderPipeline VERTICAL = pipeline("hotbar_vertical", "VERTICAL", false);
    private static final RenderPipeline EXTRA_HORIZONTAL = pipeline("hotbar_horizontal_extra", "HORIZONTAL", false, true);
    private static final RenderPipeline EXTRA_VERTICAL = pipeline("hotbar_vertical_extra", "VERTICAL", false, true);
    private static final RenderPipeline COMPOSITE = pipeline("hotbar_composite", "COMPOSITE", true);

    public boolean isActive() { return active; }
    public void clear() { active = false; }

    private final String name;
    private GpuTexture horizontal, blurred;
    private GpuTextureView horizontalView, blurredView;
    private GpuBuffer information;
    private int left, bottom, width, height, guiWidth, guiHeight;
    private boolean active;

    public GuiBlurRegion(String name) { this.name = name; }

    public void setBounds(GuiGraphics graphics, int x, int y, int w, int h) {
        int x1 = Math.max(0, x), y1 = Math.max(0, y);
        int x2 = Math.min(graphics.guiWidth(), x + w), y2 = Math.min(graphics.guiHeight(), y + h);
        guiWidth = x2 - x1;
        guiHeight = y2 - y1;
        active = guiWidth > 0 && guiHeight > 0;
        int scale = Minecraft.getInstance().getWindow().getGuiScale();
        left = x1 * scale;
        bottom = (graphics.guiHeight() - y2) * scale;
        width = guiWidth * scale;
        height = guiHeight * scale;
    }

    public void apply(GpuTextureView scene, float amount) {
        width = Math.min(width, scene.getWidth(0) - left);
        height = Math.min(height, scene.getHeight(0) - bottom);
        if (!active || width <= 0 || height <= 0) return;
        allocate();
        ByteBuffer data = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
        // Variances add across separable blur pairs. At 140%, the extra pair
        // targets a 40% larger blur radius, with no sharp-image blend.
        float extraRadius = (float) Math.sqrt(Math.max(0, amount * amount - 1));
        data.putFloat((float) left / scene.getWidth(0)).putFloat((float) bottom / scene.getHeight(0))
                .putFloat((float) width / scene.getWidth(0)).putFloat((float) height / scene.getHeight(0))
                .putFloat(1f / guiWidth).putFloat(1f / guiHeight).putFloat(Math.min(1, amount)).putFloat(extraRadius).flip();
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(information.slice(), data);
        pass(HORIZONTAL, scene, horizontalView, false);
        pass(VERTICAL, horizontalView, blurredView, false);
        if (amount > 1) {
            pass(EXTRA_HORIZONTAL, blurredView, horizontalView, false);
            pass(EXTRA_VERTICAL, horizontalView, blurredView, false);
        }
        pass(COMPOSITE, blurredView, scene, true);
    }

    private void allocate() {
        if (horizontal != null && horizontal.getWidth(0) == guiWidth && horizontal.getHeight(0) == guiHeight) return;
        release();
        var device = RenderSystem.getDevice();
        int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
        horizontal = device.createTexture("Donut " + name + " horizontal", usage, TextureFormat.RGBA8, guiWidth, guiHeight, 1, 1);
        horizontalView = device.createTextureView(horizontal);
        blurred = device.createTexture("Donut " + name + " blurred", usage, TextureFormat.RGBA8, guiWidth, guiHeight, 1, 1);
        blurredView = device.createTextureView(blurred);
        information = device.createBuffer(() -> "Donut " + name + " bounds", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 32);
    }

    private void pass(RenderPipeline pipeline, GpuTextureView source, GpuTextureView target, boolean composite) {
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Donut " + name + " blur", target, OptionalInt.empty())) {
            pass.setPipeline(pipeline);
            pass.setUniform("HotbarInfo", information);
            pass.bindTexture("Sampler0", source, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            if (composite) pass.enableScissor(left, bottom, width, height);
            pass.draw(0, 3);
        }
    }

    public void release() {
        if (horizontalView != null) horizontalView.close();
        if (blurredView != null) blurredView.close();
        if (horizontal != null) horizontal.close();
        if (blurred != null) blurred.close();
        if (information != null) information.close();
        horizontalView = blurredView = null;
        horizontal = blurred = null;
        information = null;
    }
    private static RenderPipeline pipeline(String name, String define, boolean composite) {
        return pipeline(name, define, composite, false);
    }

    private static RenderPipeline pipeline(String name, String define, boolean composite, boolean extra) {
        var builder = RenderPipeline.builder().withLocation(id("pipeline/" + name))
                .withVertexShader("core/screenquad").withFragmentShader(id("core/hotbar_blur"))
                .withShaderDefine(define).withSampler("Sampler0").withUniform("HotbarInfo", UniformType.UNIFORM_BUFFER)
                .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false).withCull(false);
        if (extra) builder.withShaderDefine("EXTRA_BLUR").withShaderDefine("LOCAL_SOURCE");
        if (composite) builder.withBlend(BlendFunction.TRANSLUCENT);
        return RenderPipelines.register(builder.build());
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("donut", path); }
}
