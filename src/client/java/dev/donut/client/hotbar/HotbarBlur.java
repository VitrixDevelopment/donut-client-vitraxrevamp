package dev.donut.client.hotbar;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.HotbarSettings;
import dev.donut.client.render.GuiBlurRegion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;

/** Blurs the hotbar and selected-item name after vanilla submits both, before later HUD/screens. */
public final class HotbarBlur {
    private static final RenderPipeline MARKER = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
                    .withLocation(id("pipeline/hotbar_boundary")).build());
    private static final RenderPipeline NAME_MARKER = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
                    .withLocation(id("pipeline/item_name_boundary")).build());
    private static HotbarBlur instance;
    private final ClientConfig config;
    private static final GuiBlurRegion hotbar = new GuiBlurRegion("hotbar");
    private static final GuiBlurRegion itemName = new GuiBlurRegion("selected item name");
    private static boolean requested;
    private static float amount;

    public HotbarBlur(ClientConfig config) {
        this.config = config;
        instance = this;
    }

    public static boolean isEnabled() { return instance != null && instance.config.hotbar.enabled; }
    public static boolean hideHeldItems() { return isEnabled() && instance.config.hotbar.hideHeldItems; }
    public static RenderPipeline markerPipeline() { return MARKER; }
    public static RenderPipeline itemNameMarkerPipeline() { return NAME_MARKER; }

    public static void beginFrame() {
        requested = false;
        hotbar.clear(); itemName.clear();
    }

    /** Called after vanilla submits all nine slots, offhand, counts and durability overlays. */
    public static void mark(GuiGraphics graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled() || client.options.hideGui || !(client.getCameraEntity() instanceof Player player)) return;
        amount = Math.clamp(instance.config.hotbar.blurPercent, 0, HotbarSettings.MAX_BLUR_PERCENT) / 100f;
        if (amount == 0) return;
        int center = graphics.guiWidth() / 2;
        int x1 = center - 92, x2 = center + 92;
        if (!player.getOffhandItem().isEmpty()) {
            if (player.getMainArm() == HumanoidArm.RIGHT) x1 = center - 121;
            else x2 = center + 121;
        }
        // Includes the selected slot border and the vanilla item pickup animation.
        hotbar.setBounds(graphics, x1, graphics.guiHeight() - 28, x2 - x1, 28);
        requested = hotbar.isActive();
        if (requested) boundary(graphics, MARKER);
    }

    /** Receives vanilla's actual text bounds, including renamed, styled and translated items. */
    public static void markSelectedItemName(GuiGraphics graphics, int x, int y, int textWidth) {
        if (!requested) return;
        // Covers the two-pixel backdrop, italic overhang and text shadow without
        // expanding the hotbar rectangle over health, hunger or experience bars.
        itemName.setBounds(graphics, x - 4, y - 2, textWidth + 8, 13);
        if (itemName.isActive()) boundary(graphics, NAME_MARKER);
    }

    private static void boundary(GuiGraphics graphics, RenderPipeline marker) {
        graphics.nextStratum();
        graphics.fill(marker, 0, 0, 1, 1, 0);
        graphics.nextStratum();
    }

    public static void beforeRender() {
        if (!requested) close();
        else if (!itemName.isActive()) itemName.release();
    }

    /** No framebuffer feedback: both blur passes finish before the adjustable composite. */
    public static void apply(boolean selectedItemName) {
        if (!requested) return;
        RenderSystem.assertOnRenderThread();
        GpuTextureView scene = Minecraft.getInstance().getMainRenderTarget().getColorTextureView();
        if (scene == null) return;
        (selectedItemName ? itemName : hotbar).apply(scene, amount);
        if (selectedItemName || !itemName.isActive()) requested = false;
    }

    public static void close() {
        requested = false;
        hotbar.release();
        itemName.release();
    }

    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("donut", path); }
}
