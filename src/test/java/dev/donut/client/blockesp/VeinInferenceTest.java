package dev.donut.client.blockesp;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VeinInferenceTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private static OreMarker received(int x, int y, int z, OreMarker.Kind kind) {
        return new OreMarker(new BlockPos(x, y, z), kind, OreMarker.Source.RECEIVED);
    }

    @Test void deepslateFaceNeighboursOfReceivedCoalAreCandidates() {
        var coal = received(0, -30, 0, OreMarker.Kind.COAL);
        var candidates = VeinInference.candidates(List.of(coal), pos -> true, new HashSet<>());
        assertEquals(6, candidates.size());
        assertTrue(candidates.stream().allMatch(marker -> marker.kind() == OreMarker.Kind.COAL
                && marker.source() == OreMarker.Source.INFERRED
                && marker.pos().distManhattan(coal.pos()) == 1));
    }

    @Test void emeraldHasNoVeinToContinue() {
        var emerald = received(0, -30, 0, OreMarker.Kind.EMERALD);
        assertTrue(VeinInference.candidates(List.of(emerald), pos -> true, new HashSet<>()).isEmpty());
    }

    @Test void nonHostBlocksAndKnownPositionsAreExcluded() {
        var coal = received(0, -30, 0, OreMarker.Kind.COAL);
        var known = new HashSet<BlockPos>(Set.of(coal.pos().above()));
        // Only the block below reads as deepslate host rock.
        var candidates = VeinInference.candidates(List.of(coal), pos -> pos.equals(coal.pos().below()), known);
        assertEquals(List.of(new OreMarker(coal.pos().below(), OreMarker.Kind.COAL)), candidates);
    }

    @Test void sharedNeighboursAreOnlyInferredOnce() {
        var a = received(0, -30, 0, OreMarker.Kind.COAL);
        var b = received(2, -30, 0, OreMarker.Kind.COAL);
        var candidates = VeinInference.candidates(List.of(a, b), pos -> true,
                new HashSet<>(Set.of(a.pos(), b.pos())));
        long shared = candidates.stream().filter(marker -> marker.pos().equals(a.pos().east())).count();
        assertEquals(1, shared);
        assertEquals(10, candidates.size());
    }

    @Test void markerIdentityIgnoresHowItIsKnown() {
        var pos = new BlockPos(4, -12, 9);
        var predicted = new OreMarker(pos, OreMarker.Kind.COAL, OreMarker.Source.PREDICTED);
        var received = new OreMarker(pos, OreMarker.Kind.COAL, OreMarker.Source.RECEIVED);
        assertEquals(predicted, received);
        assertEquals(predicted.hashCode(), received.hashCode());
        assertNotEquals(predicted, new OreMarker(pos, OreMarker.Kind.EMERALD, OreMarker.Source.PREDICTED));
    }
}
