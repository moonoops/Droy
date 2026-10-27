package io.droyagent.app;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.DeviceDataStore;
import io.droyagent.androiduse.HardwareSupport;
import io.droyagent.androiduse.NfcTools;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Native tools on an actual device, with synthetic payloads and no model or external traffic. */
@RunWith(AndroidJUnit4.class)
public class HardwareDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences preferences;
    private final Map<String, Object> previous = new HashMap<>();
    private final Set<String> absent = new java.util.HashSet<>();
    private final Set<String> owners = new java.util.HashSet<>();
    private final Set<String> savedRefs = new java.util.HashSet<>();
    private ExecutorService workers;
    private ProductBridge bridge;
    private Activity foreground;
    private static Activity practice;
    private String owner;

    @Before public void before() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        preferences = ProductPermissions.preferences(context);
        owner = "hardware-test-" + UUID.randomUUID(); owners.add(owner);
        workers = Executors.newCachedThreadPool();
        change(ProductPermissions.STOPPED, false);
        if (practice == null || practice.isDestroyed()) practice = instrumentation.startActivitySync(new Intent(context, TestPageActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        foreground = practice;
        instrumentation.runOnMainSync(() -> foreground.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        instrumentation.waitForIdleSync();
        bridge = new ProductBridge(context, "unused-hardware-test-token");
        bridge.policy.enable();
    }

    @After public void after() throws Exception {
        if (bridge != null) bridge.policy.stop();
        DeviceDataStore data = context == null ? null : new DeviceDataStore(context);
        if (data != null) for (String ref : savedRefs) {
            data.execute(new JSONObject().put("operation", "delete").put("dataRef", ref), owner, () -> {});
        }
        if (context != null) for (String task : owners) HardwareRuntime.get(context).closeOwner(task);
        if (bridge != null) bridge.stop();
        if (workers != null) { workers.shutdownNow(); workers.awaitTermination(5, TimeUnit.SECONDS); }
        if (preferences != null) {
            SharedPreferences.Editor restore = preferences.edit();
            for (String key : absent) restore.remove(key);
            for (Map.Entry<String, Object> entry : previous.entrySet()) {
                if (entry.getValue() instanceof Boolean value) restore.putBoolean(entry.getKey(), value);
                else restore.putString(entry.getKey(), (String) entry.getValue());
            }
            assertTrue("Test preferences must be restored", restore.commit());
        }
    }

    @AfterClass public static void finishPractice() {
        if (practice != null) InstrumentationRegistry.getInstrumentation().runOnMainSync(practice::finish);
        practice = null;
    }

    @Test public void shortcutsRequirePersistentDataAndIdleCleanupPreservesExplicitEmulation() throws Exception {
        change("allowDeviceDataRead", true); change("allowDeviceDataWrite", true); change("allowNfcEmulation", true);
        DeviceDataStore store = new DeviceDataStore(context);
        JSONObject temporary = store.put(owner, new byte[]{1, 2, 3}, new JSONObject(), false, "Synthetic temporary");
        assertThrows(ActionException.class, () -> HardwareShortcutActivity.validateCommand(context, "device_data",
            new JSONObject().put("dataRef", temporary.getString("dataRef")), () -> {}));
        JSONObject saved = store.put(owner, new byte[]{1, 2, 3}, new JSONObject(), true, "Synthetic saved");
        savedRefs.add(saved.getString("dataRef"));
        assertEquals("read", HardwareShortcutActivity.validateCommand(context, "device_data",
            new JSONObject().put("dataRef", saved.getString("dataRef")), () -> {}).getString("operation"));
        SharedPreferences state = context.getSharedPreferences("nfc-hce", Context.MODE_PRIVATE);
        assumeTrue("Do not interrupt an existing active profile", state.getString("active", "").isEmpty());
        HceProfiles hce = new HceProfiles(context);
        JSONObject profile = hce.execute(args("save").put("name", "Synthetic lifecycle profile")
            .put("aids", new JSONArray().put("F001020304")).put("initialState", "initial")
            .put("rules", new JSONArray().put(new JSONObject().put("state", "initial")
                .put("requestHex", "00a4040005f001020304").put("responseHex", "9000"))), owner, () -> {});
        String profileId = profile.getString("profileId");
        try {
            assertTrue(state.edit().putString("active", profileId).commit());
            HardwareRuntime.releaseTransient(context);
            assertEquals(profileId, state.getString("active", ""));
            change("allowSensorsRead", true); instrumentation.waitForIdleSync(); change("allowSensorsRead", false);
            instrumentation.waitForIdleSync();
            assertEquals(profileId, state.getString("active", ""));
            HardwareRuntime.stopAll(context);
            assertFalse(state.contains("active"));
        } finally {
            HceProfiles.disable(context);
            hce.execute(args("delete").put("profileId", profileId), owner, () -> {});
        }
    }

    @Test public void hardwareDefaultsAndSensitiveSensorGatesRequireExplicitProductChoice() throws Exception {
        for (String key : new String[]{"allowNfcWrite", "allowBluetoothControl", "allowLocationRead"}) {
            remember(key); assertTrue(preferences.edit().remove(key).commit());
            assertFalse("New hardware capabilities must not inherit the legacy default", ProductPermissions.allowed(preferences, key));
        }
        change("allowSensorsRead", true);
        change("allowActivitySensors", false);
        change("allowBodySensors", false);
        assertThrows(SecurityException.class, () -> call("sensor", args("read").put("sensorType", Sensor.TYPE_STEP_COUNTER)));
        assertThrows(SecurityException.class, () -> call("sensor", args("read").put("sensorType", Sensor.TYPE_HEART_RATE)));
        change("allowNfcRead", true); change("allowNfcWrite", false);
        assertThrows(SecurityException.class, () -> call("nfc", args("transceive").put("sessionId", "synthetic-invalid-session").put("hex", "00")));
        change("allowSensorsRead", false);
        assertThrows(SecurityException.class, () -> call("sensor", args("list")));
    }

    @Test public void accelerometerSamplingAndTaskCancellationReleaseTheSubscription() throws Exception {
        SensorManager manager = context.getSystemService(SensorManager.class);
        assumeTrue("An accelerometer is required for this device check", manager != null && manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null);
        change("allowSensorsRead", true);
        JSONObject listing = call("sensor", args("list"));
        assertTrue(listing.getJSONArray("sensors").length() > 0);
        JSONObject sampled = call("sensor", args("sample").put("sensorType", Sensor.TYPE_ACCELEROMETER)
            .put("durationMs", 400).put("intervalMs", 50).put("limit", 10));
        assertEquals("sampled", sampled.getString("status"));
        assertTrue("Real accelerometer callbacks are required", sampled.getInt("sampleCount") > 0);
        assertEquals("m/s2", sampled.getJSONObject("sensor").getString("unit"));
        JSONArray rows = sampled.getJSONArray("samples");
        assertTrue(Long.parseLong(rows.getJSONObject(0).getString("elapsedRealtimeNanos")) > 0);
        String callId = UUID.randomUUID().toString();
        Future<JSONObject> pending = workers.submit(() -> bridge.executeTool("sensor", args("sample")
            .put("sensorType", Sensor.TYPE_ACCELEROMETER).put("durationMs", 5000).put("limit", 1000), owner, callId));
        SystemClock.sleep(200);
        assertFalse("The long sample must still be running before cancellation", pending.isDone());
        bridge.policy.cancel(owner, callId);
        Throwable failure = failed(pending);
        assertTrue("Cancelled sampling must not return success", failure instanceof InterruptedException);
        JSONObject next = call("sensor", args("read").put("sensorType", Sensor.TYPE_ACCELEROMETER).put("durationMs", 1000));
        assertEquals("sampled", next.getString("status"));
        bridge.policy.stop();
        assertThrows(IllegalStateException.class, () -> call("sensor", args("list")));
        bridge.policy.enable();
    }

    @Test public void bluetoothScanWorksWithProductReadPermissionAndControlDisabled() throws Exception {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        assumeTrue("Bluetooth hardware and system scan/connect grants are required", manager != null && manager.getAdapter() != null
            && granted(Build.VERSION.SDK_INT >= 31 ? Manifest.permission.BLUETOOTH_SCAN : Manifest.permission.ACCESS_FINE_LOCATION)
            && granted(Build.VERSION.SDK_INT >= 31 ? Manifest.permission.BLUETOOTH_CONNECT : Manifest.permission.BLUETOOTH));
        assumeTrue("Bluetooth must already be enabled", manager.getAdapter().isEnabled());
        change("allowBluetoothRead", true); change("allowBluetoothControl", false);
        JSONObject result = call("bluetooth", args("scan").put("transport", "ble").put("durationMs", 300).put("limit", 5));
        assertEquals("completed", result.getString("status"));
        assertTrue(result.has("devices"));
        assertTrue(result.getLong("finishedAt") >= result.getLong("startedAt"));
        assertThrows(SecurityException.class, () -> call("bluetooth", args("connect").put("deviceRef", "synthetic-invalid-device")));
    }

    @Test public void wifiAndCellularQueriesPreserveDataTimingAndUnsupportedFields() throws Exception {
        change("allowWifiRead", true); change("allowWifiControl", false); change("allowCellularRead", true);
        JSONObject wifi = call("wifi", args("status"));
        assertEquals("queried", wifi.getString("status"));
        assertTrue(wifi.getLong("capturedAt") > 0);
        assertTrue(wifi.has("networks"));
        LocationManager location = context.getSystemService(LocationManager.class);
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) && location != null && location.isLocationEnabled()) {
            JSONObject scan = call("wifi", args("scan").put("timeoutMs", 300).put("limit", 5));
            assertTrue(scan.has("cachedResultsPossible"));
            for (int i = 0; i < scan.getJSONArray("results").length(); i++) {
                JSONObject row = scan.getJSONArray("results").getJSONObject(i);
                assertTrue(row.getLong("ageMs") >= 0);
                assertTrue(row.has("observedElapsedMicros"));
                assertTrue(row.has("informationElements"));
            }
        } else {
            assertThrows(ActionException.class, () -> call("wifi", args("scan").put("timeoutMs", 100)));
        }
        JSONObject cellular = call("cellular", args("status"));
        assertFalse(cellular.getBoolean("subscriberPriorityAvailable"));
        if (cellular.getBoolean("supported") && granted(Manifest.permission.READ_PHONE_STATE)) {
            JSONObject radio = call("cellular", args("query").put("includeCells", false));
            assertTrue(radio.getLong("capturedAt") > 0);
            assertTrue(radio.has("subscriptions"));
            assertTrue(radio.isNull("subscriberPriority"));
        } else if (cellular.getBoolean("supported")) {
            assertThrows(ActionException.class, () -> call("cellular", args("query").put("includeCells", false)));
        }
    }

    @Test public void savedBinaryDataSurvivesOwnerChangesButTemporaryDataDoesNotLeak() throws Exception {
        change("allowDeviceDataRead", true); change("allowDeviceDataWrite", true);
        JSONObject saved = call("device_data", args("save").put("hex", "00017f80ff")
            .put("name", "synthetic-hardware-regression").put("metadata", new JSONObject().put("coverage", "synthetic_complete")));
        String ref = saved.getString("dataRef"); savedRefs.add(ref);
        DeviceDataStore store = new DeviceDataStore(context);
        String temporary = store.put(owner, new byte[]{0, (byte) 255}, new JSONObject().put("coverage", "synthetic_partial"), false,
            "synthetic-temporary").getString("dataRef");
        String other = owner + "-other"; owners.add(other);
        bridge.policy.end(owner);
        JSONObject read = bridge.executeTool("device_data", args("read").put("dataRef", ref), other, UUID.randomUUID().toString());
        assertEquals("00017f80ff", read.getString("hex"));
        assertTrue(read.getBoolean("integrityVerified"));
        assertEquals("synthetic_complete", read.getJSONObject("metadata").getString("coverage"));
        assertThrows(ActionException.class, () -> bridge.executeTool("device_data", args("read").put("dataRef", temporary), other, UUID.randomUUID().toString()));
        bridge.policy.end(other);
        call("device_data", args("delete").put("dataRef", ref)); savedRefs.remove(ref);
        assertThrows(ActionException.class, () -> call("device_data", args("read").put("dataRef", ref)));
        HardwareRuntime.get(context).closeOwner(owner);
        assertThrows(ActionException.class, () -> store.readPayload(new JSONObject().put("dataRef", temporary), owner, 2));
    }

    @Test public void numericNetworkProbeUsesOnlyALocalTcpFixture() throws Exception {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        assumeTrue("A selected Android Network is required", connectivity != null && connectivity.getActiveNetwork() != null);
        change("allowNetworkProbe", true);
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            Future<Boolean> accepted = workers.submit(() -> { try (Socket ignored = server.accept()) { return true; } });
            JSONObject measured = call("network_probe", args("probe").put("network", "default").put("host", "127.0.0.1")
                .put("port", server.getLocalPort()).put("count", 1).put("timeoutMs", 2000));
            assertEquals("measured", measured.getString("status"));
            assertEquals(0, measured.getInt("tcpApplicationPayloadBytesSent"));
            assertEquals("connected", measured.getJSONArray("attempts").getJSONObject(0).getString("status"));
            assertTrue(accepted.get(3, TimeUnit.SECONDS));
            assertTrue(measured.has("networkId"));
        }
    }

    @Test public void nfcNoCardTimeoutAndCancellationLeaveNoReaderMode() throws Exception {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
        assumeTrue("An enabled NFC adapter is required", adapter != null && adapter.isEnabled());
        assumeTrue("An active user HCE profile must not be disturbed", context.getSharedPreferences("nfc-hce", Context.MODE_PRIVATE)
            .getString("active", "").isEmpty());
        change("allowNfcRead", true); change("allowNfcWrite", false);
        try {
            call("nfc", args("scan").put("timeoutMs", 700));
            call("nfc", args("close"));
            assumeTrue("Remove any physical card to test the no-card timeout", false);
        } catch (ActionException failure) {
            assertEquals("nfc_timeout", failure.getCode());
        }
        instrumentation.waitForIdleSync();
        assertFalse("Timed-out NFC scan left a reader active", NfcTools.readerActive());
        String callId = UUID.randomUUID().toString();
        Future<JSONObject> pending = workers.submit(() -> bridge.executeTool("nfc", args("scan").put("timeoutMs", 5000), owner, callId));
        long deadline = SystemClock.elapsedRealtime() + 2000;
        while (!NfcTools.readerActive() && !pending.isDone() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25);
        assertTrue("The cancellation check needs an active foreground reader", NfcTools.readerActive());
        bridge.policy.cancel(owner, callId);
        failed(pending);
        instrumentation.waitForIdleSync();
        assertFalse("Cancelled NFC scan left a reader active", NfcTools.readerActive());
        for (String language : new String[]{"zh", "en"}) {
            change("languagePreference", language);
            Future<JSONObject> visible = workers.submit(() -> call("nfc", args("scan").put("timeoutMs", 5000)));
            String label = HardwareSupport.text(context, io.droyagent.androiduse.R.string.nfc_cancel);
            android.graphics.Rect button = new android.graphics.Rect();
            DeviceUi.await("The foreground NFC Cancel button did not appear", 2000, () -> {
                android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
                if (root == null || !context.getPackageName().contentEquals(root.getPackageName())) return false;
                for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label))
                    if (node.getText() != null && label.equalsIgnoreCase(node.getText().toString()) && node.isClickable()) { node.getBoundsInScreen(button); return !button.isEmpty(); }
                return false;
            });
            instrumentation.getUiAutomation().waitForIdle(400, 2500);
            SystemClock.sleep(350);
            android.graphics.Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
            assertNotNull(screenshot);
            java.io.File directory = new java.io.File(context.getExternalFilesDir(null), "hardware-native-ui-evidence");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(new java.io.File(directory, "reader-" + language + ".png"))) {
                assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream));
            } finally { screenshot.recycle(); }
            new DeviceUi(instrumentation, null).tapAt(button.centerX(), button.centerY());
            failed(visible);
            instrumentation.waitForIdleSync();
            assertFalse("The NFC Cancel touch left a reader active", NfcTools.readerActive());
        }
    }

    @Test public void locationStatusAndInfraredValidationUseBothSavedLanguagesWithoutEmitting() throws Exception {
        change("allowLocationRead", false);
        JSONObject location = call("location", args("status"));
        assertTrue(location.has("enabled"));
        assertThrows(SecurityException.class, () -> call("location", args("current").put("durationMs", 100)));
        change("allowSensorsRead", true);
        for (String language : new String[]{"zh", "en"}) {
            change("languagePreference", language);
            ActionException error = assertThrows(ActionException.class, () -> call("sensor", args("read").put("sensorType", Integer.MAX_VALUE)));
            assertEquals("unsupported_hardware", error.getCode());
            assertEquals(HardwareSupport.text(context, io.droyagent.androiduse.R.string.hardware_sensor_missing), error.getMessage());
        }
        change("allowInfrared", true);
        boolean supported = call("infrared", args("status")).getBoolean("supported");
        ActionException invalid = assertThrows(ActionException.class, () -> call("infrared", args("transmit").put("carrierHz", 38000)
            .put("patternMicros", new JSONArray().put(1).put(0))));
        assertEquals(supported ? "invalid_argument" : "unsupported_hardware", invalid.getCode());
    }

    @Test public void locationSamplingCanBeCancelledWithoutReportingACreatedFix() throws Exception {
        LocationManager manager = context.getSystemService(LocationManager.class);
        assumeTrue("System location access and an enabled provider are required", manager != null && manager.isLocationEnabled()
            && (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)));
        boolean providerAvailable = false;
        for (String provider : new String[]{"fused", "network", "gps"})
            if (manager.getAllProviders().contains(provider) && manager.isProviderEnabled(provider)
                && (!provider.equals("gps") || granted(Manifest.permission.ACCESS_FINE_LOCATION))) providerAvailable = true;
        assumeTrue("No permitted active location provider is available", providerAvailable);
        change("allowLocationRead", true);
        String callId = UUID.randomUUID().toString();
        Future<JSONObject> pending = workers.submit(() -> bridge.executeTool("location", args("sample")
            .put("durationMs", 5000).put("intervalMs", 1000).put("limit", 40), owner, callId));
        SystemClock.sleep(200);
        assertFalse("Location sampling must still be active before cancellation", pending.isDone());
        bridge.policy.cancel(owner, callId);
        assertTrue("Cancelled location sampling must not report a fix", failed(pending) instanceof InterruptedException);
        JSONObject status = call("location", args("status"));
        assertFalse("A status query must not invent coordinates", status.has("location"));
    }

    @Test public void realCurrentLocationFixIsFreshAndRemainsInTheCurrentTask() throws Exception {
        assumeTrue("Explicit hardwareAcquireLocation=true is required for real-location acquisition",
            "true".equals(InstrumentationRegistry.getArguments().getString("hardwareAcquireLocation")));
        LocationManager manager = context.getSystemService(LocationManager.class);
        assertTrue("Enable system location and grant precise location for this acquisition fixture",
            manager != null && manager.isLocationEnabled() && granted(Manifest.permission.ACCESS_FINE_LOCATION));
        change("allowLocationRead", true);
        JSONObject result = call("location", args("current").put("durationMs", 10000).put("maxAgeMs", 0));
        assertEquals("No fresh location fix was acquired before the deadline", "located", result.getString("status"));
        assertEquals("current_task", result.getString("collectionScope"));
        assertEquals("WGS84", result.getString("coordinateReference"));
        JSONObject location = result.getJSONObject("location");
        assertTrue(location.getDouble("accuracyMeters") >= 0);
        assertTrue("A fresh observation must not become a saved data record", !result.has("dataRef"));
    }

    private JSONObject call(String action, JSONObject args) throws Exception {
        return bridge.executeTool(action, args, owner, UUID.randomUUID().toString());
    }
    private static JSONObject args(String operation) throws Exception { return new JSONObject().put("operation", operation); }
    private boolean granted(String permission) { return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private void remember(String key) {
        if (previous.containsKey(key) || absent.contains(key)) return;
        if (preferences.contains(key)) previous.put(key, preferences.getAll().get(key)); else absent.add(key);
    }
    private void change(String key, Object value) {
        remember(key);
        SharedPreferences.Editor editor = preferences.edit();
        if (value instanceof Boolean flag) editor.putBoolean(key, flag); else editor.putString(key, (String) value);
        assertTrue("Could not set a scoped test preference", editor.commit());
        instrumentation.waitForIdleSync();
    }
    private static Throwable failed(Future<?> pending) throws Exception {
        try { pending.get(4, TimeUnit.SECONDS); fail("The cancelled operation returned success"); return null; }
        catch (ExecutionException failure) { return failure.getCause(); }
    }
}
