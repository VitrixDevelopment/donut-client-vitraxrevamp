package dev.donut.client.progress;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CountdownDurationTest {
    @Test void oneHourAndBoundaryDurationsRoundTripWithoutUnitConfusion() {
        assertEquals(3600, CountdownDuration.parse("1:00:00").orElseThrow());
        assertEquals(3600, CountdownDuration.parse("01:00:00").orElseThrow());
        assertEquals(1, CountdownDuration.parse("00:00:01").orElseThrow());
        assertEquals(359999, CountdownDuration.parse("99:59:59").orElseThrow());
        for (int seconds : new int[] {1, 59, 60, 3599, 3600, 86399, 359999}) {
            assertEquals(seconds, CountdownDuration.parse(CountdownDuration.format(seconds)).orElseThrow());
        }
    }

    @Test void invalidOrOutOfRangeTimeDoesNotSilentlyChangeTheDuration() {
        for (String invalid : new String[] {"", "00:00:00", "01:60:00", "01:00:60", "100:00:00",
                "-1:00:00", "3600", "1:0:0", "00:01", "one hour", "99:99:99"}) {
            assertTrue(CountdownDuration.parse(invalid).isEmpty(), invalid);
        }
        assertTrue(CountdownDuration.parse(null).isEmpty());
    }
}
