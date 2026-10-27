package io.droyagent.androiduse;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class NetworkToolValuesTest {
    @Test public void doesNotTurnUnreportedRadioDataIntoMeasurements() {
        assertSame(JSONObject.NULL, NetworkToolValues.radio(Integer.MAX_VALUE));
        assertSame(JSONObject.NULL, NetworkToolValues.identity(Long.MAX_VALUE));
        assertEquals(-120, NetworkToolValues.radio(-120));
        assertEquals(0L, NetworkToolValues.identity(0));
        assertSame(JSONObject.NULL, NetworkToolValues.identity(-1));
    }

    @Test public void explicitRadioSelectionCannotFallBackToAnotherTransportOrVpn() {
        assertTrue(NetworkToolValues.matchesTransport("wifi", true, false, false));
        assertFalse(NetworkToolValues.matchesTransport("wifi", false, true, false));
        assertFalse(NetworkToolValues.matchesTransport("cellular", true, false, false));
        assertFalse(NetworkToolValues.matchesTransport("wifi", true, false, true));
        assertFalse(NetworkToolValues.matchesTransport("cellular", false, true, true));
        assertTrue(NetworkToolValues.matchesTransport("default", true, false, true));
    }

    @Test public void taskCannotReleaseAnotherTasksConnection() {
        assertTrue(NetworkToolValues.ownedBy("task-a", "task-a"));
        assertFalse(NetworkToolValues.ownedBy("task-a", "task-b"));
        assertFalse(NetworkToolValues.ownedBy("", ""));
        assertFalse(NetworkToolValues.ownedBy(null, "task-a"));
    }

    @Test public void probeHostIsAHostRatherThanAnImplicitUrlOrCredentialContainer() {
        assertEquals("example.invalid", NetworkToolValues.host("example.invalid"));
        assertEquals("2001:db8::1", NetworkToolValues.host("2001:db8::1"));
        for (String input : new String[]{"", "https://example.invalid", "a/b", "u@example.invalid", "two hosts"})
            assertThrows(IllegalArgumentException.class, () -> NetworkToolValues.host(input));
    }

    @Test public void wifiPasswordChecksThePlatformAsciiAndLengthContract() {
        assertTrue(NetworkToolValues.validWifiPassword("fictional wifi password"));
        assertTrue(NetworkToolValues.validWifiPassword("x"));
        assertFalse(NetworkToolValues.validWifiPassword(""));
        assertFalse(NetworkToolValues.validWifiPassword(null));
        assertFalse(NetworkToolValues.validWifiPassword("contains\nnewline"));
        assertFalse(NetworkToolValues.validWifiPassword("unprintable\u007f"));
        assertFalse(NetworkToolValues.validWifiPassword("non-ascii-密"));
        assertFalse(NetworkToolValues.validWifiPassword("x".repeat(64)));
    }

    @Test public void unknownAsuIsNotReportedAsAValidRadioMeasurement() {
        assertSame(JSONObject.NULL, NetworkToolValues.asu(99, true));
        assertSame(JSONObject.NULL, NetworkToolValues.asu(99, false));
        assertSame(JSONObject.NULL, NetworkToolValues.asu(Integer.MAX_VALUE, false));
        assertSame(JSONObject.NULL, NetworkToolValues.asu(40, true));
        assertEquals(31, NetworkToolValues.asu(31, true));
        assertEquals(97, NetworkToolValues.asu(97, false));
    }
}
