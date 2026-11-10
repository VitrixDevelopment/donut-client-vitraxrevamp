package dev.donut.client.blockesp;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Nearest-emerald search: ring walk, range clamp, candidate selection and area skipping. */
class NearestEmeraldSearchTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** A fake biome field: emerald north of the origin, everything else plains. */
    private static NearestEmeraldSearch.Biomes biomes(Set<Long> emeraldColumns) {
        return (x, z) -> emeraldColumns.contains(((long) x << 32) ^ (z & 0xffffffffL));
    }

    private static long column(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    @Test void rangeIsClampedToTheSupportedWindow() {
        assertEquals(256, NearestEmeraldSearch.clampRange(0));
        assertEquals(256, NearestEmeraldSearch.clampRange(-400));
        assertEquals(1500, NearestEmeraldSearch.clampRange(9000));
        assertEquals(704, NearestEmeraldSearch.clampRange(704));
    }

    @Test void candidatesStopSoonAfterTheNearestRingAndSkipRejectedAreas() {
        var columns = new java.util.HashSet<Long>();
        columns.add(column(0, 0));
        columns.add(column(64, 0));
        columns.add(column(1400, 1400));
        var found = NearestEmeraldSearch.candidates(biomes(columns), 0, 0, 1500, Set.of());
        assertEquals(2, found.size(), "Rings stop three steps after the first ring that had a candidate");
        assertEquals(0, found.get(0)[2], "Nearest candidate first");
        assertEquals(64L * 64L, found.get(1)[2]);

        var near = new java.util.HashSet<Long>();
        near.add(column(0, 0));
        near.add(column(64, 64));
        assertEquals(2, NearestEmeraldSearch.candidates(biomes(near), 0, 0, 1500, Set.of()).size());
        String rejected = AreaVerdicts.key(false, 2, 2);
        assertTrue(NearestEmeraldSearch.candidates(biomes(near), 0, 0, 1500, Set.of(rejected)).isEmpty(),
                "Candidates inside an area the server placed differently are skipped");
    }

    @Test void searchesOnlyAroundTheClosestCandidatesAndOrdersByDistance() {
        var columns = new java.util.HashSet<Long>();
        // Emerald columns every 32 blocks along z = 0; 0 must be one of them because the search samples
        // fromX + k * GRID.
        for (int x = -480; x <= 480; x += 32) columns.add(column(x, 0));
        var biomeField = biomes(columns);
        var candidates = NearestEmeraldSearch.candidates(biomeField, 0, 0, 1500, Set.of());
        assertTrue(candidates.size() >= NearestEmeraldSearch.CANDIDATES, "expected at least a candidate per close ring, got " + candidates.size());
        var chunks = NearestEmeraldSearch.candidatesNear(candidates);
        assertTrue(chunks.size() <= NearestEmeraldSearch.CANDIDATES * 9, "chunk count " + chunks.size() + " exceeds 3x3 per candidate");
        assertTrue(chunks.size() >= 9, "chunk count " + chunks.size());
        assertEquals(chunks.size(), new java.util.HashSet<>(chunks).size(), "duplicate chunks in " + chunks);

        Map<Long, List<BlockPos>> ore = new HashMap<>();
        for (long key : chunks) ore.put(key, List.of(new BlockPos(ChunkPos.getX(key) * 16 + 8, -20, ChunkPos.getZ(key) * 16 + 8)));
        var deepslate = new java.util.HashSet<Long>();
        var found = NearestEmeraldSearch.search(biomeField,
                (chunkX, chunkZ) -> ore.getOrDefault(ChunkPos.asLong(chunkX, chunkZ), List.of()),
                0, 64, 0, 1500, Set.of(), Set.of(), deepslate);
        assertEquals(chunks.size(), found.size(), "found " + found);
        assertEquals(chunks.size(), deepslate.size(), "deepslate set " + deepslate);
        for (int i = 1; i < found.size(); i++)
            assertTrue(found.get(i - 1).distSqr(BlockPos.ZERO.above(64)) <= found.get(i).distSqr(BlockPos.ZERO.above(64)),
                    "Results must be nearest first: " + found.get(i - 1) + " then " + found.get(i));
        assertTrue(found.stream().noneMatch(pos -> pos.distSqr(BlockPos.ZERO.above(64)) > 500 * 500),
                "Only chunks around the closest candidates are generated");
    }

    @Test void minedPositionsAndRejectedAreasAreDropped() {
        var columns = Set.of(column(0, 0));
        var biomeField = biomes(columns);
        BlockPos known = new BlockPos(4, -18, 6);
        BlockPos mined = new BlockPos(9, -18, 6);
        var gone = Set.of(mined.asLong());
        var found = NearestEmeraldSearch.search(biomeField,
                (chunkX, chunkZ) -> chunkX == 0 && chunkZ == 0 ? List.of(known, mined) : List.of(),
                0, 64, 0, 256, Set.of(), gone, null);
        assertEquals(1, found.size(), "A predicted emerald already seen as air is skipped");
        assertEquals(known, found.getFirst());

        var rejected = NearestEmeraldSearch.search(biomeField,
                (chunkX, chunkZ) -> List.of(known), 0, 64, 0, 256, Set.of(AreaVerdicts.key(false, 0, 0)), Set.of(), null);
        assertTrue(rejected.isEmpty(), "Rejected areas are never searched");
    }

    @Test void emptyFieldFindsNothing() {
        var found = NearestEmeraldSearch.search(biomes(Set.of()), (x, z) -> List.of(), 0, 64, 0, 512, Set.of(), Set.of(), null);
        assertTrue(found.isEmpty());
    }
}
