package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HotbarSettingsTest {
    @TempDir Path directory;

    @Test
    void missingAndOlderConfigurationsKeepHotbarPrivacyOptIn() throws IOException {
        Path path = directory.resolve("donut.json");
        ConfigStore store = new ConfigStore(path);
        assertDefaults(store.load().hotbar);
        assertFalse(Files.exists(path));

        for (String json : new String[] {"{}", "{\"hotbar\":null}", "{\"hotbar\":{}}"}) {
            Files.writeString(path, json);
            assertDefaults(store.load().hotbar);
        }

        ClientConfig config = new ClientConfig();
        config.hotbar = null;
        config.sanitize();
        assertDefaults(config.hotbar);

        Files.writeString(path, "{\"hotbar\":{\"enabled\":true,\"favorite\":true}}");
        ClientConfig previousVersion = store.load();
        assertTrue(previousVersion.hotbar.enabled);
        assertTrue(previousVersion.hotbar.favorite);
        assertEquals(90, previousVersion.hotbar.blurPercent);
    }

    @Test
    void activationFavoriteAndBlurAmountPersistAlongsideOtherStreamingSettings() {
        ConfigStore store = new ConfigStore(directory.resolve("nested/donut.json"));
        ClientConfig config = new ClientConfig();
        config.hotbar.enabled = true;
        config.hotbar.favorite = true;
        config.hotbar.blurPercent = 35;
        config.nameHider.enabled = true;
        config.progressBar.enabled = true;
        config.progressBar.completedStages = 2;

        store.save(config);
        ClientConfig loaded = store.load();

        assertTrue(loaded.hotbar.enabled);
        assertTrue(loaded.hotbar.favorite);
        assertEquals(35, loaded.hotbar.blurPercent);
        assertTrue(config.hotbar.enabled, "Saving must not change the live module state");
        assertTrue(loaded.nameHider.enabled);
        assertTrue(loaded.progressBar.enabled);
        assertEquals(2, loaded.progressBar.completedStages);

        loaded.hotbar.enabled = false;
        loaded.hotbar.favorite = false;
        store.save(loaded);

        ClientConfig disabled = store.load();
        assertFalse(disabled.hotbar.enabled);
        assertFalse(disabled.hotbar.favorite);
        assertEquals(35, disabled.hotbar.blurPercent);
        assertTrue(disabled.nameHider.enabled);
        assertTrue(disabled.progressBar.enabled);
        assertEquals(2, disabled.progressBar.completedStages);
    }

    @Test
    void editedBlurAmountStaysWithinTheSupportedPercentageRange() throws IOException {
        Path path = directory.resolve("donut.json");
        ConfigStore store = new ConfigStore(path);
        for (int value : new int[] {-100, -1, 0, 35, 90, 100, 110, 139, 140, 141, Integer.MAX_VALUE}) {
            Files.writeString(path, "{\"hotbar\":{\"enabled\":true,\"favorite\":true,\"blurPercent\":" + value + "}}");
            ClientConfig loaded = store.load();
            assertEquals(Math.clamp(value, 0, 140), loaded.hotbar.blurPercent);
            assertTrue(loaded.hotbar.enabled);
            assertTrue(loaded.hotbar.favorite);
        }

        ClientConfig config = new ClientConfig();
        config.hotbar.blurPercent = -10;
        config.sanitize();
        assertEquals(0, config.hotbar.blurPercent);
        config.hotbar.blurPercent = 110;
        store.save(config);
        assertEquals(110, store.load().hotbar.blurPercent);
        config.hotbar.blurPercent = 140;
        store.save(config);
        assertEquals(140, store.load().hotbar.blurPercent);
    }

    private static void assertDefaults(HotbarSettings settings) {
        assertNotNull(settings);
        assertFalse(settings.enabled);
        assertFalse(settings.favorite);
        assertEquals(90, settings.blurPercent);
        assertFalse(settings.hideHeldItems);
    }

    @Test
    void emptyHandsAreOptInAndPersistIndependentlyOfActivation() {
        ConfigStore store = new ConfigStore(directory.resolve("hands.json"));
        ClientConfig config = new ClientConfig();
        config.hotbar.enabled = true;
        config.hotbar.hideHeldItems = true;
        store.save(config);
        assertTrue(store.load().hotbar.hideHeldItems);
        config.hotbar.enabled = false;
        store.save(config);
        assertFalse(store.load().hotbar.enabled);
        assertTrue(store.load().hotbar.hideHeldItems);
    }
}
