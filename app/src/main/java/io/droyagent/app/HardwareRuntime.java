package io.droyagent.app;

import android.content.Context;
import android.content.SharedPreferences;
import io.droyagent.androiduse.*;
import java.util.Set;
import org.json.JSONObject;

/** Product lifecycle for native device sessions; the bridge still authorizes every call. */
final class HardwareRuntime {
    static final Set<String> ACTIONS = Set.of("sensor", "location", "infrared", "bluetooth", "nfc",
        "nfc_emulation", "wifi", "cellular", "network_probe", "device_data", "device_shortcut");
    private static HardwareRuntime instance;
    private final Context context;
    private final SensorTools sensors;
    private final LocationTools location;
    private final InfraredTools infrared;
    private final BluetoothTools bluetooth;
    private final NfcTools nfc;
    private final WifiTools wifi;
    private final CellularTools cellular;
    private final NetworkProbeTools probe;
    private final DeviceDataStore data;
    private final HceProfiles hce;
    private Set<String> access;
    private final java.util.concurrent.ConcurrentHashMap<String, java.io.Closeable> lifetimes = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> owners = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesChanged;

    static synchronized HardwareRuntime get(Context context) {
        if (instance == null) instance = new HardwareRuntime(context.getApplicationContext());
        return instance;
    }
    static synchronized void onPermissionsChanged(Context context) {
        if (instance != null) instance.refreshAccess();
    }
    static synchronized void stopAll(Context context) {
        if (instance != null) instance.close();
        else HceProfiles.disable(context);
        HardwareSessionService.closeAll();
    }
    static synchronized void releaseTransient(Context context) {
        if (instance != null) instance.closeTransient();
        HardwareSessionService.closeAll();
    }

    private HardwareRuntime(Context context) {
        this.context = context;
        sensors = new SensorTools(context); location = new LocationTools(context);
        infrared = new InfraredTools(context); bluetooth = new BluetoothTools(context);
        nfc = new NfcTools(context); wifi = new WifiTools(context);
        cellular = new CellularTools(context); probe = new NetworkProbeTools(context);
        data = new DeviceDataStore(context); hce = new HceProfiles(context);
        access = accessSnapshot();
        bluetooth.setAccessCheck(() -> {
            SharedPreferences choices = ProductPermissions.preferences(context);
            if (ProductPermissions.stopped(context)
                || !ProductPermissions.allowed(choices, "allowBluetoothRead"))
                throw new InterruptedException(NativeLocale.text("蓝牙操作已停止或权限已撤销", "Bluetooth access stopped or permission was revoked"));
        });
        preferencesChanged = (preferences, key) -> {
            if (key != null && (ProductPermissions.STOPPED.equals(key) || ProductPermissions.isHardwareCapability(key))) refreshAccess();
        };
        ProductPermissions.preferences(context).registerOnSharedPreferenceChangeListener(preferencesChanged);
    }

    private Set<String> accessSnapshot() {
        Set<String> current = new java.util.HashSet<>();
        SharedPreferences choices = ProductPermissions.preferences(context);
        if (!ProductPermissions.stopped(context)) current.add("running");
        org.json.JSONArray groups = new CapabilityCatalog(context).groups();
        for (int index = 0; index < groups.length(); index++) {
            String preference = groups.optJSONObject(index).optString("preference");
            if (ProductPermissions.isHardwareCapability(preference) && ProductPermissions.allowed(choices, preference)) current.add(preference);
        }
        for (String permission : new String[]{"android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.READ_PHONE_STATE",
                "android.permission.NEARBY_WIFI_DEVICES", "android.permission.ACTIVITY_RECOGNITION", "android.permission.BODY_SENSORS",
                "android.permission.health.READ_HEART_RATE"})
            if (context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) current.add(permission);
        return current;
    }
    private synchronized void refreshAccess() {
        Set<String> current = accessSnapshot();
        Set<String> revoked = new java.util.HashSet<>(access); revoked.removeAll(current);
        access = current;
        if (revoked.contains("running")) { close(); return; }
        for (String permission : revoked) {
            switch (permission) {
                case "allowSensorsRead", "allowActivitySensors", "allowBodySensors", "android.permission.ACTIVITY_RECOGNITION",
                    "android.permission.BODY_SENSORS", "android.permission.health.READ_HEART_RATE" -> sensors.close();
                case "allowLocationRead", "android.permission.ACCESS_COARSE_LOCATION" -> location.close();
                case "android.permission.ACCESS_FINE_LOCATION" -> { location.close(); wifi.close(); cellular.close(); }
                case "allowInfrared" -> infrared.close();
                case "allowBluetoothRead", "allowBluetoothControl", "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT" -> bluetooth.close();
                case "allowWifiRead", "allowWifiControl", "android.permission.NEARBY_WIFI_DEVICES" -> wifi.close();
                case "allowNfcRead", "allowNfcWrite" -> nfc.close();
                case "allowNfcEmulation" -> hce.close();
                case "allowCellularRead", "android.permission.READ_PHONE_STATE" -> cellular.close();
                case "allowNetworkProbe" -> probe.close();
            }
        }
        for (String owner : lifetimes.keySet()) releaseIdleLifetime(owner);
    }

    JSONObject execute(String action, JSONObject args, String owner, Cancellation cancellation) throws Exception {
        if (owner == null || owner.isBlank() || owner.length() > 256)
            throw new IllegalArgumentException(NativeLocale.text("缺少有效任务身份", "A valid task identity is required"));
        owners.add(owner);
        if (args.has("dataRef") && !ProductPermissions.allowed(ProductPermissions.preferences(context), "allowDeviceDataRead"))
            throw new SecurityException(NativeLocale.text("请允许读取已保存设备数据", "Allow reading saved device data"));
        if (action.equals("sensor") && !args.optString("operation").equals("list")) {
            int type = args.optInt("sensorType", -1);
            String preference = type == 18 || type == 19 ? "allowActivitySensors" : type == 21 || type == 31 ? "allowBodySensors" : null;
            if (preference != null && !ProductPermissions.allowed(ProductPermissions.preferences(context), preference))
                throw new SecurityException(NativeLocale.text("活动或身体传感器尚未获产品授权", "Activity or body sensor access is not authorized"));
        }
        if (action.equals("location") && !args.optString("operation").equals("status")) {
            try (java.io.Closeable lifetime = HardwareSessionService.begin(context, "location")) {
                cancellation.check();
                return location.execute(args, owner, cancellation);
            }
        }
        try {
        if ((action.equals("bluetooth") && Set.of("connect", "subscribe").contains(args.optString("operation")))
                || action.equals("wifi") && args.optString("operation").equals("connect")) {
            if (!lifetimes.containsKey(owner)) {
                java.io.Closeable lifetime = HardwareSessionService.begin(context, "connectedDevice");
                java.io.Closeable existing = lifetimes.putIfAbsent(owner, lifetime);
                if (existing != null) lifetime.close();
            }
            cancellation.check();
        }
        return switch (action) {
            case "sensor" -> sensors.execute(args, owner, cancellation);
            case "location" -> location.execute(args, owner, cancellation);
            case "infrared" -> infrared.execute(args, owner, cancellation);
            case "bluetooth" -> bluetooth.execute(args, owner, cancellation);
            case "nfc" -> nfc.execute(args, owner, cancellation);
            case "nfc_emulation" -> hce.execute(args, owner, cancellation);
            case "wifi" -> wifi.execute(args, owner, cancellation);
            case "cellular" -> cellular.execute(args, owner, cancellation);
            case "network_probe" -> probe.execute(args, owner, cancellation);
            case "device_data" -> data.execute(args, owner, cancellation);
            case "device_shortcut" -> HardwareShortcutActivity.request(context, args, cancellation);
            default -> throw new IllegalArgumentException(action);
        }; } finally { releaseIdleLifetime(owner); }
    }

    static boolean cleanup(String action, JSONObject args) {
        String operation = args.optString("operation");
        return (Set.of("bluetooth", "wifi").contains(action) && operation.equals("disconnect"))
            || action.equals("nfc") && operation.equals("close")
            || action.equals("nfc_emulation") && operation.equals("disable");
    }
    static boolean readOnly(String action, JSONObject args) {
        String operation = args.optString("operation");
        return switch (action) {
            case "sensor", "location", "cellular" -> true;
            case "infrared" -> operation.equals("status");
            case "bluetooth" -> Set.of("status", "scan", "discover", "read", "events").contains(operation);
            case "nfc" -> Set.of("status", "read", "page_read", "block_read").contains(operation);
            case "wifi" -> Set.of("status", "scan", "sample").contains(operation);
            case "device_data" -> Set.of("list", "read").contains(operation);
            case "nfc_emulation" -> Set.of("status", "list", "events").contains(operation);
            default -> false;
        };
    }

    JSONObject capabilities() throws Exception {
        return new JSONObject().put("sensor", sensors.capabilities()).put("location", location.capabilities())
            .put("infrared", infrared.capabilities()).put("bluetooth", bluetooth.capabilities())
            .put("nfc", nfc.capabilities()).put("nfc_emulation", hce.capabilities())
            .put("wifi", wifi.capabilities()).put("cellular", cellular.capabilities())
            .put("network_probe", probe.capabilities()).put("permissions", HardwarePermissionState.status(context));
    }
    void closeOwner(String owner) {
        sensors.closeOwner(owner); location.closeOwner(owner); infrared.closeOwner(owner);
        bluetooth.closeOwner(owner); nfc.closeOwner(owner); wifi.closeOwner(owner);
        cellular.closeOwner(owner); probe.closeOwner(owner); data.closeOwner(owner);
        owners.remove(owner);
        java.io.Closeable lifetime = lifetimes.remove(owner);
        if (lifetime != null) try { lifetime.close(); } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    java.util.concurrent.CompletableFuture<Void> closeOwnerSettled(String owner) {
        java.util.concurrent.CompletableFuture<Void> settled = new java.util.concurrent.CompletableFuture<>();
        closeOwner(owner);
        // NFC reader teardown posts to the main looper. A barrier follows those owned
        // callbacks; executing HTTP calls retain their own dispatch leases as well.
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> settled.complete(null));
        return settled;
    }
    private void releaseIdleLifetime(String owner) {
        if (bluetooth.hasOwnerSessions(owner) || wifi.hasOwnerSessions(owner)) return;
        java.io.Closeable lifetime = lifetimes.remove(owner);
        if (lifetime != null) try { lifetime.close(); } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    void closeTransient() {
        sensors.close(); location.close(); infrared.close(); bluetooth.close(); nfc.close();
        wifi.close(); cellular.close(); probe.close();
        for (String owner : owners) { data.closeOwner(owner); owners.remove(owner); }
        for (String owner : lifetimes.keySet()) {
            java.io.Closeable lifetime = lifetimes.remove(owner);
            if (lifetime != null) try { lifetime.close(); } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        }
        HardwareSessionService.closeAll();
    }
    void close() { closeTransient(); hce.close(); }
}
