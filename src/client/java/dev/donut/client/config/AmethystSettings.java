package dev.donut.client.config;

import java.util.List;

public final class AmethystSettings {
    public static final List<String> METHODS = List.of("Bypass", "Light", "ANBS+ Scan");
    public boolean enabled = false;
    public boolean favorite = false;
    public String method = "Bypass";
    public int minCells = 12;
    public boolean tracer = false;

    public void sanitize() {
        if (method == null || !METHODS.contains(method)) method = "Bypass";
        minCells = Math.clamp(minCells, 1, 100);
    }
}
