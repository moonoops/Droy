package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.AndroidUseExecutor;
import io.droyagent.androiduse.Cancellation;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Android state and control-boundary checks; no model or Root grant is used. */
@RunWith(AndroidJUnit4.class)
public final class ExpandedCapabilitiesDeviceTest {
    private static final Cancellation ACTIVE = () -> {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Expanded capability test interrupted");
    };
    private static final String[] GRANTS = {"allowDeviceInfo", "allowApps", "allowObserve", "allowControl", "allowRoot"};
    private Instrumentation instrumentation;
    private Context context;
    private AndroidUseExecutor executor;
    private TestPageActivity activity;
    private MainActivity productActivity;
    private boolean ownsRuntime;
    private SharedPreferences settings;
    private final Map<String, Boolean> previousGrants = new HashMap<>();

    @Before public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        executor = new AndroidUseExecutor(context);
        settings = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        for (String key : GRANTS) if (settings.contains(key)) previousGrants.put(key, settings.getBoolean(key, false));
    }

    @After public void tearDown() throws Exception {
        if (productActivity != null) instrumentation.runOnMainSync(productActivity::finish);
        if (ownsRuntime) {
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("Product UI test left its automatic engine startup active", 15000, RuntimeService::canManageHistory);
        }
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
        SharedPreferences.Editor restore = settings.edit();
        for (String key : GRANTS) {
            if (previousGrants.containsKey(key)) restore.putBoolean(key, previousGrants.get(key));
            else restore.remove(key);
        }
        assertTrue("Could not restore capability grants", restore.commit());
    }

    @Test public void systemInfoReturnsNativeFactsAndExplicitReadingStates() throws Exception {
        openPractice();
        long began = System.currentTimeMillis();
        JSONObject result = executor.execute("system_info", new JSONObject(), false, ACTIVE);
        assertEquals("queried", result.getString("status"));
        assertEquals("android_system", result.getString("source"));
        assertTrue(result.getLong("capturedAt") >= began);
        assertTrue(result.getLong("capturedAt") <= System.currentTimeMillis());
        JSONObject sections = result.getJSONObject("sections");
        assertEquals(5, sections.length());
        assertEquals("available", sections.getJSONObject("device").getString("status"));
        assertEquals(android.os.Build.VERSION.SDK_INT, sections.getJSONObject("device").getInt("sdkInt"));
        assertEquals("available", sections.getJSONObject("battery").getString("status"));
        assertTrue(sections.getJSONObject("battery").has("percent"));
        for (String name : new String[]{"battery", "network", "memory", "storage", "device"}) {
            JSONObject section = sections.getJSONObject(name);
            assertTrue(section.getString("status").equals("available") || section.getString("status").equals("unavailable"));
            if (section.getString("status").equals("unavailable")) assertFalse(section.getString("reason").isEmpty());
        }
        assertFalse(sections.getJSONObject("device").has("androidId"));
        assertFalse(sections.getJSONObject("device").has("serial"));
        JSONObject selected = executor.execute("system_info", new JSONObject()
                .put("sections", new JSONArray().put("battery")), false, ACTIVE);
        assertEquals(1, selected.getJSONObject("sections").length());
    }

    @Test public void directInfoWorksOnProductUiWithoutObserveAndStopsWithItsGrant() throws Exception {
        openPractice();
        assertTrue(settings.edit().putBoolean("allowDeviceInfo", true).putBoolean("allowObserve", false)
                .putBoolean("allowControl", false).putBoolean("allowRoot", false).commit());
        ownsRuntime = RuntimeService.canManageHistory();
        productActivity = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        assertTrue(MainActivity.isSensitiveUiVisible());
        ProductBridge bridge = new ProductBridge(context, "expanded-test-" + UUID.randomUUID());
        bridge.policy.enable();
        try {
            JSONObject result = bridge.executeTool("system_info", new JSONObject()
                    .put("sections", new JSONArray().put("battery")), "expanded-info", "allowed-info");
            assertEquals("queried", result.getString("status"));
            expectSecurity(() -> bridge.executeTool("observe", new JSONObject(), "expanded-info", "blocked-screen"));
            assertTrue(settings.edit().putBoolean("allowDeviceInfo", false).commit());
            expectSecurity(() -> bridge.executeTool("system_info", new JSONObject(), "expanded-info", "revoked-info"));
            assertTrue(settings.edit().putBoolean("allowDeviceInfo", true).commit());
            bridge.policy.stop();
            try {
                bridge.executeTool("system_info", new JSONObject(), "expanded-info", "stopped-info");
                fail("Stopped execution must reject even read-only device queries");
            } catch (IllegalStateException expected) { }
        } finally { bridge.policy.stop(); }
    }

    @Test public void appLookupReturnsVisibleLabelsAndPreservesPackageIdentity() throws Exception {
        openPractice();
        String label = context.getPackageManager().getApplicationLabel(context.getApplicationInfo()).toString();
        JSONObject result = executor.execute("find_apps", new JSONObject().put("query", label).put("limit", 30), false, ACTIVE);
        boolean found = false;
        JSONArray apps = result.getJSONArray("apps");
        for (int index = 0; index < apps.length(); index++) {
            JSONObject app = apps.getJSONObject(index);
            if (context.getPackageName().equals(app.getString("packageName"))) {
                assertEquals(label, app.getString("label"));
                found = true;
            }
        }
        assertTrue("The visible Droy launcher must be discoverable by its label", found);
        assertEquals("visible_launcher_apps", result.getString("visibility"));
        assertFalse(result.getString("visibilityNote").isEmpty());
        JSONObject missing = executor.execute("find_apps", new JSONObject().put("query", "droy-missing-" + UUID.randomUUID()), false, ACTIVE);
        assertEquals("not_found", missing.getString("status"));
        JSONObject noLaunch = executor.execute("open_app", new JSONObject().put("appName", "droy-missing-" + UUID.randomUUID()), false, ACTIVE);
        assertFalse(noLaunch.getBoolean("dispatched"));
        assertFalse(noLaunch.getBoolean("goalVerified"));
    }

    @Test public void openAppAllowsSelfByPackageAndResolvedName() throws Exception {
        openPractice();
        assertTrue("Stop the existing engine before the self launch test", RuntimeService.canManageHistory());
        ownsRuntime = true;
        productActivity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        new DeviceUi(instrumentation, productActivity).awaitPage();
        for (String key : new String[]{"packageName", "appName"}) {
            JSONObject result = executor.execute("open_app", new JSONObject()
                .put(key, context.getPackageName()).put("verifyTimeoutMs", 3000), false, ACTIVE);
            assertTrue("Self launch was not dispatched", result.getBoolean("dispatched"));
            assertTrue("Droy must be verified in foreground", result.getBoolean("foregroundVerified"));
            assertEquals(context.getPackageName(), result.getString("packageName"));
        }
    }

    @Test public void openExternalLauncherVerifiesItsActualForegroundPackage() throws Exception {
        openPractice();
        ResolveInfo settingsApp = context.getPackageManager().resolveActivity(new Intent(Settings.ACTION_SETTINGS), 0);
        assertNotNull("The test device must expose its system settings activity", settingsApp);
        String packageName = settingsApp.activityInfo.packageName;
        assertNotNull("The system settings package must have a visible launcher on this device",
                context.getPackageManager().getLaunchIntentForPackage(packageName));
        try {
            JSONObject result = executor.execute("open_app", new JSONObject().put("packageName", packageName)
                    .put("verifyTimeoutMs", 5000), false, ACTIVE);
            assertTrue(result.getBoolean("dispatched"));
            assertTrue("The actual foreground package must match the requested launcher", result.getBoolean("foregroundVerified"));
            JSONObject observed = executor.execute("observe", new JSONObject(), false, ACTIVE);
            assertEquals(packageName, observed.getString("packageName"));
        } finally { openPractice(); }
    }

    @Test public void waitForReturnsUsableObservationAndTimesOutWithoutRepeatingAnAction() throws Exception {
        openPractice();
        JSONObject matched = executor.execute("wait_for", new JSONObject().put("condition", new JSONObject()
                .put("resourceId", context.getPackageName() + ":id/test_confirm")).put("timeoutMs", 3000), false, ACTIVE);
        assertEquals("matched", matched.getString("status"));
        JSONObject observation = matched.getJSONObject("observation");
        assertFalse(observation.getString("snapshotId").isEmpty());
        assertNotNull(findNode(observation, "test_confirm"));
        JSONObject timed = executor.execute("wait_for", new JSONObject().put("condition", new JSONObject()
                .put("text", "missing-" + UUID.randomUUID())).put("timeoutMs", 200), false, ACTIVE);
        assertEquals("timed_out", timed.getString("status"));
        assertFalse(timed.getBoolean("replayedAction"));
        assertEquals("尚未确认", resultText());
    }

    @Test public void waitingChecksCancellationPromptly() throws Exception {
        openPractice();
        long began = SystemClock.elapsedRealtime();
        try {
            executor.execute("wait_for", new JSONObject().put("condition", new JSONObject()
                    .put("text", "missing-" + UUID.randomUUID())).put("timeoutMs", 60000), false, () -> {
                if (SystemClock.elapsedRealtime() - began > 350) throw new InterruptedException("test stop");
            });
            fail("Cancelled wait must not return success");
        } catch (InterruptedException expected) { }
        assertTrue("Waiting ignored cancellation", SystemClock.elapsedRealtime() - began < 2000);
        assertEquals("尚未确认", resultText());
    }

    @Test public void actionCanReturnFreshLightObservationWithoutClaimingTaskCompletion() throws Exception {
        openPractice();
        instrumentation.runOnMainSync(activity::startUnrelatedUpdates);
        awaitStablePracticeLayout();
        JSONObject observed = observeUntil("test_confirm", "确认");
        Rect basisBounds = nodeBounds(findNode(observed, "test_confirm"));
        assertEquals("The single action basis must describe the settled native layout", viewBounds(R.id.test_confirm), basisBounds);
        SystemClock.sleep(200);
        JSONObject result;
        try {
            result = executor.execute("tap", new JSONObject().put("snapshotId", observed.getString("snapshotId"))
                    .put("nodeId", findNode(observed, "test_confirm").getString("nodeId"))
                    .put("afterObserve", true), false, ACTIVE);
        } catch (ActionException error) {
            if ("stale_snapshot".equals(error.getCode())) Log.i("DroyLayoutRegression",
                    "phase=afterObserve basisBounds=" + basisBounds.toShortString()
                            + " nativeBounds=" + viewBounds(R.id.test_confirm).toShortString());
            throw error;
        }
        assertEquals("action_completed", result.getString("status"));
        assertFalse(result.getBoolean("goalVerified"));
        JSONObject after = result.getJSONObject("afterObservation");
        assertEquals("observed", after.getString("status"));
        assertFalse(after.getString("snapshotId").isEmpty());
        assertTrue(after.getJSONArray("nodes").length() <= 200);
        assertTrue(after.getString("text").length() <= 24000);
        assertNotNull("The semantic result control must remain in the returned observation", findNode(after, "test_result"));
        observeUntil("test_result", "已确认：");
    }

    @Test public void coordinatesRejectMissingAndSupersededSnapshotBeforeInput() throws Exception {
        openPractice();
        Rect button = viewBounds(R.id.test_confirm);
        JSONObject coordinates = new JSONObject().put("x", button.centerX()).put("y", button.centerY());
        expectAction("stale_snapshot", () -> executor.execute("tap", coordinates, false, ACTIVE));
        JSONObject older = observeUntil("test_confirm", "确认");
        observeUntil("test_confirm", "确认");
        coordinates.put("snapshotId", older.getString("snapshotId"));
        expectAction("stale_snapshot", () -> executor.execute("tap", coordinates, false, ACTIVE));
        assertEquals("尚未确认", resultText());
    }

    @Test public void clippedNodeTextCannotProveWaitedTextIsAbsent() throws Exception {
        assertClippedFieldCannotProveAbsence(false);
    }

    @Test public void clippedNodeDescriptionCannotProveWaitedTextIsAbsent() throws Exception {
        assertClippedFieldCannotProveAbsence(true);
    }

    private void assertClippedFieldCannotProveAbsence(boolean description) throws Exception {
        openPractice();
        String marker = "droy-hidden-suffix";
        String longValue = "a".repeat(1100) + marker;
        instrumentation.runOnMainSync(() -> {
            TextView result = activity.findViewById(R.id.test_result);
            result.setSingleLine(true);
            result.setText(description ? "short fixture" : longValue);
            result.setContentDescription(description ? longValue : null);
        });
        instrumentation.waitForIdleSync();
        JSONObject observation = observeUntil("test_result", description ? "short fixture" : "a".repeat(1000));
        assertEquals(1000, findNode(observation, "test_result").getString(description ? "description" : "text").length());
        assertTrue("Any per-node clipping must mark the observation incomplete", observation.getBoolean("truncated"));
        JSONObject waiting = executor.execute("wait_for", new JSONObject().put("condition", new JSONObject()
                .put("text", marker).put("state", "absent")).put("timeoutMs", 100), false, ACTIVE);
        assertEquals("timed_out", waiting.getString("status"));
        assertEquals("observation_truncated", waiting.getString("lastObservation"));
    }

    private void openPractice() throws Exception {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
        activity = (TestPageActivity) instrumentation.startActivitySync(
                new Intent(context, TestPageActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
        io.droyagent.androiduse.AccessibilityRecovery.get(context).onAppOpened(false);
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (!executor.capabilities(false).getBoolean("accessibilityConnected") && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        assertTrue("Enable the Droy accessibility service before these tests", executor.capabilities(false).getBoolean("accessibilityConnected"));
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).waitForIdle(300, 5000);
        awaitStablePracticeLayout();
        observeUntil("test_input", null);
    }

    private void awaitStablePracticeLayout() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 7000;
        long stableSince = -1;
        String previous = null;
        do {
            AtomicReference<String> sample = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                View decor = activity.getWindow().getDecorView();
                View button = activity.findViewById(R.id.test_confirm);
                WindowInsets insets = decor.getRootWindowInsets();
                Rect visible = new Rect();
                if (!activity.hasWindowFocus() || !decor.isAttachedToWindow() || decor.isLayoutRequested()
                        || !button.isLaidOut() || button.isLayoutRequested() || !button.getGlobalVisibleRect(visible) || insets == null) return;
                int[] position = new int[2];
                button.getLocationOnScreen(position);
                sample.set(position[0] + ":" + position[1] + ":" + button.getWidth() + ":" + button.getHeight()
                        + ":" + decor.getWidth() + ":" + decor.getHeight()
                        + ":" + insets.getInsets(WindowInsets.Type.systemBars())
                        + ":" + insets.getInsets(WindowInsets.Type.ime()) + ":" + insets.isVisible(WindowInsets.Type.ime()));
            });
            long now = SystemClock.elapsedRealtime();
            String current = sample.get();
            if (current != null && current.equals(previous)) {
                if (now - stableSince >= 300) return;
            } else {
                previous = current;
                stableSince = current == null ? -1 : now;
            }
            SystemClock.sleep(32);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Practice window focus, layout and system/IME insets did not settle before creating the action basis");
    }

    private static Rect nodeBounds(JSONObject node) throws Exception {
        JSONObject bounds = node.getJSONObject("bounds");
        return new Rect(bounds.getInt("left"), bounds.getInt("top"), bounds.getInt("right"), bounds.getInt("bottom"));
    }

    private JSONObject observeUntil(String resource, String text) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        do {
            try {
                JSONObject observed = executor.execute("observe", new JSONObject(), false, ACTIVE);
                JSONObject node = findNodeOrNull(observed, resource);
                if (context.getPackageName().equals(observed.optString("packageName")) && node != null
                        && (text == null || text.equals(node.optString("text")))) return observed;
            } catch (ActionException unavailable) {
                if (!unavailable.getCode().equals("window_unavailable")) throw unavailable;
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Practice fixture did not expose expected state for " + resource);
    }

    private static JSONObject findNodeOrNull(JSONObject observation, String resource) throws Exception {
        JSONArray nodes = observation.getJSONArray("nodes");
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject node = nodes.getJSONObject(index);
            if (node.optString("resourceId").endsWith(":id/" + resource)) return node;
        }
        return null;
    }

    private static JSONObject findNode(JSONObject observation, String resource) throws Exception {
        JSONObject node = findNodeOrNull(observation, resource);
        assertNotNull("Missing fixture node " + resource, node);
        return node;
    }

    private String resultText() {
        AtomicReference<String> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(((TextView) activity.findViewById(R.id.test_result)).getText().toString()));
        return result.get();
    }

    private Rect viewBounds(int id) {
        AtomicReference<Rect> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            View view = activity.findViewById(id);
            Rect bounds = new Rect();
            assertTrue(view.getGlobalVisibleRect(bounds));
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            result.set(new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight()));
        });
        return result.get();
    }

    private static void expectAction(String code, Checked operation) throws Exception {
        try { operation.run(); fail("Expected native rejection " + code); }
        catch (ActionException expected) { assertEquals(code, expected.getCode()); }
    }
    private static void expectSecurity(Checked operation) throws Exception {
        try { operation.run(); fail("Expected product permission rejection"); }
        catch (SecurityException expected) { }
    }
    private interface Checked { void run() throws Exception; }
}
