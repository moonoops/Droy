package io.droyagent.app;

import java.util.TimeZone;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class DeviceTimeZoneTest {
    @Test public void nativeClockPreservesRegionalZonesAndNormalizesFixedOffsets() {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String[] value : new String[][] {
                    {"Asia/Shanghai", "Asia/Shanghai"}, {"Asia/Kathmandu", "Asia/Kathmandu"},
                    {"America/New_York", "America/New_York"}, {"GMT+08:00", "+08:00"},
                    {"GMT-03:30", "-03:30"}, {"UTC", "UTC"}}) {
                TimeZone.setDefault(TimeZone.getTimeZone(value[0]));
                assertEquals(value[1], ProductBridge.deviceTimeZone());
            }
        } finally { TimeZone.setDefault(original); }
    }
}
