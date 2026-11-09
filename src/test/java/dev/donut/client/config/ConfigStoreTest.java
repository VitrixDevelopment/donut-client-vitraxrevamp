package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTest {
    @TempDir Path directory;

    @Test void missingFileLoadsDefaultsWithoutCreatingAFile() {
        Path file = directory.resolve("donut.json");
        assertSafeDefaults(new ConfigStore(file).load());
        assertFalse(Files.exists(file));
    }

    @Test void settingsSurviveSaveAndReloadAndExistingFilesCanBeReplaced() {
        Path file = directory.resolve("nested/donut.json");
        ConfigStore store = new ConfigStore(file);
        store.save(new ClientConfig());
        ClientConfig config = new ClientConfig();
        config.fullbright.enabled = true;
        config.fullbright.favorite = true;
        config.freecam.speed = 2.5;
        config.freecam.boostMultiplier = 4;
        config.accentColor = 0x27ADC9;
        config.clickGuiParticles = false;
        config.showHud = false;
        store.save(config);
        ClientConfig loaded = store.load();
        assertTrue(loaded.fullbright.enabled);
        assertTrue(loaded.fullbright.favorite);
        assertEquals(2.5, loaded.freecam.speed);
        assertEquals(4, loaded.freecam.boostMultiplier);
        assertEquals(config.accentColor, loaded.accentColor);
        assertFalse(loaded.clickGuiParticles);
        assertFalse(loaded.showHud);
        assertFalse(Files.exists(file.resolveSibling("donut.json.tmp")));
    }

    @Test void malformedFileIsPreservedBeforeDefaultsAreSaved() throws IOException {
        Path file = directory.resolve("donut.json");
        String damaged = "{broken json";
        Files.writeString(file, damaged);
        ConfigStore store = new ConfigStore(file);
        ClientConfig recovered = store.load();
        assertSafeDefaults(recovered);
        List<Path> backups = backups();
        assertEquals(1, backups.size());
        assertEquals(damaged, Files.readString(backups.getFirst()));
        assertEquals(damaged, Files.readString(file));
        store.save(recovered);
        assertSafeDefaults(store.load());
        assertEquals(damaged, Files.readString(backups.getFirst()));
        assertEquals(1, backups().size());
    }

    @Test void jsonNullIsRecoveredAndBackedUp() throws IOException {
        Path file = directory.resolve("donut.json");
        Files.writeString(file, "null");
        assertSafeDefaults(new ConfigStore(file).load());
        assertEquals(1, backups().size());
        assertEquals("null", Files.readString(backups().getFirst()));
    }

    @Test void removedModuleSettingsAreIgnoredAndDroppedWhenSaving() throws IOException {
        Path file = directory.resolve("donut.json");
        Files.writeString(file, """
                {"storage":{"enabled":true},"spawner":{"enabled":true},
                 "futureOption":true,"fullbright":{"enabled":true,"favorite":true}}
                """);
        ConfigStore store = new ConfigStore(file);
        ClientConfig loaded = store.load();
        assertTrue(loaded.fullbright.enabled);
        assertTrue(loaded.fullbright.favorite);
        store.save(loaded);
        String saved = Files.readString(file);
        assertFalse(saved.contains("\"storage\""));
        assertFalse(saved.contains("\"spawner\""));
        assertTrue(backups().isEmpty());
    }

    @Test void editedJsonIsSanitizedWithoutDiscardingValidSettings() throws IOException {
        Path file = directory.resolve("donut.json");
        Files.writeString(file, """
                {"accentColor":-1,"showHud":false,"freecam":{"speed":10000,"boostMultiplier":-2},
                 "fullbright":{"enabled":true},"clickGuiStyle":null,"chunkFinder":null}
                """);
        ClientConfig loaded = new ConfigStore(file).load();
        assertEquals(0xFFFFFF, loaded.accentColor);
        assertFalse(loaded.showHud);
        assertEquals(5, loaded.freecam.speed);
        assertEquals(1, loaded.freecam.boostMultiplier);
        assertTrue(loaded.fullbright.enabled);
        assertNotNull(loaded.clickGuiStyle);
        assertNotNull(loaded.chunkFinder);
        assertTrue(backups().isEmpty());
    }

    @Test void savingNonFiniteValuesProducesReadableFiniteJson() throws IOException {
        Path file = directory.resolve("donut.json");
        ClientConfig config = new ClientConfig();
        config.freecam.speed = Double.NaN;
        config.freecam.boostMultiplier = Double.POSITIVE_INFINITY;
        config.clickGuiStyle.scale = Double.NEGATIVE_INFINITY;
        assertDoesNotThrow(() -> new ConfigStore(file).save(config));
        ClientConfig loaded = new ConfigStore(file).load();
        assertEquals(1, loaded.freecam.speed);
        assertEquals(3, loaded.freecam.boostMultiplier);
        assertEquals(1, loaded.clickGuiStyle.scale);
        String saved = Files.readString(file);
        assertFalse(saved.contains("NaN"));
        assertFalse(saved.contains("Infinity"));
    }

    private List<Path> backups() throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().startsWith("donut.json.invalid-")).toList();
        }
    }

    private static void assertSafeDefaults(ClientConfig config) {
        assertNotNull(config.freecam);
        assertNotNull(config.fullbright);
        assertNotNull(config.chunkFinder);
        assertFalse(config.freecam.enabled);
        assertFalse(config.fullbright.enabled);
        assertTrue(config.clickGuiParticles);
        assertEquals(1, config.freecam.speed);
    }
}
