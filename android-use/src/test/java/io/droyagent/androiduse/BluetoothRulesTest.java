package io.droyagent.androiduse;

import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

public class BluetoothRulesTest {
    @Test public void serviceUuidMustBeFullWidthRatherThanJavaShortUuidExpansion() {
        assertEquals(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"),
                BluetoothRules.uuid("00001101-0000-1000-8000-00805f9b34fb"));
        reject(() -> BluetoothRules.uuid("1-1-1-1-1"));
        reject(() -> BluetoothRules.uuid("1101"));
        reject(() -> BluetoothRules.uuid(1101));
    }

    @Test public void oneWriteCannotSilentlySplitAnApplicationFrame() {
        BluetoothRules.writeLength(20, 23);
        reject(() -> BluetoothRules.writeLength(21, 23));
        BluetoothRules.writeLength(512, 517);
        reject(() -> BluetoothRules.writeLength(513, 517));
        reject(() -> BluetoothRules.writeLength(-1, 23));
        BluetoothRules.writeLength(8, 23, 4);
        reject(() -> BluetoothRules.writeLength(9, 23, 4));
        assertEquals(1, BluetoothRules.writeType("without_response"));
        assertEquals(2, BluetoothRules.writeType("with_response"));
        reject(() -> BluetoothRules.writeType("auto"));
    }

    @Test public void byteEncodingPreservesUnsignedBytesAndLeadingZeros() {
        assertEquals("00017f80ff", BluetoothRules.hex(new byte[]{0, 1, 127, (byte)128, (byte)255}));
    }

    private static void reject(Runnable call) {
        try { call.run(); fail("Expected rejected transport argument"); }
        catch (IllegalArgumentException expected) { }
    }
}
