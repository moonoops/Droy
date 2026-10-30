package io.droyagent.app;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the live-to-offline boundary. No UI state is accepted as proof of Node exit. */
public final class NativeCompanionDeletion {
    public interface LocalParticipant {
        JSONObject prepare(String operationId, String companionId, String admissionEpoch) throws Exception;
        JSONObject retire(JSONObject runtimeReceipt) throws Exception;
        void release() throws Exception;
    }
    public record Snapshot(String phase, String companionId, String error, boolean active,
                           boolean canCancel, boolean blocksEditing) {}
    private static final String PREFS = "native_companion_deletion", KEY = "operation";
    private static final String PATH = "/droy/native/maintenance/companion";
    private static volatile Snapshot state = new Snapshot("idle", "", "", false, false, false);
    private static volatile boolean running, cancelled;
    private NativeCompanionDeletion() {}

    public static boolean pending(Context context) { return context.getSharedPreferences(PREFS, 0).contains(KEY); }
    public static Snapshot inspect(Context context) {
        if (!running && state.phase.equals("idle") && pending(context))
            return new Snapshot("recovery", "", "", false, false, true);
        return state;
    }
    public static synchronized boolean begin(Context context, String companionId, LocalParticipant local) {
        if (running || pending(context) || NativeCompanionMemoryPurge.pending(context) || !RuntimeService.ready || RuntimeService.hostPid < 1 || RuntimeService.hostBirth.isEmpty()
                || !companionId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")) return false;
        if (!NativeRuntimeHost.claimCompanionMaintenance()) return false;
        try {
            JSONObject journal = new JSONObject().put("version", 1).put("operationId", UUID.randomUUID().toString())
                .put("companionId", companionId).put("phase", "waiting").put("port", RuntimeService.webPort)
                .put("token", RuntimeService.webToken).put("hostPid", RuntimeService.hostPid).put("hostBirth", RuntimeService.hostBirth);
            save(context, journal); launch(context.getApplicationContext(), local); return true;
        } catch (Exception error) { NativeRuntimeHost.companionMaintenance(false); state = new Snapshot("failed", companionId, "COMPANION_MAINTENANCE_STORAGE", false, false, false); return false; }
    }
    public static synchronized boolean resume(Context context, LocalParticipant local) {
        if (running || !pending(context)) return false;
        launch(context.getApplicationContext(), local); return true;
    }
    public static void cancel() { if (state.canCancel) cancelled = true; }
    public static synchronized void dismissResult(Context context) {
        if (!pending(context) && (!running || state.phase.equals("complete") || state.phase.equals("cancelled")
                || state.phase.equals("failed") && !state.blocksEditing))
            state = new Snapshot("idle", "", "", false, false, false);
    }
    private static void launch(Context context, LocalParticipant local) {
        running = true; cancelled = false; NativeRuntimeHost.companionMaintenance(true);
        state = new Snapshot("preparing", state.companionId, "", true, false, true);
        new Thread(() -> execute(context, local), "droy-companion-maintenance").start();
    }
    private static void publish(JSONObject journal, String phase, String error) {
        boolean editable = phase.equals("waiting");
        state = new Snapshot(phase, journal.optString("companionId"), error, running,
            editable, !editable && !phase.equals("complete") && !phase.equals("cancelled"));
    }
    private static void execute(Context context, LocalParticipant local) {
        JSONObject journal = new JSONObject(); boolean lock = false, completed = false;
        try {
            journal = new JSONObject(context.getSharedPreferences(PREFS, 0).getString(KEY, ""));
            String operationId = journal.getString("operationId"), companionId = journal.getString("companionId");
            if (journal.getInt("version") != 1) throw new IOException("COMPANION_MAINTENANCE_STORAGE");
            if (journal.getString("phase").equals("complete")) {
                local.release(); clear(context); completed = true; publish(journal, "complete", ""); return;
            }
            if (journal.getString("phase").equals("waiting")) {
                if (journal.has("hostPid") && !sameProcess(journal, "host")) {
                    // No offline command has run. An interrupted live preparation
                    // can be abandoned without retiring any companion data.
                    local.release(); clear(context); completed = true; publish(journal, "cancelled", ""); return;
                }
                publish(journal, "waiting", "");
                JSONObject receipt = request(journal, "POST", "prepare");
                while (true) {
                    if (cancelled) {
                        request(journal, "DELETE", null); local.release(); clear(context);
                        completed = true; publish(journal, "cancelled", ""); return;
                    }
                    if (!operationId.equals(receipt.optString("operationId")) || !companionId.equals(receipt.optString("companionId")))
                        throw new IOException("COMPANION_MAINTENANCE_IDENTITY");
                    if (!journal.has("admissionEpoch")) {
                        int pid = receipt.getInt("hostPid"); String birth = processBirth(pid);
                        if (birth == null || pid != journal.getInt("hostPid") || !birth.equals(journal.getString("hostBirth"))) throw new IOException("COMPANION_MAINTENANCE_HOST_CHANGED");
                        journal.put("hostPid", pid).put("hostBirth", birth).put("admissionEpoch", receipt.getString("admissionEpoch")); save(context, journal);
                    } else if (!sameProcess(journal, "host") || journal.getInt("hostPid") != receipt.getInt("hostPid")
                        || !journal.getString("admissionEpoch").equals(receipt.getString("admissionEpoch"))) throw new IOException("COMPANION_MAINTENANCE_HOST_CHANGED");
                    if (receipt.optString("phase").equals("failed")) throw new IOException(receipt.optString("error", "COMPANION_MAINTENANCE_FAILED"));
                    if (receipt.optString("phase").equals("ready")) break;
                    Thread.sleep(750); receipt = request(journal, "GET", null);
                }
                journal.put("admissionEpoch", receipt.getString("admissionEpoch"));
                int pid = receipt.getInt("hostPid"); String birth = processBirth(pid);
                if (birth == null) throw new IOException("COMPANION_MAINTENANCE_HOST_CHANGED");
                journal.put("hostPid", pid).put("hostBirth", birth);
                publish(journal, "preparing", "");
                local.prepare(operationId, companionId, journal.getString("admissionEpoch"));
                // Persist the exact process before requesting exit. A crash cannot
                // turn an unobserved exit into authorization to write offline.
                journal.put("phase", "shutdown"); save(context, journal);
            }
            if (journal.getString("phase").equals("shutdown")) {
                publish(journal, "preparing", "");
                if (sameProcess(journal, "host")) {
                    try { request(journal, "POST", "shutdown"); }
                    catch (IOException responseLost) { if (sameProcess(journal, "host")) throw responseLost; }
                }
                awaitGone(journal, "host");
                journal.put("phase", "offline"); save(context, journal);
            }
            if (!journal.getString("phase").equals("offline")) throw new IOException("COMPANION_MAINTENANCE_STORAGE");
            awaitGone(journal, "host"); awaitGone(journal, "worker");
            long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
            while (!(lock = RuntimeService.beginCompanionHistoryChange())) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) throw new IOException("COMPANION_MAINTENANCE_RUNTIME_BUSY");
                Thread.sleep(100);
            }
            publish(journal, "cleaning", "");
            JSONObject snapshot = local.prepare(operationId, companionId, journal.getString("admissionEpoch"));
            JSONObject result = offline(context, journal, "commit", snapshot, null);
            if (!result.optString("phase").equals("local-complete") && !result.optString("phase").equals("complete"))
                throw new IOException("COMPANION_MAINTENANCE_INCOMPLETE");
            if (!result.optString("phase").equals("complete")) {
                JSONObject androidReceipt = local.retire(result);
                result = offline(context, journal, "acknowledge", null, androidReceipt);
            }
            if (!result.optString("phase").equals("complete")) throw new IOException("COMPANION_MAINTENANCE_INCOMPLETE");
            // The local participant may now discard its original snapshot. A
            // restart must finish release, never build a different deletion plan.
            journal.put("phase", "complete"); save(context, journal);
            local.release(); clear(context); completed = true;
            publish(journal, "complete", "");
        } catch (Exception error) {
            String code = error.getMessage(); if (code == null || !code.matches("[A-Z_]+")) code = "COMPANION_MAINTENANCE_FAILED";
            // Preparation is reversible. Release a failed live window instead
            // of retaining its terminal error and blocking every future start.
            if (journal.optString("phase").equals("waiting")) {
                try {
                    if (sameProcess(journal, "host")) request(journal, "DELETE", null);
                    local.release(); clear(context); completed = true;
                } catch (Exception releaseFailed) { /* Keep the durable owner for an explicit retry. */ }
            }
            publish(journal, "failed", code);
            if (completed) state = new Snapshot("failed", journal.optString("companionId"), code, running, false, false);
        } finally {
            if (lock) RuntimeService.endHistoryChange();
            synchronized (NativeCompanionDeletion.class) {
                running = false;
                Snapshot before = state; state = new Snapshot(before.phase, before.companionId, before.error, false, before.canCancel, before.blocksEditing);
            }
            if (completed) { NativeRuntimeHost.companionMaintenance(false); NativeRuntimeHost.start(context); }
        }
    }
    private static JSONObject offline(Context context, JSONObject journal, String action, JSONObject snapshot, JSONObject receipt) throws Exception {
        return offline(context, journal, action, snapshot, receipt, value -> save(context, value));
    }
    interface JournalSaver { void save(JSONObject value) throws IOException; }
    static JSONObject offlineMemoryPurge(Context context, JSONObject journal, JournalSaver saver) throws Exception {
        return offline(context, journal, "memory-purge", null, null, saver);
    }
    private static JSONObject offline(Context context, JSONObject journal, String action, JSONObject snapshot, JSONObject receipt, JournalSaver saver) throws Exception {
        assertHeld(journal);
        // A pending operation blocks normal Host startup, so recovery must still
        // activate this APK's verified runtime before launching its offline code.
        File runtime = RuntimeInstaller.install(context);
        File requestedHome = new File(context.getFilesDir(), "dsh-home");
        File home = requestedHome.getCanonicalFile();
        assertHeld(journal);
        boolean memoryPurge = action.equals("memory-purge");
        File script = new File(runtime, "node_modules/@droyagent/mobile/companions/" +
            (memoryPurge ? "memory-session-purge-native-runner.mjs" : "maintenance-native-runner.mjs"));
        if (!script.isFile()) throw new IOException("COMPANION_MAINTENANCE_RUNTIME_MISSING");
        ProcessBuilder builder = new ProcessBuilder(context.getApplicationInfo().nativeLibraryDir + "/libdroy_node.so", "--expose-internals", script.getAbsolutePath());
        builder.directory(home);
        builder.environment().put("DSH_HOME", home.getAbsolutePath());
        builder.environment().put("HOME", home.getAbsolutePath());
        builder.environment().put("DROY_NATIVE_DIR", context.getApplicationInfo().nativeLibraryDir);
        builder.environment().put("LD_LIBRARY_PATH", context.getApplicationInfo().nativeLibraryDir);
        builder.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
        Process process = builder.start();
        ProtocolDeadline deadline = new ProtocolDeadline(process);
        // Stderr can contain source paths or provider data; never publish it as UI copy.
        Thread errors = new Thread(() -> { try (InputStream input = process.getErrorStream()) { byte[] buffer = new byte[4096]; while (input.read(buffer) != -1) {} } catch (IOException ignored) {} }, "droy-maintenance-errors"); errors.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            JSONObject hello = deadline.frame(reader);
            if (!hello.optString("kind").equals("started")) throw new IOException("COMPANION_MAINTENANCE_PROTOCOL");
            journal.put("workerPid", hello.getInt("pid")).put("workerBirth", processBirth(hello.getInt("pid"))); saver.save(journal);
            JSONObject command = new JSONObject().put("action", memoryPurge ? "commit" : action).put("home", home.getAbsolutePath())
                .put("operationId", journal.getString("operationId")).put("companionId", journal.getString("companionId"))
                .put("admissionEpoch", journal.getString("admissionEpoch"));
            if (memoryPurge) command.put("memorySpaceId", journal.getString("memorySpaceId")).put("compression", "none");
            if (!requestedHome.getAbsolutePath().equals(home.getAbsolutePath())) {
                // Android may expose /data/user/0 through a mount namespace
                // alias. Node verifies this root and every descendant before
                // interpreting stored paths; arbitrary cwd links stay denied.
                command.put("trustedRootAlias", new JSONObject()
                    .put("canonicalRoot", home.getAbsolutePath())
                    .put("aliasRoot", requestedHome.getAbsolutePath()));
            }
            if (snapshot != null) command.put("androidSnapshot", snapshot);
            if (receipt != null) command.put("androidReceipt", receipt);
            write(writer, command);
            JSONObject result = null;
            while (result == null) {
                JSONObject value = deadline.frame(reader);
                if (value.optString("kind").equals("authority")) {
                    assertHeld(journal); JSONObject request = value.getJSONObject("request");
                    if (!matchesAuthority(journal, home.getAbsolutePath(), request)) throw new IOException("COMPANION_MAINTENANCE_IDENTITY");
                    write(writer, new JSONObject().put("kind", "authority").put("nonce", value.getInt("nonce")).put("held", true));
                } else if (value.optString("kind").equals("result")) result = value.getJSONObject("receipt");
                else throw new IOException(value.optString("code", "COMPANION_MAINTENANCE_PROTOCOL"));
            }
            if (!process.waitFor(5000, TimeUnit.MILLISECONDS) || process.exitValue() != 0) throw new IOException("COMPANION_MAINTENANCE_EXIT_UNCONFIRMED");
            journal.remove("workerPid"); journal.remove("workerBirth"); saver.save(journal); return result;
        } finally {
            deadline.close();
            // EOF revokes the offline capability; preserve its PID until actual exit.
            process.getOutputStream().close();
            if (process.waitFor(5000, TimeUnit.MILLISECONDS)) errors.join(1000);
        }
    }
    /** A hung offline worker may be terminated: every destructive phase has a
     * durable recovery journal. Its PID remains recorded until exit is observed. */
    private static final class ProtocolDeadline implements AutoCloseable {
        private final Process child;
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        private final AtomicBoolean expired = new AtomicBoolean();
        ProtocolDeadline(Process child) { this.child = child; }
        JSONObject frame(BufferedReader reader) throws Exception {
            ScheduledFuture<?> alarm = timer.schedule(() -> { expired.set(true); child.destroyForcibly(); }, 180, TimeUnit.SECONDS);
            try {
                JSONObject value = NativeCompanionDeletion.frame(reader);
                if (expired.get()) throw new IOException("COMPANION_MAINTENANCE_TIMEOUT");
                return value;
            } catch (Exception failure) {
                if (expired.get()) throw new IOException("COMPANION_MAINTENANCE_TIMEOUT", failure);
                throw failure;
            } finally { alarm.cancel(false); }
        }
        @Override public void close() { timer.shutdownNow(); }
    }
    private static void assertHeld(JSONObject journal) throws Exception {
        if (!RuntimeService.companionMaintenanceHeld() || sameProcess(journal, "host")) throw new IOException("COMPANION_MAINTENANCE_AUTHORITY_LOST");
    }
    static boolean matchesAuthority(JSONObject journal, String home, JSONObject request) throws Exception {
        boolean memoryPurge = journal.optString("kind").equals("memory-purge");
        if (!request.optString("root").equals(home) || !request.optString("scope").equals(memoryPurge ? "companion-memory-purge" : "companion-deletion")) return false;
        if (memoryPurge && !journal.getString("memorySpaceId").equals(request.optString("memorySpaceId"))) return false;
        for (String key : new String[]{"operationId", "companionId", "admissionEpoch"})
            if (!journal.getString(key).equals(request.optString(key))) return false;
        return true;
    }
    static JSONObject request(JSONObject journal, String method, String action) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + journal.getInt("port") + PATH).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(30000); connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method); connection.setRequestProperty("Cookie", "droy_session=" + journal.getString("token"));
        try {
            if (!method.equals("GET")) {
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                JSONObject body = new JSONObject().put("operationId", journal.getString("operationId"));
                if (journal.has("companionId")) body.put("companionId", journal.getString("companionId"));
                if (journal.optString("kind").equals("memory-purge")) body.put("memorySpaceId", journal.getString("memorySpaceId"));
                if (action != null) body.put("action", action);
                try (OutputStream output = connection.getOutputStream()) { output.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int status = connection.getResponseCode();
            try (InputStream input = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
                JSONObject value = new JSONObject(new String(bounded(input, 65536), StandardCharsets.UTF_8));
                if (status >= 400) throw new IOException(value.optString("error", "COMPANION_MAINTENANCE_FAILED")); return value;
            }
        } finally { connection.disconnect(); }
    }
    static void awaitGone(JSONObject journal, String owner) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + 30000;
        while (sameProcess(journal, owner)) {
            if (android.os.SystemClock.elapsedRealtime() >= until) throw new IOException("COMPANION_MAINTENANCE_EXIT_UNCONFIRMED");
            Thread.sleep(100);
        }
    }
    static boolean sameProcess(JSONObject journal, String owner) throws Exception {
        if (!journal.has(owner + "Pid")) return false;
        String current = processBirth(journal.getInt(owner + "Pid"));
        return current != null && current.equals(journal.getString(owner + "Birth"));
    }
    static String processBirth(int pid) throws Exception {
        if (pid < 1) throw new IOException("COMPANION_MAINTENANCE_PROCESS_INVALID");
        try {
            String stat = new String(Files.readAllBytes(new File("/proc/" + pid + "/stat").toPath()), StandardCharsets.UTF_8);
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return fields[19];
        } catch (java.nio.file.NoSuchFileException gone) { return null; }
    }
    private static JSONObject frame(BufferedReader reader) throws Exception {
        StringBuilder line = new StringBuilder(); int next;
        while ((next = reader.read()) != -1 && next != '\n') { if (line.length() >= 262144) throw new IOException("COMPANION_MAINTENANCE_PROTOCOL"); line.append((char) next); }
        if (next == -1) throw new IOException("COMPANION_MAINTENANCE_EXIT_UNCONFIRMED"); return new JSONObject(line.toString());
    }
    private static void write(BufferedWriter writer, JSONObject value) throws IOException { writer.write(value.toString()); writer.newLine(); writer.flush(); }
    private static byte[] bounded(InputStream input, int maximum) throws IOException {
        if (input == null) throw new IOException("COMPANION_MAINTENANCE_PROTOCOL");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] chunk = new byte[4096]; int size;
        while ((size = input.read(chunk)) != -1) { if (bytes.size() + size > maximum) throw new IOException("COMPANION_MAINTENANCE_PROTOCOL"); bytes.write(chunk, 0, size); } return bytes.toByteArray();
    }
    private static void save(Context context, JSONObject value) throws IOException {
        if (!context.getSharedPreferences(PREFS, 0).edit().putString(KEY, value.toString()).commit()) throw new IOException("COMPANION_MAINTENANCE_STORAGE");
    }
    private static void clear(Context context) throws IOException {
        if (!context.getSharedPreferences(PREFS, 0).edit().remove(KEY).commit()) throw new IOException("COMPANION_MAINTENANCE_STORAGE");
    }
}
