package dev.donut.client.blockesp;

import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeedTrustTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void noObservationsProveNothing() {
        assertEquals(SeedTrust.State.UNKNOWN, SeedTrust.assess(0, 0));
    }

    @Test void aCorrectSeedMissesNoNaturalOre() {
        assertEquals(SeedTrust.State.VERIFIED, SeedTrust.assess(1, 0));
        assertEquals(SeedTrust.State.VERIFIED, SeedTrust.assess(21, 0));
    }

    @Test void isolatedMissesMayBePlayerPlacedOres() {
        assertEquals(SeedTrust.State.SUSPECT, SeedTrust.assess(0, 1));
        assertEquals(SeedTrust.State.SUSPECT, SeedTrust.assess(0, SeedTrust.REFUTATION_SAMPLE - 1));
    }

    @Test void threeUnpredictedOresWithNoMatchesRefuteTheSeed() {
        // Deterministic generation gives a correct seed a zero miss probability; three independent
        // silk-touch placements in predicted chunks is not a plausible alternative explanation.
        assertEquals(SeedTrust.State.REFUTED, SeedTrust.assess(0, SeedTrust.REFUTATION_SAMPLE));
        assertEquals(SeedTrust.State.REFUTED, SeedTrust.assess(0, 40));
    }

    @Test void matchesPlusMissesMeanMixedGenerationEras() {
        // Chunks generated before a server enabled per-feature seeds still follow the world seed.
        assertEquals(SeedTrust.State.MIXED, SeedTrust.assess(2, 1));
        assertEquals(SeedTrust.State.MIXED, SeedTrust.assess(1, 12));
    }

    @Test void negativeCountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SeedTrust.assess(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> SeedTrust.assess(0, -1));
    }

    @Test void matchingRequiresExactPositionAndVariant() {
        var emerald = new OreMarker(new BlockPos(-110, -17, -142), OreMarker.Kind.EMERALD);
        var sameSpotCoal = new OreMarker(emerald.pos(), OreMarker.Kind.COAL);
        var neighbour = new OreMarker(emerald.pos().east(), OreMarker.Kind.EMERALD);
        assertEquals(1, SeedTrust.countMatches(Set.of(emerald), List.of(emerald, sameSpotCoal, neighbour)));
        assertEquals(0, SeedTrust.countMatches(Set.of(), List.of(emerald)));
    }
}
