package dev.donut.client.sound;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalActionTrackerTest {
    @AfterEach void clear() { LocalActionTracker.clear(); }
    @Test void requiresKnownTargetCategoryAndRecentActionRatherThanPlayerProximity() {
        long now = 10_000_000_000L;
        LocalActionTracker.recordAt(new BlockPos(-3, 20, -7), "containers", now);
        assertTrue(LocalActionTracker.matches(-2.5, 20.5, -6.5, "containers", now + 200_000_000));
        assertFalse(LocalActionTracker.matches(-2.5, 20.5, -6.5, "building", now + 200_000_000));
        assertFalse(LocalActionTracker.matches(-1.5, 20.5, -6.5, "containers", now + 200_000_000), "Adjacent unrelated chest is not self activity");
        assertFalse(LocalActionTracker.matches(-2.5, 20.5, -6.5, "containers", now + 1_100_000_000));
    }
    @Test void allowsDoubleChestMidpointAndClearInvalidatesOldWorldContext() {
        long now = 10_000_000_000L;
        LocalActionTracker.recordAt(new BlockPos(5, 20, 7), "containers", now);
        assertTrue(LocalActionTracker.matches(6, 20.5, 7.5, "containers", now));
        LocalActionTracker.clear();
        assertFalse(LocalActionTracker.matches(6, 20.5, 7.5, "containers", now));
    }
    @Test void boundedHistoryDoesNotRetainOldestTargets() {
        long now = 10_000_000_000L;
        for (int i = 0; i < 200; i++) LocalActionTracker.recordAt(new BlockPos(i * 3, 20, 0), "building", now);
        assertFalse(LocalActionTracker.matches(.5, 20.5, .5, "building", now));
        assertTrue(LocalActionTracker.matches(597.5, 20.5, .5, "building", now));
    }
    @Test void configurableWindowControlsLatencyMatchWithoutExtendingTargetRadius() {
        long now = 10_000_000_000L;
        LocalActionTracker.recordAt(new BlockPos(0, 20, 0), "containers", now);
        assertFalse(LocalActionTracker.matches(.5, 20.5, .5, "containers", now + 1_500_000_000L, 1));
        assertTrue(LocalActionTracker.matches(.5, 20.5, .5, "containers", now + 1_500_000_000L, 2));
        assertFalse(LocalActionTracker.matches(1.5, 20.5, .5, "containers", now + 1_500_000_000L, 2));
    }
}
