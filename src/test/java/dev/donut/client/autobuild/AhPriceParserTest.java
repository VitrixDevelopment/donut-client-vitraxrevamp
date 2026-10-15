package dev.donut.client.autobuild;

import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AhPriceParserTest {
    @Test void readsCommonListingFormats() {
        assertEquals(1234, AhPriceParser.parse(List.of("Price: $1,234")).orElseThrow());
        assertEquals(2500, AhPriceParser.parse(List.of("Buy Now: 2.5k coins")).orElseThrow());
        assertEquals(1500, AhPriceParser.parse(List.of("Cost: 1500")).orElseThrow());
        assertEquals(3_000_000, AhPriceParser.parse(List.of("$3m")).orElseThrow());
        assertEquals(1_000_000_000, AhPriceParser.parse(List.of("1b coins")).orElseThrow());
    }

    @Test void prefersBuyoutLinesOverBidsAndFlavorText() {
        double price = AhPriceParser.parse(List.of(
                "Fancy Sword",
                "Starting bid: 500",
                "Buyout: 2,000",
                "A legend among weapons")).orElseThrow();
        assertEquals(2000, price);
    }

    @Test void refusesUnpricedOrVagueTooltips() {
        assertTrue(AhPriceParser.parse(List.of("A legend among weapons")).isEmpty());
        assertTrue(AhPriceParser.parse(List.of()).isEmpty());
        assertTrue(AhPriceParser.parse(List.of("")).isEmpty());
        assertTrue(AhPriceParser.parse(List.of("Level 5 rarity")).isEmpty());
    }

    @Test void unitPricesDivideStackTotals() {
        assertEquals(31.25, AhPriceParser.unitPrice(2000, 64), 1e-9);
        assertEquals(2000, AhPriceParser.unitPrice(2000, 1), 1e-9);
        assertEquals(2000, AhPriceParser.unitPrice(2000, 0), 1e-9);
    }
}
