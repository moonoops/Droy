package io.droyagent.androiduse;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class DiagnosticParsingTest {
    private static final Cancellation ACTIVE = () -> {};
    private static final String HEADER = "Current AppOps Service state:\n";
    private static final String APP = "  Uid u0a123:\n    Package com.example.camera:\n      CAMERA (allow): \n        null=[\n";

    @Test public void privacyDistinguishesAccessRejectionAndRunningWithoutClaimingHistory() throws Exception {
        String fixture = HEADER + APP
                + "          Access: [top-s] 2026-10-25 01:02:03.004 (-1h0m0s0ms) duration=+2s5ms proxy[uid=1000, pkg=android, attributionTag=null]\n"
                + "          Reject: [bg-s]2026-10-25 02:02:03.004 (-1m0s0ms)\n"
                + "          Running start at: +3s2ms\n        ]\n";
        DiagnosticParsing.Privacy parsed = privacy(fixture, 100);
        assertEquals(3, parsed.items.size());
        assertEquals("running_at_capture", parsed.items.get(0).get("kind"));
        assertEquals(3002L, parsed.items.get(0).get("runningForMs"));
        assertEquals("latest_rejection", parsed.items.get(1).get("kind"));
        Map<String, Object> access = parsed.items.get(2);
        assertEquals("latest_access", access.get("kind"));
        assertEquals(10123, access.get("uid"));
        assertEquals(2005L, access.get("durationMs"));
        assertEquals("android", access.get("proxyPackageName"));
        assertNull(access.get("attributionTag"));
        assertFalse(parsed.truncated);
    }

    @Test public void privacyRetainsAttributionAndExcludesOtherUsersAndPackages() throws Exception {
        String fixture = HEADER + APP.replace("null=[", "speech=[")
                + "          Access: [fg-s] 2026-10-25 01:00:00.000 (-1s0ms)\n"
                + "  Uid u1a123:\n    Package com.example.camera:\n      CAMERA (allow): \n"
                + "          Access: [top-s] 2026-10-25 02:00:00.000 (-1s0ms)\n"
                + "  Uid u0a124:\n    Package com.example.unrelated:\n      CAMERA (allow): \n"
                + "          Access: [top-s] 2026-10-25 03:00:00.000 (-1s0ms)\n";
        DiagnosticParsing.Privacy parsed = privacy(fixture, 100);
        assertEquals(1, parsed.items.size());
        assertEquals("speech", parsed.items.get(0).get("attributionTag"));
    }

    @Test public void privacyLimitKeepsNewestInsteadOfFirstUidInDumpOrder() throws Exception {
        DiagnosticParsing.Privacy parsed = privacy(HEADER + APP
                + "          Access: [bg-s] 2026-10-25 01:00:00.000 (-1s0ms)\n"
                + "          Access: [top-s] 2026-10-25 03:00:00.000 (-1s0ms)\n", 1);
        assertEquals(1, parsed.items.size());
        assertEquals("[top-s]", parsed.items.get(0).get("uidStateAndFlags"));
        assertTrue(parsed.truncated);
    }

    @Test public void knownEmptyAppOpsOutputIsEmptyButUnrecognizedOutputIsNot() throws Exception {
        assertTrue(privacy(HEADER + "  AppOps policy not set.\n", 100).items.isEmpty());
        expect("diagnostic_format_unsupported", () -> privacy("Vendor diagnostic output changed", 100));
        expect("diagnostic_format_unsupported", () -> privacy(HEADER + " VendorContext:\n Access: [top-s] changed\n", 100));
        expect("diagnostic_format_unsupported", () -> privacy(HEADER + APP + " Access: something changed\n", 100));
        expect("diagnostic_format_unsupported", () -> privacy(HEADER + APP
                + " Access: [top-s] 2026-99-99 01:00:00.000 (-1s0ms)\n", 100));
    }

    @Test public void systemRefusalAndTimeoutRemainExplicit() throws Exception {
        expect("diagnostics_permission_required", () -> DiagnosticParsing.serviceOutput("Permission Denial: can't dump due to missing permission"));
        expect("diagnostic_timeout", () -> DiagnosticParsing.serviceOutput("*** SERVICE 'appops' DUMP TIMEOUT (4000ms) EXPIRED ***"));
        expect("diagnostic_format_unsupported", () -> DiagnosticParsing.serviceOutput("Unknown option --op"));
    }

    @Test public void batteryApi30FieldsHaveTheirSourceUnitsAndPeriod() throws Exception {
        Map<String, Object> result = DiagnosticParsing.battery(batteryFixture(35), 10123, ACTIVE);
        
        assertEquals(17.25, (Double) result.get("estimatedPowerMah"), 0.0001);
        assertEquals(300L, result.get("userCpuMs"));
        assertEquals(90L, result.get("systemCpuMs"));
        assertEquals(750L, result.get("partialWakeLockMs"));
        assertEquals(250L, result.get("backgroundPartialWakeLockMs"));
        assertEquals(new java.text.SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").parse("2026-10-25-00-00-00").getTime(), result.get("statisticsStartMs"));
        assertEquals(200000L, result.get("totalRealtimeMs"));
        assertEquals(100000L, result.get("onBatteryRealtimeMs"));
    }

    @Test public void batteryApi36FieldsIgnoreOtherUidAndComponentDetails() throws Exception {
        Map<String, Object> result = DiagnosticParsing.battery(batteryFixture(36)
                + "  u0a124:\n    Total cpu time: u=999ms s=999ms \n", 10123, ACTIVE);
        assertEquals(17.25, (Double) result.get("estimatedPowerMah"), 0.0001);
        assertEquals(300L, result.get("userCpuMs"));
        assertTrue((Boolean) result.get("uidRecordsPresent"));
    }

    @Test public void absentUidCountersRemainMissingRatherThanZero() throws Exception {
        Map<String, Object> result = DiagnosticParsing.battery(batteryFixture(36), 10125, ACTIVE);
        assertNull(result.get("estimatedPowerMah"));
        assertNull(result.get("userCpuMs"));
        assertFalse((Boolean) result.get("uidRecordsPresent"));
    }

    @Test public void malformedBatteryRecordsDoNotBecomeSuccessfulZeroes() throws Exception {
        expect("diagnostic_format_unsupported", () -> DiagnosticParsing.battery("changed output", 10123, ACTIVE));
        expect("diagnostic_format_unsupported", () -> DiagnosticParsing.battery(batteryFixture(36).replace(": 17.25", ": NaN"), 10123, ACTIVE));
        expect("diagnostic_format_unsupported", () -> DiagnosticParsing.battery(batteryFixture(36).replace("Total cpu time: u=300ms s=90ms", "Total cpu time: invalid"), 10123, ACTIVE));
        expect("diagnostic_format_unsupported", () -> DiagnosticParsing.battery(batteryFixture(36).replace("Total cpu time: u=300ms s=90ms", "Total cpu time: u=-1ms s=90ms"), 10123, ACTIVE));
    }

    @Test public void cancellationIsNotHiddenByParsing() throws Exception {
        try {
            DiagnosticParsing.battery(batteryFixture(36), 10123, () -> { throw new InterruptedException("fixture stop"); });
            fail("Expected cancellation");
        } catch (InterruptedException expected) { assertEquals("fixture stop", expected.getMessage()); }
    }

    @Test public void fixedCommandsCannotResetOrConsumeBatteryHistory() throws Exception {
        List<String> command = SystemDiagnosticsTools.batteryCommand("com.example.camera", 10123);
        assertEquals(List.of("/system/bin/sh", "-c"), command.subList(0, 2));
        assertTrue(command.get(2).contains("batterystats --charged com.example.camera"));
        assertFalse(command.get(2).contains("--checkin"));
        assertFalse(command.get(2).contains("--reset"));
        assertFalse(command.get(2).contains("--history-start"));
        assertFalse(SystemDiagnosticsTools.privacyCommand("com.example.camera", "CAMERA", 0).get(2).contains("--package"));
        expect("invalid_argument", () -> SystemDiagnosticsTools.privacyCommand("com.example.a;reboot", "CAMERA", 0));
        expect("invalid_argument", () -> SystemDiagnosticsTools.privacyCommand(null, "RESET", 0));
        expect("invalid_argument", () -> SystemDiagnosticsTools.privacyCommand(null, "CAMERA", -1));
        expect("invalid_argument", () -> SystemDiagnosticsTools.batteryCommand("com.example.camera", -1));
    }

    @Test public void durationAndUidFormatsAreParsedWithoutGuessing() throws Exception {
        assertEquals(93784005L, DiagnosticParsing.duration("+1d2h3m4s5ms"));
        assertEquals(0L, DiagnosticParsing.duration("0"));
        assertEquals(1010123, DiagnosticParsing.uid("u10a123"));
        assertEquals(1000, DiagnosticParsing.uid("1000"));
        assertEquals(90000, DiagnosticParsing.uid("u0ai0"));
        assertEquals(99000, DiagnosticParsing.uid("u0ai9000"));
        expect("diagnostic_format_unsupported", () -> DiagnosticParsing.duration("yesterday"));
        expect("diagnostic_format_unsupported", () -> DiagnosticParsing.uid("vendor-uid"));
    }

    @Test public void batteryPipelineHasValidShellSyntaxAndFiltersActualStream() throws Exception {
        String command = SystemDiagnosticsTools.batteryCommand("com.example.camera", 10123).get(2);
        Process syntax = new ProcessBuilder("/bin/sh", "-n", "-c", command).start();
        assertTrue(syntax.waitFor(5, TimeUnit.SECONDS));
        assertEquals(new String(syntax.getErrorStream().readAllBytes(), StandardCharsets.UTF_8), 0, syntax.exitValue());
        String fixtureCommand = command.replace("/system/bin/dumpsys -t 10 batterystats --charged com.example.camera", "/bin/cat")
                .replace("/system/bin/sed", "/usr/bin/sed");
        Process query = new ProcessBuilder("/bin/bash", "-c", fixtureCommand).start();
        try {
            query.getOutputStream().write((batteryFixture(36)
                    + "  u0a124:\n    Total cpu time: u=999ms s=999ms \n    Apk unrelated-history:\n"
                    + "Permission Denial: fictional missing grant\n").getBytes(StandardCharsets.UTF_8));
            query.getOutputStream().close();
            String output = new String(query.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(query.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, query.exitValue());
            assertTrue(output.contains("Total cpu time: u=300ms s=90ms"));
            assertTrue(output.contains("Permission Denial:"));
            assertFalse(output.contains("u0a124"));
            assertFalse(output.contains("unrelated-history"));
        } finally { query.destroyForcibly(); }
    }

    @Test public void changedContextAfterValidRecordsCannotReusePreviousIdentity() throws Exception {
        String valid = HEADER + APP + "          Access: [top-s] 2026-10-25 01:00:00.000 (-1s0ms)\n";
        for (String changed : List.of("  Uid u0a999: future-format\n", "    Package com.example.other: future-format\n",
                "      RECORD_AUDIO: future-format\n", "      OTHER_OP (allow):\n")) {
            expect("diagnostic_format_unsupported", () -> privacy(valid + changed
                    + "          Access: [top-s] 2026-10-25 02:00:00.000 (-1s0ms)\n", 100));
        }
    }

    @Test public void appOpsPipelineHasValidSyntaxAndFiltersPackageAndAndroidUser() throws Exception {
        String fixture = HEADER + APP
                + "          Access: [top-s] 2026-10-25 01:00:00.000 (-1s0ms)\n        ]\n"
                + "    Package com.example.unrelated:\n      CAMERA (allow):\n        null=[\n"
                + "          Access: [top-s] 2026-10-25 02:00:00.000 (-1s0ms)\n        ]\n"
                + "  Uid u1a123:\n    Package com.example.camera:\n      CAMERA (allow):\n        null=[\n"
                + "          Access: [top-s] 2026-10-25 03:00:00.000 (-1s0ms)\n        ]\n"
                + "  Uid u0a999:\n    Package comXexampleXcamera:\n      CAMERA (allow):\n        null=[\n"
                + "          Access: [top-s] 2026-10-25 04:00:00.000 (-1s0ms)\n        ]\n";
        String output = appOpsFixture(fixture, "com.example.camera", 0);
        assertFalse(output.contains("unrelated"));
        assertFalse(output.contains("u1a123"));
        assertFalse(output.contains("comXexampleXcamera"));
        DiagnosticParsing.Privacy parsed = privacy(output, 100);
        assertEquals(1, parsed.items.size());
        assertEquals(10123, parsed.items.get(0).get("uid"));
    }

    @Test public void appOpsFilterSupportsAllPackagesForExactlyTheRequestedUser() throws Exception {
        String fixture = HEADER + APP + "          Access: [top-s] 2026-10-25 01:00:00.000 (-1s0ms)\n"
                + "  Uid u10a123:\n    Package com.example.camera:\n      CAMERA (allow):\n"
                + "          Access: [top-s] 2026-10-25 02:00:00.000 (-1s0ms)\n"
                + "  Uid 1010124:\n    Package com.example.second:\n      CAMERA (allow):\n"
                + "          Access: [top-s] 2026-10-25 03:00:00.000 (-1s0ms)\n";
        String output = appOpsFixture(fixture, null, 10);
        assertFalse(output.contains("u0a123"));
        DiagnosticParsing.Privacy parsed = DiagnosticParsing.privacy(output, null, 10, 100,
                Locale.US, TimeZone.getTimeZone("UTC"), ACTIVE);
        assertEquals(2, parsed.items.size());
        assertEquals("com.example.second", parsed.items.get(0).get("packageName"));
    }

    @Test public void appOpsFilterReportsMalformedContextInsteadOfReturningEmpty() throws Exception {
        String output = appOpsFixture(HEADER + "  Uid u0a123: future-format\n"
                + "    Package com.example.camera:\n      CAMERA (allow):\n"
                + "          Access: [top-s] 2026-10-25 02:00:00.000 (-1s0ms)\n", "com.example.camera", 0);
        expect("diagnostic_format_unsupported", () -> privacy(output, 100));
    }

    @Test public void changedIndentationCannotDropAContextBoundaryAndKeepItsRecords() throws Exception {
        String valid = HEADER + APP + "          Access: [top-s] 2026-10-25 01:00:00.000 (-1s0ms)\n        ]\n";
        String access = "        null=[\n          Access: [top-s] 2026-10-25 02:00:00.000 (-1s0ms)\n        ]\n";
        for (String changed : List.of(
                "   Uid u1a123:\n    Package com.example.camera:\n      CAMERA (allow):\n",
                "     Package com.example.camera:\n      CAMERA (allow):\n",
                "     RECORD_AUDIO (allow):\n",
                "       CAMERA (allow):\n")) {
            String output = appOpsFixture(valid + changed + access, "com.example.camera", 0);
            assertTrue("The filter must retain a format failure instead of the old identity", output.contains("DROY_DIAGNOSTIC_CONTEXT_UNSUPPORTED"));
            expect("diagnostic_format_unsupported", () -> privacy(output, 100));
        }
    }

    private static String appOpsFixture(String input, String pkg, int user) throws Exception {
        String command = SystemDiagnosticsTools.privacyCommand(pkg, "CAMERA", user).get(2);
        Process syntax = new ProcessBuilder("/bin/sh", "-n", "-c", command).start();
        assertTrue(syntax.waitFor(5, TimeUnit.SECONDS));
        assertEquals(new String(syntax.getErrorStream().readAllBytes(), StandardCharsets.UTF_8), 0, syntax.exitValue());
        String fixtureCommand = command.replace("/system/bin/dumpsys -t 4 appops --op CAMERA", "/bin/cat")
                .replace("/system/bin/sed", "/usr/bin/sed");
        Process query = new ProcessBuilder("/bin/bash", "-c", fixtureCommand).start();
        try {
            query.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
            query.getOutputStream().close();
            String output = new String(query.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(query.waitFor(5, TimeUnit.SECONDS));
            assertEquals(new String(query.getErrorStream().readAllBytes(), StandardCharsets.UTF_8), 0, query.exitValue());
            return output;
        } finally { query.destroyForcibly(); }
    }

    private static DiagnosticParsing.Privacy privacy(String output, int limit) throws Exception {
        return DiagnosticParsing.privacy(output, "com.example.camera", 0, limit, Locale.US, TimeZone.getTimeZone("UTC"), ACTIVE);
    }

    private static String batteryFixture(int version) {
        String energy = version == 35 ? "    Uid u0a123: 17.25 (cpu=10)" : "  UID u0a123: 17.25 fg: 1 bg: 16.25";
        return "Statistics since last charge:\n"
                + "  Time on battery: 1m 40s (50.0%) realtime, 80s (80.0%) uptime\n"
                + "  Total run time: 3m 20s realtime, 2m uptime\n"
                + "  Start clock time: 2026-10-25-00-00-00\n"
                + "  Estimated power use (mAh):\n" + energy + "\n\n"
                + "  u0a123:\n"
                + "    Total cpu time: u=300ms s=90ms \n"
                + "    TOTAL wake: 500ms blamed partial, 750ms actual partial, 250ms actual background partial\n";
    }

    private static void expect(String code, Throwing action) throws Exception {
        try { action.run(); fail("Expected " + code); }
        catch (ActionException expected) { assertEquals(code, expected.getCode()); }
    }
    private interface Throwing { void run() throws Exception; }
}
