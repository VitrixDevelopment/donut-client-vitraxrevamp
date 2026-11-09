package dev.donut.client.config;

import dev.donut.client.chunkfinder.ActivityClues;
import dev.donut.client.chunkfinder.SuspectObjects.Category;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChunkFinderSettings {
    public static final List<String> METHODS = List.of("Classic", "ANBS+ Scan", "Activity clues");
    public boolean enabled = false;
    public boolean favorite = false;
    public String method = "Classic";
    public int simulationDistance = 5;
    public int sensitivity = 6;
    public boolean smartAdjustment = true;
    public int alpha = 255;
    public int objectRange = 128;
    public boolean inspectPanel = true;
    public int panelTopColor = 0x292C3B;
    public int panelBottomColor = 0x171A21;
    public double panelOpacity = .96;
    public int clueThreshold = 2;
    public int kelpLength = 16;
    public int vineLength = 16;
    public int caveVineLength = 20;
    public int bambooLength = 14;
    public int matureClusters = 12;
    public int maturePercent = 75;
    public Map<String, Boolean> objects = new LinkedHashMap<>();
    public Map<String, Integer> objectColors = new LinkedHashMap<>();
    public boolean kelp = true;
    public boolean caveVines = true;
    public boolean vines = true;
    public boolean amethyst = true;
    public boolean bamboo = true;
    public boolean cocoa = true;
    public boolean beeNest = true;
    public int color = 0xC447FF; // Retained for older configuration files; objects use category colors.

    public boolean clueEnabled(ActivityClues.Kind kind) {
        return switch (kind) {
            case KELP -> kelp;
            case CAVE_VINES -> caveVines;
            case VINES -> vines;
            case AMETHYST -> amethyst;
            case BAMBOO -> bamboo;
            case COCOA -> cocoa;
            case BEES -> beeNest;
        };
    }

    public boolean categoryEnabled(Category category) {
        return objects == null || !Boolean.FALSE.equals(objects.get(category.id()));
    }

    public int categoryColor(Category category) {
        Integer saved = objectColors == null ? null : objectColors.get(category.id());
        return 0xFF000000 | (saved == null ? category.color : saved) & 0xFFFFFF;
    }

    public void sanitize() {
        if (method == null || !METHODS.contains(method)) method = "Classic";
        simulationDistance = Math.clamp(simulationDistance, 2, 16);
        sensitivity = Math.clamp(sensitivity, 1, 20);
        alpha = Math.clamp(alpha, 0, 255);
        objectRange = Math.clamp(objectRange, 16, 256);
        panelTopColor &= 0xFFFFFF;
        panelBottomColor &= 0xFFFFFF;
        panelOpacity = Double.isFinite(panelOpacity) ? Math.clamp(panelOpacity, .2, 1) : .96;
        clueThreshold = Math.clamp(clueThreshold, 1, 8);
        kelpLength = Math.clamp(kelpLength, 2, 64);
        vineLength = Math.clamp(vineLength, 2, 64);
        caveVineLength = Math.clamp(caveVineLength, 2, 64);
        bambooLength = Math.clamp(bambooLength, 2, 32);
        matureClusters = Math.clamp(matureClusters, 2, 128);
        maturePercent = Math.clamp(maturePercent, 10, 100);
        if (objects == null) objects = new LinkedHashMap<>();
        if (objectColors == null) objectColors = new LinkedHashMap<>();
        for (Category category : Category.values()) {
            objects.putIfAbsent(category.id(), true);
            objectColors.compute(category.id(), (key, value) -> (value == null ? category.color : value) & 0xFFFFFF);
        }
        color &= 0xFFFFFF;
    }
}
