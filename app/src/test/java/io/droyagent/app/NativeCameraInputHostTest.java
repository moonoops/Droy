package io.droyagent.app;

import static org.junit.Assert.*;
import org.junit.Test;

public final class NativeCameraInputHostTest {
    @Test public void outputIdentityCanOnlyNameOnePrivateNativeCapture() {
        String id = "12345678-1234-4abc-8abc-123456789abc";
        assertEquals("native-" + id + ".jpg", NativeCameraInputHost.filename(id));
        for (String invalid : new String[]{"../" + id, id + ".jpg", "content://camera/" + id, id.toUpperCase(), "capture"})
            assertThrows(IllegalArgumentException.class, () -> NativeCameraInputHost.filename(invalid));
    }
}
