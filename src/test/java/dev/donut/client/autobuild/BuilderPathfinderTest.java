package dev.donut.client.autobuild;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.donut.client.autobuild.BuilderPathfinder.Cell;

class BuilderPathfinderTest {
    @Test void walksAroundAnObstacleAndNeverCrossesUnsupportedCells() {
        Set<Cell> blocked=Set.of(new Cell(2,1,-1),new Cell(2,1,0),new Cell(2,1,1));
        var terrain=new BuilderPathfinder.Terrain() {
            public boolean standable(Cell p) { return p.y()==1 && !blocked.contains(p) && p.z()!=-2; }
            public boolean headroom(Cell p) { return !blocked.contains(p); }
        };
        Cell end=new Cell(5,1,0);
        var path=BuilderPathfinder.find(new Cell(0,1,0),end,terrain,end::equals,500,10);
        assertFalse(path.isEmpty());
        assertEquals(end,path.getLast());
        assertTrue(path.stream().allMatch(terrain::standable));
        assertTrue(path.stream().anyMatch(p->p.z()==2));
    }
    @Test void refusesAJumpWithABlockedDepartureCeiling() {
        var terrain=new BuilderPathfinder.Terrain() {
            public boolean standable(Cell p) { return p.equals(new Cell(0,1,0)) || p.equals(new Cell(1,2,0)); }
            public boolean headroom(Cell p) { return !p.equals(new Cell(0,2,0)); }
        };
        Cell end=new Cell(1,2,0);
        assertTrue(BuilderPathfinder.find(new Cell(0,1,0),end,terrain,end::equals,40,5).isEmpty());
    }
    @Test void unreachableGoalHonorsTheSearchBounds() {
        var terrain=new BuilderPathfinder.Terrain() {
            public boolean standable(Cell p) { return p.y()==1; }
            public boolean headroom(Cell p) { return true; }
        };
        Cell end=new Cell(1000,1,0);
        assertTrue(BuilderPathfinder.find(new Cell(0,1,0),end,terrain,end::equals,200,8).isEmpty());
    }
    @Test void crossesOpenGroundDiagonally() {
        var terrain=new BuilderPathfinder.Terrain() {
            public boolean standable(Cell p) { return p.y()==1; }
            public boolean headroom(Cell p) { return true; }
        };
        Cell end=new Cell(3,1,3);
        var path=BuilderPathfinder.find(new Cell(0,1,0),end,terrain,end::equals,400,10);
        assertFalse(path.isEmpty());
        assertEquals(end,path.getLast());
        // A diagonal shortcut visits fewer cells than walking the two axis edges.
        assertTrue(path.size()<=3,"expected a diagonal route, got "+path);
    }
    @Test void diagonalNeverCutsAnUnsupportedCorner() {
        // Reaching (1,1,1) diagonally would clip the missing corner cell (1,1,0); the route must step around.
        var terrain=new BuilderPathfinder.Terrain() {
            public boolean standable(Cell p) { return p.y()==1 && !p.equals(new Cell(1,1,0)); }
            public boolean headroom(Cell p) { return true; }
        };
        Cell end=new Cell(1,1,1);
        var path=BuilderPathfinder.find(new Cell(0,1,0),end,terrain,end::equals,200,8);
        assertFalse(path.isEmpty());
        assertEquals(end,path.getLast());
        assertTrue(path.size()>=2,"the diagonal past the missing corner must not be used: "+path);
    }
    @Test void fallsUpToTheSafeDropButRefusesLongerOnes() {
        var shallow=new BuilderPathfinder.Terrain() {
            public boolean standable(Cell p) { return p.equals(new Cell(0,3,0)) || p.equals(new Cell(1,0,0)); }
            public boolean headroom(Cell p) { return p.y()>=0; }
        };
        Cell low=new Cell(1,0,0);
        // Stepping off the ledge lands three cells below: exactly vanilla's safe fall distance.
        var path=BuilderPathfinder.find(new Cell(0,3,0),low,shallow,low::equals,200,8);
        assertFalse(path.isEmpty());
        assertEquals(low,path.getLast());
        var deep=new BuilderPathfinder.Terrain() {
            public boolean standable(Cell p) { return p.equals(new Cell(0,4,0)) || p.equals(new Cell(1,0,0)); }
            public boolean headroom(Cell p) { return p.y()>=0; }
        };
        // One cell deeper is fall damage territory and must be refused by default.
        assertTrue(BuilderPathfinder.find(new Cell(0,4,0),low,deep,low::equals,200,8).isEmpty());
    }
}
