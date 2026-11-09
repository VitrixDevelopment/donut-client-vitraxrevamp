package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkFinderSettingsTest {
    @TempDir Path directory;

    @Test
    void defaultsAndOlderConfigsStartDisabledWithAllIndicatorsAvailable() throws IOException {
        Path path = directory.resolve("donut.json");
        assertDefaults(new ConfigStore(path).load().chunkFinder);
        assertFalse(Files.exists(path));
        for (String json : new String[] {"{}", "{\"chunkFinder\":null}"}) {
            Files.writeString(path, json);
            assertDefaults(new ConfigStore(path).load().chunkFinder);
        }
        ClientConfig config = new ClientConfig();
        config.chunkFinder = null;
        config.sanitize();
        assertDefaults(config.chunkFinder);
    }

    @Test
    void boundsClampWithoutChangingIndicatorPreferences() {
        ChunkFinderSettings low = new ChunkFinderSettings();
        low.simulationDistance = Integer.MIN_VALUE;
        low.sensitivity = Integer.MIN_VALUE;
        low.alpha = Integer.MIN_VALUE;
        low.color = 0xAB123456;
        low.enabled = true;
        low.favorite = true;
        low.smartAdjustment = false;
        low.kelp = false;
        low.caveVines = false;
        low.vines = false;
        low.amethyst = false;
        low.bamboo = false;
        low.cocoa = false;
        low.beeNest = false;
        ChunkFinderSettings high = new ChunkFinderSettings();
        high.simulationDistance = Integer.MAX_VALUE;
        high.sensitivity = Integer.MAX_VALUE;
        high.alpha = Integer.MAX_VALUE;
        high.color = -1;

        low.sanitize();
        high.sanitize();

        assertEquals(2, low.simulationDistance);
        assertEquals(1, low.sensitivity);
        assertEquals(0, low.alpha);
        assertEquals(0x123456, low.color);
        assertEquals(16, high.simulationDistance);
        assertEquals(20, high.sensitivity);
        assertEquals(255, high.alpha);
        assertEquals(0xFFFFFF, high.color);
        assertTrue(low.enabled);
        assertTrue(low.favorite);
        assertFalse(low.smartAdjustment);
        assertAllIndicatorsDisabled(low);
    }

    @Test
    void everySettingSurvivesSaveAndReload() {
        ConfigStore store = new ConfigStore(directory.resolve("nested/donut.json"));
        ClientConfig config = new ClientConfig();
        ChunkFinderSettings settings = config.chunkFinder;
        settings.enabled = true;
        settings.favorite = true;
        settings.simulationDistance = 12;
        settings.sensitivity = 5;
        settings.smartAdjustment = false;
        settings.alpha = 173;
        settings.color = 0x27ADC9;
        settings.kelp = false;
        settings.caveVines = false;
        settings.vines = false;
        settings.amethyst = false;
        settings.bamboo = false;
        settings.cocoa = false;
        settings.beeNest = false;

        store.save(config);
        ChunkFinderSettings loaded = store.load().chunkFinder;

        assertTrue(loaded.enabled);
        assertTrue(loaded.favorite);
        assertEquals(12, loaded.simulationDistance);
        assertEquals(5, loaded.sensitivity);
        assertFalse(loaded.smartAdjustment);
        assertEquals(173, loaded.alpha);
        assertEquals(0x27ADC9, loaded.color);
        assertAllIndicatorsDisabled(loaded);
    }

    @Test
    void handEditedSettingsAreSanitizedAndMissingFieldsKeepDefaults() throws IOException {
        Path path = directory.resolve("donut.json");
        Files.writeString(path, """
                {"chunkFinder":{"enabled":true,"simulationDistance":10000,
                  "sensitivity":-5,"alpha":999,"color":-1,"amethyst":false}}
                """);

        ChunkFinderSettings loaded = new ConfigStore(path).load().chunkFinder;

        assertTrue(loaded.enabled);
        assertEquals(16, loaded.simulationDistance);
        assertEquals(1, loaded.sensitivity);
        assertEquals(255, loaded.alpha);
        assertEquals(0xFFFFFF, loaded.color);
        assertFalse(loaded.amethyst);
        assertFalse(loaded.favorite);
        assertTrue(loaded.smartAdjustment);
        assertTrue(loaded.kelp);
        assertTrue(loaded.caveVines);
        assertTrue(loaded.vines);
        assertTrue(loaded.bamboo);
        assertTrue(loaded.cocoa);
        assertTrue(loaded.beeNest);
    }

    private static void assertDefaults(ChunkFinderSettings settings) {
        assertNotNull(settings);
        assertFalse(settings.enabled);
        assertFalse(settings.favorite);
        assertEquals(5, settings.simulationDistance);
        assertEquals(6, settings.sensitivity);
        assertTrue(settings.smartAdjustment);
        assertEquals(255, settings.alpha);
        assertEquals(0xC447FF, settings.color);
        assertTrue(settings.kelp);
        assertTrue(settings.caveVines);
        assertTrue(settings.vines);
        assertTrue(settings.amethyst);
        assertTrue(settings.bamboo);
        assertTrue(settings.cocoa);
        assertTrue(settings.beeNest);
    }

    private static void assertAllIndicatorsDisabled(ChunkFinderSettings settings) {
        assertFalse(settings.kelp);
        assertFalse(settings.caveVines);
        assertFalse(settings.vines);
        assertFalse(settings.amethyst);
        assertFalse(settings.bamboo);
        assertFalse(settings.cocoa);
        assertFalse(settings.beeNest);
    }

    @Test void spectreSettingsAndCategoryColorsPersist() {
        var config = new ClientConfig();
        var settings = config.chunkFinder;
        settings.method = "ANBS+ Scan";
        settings.objectRange = 192;
        settings.inspectPanel = false;
        settings.kelpLength = 23;
        settings.vineLength = 22;
        settings.caveVineLength = 31;
        settings.bambooLength = 19;
        settings.matureClusters = 42;
        settings.maturePercent = 85;
        settings.clueThreshold = 7;
        settings.objects.put("hoppers", false);
        settings.objectColors.put("machines", 0x123456);
        var store = new ConfigStore(directory.resolve("spectre.json"));
        store.save(config);
        var loaded = store.load().chunkFinder;
        assertEquals("ANBS+ Scan", loaded.method);
        assertEquals(192, loaded.objectRange);
        assertFalse(loaded.inspectPanel);
        assertEquals(23, loaded.kelpLength);
        assertEquals(22, loaded.vineLength);
        assertEquals(31, loaded.caveVineLength);
        assertEquals(19, loaded.bambooLength);
        assertEquals(42, loaded.matureClusters);
        assertEquals(85, loaded.maturePercent);
        assertEquals(7, loaded.clueThreshold);
        assertFalse(loaded.categoryEnabled(dev.donut.client.chunkfinder.SuspectObjects.Category.HOPPERS));
        assertEquals(0xFF123456, loaded.categoryColor(dev.donut.client.chunkfinder.SuspectObjects.Category.MACHINES));
    }

    @Test void olderSettingsKeepTheirActivationFiltersAndGainObjectDefaults() throws IOException {
        Path path = directory.resolve("legacy.json");
        Files.writeString(path, "{\"chunkFinder\":{\"enabled\":true,\"favorite\":true,\"sensitivity\":4,\"alpha\":80,\"kelp\":false,\"objects\":null,\"objectColors\":null}}");
        var loaded = new ConfigStore(path).load().chunkFinder;
        assertTrue(loaded.enabled);
        assertTrue(loaded.favorite);
        assertEquals(4, loaded.sensitivity);
        assertEquals(80, loaded.alpha);
        assertFalse(loaded.kelp);
        assertEquals("Classic", loaded.method);
        for (var category : dev.donut.client.chunkfinder.SuspectObjects.Category.values()) {
            assertTrue(loaded.categoryEnabled(category));
            assertEquals(category.color, loaded.categoryColor(category));
        }
    }

    @Test void invalidSpectreSettingsRecoverWithoutLosingCategoryChoices() {
        var settings = new ChunkFinderSettings();
        settings.method = null;
        settings.objectRange = -1;
        settings.clueThreshold = 100;
        settings.kelpLength = 999;
        settings.vineLength = -1;
        settings.caveVineLength = 999;
        settings.bambooLength = 999;
        settings.matureClusters = 999;
        settings.maturePercent = -1;
        settings.objects.put("hoppers", false);
        settings.objectColors.put("machines", 0xAA123456);
        settings.sanitize();
        assertEquals("Classic", settings.method);
        assertEquals(16, settings.objectRange);
        assertEquals(8, settings.clueThreshold);
        assertEquals(64, settings.kelpLength);
        assertEquals(2, settings.vineLength);
        assertEquals(64, settings.caveVineLength);
        assertEquals(32, settings.bambooLength);
        assertEquals(128, settings.matureClusters);
        assertEquals(10, settings.maturePercent);
        assertFalse(settings.categoryEnabled(dev.donut.client.chunkfinder.SuspectObjects.Category.HOPPERS));
        assertEquals(0xFF123456, settings.categoryColor(dev.donut.client.chunkfinder.SuspectObjects.Category.MACHINES));
    }
}
