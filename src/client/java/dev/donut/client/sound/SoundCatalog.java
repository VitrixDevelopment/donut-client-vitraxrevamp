package dev.donut.client.sound;

import com.google.gson.GsonBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Exact registered identifiers only. Material sounds never identify one of their candidate blocks. */
public final class SoundCatalog {
    public record Classification(String category, String actionKey, String label, int score) { }
    public record Coverage(String blockId, String implementation, int stateCount,
                           Map<String, List<String>> materialActions, String specialActionStatus) { }
    private static final Classification UNKNOWN = new Classification("custom", "unknown", "Unclassified sound", 0);
    private static volatile Map<String, Classification> sounds = Map.of();
    private static volatile Map<String, Set<String>> candidates = Map.of();
    private static volatile List<Coverage> coverage = List.of();
    private SoundCatalog() { }

    /** Call once after registry bootstrap, and after resource reload if registry content changed. */
    public static synchronized void rebuild() {
        Map<String, Classification> nextSounds = new HashMap<>();
        Map<String, Set<String>> nextCandidates = new HashMap<>();
        List<Coverage> nextCoverage = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
            Map<String, Set<String>> actions = new TreeMap<>();
            Set<SoundType> types = Collections.newSetFromMap(new IdentityHashMap<>());
            for (BlockState state : block.getStateDefinition().getPossibleStates()) types.add(state.getSoundType());
            for (SoundType type : types) {
                material(nextSounds, nextCandidates, actions, blockId, "break", type.getBreakSound(), 25);
                material(nextSounds, nextCandidates, actions, blockId, "place", type.getPlaceSound(), 25);
                material(nextSounds, nextCandidates, actions, blockId, "hit", type.getHitSound(), 10);
                material(nextSounds, nextCandidates, actions, blockId, "step", type.getStepSound(), 10);
                material(nextSounds, nextCandidates, actions, blockId, "fall", type.getFallSound(), 10);
            }
            Map<String, List<String>> stableActions = new TreeMap<>();
            actions.forEach((key, value) -> stableActions.put(key, value.stream().sorted().toList()));
            String status = block instanceof ChestBlock || block instanceof EnderChestBlock || block instanceof ShulkerBoxBlock
                    || block instanceof PistonBaseBlock || block instanceof NoteBlock || block instanceof BellBlock
                    ? "Explicit block-event decoder; live-server delivery unverified"
                    : "Material actions indexed; special/autonomous/ambient routes require individual review";
            nextCoverage.add(new Coverage(blockId, block.getClass().getName(), block.getStateDefinition().getPossibleStates().size(),
                    Map.copyOf(stableActions), block.defaultBlockState().isAir() ? "Air: no block interaction sound inferred" : status));
        }
        // Field names refer to Mojang's known built-in declarations, never arbitrary/custom ID substrings.
        for (var field : SoundEvents.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            try {
                Object value = field.get(null);
                SoundEvent event = value instanceof SoundEvent sound ? sound
                        : value instanceof Holder<?> holder && holder.value() instanceof SoundEvent sound ? sound : null;
                if (event == null) continue;
                Classification special = builtin(field.getName());
                // Ambient-family field groups also contain material break/step/etc constants.
                // Preserve their indexed action instead of turning genuine mining into ambience.
                if (special != null && !(special.score() == 0 && nextSounds.containsKey(id(event)))) nextSounds.put(id(event), special);
            } catch (IllegalAccessException ignored) { /* Not a registered accessible built-in declaration. */ }
        }
        Map<String, Set<String>> stableCandidates = new HashMap<>();
        nextCandidates.forEach((key, value) -> stableCandidates.put(key, Set.copyOf(value)));
        sounds = Map.copyOf(nextSounds);
        candidates = Map.copyOf(stableCandidates);
        coverage = nextCoverage.stream().sorted(Comparator.comparing(Coverage::blockId)).toList();
    }

    private static void material(Map<String, Classification> target, Map<String, Set<String>> reverse,
                                 Map<String, Set<String>> actions, String block, String action, SoundEvent event, int score) {
        String id = id(event);
        boolean movement = action.equals("step") || action.equals("fall");
        target.putIfAbsent(id, new Classification(movement ? "movement" : "building", "building." + action,
                humanize(id.substring(id.indexOf(':') + 1)), score));
        reverse.computeIfAbsent(id, key -> new HashSet<>()).add(block);
        actions.computeIfAbsent(action, key -> new HashSet<>()).add(id);
    }

    private static Classification builtin(String field) {
        if (Set.of("CHEST_OPEN", "ENDER_CHEST_OPEN", "BARREL_OPEN", "SHULKER_BOX_OPEN", "COPPER_CHEST_OPEN",
                "COPPER_CHEST_WEATHERED_OPEN", "COPPER_CHEST_OXIDIZED_OPEN").contains(field))
            return c("containers", "container.open", "Container opened", 50);
        if (Set.of("CHEST_CLOSE", "ENDER_CHEST_CLOSE", "BARREL_CLOSE", "SHULKER_BOX_CLOSE", "COPPER_CHEST_CLOSE",
                "COPPER_CHEST_WEATHERED_CLOSE", "COPPER_CHEST_OXIDIZED_CLOSE").contains(field))
            return c("containers", "container.close", "Container closed", 50);
        if (field.equals("PISTON_EXTEND")) return c("machinery", "piston.extend", "Piston extended", 15);
        if (field.equals("PISTON_CONTRACT")) return c("machinery", "piston.retract", "Piston retracted", 15);
        if (field.startsWith("NOTE_BLOCK_")) return c("music", "note.play", "Note block played", 15);
        if (field.equals("BELL_BLOCK") || field.equals("BELL_RESONATE")) return c("music", "bell.ring", "Bell rang", 15);
        if (Set.of("CRAFTER_CRAFT", "CRAFTER_FAIL").contains(field)) return c("machinery", "crafter." + (field.endsWith("FAIL") ? "fail" : "craft"), humanize(field), 15);
        if (Set.of("DISPENSER_DISPENSE", "DISPENSER_FAIL", "DISPENSER_LAUNCH").contains(field))
            return c("machinery", field.endsWith("FAIL") ? "dispenser.fail" : "dispenser.dispense", humanize(field), 15);
        if (Set.of("ANVIL_USE", "GRINDSTONE_USE", "SMITHING_TABLE_USE", "STONECUTTER_TAKE_RESULT", "LOOM_TAKE_RESULT",
                "UI_CARTOGRAPHY_TABLE_TAKE_RESULT", "ENCHANTMENT_TABLE_USE").contains(field))
            return c("workstations", "workstation." + field.toLowerCase(Locale.ROOT), humanize(field), 50);
        if (Set.of("ANVIL_LAND", "ANVIL_BREAK").contains(field)) return c("building", "anvil.land", humanize(field), 25);
        if (field.startsWith("CHISELED_BOOKSHELF_") && (field.contains("INSERT") || field.contains("PICKUP"))
                || field.startsWith("SHELF_") && Set.of("SHELF_PLACE_ITEM", "SHELF_TAKE_ITEM", "SHELF_SINGLE_SWAP", "SHELF_MULTI_SWAP").contains(field))
            return c("containers", "shelf.interact", humanize(field), 50);
        if (Set.of("DECORATED_POT_INSERT", "DECORATED_POT_INSERT_FAIL").contains(field)) return c("containers", "pot.interact", humanize(field), 50);
        if ((field.contains("DOOR_") || field.contains("TRAPDOOR_") || field.contains("FENCE_GATE_"))
                && (field.endsWith("OPEN") || field.endsWith("CLOSE")))
            return c("switches", field.endsWith("OPEN") ? "door.open" : "door.close", humanize(field), 30);
        if (field.equals("LEVER_CLICK") || field.endsWith("BUTTON_CLICK_ON") || field.endsWith("BUTTON_CLICK_OFF")
                || field.endsWith("PRESSURE_PLATE_CLICK_ON") || field.endsWith("PRESSURE_PLATE_CLICK_OFF")
                || field.startsWith("TRIPWIRE_") && !field.endsWith("DETACH"))
            return c("switches", "switch.toggle", humanize(field), 25);
        if (Set.of("GENERIC_EXPLODE", "RESPAWN_ANCHOR_DEPLETE").contains(field)) return c("explosions", "explosion", humanize(field), 55);
        if (field.startsWith("PLAYER_ATTACK_") || field.equals("PLAYER_HURT") || field.equals("PLAYER_DEATH"))
            return c("combat", "combat.action", humanize(field), 35);
        if (Set.of("ARROW_SHOOT", "CROSSBOW_SHOOT", "SHIELD_BLOCK").contains(field)) return c("combat", "combat.action", humanize(field), 35);
        if (Set.of("GENERIC_EAT", "GENERIC_DRINK", "PLAYER_BURP").contains(field)) return c("farming", "item.consume", humanize(field), 25);
        if (field.equals("FISHING_BOBBER_THROW")) return c("farming", "fishing.cast", "Fishing line cast", 25);
        if (field.equals("FIREWORK_ROCKET_LAUNCH")) return c("transport", "firework.launch", "Firework launched", 25);
        if (field.startsWith("VILLAGER_WORK_") || field.equals("VILLAGER_AMBIENT") || field.startsWith("BEE_")
                || field.startsWith("FURNACE_") || field.startsWith("BLASTFURNACE_") || field.equals("SMOKER_SMOKE")
                || field.startsWith("AMBIENT_") || field.startsWith("WEATHER_") || field.startsWith("DRIED_GHAST_") && field.contains("AMBIENT"))
            return c("natural", "environment", humanize(field), 0);
        if (Set.of("BEEHIVE_SHEAR", "BOTTLE_FILL", "BOTTLE_EMPTY", "BUCKET_FILL", "BUCKET_EMPTY", "SHEEP_SHEAR").contains(field))
            return c("farming", "farming.interact", humanize(field), 30);
        if (field.startsWith("SCULK_SENSOR_") || field.startsWith("SCULK_SHRIEKER_") || field.startsWith("TRIAL_SPAWNER_") || field.startsWith("VAULT_"))
            return c("sculk", "generated.activity", humanize(field), 0);
        return null;
    }

    public static Classification classify(String soundId) { return sounds.getOrDefault(soundId, UNKNOWN); }
    public static Set<String> candidates(String soundId) { return candidates.getOrDefault(soundId, Set.of()); }
    public static List<Coverage> coverage() { return coverage; }
    public static String id(SoundEvent sound) { return sound.location().toString(); }
    public static String blockId(Block block) { return BuiltInRegistries.BLOCK.getKey(block).toString(); }
    public static Classification c(String category, String action, String label, int score) { return new Classification(category, action, label, score); }
    public static String humanize(String text) {
        String plain = text.replace('_', ' ').replace('.', ' ').toLowerCase(Locale.ROOT);
        return plain.isEmpty() ? "Unknown" : Character.toUpperCase(plain.charAt(0)) + plain.substring(1);
    }
    /** Explicit user action only; no per-packet filesystem writes. */
    public static void exportCoverage(Path destination) throws IOException {
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(destination, new GsonBuilder().setPrettyPrinting().create().toJson(Map.of(
                "gameVersion", "1.21.11", "blocks", coverage, "notes", "Material index is exhaustive for registered states; special routes and actual server delivery require fixture review.")), StandardCharsets.UTF_8);
    }
}
