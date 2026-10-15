package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutoBuilderSettingsTest {
    @TempDir Path directory;

    @Test void preferencesPersistWithoutSavingBuildActivationOrPosition() throws IOException {
        Path path = directory.resolve("donut.json");
        ClientConfig config = new ClientConfig();
        config.autoBuilder.favorite = true;
        config.autoBuilder.previewOpacity = .53;
        config.autoBuilder.buildUp = false;
        config.autoBuilder.autoShulker = false;
        config.autoBuilder.autoBuy = false;
        config.autoBuilder.maxAhPrice = 900;
        ConfigStore store = new ConfigStore(path);
        store.save(config);
        ClientConfig loaded = store.load();
        assertTrue(loaded.autoBuilder.favorite);
        assertEquals(.53, loaded.autoBuilder.previewOpacity);
        assertFalse(loaded.autoBuilder.buildUp);
        assertFalse(loaded.autoBuilder.autoShulker);
        assertFalse(loaded.autoBuilder.autoBuy);
        assertEquals(900, loaded.autoBuilder.maxAhPrice);
        var saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("autoBuilder");
        assertEquals(6, saved.size(), "Only presentation and building preferences may persist");
        assertFalse(saved.has("enabled"));
        assertFalse(saved.has("origin"));
    }

    @Test void existingAndNullConfigurationsRestoreUsablePreferences() throws IOException {
        Path path = directory.resolve("donut.json");
        for (String json : new String[]{"{}", "{\"autoBuilder\":null}"}) {
            Files.writeString(path, json);
            AutoBuilderSettings settings = new ConfigStore(path).load().autoBuilder;
            assertNotNull(settings);
            assertFalse(settings.favorite);
            assertEquals(.28, settings.previewOpacity);
            assertTrue(settings.buildUp);
            assertTrue(settings.autoShulker);
            assertTrue(settings.autoBuy);
            assertEquals(250, settings.maxAhPrice);
        }
    }

    @Test void invalidOpacityCannotBreakSavingOrHideTheGhostCompletely() throws IOException {
        Path path = directory.resolve("donut.json");
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            ClientConfig config = new ClientConfig();
            config.autoBuilder.previewOpacity = value;
            new ConfigStore(path).save(config);
            assertEquals(.28, new ConfigStore(path).load().autoBuilder.previewOpacity);
        }
        Files.writeString(path, "{\"autoBuilder\":{\"previewOpacity\":-12}}");
        assertEquals(.08, new ConfigStore(path).load().autoBuilder.previewOpacity);
        Files.writeString(path, "{\"autoBuilder\":{\"previewOpacity\":12}}");
        assertEquals(.8, new ConfigStore(path).load().autoBuilder.previewOpacity);
    }

    @Test void invalidPricesFallBackAndStayInsideSaneBounds() throws IOException {
        Path path = directory.resolve("donut.json");
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            ClientConfig config = new ClientConfig();
            config.autoBuilder.maxAhPrice = value;
            new ConfigStore(path).save(config);
            assertEquals(250, new ConfigStore(path).load().autoBuilder.maxAhPrice);
        }
        Files.writeString(path, "{\"autoBuilder\":{\"maxAhPrice\":0}}");
        assertEquals(1, new ConfigStore(path).load().autoBuilder.maxAhPrice);
        Files.writeString(path, "{\"autoBuilder\":{\"maxAhPrice\":99999999}}");
        assertEquals(1_000_000, new ConfigStore(path).load().autoBuilder.maxAhPrice);
    }
}
