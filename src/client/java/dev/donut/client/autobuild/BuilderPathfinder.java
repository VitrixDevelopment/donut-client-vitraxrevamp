package dev.donut.client.autobuild;

import java.util.*;
import java.util.function.Predicate;

/**
 * Bounded A* over walkable feet cells. Pure geometry keeps route behavior independently testable.
 * Routes may use diagonals and short falls; jumps need clearance over the departure cell.
 */
public final class BuilderPathfinder {
    public record Cell(int x, int y, int z) {
        public Cell offset(int dx, int dy, int dz) { return new Cell(x + dx, y + dy, z + dz); }
        public double distance(Cell other) { return Math.sqrt((double)(x-other.x)*(x-other.x) + (double)(y-other.y)*(y-other.y) + (double)(z-other.z)*(z-other.z)); }
    }
    public interface Terrain {
        boolean standable(Cell cell);
        /** Jumping up requires clearance over the departure block too. */
        boolean headroom(Cell cell);
        /** Largest fall taken without damage, in cells. Vanilla's safe fall covers three. */
        default int maxDrop() { return 3; }
    }
    private record Entry(Cell cell, double score) { }
    private BuilderPathfinder() { }

    /** Four axis steps and four diagonals; diagonals never cut a corner the player cannot pass. */
    private static final int[][] PLANAR = {
            {1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}
    };
    private static final double ORTHOGONAL_COST = 1.0, DIAGONAL_COST = 1.42;
    private static final double JUMP_COST = 0.5, FALL_COST = 0.4, EXTRA_DROP_COST = 0.25;

    public static List<Cell> find(Cell start, Cell target, Terrain terrain, Predicate<Cell> goal,
                                  int maxVisited, int maxDistance) {
        PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::score));
        Map<Cell, Double> costs = new HashMap<>();
        Map<Cell, Cell> previous = new HashMap<>();
        Set<Cell> closed = new HashSet<>();
        costs.put(start, 0d);
        open.add(new Entry(start, heuristic(start, target)));
        int maxDrop = Math.max(0, terrain.maxDrop());
        while (!open.isEmpty() && closed.size() < maxVisited) {
            Cell current = open.remove().cell;
            if (!closed.add(current)) continue;
            if (goal.test(current)) {
                LinkedList<Cell> route = new LinkedList<>();
                for (Cell cursor = current; !cursor.equals(start); cursor = previous.get(cursor)) route.addFirst(cursor);
                return List.copyOf(route);
            }
            double base = costs.get(current);
            for (int[] axis : PLANAR) {
                boolean diagonal = axis[0] != 0 && axis[1] != 0;
                Cell side = current.offset(axis[0], 0, axis[1]);
                if (!inBounds(side, start, maxDistance)) continue;
                boolean corner = !diagonal || clearCorner(terrain, current, side);
                // Straight or diagonal step at the same height.
                if (corner && terrain.standable(side))
                    offer(open, costs, previous, current, side, base + (diagonal ? DIAGONAL_COST : ORTHOGONAL_COST), target);
                // Jump one block up; the jump itself needs headroom over the departure cell.
                Cell up = side.offset(0, 1, 0);
                if (inBounds(up, start, maxDistance) && terrain.headroom(current.offset(0, 1, 0))
                        && (!diagonal || clearCorner(terrain, current, side) && clearCorner(terrain, current.offset(0, 1, 0), up))
                        && terrain.standable(up))
                    offer(open, costs, previous, current, up,
                            base + (diagonal ? DIAGONAL_COST : ORTHOGONAL_COST) + JUMP_COST, target);
                // Walk off an edge and fall to the first standable landing within the safe drop.
                if (!terrain.headroom(side)) continue;
                if (diagonal && !corner) continue;
                for (int drop = 1; drop <= maxDrop; drop++) {
                    Cell shaft = side.offset(0, 1 - drop, 0);
                    if (!terrain.headroom(shaft)) break;
                    Cell landing = side.offset(0, -drop, 0);
                    if (!inBounds(landing, start, maxDistance)) break;
                    if (terrain.standable(landing)) {
                        offer(open, costs, previous, current, landing,
                                base + (diagonal ? DIAGONAL_COST : ORTHOGONAL_COST)
                                        + FALL_COST + EXTRA_DROP_COST * (drop - 1), target);
                        break;
                    }
                }
            }
        }
        return List.of();
    }

    private static void offer(PriorityQueue<Entry> open, Map<Cell, Double> costs, Map<Cell, Cell> previous,
                              Cell from, Cell next, double cost, Cell target) {
        if (cost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) return;
        costs.put(next, cost);
        previous.put(next, from);
        open.add(new Entry(next, cost + heuristic(next, target)));
    }

    /** Octile planar distance plus the unavoidable cost of rising; the goal region slack keeps it admissible. */
    private static double heuristic(Cell from, Cell to) {
        int dx = Math.abs(from.x() - to.x()), dz = Math.abs(from.z() - to.z());
        int diagonal = Math.min(dx, dz), straight = Math.max(dx, dz) - diagonal;
        double planar = diagonal * 1.41 + straight;
        double rise = Math.max(0, to.y() - from.y()) * JUMP_COST;
        return Math.max(0, planar + rise - 4);
    }

    /** Diagonals must pass both axis-adjacent cells so the player cannot clip through a corner. */
    private static boolean clearCorner(Terrain terrain, Cell current, Cell side) {
        return terrain.standable(current.offset(side.x() - current.x(), 0, 0))
                && terrain.standable(current.offset(0, 0, side.z() - current.z()));
    }

    private static boolean inBounds(Cell cell, Cell start, int maxDistance) {
        return Math.abs(cell.x() - start.x()) <= maxDistance
                && Math.abs(cell.z() - start.z()) <= maxDistance
                && Math.abs(cell.y() - start.y()) <= 24;
    }
}
