package dev.donut.client.blockesp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Owns a {@link SeedTerrain} simulation for one seed.
 *
 * <p>{@code SeedTerrain} rebuilds each chunk from the seed with the game's own generator - noise terrain, surface
 * rules, carved caves and canyons, mineshafts, then the features the generator runs before and including coal and
 * emerald - and reports the ore the server generated. That is the same result Donut's full vanilla world gives, but
 * without the vanilla server's world directories, ticket system, chunk scheduler or generation threads, and it skips
 * the loaded-chunk round trip that limits the full world to the prediction radius.
 *
 * <p>All methods must be called from the single worker thread that owns the instance; the controller does that.
 */
public final class SeedTerrainEngine implements AutoCloseable {
    public static final int MIN_Y = -64;
    public static final int HEIGHT = 384;

    private final long seed;
    private final SeedTerrain terrain;
    private boolean closed;

    /** Vanilla registries, shared by every simulation of the session; building them is the slow part. */
    private static volatile HolderLookup.Provider cachedRegistries;

    private static HolderLookup.Provider registries() {
        var value = cachedRegistries;
        if (value == null) {
            value = net.minecraft.data.registries.VanillaRegistries.createLookup();
            cachedRegistries = value;
        }
        return value;
    }

    public SeedTerrainEngine(long seed) {
        this(seed, registries());
    }

    SeedTerrainEngine(long seed, HolderLookup.Provider lookup) {
        this.seed = seed;
        var preset = lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        var source = MultiNoiseBiomeSource.createFromPreset(preset);
        RandomState randomState = RandomState.create(lookup, NoiseGeneratorSettings.OVERWORLD, seed);
        var settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var placed = lookup.lookupOrThrow(Registries.PLACED_FEATURE);
        // The simulation replays every feature up to and including the latest of these, so coal (upper and lower) and
        // emerald are reproduced with the same random sequence the server used.
        PlacedFeature coalUpper = placed.getOrThrow(placedKey("ore_coal_upper")).value();
        PlacedFeature coalLower = placed.getOrThrow(placedKey("ore_coal_lower")).value();
        PlacedFeature emerald = placed.getOrThrow(placedKey("ore_emerald")).value();
        terrain = new SeedTerrain(seed, MIN_Y, HEIGHT, source, randomState,
                new NoiseBasedChunkGenerator(source, settings), settings, lookup, null, coalUpper, coalLower, emerald);
    }

    private static ResourceKey<PlacedFeature> placedKey(String path) {
        return ResourceKey.create(Registries.PLACED_FEATURE, Identifier.withDefaultNamespace(path));
    }

    public long seed() { return seed; }

    /** Predicted deepslate coal and deepslate emerald markers inside one chunk. */
    public List<OreMarker> predict(int chunkX, int chunkZ) {
        checkOpen();
        var result = new ArrayList<OreMarker>();
        for (var ore : terrain.predict(chunkX, chunkZ, ORES)) {
            var kind = ore.block() == Blocks.DEEPSLATE_COAL_ORE ? OreMarker.Kind.COAL
                    : ore.block() == Blocks.DEEPSLATE_EMERALD_ORE ? OreMarker.Kind.EMERALD : null;
            // The module only ever draws the deepslate variants, and the full vanilla world filtered them too.
            if (kind != null) result.add(new OreMarker(ore.pos(), kind));
        }
        return List.copyOf(result);
    }

    /** Predicted deepslate emerald positions inside one chunk, for the nearest-emerald search. */
    public List<net.minecraft.core.BlockPos> deepslateEmerald(int chunkX, int chunkZ) {
        checkOpen();
        var result = new ArrayList<net.minecraft.core.BlockPos>();
        for (var ore : terrain.predict(chunkX, chunkZ, EMERALD)) {
            if (ore.block() == Blocks.DEEPSLATE_EMERALD_ORE) result.add(ore.pos());
        }
        return result;
    }

    /** Simulated ocean-floor height per column, index {@code x * 16 + z}, for area terrain checks. */
    public int[] floor(int chunkX, int chunkZ) {
        checkOpen();
        return terrain.floor(chunkX, chunkZ);
    }

    /** Features the stand-in world could not run, with counts; empty means a clean simulation. */
    public String skippedSummary() {
        var skipped = terrain.skipped;
        if (skipped.isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (var entry : skipped.entrySet()) {
            if (shown++ == 3) break;
            text.append(text.isEmpty() ? "" : ", ").append(entry.getKey()).append(" x").append(entry.getValue());
        }
        return text.toString();
    }

    public int structurePieces() { return terrain.structurePieces; }

    private static final Set<net.minecraft.world.level.block.Block> ORES = Set.of(Blocks.COAL_ORE,
            Blocks.DEEPSLATE_COAL_ORE, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
    private static final Set<net.minecraft.world.level.block.Block> EMERALD = Set.of(Blocks.EMERALD_ORE,
            Blocks.DEEPSLATE_EMERALD_ORE);

    private void checkOpen() {
        if (closed) throw new IllegalStateException("SeedTerrainEngine is closed");
    }

    @Override
    public void close() {
        closed = true;
    }
}
