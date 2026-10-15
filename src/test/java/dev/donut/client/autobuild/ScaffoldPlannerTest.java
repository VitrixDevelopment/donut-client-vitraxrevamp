package dev.donut.client.autobuild;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.donut.client.autobuild.BuilderPathfinder.Cell;

class ScaffoldPlannerTest {
    /** Only the player's original support exists; everything else must be planned. */
    private static final Predicate<Cell> NOTHING_SOLID = cell -> false;
    private static final Predicate<Cell> ALWAYS_PLACEABLE = cell -> true;
    private static final Predicate<Cell> NEVER_STANDABLE = cell -> false;

    private static Predicate<Cell> solid(Set<Cell> cells) { return cells::contains; }

    @Test void climbsWithTreadRiserPairsAndEveryBlockKeepsAFace() {
        Cell start = new Cell(0, 1, 0);
        Cell goal = new Cell(3, 3, 0);
        var blocks = ScaffoldPlanner.plan(start, goal, 24, NEVER_STANDABLE, ALWAYS_PLACEABLE,
                solid(Set.of(start.offset(0, -1, 0))));
        assertEquals(List.of(new Cell(1, 0, 0), new Cell(1, 1, 0), new Cell(2, 1, 0), new Cell(2, 2, 0),
                new Cell(3, 2, 0)), blocks);
        assertChain(blocks, Set.of(start.offset(0, -1, 0)));
    }

    @Test void bridgesLevelGapsWithOneBlockPerStep() {
        Cell start = new Cell(0, 1, 0);
        Cell goal = new Cell(2, 1, 0);
        var blocks = ScaffoldPlanner.plan(start, goal, 24, NEVER_STANDABLE, ALWAYS_PLACEABLE,
                solid(Set.of(start.offset(0, -1, 0))));
        assertEquals(List.of(new Cell(1, 0, 0), new Cell(2, 0, 0)), blocks);
        assertChain(blocks, Set.of(start.offset(0, -1, 0)));
    }

    @Test void skipsExistingGroundAndOnlyFillsTheGap() {
        Cell start = new Cell(0, 1, 0);
        Cell goal = new Cell(2, 1, 0);
        Cell existing = new Cell(1, 0, 0);
        var blocks = ScaffoldPlanner.plan(start, goal, 24, cell -> cell.equals(new Cell(1, 1, 0)),
                ALWAYS_PLACEABLE, solid(Set.of(start.offset(0, -1, 0), existing)));
        assertEquals(List.of(new Cell(2, 0, 0)), blocks);
    }

    @Test void refusesWhenTheFirstBlockHasNoFaceOrRoom() {
        Cell start = new Cell(0, 1, 0);
        var noRoom = ScaffoldPlanner.plan(start, new Cell(3, 3, 0), 24, NEVER_STANDABLE,
                cell -> false, solid(Set.of(start.offset(0, -1, 0))));
        assertTrue(noRoom.isEmpty());
        var noFoundation = ScaffoldPlanner.plan(start, new Cell(3, 3, 0), 24, NEVER_STANDABLE,
                ALWAYS_PLACEABLE, NOTHING_SOLID);
        assertTrue(noFoundation.isEmpty());
    }

    @Test void honorsTheBlockBudgetAndRefusesDescents() {
        Cell start = new Cell(0, 1, 0);
        assertTrue(ScaffoldPlanner.plan(start, new Cell(10, 1, 0), 4, NEVER_STANDABLE, ALWAYS_PLACEABLE,
                solid(Set.of(start.offset(0, -1, 0)))).isEmpty());
        assertTrue(ScaffoldPlanner.plan(start, new Cell(1, -4, 0), 24, NEVER_STANDABLE, ALWAYS_PLACEABLE,
                solid(Set.of(start.offset(0, -1, 0)))).isEmpty());
    }

    @Test void towersStraightUpWithAZigzag() {
        Cell start = new Cell(0, 1, 0);
        Cell goal = new Cell(0, 3, 0);
        var blocks = ScaffoldPlanner.plan(start, goal, 24, NEVER_STANDABLE, ALWAYS_PLACEABLE,
                solid(Set.of(start.offset(0, -1, 0))));
        assertEquals(4, blocks.size());
        assertChain(blocks, Set.of(start.offset(0, -1, 0)));
        assertEquals(goal, new Cell(blocks.getLast().x(), blocks.getLast().y() + 1, blocks.getLast().z()));
    }

    /** Each planned block must face-touch the original foundation or an earlier planned block. */
    private static void assertChain(List<Cell> blocks, Set<Cell> foundation) {
        for (int i = 0; i < blocks.size(); i++) {
            Cell block = blocks.get(i);
            boolean attached = foundation.contains(block);
            for (int j = 0; j < i && !attached; j++) {
                Cell earlier = blocks.get(j);
                attached = Math.abs(block.x() - earlier.x()) + Math.abs(block.y() - earlier.y())
                        + Math.abs(block.z() - earlier.z()) == 1;
            }
            assertTrue(attached, "block " + block + " has no face support");
        }
    }
}
