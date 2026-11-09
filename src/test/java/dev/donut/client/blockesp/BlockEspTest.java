package dev.donut.client.blockesp;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockEspTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void onlyTheExactDeepslateVariantsMatch() {
        assertEquals(OreMarker.Kind.EMERALD, OreMarker.kind(Blocks.DEEPSLATE_EMERALD_ORE.defaultBlockState()));
        assertEquals(OreMarker.Kind.COAL, OreMarker.kind(Blocks.DEEPSLATE_COAL_ORE.defaultBlockState()));
        for (var block : new net.minecraft.world.level.block.Block[] {Blocks.EMERALD_ORE, Blocks.COAL_ORE,
                Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.DEEPSLATE, Blocks.STONE, Blocks.AIR})
            assertNull(OreMarker.kind(block.defaultBlockState()));
    }

    @Test void nearestFirstSchedulingHandlesNegativeCoordinatesWithoutDuplicates() {
        Set<Long> cached = new HashSet<>();
        assertEquals(new ChunkPos(-123, -456), BlockEspController.nextChunk(-123, -456, 2, cached));
        int lastDistance = -1;
        for (int i = 0; i < 25; i++) {
            var next = BlockEspController.nextChunk(-123, -456, 2, cached);
            assertNotNull(next);
            int dx = next.x + 123, dz = next.z + 456, distance = dx * dx + dz * dz;
            assertTrue(distance >= lastDistance);
            assertTrue(cached.add(next.toLong()));
            lastDistance = distance;
        }
        assertNull(BlockEspController.nextChunk(-123, -456, 2, cached));
    }
}
