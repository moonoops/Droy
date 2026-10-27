package io.droyagent.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.os.Build;
import org.json.JSONObject;

/** System grants are independent from the product capability switches. */
final class HardwarePermissionState {
    private HardwarePermissionState() {}

    static String[] runtimePermissions(String name, int sdk) {
        return switch (name) {
            case "location", "locationPrecise", "wifiScan" -> new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION};
            case "bluetooth" -> sdk >= 31 ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION};
            case "nearbyWifi" -> sdk >= 33 ? new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}
                : new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION};
            case "phoneState" -> new String[]{Manifest.permission.READ_PHONE_STATE};
            case "activityRecognition" -> new String[]{Manifest.permission.ACTIVITY_RECOGNITION};
            case "bodySensors" -> new String[]{sdk >= 36 ? "android.permission.health.READ_HEART_RATE" : Manifest.permission.BODY_SENSORS};
            default -> null;
        };
    }

    static JSONObject status(Context context) throws Exception {
        PackageManager packages = context.getPackageManager();
        boolean wifi = packages.hasSystemFeature(PackageManager.FEATURE_WIFI);
        boolean bluetooth = packages.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH);
        boolean nfc = packages.hasSystemFeature(PackageManager.FEATURE_NFC);
        boolean telephony = packages.hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
        boolean coarse = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION);
        boolean fine = granted(context, Manifest.permission.ACCESS_FINE_LOCATION);
        LocationManager location = context.getSystemService(LocationManager.class);
        boolean locationEnabled = location != null && location.isLocationEnabled();
        boolean scan = Build.VERSION.SDK_INT >= 31 ? granted(context, Manifest.permission.BLUETOOTH_SCAN)
            : granted(context, Manifest.permission.BLUETOOTH) && fine && locationEnabled;
        boolean connect = Build.VERSION.SDK_INT >= 31 ? granted(context, Manifest.permission.BLUETOOTH_CONNECT)
            : granted(context, Manifest.permission.BLUETOOTH);
        WifiManager wifiManager = context.getSystemService(WifiManager.class);
        BluetoothManager bluetoothManager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        NfcAdapter nfcAdapter = nfc ? NfcAdapter.getDefaultAdapter(context) : null;
        SensorManager sensors = context.getSystemService(SensorManager.class);
        boolean heartRate = sensors != null && (sensors.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null
            || sensors.getDefaultSensor(Sensor.TYPE_HEART_BEAT) != null);
        return new JSONObject().put("hardware", new JSONObject().put("wifi", wifi).put("bluetooth", bluetooth)
                .put("nfc", nfc).put("telephony", telephony)
                .put("stepCounter", packages.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)
                    || packages.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_DETECTOR)).put("heartRate", heartRate))
            .put("locationApproximate", coarse).put("locationPrecise", fine).put("locationEnabled", locationEnabled)
            .put("bluetoothScan", scan).put("bluetoothConnect", connect)
            .put("bluetoothEnabled", adapter == null || !connect ? JSONObject.NULL : adapter.isEnabled())
            .put("nearbyWifi", Build.VERSION.SDK_INT >= 33 ? granted(context, Manifest.permission.NEARBY_WIFI_DEVICES) : fine)
            .put("wifiScan", fine && locationEnabled).put("wifiEnabled", wifiManager == null ? JSONObject.NULL : wifiManager.isWifiEnabled())
            .put("phoneState", granted(context, Manifest.permission.READ_PHONE_STATE))
            .put("activityRecognition", granted(context, Manifest.permission.ACTIVITY_RECOGNITION))
            .put("bodySensors", granted(context, Build.VERSION.SDK_INT >= 36
                ? "android.permission.health.READ_HEART_RATE" : Manifest.permission.BODY_SENSORS))
            .put("nfcEnabled", nfcAdapter == null ? JSONObject.NULL : nfcAdapter.isEnabled());
    }

    private static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
