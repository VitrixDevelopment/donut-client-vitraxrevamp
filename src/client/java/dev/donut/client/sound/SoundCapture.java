package dev.donut.client.sound;

import dev.donut.client.mixin.SoundEntityEventAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.*;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

import static dev.donut.client.sound.SoundObservation.Actor;
import static dev.donut.client.sound.SoundObservation.Route;

/** Main-thread capture bridge. It never requests data, sends packets, or guesses a nearby actor. */
public final class SoundCapture {
    private static int packetDepth;
    private static int excludedDepth;
    private static final Map<BlockPos, Integer> openerCounts = new HashMap<>();
    private record MiningState(BlockPos pos, int progress) { }
    private static final Map<Integer, MiningState> mining = new HashMap<>();
    private static final StackWalker dispatchWalker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private SoundCapture() { }
    public static void clear() { packetDepth = 0; excludedDepth = 0; openerCounts.clear(); mining.clear(); LocalActionTracker.clear(); }
    public static void forgetChunk(int x, int z) {
        if (!Minecraft.getInstance().isSameThread()) return;
        openerCounts.keySet().removeIf(pos -> (pos.getX() >> 4) == x && (pos.getZ() >> 4) == z);
        mining.values().removeIf(state -> (state.pos.getX() >> 4) == x && (state.pos.getZ() >> 4) == z);
    }
    public static boolean active() { return SoundDetectorController.enabled() && Minecraft.getInstance().isSameThread(); }
    public static void enterPacket() { if (Minecraft.getInstance().isSameThread()) packetDepth++; }
    public static void leavePacket() { if (Minecraft.getInstance().isSameThread()) packetDepth = Math.max(0, packetDepth - 1); }
    public static void withoutCapture(Runnable action) {
        excludedDepth++;
        try { action.run(); } finally { excludedDepth = Math.max(0, excludedDepth - 1); }
    }
    public static void sound(ClientboundSoundPacket packet) {
        if (!active()) return;
        emit(Route.POSITIONAL, SoundCatalog.id(packet.getSound().value()), packet.getSource(),
                new Vec3(packet.getX(), packet.getY(), packet.getZ()), null, -1, "", null,
                packet.getVolume(), packet.getPitch(), packet.getSeed(), 0, 0, false);
    }
    public static void sound(ClientboundSoundEntityPacket packet) {
        if (!active()) return;
        Entity entity = entity(packet.getId());
        emit(Route.ENTITY_SOUND, SoundCatalog.id(packet.getSound().value()), packet.getSource(),
                entity == null ? null : entity.position(), entity, packet.getId(), "", null,
                packet.getVolume(), packet.getPitch(), packet.getSeed(), 0, 0, false);
    }
    public static void clientSound(SoundEvent sound, SoundSource source, Vec3 position, Entity entity, float volume, float pitch) {
        if (!active() || excludedDepth > 0) return;
        if (packetDepth > 0) {
            // A third-party handler can throw before RETURN. A stale depth must not silence later dispatch forever.
            if (dispatchWalker.walk(frames -> frames.limit(48).anyMatch(frame -> frame.getDeclaringClass() == ClientPacketListener.class))) return;
            packetDepth = 0;
        }
        emit(Route.CLIENT_DERIVED, SoundCatalog.id(sound), source, position, entity, entity == null ? -1 : entity.getId(),
                "", null, volume, pitch, 0, 0, 0, false);
    }
    public static void block(ClientboundBlockEventPacket packet) {
        if (!active()) return;
        Block block = packet.getBlock();
        BlockPos pos = packet.getPos();
        SoundCatalog.Classification action = null;
        if ((block instanceof ChestBlock || block instanceof EnderChestBlock || block instanceof ShulkerBoxBlock) && packet.getB0() == 1) {
            // The count can change without an open/close transition. Never count each viewer as a new opening.
            BlockState state = loadedState(pos);
            if (state != null && state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE)
                    && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos partner = ChestBlock.getConnectedBlockPos(pos, state);
                if (partner.asLong() < pos.asLong()) pos = partner;
            }
            Integer previous = openerCounts.put(pos.immutable(), packet.getB1());
            if (openerCounts.size() > 512) { openerCounts.clear(); openerCounts.put(pos.immutable(), packet.getB1()); }
            boolean opening = packet.getB1() > 0;
            if (previous != null && (previous > 0) == opening) return;
            // An initial zero only supplies state; it is not proof that a close just occurred.
            if (previous == null && !opening) return;
            action = c("containers", opening ? "container.open" : "container.close", opening ? "Container opened" : "Container closed", 50);
        } else if (block instanceof PistonBaseBlock && packet.getB0() >= 0 && packet.getB0() <= 2) {
            boolean extending = packet.getB0() == 0;
            action = c("machinery", extending ? "piston.extend" : "piston.retract", extending ? "Piston extended" : "Piston retracted", 15);
        } else if (block instanceof NoteBlock && packet.getB0() == 0) action = c("music", "note.play", "Note block played", 15);
        else if (block instanceof BellBlock && packet.getB0() == 1) action = c("music", "bell.ring", "Bell rang", 15);
        if (action == null) action = c("custom", "block.event", "Unclassified block event", 0);
        emit(Route.BLOCK_EVENT, "", null, Vec3.atCenterOf(pos), null, -1, SoundCatalog.blockId(block), action,
                0, 0, 0, packet.getB0(), packet.getB1(), false);
    }
    public static void level(ClientboundLevelEventPacket packet) {
        if (!active()) return;
        int code = packet.getType();
        SoundCatalog.Classification action = switch (code) {
            case LevelEvent.SOUND_DISPENSER_DISPENSE, LevelEvent.SOUND_DISPENSER_PROJECTILE_LAUNCH -> c("machinery", "dispenser.dispense", "Dispenser / dropper activated", 15);
            case LevelEvent.SOUND_DISPENSER_FAIL -> c("machinery", "dispenser.fail", "Dispenser / dropper failed", 15);
            case LevelEvent.SOUND_CRAFTER_CRAFT -> c("machinery", "crafter.craft", "Crafter crafted", 15);
            case LevelEvent.SOUND_CRAFTER_FAIL -> c("machinery", "crafter.fail", "Crafter failed", 15);
            case LevelEvent.SOUND_ANVIL_USED -> c("workstations", "workstation.anvil_use", "Anvil used", 50);
            case LevelEvent.SOUND_ANVIL_LAND, LevelEvent.SOUND_ANVIL_BROKEN -> c("building", "anvil.land", "Anvil landed / broke", 25);
            case LevelEvent.SOUND_GRINDSTONE_USED -> c("workstations", "workstation.grindstone_use", "Grindstone used", 50);
            case LevelEvent.SOUND_SMITHING_TABLE_USED -> c("workstations", "workstation.smithing_table_use", "Smithing table used", 50);
            case LevelEvent.SOUND_PAGE_TURN -> c("workstations", "lectern.page", "Lectern page turned", 30);
            case LevelEvent.SOUND_PLAY_JUKEBOX_SONG -> c("music", "jukebox.play", "Jukebox started", 30);
            case LevelEvent.SOUND_STOP_JUKEBOX_SONG -> c("music", "jukebox.stop", "Jukebox stopped", 15);
            case LevelEvent.PARTICLES_DESTROY_BLOCK -> c("building", "building.break", "Block broken", 25);
            case LevelEvent.PARTICLES_AND_SOUND_WAX_ON, LevelEvent.PARTICLES_WAX_OFF, LevelEvent.PARTICLES_SCRAPE -> c("building", "copper.modify", "Copper waxed / scraped", 30);
            case LevelEvent.PARTICLES_AND_SOUND_BRUSH_BLOCK_COMPLETE -> c("farming", "archaeology.brush", "Brushing completed", 30);
            case LevelEvent.COMPOSTER_FILL -> c("farming", "composter.fill", "Composter filled", 15);
            case LevelEvent.SOUND_BREWING_STAND_BREW -> c("machinery", "brewing.complete", "Brewing completed", 15);
            case LevelEvent.PARTICLES_SCULK_SHRIEK -> c("sculk", "sculk.shriek", "Sculk shriek", 0);
            case LevelEvent.ANIMATION_VAULT_ACTIVATE, LevelEvent.ANIMATION_VAULT_DEACTIVATE, LevelEvent.ANIMATION_VAULT_EJECT_ITEM,
                    LevelEvent.PARTICLES_TRIAL_SPAWNER_DETECT_PLAYER, LevelEvent.PARTICLES_TRIAL_SPAWNER_DETECT_PLAYER_OMINOUS -> c("sculk", "generated.activity", "Trial / vault activity", 0);
            default -> c("natural", "world.effect", "World effect " + code, 0);
        };
        String blockId = "";
        if (code == LevelEvent.PARTICLES_DESTROY_BLOCK) blockId = SoundCatalog.blockId(Block.stateById(packet.getData()).getBlock());
        else {
            BlockState state = loadedState(packet.getPos());
            if (state != null && !state.isAir()) blockId = SoundCatalog.blockId(state.getBlock());
        }
        emit(Route.LEVEL_EVENT, "", null, packet.isGlobalEvent() ? null : Vec3.atCenterOf(packet.getPos()), null, -1, blockId,
                action, 0, 0, 0, code, packet.getData(), packet.isGlobalEvent());
    }
    public static void mining(ClientboundBlockDestructionPacket packet) {
        if (!active()) return;
        if (packet.getProgress() < 0 || packet.getProgress() > 9) { mining.remove(packet.getId()); return; }
        MiningState previous = mining.put(packet.getId(), new MiningState(packet.getPos().immutable(), packet.getProgress()));
        if (mining.size() > 512) mining.clear();
        if (previous != null && previous.pos.equals(packet.getPos()) && previous.progress <= packet.getProgress()) return;
        BlockState state = loadedState(packet.getPos());
        emit(Route.MINING, "", null, Vec3.atCenterOf(packet.getPos()), entity(packet.getId()), packet.getId(),
                state == null ? "" : SoundCatalog.blockId(state.getBlock()), c("building", "building.hit", "Block breaking", 10),
                0, 0, 0, packet.getProgress(), 0, false);
    }
    public static void entityEvent(ClientboundEntityEventPacket packet) {
        if (!active()) return;
        Entity entity = packet.getEntity(Minecraft.getInstance().level);
        int id = ((SoundEntityEventAccessor) packet).donut$soundEntityId();
        SoundCatalog.Classification action = switch (packet.getEventId()) {
            case EntityEvent.DEATH -> c("combat", "combat.death", "Entity died", 25);
            case EntityEvent.PROTECTED_FROM_DEATH -> c("combat", "combat.totem", "Totem activated", 50);
            case EntityEvent.USE_ITEM_COMPLETE -> c("farming", "item.complete", "Item use completed", 25);
            case EntityEvent.FIREWORKS_EXPLODE -> c("explosions", "firework.explode", "Firework exploded", 15);
            default -> c("custom", "entity.status", "Entity status " + packet.getEventId(), 0);
        };
        emit(Route.ENTITY_EVENT, "", null, entity == null ? null : entity.position(), entity, id, "", action,
                0, 0, 0, packet.getEventId(), 0, false);
    }
    public static void damage(ClientboundDamageEventPacket packet) {
        if (!active()) return;
        Entity victim = entity(packet.entityId());
        Entity cause = entity(packet.sourceCauseId());
        if (cause != null && cause == Minecraft.getInstance().player && victim != null) {
            // An outgoing attack attempt alone is insufficient. This server event confirms its cause.
            LocalActionTracker.record(victim.position(), "combat");
            LocalActionTracker.record(cause.position(), "combat");
        }
        // The victim's identity is not the attacker's. Only the explicit cause is eligible for attribution.
        emit(Route.DAMAGE, "", null, victim == null ? packet.sourcePosition().orElse(null) : victim.position(), cause,
                packet.sourceCauseId(), "", c("combat", "combat.action", "Damage event", 35), 0, 0, 0, packet.entityId(), packet.sourceDirectId(), false);
    }
    public static void explosion(ClientboundExplodePacket packet) {
        if (!active()) return;
        emit(Route.EXPLOSION, SoundCatalog.id(packet.explosionSound().value()), null, packet.center(), null, -1, "",
                c("explosions", "explosion", "Explosion", 55), 0, 0, 0, 0, packet.blockCount(), false);
    }
    public static void stop(ClientboundStopSoundPacket packet) {
        if (!active()) return;
        emit(Route.STOP_SOUND, packet.getName() == null ? "" : packet.getName().toString(), packet.getSource(), null, null, -1, "",
                c("custom", "sound.stop", "Sound stopped", 0), 0, 0, 0, 0, 0, false);
    }
    private static void emit(Route route, String sound, SoundSource source, Vec3 pos, Entity entity, int entityId,
                             String blockId, SoundCatalog.Classification classification, float volume, float pitch, long seed,
                             int eventCode, int data, boolean global) {
        if (!active() || excludedDepth > 0) return;
        var c = classification == null ? SoundCatalog.classify(sound) : classification;
        Actor actor = entity == null ? Actor.UNKNOWN : entity == Minecraft.getInstance().player ? Actor.SELF
                : entity instanceof Player ? Actor.PLAYER : Actor.NON_PLAYER;
        long now = System.nanoTime();
        double x = pos == null ? Double.NaN : pos.x, y = pos == null ? Double.NaN : pos.y, z = pos == null ? Double.NaN : pos.z;
        boolean probableSelf = actor != Actor.PLAYER && actor != Actor.SELF && pos != null && LocalActionTracker.matches(x, y, z, c.category(), now);
        SoundDetectorController.observe(new SoundObservation(now, route, sound, c.category(), c.actionKey(), c.label(), blockId,
                x, y, z, entityId, entity == null ? "" : entity.getUUID().toString(), entity instanceof Player ? entity.getName().getString() : "",
                actor, volume, pitch, seed, eventCode, data, probableSelf, c.score(), global)
                .withSoundSource(source == null ? "" : source.getName()));
    }
    private static Entity entity(int id) { return Minecraft.getInstance().level == null || id < 0 ? null : Minecraft.getInstance().level.getEntity(id); }
    private static BlockState loadedState(BlockPos pos) {
        var level = Minecraft.getInstance().level;
        return level != null && level.hasChunkAt(pos) ? level.getBlockState(pos) : null;
    }
    private static SoundCatalog.Classification c(String category, String action, String label, int score) { return SoundCatalog.c(category, action, label, score); }
    public static String interactionCategory(Block block) {
        if (block instanceof ChestBlock || block instanceof EnderChestBlock || block instanceof ShulkerBoxBlock || block instanceof BarrelBlock
                || block instanceof ChiseledBookShelfBlock || block instanceof ShelfBlock || block instanceof DecoratedPotBlock) return "containers";
        if (block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock || block instanceof ButtonBlock || block instanceof LeverBlock) return "switches";
        if (block instanceof NoteBlock || block instanceof BellBlock || block instanceof JukeboxBlock) return "music";
        return "workstations";
    }
}
