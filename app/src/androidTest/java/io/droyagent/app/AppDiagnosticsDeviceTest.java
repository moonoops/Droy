package io.droyagent.app;

import android.Manifest;
import android.app.Instrumentation;
import android.app.ApplicationExitInfo;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.os.Build;
import android.os.IBinder;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.AppDiagnosticsTools;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.PersonalDataTools;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Queries only the Droy and instrumentation packages; no private application data or model calls. */
@RunWith(AndroidJUnit4.class)
public final class AppDiagnosticsDeviceTest {
    private static final Cancellation ACTIVE = () -> {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Diagnostic fixture stopped");
    };

    @Test public void packageQueryIncludesInstalledPackagesWithoutLauncher() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        AppDiagnosticsTools tools = new AppDiagnosticsTools(context);
        PackageInfo expected = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
        JSONObject item = tools.execute("app_info", new JSONObject().put("packageName", context.getPackageName()), ACTIVE)
                .getJSONArray("items").getJSONObject(0);
        assertEquals(expected.applicationInfo.uid, item.getInt("uid"));
        assertEquals(expected.getLongVersionCode(), item.getLong("versionCode"));
        assertEquals(expected.applicationInfo.targetSdkVersion, item.getInt("targetSdk"));
        assertTrue(item.getInt("permissionCount") > 0);
        String fixture = instrumentation.getContext().getPackageName();
        assertNull(context.getPackageManager().getLaunchIntentForPackage(fixture));
        JSONObject nonLauncher = tools.execute("app_info", new JSONObject().put("packageName", fixture), ACTIVE);
        assertEquals(fixture, nonLauncher.getJSONArray("items").getJSONObject(0).getString("packageName"));
        JSONObject platform = tools.execute("app_info", new JSONObject().put("packageName", "android"), ACTIVE);
        assertEquals("android", platform.getJSONArray("items").getJSONObject(0).getString("packageName"));
        JSONObject first = tools.execute("app_info", new JSONObject().put("query", context.getPackageName()).put("limit", 1), ACTIVE);
        assertTrue(first.getInt("totalMatches") >= 2);
        assertEquals(1, first.getInt("nextOffset"));
        JSONObject second = tools.execute("app_info", new JSONObject().put("query", context.getPackageName())
                .put("limit", 1).put("offset", first.getInt("nextOffset")), ACTIVE);
        assertEquals(first.getInt("totalMatches"), second.getInt("totalMatches"));
        assertNotEquals(first.getJSONArray("items").getJSONObject(0).getString("packageName"),
                second.getJSONArray("items").getJSONObject(0).getString("packageName"));
    }

    @Test public void ownExitQueryKeepsSystemReasonAndExplicitRetention() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject result = new AppDiagnosticsTools(context).execute("app_exit_info", new JSONObject()
                .put("packageName", context.getPackageName()).put("limit", 2), ACTIVE);
        assertEquals("android_application_exit_info", result.getString("source"));
        assertEquals("recent_system_ring_buffer", result.getString("retention"));
        assertTrue(result.getBoolean("readOnly"));
        JSONArray items = result.getJSONArray("items");
        assertTrue(items.length() <= 2);
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            assertTrue(item.has("reasonCode"));
            assertTrue(item.getLong("timestampMs") > 0);
            assertFalse("Trace must be opt-in", item.has("trace"));
        }
    }

    @Test public void separateUidSelfExitHasActualPidTimeAndReason() throws Exception {
        JSONObject exit = fixtureExit("exit_self", ApplicationExitInfo.REASON_EXIT_SELF);
        assertEquals("exit_self", exit.getString("reason"));
        assertEquals(73, exit.getInt("exitStatus"));
    }

    @Test public void separateUidJavaCrashHasActualPidTimeAndReason() throws Exception {
        JSONObject exit = fixtureExit("java_crash", ApplicationExitInfo.REASON_CRASH);
        assertEquals("java_crash", exit.getString("reason"));
    }

    /** Run alone after the host revokes DUMP, never revoke it inside instrumentation. */
    @Test public void anotherUidExitQueryRequiresDumpPermission() throws Exception {
        requireEmulator();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        assertEquals("Run this method separately without DUMP", PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(Manifest.permission.DUMP));
        String target = instrumentation.getContext().getPackageName();
        int fixtureUid = context.getPackageManager().getApplicationInfo(target, 0).uid;
        assertNotEquals(android.os.Process.myUid(), fixtureUid);
        try {
            new AppDiagnosticsTools(context).execute("app_exit_info", new JSONObject().put("packageName", target), ACTIVE);
            fail("Cross UID exit query must fail without DUMP");
        } catch (ActionException expected) { assertEquals("dump_permission_required", expected.getCode()); }
    }

    private JSONObject fixtureExit(String mode, int expectedReason) throws Exception {
        requireEmulator();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        Context context = instrumentation.getTargetContext();
        assertEquals("Grant DUMP before this method", PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.DUMP));
        String target = instrumentation.getContext().getPackageName();
        int fixtureUid = context.getPackageManager().getApplicationInfo(target, 0).uid;
        assertNotEquals(android.os.Process.myUid(), fixtureUid);
        CountDownLatch connected = new CountDownLatch(1);
        AtomicInteger pid = new AtomicInteger(-1);
        AtomicReference<Exception> connectionFailure = new AtomicReference<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                try { pid.set(Integer.parseInt(service.getInterfaceDescriptor())); }
                catch (Exception failure) { connectionFailure.set(failure); }
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        long start = System.currentTimeMillis();
        Intent intent = new Intent().setComponent(new ComponentName(target, DiagnosticExitFixtureService.class.getName())).putExtra("mode", mode);
        boolean bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        assertTrue("The separate test process must start", bound);
        try {
            assertTrue("Fixture did not provide its PID", connected.await(5, TimeUnit.SECONDS));
            if (connectionFailure.get() != null) throw connectionFailure.get();
            assertTrue(pid.get() > 0);
        } finally { context.unbindService(connection); }
        AppDiagnosticsTools tools = new AppDiagnosticsTools(context);
        long deadline = SystemClock.elapsedRealtime() + 15000;
        do {
            JSONObject records = tools.execute("app_exit_info", new JSONObject().put("packageName", target).put("limit", 10), ACTIVE);
            for (int i = 0; i < records.getJSONArray("items").length(); i++) {
                JSONObject item = records.getJSONArray("items").getJSONObject(i);
                if (item.getInt("pid") == pid.get() && item.getLong("timestampMs") >= start) {
                    // Android 11 can publish the death record before the separate
                    // zygote SIGCHLD message supplies the final reason and status.
                    if (item.getInt("reasonCode") == ApplicationExitInfo.REASON_UNKNOWN) continue;
                    assertEquals(expectedReason, item.getInt("reasonCode"));
                    assertEquals(fixtureUid, item.getInt("uid"));
                    assertTrue(item.getString("processName").endsWith(":diagnostic_exit_fixture"));
                    assertTrue(item.getLong("timestampMs") <= System.currentTimeMillis());
                    assertFalse("Trace must remain opt-in", item.has("trace"));
                    return item;
                }
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("The actual separate-process exit was not recorded for PID " + pid.get());
    }

    private static void requireEmulator() {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        String fingerprint = Build.FINGERPRINT.toLowerCase(Locale.ROOT);
        assertTrue("Destructive exit fixtures require a dedicated emulator",
                (hardware.contains("ranchu") || hardware.contains("goldfish"))
                        && (fingerprint.contains("generic") || fingerprint.contains("emulator") || fingerprint.contains("sdk_gphone")));
    }

    @Test public void absentPackageAndCancelledQueriesDoNotReturnEmptySuccess() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AppDiagnosticsTools tools = new AppDiagnosticsTools(context);
        try {
            tools.execute("app_info", new JSONObject().put("packageName", "io.droyagent.fixture.notinstalled"), ACTIVE);
            fail("Missing package must have a visibility error");
        } catch (ActionException expected) { assertEquals("app_not_visible", expected.getCode()); }
        try {
            tools.execute("app_info", new JSONObject(), () -> { throw new InterruptedException("fixture cancelled"); });
            fail("Cancelled query must not run");
        } catch (InterruptedException expected) { assertEquals("fixture cancelled", expected.getMessage()); }
    }

    @Test public void usageEventsContainActualFixtureActivityAndNetworkUsesSystemBuckets() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        Context context = instrumentation.getTargetContext();
        AppDiagnosticsTools tools = new AppDiagnosticsTools(context);
        assertTrue("Grant usage access before this test", tools.capabilities().getJSONObject("network_usage").getBoolean("available"));
        PersonalDataTools personal = new PersonalDataTools(context);
        long start = System.currentTimeMillis() - 1000;
        TestPageActivity page = (TestPageActivity) instrumentation.startActivitySync(new Intent(context, TestPageActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.waitForIdleSync();
            long deadline = SystemClock.elapsedRealtime() + 5000;
            boolean found = false;
            do {
                JSONObject events = personal.execute("usage_query", new JSONObject().put("mode", "events")
                        .put("packageName", context.getPackageName()).put("startMs", start).put("endMs", System.currentTimeMillis()), ACTIVE);
                assertEquals("android_usage_events", events.getString("source"));
                for (int i = 0; i < events.getJSONArray("items").length(); i++) {
                    JSONObject event = events.getJSONArray("items").getJSONObject(i);
                    assertEquals(context.getPackageName(), event.getString("packageName"));
                    if (event.getString("event").equals("activity_resumed")
                            && event.getString("className").equals(TestPageActivity.class.getName())) found = true;
                }
                if (!found) SystemClock.sleep(100);
            } while (!found && SystemClock.elapsedRealtime() < deadline);
            assertTrue("Actual Activity resume must appear in system usage events", found);
            long now = System.currentTimeMillis();
            JSONObject network = tools.execute("network_usage", new JSONObject().put("packageName", context.getPackageName())
                    .put("startMs", now - 24 * 60 * 60 * 1000L).put("endMs", now).put("limit", 1), ACTIVE);
            assertEquals("android_network_stats", network.getString("source"));
            assertEquals(android.os.Process.myUid(), network.getInt("uid"));
            assertEquals("historical_buckets_not_realtime", network.getString("rangePrecision"));
            assertTrue(network.getJSONObject("totals").getLong("rxBytes") >= 0);
            assertTrue(network.getJSONObject("totals").getLong("txBytes") >= 0);
            assertTrue(network.getJSONArray("items").length() <= 1);
            assertTrue(network.getBoolean("readOnly"));
        } finally { instrumentation.runOnMainSync(page::finish); }
    }
}
