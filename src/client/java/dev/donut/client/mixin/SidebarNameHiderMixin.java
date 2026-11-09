package dev.donut.client.mixin;

import dev.donut.client.namehider.NameHiderController;
import dev.donut.client.namehider.ScoreboardBlur;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.scores.Objective;

/** Adapted from Name Hider 1.0.3 (CC0-1.0); see licenses/name-hider-CC0-1.0.txt. */
@Mixin(Gui.class)
abstract class SidebarNameHiderMixin {
    @Inject(method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/scores/Objective;)V", at = @At("HEAD"))
    private void donut$beginSidebar(GuiGraphics graphics, Objective objective, CallbackInfo ci) {
        ScoreboardBlur.beginSidebar();
    }

    @Redirect(method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/scores/Objective;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"), require = 2)
    private void donut$recordSidebarBounds(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        graphics.fill(x1, y1, x2, y2, color);
        ScoreboardBlur.include(x1, y1, x2, y2);
    }

    @Inject(method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/scores/Objective;)V", at = @At("RETURN"))
    private void donut$blurSidebar(GuiGraphics graphics, Objective objective, CallbackInfo ci) {
        ScoreboardBlur.mark(graphics);
    }

    @ModifyArg(method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/scores/Objective;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"),
            index = 1, require = 3, expect = 3, allow = 3)
    private Component donut$hideSidebarName(Component text) {
        return NameHiderController.sanitize(text);
    }

    @ModifyArg(method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/scores/Objective;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/network/chat/FormattedText;)I"),
            index = 0, require = 2, expect = 2, allow = 2)
    private FormattedText donut$measureSanitizedSidebar(FormattedText text) {
        return text instanceof Component component ? NameHiderController.sanitize(component) : text;
    }

    // Sidebar scores cache their width while the display entries are assembled.
    // Measure the same text we draw so a custom number format cannot leave a gap.
    @ModifyArg(method = "method_55439(Lnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/network/chat/numbers/NumberFormat;Lnet/minecraft/world/scores/PlayerScoreEntry;)Lnet/minecraft/client/gui/Gui$1DisplayEntry;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/network/chat/FormattedText;)I"),
            index = 0, require = 1, expect = 1, allow = 1)
    private FormattedText donut$measureSanitizedSidebarScore(FormattedText text) {
        return text instanceof Component component ? NameHiderController.sanitize(component) : text;
    }
}
