package io.droyagent.androiduse;

import android.app.ApplicationExitInfo;
import android.app.usage.UsageEvents;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticsValidationTest {
    @Test public void packageTargetsIncludePlatformButRejectShellAndForeignUserSyntax() throws Exception {
        assertEquals("android", DiagnosticsValidation.packageName("android"));
        assertEquals("io.droyagent.fixture", DiagnosticsValidation.packageName("io.droyagent.fixture"));
        reject(() -> DiagnosticsValidation.packageName("fixture;id"));
        reject(() -> DiagnosticsValidation.packageName("10:io.droyagent.fixture"));
        reject(() -> DiagnosticsValidation.packageName(123));
    }

    @Test public void permissionFiltersMatchDeclarationsExactly() throws Exception {
        assertEquals("android.permission.CAMERA", DiagnosticsValidation.permission("android.permission.CAMERA"));
        assertTrue(DiagnosticsValidation.requestsPermission(new String[]{"android.permission.CAMERA"}, "android.permission.CAMERA"));
        assertFalse(DiagnosticsValidation.requestsPermission(new String[]{"fixture.permission.CAMERA"}, "android.permission.CAMERA"));
        assertFalse(DiagnosticsValidation.requestsPermission(null, "android.permission.CAMERA"));
        reject(() -> DiagnosticsValidation.permission("CAMERA"));
        reject(() -> DiagnosticsValidation.permission("android.permission.CAMERA\nother"));
    }

    @Test public void newModesKeepOldSummaryDefaultAndRejectUnknownValues() throws Exception {
        assertEquals("summary", DiagnosticsValidation.usageMode(null));
        assertEquals("events", DiagnosticsValidation.usageMode("events"));
        reject(() -> DiagnosticsValidation.usageMode("foreground"));
        reject(() -> DiagnosticsValidation.choice(true, "networkType", "all", Set.of("all", "wifi", "mobile")));
        reject(() -> DiagnosticsValidation.bool("true", "includeTrace", false));
    }

    @Test public void timestampValidationDoesNotRoundFractionsOrInvalidNumbers() throws Exception {
        assertEquals(1234, DiagnosticsValidation.timestamp(1234L, "startMs"));
        reject(() -> DiagnosticsValidation.timestamp(1.1, "startMs"));
        reject(() -> DiagnosticsValidation.timestamp(-1, "startMs"));
        reject(() -> DiagnosticsValidation.timestamp(Double.POSITIVE_INFINITY, "startMs"));
    }

    @Test public void exitReasonsKeepSystemKillSeparateFromJavaCrash() {
        assertEquals("java_crash", AppDiagnosticsTools.reasonName(ApplicationExitInfo.REASON_CRASH));
        assertEquals("low_memory", AppDiagnosticsTools.reasonName(ApplicationExitInfo.REASON_LOW_MEMORY));
        assertEquals("user_requested", AppDiagnosticsTools.reasonName(ApplicationExitInfo.REASON_USER_REQUESTED));
        assertEquals("unknown", AppDiagnosticsTools.reasonName(99999));
        assertEquals("activity_paused", PersonalDataTools.usageEventName(UsageEvents.Event.ACTIVITY_PAUSED));
        assertNull(PersonalDataTools.usageEventName(99999));
    }

    @Test public void traceReadUsesOneExtraByteForTruncationAndHonorsCancellation() throws Exception {
        byte[] large = new byte[100000];
        ByteArrayInputStream input = new ByteArrayInputStream(large);
        assertEquals(8193, AppDiagnosticsTools.readTrace(input, 8192, () -> {}).length);
        assertEquals(large.length - 8193, input.available());
        assertEquals(4, AppDiagnosticsTools.readTrace(new ByteArrayInputStream(new byte[4]), 8192, () -> {}).length);
        int[] checks = {0};
        try {
            AppDiagnosticsTools.readTrace(new ByteArrayInputStream(large), 8192, () -> {
                if (++checks[0] == 2) throw new InterruptedException("fixture cancel");
            });
            fail("Cancellation must stop further reads");
        } catch (InterruptedException expected) { assertEquals(2, checks[0]); }
    }

    @Test public void api30NativeExitMayStillCarryAnrTextInsteadOfTombstone() {
        assertFalse(AppDiagnosticsTools.nativeTraceFormat(30, ApplicationExitInfo.REASON_CRASH_NATIVE));
        assertTrue(AppDiagnosticsTools.nativeTraceFormat(31, ApplicationExitInfo.REASON_CRASH_NATIVE));
        assertTrue(AppDiagnosticsTools.nativeTraceFormat(36, ApplicationExitInfo.REASON_CRASH_NATIVE));
        assertFalse(AppDiagnosticsTools.nativeTraceFormat(36, ApplicationExitInfo.REASON_ANR));
        assertFalse(AppDiagnosticsTools.nativeTraceFormat(36, ApplicationExitInfo.REASON_CRASH));
    }

    private interface Checked { void run() throws Exception; }
    private static void reject(Checked action) throws Exception {
        try { action.run(); fail("Expected invalid_argument"); }
        catch (ActionException expected) { assertEquals("invalid_argument", expected.getCode()); }
    }
}
