package io.droyagent.app;

import android.content.Context;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONObject;

/** Durable native ownership of an already-authorized, offline memory purge. */
public final class NativeCompanionMemoryPurge {
    private static final String PREFS = "native_companion_memory_purge", OPERATION = "operation", RESULT = "result";
    private static volatile boolean running;
    private NativeCompanionMemoryPurge() {}

    public static boolean pending(Context context) { return context.getSharedPreferences(PREFS, 0).contains(OPERATION); }

    public static JSONObject request(Context context, JSONObject request) throws Exception {
        String operation = request.getString("operation");
        fields(request, "operation", "operationId", "memorySpaceId");
        if (operation.equals("status")) return inspect(context, request.optString("operationId"), request.optString("memorySpaceId"));
        if (!operation.equals("purge")) throw new IOException("COMPANION_MEMORY_OPERATION_INVALID");
        return begin(context, identity(request.getString("memorySpaceId")), identity(request.getString("operationId")));
    }

    private static synchronized JSONObject begin(Context context, String memorySpaceId, String operationId) throws Exception {
        Context app = context.getApplicationContext();
        if (pending(app)) {
            JSONObject previous = read(app);
            requireIdentity(previous, operationId, memorySpaceId);
            if (!running) launch(app);
            return inspect(app, operationId, memorySpaceId);
        }
        JSONObject oldResult = storedResult(app);
        if (oldResult.optBoolean("completed") && oldResult.optString("operationId").equals(operationId)
            && oldResult.optString("memorySpaceId").equals(memorySpaceId)) return oldResult;
        if (running || NativeCompanionDeletion.pending(app) || !RuntimeService.ready || RuntimeService.hostPid < 1
            || RuntimeService.hostBirth.isEmpty() || !NativeRuntimeHost.claimCompanionMaintenance())
            throw new IOException("COMPANION_MEMORY_MAINTENANCE_BUSY");
        JSONObject journal = new JSONObject().put("version", 1).put("kind", "memory-purge").put("phase", "waiting")
            .put("operationId", operationId).put("memorySpaceId", memorySpaceId).put("port", RuntimeService.webPort)
            .put("token", RuntimeService.webToken).put("hostPid", RuntimeService.hostPid).put("hostBirth", RuntimeService.hostBirth);
        boolean saved = false;
        try {
            // This endpoint returns its lease immediately; waiting for Agent idle
            // here would deadlock the workbench tool which requested the purge.
            JSONObject receipt = NativeCompanionDeletion.request(journal, "POST", "prepare-memory-purge");
            requireIdentity(receipt, operationId, memorySpaceId);
            if (receipt.getInt("hostPid") != journal.getInt("hostPid")) throw new IOException("COMPANION_MAINTENANCE_HOST_CHANGED");
            journal.put("companionId", identity(receipt.getString("companionId")))
                .put("admissionEpoch", identity(receipt.getString("admissionEpoch")));
            validateReceipt(journal, receipt, true);
            if (!NativeCompanionDeletion.sameProcess(journal, "host")) throw new IOException("COMPANION_MAINTENANCE_HOST_CHANGED");
            save(app, journal); saved = true;
            launch(app);
            return view(journal, true);
        } finally {
            if (!saved) {
                // A failed synchronous commit can have an uncertain disk outcome.
                // If its private journal is visible, preserve the exclusion for recovery.
                if (!pending(app)) {
                    try { if (NativeCompanionDeletion.sameProcess(journal, "host")) NativeCompanionDeletion.request(journal, "DELETE", null); }
                    finally { NativeRuntimeHost.companionMaintenance(false); }
                }
            }
        }
    }

    public static synchronized boolean resume(Context context) {
        if (running || !pending(context) || NativeCompanionDeletion.pending(context)) return false;
        NativeRuntimeHost.companionMaintenance(true);
        launch(context.getApplicationContext()); return true;
    }

    private static void launch(Context context) {
        running = true; NativeRuntimeHost.companionMaintenance(true);
        new Thread(() -> execute(context), "droy-memory-purge").start();
    }

    private static void execute(Context context) {
        boolean held = false, completed = false;
        JSONObject journal = new JSONObject();
        try {
            journal = read(context);
            if (journal.has("error")) { journal.remove("error"); save(context, journal); }
            if (journal.getString("phase").equals("complete")) { finish(context, journal); completed = true; return; }
            if (journal.getString("phase").equals("waiting")) {
                if (NativeCompanionDeletion.sameProcess(journal, "host")) {
                    while (true) {
                        JSONObject receipt = NativeCompanionDeletion.request(journal, "GET", null);
                        validateReceipt(journal, receipt, true);
                        if (!NativeCompanionDeletion.sameProcess(journal, "host")) throw new IOException("COMPANION_MAINTENANCE_HOST_CHANGED");
                        if (receipt.optString("phase").equals("ready")) break;
                        Thread.sleep(500);
                    }
                    journal.put("phase", "shutdown"); save(context, journal);
                } else {
                    // The persisted lease still names the accepted operation.
                    // Actual process exit plus the startup exclusion is stronger
                    // than an old live quiet receipt; the runner rechecks the grant.
                    journal.put("phase", "offline"); save(context, journal);
                }
            }
            if (journal.getString("phase").equals("shutdown")) {
                if (NativeCompanionDeletion.sameProcess(journal, "host")) {
                    try { NativeCompanionDeletion.request(journal, "POST", "shutdown"); }
                    catch (IOException lostResponse) { if (NativeCompanionDeletion.sameProcess(journal, "host")) throw lostResponse; }
                }
                NativeCompanionDeletion.awaitGone(journal, "host");
                journal.put("phase", "offline"); save(context, journal);
            }
            if (!journal.getString("phase").equals("offline")) throw new IOException("COMPANION_MEMORY_MAINTENANCE_STORAGE");
            NativeCompanionDeletion.awaitGone(journal, "host");
            NativeCompanionDeletion.awaitGone(journal, "worker");
            long deadline = android.os.SystemClock.elapsedRealtime() + 30000;
            while (!(held = RuntimeService.beginCompanionHistoryChange())) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) throw new IOException("COMPANION_MAINTENANCE_RUNTIME_BUSY");
                Thread.sleep(100);
            }
            JSONObject result = NativeCompanionDeletion.offlineMemoryPurge(context, journal, value -> save(context, value));
            requireIdentity(result, journal.getString("operationId"), journal.getString("memorySpaceId"));
            if (!result.optBoolean("completed") || !journal.getString("companionId").equals(result.optString("companionId")))
                throw new IOException("COMPANION_MEMORY_PURGE_INCOMPLETE");
            journal.put("phase", "complete"); journal.remove("error"); save(context, journal);
            finish(context, journal); completed = true;
        } catch (Exception error) {
            String code = error.getMessage();
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,100}")) code = "COMPANION_MEMORY_PURGE_FAILED";
            try { if (journal.has("operationId")) { journal.put("error", code); save(context, journal); } }
            catch (Exception storageFailure) { /* Keep the previously committed recovery journal. */ }
        } finally {
            if (held) RuntimeService.endHistoryChange();
            synchronized (NativeCompanionMemoryPurge.class) { running = false; }
            if (completed) { NativeRuntimeHost.companionMaintenance(false); NativeRuntimeHost.start(context); }
        }
    }

    static void validateReceipt(JSONObject journal, JSONObject receipt, boolean bound) throws Exception {
        requireIdentity(receipt, journal.getString("operationId"), journal.getString("memorySpaceId"));
        if (receipt.getInt("hostPid") != journal.getInt("hostPid")) throw new IOException("COMPANION_MAINTENANCE_HOST_CHANGED");
        identity(receipt.getString("companionId")); identity(receipt.getString("admissionEpoch"));
        if (bound && (!journal.getString("companionId").equals(receipt.getString("companionId"))
            || !journal.getString("admissionEpoch").equals(receipt.getString("admissionEpoch"))))
            throw new IOException("COMPANION_MAINTENANCE_IDENTITY");
        if (!Set.of("waiting", "ready").contains(receipt.optString("phase"))) throw new IOException("COMPANION_MEMORY_PREPARATION_FAILED");
    }
    private static void requireIdentity(JSONObject value, String operationId, String memorySpaceId) throws Exception {
        if (!operationId.equals(value.optString("operationId")) || !memorySpaceId.equals(value.optString("memorySpaceId")))
            throw new IOException("COMPANION_MEMORY_OPERATION_CONFLICT");
    }
    private static JSONObject read(Context context) throws Exception {
        JSONObject value = new JSONObject(context.getSharedPreferences(PREFS, 0).getString(OPERATION, ""));
        if (value.getInt("version") != 1 || !value.getString("kind").equals("memory-purge")
            || !Set.of("waiting", "shutdown", "offline", "complete").contains(value.getString("phase")))
            throw new IOException("COMPANION_MEMORY_MAINTENANCE_STORAGE");
        identity(value.getString("operationId")); identity(value.getString("memorySpaceId"));
        identity(value.getString("companionId")); identity(value.getString("admissionEpoch"));
        if (value.getInt("hostPid") < 1 || value.getString("hostBirth").isEmpty()) throw new IOException("COMPANION_MEMORY_MAINTENANCE_STORAGE");
        return value;
    }
    private static JSONObject storedResult(Context context) throws Exception {
        String raw = context.getSharedPreferences(PREFS, 0).getString(RESULT, "");
        return raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
    }
    private static JSONObject inspect(Context context, String operationId, String memorySpaceId) throws Exception {
        JSONObject value = pending(context) ? view(read(context), true) : storedResult(context);
        if (!operationId.isEmpty() && !operationId.equals(value.optString("operationId"))
            || !memorySpaceId.isEmpty() && !memorySpaceId.equals(value.optString("memorySpaceId")))
            throw new IOException("COMPANION_MEMORY_OPERATION_NOT_FOUND");
        if (value.length() == 0) value.put("pending", false).put("completed", false).put("phase", "idle");
        return value;
    }
    private static JSONObject view(JSONObject journal, boolean pending) throws Exception {
        JSONObject result = new JSONObject().put("operationId", journal.getString("operationId"))
            .put("memorySpaceId", journal.getString("memorySpaceId"))
            .put("phase", journal.has("error") ? "failed" : journal.getString("phase"))
            .put("pending", pending).put("completed", !pending && journal.getString("phase").equals("complete"));
        if (journal.has("error")) result.put("error", journal.getString("error"));
        return result;
    }
    private static void finish(Context context, JSONObject journal) throws Exception {
        if (!context.getSharedPreferences(PREFS, 0).edit().putString(RESULT, view(journal, false).toString()).remove(OPERATION).commit())
            throw new IOException("COMPANION_MEMORY_MAINTENANCE_STORAGE");
    }
    private static void save(Context context, JSONObject journal) throws IOException {
        if (!context.getSharedPreferences(PREFS, 0).edit().putString(OPERATION, journal.toString()).commit())
            throw new IOException("COMPANION_MEMORY_MAINTENANCE_STORAGE");
    }
    private static String identity(String value) throws IOException {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}")) throw new IOException("COMPANION_MEMORY_ID_INVALID");
        return value;
    }
    private static void fields(JSONObject request, String... allowed) throws Exception {
        Set<String> keys = Set.of(allowed);
        for (Iterator<String> iterator = request.keys(); iterator.hasNext();) if (!keys.contains(iterator.next()))
            throw new IOException("COMPANION_MEMORY_INVALID_REQUEST");
    }
}
