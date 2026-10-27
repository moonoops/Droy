package io.droyagent.androiduse;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.junit.Test;

public class InfraredToolsTest {
    @Test public void waveformLimitsMatchAndroidAndNeverOverflowIntegerTotal() throws Exception {
        assertArrayEquals(new int[]{9000, 4500, 560}, InfraredTools.waveform(new JSONArray("[9000,4500,560]")));
        assertThrows(ActionException.class, () -> InfraredTools.waveform(new JSONArray("[1000000,1000000]")));
        assertThrows(ActionException.class, () -> InfraredTools.waveform(new JSONArray("[2147483647,2147483647]")));
        assertThrows(ActionException.class, () -> InfraredTools.waveform(new JSONArray("[1,0]")));
        assertThrows(ActionException.class, () -> InfraredTools.waveform(new JSONArray("[1,-1]")));
        assertThrows(ActionException.class, () -> InfraredTools.waveform(new JSONArray("[1,1.5]")));
        assertThrows(ActionException.class, () -> InfraredTools.waveform(new JSONArray()));
        assertThrows(ActionException.class, () -> InfraredTools.waveform("[1,2]"));
        JSONArray overlong = new JSONArray();
        for (int i = 0; i < 4097; i++) overlong.put(1);
        assertThrows(ActionException.class, () -> InfraredTools.waveform(overlong));
    }

    @Test public void sequenceDurationIncludesOnlyGapsBetweenRepeatsAndRoundsUpMicros() {
        assertEquals(2, InfraredTools.sequenceMillis(1001, 1, 500));
        assertEquals(3200, InfraredTools.sequenceMillis(1000000, 3, 100));
        assertEquals(10000, InfraredTools.sequenceMillis(1600000, 5, 500));
        assertTrue(InfraredTools.sequenceMillis(1999999, 5, 1) > 10000);
    }
}
