package io.droyagent.androiduse;

import static org.junit.Assert.*;
import android.hardware.Sensor;
import org.json.JSONObject;
import org.junit.Test;

public class SensorToolsTest {
    @Test public void sampleSummaryRejectsInvalidValuesAndPreservesActualEventTimes() throws Exception {
        SensorTools.Samples samples = new SensorTools.Samples(3, 100000000L);
        samples.add(1000000000L, 3, new float[]{1, 5});
        samples.add(1050000000L, 3, new float[]{100, 100});
        samples.add(1200000000L, 2, new float[]{3, 1});
        samples.add(1300000000L, 3, new float[]{Float.NaN, 2});
        samples.add(1400000000L, 3, new float[]{1});
        JSONObject result = samples.result();
        assertEquals(2, result.getInt("sampleCount"));
        assertEquals(2, result.getInt("invalidSampleCount"));
        assertEquals(1, result.getInt("skippedSampleCount"));
        assertEquals("1200000000", result.getJSONArray("samples").getJSONObject(1).getString("elapsedRealtimeNanos"));
        assertEquals(2, result.getJSONObject("summary").getJSONArray("mean").getDouble(0), 0.0);
        assertEquals(3, result.getJSONObject("summary").getJSONArray("mean").getDouble(1), 0.0);
        assertEquals(200, result.getJSONObject("summary").getDouble("observedDurationMs"), 0.0);
    }

    @Test public void samplesNeverExceedLimitAndOldOrDuplicateEventsAreNotReordered() throws Exception {
        SensorTools.Samples samples = new SensorTools.Samples(2, 1);
        samples.add(10, 3, new float[]{1});
        samples.add(9, 3, new float[]{2});
        samples.add(10, 3, new float[]{3});
        samples.add(11, 3, new float[]{4});
        samples.add(12, 3, new float[]{5});
        assertEquals(2, samples.count());
        assertEquals(3, samples.result().getInt("skippedSampleCount"));
        assertEquals(4, samples.result().getJSONObject("summary").getJSONArray("maximum").getDouble(0), 0.0);
    }

    @Test public void noSampleIsNotRepresentedAsZeroAndUnknownUnitsStayUnknown() throws Exception {
        JSONObject result = new SensorTools.Samples(1, 1).result();
        assertFalse(result.has("summary"));
        assertEquals("unknown", SensorTools.unitFor(65536));
        assertEquals("steps_since_reboot", SensorTools.unitFor(Sensor.TYPE_STEP_COUNTER));
        assertEquals("cm_or_near_far", SensorTools.unitFor(Sensor.TYPE_PROXIMITY));
        assertTrue(SensorTools.axesFor(Sensor.TYPE_ACCELEROMETER).contains("natural_orientation"));
    }

    @Test public void callbackAlreadyQueuedAtStopCannotChangeFinalSnapshot() throws Exception {
        SensorTools.Samples samples = new SensorTools.Samples(2, 1);
        samples.add(10, 3, new float[]{1});
        samples.freeze();
        samples.add(11, 3, new float[]{99});
        assertEquals(1, samples.count());
        assertEquals(1, samples.result().getJSONObject("summary").getJSONArray("maximum").getDouble(0), 0.0);
    }
}
