package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgressBarSettingsTest {
    @TempDir Path directory;

    @Test
    void appearanceAndPlacementSurviveSaveAndReload() {
        Path path = directory.resolve("donut.json");
        ClientConfig config = new ClientConfig();
        config.progressBar.enabled = true;
        config.progressBar.favorite = true;
        config.progressBar.shinyOutline = false;
        config.progressBar.completedStages = 2;
        config.progressBar.basesFound = 37;
        config.progressBar.scale = 1.65;
        config.progressBar.x = 0.84;
        config.progressBar.y = 0.73;

        new ConfigStore(path).save(config);
        ProgressBarSettings loaded = new ConfigStore(path).load().progressBar;

        assertTrue(loaded.enabled);
        assertTrue(loaded.favorite);
        assertFalse(loaded.shinyOutline);
        assertEquals(2, loaded.completedStages);
        assertEquals(37, loaded.basesFound);
        assertEquals(1.65, loaded.scale);
        assertEquals(0.84, loaded.x);
        assertEquals(0.73, loaded.y);
    }

    @Test
    void olderAndNullConfigurationsRestoreTheStreamingDefaults() throws IOException {
        Path path = directory.resolve("donut.json");
        for (String json : new String[] {"{}", "{\"progressBar\":null}", "{\"progressBar\":{}}"}) {
            Files.writeString(path, json);

            ProgressBarSettings loaded = new ConfigStore(path).load().progressBar;

            assertNotNull(loaded);
            assertFalse(loaded.enabled);
            assertFalse(loaded.favorite);
            assertTrue(loaded.shinyOutline);
            assertEquals(0, loaded.completedStages);
            assertEquals(0, loaded.basesFound);
            assertEquals(1.0, loaded.scale);
            assertEquals(0.5, loaded.x);
            assertEquals(0.12, loaded.y);
            assertEquals(3, loaded.timers.length);
            for (ProgressTimerSettings timer : loaded.timers) {
                assertEquals(3600, timer.durationSeconds);
                assertEquals(3600000, timer.remainingMillis);
            }
        }
    }

    @Test
    void editedPositionStaysWithinTheVisibleScreenTravel() throws IOException {
        Path path = directory.resolve("donut.json");
        Files.writeString(path, """
                {"progressBar":{"enabled":true,"shinyOutline":false,"x":-4,"y":12}}
                """);

        ProgressBarSettings loaded = new ConfigStore(path).load().progressBar;

        assertTrue(loaded.enabled);
        assertFalse(loaded.shinyOutline);
        assertEquals(0, loaded.x);
        assertEquals(1, loaded.y);
    }

    @Test
    void completedMilestonesAreClampedToTheThreeDisplayedStages() throws IOException {
        Path path = directory.resolve("donut.json");
        for (int invalid : new int[] {-5, 18}) {
            Files.writeString(path, "{\"progressBar\":{\"completedStages\":" + invalid + "}}");

            ProgressBarSettings loaded = new ConfigStore(path).load().progressBar;

            assertEquals(invalid < 0 ? 0 : 3, loaded.completedStages);
        }
    }

    @Test
    void anExistingProgressBarConfigurationAddsTheCounterWithoutResettingMilestones() throws IOException {
        Path path = directory.resolve("donut.json");
        Files.writeString(path, """
                {"progressBar":{"enabled":true,"completedStages":2,"scale":1.5,"x":0.8,"y":0.2}}
                """);

        ProgressBarSettings loaded = new ConfigStore(path).load().progressBar;

        assertEquals(0, loaded.basesFound);
        assertTrue(loaded.enabled);
        assertEquals(2, loaded.completedStages);
        assertEquals(1.5, loaded.scale);
        assertEquals(0.8, loaded.x);
        assertEquals(0.2, loaded.y);
    }

    @Test
    void editedBasesFoundCountClampsToTheSupportedRange() throws IOException {
        Path path = directory.resolve("donut.json");
        for (int invalid : new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            Files.writeString(path, "{\"progressBar\":{\"basesFound\":" + invalid + ",\"completedStages\":2}}");

            ProgressBarSettings loaded = new ConfigStore(path).load().progressBar;

            assertEquals(invalid < 0 ? 0 : 999999, loaded.basesFound);
            assertEquals(2, loaded.completedStages);
        }
    }

    @Test
    void editedScaleClampsToTheSupportedSizeRangeWithoutChangingPlacement() throws IOException {
        Path path = directory.resolve("donut.json");
        for (double invalid : new double[] {-1, 100}) {
            Files.writeString(path, "{\"progressBar\":{\"scale\":" + invalid + ",\"x\":0.7,\"y\":0.4}}");

            ProgressBarSettings loaded = new ConfigStore(path).load().progressBar;

            assertEquals(invalid < 0.5 ? 0.5 : 2.0, loaded.scale);
            assertEquals(0.7, loaded.x);
            assertEquals(0.4, loaded.y);
        }
    }

    @Test
    void nonFinitePositionsAndSizeAreRepairedBeforeSerialization() throws IOException {
        Path path = directory.resolve("donut.json");
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            ClientConfig config = new ClientConfig();
            config.progressBar.x = invalid;
            config.progressBar.y = invalid;
            config.progressBar.scale = invalid;
            config.progressBar.favorite = true;

            assertDoesNotThrow(() -> new ConfigStore(path).save(config));
            ProgressBarSettings loaded = new ConfigStore(path).load().progressBar;

            assertEquals(0.5, loaded.x);
            assertEquals(0.12, loaded.y);
            assertEquals(1.0, loaded.scale);
            assertTrue(loaded.favorite);
            String json = Files.readString(path);
            assertFalse(json.contains("NaN"));
            assertFalse(json.contains("Infinity"));
        }
    }
}
