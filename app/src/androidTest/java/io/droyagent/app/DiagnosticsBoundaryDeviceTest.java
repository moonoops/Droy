package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises real product authorization without reading another application's records. */
@RunWith(AndroidJUnit4.class)
public final class DiagnosticsBoundaryDeviceTest {
    private static final String[] KEYS = {"allowApps", "allowDiagnostics", "allowPrivacyUsage", "allowUsage", "allowObserve", "allowControl"};
    private static final String[] ACTIONS = {"app_info", "app_exit_info", "network_usage", "privacy_usage", "battery_usage"};
    private static final String[] ACTION_KEYS = {"allowApps", "allowDiagnostics", "allowUsage", "allowPrivacyUsage", "allowDiagnostics"};
    private final Map<String, Boolean> previous = new HashMap<>();
    private Context context;
    private SharedPreferences preferences;
    private ProductBridge bridge;

    @Before public void setUp() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        for (String key : KEYS) if (preferences.contains(key)) previous.put(key, preferences.getBoolean(key, false));
        permitQueries();
        bridge = new ProductBridge(context, "fictional-diagnostics-boundary-not-listening");
        bridge.policy.enable();
    }

    @After public void tearDown() {
        if (bridge != null) bridge.policy.stop();
        if (preferences == null) return;
        SharedPreferences.Editor restore = preferences.edit();
        for (String key : KEYS) {
            if (previous.containsKey(key)) restore.putBoolean(key, previous.get(key)); else restore.remove(key);
        }
        assertTrue("Could not restore diagnostic product choices", restore.commit());
    }

    @Test public void ownPackageQueryDoesNotRequireScreenReadOrControl() throws Exception {
        assertFalse(preferences.getBoolean("allowObserve", true));
        assertFalse(preferences.getBoolean("allowControl", true));
        JSONObject result = bridge.executeTool("app_info", arguments(), "diagnostic-boundary", "own-package");
        JSONArray items = result.getJSONArray("items");
        assertEquals(1, items.length());
        assertEquals(context.getPackageName(), items.getJSONObject(0).getString("packageName"));
        assertFalse("Direct query must not attach page text", result.has("afterObservation"));
    }

    @Test public void eachDiagnosticGroupRejectsBeforeAnySystemCollection() throws Exception {
        for (int index = 0; index < ACTIONS.length; index++) {
            permitQueries();
            assertTrue(preferences.edit().putBoolean(ACTION_KEYS[index], false).commit());
            try {
                bridge.executeTool(ACTIONS[index], arguments(), "diagnostic-boundary", "revoked-" + index);
                fail("Product revocation did not stop " + ACTIONS[index]);
            } catch (SecurityException expected) {
                assertTrue("The rejection did not identify product authorization", expected.getMessage().contains("允许"));
            }
        }
    }

    @Test public void stoppedQueriesDoNotReachSystemCollectors() throws Exception {
        bridge.policy.stop();
        for (String action : ACTIONS) {
            try {
                bridge.executeTool(action, arguments(), "diagnostic-boundary", "stopped-" + action);
                fail("Stopped execution accepted " + action);
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("暂停"));
            }
        }
    }

    @Test public void productAndSystemCapabilityReasonsRemainIndependent() throws Exception {
        JSONObject allowed = bridge.capabilityStatus();
        JSONObject system = allowed.getJSONObject("diagnostics");
        for (String action : ACTIONS) {
            JSONObject tool = tool(allowed, action);
            assertTrue("Screen permissions must not disable diagnostic products: " + tool, tool.getBoolean("productAllowed"));
            JSONObject nativeState = system.getJSONObject(action);
            if (!nativeState.getBoolean("available")) {
                assertFalse("An unavailable collector needs an actionable reason", nativeState.optString("reason").isBlank());
                assertFalse(tool.getBoolean("available"));
                assertTrue(tool.getJSONArray("reasons").length() > 0);
            }
        }
        assertTrue(preferences.edit().putBoolean("allowPrivacyUsage", false).commit());
        JSONObject denied = bridge.capabilityStatus();
        assertFalse(tool(denied, "privacy_usage").getBoolean("productAllowed"));
        assertEquals("Product revocation must not masquerade as a changed Android grant",
            system.getJSONObject("privacy_usage").getBoolean("available"),
            denied.getJSONObject("diagnostics").getJSONObject("privacy_usage").getBoolean("available"));
        assertFalse(preferences.getBoolean("allowPrivacyUsage", true));
    }

    private void permitQueries() {
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : KEYS) editor.putBoolean(key, !key.equals("allowObserve") && !key.equals("allowControl"));
        assertTrue(editor.commit());
    }

    private JSONObject arguments() throws Exception {
        long end = System.currentTimeMillis();
        return new JSONObject().put("packageName", context.getPackageName()).put("startMs", end - 3600000).put("endMs", end);
    }

    private static JSONObject tool(JSONObject state, String action) throws Exception {
        JSONArray tools = state.getJSONArray("tools");
        for (int index = 0; index < tools.length(); index++) {
            JSONObject tool = tools.getJSONObject(index);
            if (action.equals(tool.getString("action"))) return tool;
        }
        throw new AssertionError("Missing tool capability: " + action);
    }
}
