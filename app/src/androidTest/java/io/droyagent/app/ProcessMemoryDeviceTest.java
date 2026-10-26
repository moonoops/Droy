package io.droyagent.app;

import android.Manifest;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.ProcessMemoryTools;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Requires the separate local fixture APK and real Root approval for Droy. */
@RunWith(AndroidJUnit4.class)
public final class ProcessMemoryDeviceTest {
    private static final String FIXTURE = "io.droyagent.memoryfixture";
    private static final Uri METADATA = Uri.parse("content://" + FIXTURE + ".metadata");
    private static final String[] CHOICES = {"allowApps", "allowRoot", "allowMemoryRead", "allowMemoryWrite", "toolsStopped"};
    private final Map<String, Boolean> saved = new HashMap<>();
    private Instrumentation instrumentation;
    private UiAutomation automation;
    private Context context;
    private SharedPreferences settings;
    private ProductBridge bridge;
    private String owner;
    private int calls;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        context.getPackageManager().getPackageInfo(FIXTURE, 0);
        settings = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        SharedPreferences.Editor setup = settings.edit();
        for (String key : CHOICES) {
            if (settings.contains(key)) saved.put(key, settings.getBoolean(key, false));
            setup.putBoolean(key, !key.equals("toolsStopped"));
        }
        assertTrue(setup.commit());
        ProcessMemoryTools.get(context).closeAll();
        owner = "memory-device-" + UUID.randomUUID();
        bridge = new ProductBridge(context, "memory-test-" + UUID.randomUUID());
        bridge.policy.enable();
        launch("reset");
        awaitGold(1200);
    }

    @After public void tearDown() throws Exception {
        if (context != null && settings != null) {
            if (owner != null) ProcessMemoryTools.get(context).end(owner);
            if (bridge != null) bridge.policy.stop();
            shell("am force-stop " + FIXTURE);
            SharedPreferences.Editor restore = settings.edit();
            for (String key : CHOICES) {
                if (saved.containsKey(key)) restore.putBoolean(key, saved.get(key));
                else restore.remove(key);
            }
            assertTrue("Could not restore product choices", restore.commit());
            RuntimeService.memoryPermissionsChanged(context);
            RuntimeService.applySavedOperationState();
        }
    }

    @Test public void scanRefineAndSingleWriteChangeOnlyTheFixtureGold() throws Exception {
        JSONObject queried = call("process_memory", args("query").put("packageName", FIXTURE));
        assertTrue("Fixture process was not found", queried.getJSONArray("processes").length() > 0);
        JSONObject opened = open("read_write");
        String session = opened.getString("sessionId");
        JSONObject region = dataRegion(session);
        JSONObject scan = call("memory_scan", args("start").put("sessionId", session)
            .put("regionIds", new JSONArray().put(region.getString("regionId")))
            .put("type", "int32").put("value", "1200"));
        scan = completeScan(scan);
        assertTrue(scan.getBoolean("coverageComplete"));
        assertEquals("The fixture page must expose gold and two equal decoys", 3, scan.getInt("candidateCount"));
        launch("spend");
        awaitGold(1100);
        scan = completeScan(call("memory_scan", args("refine").put("scanId", scan.getString("scanId"))
            .put("comparison", "eq").put("value", "1100")));
        assertEquals(1, scan.getInt("candidateCount"));
        JSONObject candidates = call("memory_scan", args("results").put("scanId", scan.getString("scanId")));
        JSONArray items = candidates.getJSONArray("items");
        assertEquals(1, items.length());
        // Metadata verifies the implementation, rather than teaching a model the answer.
        assertEquals(fixture("metadata").getString("goldAddress"), items.getJSONObject(0).getString("address"));
        JSONObject read = call("memory_read", new JSONObject().put("sessionId", session)
            .put("candidateId", items.getJSONObject(0).getString("candidateId")));
        assertEquals("1100", read.getJSONArray("values").getString(0));
        JSONObject writeArgs = new JSONObject().put("sessionId", session).put("readId", read.getString("readId"))
            .put("value", "99999");
        String callId = "single-write";
        JSONObject written = bridge.executeTool("memory_write", writeArgs, owner, callId);
        assertEquals("written_verified", written.getString("status"));
        assertEquals(4, written.getInt("bytesWritten"));
        assertTrue(written.getBoolean("writeResultKnown"));
        JSONObject replay = bridge.executeTool("memory_write", writeArgs, owner, callId);
        assertEquals(written.getString("writeReceiptId"), replay.getString("writeReceiptId"));
        expectCode("memory_request_conflict", () -> bridge.executeTool("memory_write",
            new JSONObject(writeArgs.toString()).put("value", "100"), owner, "changed-write"));
        JSONObject state = awaitGold(99999);
        assertEquals(1200, state.getInt("decoyA"));
        assertEquals(1200, state.getInt("decoyB"));
        assertEquals(77, state.getInt("energy"));
        awaitVisible("金币：99999");
    }

    @Test public void readOnlySessionAndForeignTaskCannotWrite() throws Exception {
        String session = open("read_only").getString("sessionId");
        JSONObject region = dataRegion(session);
        JSONObject read = readGold(session, region);
        expectCode("memory_read_only", () -> call("memory_write", new JSONObject().put("sessionId", session)
            .put("readId", read.getString("readId")).put("value", "99999")));
        JSONObject foreignRead = new JSONObject().put("sessionId", session).put("regionId", region.getString("regionId"))
            .put("offset", "0x80").put("type", "int32").put("count", 1);
        expectCode("memory_owner_mismatch", () -> ProcessMemoryTools.get(context).execute("memory_read",
            foreignRead, "another-task", "foreign-read", () -> {}));
        assertEquals(1200, fixture("state").getInt("gold"));
    }

    @Test public void restartedTargetRejectsOldReadAndDoesNotModifyTheNewProcess() throws Exception {
        String session = open("read_write").getString("sessionId");
        JSONObject read = readGold(session, dataRegion(session));
        int oldPid = fixture("metadata").getInt("pid");
        shell("am force-stop " + FIXTURE);
        launch("reset");
        awaitGold(1200);
        assertNotEquals(oldPid, fixture("metadata").getInt("pid"));
        expectCode("memory_target_changed", () -> call("memory_write", new JSONObject().put("sessionId", session)
            .put("readId", read.getString("readId")).put("value", "99999")));
        assertEquals(1200, fixture("state").getInt("gold"));
    }

    @Test public void stopKeepsLocalCleanupAvailableAndPreventsNewAccess() throws Exception {
        String session = open("read_write").getString("sessionId");
        RuntimeService.pauseOperations(context);
        bridge.policy.stop();
        call("process_memory", args("status"));
        assertTrue("Stop must release the target session", call("process_memory", args("close")
            .put("sessionId", session)).getBoolean("alreadyClosed"));
        try {
            call("process_memory", args("query").put("packageName", FIXTURE));
            fail("Stopped product must reject new process access");
        } catch (IllegalStateException expected) { }
        assertEquals(1200, fixture("state").getInt("gold"));
    }

    @Test public void revokedWritePermissionRejectsReadWriteOpen() throws Exception {
        assertTrue(settings.edit().putBoolean("allowMemoryWrite", false).commit());
        RuntimeService.memoryPermissionsChanged(context);
        try {
            open("read_write");
            fail("Read-write open must require the write capability");
        } catch (SecurityException expected) { }
        String readOnly = open("read_only").getString("sessionId");
        assertEquals("1200", readGold(readOnly, dataRegion(readOnly)).getJSONArray("values").getString(0));
    }

    private JSONObject open(String mode) throws Exception {
        return call("process_memory", args("open").put("packageName", FIXTURE).put("mode", mode));
    }

    private JSONObject dataRegion(String session) throws Exception {
        JSONObject metadata = fixture("metadata");
        String expected = metadata.getString("regionStart");
        JSONObject request = args("regions").put("sessionId", session).put("limit", 50);
        int inspected = 0, total = 0;
        for (int page = 0; page < 400; page++) {
            JSONObject result = call("process_memory", request);
            JSONArray items = result.getJSONArray("items");
            inspected += items.length(); total = result.getInt("total");
            for (int i = 0; i < items.length(); i++)
                if (hex(expected).equals(hex(items.getJSONObject(i).getString("start")))) return items.getJSONObject(i);
            String cursor = result.optString("nextCursor", "");
            if (cursor.isEmpty() || result.isNull("nextCursor")) break;
            request.put("cursor", cursor);
        }
        throw new AssertionError("The fixture's guarded private data mapping was not returned; inspected " + inspected + " of " + total);
    }

    private JSONObject readGold(String session, JSONObject region) throws Exception {
        String offset = "0x" + hex(fixture("metadata").getString("goldAddress"))
            .subtract(hex(region.getString("start"))).toString(16);
        return call("memory_read", new JSONObject().put("sessionId", session).put("regionId", region.getString("regionId"))
            .put("offset", offset).put("type", "int32").put("count", 1));
    }

    private JSONObject completeScan(JSONObject scan) throws Exception {
        for (int attempt = 0; !scan.optString("state").equals("ready") && attempt < 20; attempt++)
            scan = call("memory_scan", args("continue").put("scanId", scan.getString("scanId")));
        assertEquals("ready", scan.getString("state"));
        return scan;
    }

    private JSONObject call(String action, JSONObject args) throws Exception {
        return bridge.executeTool(action, args, owner, "call-" + (++calls));
    }

    private static JSONObject args(String operation) throws Exception { return new JSONObject().put("operation", operation); }
    private static BigInteger hex(String value) { return new BigInteger(value.substring(2), 16); }

    private void launch(String action) throws Exception {
        if (!java.util.Set.of("reset", "spend").contains(action)) throw new IllegalArgumentException("Unknown fixture action");
        shell("am start -W -n " + FIXTURE + "/.MainActivity --es fixtureAction " + action);
    }

    private JSONObject fixture(String method) throws Exception {
        Bundle result = context.getContentResolver().call(METADATA, method, null, null);
        assertNotNull("The separate memory fixture is unavailable", result);
        return new JSONObject(result.getString("json"));
    }

    private JSONObject awaitGold(int expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        JSONObject state;
        do {
            state = fixture("state");
            if (state.getInt("gold") == expected) return state;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertEquals("Fixture gold did not reach expected value", expected, state.getInt("gold"));
        return state;
    }

    private void awaitVisible(String text) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        do {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null && contains(root, text)) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("The fixture did not visibly show " + text);
    }

    private static boolean contains(AccessibilityNodeInfo node, String text) {
        if (node.getText() != null && node.getText().toString().contains(text)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && contains(child, text)) return true;
        }
        return false;
    }

    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = automation.executeShellCommand(command);
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] bytes = new byte[1024];
            while (input.read(bytes) != -1) { }
        }
    }

    private static void expectCode(String code, Throwing action) throws Exception {
        try { action.run(); fail("Expected " + code); }
        catch (ActionException expected) { assertEquals(code, expected.getCode()); }
    }

    private interface Throwing { void run() throws Exception; }
}
