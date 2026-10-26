package io.droyagent.app;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.ActionException;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises isolated blank browser tabs without external URLs, accounts or model calls. */
@RunWith(AndroidJUnit4.class)
public class BrowserProcessDeviceTest {
    @Test public void isolatedServiceKeepsTabsAndElementReferencesIndependent() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ComponentName component = new ComponentName(context, BrowserService.class);
        ServiceInfo declaration = context.getPackageManager().getServiceInfo(component, 0);
        assertEquals(context.getPackageName() + ":browser", declaration.processName);
        assertFalse(declaration.exported);
        BrowserSession session = new BrowserSession(context);
        String prefix = "test-browser-" + UUID.randomUUID();
        String first = prefix + "-a", second = prefix + "-b", third = prefix + "-c";
        try {
            JSONObject absent = ui(session, first, "state");
            assertFalse(absent.getBoolean("sessionOpen"));
            assertFalse(hasTab(session.execute("browser_status", new JSONObject(), () -> {}).getJSONArray("tabs"), first));
            assertEquals(first, ui(session, first, "attach").getString("sessionId"));
            assertEquals(second, ui(session, second, "attach").getString("sessionId"));
            assertEquals(third, ui(session, third, "attach").getString("sessionId"));
            assertServiceRunsInAnotherProcess(context, component);
            JSONObject tabs = session.execute("browser_status", new JSONObject(), () -> {});
            for (String id : new String[]{first, second, third}) assertTrue(hasTab(tabs.getJSONArray("tabs"), id));
            assertEquals(third, tabs.getString("activeSessionId"));
            observeReady(session, third);
            assertEquals("An omitted sessionId must follow the selected DSH tab", third,
                session.execute("browser_observe", new JSONObject(), () -> {}).getString("sessionId"));

            JSONObject firstSnapshot = observeReady(session, first);
            assertEquals(second, observeReady(session, second).getString("sessionId"));
            try {
                session.execute("browser_click", new JSONObject().put("sessionId", second)
                    .put("snapshotId", firstSnapshot.getString("snapshotId")).put("ref", "b1"), () -> {});
                fail("A snapshot from another tab was accepted");
            } catch (ActionException expected) { assertEquals("STALE_BROWSER_SNAPSHOT", expected.getCode()); }

            assertFalse(ui(session, third, "close").getBoolean("sessionOpen"));
            try { session.execute("browser_observe", new JSONObject(), () -> {}); fail("Ambiguous browser selection was accepted"); }
            catch (ActionException expected) { assertEquals("BROWSER_SESSION_REQUIRED", expected.getCode()); }
            assertFalse(ui(session, first, "close").getBoolean("sessionOpen"));
            assertFalse(ui(session, first, "state").getBoolean("sessionOpen"));
            assertEquals(second, observeReady(session, second).getString("sessionId"));
            assertTrue(hasTab(session.execute("browser_status", new JSONObject(), () -> {}).getJSONArray("tabs"), second));
        } finally {
            for (String id : new String[]{first, second, third}) ui(session, id, "close");
            BrowserSession.release();
        }
    }

    private static JSONObject ui(BrowserSession session, String id, String operation) throws Exception {
        return session.execute("browser_ui", new JSONObject().put("tabId", id).put("operation", operation), () -> {});
    }
    private static JSONObject observeReady(BrowserSession session, String id) throws Exception {
        long until = SystemClock.elapsedRealtime() + 10000;
        while (true) {
            try { return session.execute("browser_observe", new JSONObject().put("sessionId", id), () -> {}); }
            catch (ActionException notReady) {
                if (!notReady.getCode().equals("BROWSER_NOT_READY") || SystemClock.elapsedRealtime() >= until) throw notReady;
                SystemClock.sleep(50);
            }
        }
    }
    private static boolean hasTab(JSONArray tabs, String id) throws Exception {
        for (int index = 0; index < tabs.length(); index++) if (id.equals(tabs.getJSONObject(index).getString("sessionId"))) return true;
        return false;
    }
    @SuppressWarnings("deprecation")
    private static void assertServiceRunsInAnotherProcess(Context context, ComponentName component) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(Integer.MAX_VALUE);
        for (ActivityManager.RunningServiceInfo service : services) {
            if (component.equals(service.service)) {
                assertEquals(context.getPackageName() + ":browser", service.process);
                assertTrue("Browser service must have a live process", service.pid > 0);
                assertNotEquals("Browser contents share the product process", android.os.Process.myPid(), service.pid);
                return;
            }
        }
        fail("Bound browser service was not visible to its own application");
    }
}
