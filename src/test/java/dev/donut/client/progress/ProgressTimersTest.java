package dev.donut.client.progress;

import static org.junit.jupiter.api.Assertions.*;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.config.ConfigStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgressTimersTest {
    @TempDir Path directory;
    private final AtomicLong nanos = new AtomicLong();
    private final ProgressTimers.Engine timers = new ProgressTimers.Engine(nanos::get);
    private final ClientConfig config = new ClientConfig();

    @Test
    void oneHourExpiresAfterExactly3600RealSecondsEvenWithoutTicks() {
        timers.start(config, 0);
        advanceMillis(3_599_000);

        assertEquals(1000, timers.remainingMillis(config, 0));
        assertEquals("00:00:01", timers.formattedTime(config, 0));
        assertTrue(timers.isRunning(config, 0));

        advanceMillis(999);
        assertEquals(1, timers.remainingMillis(config, 0));
        assertEquals("00:00:01", timers.formattedTime(config, 0));
        advanceMillis(1);
        assertEquals(0, timers.remainingMillis(config, 0));
        assertEquals("00:00:00", timers.formattedTime(config, 0));
        assertFalse(timers.isRunning(config, 0));
    }

    @Test
    void timersAndConfigurationsRunIndependently() {
        ClientConfig another = new ClientConfig();
        timers.start(config, 0);
        advanceMillis(1000);
        timers.start(config, 1);
        timers.start(another, 0);
        advanceMillis(2000);
        timers.pause(config, 0);
        advanceMillis(3000);
        timers.tick(config);

        assertEquals(3_597_000, timers.remainingMillis(config, 0));
        assertEquals(3_595_000, timers.remainingMillis(config, 1));
        assertEquals(3_600_000, timers.remainingMillis(config, 2));
        assertEquals(3_595_000, timers.remainingMillis(another, 0));
        assertFalse(timers.isRunning(config, 0));
        assertTrue(timers.isRunning(config, 1));
        assertFalse(timers.isRunning(config, 2));
        assertTrue(timers.isRunning(another, 0));
    }

    @Test
    void pauseAndResumePreserveMillisecondsAndIgnoreTimeSpentPaused() {
        timers.setDurationSeconds(config, 0, 60);
        timers.start(config, 0);
        advanceMillis(12345);
        timers.pause(config, 0);

        assertEquals(47655, timers.remainingMillis(config, 0));
        advanceMillis(3_600_000);
        assertEquals(47655, timers.remainingMillis(config, 0));
        timers.start(config, 0);
        advanceMillis(1234);
        assertEquals(46421, timers.remainingMillis(config, 0));
    }

    @Test
    void repeatedStartDoesNotExtendRunningTimeAndStartingExpiredRestartsTheDuration() {
        timers.setDurationSeconds(config, 0, 2);
        timers.start(config, 0);
        advanceMillis(750);
        timers.start(config, 0);
        advanceMillis(1250);
        assertEquals(0, timers.remainingMillis(config, 0));
        assertFalse(timers.isRunning(config, 0));

        timers.start(config, 0);

        assertTrue(timers.isRunning(config, 0));
        assertEquals(2000, timers.remainingMillis(config, 0));
        advanceMillis(500);
        assertEquals(1500, timers.remainingMillis(config, 0));
    }

    @Test
    void changingDurationAndResettingStopOnlyTheSelectedTimer() {
        timers.start(config, 0);
        timers.start(config, 1);
        advanceMillis(1234);
        timers.setDurationSeconds(config, 0, 90);

        assertEquals(90000, timers.remainingMillis(config, 0));
        assertFalse(timers.isRunning(config, 0));
        assertTrue(timers.isRunning(config, 1));
        timers.start(config, 0);
        advanceMillis(500);
        timers.reset(config, 0);

        assertEquals(90000, timers.remainingMillis(config, 0));
        assertFalse(timers.isRunning(config, 0));
        assertEquals(3_598_266, timers.remainingMillis(config, 1));
    }

    @Test
    void durationEditsRespectBoundsAndFormattingIncludesEveryHour() {
        timers.setDurationSeconds(config, 0, Integer.MIN_VALUE);
        assertEquals(1000, timers.remainingMillis(config, 0));
        assertEquals("00:00:01", timers.formattedTime(config, 0));
        timers.setDurationSeconds(config, 0, Integer.MAX_VALUE);
        assertEquals(359_999_000, timers.remainingMillis(config, 0));
        assertEquals("99:59:59", timers.formattedTime(config, 0));
        timers.setDurationSeconds(config, 0, 3600);
        assertEquals("01:00:00", timers.formattedTime(config, 0));
    }

    @Test
    void shutdownCapturesRemainingValuesAndReloadAlwaysStartsPaused() throws IOException {
        ConfigStore store = new ConfigStore(directory.resolve("donut.json"));
        timers.start(config, 0);
        timers.start(config, 1);
        advanceMillis(12345);
        timers.captureAndPause(config);
        store.save(config);
        ClientConfig loaded = store.load();
        advanceMillis(3_600_000);

        for (int i = 0; i < 3; i++) {
            assertFalse(timers.isRunning(config, i));
            assertFalse(timers.isRunning(loaded, i));
        }
        assertEquals(3_587_655, timers.remainingMillis(loaded, 0));
        assertEquals(3_587_655, timers.remainingMillis(loaded, 1));
        assertEquals(3_600_000, timers.remainingMillis(loaded, 2));
        assertFalse(Files.readString(directory.resolve("donut.json")).contains("running"));
        timers.start(loaded, 0);
        advanceMillis(155);
        assertEquals(3_587_500, timers.remainingMillis(loaded, 0));
    }

    @Test
    void savingATickedLiveCountdownDoesNotPauseItOrRestoreRunningOnReload() {
        ConfigStore store = new ConfigStore(directory.resolve("donut.json"));
        timers.start(config, 0);
        advanceMillis(9876);
        timers.tick(config);
        store.save(config);
        ClientConfig loaded = store.load();
        advanceMillis(1000);

        assertTrue(timers.isRunning(config, 0));
        assertFalse(timers.isRunning(loaded, 0));
        assertEquals(3_590_124, timers.remainingMillis(loaded, 0));
        assertEquals(3_589_124, timers.remainingMillis(config, 0));
    }

    private void advanceMillis(long elapsed) { nanos.addAndGet(elapsed * 1_000_000L); }
}
