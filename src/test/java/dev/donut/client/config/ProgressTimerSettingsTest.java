package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgressTimerSettingsTest {
    @TempDir Path directory;

    @Test
    void timerDurationsAndPausedRemainingValuesSurviveSaveAndReload() {
        ConfigStore store = new ConfigStore(directory.resolve("donut.json"));
        ClientConfig config = new ClientConfig();
        config.progressBar.timers[0].durationSeconds = 7200;
        config.progressBar.timers[0].remainingMillis = 456789;
        config.progressBar.timers[1].durationSeconds = 90;
        config.progressBar.timers[1].remainingMillis = 0;
        config.progressBar.timers[2].durationSeconds = 359999;
        config.progressBar.timers[2].remainingMillis = 359998123;

        store.save(config);
        ProgressTimerSettings[] loaded = store.load().progressBar.timers;

        assertEquals(7200, loaded[0].durationSeconds);
        assertEquals(456789, loaded[0].remainingMillis);
        assertEquals(90, loaded[1].durationSeconds);
        assertEquals(0, loaded[1].remainingMillis);
        assertEquals(359999, loaded[2].durationSeconds);
        assertEquals(359998123, loaded[2].remainingMillis);
    }

    @Test
    void timerBoundsRepairEditedJsonWithoutChangingOtherProgressValues() throws IOException {
        Path path = directory.resolve("donut.json");
        Files.writeString(path, """
                {"progressBar":{"completedStages":2,"basesFound":37,"timers":[
                  {"durationSeconds":-8,"remainingMillis":-1},
                  {"durationSeconds":2147483647,"remainingMillis":9223372036854775807},
                  {"durationSeconds":25,"remainingMillis":3600000}
                ]}}
                """);

        ProgressBarSettings loaded = new ConfigStore(path).load().progressBar;

        assertEquals(2, loaded.completedStages);
        assertEquals(37, loaded.basesFound);
        assertEquals(1, loaded.timers[0].durationSeconds);
        assertEquals(0, loaded.timers[0].remainingMillis);
        assertEquals(359999, loaded.timers[1].durationSeconds);
        assertEquals(359999000, loaded.timers[1].remainingMillis);
        assertEquals(25, loaded.timers[2].durationSeconds);
        assertEquals(25000, loaded.timers[2].remainingMillis);
    }

    @Test
    void missingNullShortAndLongArraysBecomeThreeIndependentTimers() throws IOException {
        Path path = directory.resolve("donut.json");
        for (String timerJson : new String[] {"null", "[]", "[null]", "[null,null,null,null]"}) {
            Files.writeString(path, "{\"progressBar\":{\"timers\":" + timerJson + "}}");

            ProgressTimerSettings[] loaded = new ConfigStore(path).load().progressBar.timers;

            assertEquals(3, loaded.length);
            for (ProgressTimerSettings timer : loaded) {
                assertNotNull(timer);
                assertEquals(3600, timer.durationSeconds);
                assertEquals(3600000, timer.remainingMillis);
            }
            assertNotSame(loaded[0], loaded[1]);
            assertNotSame(loaded[1], loaded[2]);
        }
    }

    @Test
    void resizingAnEditedArrayPreservesTheExistingTimerValues() throws IOException {
        Path path = directory.resolve("donut.json");
        Files.writeString(path, """
                {"progressBar":{"timers":[{"durationSeconds":75,"remainingMillis":12345}]}}
                """);

        ProgressTimerSettings[] loaded = new ConfigStore(path).load().progressBar.timers;

        assertEquals(3, loaded.length);
        assertEquals(75, loaded[0].durationSeconds);
        assertEquals(12345, loaded[0].remainingMillis);
        assertEquals(3600, loaded[1].durationSeconds);
        assertEquals(3600000, loaded[2].remainingMillis);
    }
}
