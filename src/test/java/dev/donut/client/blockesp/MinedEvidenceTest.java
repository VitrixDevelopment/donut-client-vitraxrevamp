package dev.donut.client.blockesp;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Mined test bookkeeping: air only counts once, and controls keep caves from looking like mining. */
class MinedEvidenceTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void positionsRoundTripAndControlsSitTwoBlocksAway() {
        long packed = MinedEvidence.pack(-1234, -59, 987);
        assertEquals(-1234, MinedEvidence.x(packed));
        assertEquals(-59, MinedEvidence.y(packed));
        assertEquals(987, MinedEvidence.z(packed));
        assertEquals(net.minecraft.core.BlockPos.asLong(-1234, -59, 987), packed,
                "Packing must agree with BlockPos so markers map to evidence");
        long[] controls = MinedEvidence.controls(packed);
        assertEquals(MinedEvidence.CONTROL_SPOTS, controls.length);
        assertTrue(java.util.Arrays.stream(controls).noneMatch(control -> control == packed));
        for (long control : controls) assertEquals(-59, MinedEvidence.y(control), "Controls stay at the same height");
    }

    @Test void repeatedSamplesDoNotInflateCounts() {
        var evidence = new MinedEvidence();
        long packed = MinedEvidence.pack(0, -30, 0);
        assertTrue(evidence.observePredicted(packed, true));
        assertFalse(evidence.observePredicted(packed, true));
        assertTrue(evidence.observeControl(packed + 1, false));
        assertFalse(evidence.observeControl(packed + 1, false));
        assertEquals(1, evidence.predicted());
        assertEquals(1, evidence.predictedGone());
        assertEquals(1, evidence.control());
        assertEquals(0, evidence.controlGone());
    }

    @Test void sampledPositionsCanBeSkippedBeforeReadingBlocks() {
        var evidence = new MinedEvidence();
        long packed = MinedEvidence.pack(7, -40, 9);
        assertFalse(evidence.sawPredicted(packed));
        evidence.observePredicted(packed, false);
        assertTrue(evidence.sawPredicted(packed));
        long control = MinedEvidence.controls(packed)[0];
        assertFalse(evidence.sawControl(control));
        evidence.observeControl(control, true);
        assertTrue(evidence.sawControl(control));
    }

    @Test void miningIsOnlyConcludedWhenPredictedSpotsBeatControls() {
        var evidence = new MinedEvidence();
        for (int i = 0; i < 10; i++) evidence.observePredicted(MinedEvidence.pack(i, -50, 0), true);
        for (int i = 0; i < 10; i++) evidence.observeControl(MinedEvidence.pack(i, -50, 2), i < 2);
        assertTrue(evidence.suggestsMined(), "Ten air ore spots against two air controls is mining");

        var caves = new MinedEvidence();
        for (int i = 0; i < 10; i++) caves.observePredicted(MinedEvidence.pack(i, -50, 0), i < 5);
        for (int i = 0; i < 10; i++) caves.observeControl(MinedEvidence.pack(i, -50, 2), i < 4);
        assertFalse(caves.suggestsMined(), "Caves produce air on both sides");

        var untouched = new MinedEvidence();
        for (int i = 0; i < 4; i++) untouched.observePredicted(MinedEvidence.pack(i, -50, 0), true);
        assertFalse(untouched.suggestsMined(), "A tiny sample is not evidence");
    }

    @Test void resetClearsEverything() {
        var evidence = new MinedEvidence();
        evidence.observePredicted(MinedEvidence.pack(1, -20, 1), true);
        evidence.reset();
        assertEquals(0, evidence.predicted());
        assertEquals(0, evidence.predictedGone());
        assertTrue(evidence.observePredicted(MinedEvidence.pack(1, -20, 1), false), "Positions can be seen again after a reset");
        assertEquals(0, evidence.predictedRate());
        assertEquals(0, evidence.controlRate());
    }
}
