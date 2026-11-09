package dev.donut.client.progress;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ProgressTimerSettings;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.LongSupplier;

/** Independent real-time countdowns with monotonic clocks and no disk writes. */
public final class ProgressTimers {
    private static final Engine ENGINE = new Engine(System::nanoTime);

    private ProgressTimers() { }

    public static long remainingMillis(ClientConfig config, int index) { return ENGINE.remainingMillis(config, index); }
    public static String formattedTime(ClientConfig config, int index) { return ENGINE.formattedTime(config, index); }
    public static boolean isRunning(ClientConfig config, int index) { return ENGINE.isRunning(config, index); }
    public static boolean isExpired(ClientConfig config, int index) { return remainingMillis(config, index) == 0; }
    public static void start(ClientConfig config, int index) { ENGINE.start(config, index); }
    public static void pause(ClientConfig config, int index) { ENGINE.pause(config, index); }
    public static void reset(ClientConfig config, int index) { ENGINE.reset(config, index); }
    public static void setDurationSeconds(ClientConfig config, int index, int duration) {
        ENGINE.setDurationSeconds(config, index, duration);
    }
    public static void tick(ClientConfig config) { ENGINE.tick(config); }
    public static void captureAndPause(ClientConfig config) { ENGINE.captureAndPause(config); }

    /** Package-visible clock injection keeps timing tests independent of real waits. */
    static final class Engine {
        private final LongSupplier nanoTime;
        // Clock values never retain the settings keys, allowing discarded configurations to be collected.
        private final Map<ProgressTimerSettings, ClockState> running = new WeakHashMap<>();

        Engine(LongSupplier nanoTime) { this.nanoTime = Objects.requireNonNull(nanoTime); }

        synchronized long remainingMillis(ClientConfig config, int index) {
            ProgressTimerSettings timer = timer(config, index);
            ClockState state = running.get(timer);
            if (state == null) return timer.remainingMillis;
            long elapsedMillis = Math.max(0, (nanoTime.getAsLong() - state.startedNanos()) / 1_000_000L);
            timer.remainingMillis = Math.max(0, state.remainingAtStart() - elapsedMillis);
            if (timer.remainingMillis == 0) running.remove(timer);
            return timer.remainingMillis;
        }

        synchronized String formattedTime(ClientConfig config, int index) {
            long seconds = (remainingMillis(config, index) + 999) / 1000;
            return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        }

        synchronized boolean isRunning(ClientConfig config, int index) {
            remainingMillis(config, index);
            return running.containsKey(timer(config, index));
        }

        synchronized void start(ClientConfig config, int index) {
            ProgressTimerSettings timer = timer(config, index);
            remainingMillis(config, index);
            if (running.containsKey(timer)) return;
            if (timer.remainingMillis == 0) timer.remainingMillis = timer.durationSeconds * 1000L;
            running.put(timer, new ClockState(nanoTime.getAsLong(), timer.remainingMillis));
        }

        synchronized void pause(ClientConfig config, int index) {
            remainingMillis(config, index);
            running.remove(timer(config, index));
        }

        synchronized void reset(ClientConfig config, int index) {
            ProgressTimerSettings timer = timer(config, index);
            running.remove(timer);
            timer.remainingMillis = timer.durationSeconds * 1000L;
        }

        synchronized void setDurationSeconds(ClientConfig config, int index, int duration) {
            ProgressTimerSettings timer = timer(config, index);
            timer.durationSeconds = Math.clamp(duration,
                    ProgressTimerSettings.MIN_DURATION_SECONDS, ProgressTimerSettings.MAX_DURATION_SECONDS);
            reset(config, index);
        }

        synchronized void tick(ClientConfig config) {
            for (int i = 0; i < 3; i++) remainingMillis(config, i);
        }

        synchronized void captureAndPause(ClientConfig config) {
            for (int i = 0; i < 3; i++) pause(config, i);
        }

        private ProgressTimerSettings timer(ClientConfig config, int index) {
            Objects.checkIndex(index, 3);
            Objects.requireNonNull(config);
            if (config.progressBar == null) config.sanitize();
            else config.progressBar.sanitize();
            return config.progressBar.timers[index];
        }

        private record ClockState(long startedNanos, long remainingAtStart) { }
    }
}
