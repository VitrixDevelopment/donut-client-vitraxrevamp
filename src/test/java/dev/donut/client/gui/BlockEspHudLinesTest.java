package dev.donut.client.gui;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** The overlay's text helpers stay pure so layout rules can be checked without a running client. */
class BlockEspHudLinesTest {
    @Test void truncationKeepsLinesInsideThePanel() {
        assertEquals("short", BlockEspHud.truncate("short", 10));
        assertEquals("1234567...", BlockEspHud.truncate("1234567890abcdef", 10));
        assertEquals(10, BlockEspHud.truncate("1234567890abcdef", 10).length());
        assertEquals("...", BlockEspHud.truncate("abcdef", 3));
    }

    @Test void bearingPointsAtTheTargetRelativeToWhereThePlayerLooks() {
        Vec3 player = new Vec3(0.5, 64, 0.5);
        var north = new net.minecraft.core.BlockPos(0, 64, -100);
        var south = new net.minecraft.core.BlockPos(0, 64, 100);
        var east = new net.minecraft.core.BlockPos(100, 64, 0);
        // Facing north (yaw 180): north is straight ahead, south behind, east to the right.
        assertTrue(BlockEspHud.bearing(player, north, 180f).startsWith("\u2191"));
        assertTrue(BlockEspHud.bearing(player, south, 180f).startsWith("\u2193"));
        assertTrue(BlockEspHud.bearing(player, east, 180f).startsWith("\u2192"));
        assertTrue(BlockEspHud.bearing(player, north, 180f).endsWith("N"));
        assertTrue(BlockEspHud.bearing(player, east, 180f).endsWith("E"));
        // Facing east (yaw -90 = 270): east is straight ahead.
        assertTrue(BlockEspHud.bearing(player, east, 270f).startsWith("\u2191"));
    }
}
