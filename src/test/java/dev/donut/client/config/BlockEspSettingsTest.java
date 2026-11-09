package dev.donut.client.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BlockEspSettingsTest {
    @TempDir Path directory;

    @Test void missingAndNullPreferencesUseTheRequestedSeedAndBothTargets() throws Exception {
        Path file = directory.resolve("settings.json");
        for (String json : new String[] {"{}", "{\"blockEsp\":null}"}) {
            Files.writeString(file, json);
            var value = new ConfigStore(file).load().blockEsp;
            assertEquals("6608149111735331168", value.seed);
            assertTrue(value.emerald && value.coal);
            assertFalse(value.enabled);
        }
    }

    @Test void full64BitSeedAndIndependentPreferencesSurviveJson() throws Exception {
        var config = new ClientConfig();
        config.blockEsp.seed = "6608149111735331168";
        config.blockEsp.enabled = config.blockEsp.favorite = config.blockEsp.tracers = true;
        config.blockEsp.coal = false;
        config.blockEsp.radius = 999;
        config.blockEsp.opacity = -1;
        config.blockEsp.emeraldColor = 0xAA123456;
        var store = new ConfigStore(directory.resolve("settings.json"));
        store.save(config);
        var value = store.load().blockEsp;
        assertEquals(6608149111735331168L, BlockEspSettings.parseSeed(value.seed));
        assertTrue(value.enabled && value.favorite && value.tracers && value.emerald);
        assertFalse(value.coal);
        assertEquals(8, value.radius);
        assertEquals(0, value.opacity);
        assertEquals(0x123456, value.emeraldColor);
        assertTrue(Files.readString(directory.resolve("settings.json")).contains("\"6608149111735331168\""));
    }

    @Test void numericValidationCoversSignedLimitsAndRejectsLossyOrMalformedSeeds() {
        assertEquals(Long.MIN_VALUE, BlockEspSettings.parseSeed("-9223372036854775808"));
        assertEquals(Long.MAX_VALUE, BlockEspSettings.parseSeed("9223372036854775807"));
        assertEquals(0, BlockEspSettings.parseSeed(" 0 "));
        for (String invalid : new String[] {"", "-", "9223372036854775808", "-9223372036854775809", "1.2", "6.6E18", "hello", "12 34"})
            assertThrows(IllegalArgumentException.class, () -> BlockEspSettings.parseSeed(invalid), invalid);
        var settings = new BlockEspSettings(); settings.seed = "invalid"; settings.sanitize();
        assertEquals("invalid", settings.seed, "Invalid persisted input must not become another valid seed");
    }
}
