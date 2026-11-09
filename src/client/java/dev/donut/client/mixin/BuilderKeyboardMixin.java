package dev.donut.client.mixin;

import dev.donut.client.spectrebuilder.BuilderPlacement;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class BuilderKeyboardMixin {
    @Inject(method="keyPress",at=@At("HEAD"),cancellable=true)
    private void donut$placementKey(long window,int action,KeyEvent event,CallbackInfo ci){
        var mc=Minecraft.getInstance();
        if(window==mc.getWindow().handle()&&BuilderPlacement.key(mc,event.key(),action))ci.cancel();
    }
}
