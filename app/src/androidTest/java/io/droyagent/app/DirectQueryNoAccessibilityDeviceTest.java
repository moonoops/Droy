package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Run separately with the system accessibility service disabled. */
@RunWith(AndroidJUnit4.class)
public final class DirectQueryNoAccessibilityDeviceTest {
    @Test public void deviceAndAppToolsWorkWithoutScreenAccess() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        Context context = instrumentation.getTargetContext();
        boolean ownsRuntime = RuntimeService.canManageHistory();
        MainActivity main = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        SharedPreferences preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        String[] keys = {"allowDeviceInfo", "allowApps", "allowControl", "allowObserve", "allowRoot"};
        Map<String, Boolean> previous = new HashMap<>();
        SharedPreferences.Editor grants = preferences.edit();
        for (String key : keys) {
            if (preferences.contains(key)) previous.put(key, preferences.getBoolean(key, false));
            grants.putBoolean(key, !key.equals("allowObserve") && !key.equals("allowRoot"));
        }
        assertTrue(grants.commit());
        ProductBridge bridge = new ProductBridge(context, "fictional-direct-query-without-accessibility");
        bridge.policy.enable();
        boolean opened = false;
        try {
            assertFalse("Disable the system accessibility service before this test", bridge.capabilityStatus().getBoolean("accessibilityConnected"));
            JSONObject info = bridge.executeTool("system_info", new JSONObject(), "no-screen", "info");
            assertEquals("queried", info.getString("status"));
            assertTrue(info.getJSONObject("sections").has("battery"));
            JSONObject apps = bridge.executeTool("find_apps", new JSONObject().put("query", "com.android.settings"), "no-screen", "find");
            assertTrue(apps.getJSONArray("apps").length() > 0);
            long deadline = SystemClock.elapsedRealtime() + 5000;
            boolean[] focused = {false};
            do {
                instrumentation.runOnMainSync(() -> focused[0] = main.hasWindowFocus());
                if (!focused[0]) SystemClock.sleep(50);
            } while (!focused[0] && SystemClock.elapsedRealtime() < deadline);
            assertTrue(focused[0]);
            JSONObject launch = bridge.executeTool("open_app", new JSONObject().put("packageName", "com.android.settings").put("verifyTimeoutMs", 0), "no-screen", "open");
            opened = launch.getBoolean("dispatched");
            assertTrue(launch.toString(), opened);
            assertFalse("Without a screen channel the foreground must remain unverified", launch.getBoolean("goalVerified"));
        } finally {
            bridge.policy.stop();
            if (opened) try (ParcelFileDescriptor ignored = automation.executeShellCommand("input keyevent KEYCODE_BACK")) { SystemClock.sleep(200); }
            instrumentation.runOnMainSync(main::finish);
            if (ownsRuntime) {
                instrumentation.runOnMainSync(RuntimeService::requestStop);
                DeviceUi.await("Direct query test left its automatic engine startup active", 15000, RuntimeService::canManageHistory);
            }
            SharedPreferences.Editor restore = preferences.edit();
            for (String key : keys) { if (previous.containsKey(key)) restore.putBoolean(key, previous.get(key)); else restore.remove(key); }
            assertTrue(restore.commit());
        }
    }
}
