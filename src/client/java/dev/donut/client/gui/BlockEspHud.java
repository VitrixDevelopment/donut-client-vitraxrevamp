package dev.donut.client.gui;

import dev.donut.client.blockesp.AreaVerdicts;
import dev.donut.client.blockesp.BlockEspController;
import dev.donut.client.blockesp.OreMarker;
import dev.donut.client.config.ClientConfig;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * BlockESP overlay: prediction progress, counts, area verdicts, the mined test and the nearest deepslate emerald card.
 * Text lines are built here so the layout stays testable without a running game.
 */
public final class BlockEspHud {
    /** Longest emerald card line the panel will draw before truncating. */
    static final int MAX_CARD_CHARS = 62;

    private BlockEspHud() { }

    /** The nearest-emerald card text: heading, target line, distance line. */
    static ArrayList<String> cardLines(BlockEspController controller, Vec3 player, float yaw) {
        var lines = new ArrayList<String>();
        BlockPos target = controller.nearestEmerald();
        lines.add("Nearest emerald");
        if (target == null) {
            lines.add(controller.searchingEmerald() ? "Searching mountain biomes..." : controller.emeraldSearchState());
            return lines;
        }
        Vec3 at = Vec3.atCenterOf(target);
        double distance = player.distanceTo(at);
        String heading = bearing(player, target, yaw);
        int dy = target.getY() - (int) Math.floor(player.y);
        String vertical = Math.abs(dy) <= 1 ? "same height" : dy > 0 ? dy + "m above" : (-dy) + "m below";
        String verdict = switch (controller.areaStatus(target)) {
            case CONFIRMED -> "confirmed area";
            case UNMATCHED -> "area does not match the seed";
            default -> "unverified area";
        };
        lines.add((controller.nearestEmeraldIsDeepslate() ? "Deepslate emerald" : "Emerald") + " - " + Math.round(distance)
                + "m " + heading + " - " + vertical);
        lines.add(target.getX() + ", " + target.getY() + ", " + target.getZ() + " - " + verdict);
        if (controller.areaStatus(target) == AreaVerdicts.Status.UNVERIFIED) {
            lines.add("Check a little exposed coal or emerald there");
        }
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).length() > MAX_CARD_CHARS) lines.set(i, truncate(lines.get(i), MAX_CARD_CHARS));
        }
        return lines;
    }

    static String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, Math.max(0, limit - 3)) + "...";
    }

    /** Arrow for where the target sits relative to the way the player looks, plus a compass letter. */
    static String bearing(Vec3 player, BlockPos target, float yaw) {
        double targetYaw = Math.toDegrees(Math.atan2(-(target.getX() + .5 - player.x), target.getZ() + .5 - player.z));
        double relative = net.minecraft.util.Mth.wrapDegrees(targetYaw - yaw);
        String[] arrows = {"\u2191", "\u2197", "\u2192", "\u2198", "\u2193", "\u2199", "\u2190", "\u2196"};
        String[] compass = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int arrowIndex = Math.floorMod((int) Math.round(relative / 45.0), 8);
        double world = Math.toDegrees(Math.atan2(target.getX() + .5 - player.x, -(target.getZ() + .5 - player.z)));
        int compassIndex = Math.floorMod((int) Math.round(((world % 360) + 360) % 360 / 45.0), 8);
        return arrows[arrowIndex] + " " + compass[compassIndex];
    }

    public static void render(GuiGraphics graphics, ClientConfig config, BlockEspController controller) {
        Minecraft client = Minecraft.getInstance();
        var settings = config.blockEsp;
        if (!settings.enabled || !settings.showHud || !config.showHud || client.options.hideGui || client.player == null || client.screen != null) return;
        var text = new ArrayList<String>();
        var colors = new ArrayList<Integer>();
        text.add("BlockESP | Predicted"); colors.add(0xFFE5EFF9);
        text.add(controller.status()); colors.add(0xFFB3C3D3);
        for (var kind : OreMarker.Kind.values()) {
            if (kind == OreMarker.Kind.EMERALD ? !settings.emerald : !settings.coal) continue;
            var hits = controller.snapshot().stream().filter(marker -> marker.kind() == kind).toList();
            String label = (kind == OreMarker.Kind.EMERALD ? "Emerald" : "Coal") + ": " + hits.size();
            if (!hits.isEmpty()) {
                var pos = hits.getFirst().pos();
                int distance = (int) Math.round(Math.sqrt(pos.distToCenterSqr(client.player.position())));
                label += " | " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + " | " + distance + "m";
            }
            text.add(label); colors.add(0xFF000000 | (kind == OreMarker.Kind.EMERALD ? settings.emeraldColor : settings.coalColor));
        }
        if (settings.verifyAreas) {
            String areas = "Areas: " + controller.areasSummary();
            if (controller.hiddenMarkers() > 0) areas += " | " + controller.hiddenMarkers() + " markers hidden";
            text.add(areas); colors.add(0xFFB9C7D6);
        }
        if (settings.minedTest && controller.minedEvidence().predicted() > 0) {
            text.add((controller.minedEvidence().suggestsMined() ? "Mined: " : "Air check: ")
                    + controller.minedSummary()); colors.add(0xFFA9B9C9);
        }
        if (settings.nearestEmerald && settings.emerald) {
            for (String line : cardLines(controller, client.player.position(), client.player.getYRot())) {
                text.add(line); colors.add(0xFF000000 | settings.emeraldColor);
            }
        }
        int maxWidth = Math.max(80, graphics.guiWidth() - 24);
        int w = text.stream().mapToInt(client.font::width).max().orElse(100) + 16;
        w = Math.min(w, maxWidth);
        int h = text.size() * 12 + 12, x = 8, y = graphics.guiHeight() - h - 8;
        UiPaint.round(graphics, x, y, w, h, 5, 0xC0141C27);
        for (int i = 0; i < text.size(); i++) graphics.drawString(client.font,
                client.font.plainSubstrByWidth(text.get(i), w - 16), x + 8, y + 7 + i * 12, colors.get(i), false);
    }
}
