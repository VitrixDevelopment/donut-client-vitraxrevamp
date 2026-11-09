package dev.donut.client.blockesp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Probabilistic continuation of a partially revealed deepslate coal vein.
 *
 * <p>Vanilla deepslate coal is placed by blob features (vein sizes 20 and 30 for the lower and
 * upper features), which fill a connected volume around a random spine. Anti-xray style obfuscation
 * replaces every unexposed ore in the sent chunk with its host rock, so when mining or a cave
 * exposes one block of a vein, the rest of the vein is still in the chunk data — disguised as
 * ordinary deepslate. A random deepslate block is deepslate coal with probability well under 1%
 * (a few veins of ~20–30 blocks per chunk against ~10<sup>4</sup> deepslate blocks), but a
 * face-neighbour of a <i>confirmed</i> coal block belongs to the same connected blob with
 * probability orders of magnitude higher: blobs of size 20+ in a compact volume have most blocks
 * face-adjacent to another vein block. Candidates are therefore exactly the face-neighbours of
 * received coal that currently read as deepslate (the only host deepslate coal replaces in the
 * deepslate layer, and exactly what obfuscation disguises it as).
 *
 * <p>Deepslate emerald is scattered with vein size 1, so it has no continuation to infer; this
 * asymmetry is why inference applies to coal only. Candidates are hypotheses, marked
 * {@link OreMarker.Source#INFERRED} and rendered dimmer; they never count as seed-trust evidence.
 */
public final class VeinInference {
    private VeinInference() { }

    /**
     * Face-neighbours of received coal markers that {@code isDeepslateHost} accepts and whose
     * position is not already known (predicted, received, or previously inferred).
     */
    public static List<OreMarker> candidates(Collection<OreMarker> received, Predicate<BlockPos> isDeepslateHost,
                                             Set<BlockPos> knownPositions) {
        var result = new ArrayList<OreMarker>();
        for (var marker : received) {
            if (marker.kind() != OreMarker.Kind.COAL || marker.source() == OreMarker.Source.INFERRED) continue;
            for (var direction : Direction.values()) {
                BlockPos neighbour = marker.pos().relative(direction);
                if (knownPositions.contains(neighbour) || !isDeepslateHost.test(neighbour)) continue;
                knownPositions.add(neighbour);
                result.add(new OreMarker(neighbour, OreMarker.Kind.COAL, OreMarker.Source.INFERRED));
            }
        }
        return List.copyOf(result);
    }
}
