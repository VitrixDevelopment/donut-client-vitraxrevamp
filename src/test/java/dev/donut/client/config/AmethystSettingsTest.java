package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AmethystSettingsTest {
    @TempDir Path directory;

    @Test void olderConfigurationsGainAnOptInScanner() throws Exception {
        Path file = directory.resolve("old.json");
        for (String json : new String[]{"{}", "{\"amethyst\":null}", "{\"amethyst\":{}}"}) {
            Files.writeString(file,json);
            var settings = new ConfigStore(file).load().amethyst;
            assertFalse(settings.enabled);
            assertFalse(settings.favorite);
            assertEquals("Bypass",settings.method);
            assertEquals(12,settings.minCells);
            assertFalse(settings.tracer);
        }
    }

    @Test void allReferenceMethodsAndPreferencesPersist() {
        var store = new ConfigStore(directory.resolve("amethyst.json"));
        var config = new ClientConfig();
        for (String method : AmethystSettings.METHODS) {
            config.amethyst.enabled = true;
            config.amethyst.favorite = true;
            config.amethyst.method = method;
            config.amethyst.minCells = 57;
            config.amethyst.tracer = true;
            store.save(config);
            var loaded = store.load().amethyst;
            assertTrue(loaded.enabled && loaded.favorite && loaded.tracer);
            assertEquals(method,loaded.method);
            assertEquals(57,loaded.minCells);
        }
    }

    @Test void invalidValuesRecoverWithoutLosingPreferences() {
        var settings = new AmethystSettings();
        settings.method = null;
        settings.minCells = -100;
        settings.tracer = true;
        settings.sanitize();
        assertEquals("Bypass",settings.method);
        assertEquals(1,settings.minCells);
        assertTrue(settings.tracer);
        settings.method = "future mode";
        settings.minCells = Integer.MAX_VALUE;
        settings.sanitize();
        assertEquals("Bypass",settings.method);
        assertEquals(100,settings.minCells);
    }
}
