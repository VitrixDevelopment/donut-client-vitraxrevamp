package dev.donut.client.gui;

import dev.donut.client.blockesp.BlockEspController;
import dev.donut.client.blockesp.OreMarker;
import dev.donut.client.config.ClientConfig;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class BlockEspHud {
    private BlockEspHud() { }
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
        int maxWidth = Math.max(80, graphics.guiWidth() - 24);
        int w = text.stream().mapToInt(client.font::width).max().orElse(100) + 16;
        w = Math.min(w, maxWidth);
        int h = text.size() * 12 + 12, x = 8, y = graphics.guiHeight() - h - 8;
        UiPaint.round(graphics, x, y, w, h, 5, 0xC0141C27);
        for (int i = 0; i < text.size(); i++) graphics.drawString(client.font,
                client.font.plainSubstrByWidth(text.get(i), w - 16), x + 8, y + 7 + i * 12, colors.get(i), false);
    }
}
