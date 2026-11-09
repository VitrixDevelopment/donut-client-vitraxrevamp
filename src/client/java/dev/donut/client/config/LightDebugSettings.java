package dev.donut.client.config;

/** Spectre Light Debug preferences. No Player Bypass or Prime Chunk Finder modes. */
public final class LightDebugSettings {
    public boolean enabled, favorite;
    public int radius = 8, minLight = 5, color = 0x80FF0000;
    public boolean denoise = true;

    public void sanitize() {
        radius = Math.clamp(radius, 1, 16);
        minLight = Math.clamp(minLight, 1, 15);
    }
}
