package dev.donut.client.gui;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BaseFinderPanelTest {
    @Test void largeDiscoveryCardsFitScreenMarginsAtEveryGuiScale() {
        for (int scale = 1; scale <= 4; scale++) {
            int width = 1280 / scale, height = 800 / scale;
            for (int cardHeight : new int[]{149, 301, 400}) {
                float fit = BaseFinderPanel.fittedScale(width, height, cardHeight, scale, 1.5);
                assertTrue(Float.isFinite(fit) && fit > 0);
                assertTrue(254 * fit <= width - 16 + .001, "Card exceeded horizontal margins");
                assertTrue(cardHeight * fit <= height - 44 + .001, "Card exceeded top/bottom margins");
            }
        }
    }
}
