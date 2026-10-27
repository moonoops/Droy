package io.droyagent.androiduse;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BluetoothEventBufferTest {
    @Test public void droppedNotificationsHaveAnExplicitCoverageGap() throws Exception {
        BluetoothEventBuffer buffer = new BluetoothEventBuffer(2);
        buffer.add(new JSONObject().put("hex", "01"));
        buffer.add(new JSONObject().put("hex", "02"));
        buffer.add(new JSONObject().put("hex", "03"));
        JSONObject result = buffer.read(0, 1);
        assertTrue(result.getBoolean("coverageGap"));
        assertEquals(1, result.getLong("droppedTotal"));
        assertEquals(2, result.getLong("nextCursor"));
        assertTrue(result.getBoolean("hasMore"));
        assertEquals("02", result.getJSONArray("events").getJSONObject(0).getString("hex"));
        JSONObject next = buffer.read(2, 10);
        assertFalse(next.getBoolean("coverageGap"));
        assertEquals(3, next.getLong("nextCursor"));
        assertFalse(next.getBoolean("hasMore"));
    }

    @Test public void readingIsRepeatableAndCannotModifyBufferedPayload() throws Exception {
        BluetoothEventBuffer buffer = new BluetoothEventBuffer(2);
        buffer.add(new JSONObject().put("hex", "00ff"));
        buffer.read(0, 2).getJSONArray("events").getJSONObject(0).put("hex", "changed");
        assertEquals("00ff", buffer.read(0, 2).getJSONArray("events").getJSONObject(0).getString("hex"));
        assertEquals(0, buffer.read(1, 2).getJSONArray("events").length());
        try { buffer.read(2, 2); fail("Future cursor must be rejected"); }
        catch (IllegalArgumentException expected) { }
    }
}
