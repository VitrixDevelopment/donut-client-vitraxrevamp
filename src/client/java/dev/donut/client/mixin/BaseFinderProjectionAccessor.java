package dev.donut.client.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface BaseFinderProjectionAccessor {
    @Invoker("getFov") float donut$worldFov(Camera camera, float delta, boolean useSetting);
}
