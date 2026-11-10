package dev.donut.client.blockesp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Area verdicts: thresholds, negative coordinates, persistence and damaged files. */
class AreaVerdictsTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @TempDir Path directory;

    @Test void areasAreEightChunksAndFloorTowardsNegativeInfinity() {
        assertEquals("o0,0", AreaVerdicts.key(false, 0, 0));
        assertEquals("o0,0", AreaVerdicts.key(false, 7, 7));
        assertEquals("o1,1", AreaVerdicts.key(false, 8, 8));
        assertEquals("o-1,-1", AreaVerdicts.key(false, -1, -1));
        assertEquals("o-1,-1", AreaVerdicts.key(false, -8, -8));
        assertEquals("o-2,-2", AreaVerdicts.key(false, -9, -9));
        assertEquals("n0,0", AreaVerdicts.key(true, 3, 3));
        assertEquals(64, AreaVerdicts.center(0));
        assertEquals(-64, AreaVerdicts.center(-1));
        assertEquals(128, AreaVerdicts.AREA_BLOCKS);
        // A packed chunk key maps to the same area as its coordinates.
        assertEquals(AreaVerdicts.key(false, -20, 12),
                AreaVerdicts.key(false, net.minecraft.world.level.ChunkPos.asLong(-20, 12)));
    }

    @Test void verdictsNeedRealEvidenceInBothDirections() {
        assertEquals(AreaVerdicts.Status.UNVERIFIED, AreaVerdicts.statusOf(0, 0));
        assertEquals(AreaVerdicts.Status.UNVERIFIED, AreaVerdicts.statusOf(2, 0), "Two hits are too few");
        assertEquals(AreaVerdicts.Status.CONFIRMED, AreaVerdicts.statusOf(3, 0));
        assertEquals(AreaVerdicts.Status.CONFIRMED, AreaVerdicts.statusOf(4, 3), "Hits win while they are half the evidence");
        assertEquals(AreaVerdicts.Status.UNVERIFIED, AreaVerdicts.statusOf(3, 4), "Mixed evidence stays unverified");
        assertEquals(AreaVerdicts.Status.UNVERIFIED, AreaVerdicts.statusOf(0, 3), "Three misses are too few");
        assertEquals(AreaVerdicts.Status.UNMATCHED, AreaVerdicts.statusOf(0, 4));
        assertEquals(AreaVerdicts.Status.UNMATCHED, AreaVerdicts.statusOf(0, 12), "Misses only");
        assertEquals(AreaVerdicts.Status.UNVERIFIED, AreaVerdicts.statusOf(2, 8), "A fifth of the ores matching is not a rejection");
        // Once confirmed, a couple of border misses cannot flip a large area back.
        assertEquals(AreaVerdicts.Status.CONFIRMED, AreaVerdicts.statusOf(20, 8));
    }

    @Test void evidenceAccumulatesPerAreaWithWeights() {
        var verdicts = new AreaVerdicts();
        String area = AreaVerdicts.key(false, -17, 33);
        assertEquals(AreaVerdicts.Status.UNVERIFIED, verdicts.status(area));
        verdicts.record(area, true, 1);
        assertEquals(AreaVerdicts.Status.UNVERIFIED, verdicts.status(area), "One hit is not a verdict");
        verdicts.record(area, true, 2);
        assertEquals(AreaVerdicts.Status.CONFIRMED, verdicts.status(area), "A reached vein counts double");
        assertEquals(3, verdicts.hits(area));
        assertEquals(0, verdicts.misses(area));
        assertEquals(1, verdicts.count(AreaVerdicts.Status.CONFIRMED), "one area holds that verdict");
        assertEquals(1, verdicts.areas().size());
        assertTrue(verdicts.isDirty());
    }

    @Test void verdictsSurviveRestartAndDamagedLinesAreIgnored() throws Exception {
        Path file = directory.resolve("donut/blockesp-areas.txt");
        var original = new AreaVerdicts();
        original.record("o0,0", true, 1);
        original.record("o0,0", true, 1);
        original.record("o0,0", true, 1);
        original.record("o-2,5", false, 1);
        original.record("o-2,5", false, 1);
        original.record("o-2,5", false, 1);
        original.record("o-2,5", false, 1);
        original.record("o3,-3", false, 1);
        assertTrue(original.isDirty());
        original.save(file);
        assertFalse(original.isDirty(), "Saving clears the dirty flag");
        assertTrue(Files.exists(file));

        var loaded = new AreaVerdicts();
        loaded.load(file);
        assertEquals(AreaVerdicts.Status.CONFIRMED, loaded.status("o0,0"));
        assertEquals(AreaVerdicts.Status.UNMATCHED, loaded.status("o-2,5"));
        assertEquals(AreaVerdicts.Status.UNVERIFIED, loaded.status("o3,-3"));
        assertEquals(3, loaded.hits("o0,0"));
        assertEquals(4, loaded.misses("o-2,5"));
        assertFalse(loaded.isDirty(), "Loading is not a modification");

        Files.writeString(file, "o1,1;1;0\ngarbage\no2,2;a;b\no3,3;-1;0\no4,4;2\n\no5,5; 4 ; 1 \n",
                StandardCharsets.UTF_8);
        var damaged = new AreaVerdicts();
        damaged.load(file);
        assertEquals(2, damaged.areas().size());
        assertEquals(AreaVerdicts.Status.UNVERIFIED, damaged.status("o1,1"));
        assertEquals(AreaVerdicts.Status.CONFIRMED, damaged.status("o5,5"));
        assertEquals(AreaVerdicts.Status.UNVERIFIED, damaged.status("missing"), "An unknown area is unverified, never null");
    }

    @Test void missingFileAndClearAreHarmless() {
        var verdicts = new AreaVerdicts();
        verdicts.load(directory.resolve("nothing-here.txt"));
        assertEquals(0, verdicts.areas().size());
        verdicts.record("o0,0", true, 1);
        verdicts.clear();
        assertTrue(verdicts.areas().isEmpty());
        assertEquals("no areas checked yet", verdicts.summary());
    }

    @Test void unmatchedAreasAreExposedForTheEmeraldSearch() {
        var verdicts = new AreaVerdicts();
        for (int i = 0; i < AreaVerdicts.REJECT_MISSES; i++) verdicts.record("o-1,2", false, 1);
        for (int i = 0; i < AreaVerdicts.CONFIRM_HITS; i++) verdicts.record("o0,0", true, 1);
        assertEquals(List.of("o-1,2"), List.copyOf(verdicts.keysWith(AreaVerdicts.Status.UNMATCHED)));
        assertEquals(1, verdicts.keysWith(AreaVerdicts.Status.CONFIRMED).size());
        assertTrue(verdicts.summary().contains("1 confirmed"));
        assertTrue(verdicts.summary().contains("1 unmatched"));
    }
}
