package dev.donut.client.chunkfinder;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SuspectObjectsTest {
    private static SuspectObjects.Marker marker(AABB bounds) {
        return new SuspectObjects.Marker(bounds, List.of(bounds), SuspectObjects.Category.CONTAINERS, Map.of(), "Container");
    }

    @Test void exactSightTargetWinsOverNearCrosshairTarget() {
        var nearMiss = marker(new AABB(2.1, .1, -.1, 3, 1, .1));
        var exact = marker(new AABB(5, -.5, -.5, 6, .5, .5));
        var focus = SuspectObjects.focus(List.of(nearMiss, exact), Vec3.ZERO, new Vec3(1, 0, 0), 20);
        assertNotNull(focus);
        assertSame(exact, focus.target());
    }

    @Test void closestExactObjectWinsAndNearbyCountsStayInRange() {
        var near = marker(new AABB(3, -.5, -.5, 4, .5, .5));
        var behind = marker(new AABB(8, -.5, -.5, 9, .5, .5));
        var remote = marker(new AABB(50, -.5, -.5, 51, .5, .5));
        var focus = SuspectObjects.focus(List.of(behind, remote, near), Vec3.ZERO, new Vec3(1, 0, 0), 20);
        assertSame(near, focus.target());
        assertEquals(3, focus.distance(), .001);
        assertEquals(2, focus.nearby().size());
    }

    @Test void objectsBehindCameraAndOutsideRangeAreNotFocused() {
        var behind = marker(new AABB(-4, -.5, -.5, -3, .5, .5));
        var remote = marker(new AABB(30, -.5, -.5, 31, .5, .5));
        assertNull(SuspectObjects.focus(List.of(behind, remote), Vec3.ZERO, new Vec3(1, 0, 0), 20));
    }

    @Test void cameraInsideFormationCanStillInspectIt() {
        var inside = marker(new AABB(-1, -1, -1, 1, 1, 1));
        assertSame(inside, SuspectObjects.focus(List.of(inside), Vec3.ZERO, new Vec3(0, 0, 1), 20).target());
    }
}
