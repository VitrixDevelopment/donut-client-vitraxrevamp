package dev.donut.client.mixin;

import dev.donut.client.hotbar.HotbarBlur;
import dev.donut.client.namehider.ScoreboardBlur;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
abstract class HotbarMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void donut$beginHotbarFrame(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        HotbarBlur.beginFrame();
        ScoreboardBlur.beginFrame();
    }

    @Inject(method = "renderItemHotbar", at = @At("RETURN"))
    private void donut$markHotbar(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        HotbarBlur.mark(graphics);
    }

    @Redirect(method = "renderSelectedItemName", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/GuiGraphics;drawStringWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V"))
    private void donut$includeItemName(GuiGraphics graphics, Font font, Component name,
                                       int x, int y, int width, int color) {
        graphics.drawStringWithBackdrop(font, name, x, y, width, color);
        HotbarBlur.markSelectedItemName(graphics, x, y, width);
    }
}
