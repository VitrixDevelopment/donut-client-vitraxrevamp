package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClickGuiStyleTest {
    @TempDir Path directory;

    @Test void olderConfigurationsKeepTheirModulesAndGainThePresentationDefaults() throws Exception {
        Path file = directory.resolve("old.json");
        Files.writeString(file, "{\"accentColor\":12863487,\"freecam\":{\"favorite\":true,\"speed\":1.7},\"clickGuiStyle\":null}");
        ClientConfig config = new ConfigStore(file).load();
        assertTrue(config.freecam.favorite);
        assertEquals(1.7, config.freecam.speed);
        assertEquals(12863487, config.accentColor);
        assertEquals(1, config.clickGuiStyle.scale);
        assertEquals(.25, config.clickGuiStyle.backgroundDim);
        assertTrue(config.clickGuiStyle.liquidGlass);
    }

    @Test void styleAndModulePreferencesPersistTogether() {
        ConfigStore store = new ConfigStore(directory.resolve("config.json"));
        ClientConfig config = new ClientConfig();
        config.clickGuiStyle.scale = 1.2;
        config.clickGuiStyle.animationSpeed = 0;
        config.clickGuiStyle.cardsColor = 0x223344;
        config.clickGuiStyle.liquidGlass = false;
        config.clickGuiParticles = false;
        config.freecam.favorite = true;
        store.save(config);
        ClientConfig reloaded = store.load();
        assertEquals(1.2, reloaded.clickGuiStyle.scale);
        assertEquals(0, reloaded.clickGuiStyle.animationSpeed);
        assertEquals(0x223344, reloaded.clickGuiStyle.cardsColor);
        assertFalse(reloaded.clickGuiStyle.liquidGlass);
        assertFalse(reloaded.clickGuiParticles);
        assertTrue(reloaded.freecam.favorite);
    }

    @Test void invalidPresentationNumbersCannotBreakLayoutOrAnimation() {
        ClickGuiStyle style = new ClickGuiStyle();
        style.scale = Double.NaN;
        style.backgroundDim = Double.POSITIVE_INFINITY;
        style.animationSpeed = -10;
        style.rounding = 90;
        style.cardsOpacity = -1;
        style.sanitize();
        assertEquals(1, style.scale);
        assertEquals(.25, style.backgroundDim);
        assertEquals(0, style.animationSpeed);
        assertEquals(16, style.rounding);
        assertEquals(.2, style.cardsOpacity);
    }
}
