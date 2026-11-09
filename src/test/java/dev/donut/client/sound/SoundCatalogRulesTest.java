package dev.donut.client.sound;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class SoundCatalogRulesTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        SoundCatalog.rebuild();
    }
    @Test void everyRegisteredBlockAndItsValidStatesReceiveCoverageMetadata() {
        assertEquals(BuiltInRegistries.BLOCK.size(), SoundCatalog.coverage().size());
        var ids = new HashSet<String>();
        for (var row : SoundCatalog.coverage()) {
            assertTrue(ids.add(row.blockId()), "Duplicated coverage " + row.blockId());
            assertTrue(row.stateCount() > 0);
            assertFalse(row.specialActionStatus().isBlank());
        }
        assertTrue(ids.contains("minecraft:oxidized_copper_chest"));
        assertTrue(ids.contains("minecraft:pale_oak_shelf"));
    }
    @Test void sharedMaterialIsNotMisidentifiedAsOneBlockAndUnknownLookalikesStayUnknown() {
        assertTrue(SoundCatalog.candidates("minecraft:block.stone.break").size() > 1);
        assertEquals("building.break", SoundCatalog.classify("minecraft:block.stone.break").actionKey());
        assertEquals(0, SoundCatalog.classify("malicious:block.chest.open").score());
        assertEquals("custom", SoundCatalog.classify("minecraft:unregistered.chest.open").category());
    }
    @Test void specializedInteractionsAndNaturalSourcesRemainSeparate() {
        assertEquals(50, SoundCatalog.classify("minecraft:block.chest.open").score());
        assertEquals("container.open", SoundCatalog.classify("minecraft:block.copper_chest.open").actionKey());
        assertEquals("machinery", SoundCatalog.classify("minecraft:block.piston.extend").category());
        assertEquals(0, SoundCatalog.classify("minecraft:entity.villager.work_armorer").score());
        assertEquals("building.break", SoundCatalog.classify("minecraft:block.sculk_sensor.break").actionKey());
    }
}
