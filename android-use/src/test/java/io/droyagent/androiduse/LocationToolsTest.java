package io.droyagent.androiduse;

import static org.junit.Assert.*;
import org.junit.Test;

public class LocationToolsTest {
    @Test public void locationValidityIncludesAgeCoordinatesAccuracyAndMonotonicClock() {
        assertTrue(LocationTools.validReading(31.2, 121.5, 4, 1000000000L, 2000000000L, 1000));
        assertTrue(LocationTools.validReading(0, 0, 0, 1000000000L, 1000000000L, 0));
        assertFalse(LocationTools.validReading(31.2, 121.5, 4, 1000000000L, 2000000001L, 1000));
        assertFalse(LocationTools.validReading(91, 121.5, 4, 1000000000L, 2000000000L, 1000));
        assertFalse(LocationTools.validReading(31.2, 181, 4, 1000000000L, 2000000000L, 1000));
        assertFalse(LocationTools.validReading(Double.NaN, 0, 4, 1000000000L, 2000000000L, 1000));
        assertFalse(LocationTools.validReading(0, 0, Float.NaN, 1000000000L, 2000000000L, 1000));
        assertFalse(LocationTools.validReading(0, 0, -1, 1000000000L, 2000000000L, 1000));
        assertFalse(LocationTools.validReading(0, 0, 1, 3000000000L, 2000000000L, 1000));
        assertFalse(LocationTools.validReading(0, 0, 1, 0L, 2000000000L, 1000));
    }
}
