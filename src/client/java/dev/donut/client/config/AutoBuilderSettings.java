package dev.donut.client.config;

/** Builder preferences only; building always requires an explicit Start action. */
public final class AutoBuilderSettings {
    public boolean favorite = false;
    public double previewOpacity = .28;
    public boolean spectreCreative = true;

    public void sanitize() {
        previewOpacity = Double.isFinite(previewOpacity) ? Math.clamp(previewOpacity, .08, .8) : .28;
    }
}
