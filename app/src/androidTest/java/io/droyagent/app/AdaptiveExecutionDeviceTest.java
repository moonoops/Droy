package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.AndroidUseExecutor;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.DroyAccessibilityService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Uses the local practice app and no model or third-party app. */
@RunWith(AndroidJUnit4.class)
public class AdaptiveExecutionDeviceTest {
    private final Cancellation active = () -> {};
    private Instrumentation instrumentation;
    private Context context;
    private TestPageActivity activity;
    private AndroidUseExecutor executor;

    @Before public void open() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        io.droyagent.androiduse.AccessibilityRecovery.get(context).onAppOpened(false);
        activity = (TestPageActivity) instrumentation.startActivitySync(new Intent(context, TestPageActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        executor = new AndroidUseExecutor(context);
        DeviceUi.await("Accessibility must connect", 8000, () -> DroyAccessibilityService.isConnected(context));
    }

    @After public void close() { if (activity != null) instrumentation.runOnMainSync(activity::finish); }

    @Test public void rootDisabledStillUsesAvailableChannelPreferences() throws Exception {
        for (String preferred : new String[]{"root", "shizuku", "auto"}) {
            Thread.sleep(400);
            JSONObject captured = executor.execute("screenshot", new JSONObject().put("channel", preferred), false, active);
            io.droyagent.androiduse.ShizukuAccess shizuku = io.droyagent.androiduse.ShizukuAccess.get(context);
            String expected = preferred.equals("shizuku") && shizuku.available() && shizuku.uid() != 0 ? "shizuku" : "accessibility";
            assertEquals(expected, captured.getString("channel"));
            assertEquals("image/png", captured.getString("mimeType"));
        }
    }

    @Test public void rootPreferenceCannotRemoveSemanticUnicodeInput() throws Exception {
        JSONObject observed = observe();
        JSONObject input = node(observed, "test_input");
        JSONObject result = executor.execute("input_text", new JSONObject().put("channel", "root")
                .put("snapshotId", observed.getString("snapshotId")).put("nodeId", input.getString("nodeId"))
                .put("text", "权限自适应 你好🌙"), false, active);
        assertEquals("accessibility", result.getString("channel"));
        assertTrue(result.getBoolean("inputTextVerified"));
    }

    @Test public void bridgeUsesActualBackendAuthorizationAndStopStillApplies() throws Exception {
        SharedPreferences settings = ProductPermissions.preferences(context);
        String[] keys = {"allowRoot", "allowObserve", "allowControl", "allowScreenshot"};
        java.util.Map<String, ?> before = settings.getAll();
        ProductBridge bridge = new ProductBridge(context, "fictional-adaptive-device-token");
        try {
            settings.edit().putBoolean("allowRoot", false).putBoolean("allowObserve", true)
                    .putBoolean("allowControl", true).putBoolean("allowScreenshot", true).commit();
            bridge.policy.enable();
            JSONObject result = bridge.executeTool("screenshot", new JSONObject().put("channel", "root"), "adaptive-test", "capture");
            assertEquals("accessibility", result.getString("channel"));
            JSONObject observed = observe();
            JSONObject button = node(observed, "test_confirm");
            bridge.policy.stop();
            assertThrows(IllegalStateException.class, () -> bridge.executeTool("tap", new JSONObject().put("channel", "root")
                    .put("snapshotId", observed.getString("snapshotId")).put("nodeId", button.getString("nodeId")), "adaptive-test", "stopped"));
            instrumentation.runOnMainSync(() -> assertEquals("尚未确认", ((TextView) activity.findViewById(R.id.test_result)).getText().toString()));
        } finally {
            bridge.stop();
            SharedPreferences.Editor restore = settings.edit();
            for (String key : keys) { if (before.containsKey(key)) restore.putBoolean(key, (Boolean) before.get(key)); else restore.remove(key); }
            restore.commit();
        }
    }

    private JSONObject observe() throws Exception {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).waitForIdle(150, 4000);
        return executor.execute("observe", new JSONObject(), false, active);
    }

    private JSONObject node(JSONObject observation, String id) throws Exception {
        JSONArray nodes = observation.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) if (nodes.getJSONObject(i).optString("resourceId").endsWith("/" + id)) return nodes.getJSONObject(i);
        throw new AssertionError("Missing practice node " + id);
    }

}
