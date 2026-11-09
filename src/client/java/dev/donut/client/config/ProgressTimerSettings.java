package dev.donut.client.config;

/** Saved countdown values; active clock state is kept outside the configuration. */
public final class ProgressTimerSettings {
    public static final int MIN_DURATION_SECONDS = 1;
    public static final int MAX_DURATION_SECONDS = 359999;
    public static final int DEFAULT_DURATION_SECONDS = 3600;

    public int durationSeconds = DEFAULT_DURATION_SECONDS;
    public long remainingMillis = DEFAULT_DURATION_SECONDS * 1000L;

    public void sanitize() {
        durationSeconds = Math.clamp(durationSeconds, MIN_DURATION_SECONDS, MAX_DURATION_SECONDS);
        remainingMillis = Math.clamp(remainingMillis, 0, durationSeconds * 1000L);
    }
}
