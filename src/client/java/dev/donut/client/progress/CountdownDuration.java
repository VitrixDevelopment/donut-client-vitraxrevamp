package dev.donut.client.progress;

import dev.donut.client.config.ProgressTimerSettings;
import java.util.Locale;
import java.util.OptionalInt;

/** The explicit hours:minutes:seconds format used by the timer controls. */
public final class CountdownDuration {
    private CountdownDuration() { }

    public static OptionalInt parse(String value) {
        if (value == null || !value.trim().matches("[0-9]{1,2}:[0-9]{2}:[0-9]{2}")) return OptionalInt.empty();
        String[] parts = value.trim().split(":");
        int hours = Integer.parseInt(parts[0]);
        int minutes = Integer.parseInt(parts[1]);
        int seconds = Integer.parseInt(parts[2]);
        int total = hours * 3600 + minutes * 60 + seconds;
        return minutes < 60 && seconds < 60 && total > 0 && total <= ProgressTimerSettings.MAX_DURATION_SECONDS
                ? OptionalInt.of(total) : OptionalInt.empty();
    }

    public static String format(int seconds) {
        int value = Math.clamp(seconds, 0, ProgressTimerSettings.MAX_DURATION_SECONDS);
        return String.format(Locale.ROOT, "%02d:%02d:%02d", value / 3600, value / 60 % 60, value % 60);
    }
}
