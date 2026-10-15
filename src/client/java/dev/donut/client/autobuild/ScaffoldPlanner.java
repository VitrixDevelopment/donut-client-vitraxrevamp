package dev.donut.client.autobuild;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Plans temporary stair and bridge blocks so the builder can walk up or across to a stand cell.
 * Every planned block face-touches an earlier support, so vanilla placement always has a face.
 * The plan climbs or bridges level with the builder; descents use existing terrain falls.
 */
public final class ScaffoldPlanner {
    private ScaffoldPlanner() { }

    /**
     * Returns temporary block positions in build order, or an empty list when a bounded plan is impossible.
     * {@code standable} marks feet cells walkable now, {@code placeable} cells that may receive a temporary
     * block, and {@code solid} existing blocks that can carry or face-support another block.
     */
    public static List<BuilderPathfinder.Cell> plan(BuilderPathfinder.Cell start, BuilderPathfinder.Cell goal,
                                                    int maxBlocks, Predicate<BuilderPathfinder.Cell> standable,
                                                    Predicate<BuilderPathfinder.Cell> placeable,
                                                    Predicate<BuilderPathfinder.Cell> solid) {
        if (start.equals(goal) || maxBlocks < 1) return List.of();
        int rise = goal.y() - start.y();
        if (rise < 0) return List.of();
        if (!solid.test(start.offset(0, -1, 0)) && !standable.test(start)) return List.of();
        List<BuilderPathfinder.Cell> blocks = new ArrayList<>();
        Set<BuilderPathfinder.Cell> planned = new HashSet<>();
        BuilderPathfinder.Cell feet = start;
        int steps = 0;
        while (!feet.equals(goal)) {
            if (blocks.size() >= maxBlocks || steps++ > maxBlocks * 3) return List.of();
            int stepX = Integer.compare(goal.x() - feet.x(), 0);
            int stepZ = Integer.compare(goal.z() - feet.z(), 0);
            boolean moveX = stepX != 0 && (stepZ == 0 || Math.abs(goal.x() - feet.x()) >= Math.abs(goal.z() - feet.z()));
            int sx = moveX ? stepX : 0, sz = moveX ? 0 : stepZ;
            if (sx == 0 && sz == 0) {
                // Straight above the goal column: rise with a small zigzag so every block keeps a face.
                if (feet.y() >= goal.y()) return List.of();
                sx = (steps & 1) == 0 ? -1 : 1;
            }
            BuilderPathfinder.Cell next = feet.offset(sx, feet.y() < goal.y() ? 1 : 0, sz);
            if (standable.test(next)) {
                feet = next;
                continue;
            }
            if (feet.y() < goal.y()) {
                // Climb one: a tread beside the current support, then the riser on top of it.
                BuilderPathfinder.Cell tread = feet.offset(sx, -1, sz);
                BuilderPathfinder.Cell riser = tread.offset(0, 1, 0);
                if (!ensure(blocks, planned, tread, placeable, solid)) return List.of();
                if (!ensure(blocks, planned, riser, placeable, solid)) return List.of();
            } else {
                // Bridge level: one full block under the next feet cell.
                if (!ensure(blocks, planned, feet.offset(sx, -1, sz), placeable, solid)) return List.of();
            }
            feet = next;
        }
        return List.copyOf(blocks);
    }

    private static boolean ensure(List<BuilderPathfinder.Cell> blocks, Set<BuilderPathfinder.Cell> planned,
                                  BuilderPathfinder.Cell block, Predicate<BuilderPathfinder.Cell> placeable,
                                  Predicate<BuilderPathfinder.Cell> solid) {
        if (planned.contains(block) || solid.test(block)) return true;
        if (!placeable.test(block)) return false;
        for (BuilderPathfinder.Cell neighbor : new BuilderPathfinder.Cell[]{
                block.offset(0, -1, 0), block.offset(0, 1, 0), block.offset(1, 0, 0),
                block.offset(-1, 0, 0), block.offset(0, 0, 1), block.offset(0, 0, -1)}) {
            if (solid.test(neighbor) || planned.contains(neighbor)) {
                blocks.add(block);
                planned.add(block);
                return true;
            }
        }
        return false;
    }
}
