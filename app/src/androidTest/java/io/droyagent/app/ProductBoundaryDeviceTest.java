package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ProductBoundaryDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();

    @Test public void openingAppCannotAttachScreenTextWithoutSeparateReadPermission() throws Exception {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        SharedPreferences settings = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        String[] keys = {"allowApps", "allowControl", "allowObserve"};
        Map<String,Boolean> previous = new HashMap<>();
        for (String key : keys) if (settings.contains(key)) previous.put(key, settings.getBoolean(key,false));
        TestPageActivity fixture = (TestPageActivity) instrumentation.startActivitySync(new Intent(context,TestPageActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ProductBridge bridge = new ProductBridge(context,"fictional-boundary-test-not-listening");
        try {
            assertTrue(settings.edit().putBoolean("allowApps",true).putBoolean("allowControl",true).putBoolean("allowObserve",false).commit());
            bridge.policy.enable();
            JSONObject result = bridge.executeTool("open_app",new JSONObject().put("packageName","com.android.settings")
                .put("verifyTimeoutMs",1000),"boundary-test","launch-without-screen");
            assertTrue(result.getBoolean("dispatched"));
            assertEquals("unavailable",result.getJSONObject("afterObservation").getString("status"));
            assertFalse(result.getJSONObject("afterObservation").has("text"));
            assertFalse(result.getJSONObject("afterObservation").has("nodes"));
        } finally {
            bridge.policy.stop();
            SharedPreferences.Editor restore=settings.edit();
            for(String key:keys) {if(previous.containsKey(key))restore.putBoolean(key,previous.get(key));else restore.remove(key);}
            assertTrue(restore.commit());
            instrumentation.runOnMainSync(fixture::finish);
        }
    }

    @Test public void oneProductBudgetBoundsAnUnmatchedWaitWithoutDispatchingAnAction() throws Exception {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        DeviceUi.await("Accessibility did not connect", 15000,
            () -> io.droyagent.androiduse.DroyAccessibilityService.isConnected(context));
        SharedPreferences settings = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        boolean present = settings.contains("allowObserve");
        boolean previous = settings.getBoolean("allowObserve", false);
        TestPageActivity fixture = (TestPageActivity) instrumentation.startActivitySync(new Intent(context, TestPageActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ProductBridge bridge = new ProductBridge(context, "fictional-deadline-test-token");
        try {
            assertTrue(settings.edit().putBoolean("allowObserve", true).commit());
            bridge.policy.enable();
            long start = android.os.SystemClock.elapsedRealtime();
            try {
                bridge.executeTool("wait_for", new JSONObject().put("timeoutMs", 60000)
                    .put("condition", new JSONObject().put("text", "fictional-never-rendered-deadline-marker")),
                    "deadline-test", "bounded-wait", 200);
                fail("The shared product budget must end the wait");
            } catch (ControlPolicy.DeadlineExceeded expected) {
                assertFalse("A read-only wait must never dispatch a phone action", expected.dispatched);
                assertTrue("The tool used its 60-second local timeout instead of the product budget",
                    android.os.SystemClock.elapsedRealtime() - start < 2000);
            }
        } finally {
            bridge.policy.stop();
            SharedPreferences.Editor restore = settings.edit();
            if (present) restore.putBoolean("allowObserve", previous); else restore.remove("allowObserve");
            assertTrue(restore.commit());
            instrumentation.runOnMainSync(fixture::finish);
        }
    }

    @Test public void unresolvedAppNameReturnsCandidatesWithoutWaitingForScreenDeparture() throws Exception {
        SharedPreferences settings = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        String[] keys = {"allowApps", "allowControl"};
        Map<String, Boolean> previous = new HashMap<>();
        for (String key : keys) if (settings.contains(key)) previous.put(key, settings.getBoolean(key, false));
        ProductBridge bridge = new ProductBridge(context, "fictional-unresolved-app-token");
        try {
            assertTrue(settings.edit().putBoolean("allowApps", true).putBoolean("allowControl", true).commit());
            bridge.policy.enable();
            long start = android.os.SystemClock.elapsedRealtime();
            JSONObject result = bridge.executeTool("open_app", new JSONObject()
                .put("appName", "fictional-app-name-5ac81555-uninstalled"), "unresolved-app-test", "lookup", 10000);
            assertEquals("not_found", result.getString("status"));
            assertFalse(result.getBoolean("dispatched"));
            assertFalse("No launch means no screen observation", result.has("afterObservation"));
            assertTrue("Candidate selection waited for Droy to leave the screen",
                android.os.SystemClock.elapsedRealtime() - start < 2000);
        } finally {
            bridge.policy.stop();
            SharedPreferences.Editor restore = settings.edit();
            for (String key : keys) {
                if (previous.containsKey(key)) restore.putBoolean(key, previous.get(key)); else restore.remove(key);
            }
            assertTrue(restore.commit());
        }
    }

    @Test public void mainBrowserChromeAllowsAuthorizedPhoneScreenToolsWithoutLegacyWindowSignals() throws Exception {
        SharedPreferences settings = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        String[] keys = {"allowObserve", "allowControl", "allowScreenshot", "allowRoot"};
        Map<String, Boolean> previous = new HashMap<>();
        for (String key : keys) if (settings.contains(key)) previous.put(key, settings.getBoolean(key, false));
        FullDshUiDeviceTest fixture = new FullDshUiDeviceTest();
        ProductBridge bridge = new ProductBridge(context, "fictional-main-browser-boundary-token");
        try {
            fixture.setUp();
            fixture.selectBrowserFixture();
            DeviceUi ui = fixture.browserFixtureUi();
            ui.evaluate("window.DroyMobile.openBrowser()");
            DeviceUi.await("The DSH browser did not become visible", 10000,
                () -> ui.truth("(()=>{const e=document.querySelector('.SB_kFW_root');return !!e&&e.getClientRects().length>0})()"));
            assertTrue("The real MainActivity is not visible", MainActivity.isSensitiveUiVisible());
            assertFalse("Unified browser chrome must not require the retired separate-window signal", BrowserUiProtection.isVisible());
            SharedPreferences.Editor permit = settings.edit();
            for (String key : keys) permit.putBoolean(key, true);
            assertTrue(permit.commit());
            bridge.policy.enable();
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            DeviceUi.await("Accessibility did not connect", 15000,
                () -> io.droyagent.androiduse.DroyAccessibilityService.isConnected(context));
            assertFalse(bridge.capabilityStatus().getJSONObject("product").getBoolean("productUiProtected"));
            JSONObject observed = bridge.executeTool("observe", new JSONObject(), "main-browser-boundary", "observe-browser");
            assertEquals("observed", observed.getString("status"));
            assertEquals(context.getPackageName(), observed.getString("packageName"));
            JSONObject captured = bridge.executeTool("screenshot", new JSONObject(), "main-browser-boundary", "capture-browser");
            assertEquals("captured", captured.getString("status"));
            JSONObject back = bridge.executeTool("back", new JSONObject(), "main-browser-boundary", "back-browser");
            assertEquals("action_completed", back.getString("status"));
            assertTrue(settings.edit().putBoolean("allowObserve", false).commit());
            try {
                bridge.executeTool("observe", new JSONObject(), "main-browser-boundary", "read-revoked");
                fail("Opening self UI must not ignore a revoked read grant");
            } catch (SecurityException expected) { assertTrue(expected.getMessage().contains("允许")); }
        } finally {
            bridge.policy.stop();
            SharedPreferences.Editor restore = settings.edit();
            for (String key : keys) {
                if (previous.containsKey(key)) restore.putBoolean(key, previous.get(key)); else restore.remove(key);
            }
            assertTrue(restore.commit());
            fixture.tearDown();
        }
    }
}
