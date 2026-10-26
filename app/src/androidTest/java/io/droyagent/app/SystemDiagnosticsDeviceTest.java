package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.SystemDiagnosticsTools;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Calls use the real App UID and only query this test host's package. No model calls. */
@RunWith(AndroidJUnit4.class)
public final class SystemDiagnosticsDeviceTest {
    private static final Cancellation ACTIVE = () -> {};
    private Context context;
    private SystemDiagnosticsTools tools;
    private UiAutomation automation;

    @Before public void setUp() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        tools = new SystemDiagnosticsTools(context);
    }

    @Test public void syntheticCameraAppOpIsReadAsARealSystemRecord() throws Exception {
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        assertTrue("Synthetic AppOps fixtures run only on an emulator", hardware.contains("ranchu") || hardware.contains("goldfish"));
        assertEquals("io.droyagent.app", context.getPackageName());
        assertTrue("Run with DUMP and usage access granted",
                tools.capabilities().getJSONObject("privacy_usage").getBoolean("available"));
        // This records a synthetic AppOp; it neither opens a camera nor changes the op mode.
        long startedAt = System.currentTimeMillis();
        try {
            String output = shell("cmd appops start io.droyagent.app CAMERA");
            assertFalse(output, output.contains("Error:"));
            JSONObject privacy = tools.execute("privacy_usage", new JSONObject()
                    .put("packageName", context.getPackageName()).put("resource", "camera"), ACTIVE);
            boolean recorded = false;
            JSONArray items = privacy.getJSONArray("items");
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.getJSONObject(index);
                if ((item.getString("kind").equals("latest_access") || item.getString("kind").equals("latest_rejection"))
                        && item.getLong("timestampMs") >= startedAt) {
                    assertEquals(context.getPackageName(), item.getString("packageName"));
                    assertEquals("camera", item.getString("resource"));
                    recorded = true;
                }
            }
            assertTrue("A synthetic start/rejection must be visible through the normal App-UID parser", recorded);
            assertFalse(privacy.getBoolean("historyComplete"));
        } finally { shell("cmd appops stop io.droyagent.app CAMERA"); }
    }

    @Test public void grantedQueriesReturnOnlyOwnPackageAndExplicitStatistics() throws Exception {
        JSONObject status = tools.capabilities();
        assertTrue("Run with DUMP and usage access granted", status.getJSONObject("privacy_usage").getBoolean("available"));
        JSONObject privacy = tools.execute("privacy_usage", new JSONObject().put("packageName", context.getPackageName()), ACTIVE);
        assertEquals("android_appops_dumpsys", privacy.getString("source"));
        assertFalse(privacy.getBoolean("historyComplete"));
        assertTrue(privacy.getBoolean("readOnly"));
        assertTrue(privacy.isNull("retentionStartMs"));
        JSONArray items = privacy.getJSONArray("items");
        for (int index = 0; index < items.length(); index++)
            assertEquals(context.getPackageName(), items.getJSONObject(index).getString("packageName"));
        JSONObject battery = tools.execute("battery_usage", new JSONObject().put("packageName", context.getPackageName()), ACTIVE);
        assertEquals(context.getApplicationInfo().uid, battery.getInt("uid"));
        assertEquals("uid", battery.getString("attribution"));
        assertTrue(battery.getBoolean("readOnly"));
        JSONObject statistics = battery.getJSONObject("statistics");
        assertTrue(statistics.has("estimatedPowerMah"));
        assertTrue(statistics.has("userCpuMs"));
        assertTrue(statistics.getLong("statisticsStartMs") > 0);
        assertTrue(statistics.getLong("totalRealtimeMs") >= 0);
        if (!statistics.isNull("estimatedPowerMah")) assertTrue(statistics.getDouble("estimatedPowerMah") >= 0);
    }

    @Test public void deniedQueriesReturnPermissionReasonInsteadOfEmptyData() throws Exception {
        JSONObject status = tools.capabilities().getJSONObject("privacy_usage");
        assertFalse("Run separately without DUMP or usage access", status.getBoolean("available"));
        assertFalse(status.getString("reason").equals("ready"));
        try {
            tools.execute("privacy_usage", new JSONObject().put("packageName", context.getPackageName()), ACTIVE);
            fail("Missing diagnostic grant must not produce an empty success");
        } catch (ActionException denied) {
            assertTrue(denied.getCode(), denied.getCode().equals("dump_permission_required")
                    || denied.getCode().equals("usage_permission_required") || denied.getCode().equals("usage_stats_permission_required"));
        }
    }

    @Test public void cancellationStopsBeforeSystemCollection() throws Exception {
        try {
            tools.execute("privacy_usage", new JSONObject().put("packageName", context.getPackageName()),
                    () -> { throw new InterruptedException("fictional diagnostic cancellation"); });
            fail("Expected cancellation");
        } catch (InterruptedException expected) { assertEquals("fictional diagnostic cancellation", expected.getMessage()); }
    }

    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = automation.executeShellCommand(command);
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
