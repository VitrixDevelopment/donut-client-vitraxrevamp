package dev.donut.client.config;

/** Local stream privacy for the hotbar, selected-item name and held-item rendering. */
public final class HotbarSettings {
    public static final int MAX_BLUR_PERCENT = 140;
    public boolean enabled = false;
    public boolean favorite = false;
    public int blurPercent = 90;
    public boolean hideHeldItems = false;

    public void sanitize() {
        blurPercent = Math.clamp(blurPercent, 0, MAX_BLUR_PERCENT);
    }
}
