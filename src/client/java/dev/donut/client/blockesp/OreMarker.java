package dev.donut.client.blockesp;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A target-ore position and how it is known. Identity is position plus variant only: the same
 * block reported by prediction, by a received chunk, and by vein inference is one marker, so
 * {@link #equals(Object)} deliberately ignores {@link Source}.
 */
public record OreMarker(BlockPos pos, Kind kind, Source source) {
    public enum Kind { EMERALD, COAL }

    /** How a marker is known, in decreasing certainty. */
    public enum Source {
        /** Reconstructed by vanilla generation from the configured seed; original, not current. */
        PREDICTED,
        /** Present in a chunk the server actually sent; true at the time it was received. */
        RECEIVED,
        /** Not observed: a deepslate neighbour of a received coal block, inferred from vein shape. */
        INFERRED
    }

    public OreMarker(BlockPos pos, Kind kind) { this(pos, kind, Source.PREDICTED); }

    public OreMarker withSource(Source source) { return new OreMarker(pos, kind, source); }
    public boolean inferred() { return source == Source.INFERRED; }

    @Override public boolean equals(Object other) {
        return other instanceof OreMarker marker && pos.equals(marker.pos) && kind == marker.kind;
    }

    @Override public int hashCode() { return Objects.hash(pos, kind); }

    public static Kind kind(BlockState state) {
        if (state.is(Blocks.DEEPSLATE_EMERALD_ORE)) return Kind.EMERALD;
        if (state.is(Blocks.DEEPSLATE_COAL_ORE)) return Kind.COAL;
        return null;
    }
}
