package io.droyagent.androiduse;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

public class ProcessMemoryToolsTest {
    private static final String OWNER = "memory-task";
    private static final Cancellation ACTIVE = () -> {};
    private FakeBackend backend;
    private ProcessMemoryTools tools;
    private File ledger;
    private int calls;

    @Before public void setup() throws Exception {
        backend = new FakeBackend();
        ledger = new File(Files.createTempDirectory("droy-memory-test").toFile(), "receipts.json");
        tools = new ProcessMemoryTools(backend, name -> new ProcessMemoryTools.Target(name, 10123, 7, 10), ledger);
    }

    private JSONObject invoke(String action, JSONObject args) throws Exception {
        return tools.execute(action, args, OWNER, "call-" + (++calls), ACTIVE);
    }
    private JSONObject process(String op) throws Exception { return new JSONObject().put("operation", op); }
    private String open(String mode) throws Exception {
        return invoke("process_memory", process("open").put("packageName", "com.example.game").put("mode", mode)).getString("sessionId");
    }
    private String region(String sid) throws Exception {
        return invoke("process_memory", process("regions").put("sessionId", sid)).getJSONArray("items").getJSONObject(0).getString("regionId");
    }
    private JSONObject read(String sid, String region) throws Exception {
        return invoke("memory_read", new JSONObject().put("sessionId", sid).put("regionId", region)
            .put("offset", "0x0").put("type", "int32"));
    }
    private void rejected(String code, Checked action) throws Exception {
        try { action.run(); fail("Expected " + code); }
        catch (ActionException failure) { assertEquals(code, failure.getCode()); }
    }
    interface Checked { void run() throws Exception; }

    @Test public void capabilitiesDoesNotStartRootOrProbeMemory() throws Exception {
        JSONObject status = tools.capabilities();
        assertTrue(status.getBoolean("supported"));
        assertTrue(status.getBoolean("rootRequired"));
        assertEquals("checked_on_open", status.getString("targetAccess"));
        assertEquals(0, backend.requests);
    }

    @Test public void scalarWriteIsPreparedDurablyReadBackAndNotRepeated() throws Exception {
        String sid = open("read_write"), region = region(sid);
        JSONObject before = read(sid, region);
        assertEquals("1200", before.getJSONArray("values").getString(0));
        backend.prepared = () -> {
            assertTrue("Ledger must be durable before the native GO", ledger.isFile());
            assertTrue(new String(Files.readAllBytes(ledger.toPath()), java.nio.charset.StandardCharsets.UTF_8).contains("pending"));
        };
        JSONObject args = new JSONObject().put("sessionId", sid).put("readId", before.getString("readId")).put("value", "99999");
        JSONObject result = invoke("memory_write", args);
        assertEquals("written_verified", result.getString("status"));
        assertEquals(4, result.getInt("bytesWritten"));
        assertFalse(result.getBoolean("goalVerified"));
        assertEquals("99999", result.getJSONObject("readback").getJSONArray("values").getString(0));
        assertEquals(1, backend.writes);
        JSONObject duplicate = invoke("memory_write", args);
        assertTrue(duplicate.getBoolean("duplicate"));
        assertEquals(1, backend.writes);
        assertFalse("Persisted receipts must not store memory bytes", new String(Files.readAllBytes(ledger.toPath()), java.nio.charset.StandardCharsets.UTF_8).contains("b0040000"));
        assertFalse("Persisted receipts must not store readback values", new String(Files.readAllBytes(ledger.toPath()), java.nio.charset.StandardCharsets.UTF_8).contains("99999"));
        rejected("memory_request_conflict", () -> invoke("memory_write", new JSONObject(args.toString()).put("value", "88")));
        assertEquals(1, backend.writes);
    }

    @Test public void normalizedEquivalentValueReturnsSameReceipt() throws Exception {
        String sid = open("read_write");
        String readId = read(sid, region(sid)).getString("readId");
        JSONObject args = new JSONObject().put("sessionId", sid).put("readId", readId).put("value", "0008");
        invoke("memory_write", args);
        assertTrue(invoke("memory_write", new JSONObject(args.toString()).put("value", "8")).getBoolean("duplicate"));
        assertEquals(1, backend.writes);
    }

    @Test public void readOnlyAndCrossTaskReferencesCannotWrite() throws Exception {
        String sid = open("read_only");
        String readId = read(sid, region(sid)).getString("readId");
        JSONObject args = new JSONObject().put("sessionId", sid).put("readId", readId).put("value", "99999");
        rejected("memory_read_only", () -> invoke("memory_write", args));
        rejected("memory_owner_mismatch", () -> tools.execute("memory_write", args, "another-task", "other-call", ACTIVE));
        assertEquals(0, backend.writes);
    }

    @Test public void arraysAndBytesNeverBecomeBatchWriteAuthority() throws Exception {
        String sid = open("read_write"), region = region(sid);
        JSONObject array = invoke("memory_read", new JSONObject().put("sessionId", sid).put("regionId", region)
            .put("offset", "0x0").put("type", "int32").put("count", 2));
        rejected("invalid_argument", () -> invoke("memory_write", new JSONObject().put("sessionId", sid)
            .put("readId", array.getString("readId")).put("value", "99999")));
        assertEquals(0, backend.writes);
    }

    @Test public void partialAndUnknownWritesAreReceiptsNotAutomaticRetries() throws Exception {
        String sid = open("read_write"), region = region(sid);
        JSONObject args = new JSONObject().put("sessionId", sid).put("readId", read(sid, region).getString("readId")).put("value", "99999");
        backend.disconnectAfterPreparation = true;
        JSONObject unknown = invoke("memory_write", args);
        assertEquals("outcome_unknown", unknown.getString("status"));
        assertFalse(unknown.getBoolean("writeResultKnown"));
        assertTrue(unknown.isNull("bytesWritten"));
        assertEquals(0, backend.writes);
        tools.closeAll();
        JSONObject status = invoke("process_memory", process("status").put("writeReceiptId", unknown.getString("writeReceiptId")));
        assertEquals("outcome_unknown", status.getString("status"));
        ProcessMemoryTools recovered = new ProcessMemoryTools(backend,
            name -> new ProcessMemoryTools.Target(name, 10123, 7, 10), ledger);
        JSONObject replay = recovered.execute("memory_write", args, OWNER, "resume-call", ACTIVE);
        assertEquals("outcome_unknown", replay.getString("status"));
        assertEquals(0, backend.writes);
    }

    @Test public void unresolvedPriorExecutorBlocksNewWriteUntilExitConfirmed() throws Exception {
        String sid = open("read_write"), region = region(sid);
        backend.disconnectAfterPreparation = true;
        invoke("memory_write", new JSONObject().put("sessionId", sid).put("readId", read(sid, region).getString("readId")).put("value", "8"));
        backend.disconnectAfterPreparation = false; backend.helperAlive = true;
        JSONObject next = new JSONObject().put("sessionId", sid).put("readId", read(sid, region).getString("readId")).put("value", "9");
        rejected("memory_write_blocked", () -> invoke("memory_write", next));
        assertEquals(0, backend.writes);
        backend.helperAlive = false;
        assertEquals("written_verified", invoke("memory_write", next).getString("status"));
        assertEquals(1, backend.writes);
    }

    @Test public void processRestartInvalidatesSessionRatherThanFollowingPid() throws Exception {
        String sid = open("read_write"), region = region(sid);
        backend.targetChanged = true;
        rejected("memory_target_changed", () -> read(sid, region));
        backend.targetChanged = false;
        rejected("memory_reference_invalid", () -> read(sid, region));
    }

    @Test public void privilegeRevocationDropsIdleStateAndLocalCloseStillWorks() throws Exception {
        String sid = open("read_write");
        tools.onPermissionsChanged(true, true, false, true);
        rejected("memory_reference_invalid", () -> invoke("process_memory", process("regions").put("sessionId", sid)));
        tools.onPermissionsChanged(false, false, false, false);
        assertEquals("closed", invoke("process_memory", process("close").put("sessionId", sid)).getString("status"));
        assertEquals(0, invoke("process_memory", process("status")).getJSONArray("receipts").length());
    }

    @Test public void pausedInitialScanMustContinueBeforeRefining() throws Exception {
        String sid = open("read_only"), region = region(sid);
        backend.scans.add(scanResult(false, "0x1004", candidate("0x1000", "b0040000")));
        backend.scans.add(scanResult(true, "0x2000", candidate("0x1004", "b0040000")));
        JSONObject started = invoke("memory_scan", process("start").put("sessionId", sid).put("regionIds", new JSONArray().put(region))
            .put("type", "int32").put("value", "1200"));
        String scanId = started.getString("scanId");
        assertEquals("paused", started.getString("state"));
        assertFalse(started.getBoolean("coverageComplete"));
        rejected("memory_scan_state", () -> invoke("memory_scan", process("refine").put("scanId", scanId).put("comparison", "changed")));
        assertEquals("ready", invoke("memory_scan", process("continue").put("scanId", scanId)).getString("state"));
        JSONObject page = invoke("memory_scan", process("results").put("scanId", scanId));
        assertEquals(2, page.getJSONArray("items").length());
        String oldCandidate = page.getJSONArray("items").getJSONObject(0).getString("candidateId");
        backend.scans.add(scanResult(true, "0x2000", candidate("0x1000", "4c040000")));
        JSONObject refined = invoke("memory_scan", process("refine").put("scanId", scanId).put("comparison", "eq").put("value", "1100"));
        assertEquals(1, refined.getInt("candidateCount"));
        assertEquals(2, backend.lastRefineSourceCount);
        rejected("memory_reference_invalid", () -> invoke("memory_read", new JSONObject().put("sessionId", sid).put("candidateId", oldCandidate)));
    }

    @Test public void candidateOverflowIsExplicitAndDoesNotBecomeUniqueEvidence() throws Exception {
        String sid = open("read_only");
        JSONObject overflowing = scanResult(false, "0x1004", candidate("0x1000", "b0040000")).put("candidateOverflow", true);
        backend.scans.add(overflowing);
        rejected("memory_candidate_limit", () -> invoke("memory_scan", process("start").put("sessionId", sid)
            .put("type", "int32").put("value", "1200")));
        JSONObject state = invoke("process_memory", process("status").put("sessionId", sid));
        assertEquals("failed", state.getString("scanState"));
    }

    @Test public void corruptReceiptLedgerDoesNotSilentlyEnableWrites() throws Exception {
        Files.write(ledger.toPath(), "not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ProcessMemoryTools broken = new ProcessMemoryTools(backend, name -> new ProcessMemoryTools.Target(name, 10123, 7, 10), ledger);
        try { broken.execute("process_memory", process("status"), OWNER, "status-corrupt", ACTIVE); fail("Expected recovery failure"); }
        catch (java.io.IOException expected) { assertEquals(0, backend.writes); }
    }

    @Test public void modelCannotSupplyTrustedIdentityAndUnknownFieldsAreRejected() throws Exception {
        rejected("invalid_argument", () -> invoke("process_memory", process("query").put("packageName", "com.example.game").put("uid", 0)));
        rejected("invalid_argument", () -> invoke("process_memory", process("query").put("packageName", "com.example.game").put("irrelevant", true)));
        assertEquals(0, backend.requests);
    }


    @Test public void omittedModeDefaultsToReadOnly() throws Exception {
        JSONObject session = invoke("process_memory", process("open").put("packageName", "com.example.game"));
        assertEquals("read_only", session.getString("mode"));
    }

    @Test public void malformedRegionSelectionDoesNotExpandToAllMappings() throws Exception {
        String sid = open("read_only");
        int before = backend.requests;
        for (Object bad : new Object[]{"all", new JSONObject(), JSONObject.NULL}) {
            rejected("invalid_argument", () -> invoke("memory_scan", process("start").put("sessionId", sid)
                .put("regionIds", bad).put("type", "int32").put("value", "1200")));
        }
        assertEquals(before, backend.requests);
    }

    @Test public void byteRefinementPreservesPatternLengthAcrossChangedComparison() throws Exception {
        String sid = open("read_only");
        backend.scans.add(scanResult(true, "0x2000", candidate("0x1000", "0102")));
        String scanId = invoke("memory_scan", process("start").put("sessionId", sid).put("type", "bytes").put("value", "0102")).getString("scanId");
        backend.scans.add(scanResult(true, "0x2000", candidate("0x1000", "0304")));
        invoke("memory_scan", process("refine").put("scanId", scanId).put("comparison", "changed"));
        backend.scans.add(scanResult(true, "0x2000", candidate("0x1000", "0304")));
        JSONObject result = invoke("memory_scan", process("refine").put("scanId", scanId).put("comparison", "eq").put("value", "0304"));
        assertEquals(1, result.getInt("candidateCount"));
    }


    @Test public void defaultScanRefreshesCurrentRegionsOnceBeforeCreatingItsPlan() throws Exception {
        String sid = open("read_only");
        backend.regionVersion = 2;
        backend.scans.add(scanResult(true, "0x2000", candidate("0x1000", "b0040000")));
        JSONObject result = invoke("memory_scan", process("start").put("sessionId", sid).put("type", "int32").put("value", "1200"));
        assertEquals("ready", result.getString("state"));
        assertEquals(1, backend.regionQueries);
        assertEquals("fixture-region-2", backend.lastScanSignature);
    }

    @Test public void changedUnrelatedMappingsPreserveStableCandidatesButKeepCoverageIncomplete() throws Exception {
        backend.twoRegions = true;
        String sid = open("read_only");
        backend.scans.add(scanResult(false, "0x1004", candidate("0x1000", "b0040000"))
            .put("changedRegionIndices", new JSONArray().put(1)).put("skippedBytes", 4096).put("coverageIncomplete", true));
        backend.scans.add(scanResult(true, "0x4000")
            .put("changedRegionIndices", new JSONArray().put(1)).put("skippedBytes", 0).put("coverageIncomplete", true));
        JSONObject first = invoke("memory_scan", process("start").put("sessionId", sid).put("type", "int32").put("value", "1200"));
        JSONObject done = invoke("memory_scan", process("continue").put("scanId", first.getString("scanId")));
        assertEquals("ready", done.getString("state"));
        assertEquals(1, done.getInt("candidateCount"));
        assertEquals(1, done.getInt("changedRegionCount"));
        assertEquals(4096, done.getLong("skippedBytes"));
        assertFalse(done.getBoolean("coverageComplete"));
        assertTrue(done.getBoolean("coverageIncomplete"));
        backend.scans.add(scanResult(true, "0x4000", candidate("0x1000", "b0040000")));
        JSONObject refined = invoke("memory_scan", process("refine").put("scanId", first.getString("scanId")).put("comparison", "eq").put("value", "1200"));
        assertFalse(refined.getBoolean("sourceCoverageComplete"));
        assertFalse(refined.getBoolean("coverageComplete"));
    }

    @Test public void byteCandidateRequiresANewScalarReadBeforeSingleWrite() throws Exception {
        String sid = open("read_write");
        backend.scans.add(scanResult(true, "0x2000", candidate("0x1000", "b0040000")));
        String scanId = invoke("memory_scan", process("start").put("sessionId", sid).put("type", "bytes").put("value", "b0040000")).getString("scanId");
        String candidateId = invoke("memory_scan", process("results").put("scanId", scanId)).getJSONArray("items").getJSONObject(0).getString("candidateId");
        JSONObject raw = invoke("memory_read", new JSONObject().put("sessionId", sid).put("candidateId", candidateId));
        assertEquals("bytes", raw.getString("type"));
        rejected("invalid_argument", () -> invoke("memory_write", new JSONObject().put("sessionId", sid)
            .put("readId", raw.getString("readId")).put("value", "99999")));
        JSONObject scalar = invoke("memory_read", new JSONObject().put("sessionId", sid).put("candidateId", candidateId).put("type", "int32"));
        assertEquals("1200", scalar.getJSONArray("values").getString(0));
        assertEquals("written_verified", invoke("memory_write", new JSONObject().put("sessionId", sid)
            .put("readId", scalar.getString("readId")).put("value", "99999")).getString("status"));
        assertEquals(1, backend.writes);
    }

    @Test public void unsupportedParameterReportsOnlyTheAllowedNames() throws Exception {
        String sid = open("read_only");
        try {
            invoke("memory_scan", process("start").put("sessionId", sid).put("type", "int32").put("value", "1200")
                .put("unknown-private-key", "secret123"));
            fail("Expected an operation parameter error");
        } catch (ActionException expected) {
            assertTrue(expected.getMessage().contains("Supported parameters for this operation:"));
            assertTrue(expected.getMessage().contains("regionIds"));
            assertFalse(expected.getMessage().contains("unknown-private-key"));
            assertFalse(expected.getMessage().contains("secret123"));
        }
    }


    @Test public void changedMappingPrunesEarlierCandidatesWithoutRetargetingTheirReferences() throws Exception {
        backend.twoRegions = true;
        String sid = open("read_only");
        backend.scans.add(scanResult(false, "0x1004", candidate("0x1000", "b0040000")));
        JSONObject first = invoke("memory_scan", process("start").put("sessionId", sid).put("type", "int32").put("value", "1200"));
        String scanId = first.getString("scanId");
        String oldCandidate = invoke("memory_scan", process("results").put("scanId", scanId)).getJSONArray("items").getJSONObject(0).getString("candidateId");
        backend.scans.add(scanResult(true, "0x4000", candidate("0x3000", "b0040000").put("regionIndex", 1))
            .put("changedRegionIndices", new JSONArray().put(0)).put("coverageIncomplete", true));
        JSONObject done = invoke("memory_scan", process("continue").put("scanId", scanId));
        assertEquals(1, done.getInt("candidateCount"));
        assertEquals(1, done.getInt("invalidatedCandidateCount"));
        rejected("memory_reference_invalid", () -> invoke("memory_read", new JSONObject().put("sessionId", sid).put("candidateId", oldCandidate)));
        JSONObject surviving = invoke("memory_scan", process("results").put("scanId", scanId)).getJSONArray("items").getJSONObject(0);
        assertEquals("0x3000", surviving.getString("address"));
        assertEquals("1200", invoke("memory_read", new JSONObject().put("sessionId", sid).put("candidateId", surviving.getString("candidateId")))
            .getJSONArray("values").getString(0));
    }

    private static JSONObject candidate(String address, String hex) throws Exception {
        return new JSONObject().put("address", address).put("hex", hex).put("regionIndex", 0);
    }
    private static JSONObject scanResult(boolean finished, String next, JSONObject... candidates) throws Exception {
        JSONArray array = new JSONArray(); for (JSONObject candidate : candidates) array.put(candidate);
        return new JSONObject().put("candidates", array).put("finished", finished)
            .put("cursor", new JSONObject().put("regionIndex", finished ? 1 : 0).put("address", next))
            .put("nextIndex", finished ? 2 : 1).put("scannedBytes", 4).put("unreadableBytes", 0).put("candidateOverflow", false);
    }

    private static final class FakeBackend implements MemoryBackend {
        int requests, writes, lastRefineSourceCount, regionQueries, regionVersion = 1; boolean disconnectAfterPreparation, helperAlive, targetChanged, twoRegions;
        String lastScanSignature;
        Checked prepared; String current = "b0040000"; final Queue<JSONObject> scans = new ArrayDeque<>();
        @Override public JSONObject capabilities() throws Exception { return new JSONObject().put("packaged", true); }
        JSONObject process() throws Exception {
            return new JSONObject().put("pid", 456).put("uid", 10123).put("startTime", "12345")
                .put("name", "com.example.game").put("bitness", 64);
        }
        JSONObject region() throws Exception {
            return new JSONObject().put("start", "0x1000").put("end", "0x2000").put("perms", "rw-p")
                .put("offset", "0x0").put("dev", "00:00").put("inode", "0").put("name", "[anon:test]").put("signature", "fixture-region-" + regionVersion);
        }
        JSONArray regionItems() throws Exception {
            JSONArray result = new JSONArray().put(region());
            if (twoRegions) result.put(new JSONObject(region().toString()).put("start", "0x3000").put("end", "0x4000").put("signature", "fixture-second-region"));
            return result;
        }
        @Override public JSONObject request(JSONObject request, Cancellation cancellation, BeforeWrite gate) throws Exception {
            requests++; cancellation.check();
            String op = request.getString("op");
            if (targetChanged && !SetHolder.METADATA.contains(op)) throw new ActionException("memory_target_changed", "fixture target changed");
            return switch (op) {
                case "query" -> new JSONObject().put("processes", new JSONArray().put(process()));
                case "inspect" -> process().put("pageSize", 4096).put("readAccess", true)
                    .put("writeEligibility", request.optString("mode").equals("read_write") ? "eligible_unverified" : "not_requested")
                    .put("regions", regionItems());
                case "regions" -> { regionQueries++; yield new JSONObject().put("regions", regionItems()).put("pageSize", 4096); }
                case "read" -> {
                    int length = request.getInt("length");
                    String hex = current;
                    while (hex.length() < length * 2) hex += "00000000";
                    yield new JSONObject().put("hex", hex.substring(0, length * 2)).put("bytesRead", length);
                }
                case "helper_status" -> new JSONObject().put("alive", helperAlive);
                case "scan", "refine" -> {
                    lastScanSignature = request.getJSONArray("regions").getJSONObject(0).getString("signature");
                    if (op.equals("refine")) lastRefineSourceCount = request.getJSONArray("candidates").length();
                    yield scans.remove();
                }
                case "write" -> {
                    if (!current.equals(request.getString("expectedHex"))) yield new JSONObject().put("status", "not_dispatched")
                        .put("dispatched", false).put("bytesWritten", 0).put("writeResultKnown", true).put("helperExited", true);
                    gate.prepared(new JSONObject().put("pid", 987).put("startTime", "7654").put("bootId", "test-boot"));
                    if (prepared != null) prepared.run();
                    if (disconnectAfterPreparation) throw new java.io.IOException("simulated receipt loss");
                    writes++; current = request.getString("valueHex");
                    yield new JSONObject().put("status", "written_verified").put("dispatched", true)
                        .put("bytesWritten", current.length() / 2).put("writeResultKnown", true).put("helperExited", true)
                        .put("readback", new JSONObject().put("status", "matched").put("hex", current));
                }
                default -> throw new AssertionError(op);
            };
        }
    }
    private static final class SetHolder { static final java.util.Set<String> METADATA = java.util.Set.of("query", "helper_status"); }
}
