package dev.donut.client.config;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SoundDetectorSettingsTest {
    @TempDir Path directory;

    @Test void oldAndNullConfigsReceiveSafeIndependentDefaults() throws Exception {
        Path path=directory.resolve("client.json");
        for(String content:new String[]{"{}","{\"soundDetector\":null}"}) {
            Files.writeString(path,content);var config=new ConfigStore(path).load();
            assertFalse(config.soundDetector.enabled);assertEquals(128,config.soundDetector.range);
            assertFalse(config.soundDetector.categoryEnabled("natural"));assertTrue(config.soundDetector.categoryEnabled("containers"));
        }
        assertFalse(new SoundDetectorSettings().categoryEnabled("natural"));
    }
    @Test void malformedValuesAndNestedListsRecoverLocally() {
        var s=new SoundDetectorSettings();s.enabled=true;s.range=Double.NaN;s.scale=Double.POSITIVE_INFINITY;s.volume=-2;s.maxCards=999;s.preset=null;s.material=null;s.categories=null;s.rules=null;s.zones=null;
        s.sanitize();assertTrue(s.enabled);assertEquals(128,s.range);assertEquals(1,s.scale);assertEquals(0,s.volume);assertEquals(5,s.maxCards);assertEquals("Balanced activity",s.preset);
        assertNotNull(s.rules);assertNotNull(s.zones);assertFalse(s.categoryEnabled("custom"));
    }
    @Test void presetsPreserveRulesAndAppearance() {
        var s=new SoundDetectorSettings();s.scale=1.7;s.topColor=0x123456;s.rules.add(new SoundDetectorSettings.Rule());
        s.applyPreset("Players only");assertTrue(s.playersOnly);assertEquals(1.7,s.scale);assertEquals(1,s.rules.size());
        s.applyPreset("Diagnostics");assertFalse(s.playersOnly);assertTrue(s.categoryEnabled("natural"));assertTrue(s.includeClientDerived);
    }
    @Test void groupingRepeatsAndAnimationControlsAreFiniteAndBounded() {
        var s=new SoundDetectorSettings();s.groupingHorizontal=Double.NaN;s.groupingVertical=999;s.repeatCount=999;s.repeatIntervalSeconds=Double.POSITIVE_INFINITY;
        s.fadeInMillis=-10;s.fadeOutMillis=99999;s.slidePixels=999;s.fontScale=Double.NaN;
        s.sanitize();assertEquals(4,s.groupingHorizontal);assertEquals(64,s.groupingVertical);assertEquals(3,s.repeatCount);assertEquals(.5,s.repeatIntervalSeconds);
        assertEquals(0,s.fadeInMillis);assertEquals(2000,s.fadeOutMillis);assertEquals(80,s.slidePixels);assertEquals(1,s.fontScale);assertTrue(s.autoLoadProfiles);
        s.groupingHorizontal=0;s.groupingVertical=0;s.repeatCount=0;s.repeatIntervalSeconds=0;s.sanitize();
        assertEquals(1,s.groupingHorizontal);assertEquals(1,s.groupingVertical);assertEquals(1,s.repeatCount);assertEquals(.25,s.repeatIntervalSeconds);
    }
    @Test void profileCopiesAreIndependentNonrecursiveAndDoNotToggleTheModule() {
        var s=new SoundDetectorSettings();s.enabled=true;s.favorite=true;s.range=80;s.scale=1.5;s.rules.add(new SoundDetectorSettings.Rule());s.saveProfile(" Example.org:25565 ");
        s.range=4;s.scale=.5;s.rules.getFirst().name="changed";s.enabled=false;
        assertTrue(s.loadProfile("example.org:25565"));assertEquals(80,s.range);assertEquals(1.5,s.scale);assertFalse(s.enabled);assertTrue(s.favorite);
        assertEquals("Custom rule",s.rules.getFirst().name);assertTrue(s.profiles.get("example.org:25565").settings.profiles.isEmpty());
        s.rules.getFirst().name="changed again";assertTrue(s.loadProfile("example.org:25565"));assertEquals("Custom rule",s.rules.getFirst().name);
    }
    @Test void savedConfigRoundTripsRulesAppearanceAndProfiles() {
        var config=new ClientConfig();var s=config.soundDetector;s.enabled=true;s.showCoordinates=true;s.titleTemplate="Activity: {event}";s.categoryEnabled("containers");
        var rule=new SoundDetectorSettings.Rule();rule.name="Custom";rule.priority="CRITICAL";rule.soundPattern="minecraft:block.*.open";s.rules.add(rule);s.saveProfile("example.org");
        var store=new ConfigStore(directory.resolve("client.json"));store.save(config);var loaded=store.load().soundDetector;
        assertTrue(loaded.enabled);assertTrue(loaded.showCoordinates);assertEquals("Activity: {event}",loaded.titleTemplate);assertEquals("Custom",loaded.rules.getFirst().name);assertEquals(1,loaded.profiles.size());
    }
}
