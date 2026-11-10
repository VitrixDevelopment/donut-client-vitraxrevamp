package dev.donut.client.blockesp;

import java.nio.file.Path;
import java.util.List;

/**
 * A selectable ore prediction engine. Both engines reconstruct vanilla generation; they differ only in how.
 *
 * <p>Implementations belong to one worker thread and must be closed on it.
 */
public interface OrePredictorEngine extends AutoCloseable {
    /** Human name shown in the settings screen. */
    String name();

    /** Predicted deepslate coal and deepslate emerald markers inside one chunk. */
    List<OreMarker> predict(int chunkX, int chunkZ);

    /** Predicted deepslate emerald positions inside one chunk, for the nearest-emerald search. */
    List<net.minecraft.core.BlockPos> deepslateEmerald(int chunkX, int chunkZ);

    /** Releases the engine. Narrowed so callers do not have to handle a checked exception. */
    @Override
    void close();

    /**
     * Full vanilla world generation: the game's own chunk scheduler, world directories and tickets.
     * The first chunk of a new session is slow because the world has to be prepared.
     */
    static OrePredictorEngine vanillaWorld(long seed, Path scratchParent) throws Exception {
        return new VanillaEngine(new VanillaOreGenerator(seed, scratchParent));
    }

    /** Seed simulation: the same generator, replayed per chunk without a server or world storage. */
    static OrePredictorEngine seedTerrain(long seed) {
        return new SeedTerrainAdapter(new SeedTerrainEngine(seed));
    }

    /** Full vanilla world generation through {@link VanillaOreGenerator}. */
    final class VanillaEngine implements OrePredictorEngine {
        private final VanillaOreGenerator generator;

        private VanillaEngine(VanillaOreGenerator generator) {
            this.generator = generator;
        }

        @Override public String name() { return "Vanilla world"; }

        @Override public List<OreMarker> predict(int chunkX, int chunkZ) { return generator.predict(chunkX, chunkZ); }

        @Override public List<net.minecraft.core.BlockPos> deepslateEmerald(int chunkX, int chunkZ) {
            var result = new java.util.ArrayList<net.minecraft.core.BlockPos>();
            for (var marker : generator.predict(chunkX, chunkZ)) {
                if (marker.kind() == OreMarker.Kind.EMERALD) result.add(marker.pos());
            }
            return result;
        }

        @Override public void close() { generator.close(); }
    }

    /** Seed simulation through {@link SeedTerrainEngine}: no world storage, no server, no scheduler. */
    final class SeedTerrainAdapter implements OrePredictorEngine {
        private final SeedTerrainEngine simulation;

        private SeedTerrainAdapter(SeedTerrainEngine simulation) {
            this.simulation = simulation;
        }

        @Override public String name() { return "Seed simulation"; }

        @Override public List<OreMarker> predict(int chunkX, int chunkZ) { return simulation.predict(chunkX, chunkZ); }

        @Override public List<net.minecraft.core.BlockPos> deepslateEmerald(int chunkX, int chunkZ) {
            return simulation.deepslateEmerald(chunkX, chunkZ);
        }

        @Override public void close() { simulation.close(); }
    }
}
