package dev.donut.client.mixin;

import dev.donut.client.chunkfinder.ChunkFinderScanner;
import dev.donut.client.amethyst.AmethystScanner;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe received chunks, light and block changes after vanilla has applied them. */
@Mixin(ClientPacketListener.class)
abstract class BaseFinderPacketMixin {
    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void donut$chunk(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        ChunkFinderScanner.packetApplied(packet);
        AmethystScanner.packetApplied(packet);
        ChunkFinderScanner.lightQueued(Minecraft.getInstance().level, packet.getX(), packet.getZ());
    }
    @Inject(method = "handleLightUpdatePacket", at = @At("RETURN"))
    private void donut$light(ClientboundLightUpdatePacket packet, CallbackInfo ci) {
        ChunkFinderScanner.lightQueued(Minecraft.getInstance().level, packet.getX(), packet.getZ());
    }
    @Inject(method = "handleBlockUpdate", at = @At("RETURN"))
    private void donut$block(ClientboundBlockUpdatePacket packet, CallbackInfo ci) { ChunkFinderScanner.packetApplied(packet); AmethystScanner.packetApplied(packet); }
    @Inject(method = "handleChunkBlocksUpdate", at = @At("RETURN"))
    private void donut$section(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) { ChunkFinderScanner.packetApplied(packet); AmethystScanner.packetApplied(packet); }
    @Inject(method = "handleForgetLevelChunk", at = @At("RETURN"))
    private void donut$forget(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) { ChunkFinderScanner.packetApplied(packet); AmethystScanner.packetApplied(packet); }
}
