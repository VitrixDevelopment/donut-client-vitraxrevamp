package dev.donut.client.blockesp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Nearest-emerald search: finds the closest predicted deepslate emerald without generating every chunk in range.
 *
 * <p>Emerald only generates in mountain-type biomes, so biome sampling on a coarse grid is cheap and narrows tens of
 * thousands of chunks down to a few candidates; only the chunks around the closest candidates are generated. The
 * algorithm is deliberately free of game classes so it can be tested: biome and ore access are supplied by the
 * caller through {@link Biomes} and {@link Chunks}.
 */
public final class NearestEmeraldSearch {
    /** Biome grid step in blocks. Emerald biomes are hundreds of blocks wide, so 32 blocks cannot miss one. */
    public static final int GRID = 32;
    /** Extra rings beyond the first ring that produced a candidate. */
    public static final int EXTRA_RINGS = 3;
    /** Candidates whose 3x3 chunk neighbourhood is generated. */
    public static final int CANDIDATES = 6;
    public static final int MIN_RANGE = 256;
    public static final int MAX_RANGE = 1500;

    /** Mountain biomes that can hold emerald ore. */
    public static final Set<String> EMERALD_BIOMES = Set.of("jagged_peaks", "frozen_peaks", "stony_peaks",
            "snowy_slopes", "grove", "meadow", "windswept_hills", "windswept_gravelly_hills", "windswept_forest",
            "cherry_grove");

    public interface Biomes {
        /** True if any sampled height at this block column is an emerald biome. */
        boolean emerald(int blockX, int blockZ);
    }

    public interface Chunks {
        /** Predicted deepslate emerald ore inside one chunk. Called off the client thread. */
        List<net.minecraft.core.BlockPos> deepslateEmerald(int chunkX, int chunkZ);
    }

    private NearestEmeraldSearch() { }

    public static int clampRange(int blocks) {
        return Math.clamp(blocks, MIN_RANGE, MAX_RANGE);
    }

    /** Candidate block columns in the search rings, nearest ring first. */
    public static List<long[]> candidates(Biomes biomes, int fromX, int fromZ, int range, Set<String> unmatchedAreas) {
        int limit = clampRange(range);
        List<long[]> found = new ArrayList<>();
        int firstRing = -1;
        for (int ring = 0; ring <= limit; ring += GRID) {
            if (firstRing >= 0 && ring > firstRing + EXTRA_RINGS * GRID) break;
            for (int dx = -ring; dx <= ring; dx += GRID) {
                for (int dz = -ring; dz <= ring; dz += GRID) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = fromX + dx;
                    int z = fromZ + dz;
                    if (unmatchedAreas.contains(AreaVerdicts.key(false, x >> 4, z >> 4))) continue;
                    if (!biomes.emerald(x, z)) continue;
                    found.add(new long[] {x, z, (long) dx * dx + (long) dz * dz});
                    if (firstRing < 0) firstRing = ring;
                }
            }
        }
        found.sort(Comparator.comparingLong(entry -> entry[2]));
        return found;
    }

    /** Chunk keys around the closest candidates, nearest candidate first, without duplicates. */
    public static List<Long> candidatesNear(List<long[]> candidates) {
        Set<Long> chunks = new LinkedHashSet<>();
        int used = Math.min(CANDIDATES, candidates.size());
        for (int i = 0; i < used; i++) {
            int centerX = (int) (candidates.get(i)[0] >> 4);
            int centerZ = (int) (candidates.get(i)[1] >> 4);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    chunks.add(net.minecraft.world.level.ChunkPos.asLong(centerX + dx, centerZ + dz));
                }
            }
        }
        return new ArrayList<>(chunks);
    }

    /**
     * Full search. Returns predicted deepslate emerald positions, nearest first, skipping unmatched areas and
     * positions the caller has already seen removed.
     */
    public static List<net.minecraft.core.BlockPos> search(Biomes biomes, Chunks chunks, int fromX, int fromY,
            int fromZ, int range, Set<String> unmatchedAreas, Set<Long> gone, Set<Long> deepslate) {
        List<long[]> candidates = candidates(biomes, fromX, fromZ, range, unmatchedAreas);
        if (candidates.isEmpty()) return List.of();
        var origin = new net.minecraft.core.BlockPos(fromX, fromY, fromZ);
        List<net.minecraft.core.BlockPos> all = new ArrayList<>();
        for (long key : candidatesNear(candidates)) {
            int chunkX = net.minecraft.world.level.ChunkPos.getX(key);
            int chunkZ = net.minecraft.world.level.ChunkPos.getZ(key);
            if (unmatchedAreas.contains(AreaVerdicts.key(false, chunkX, chunkZ))) continue;
            for (var pos : chunks.deepslateEmerald(chunkX, chunkZ)) {
                long packed = pos.asLong();
                if (gone.contains(packed)) continue;
                all.add(pos);
                if (deepslate != null) deepslate.add(packed);
            }
        }
        all.sort(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
        return all;
    }
}
