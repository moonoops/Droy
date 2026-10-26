package io.droyagent.app;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import org.json.*;
import java.io.*;
import java.lang.Process;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Debug-only mechanism probe in the normal app domain; never handles user data. */
public final class MemoryIsolationProbeService extends Service {
    static final int RUN = 1, RESULT = 2;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile Process child;
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (message.what != RUN || message.replyTo == null) return true;
        if (!busy.compareAndSet(false, true)) return true;
        Messenger recipient = message.replyTo;
        String probe = message.getData().getString("probe", "broker");
        worker.execute(() -> execute(recipient, probe));
        return true;
    }));

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    private void execute(Messenger recipient, String probe) {
        JSONObject report = new JSONObject();
        Path directory = null;
        try {
            report.put("serviceUid", android.os.Process.myUid());
            report.put("applicationUid", getApplicationInfo().uid);
            report.put("servicePid", android.os.Process.myPid());
            report.put("serviceDomain", new String(Files.readAllBytes(Path.of("/proc/self/attr/current")), StandardCharsets.UTF_8).trim());
            String processStatus = new String(Files.readAllBytes(Path.of("/proc/self/status")), StandardCharsets.UTF_8);
            for (String line : processStatus.split("\n")) {
                if (line.startsWith("Seccomp:") || line.startsWith("NoNewPrivs:")) {
                    String[] parts = line.split(":", 2);
                    report.put(parts[0], Integer.parseInt(parts[1].trim()));
                }
            }
            report.put("syntheticDataOnly", true);
            report.put("productionSandboxVerified", false);
            directory = Files.createTempDirectory(getCacheDir().toPath(), "memory-service-probe-");
            String executable;
            if ("broker".equals(probe)) executable = "libdroy_memory_broker_probe.so";
            else if ("primitives".equals(probe)) executable = "libdroy_memory_primitives_probe.so";
            else if ("fixed-root".equals(probe)) executable = "libdroy_memory_fixed_root_probe.so";
            else throw new IllegalArgumentException("Unknown synthetic probe");
            report.put("probe", probe);
            if ("fixed-root".equals(probe)) {
                report.put("limits", new JSONArray()
                    .put("Directory object grants survive rename; this is not current-path-beneath authorization")
                    .put("Synthetic contract requires trusted writers not to import private objects or private hardlinks")
                    .put("A writer that moves a granted directory and then inserts private content can expose it; the read-only worker cannot perform those mutations")
                    .put("Only prebuilt synthetic regular-file fixtures are authorized; symlinks, writes and directory changes are unsupported")
                    .put("Static mechanism experiment only; no general Node/Bash/PTC or network-isolation acceptance"));
            }
            File binary = new File(getApplicationInfo().nativeLibraryDir, executable);
            if (!binary.isFile()) throw new IOException("Build with tools/memory_service_probe.gradle");
            ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath());
            builder.directory(directory.toFile());
            builder.environment().clear();
            child = builder.start();
            Process process = child;
            ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
            AtomicBoolean overflow = new AtomicBoolean();
            Thread stdout = drain(process.getInputStream(), out, process, overflow);
            Thread stderr = drain(process.getErrorStream(), err, process, overflow);
            process.getOutputStream().close();
            boolean completed = process.waitFor(25, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            process.waitFor(3, TimeUnit.SECONDS);
            stdout.join(3000); stderr.join(3000);
            report.put("completed", completed).put("overflow", overflow.get());
            report.put("exitCode", process.isAlive() ? -999 : process.exitValue());
            report.put("stderr", err.toString(StandardCharsets.UTF_8.name()));
            JSONArray observations = new JSONArray();
            for (String line : out.toString(StandardCharsets.UTF_8.name()).split("\n")) {
                if (!line.isBlank()) observations.put(new JSONObject(line));
            }
            report.put("observations", observations);
        } catch (Exception failure) {
            try { report.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage()); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        } finally {
            Process process = child;
            if (process != null && process.isAlive()) process.destroyForcibly();
            child = null;
            if (directory != null) {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(path);
                    report.put("temporaryDirectoryRemoved", true);
                } catch (Exception cleanup) {
                    try { report.put("temporaryDirectoryRemoved", false); }
                    catch (JSONException impossible) { throw new IllegalStateException(impossible); }
                }
            }
            busy.set(false);
        }
        Message reply = Message.obtain(null, RESULT);
        Bundle result = new Bundle(); result.putString("result", report.toString()); reply.setData(result);
        try { recipient.send(reply); } catch (RemoteException disconnected) { /* The test owner stopped. */ }
    }

    private static Thread drain(InputStream input, ByteArrayOutputStream output, Process process, AtomicBoolean overflow) {
        Thread thread = new Thread(() -> {
            try (InputStream stream = input) {
                byte[] buffer = new byte[2048]; int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (output.size() + count > 65536) { overflow.set(true); process.destroyForcibly(); return; }
                    output.write(buffer, 0, count);
                }
            } catch (IOException error) { overflow.set(true); process.destroyForcibly(); }
        }, "memory-probe-output");
        thread.start(); return thread;
    }

    @Override public void onDestroy() {
        Process process = child;
        if (process != null) process.destroyForcibly();
        worker.shutdownNow();
        super.onDestroy();
    }
}
