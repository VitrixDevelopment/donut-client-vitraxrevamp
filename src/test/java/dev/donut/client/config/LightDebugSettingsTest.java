package dev.donut.client.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LightDebugSettingsTest {
    @TempDir Path directory;
    @Test void oldAndMissingSettingsUseSpectreDefaults() throws Exception {
        Path file=directory.resolve("settings.json");
        for(String json:new String[]{"{}","{\"lightDebug\":null}"}) {
            Files.writeString(file,json);var value=new ConfigStore(file).load().lightDebug;
            assertFalse(value.enabled);assertEquals(8,value.radius);assertEquals(5,value.minLight);assertTrue(value.denoise);assertEquals(0x80FF0000,value.color);
        }
    }
    @Test void boundedValuesAndAlphaPersistIndependently() throws Exception {
        Path file=directory.resolve("settings.json");var config=new ClientConfig();
        config.lightDebug.enabled=true;config.lightDebug.favorite=true;config.lightDebug.radius=500;config.lightDebug.minLight=-20;config.lightDebug.color=0x5799BBCC;config.lightDebug.denoise=false;
        var store=new ConfigStore(file);store.save(config);var value=store.load().lightDebug;
        assertTrue(value.enabled&&value.favorite);assertEquals(16,value.radius);assertEquals(1,value.minLight);assertEquals(0x5799BBCC,value.color);assertFalse(value.denoise);
    }
}
