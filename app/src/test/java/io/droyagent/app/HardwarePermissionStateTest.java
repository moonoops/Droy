package io.droyagent.app;

import android.Manifest;
import org.junit.Test;
import static org.junit.Assert.*;

public final class HardwarePermissionStateTest {
    @Test public void bluetoothUsesNearbyPermissionsOnlyOnAndroid12AndLater() {
        assertArrayEquals(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION},
            HardwarePermissionState.runtimePermissions("bluetooth", 30));
        assertArrayEquals(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT},
            HardwarePermissionState.runtimePermissions("bluetooth", 31));
    }

    @Test public void wifiConnectionPermissionDoesNotReplaceLocationForScanning() {
        assertArrayEquals(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},
            HardwarePermissionState.runtimePermissions("nearbyWifi", 33));
        assertArrayEquals(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION},
            HardwarePermissionState.runtimePermissions("wifiScan", 36));
    }

    @Test public void settingsNavigationDoesNotMasqueradeAsRuntimePermissionRequest() {
        assertNull(HardwarePermissionState.runtimePermissions("nfcSettings", 36));
        assertNull(HardwarePermissionState.runtimePermissions("wifiSettings", 36));
        assertNull(HardwarePermissionState.runtimePermissions("unsupported", 36));
    }

    @Test public void android16UsesGranularHeartRatePermissionInsteadOfLegacyBodySensors() {
        assertArrayEquals(new String[]{Manifest.permission.BODY_SENSORS},
            HardwarePermissionState.runtimePermissions("bodySensors", 35));
        assertArrayEquals(new String[]{"android.permission.health.READ_HEART_RATE"},
            HardwarePermissionState.runtimePermissions("bodySensors", 36));
        assertArrayEquals(new String[]{Manifest.permission.ACTIVITY_RECOGNITION},
            HardwarePermissionState.runtimePermissions("activityRecognition", 36));
    }
}
