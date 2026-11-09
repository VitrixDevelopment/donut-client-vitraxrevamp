package dev.donut.client.blockesp;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Original generated block coordinates, never an assertion about the server's current block. */
public record OreMarker(BlockPos pos, Kind kind) {
    public enum Kind { EMERALD, COAL }

    public static Kind kind(BlockState state) {
        if (state.is(Blocks.DEEPSLATE_EMERALD_ORE)) return Kind.EMERALD;
        if (state.is(Blocks.DEEPSLATE_COAL_ORE)) return Kind.COAL;
        return null;
    }
}
