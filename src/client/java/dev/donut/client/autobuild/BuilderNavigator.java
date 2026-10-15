package dev.donut.client.autobuild;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;

/**
 * Walks using vanilla player input. Does not teleport, fly, mine, or modify the world.
 * When the player stalls or the server rubber-bands the position, the walker pauses briefly,
 * finds a fresh route from the corrected spot and continues instead of abandoning the goal.
 */
public final class BuilderNavigator {
    public enum Result { MOVING, ARRIVED, NO_PATH, STUCK, RECOVERING }

    private static final int STALL_TICKS = 12;
    private static final int RECOVERY_PAUSE = 10;
    private static final int MAX_RECOVERIES = 4;

    private LocalPlayer owner;
    private ClientInput saved;
    private final SteeringInput steering = new SteeringInput();
    private List<BuilderPathfinder.Cell> route = List.of();
    private int index, stalled, recovery, recoveries;
    private Vec3 lastPosition;
    private double bestDistance = Double.POSITIVE_INFINITY;
    private BlockPos target;
    private final Set<BuilderPathfinder.Cell> rejectedGoals = new HashSet<>();

    private static final class SteeringInput extends ClientInput {
        boolean forward, jump, sneak;
        @Override public void tick() {
            keyPresses = new Input(forward, false, false, false, jump, sneak, false);
            moveVector = forward ? new Vec2(0, 1) : Vec2.ZERO;
        }
        @Override public void makeJump() { if (forward) super.makeJump(); }
    }

    public void hold(Minecraft client, boolean sneak) {
        if (client.player == null) return;
        if (owner != client.player) {
            release(); owner = client.player; saved = owner.input; owner.input = steering;
        }
        steering.forward = false; steering.jump = false; steering.sneak = sneak; steering.tick();
    }

    public void release() {
        if (owner != null && owner.input == steering && saved != null) owner.input = saved;
        owner = null; saved = null; steering.forward = false; steering.jump = false; steering.sneak = false;
        route = List.of(); index = 0; stalled = 0; lastPosition = null; recovery = 0;
    }
    public void replan() { route = List.of(); index = 0; stalled = 0; lastPosition = null; }
    public void reset() { release(); target = null; rejectedGoals.clear(); recoveries = 0; bestDistance = Double.POSITIVE_INFINITY; }
    public void rejectCurrent(Minecraft client) {
        if (client.player != null) rejectedGoals.add(cell(client.player.blockPosition()));
        replan();
    }
    /** The server corrected the position: stop trusting the old route, pause, then replan. */
    public void rubberBanded() {
        if (route.isEmpty() && recovery == 0 && stalled == 0) return;
        replan();
        recoveries = Math.min(recoveries + 1, MAX_RECOVERIES);
        recovery = Math.max(recovery, RECOVERY_PAUSE);
    }
    public boolean recovering() { return recovery > 0; }
    public int routeLength() { return Math.max(0, route.size() - index); }

    public Result moveTo(Minecraft client, BlockPos destination) {
        if (client.level == null || client.player == null) return Result.NO_PATH;
        hold(client, false);
        if (!destination.equals(target)) {
            target = destination.immutable(); rejectedGoals.clear(); replan();
            recoveries = 0; bestDistance = Double.POSITIVE_INFINITY;
        }
        BuilderPathfinder.Cell feet = cell(client.player.blockPosition());
        if (goal(client, feet, destination)) {
            recoveries = 0; bestDistance = Double.POSITIVE_INFINITY;
            return Result.ARRIVED;
        }
        if (recovery > 0) {
            // Standing still for a moment lets lag settle before the world is trusted again.
            recovery--;
            if (recovery == 0) { route = List.of(); index = 0; stalled = 0; lastPosition = null; }
            return Result.RECOVERING;
        }
        if (route.isEmpty() || index >= route.size()) {
            route = BuilderPathfinder.find(feet, cell(destination), terrain(client),
                    at -> goal(client, at, destination), 12000, 96);
            index = 0;
            if (route.isEmpty()) return Result.NO_PATH;
        }
        BuilderPathfinder.Cell next = route.get(index);
        Vec3 aim = new Vec3(next.x()+.5, next.y(), next.z()+.5);
        if (reached(client.player.position(), next)) {
            index++;
            if (index >= route.size()) { replan(); return Result.MOVING; }
            next = route.get(index);
            aim = new Vec3(next.x()+.5, next.y(), next.z()+.5);
        }
        // After a knockback or rubber-band the player may stand past the next cells; skip them.
        while (index + 1 < route.size() && closer(route.get(index + 1), next, client.player.position())) {
            index++;
            next = route.get(index);
            aim = new Vec3(next.x()+.5, next.y(), next.z()+.5);
        }
        Vec3 delta = aim.subtract(client.player.position());
        client.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        client.player.setXRot(10);
        steering.forward = true;
        steering.jump = next.y() > client.player.getY()+.25 && client.player.onGround();
        steering.tick();
        if (lastPosition != null && client.player.position().distanceToSqr(lastPosition) < .0004) stalled++;
        else stalled = 0;
        lastPosition = client.player.position();
        double distance = client.player.position().distanceToSqr(Vec3.atCenterOf(destination));
        if (distance < bestDistance - .25) recoveries = 0;
        bestDistance = Math.min(bestDistance, distance);
        if (stalled > STALL_TICKS) return beginRecovery(client);
        return Result.MOVING;
    }

    /** Stop input, forget the route and try again shortly. Repeated failures give up on this stance. */
    private Result beginRecovery(Minecraft client) {
        stalled = 0;
        recoveries++;
        if (recoveries > MAX_RECOVERIES) {
            recoveries = 0;
            rejectCurrent(client);
            return Result.STUCK;
        }
        recovery = RECOVERY_PAUSE;
        route = List.of(); index = 0; lastPosition = null;
        return Result.RECOVERING;
    }

    private static boolean reached(Vec3 position, BuilderPathfinder.Cell waypoint) {
        double dx = position.x - (waypoint.x() + .5), dz = position.z - (waypoint.z() + .5);
        return dx * dx + dz * dz < .12 && Math.abs(position.y - waypoint.y()) < .6;
    }
    private static boolean closer(BuilderPathfinder.Cell candidate, BuilderPathfinder.Cell current, Vec3 position) {
        return distanceTo(position, candidate) < distanceTo(position, current) - .09;
    }
    private static double distanceTo(Vec3 position, BuilderPathfinder.Cell waypoint) {
        return position.distanceToSqr(new Vec3(waypoint.x() + .5, waypoint.y(), waypoint.z() + .5));
    }

    private BuilderPathfinder.Terrain terrain(Minecraft client) {
        return new BuilderPathfinder.Terrain() {
            @Override public boolean standable(BuilderPathfinder.Cell at) {
                BlockPos pos = pos(at);
                if (!MaterialTracker.isLoaded(client.level,pos) || !MaterialTracker.isLoaded(client.level,pos.above(2))) return false;
                var ground = client.level.getBlockState(pos.below());
                if (ground.is(Blocks.MAGMA_BLOCK) || ground.is(Blocks.CAMPFIRE) || ground.is(Blocks.SOUL_CAMPFIRE)
                        || ground.is(Blocks.CACTUS) || ground.is(Blocks.POWDER_SNOW)
                        || !ground.isCollisionShapeFullBlock(client.level, pos.below())) return false;
                return headroom(at);
            }
            @Override public boolean headroom(BuilderPathfinder.Cell at) {
                BlockPos pos = pos(at);
                if (!MaterialTracker.isLoaded(client.level,pos) || !client.level.getFluidState(pos).isEmpty()
                        || !client.level.getFluidState(pos.above()).isEmpty()) return false;
                var state = client.level.getBlockState(pos);
                if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.SWEET_BERRY_BUSH)
                        || state.is(Blocks.POWDER_SNOW)) return false;
                return client.level.noCollision(client.player, new AABB(at.x()+.2, at.y()+.01, at.z()+.2,
                        at.x()+.8, at.y()+1.8, at.z()+.8));
            }
        };
    }

    /** True when standing at {@code at} lets the player place a block into {@code destination} within reach. */
    public static boolean canPlaceFrom(Minecraft client, BuilderPathfinder.Cell at, BlockPos destination) {
        return client.level != null && client.player != null && goal(client, at, destination);
    }

    private boolean goal(Minecraft client, BuilderPathfinder.Cell at, BlockPos destination) {
        return goal(client, at, destination, rejectedGoals);
    }

    private static boolean goal(Minecraft client, BuilderPathfinder.Cell at, BlockPos destination,
                                Set<BuilderPathfinder.Cell> rejected) {
        if (rejected != null && rejected.contains(at)) return false;
        Vec3 eye = new Vec3(at.x()+.5, at.y()+client.player.getEyeHeight(), at.z()+.5);
        // A block cannot be placed into the walking player's body.
        if (at.x()==destination.getX() && at.z()==destination.getZ()
                && at.y()<=destination.getY() && at.y()+1.8>destination.getY()) return false;
        // When the only usable neighbor is below, the top placement face must be viewed from above.
        // Center visibility alone would repeatedly choose ground cells below an elevated support column.
        if (client.level.getBlockState(destination).canBeReplaced() && eye.y<=destination.getY()+.01) {
            boolean alternativeFace=false;
            for (var direction:net.minecraft.core.Direction.values()) {
                if (direction==net.minecraft.core.Direction.DOWN) continue;
                BlockPos neighbor=destination.relative(direction);
                if (!MaterialTracker.isLoaded(client.level,neighbor)) continue;
                var state=client.level.getBlockState(neighbor);
                if (!state.canBeReplaced() && !state.getCollisionShape(client.level,neighbor).isEmpty()) { alternativeFace=true; break; }
            }
            if (!alternativeFace) return false;
        }
        double reach = Math.min(4.25, client.player.blockInteractionRange() - .25);
        if (eye.distanceToSqr(Vec3.atCenterOf(destination)) > reach * reach) return false;
        BlockHitResult hit = client.level.clip(new ClipContext(eye, Vec3.atCenterOf(destination),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(destination);
    }
    private static BuilderPathfinder.Cell cell(BlockPos p) { return new BuilderPathfinder.Cell(p.getX(),p.getY(),p.getZ()); }
    private static BlockPos pos(BuilderPathfinder.Cell p) { return new BlockPos(p.x(),p.y(),p.z()); }
}
