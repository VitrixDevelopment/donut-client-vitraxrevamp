package dev.donut.client.config;

/** Presentation preferences only; module state and module behavior live in their existing settings. */
public final class ClickGuiStyle {
    public double scale = 1;
    public double rounding = 10;
    public double backgroundDim = .25;
    public double animationSpeed = 1;
    public boolean liquidGlass = true;
    public int panelsColor = 0x1C1F29;
    public int cardsColor = 0x252833;
    public int tabBarColor = 0x191B24;
    public double panelsOpacity = 1;
    public double cardsOpacity = 1;
    public double tabBarOpacity = 1;

    public void sanitize() {
        scale = clamp(scale, .75, 1.5, 1);
        rounding = clamp(rounding, 0, 16, 10);
        backgroundDim = clamp(backgroundDim, 0, .8, .25);
        animationSpeed = clamp(animationSpeed, 0, 2, 1);
        panelsOpacity = clamp(panelsOpacity, .2, 1, 1);
        cardsOpacity = clamp(cardsOpacity, .2, 1, 1);
        tabBarOpacity = clamp(tabBarOpacity, .2, 1, 1);
        panelsColor &= 0xFFFFFF;
        cardsColor &= 0xFFFFFF;
        tabBarColor &= 0xFFFFFF;
    }

    private static double clamp(double value, double min, double max, double fallback) {
        return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
    }
}
