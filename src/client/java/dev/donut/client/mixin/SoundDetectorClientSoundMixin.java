package dev.donut.client.mixin;

import dev.donut.client.sound.SoundCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe logical dispatch before audio settings/channel allocation can discard it. */
@Mixin(ClientLevel.class)
abstract class SoundDetectorClientSoundMixin {
    @Inject(method = "playLocalSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V", at = @At("HEAD"))
    private void donut$soundEntityEnter(Entity entity, SoundEvent sound, SoundSource source, float volume, float pitch, CallbackInfo ci) {
        if (SoundCapture.active() && entity != null) SoundCapture.clientSound(sound, source, entity.position(), entity, volume, pitch);
    }
    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"))
    private void donut$soundSeedEntityEnter(Entity excluded, Entity emitter, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed, CallbackInfo ci) {
        if (SoundCapture.active() && excluded == Minecraft.getInstance().player && emitter != null)
            SoundCapture.clientSound(sound.value(), source, emitter.position(), emitter, volume, pitch);
    }
    @Inject(method = "playPlayerSound", at = @At("HEAD"))
    private void donut$soundLocalPlayer(SoundEvent sound, SoundSource source, float volume, float pitch, CallbackInfo ci) {
        if (SoundCapture.active() && Minecraft.getInstance().player != null) {
            var player = Minecraft.getInstance().player;
            SoundCapture.clientSound(sound, source, player.position(), player, volume, pitch);
        }
    }
    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V", at = @At("HEAD"))
    private void donut$soundPredicted(Entity excluded, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed, CallbackInfo ci) {
        // This overload plays locally only for the initiating local player. Keep that exact self context.
        if (SoundCapture.active() && excluded == Minecraft.getInstance().player)
            SoundCapture.clientSound(sound.value(), source, new Vec3(x, y, z), excluded, volume, pitch);
    }
    @Inject(method = "playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V", at = @At("HEAD"))
    private void donut$soundDispatch(double x, double y, double z, SoundEvent sound, SoundSource source, float volume, float pitch, boolean delay, CallbackInfo ci) {
        SoundCapture.clientSound(sound, source, new Vec3(x, y, z), null, volume, pitch);
    }
}
