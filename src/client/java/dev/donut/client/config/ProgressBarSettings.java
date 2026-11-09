package dev.donut.client.config;

/** Appearance and screen placement for the streaming dimension progress overlay. */
public final class ProgressBarSettings {
    public static final double MIN_SCALE = 0.5;
    public static final double MAX_SCALE = 2.0;
    public static final int MAX_BASES_FOUND = 999999;

    public boolean enabled = false;
    public boolean favorite = false;
    public boolean shinyOutline = true;
    public int completedStages = 0;
    public int basesFound = 0;
    public ProgressTimerSettings[] timers = defaultTimers();
    public double scale = 1.0;
    /** Position within the available screen travel, so resizing keeps the overlay on screen. */
    public double x = 0.5;
    public double y = 0.12;

    public void sanitize() {
        completedStages = Math.clamp(completedStages, 0, 3);
        basesFound = Math.clamp(basesFound, 0, MAX_BASES_FOUND);
        if (timers == null) timers = defaultTimers();
        else if (timers.length != 3) timers = java.util.Arrays.copyOf(timers, 3);
        for (int i = 0; i < timers.length; i++) {
            if (timers[i] == null) timers[i] = new ProgressTimerSettings();
            timers[i].sanitize();
        }
        scale = Double.isFinite(scale) ? Math.clamp(scale, MIN_SCALE, MAX_SCALE) : 1.0;
        x = Double.isFinite(x) ? Math.clamp(x, 0.0, 1.0) : 0.5;
        y = Double.isFinite(y) ? Math.clamp(y, 0.0, 1.0) : 0.12;
    }

    private static ProgressTimerSettings[] defaultTimers() {
        return new ProgressTimerSettings[] {
                new ProgressTimerSettings(), new ProgressTimerSettings(), new ProgressTimerSettings()
        };
    }
}
