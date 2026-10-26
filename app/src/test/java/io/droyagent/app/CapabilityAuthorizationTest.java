package io.droyagent.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CapabilityAuthorizationTest {
    private static JSONObject tool(boolean screen, String... permissions) throws Exception {
        return new JSONObject().put("screen", screen).put("permissions", new JSONArray(permissions));
    }
    private static JSONObject group(String id) {
        try { return new JSONObject().put("preference", "allow_" + id).put("label", id); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    @Test public void deviceQueryWorksWithoutScreenPermission() throws Exception {
        CapabilityAuthorization.check(tool(false, "device"), CapabilityAuthorizationTest::group,
            key -> key.equals("allow_device"), false);
    }
    @Test public void authorizedScreenToolsHaveNoProductPageRestriction() throws Exception {
        for (String permission : new String[]{"observe", "control", "screenshot"}) {
            CapabilityAuthorization.check(tool(true, permission), CapabilityAuthorizationTest::group,
                key -> true, false);
            CapabilityAuthorization.check(tool(true, permission), CapabilityAuthorizationTest::group,
                key -> true, true);
        }
    }
    @Test public void disabledCapabilitiesDoNotInheritScreenGrant() throws Exception {
        for (String permission : new String[]{"device", "calendar", "files", "compute"}) {
            try { CapabilityAuthorization.check(tool(false, permission), CapabilityAuthorizationTest::group,
                key -> key.equals("allow_observe"), false); fail(); }
            catch (SecurityException expected) { assertTrue(expected.getMessage().contains(permission)); }
        }
    }
    @Test public void writeNeedsBothReadAndWriteAndRootCannotExpandGrant() throws Exception {
        try { CapabilityAuthorization.check(tool(false, "files", "export"), CapabilityAuthorizationTest::group,
            key -> key.equals("allow_files"), false); fail(); }
        catch (SecurityException expected) { assertTrue(expected.getMessage().contains("export")); }
        try { CapabilityAuthorization.check(tool(true, "observe"), CapabilityAuthorizationTest::group,
            key -> !key.equals("allowRoot"), true); fail(); }
        catch (SecurityException expected) { assertTrue(expected.getMessage().contains("Root")); }
    }

    private static JSONObject memoryTool() throws Exception {
        JSONObject operations = new JSONObject();
        for (String operation : new String[]{"query", "open_read_only", "regions"})
            operations.put(operation, new JSONArray(new String[]{"apps", "memoryRead"}));
        operations.put("open_read_write", new JSONArray(new String[]{"apps", "memoryRead", "memoryWrite"}));
        operations.put("status", new JSONArray()).put("close", new JSONArray());
        return tool(false).put("action", "process_memory").put("name", "android_process_memory")
            .put("operationPermissions", operations);
    }

    @Test public void readWriteOpenChecksWriteGrantBeforeOpeningSession() throws Exception {
        JSONObject invocation = CapabilityAuthorization.forArguments(memoryTool(),
            new JSONObject().put("operation", "open").put("mode", "read_write").put("permissions", new JSONArray()));
        SecurityException denied = assertThrows(SecurityException.class, () -> CapabilityAuthorization.check(invocation,
            CapabilityAuthorizationTest::group, key -> !key.equals("allow_memoryWrite"), false));
        assertTrue(denied.getMessage().contains("memoryWrite"));
        JSONObject read = CapabilityAuthorization.forArguments(memoryTool(),
            new JSONObject().put("operation", "open").put("mode", "read_only"));
        CapabilityAuthorization.check(read, CapabilityAuthorizationTest::group,
            key -> !key.equals("allow_memoryWrite"), false);
    }

    @Test public void omittedMemoryOpenModeDefaultsToReadOnlyPermissions() throws Exception {
        JSONObject read = CapabilityAuthorization.forArguments(memoryTool(),
            new JSONObject().put("operation", "open"));
        assertEquals("[\"apps\",\"memoryRead\"]", read.getJSONArray("permissions").toString());
        CapabilityAuthorization.check(read, CapabilityAuthorizationTest::group,
            key -> !key.equals("allow_memoryWrite"), false);
    }

    @Test public void invalidOperationAndModeCannotSelectAnEmptyPermissionSet() throws Exception {
        for (JSONObject args : new JSONObject[]{new JSONObject(), new JSONObject().put("operation", "execute"),
                new JSONObject().put("operation", "open_read_only"),
                new JSONObject().put("operation", "open").put("mode", JSONObject.NULL),
                new JSONObject().put("operation", "open").put("mode", true),
                new JSONObject().put("operation", "open").put("mode", "root"),
                new JSONObject().put("operation", true)}) {
            assertThrows(IllegalArgumentException.class, () -> CapabilityAuthorization.forArguments(memoryTool(), args));
        }
    }

    @Test public void localMemoryStatusAndCloseNeedNoDataGrant() throws Exception {
        for (String operation : new String[]{"status", "close"}) {
            JSONObject invocation = CapabilityAuthorization.forArguments(memoryTool(), new JSONObject().put("operation", operation));
            assertEquals(0, invocation.getJSONArray("permissions").length());
            CapabilityAuthorization.check(invocation, CapabilityAuthorizationTest::group, key -> false, false);
        }
    }

    @Test public void pausedCapabilitiesStillExposeOnlyLocalMemoryCleanupAndReceipts() throws Exception {
        JSONObject grants = new JSONObject().put("apps", false).put("memoryRead", false)
            .put("memoryWrite", false).put("root", false);
        JSONObject state = ProductBridge.memoryAvailability(memoryTool(), grants, true,
            new JSONObject().put("supported", false).put("reason", "missing_native_helper"));
        assertTrue(state.getBoolean("available"));
        JSONObject operations = state.getJSONObject("operations");
        for (String operation : new String[]{"query", "open_read_only", "open_read_write", "regions"})
            assertFalse(operations.getJSONObject(operation).getBoolean("available"));
        assertTrue(operations.getJSONObject("status").getBoolean("available"));
        assertTrue(operations.getJSONObject("close").getBoolean("available"));
        assertTrue(operations.getJSONObject("close").getBoolean("localOnly"));
        assertEquals("not_required", operations.getJSONObject("status").getString("targetAccess"));
    }

    @Test public void memoryAvailabilitySeparatesReadWriteAndBackendRequirements() throws Exception {
        JSONObject grants = new JSONObject().put("apps", true).put("memoryRead", true)
            .put("memoryWrite", false).put("root", true);
        JSONObject operations = ProductBridge.memoryAvailability(memoryTool(), grants, false,
            new JSONObject().put("supported", true)).getJSONObject("operations");
        assertTrue(operations.getJSONObject("open_read_only").getBoolean("available"));
        assertFalse(operations.getJSONObject("open_read_write").getBoolean("available"));
        assertEquals("checked_on_open", operations.getJSONObject("query").getString("targetAccess"));
        JSONObject unsupported = ProductBridge.memoryAvailability(memoryTool(), grants, false,
            new JSONObject().put("supported", false).put("reason", "missing_native_helper"))
            .getJSONObject("operations").getJSONObject("query");
        assertTrue(unsupported.getBoolean("productAllowed"));
        assertFalse(unsupported.getBoolean("available"));
        assertEquals("missing_native_helper", unsupported.getJSONArray("reasons").getString(0));
    }

    @Test public void scanDiscardIsLocalWhileScanResultsStillRequireReadPermission() throws Exception {
        JSONObject scan = tool(false, "apps", "memoryRead").put("action", "memory_scan").put("name", "android_memory_scan");
        JSONObject operations = ProductBridge.memoryAvailability(scan, new JSONObject(), true,
            new JSONObject().put("supported", false)).getJSONObject("operations");
        assertTrue(operations.getJSONObject("discard").getBoolean("available"));
        assertFalse(operations.getJSONObject("results").getBoolean("available"));
        assertFalse(operations.getJSONObject("start").getBoolean("available"));
    }
}
