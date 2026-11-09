package dev.donut.client.mixin;

import dev.donut.client.sound.LocalActionTracker;
import dev.donut.client.sound.SoundCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Successful local predictions contribute probable-self context only; no interactions are fabricated. */
@Mixin(MultiPlayerGameMode.class)
abstract class SoundDetectorLocalMixin {
    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void donut$soundUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> ci) {
        if (!SoundCapture.active() || !ci.getReturnValue().consumesAction()) return;
        var level = Minecraft.getInstance().level;
        if (level == null || !level.hasChunkAt(hit.getBlockPos())) return;
        var target = level.getBlockState(hit.getBlockPos());
        LocalActionTracker.record(hit.getBlockPos(), SoundCapture.interactionCategory(target.getBlock()));
        if (target.getBlock() instanceof ChestBlock && target.hasProperty(ChestBlock.TYPE) && target.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
            LocalActionTracker.record(ChestBlock.getConnectedBlockPos(hit.getBlockPos(), target), "containers");
        if (player.getItemInHand(hand).getItem() instanceof BlockItem) {
            LocalActionTracker.record(hit.getBlockPos(), "building");
            LocalActionTracker.record(hit.getBlockPos().relative(hit.getDirection()), "building");
        }
    }
    @Inject(method = "startDestroyBlock", at = @At("RETURN"))
    private void donut$soundStartBreak(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> ci) {
        if (Boolean.TRUE.equals(ci.getReturnValue())) LocalActionTracker.record(pos, "building");
    }
    @Inject(method = "continueDestroyBlock", at = @At("RETURN"))
    private void donut$soundContinueBreak(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> ci) {
        if (Boolean.TRUE.equals(ci.getReturnValue())) LocalActionTracker.record(pos, "building");
    }
    @Inject(method = "interact", at = @At("RETURN"))
    private void donut$soundInteract(Player player, Entity target, InteractionHand hand, CallbackInfoReturnable<InteractionResult> ci) {
        if (SoundCapture.active() && ci.getReturnValue().consumesAction()) LocalActionTracker.record(target.position(), "farming");
    }
    @Inject(method = "interactAt", at = @At("RETURN"))
    private void donut$soundInteractAt(Player player, Entity target, EntityHitResult hit, InteractionHand hand, CallbackInfoReturnable<InteractionResult> ci) {
        if (SoundCapture.active() && ci.getReturnValue().consumesAction()) LocalActionTracker.record(target.position(), "farming");
    }
}
