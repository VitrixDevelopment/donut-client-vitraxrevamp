package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NameHiderSettingsTest {
    @TempDir Path directory;

    @Test
    void missingAndOlderConfigurationsKeepNameHidingOptIn() throws IOException {
        Path path = directory.resolve("donut.json");
        ConfigStore store = new ConfigStore(path);
        assertDefaults(store.load().nameHider);
        assertFalse(Files.exists(path));

        for (String json : new String[] {"{}", "{\"nameHider\":null}", "{\"nameHider\":{}}"}) {
            Files.writeString(path, json);
            assertDefaults(store.load().nameHider);
        }

        ClientConfig config = new ClientConfig();
        config.nameHider = null;
        config.sanitize();
        assertDefaults(config.nameHider);
    }

    @Test
    void activationAndFavoritePersistWithoutChangingOtherStreamingSettings() {
        ConfigStore store = new ConfigStore(directory.resolve("nested/donut.json"));
        ClientConfig config = new ClientConfig();
        config.nameHider.enabled = true;
        config.nameHider.favorite = true;
        config.progressBar.enabled = true;
        config.progressBar.completedStages = 2;
        config.progressBar.scale = 1.6;

        store.save(config);
        ClientConfig loaded = store.load();

        assertTrue(loaded.nameHider.enabled);
        assertTrue(loaded.nameHider.favorite);
        assertTrue(config.nameHider.enabled, "Saving must not change the live module state");
        assertTrue(loaded.progressBar.enabled);
        assertEquals(2, loaded.progressBar.completedStages);
        assertEquals(1.6, loaded.progressBar.scale);

        loaded.nameHider.enabled = false;
        loaded.nameHider.favorite = false;
        store.save(loaded);

        ClientConfig disabled = store.load();
        assertDefaults(disabled.nameHider);
        assertTrue(disabled.progressBar.enabled);
        assertEquals(2, disabled.progressBar.completedStages);
    }

    private static void assertDefaults(NameHiderSettings settings) {
        assertNotNull(settings);
        assertFalse(settings.enabled);
        assertFalse(settings.favorite);
        assertFalse(settings.blurScoreboard);
    }

    @Test
    void fullScoreboardBlurIsOptInAndPersistsWhenTheModuleIsDisabled() {
        ConfigStore store = new ConfigStore(directory.resolve("privacy.json"));
        ClientConfig config = new ClientConfig();
        config.nameHider.enabled = true;
        config.nameHider.blurScoreboard = true;
        store.save(config);
        ClientConfig loaded = store.load();
        assertTrue(loaded.nameHider.enabled);
        assertTrue(loaded.nameHider.blurScoreboard);
        loaded.nameHider.enabled = false;
        store.save(loaded);
        assertFalse(store.load().nameHider.enabled);
        assertTrue(store.load().nameHider.blurScoreboard);
    }
}
