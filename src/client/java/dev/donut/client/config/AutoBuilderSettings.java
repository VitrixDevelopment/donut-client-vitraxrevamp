package dev.donut.client.config;

/** Preferences for building behavior and presentation; starting always requires an explicit Start action. */
public final class AutoBuilderSettings {
    public boolean favorite = false;
    public double previewOpacity = .28;
    /** Build temporary stairs and bridges so the builder can walk up or across to reach blocks. */
    public boolean buildUp = true;
    /** Place and open shulker boxes from the inventory when a material runs out. */
    public boolean autoShulker = true;
    /** Buy missing materials through /ah after verifying the listing item and price. */
    public boolean autoBuy = true;
    /** Highest coin price paid per item on the auction house; listings above it are skipped. */
    public double maxAhPrice = 250;

    public void sanitize() {
        previewOpacity = Double.isFinite(previewOpacity) ? Math.clamp(previewOpacity, .08, .8) : .28;
        maxAhPrice = Double.isFinite(maxAhPrice) ? Math.clamp(maxAhPrice, 1, 1_000_000) : 250;
    }
}
