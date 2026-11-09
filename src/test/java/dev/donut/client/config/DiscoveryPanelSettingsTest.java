package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryPanelSettingsTest {
    @TempDir Path directory;

    @Test void panelColorsAreIndependentOfWorkspacePaletteAndPersist() {
        var config = new ClientConfig();
        config.clickGuiStyle.panelsColor = 0x4AB5FF;
        assertEquals(0x292C3B,config.chunkFinder.panelTopColor);
        assertEquals(0x171A21,config.chunkFinder.panelBottomColor);
        config.chunkFinder.panelTopColor = 0x22223A;
        config.chunkFinder.panelBottomColor = 0x11121C;
        config.chunkFinder.panelOpacity = .65;
        var store = new ConfigStore(directory.resolve("panel.json"));
        store.save(config);
        var loaded = store.load();
        assertEquals(0x4AB5FF,loaded.clickGuiStyle.panelsColor);
        assertEquals(0x22223A,loaded.chunkFinder.panelTopColor);
        assertEquals(0x11121C,loaded.chunkFinder.panelBottomColor);
        assertEquals(.65,loaded.chunkFinder.panelOpacity);
    }

    @Test void invalidPanelValuesKeepTheMaterialRenderable() {
        var settings = new ChunkFinderSettings();
        settings.panelTopColor = 0xFF123456;
        settings.panelBottomColor = -1;
        settings.panelOpacity = Double.NaN;
        settings.sanitize();
        assertEquals(0x123456,settings.panelTopColor);
        assertEquals(0xFFFFFF,settings.panelBottomColor);
        assertEquals(.96,settings.panelOpacity);
        settings.panelOpacity = -100;
        settings.sanitize();
        assertEquals(.2,settings.panelOpacity);
    }
}
