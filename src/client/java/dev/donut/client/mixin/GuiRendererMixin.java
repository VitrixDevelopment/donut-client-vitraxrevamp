package dev.donut.client.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.donut.client.gui.GlassPanel;
import dev.donut.client.hotbar.HotbarBlur;
import dev.donut.client.namehider.ScoreboardBlur;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Supplier;

@Mixin(GuiRenderer.class)
abstract class GuiRendererMixin {
    @Shadow @Final private List<?> meshesToDraw;

    @Unique private int donut$hotbarDrawIndex = -1;
    @Unique private int donut$itemNameDrawIndex = -1;
    @Unique private int donut$scoreboardDrawIndex = -1;

    @Shadow
    private void executeDrawRange(Supplier<String> name, RenderTarget target, GpuBufferSlice fog,
                                  GpuBufferSlice transforms, GpuBuffer indices,
                                  VertexFormat.IndexType indexType, int start, int end) {
        throw new AssertionError();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void donut$prepareGlass(GpuBufferSlice fog, CallbackInfo ci) {
        donut$hotbarDrawIndex = -1;
        donut$itemNameDrawIndex = -1;
        donut$scoreboardDrawIndex = -1;
        HotbarBlur.beforeRender();
        ScoreboardBlur.beforeRender();
        GlassPanel.prepareScene();
    }

    @Inject(method = "addElementToMesh", at = @At("TAIL"))
    private void donut$recordHotbarBoundary(GuiElementRenderState state, CallbackInfo ci) {
        if (state.pipeline() == HotbarBlur.markerPipeline()) {
            // Changing to the marker pipeline flushes the preceding HUD element's last mesh.
            // The pending marker mesh becomes the next draw at this index.
            donut$hotbarDrawIndex = meshesToDraw.size();
        } else if (state.pipeline() == HotbarBlur.itemNameMarkerPipeline()) {
            donut$itemNameDrawIndex = meshesToDraw.size();
        } else if (state.pipeline() == ScoreboardBlur.markerPipeline()) {
            donut$scoreboardDrawIndex = meshesToDraw.size();
        }
    }

    @Redirect(method = "draw", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/render/GuiRenderer;executeDrawRange(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/vertex/VertexFormat$IndexType;II)V"))
    private void donut$blurAfterHotbar(GuiRenderer renderer, Supplier<String> name, RenderTarget target,
                                      GpuBufferSlice fog, GpuBufferSlice transforms, GpuBuffer indices,
                                      VertexFormat.IndexType indexType, int start, int end) {
        int cursor = start;
        int hotbar = donut$hotbarDrawIndex, itemName = donut$itemNameDrawIndex;
        if (hotbar >= cursor && hotbar < end) {
            if (cursor < hotbar) {
                executeDrawRange(name, target, fog, transforms, indices, indexType, cursor, hotbar);
            }
            // Finish the GUI pass before sampling its color attachment.
            HotbarBlur.apply(false);
            donut$hotbarDrawIndex = -1;
            cursor = hotbar;
        }
        if (itemName >= cursor && itemName < end) {
            if (cursor < itemName) executeDrawRange(name, target, fog, transforms, indices, indexType, cursor, itemName);
            HotbarBlur.apply(true);
            donut$itemNameDrawIndex = -1;
            cursor = itemName;
        }
        int scoreboard = donut$scoreboardDrawIndex;
        if (scoreboard >= cursor && scoreboard < end) {
            if (cursor < scoreboard) executeDrawRange(name, target, fog, transforms, indices, indexType, cursor, scoreboard);
            ScoreboardBlur.apply();
            donut$scoreboardDrawIndex = -1;
            cursor = scoreboard;
        }
        if (cursor < end) executeDrawRange(name, target, fog, transforms, indices, indexType, cursor, end);
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void donut$closeGlass(CallbackInfo ci) {
        HotbarBlur.close();
        ScoreboardBlur.close();
        GlassPanel.close();
    }
}
