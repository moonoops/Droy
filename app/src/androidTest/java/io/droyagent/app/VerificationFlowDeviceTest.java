package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.AccessibilityRecovery;
import io.droyagent.androiduse.DroyAccessibilityService;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Local fixture of product gating and manual touch recovery; no CAPTCHA provider or model. */
@RunWith(AndroidJUnit4.class)
public final class VerificationFlowDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final Map<String, Boolean> previous = new HashMap<>();
    private final String[] keys = {"allowObserve", "allowScreenshot", "allowControl", "allowApps", "allowBrowser", "allowBrowserControl"};
    private SharedPreferences settings;
    private ProductBridge bridge;
    private TestPageActivity activity;
    private int call;

    @Before public void setUp() throws Exception {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityRecovery.get(context).onAppOpened(false);
        DeviceUi.await("Accessibility did not connect", 15000, () -> DroyAccessibilityService.isConnected(context));
        settings = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = settings.edit();
        for (String key : keys) {
            if (settings.contains(key)) previous.put(key, settings.getBoolean(key, false));
            editor.putBoolean(key, true);
        }
        assertTrue(editor.commit());
        activity = (TestPageActivity) instrumentation.startActivitySync(new Intent(context, TestPageActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(() -> ((TextView) activity.findViewById(R.id.test_result)).setText("请完成本地图形验证"));
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).waitForIdle(300, 5000);
        bridge = new ProductBridge(context, "fictional-verification-device-token");
        bridge.policy.enable();
    }

    @After public void tearDown() {
        if (bridge != null) bridge.policy.stop();
        if (settings != null) {
            SharedPreferences.Editor restore = settings.edit();
            for (String key : keys) {
                if (previous.containsKey(key)) restore.putBoolean(key, previous.get(key)); else restore.remove(key);
            }
            assertTrue(restore.commit());
        }
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
    }

    @Test public void graphicalAttemptUsesCurrentScreenshotThenVerifiesActualFixtureResult() throws Exception {
        JSONObject image = currentScreenshot();
        JSONObject assessed = call("verification", basis(image).put("operation", "assess").put("kind", "ordered_points")
                .put("solvable", true).put("imageSupported", true));
        assertEquals("automatic_allowed", assessed.getString("state"));
        JSONObject before = call("observe", new JSONObject());
        JSONObject result = call("tap", basis(before).put("nodeId", node(before, "test_confirm").getString("nodeId")));
        assertEquals("action_completed", result.getString("status"));
        // Accessibility can acknowledge ACTION_CLICK before its content-change event updates the tree.
        JSONObject after = confirmedObservation();
        JSONObject completed = call("verification", basis(after).put("operation", "complete").put("passed", true));
        assertEquals("verified", completed.getString("state"));
        assertFalse(completed.getBoolean("goalVerified"));
    }

    @Test public void manualFlowBlocksAgentTouchButAllowsActualUserTouchAndFreshReview() throws Exception {
        JSONObject before = call("observe", new JSONObject());
        JSONObject assessed = call("verification", basis(before).put("operation", "assess").put("kind", "otp")
                .put("solvable", false).put("imageSupported", true));
        assertEquals("manual_required", assessed.getString("state"));
        expect("verification_requires_user", () -> call("tap", basis(before)
                .put("nodeId", node(before, "test_confirm").getString("nodeId"))));
        expect("verification_requires_user", () -> call("open_app", new JSONObject().put("packageName", context.getPackageName())));
        expect("verification_requires_user", () -> call("browser_open", new JSONObject().put("url", "https://example.invalid")));
        call("verification", new JSONObject().put("operation", "handoff"));
        actualTouch(activity.findViewById(R.id.test_confirm));
        JSONObject returned = call("verification", new JSONObject().put("operation", "return_to_app"));
        assertTrue(returned.getBoolean("alreadyForeground"));
        assertFalse(returned.getBoolean("goalVerified"));
        JSONObject after = confirmedObservation();
        assertEquals("awaiting_review", call("verification", basis(after).put("operation", "resume")).getString("state"));
        expect("verification_review_required", () -> call("tap", basis(after)
                .put("nodeId", node(after, "test_confirm").getString("nodeId"))));
        assertEquals("verified", call("verification", basis(after).put("operation", "complete").put("passed", true)).getString("state"));
    }

    @Test public void verificationStillHonorsPermissionRevocationAndProductStop() throws Exception {
        JSONObject before = call("observe", new JSONObject());
        assertTrue(settings.edit().putBoolean("allowControl", false).commit());
        try {
            call("verification", basis(before).put("operation", "assess").put("kind", "unknown")
                    .put("solvable", false).put("imageSupported", false));
            fail("Verification ignored control revocation");
        } catch (SecurityException expected) { assertTrue(expected.getMessage().contains("允许")); }
        assertTrue(settings.edit().putBoolean("allowControl", true).commit());
        bridge.policy.stop();
        try {
            call("verification", basis(before).put("operation", "assess").put("kind", "unknown")
                    .put("solvable", false).put("imageSupported", false));
            fail("Verification ignored product stop");
        } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("暂停")); }
    }

    private JSONObject call(String action, JSONObject args) throws Exception {
        return bridge.executeTool(action, args, "verification-device-fixture", "fixture-call-" + ++call);
    }
    private JSONObject currentScreenshot() throws Exception {
        JSONObject image = call("screenshot", new JSONObject());
        if (!image.has("snapshotId")) {
            assertEquals("unavailable", image.getString("coordinateBasisStatus"));
            assertTrue("Rejected screenshot must explain its missing coordinate basis",
                    image.has("coordinateBasisReason"));
            // A capture can overlap a real window event. Its pixels remain readable,
            // but cannot authorize coordinates; reacquire once after the fixture settles.
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).waitForIdle(300, 5000);
            SystemClock.sleep(400);
            image = call("screenshot", new JSONObject());
        }
        assertEquals("Screenshot still lacks a current basis: " + image.optString("coordinateBasisReason"),
                "available", image.getString("coordinateBasisStatus"));
        assertTrue(image.has("snapshotId"));
        return image;
    }
    private JSONObject confirmedObservation() throws Exception {
        JSONObject waited = call("wait_for", new JSONObject().put("timeoutMs", 3000)
                .put("condition", new JSONObject().put("packageName", context.getPackageName()).put("text", "已确认：")));
        assertEquals("The fixture did not expose the confirmed result", "matched", waited.getString("status"));
        JSONObject observed = waited.getJSONObject("observation");
        assertEquals("已确认：", node(observed, "test_result").getString("text"));
        return observed;
    }
    private static JSONObject basis(JSONObject observation) throws Exception {
        return new JSONObject().put("snapshotId", observation.getString("snapshotId"));
    }
    private static JSONObject node(JSONObject observation, String suffix) throws Exception {
        JSONArray nodes = observation.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if (node.optString("resourceId").endsWith("/" + suffix)) return node;
        }
        throw new AssertionError("Fixture node missing: " + suffix);
    }
    private void actualTouch(View view) {
        int[] location = new int[2];
        float[] point = new float[2];
        instrumentation.runOnMainSync(() -> {
            view.getLocationOnScreen(location);
            point[0] = location[0] + view.getWidth() / 2f;
            point[1] = location[1] + view.getHeight() / 2f;
        });
        long downTime = SystemClock.uptimeMillis();
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point[0], point[1], 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try { assertTrue(automation.injectInputEvent(event, true)); }
            finally { event.recycle(); }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(60);
        }
        instrumentation.waitForIdleSync();
    }
    private static void expect(String code, Throwing action) throws Exception {
        try { action.run(); fail("Expected " + code); }
        catch (ActionException actual) { assertEquals(code, actual.getCode()); }
    }
    private interface Throwing { void run() throws Exception; }
}
