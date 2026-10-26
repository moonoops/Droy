package io.droyagent.app;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.lang.Process;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs only in an Android isolated process: no app files, credentials or permissions. */
public final class ComputeService extends Service {
    static final int RUN = 1, CANCEL = 2, RESULT = 3;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile Process process;
    private volatile String activeRequest;
    private volatile boolean cancelled;
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        Bundle data = message.getData();
        if (message.what == CANCEL) {
            if (data.getString("requestId", "").equals(activeRequest)) cancel();
        } else if (message.what == RUN) {
            if (activeRequest != null) { reply(message.replyTo, data.getString("requestId"), error("Compute service is busy")); return true; }
            activeRequest = data.getString("requestId"); cancelled = false;
            Messenger recipient = message.replyTo;
            worker.submit(() -> run(data, recipient));
        }
        return true;
    }));

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    private void run(Bundle data, Messenger recipient) {
        String id = data.getString("requestId");
        JSONObject response;
        try {
            if (android.os.Process.myUid() == getApplicationInfo().uid) throw new SecurityException("Compute service must use an isolated UID");
            String source = data.getString("runner", "");
            String request = data.getString("request", "");
            if (source.length() > 32768 || request.getBytes(StandardCharsets.UTF_8).length > 120000) throw new IOException("Compute request too large");
            int timeout = data.getInt("timeoutMs", 5000);
            if (timeout < 100 || timeout > 30000) throw new IOException("Invalid timeout");
            String nativeDir = getApplicationInfo().nativeLibraryDir;
            ProcessBuilder builder = new ProcessBuilder(nativeDir + "/libdroy_node.so", "--permission", "--max-old-space-size=64",
                "--no-addons", "--disable-proto=throw", "--input-type=module", "--eval", source);
            builder.directory(new File("/"));
            builder.environment().clear();
            builder.environment().put("LD_LIBRARY_PATH", nativeDir);
            builder.environment().put("TZ", "UTC");
            synchronized (this) {
                if (cancelled) throw new InterruptedException("Compute stopped");
                process = builder.start();
            }
            Process child = process;
            AtomicBoolean overflow = new AtomicBoolean();
            ByteArrayOutputStream output = new ByteArrayOutputStream(), errors = new ByteArrayOutputStream();
            Thread stdout = drain(child.getInputStream(), output, 262144, child, overflow);
            Thread stderr = drain(child.getErrorStream(), errors, 16384, child, overflow);
            try (OutputStream input = child.getOutputStream()) { input.write(request.getBytes(StandardCharsets.UTF_8)); }
            boolean ended = child.waitFor(timeout + 2000L, TimeUnit.MILLISECONDS);
            if (!ended) child.destroyForcibly();
            child.waitFor(2000, TimeUnit.MILLISECONDS);
            stdout.join(2000); stderr.join(2000);
            if (cancelled) response = new JSONObject().put("status", "stopped").put("error", "Compute stopped");
            else if (!ended) response = new JSONObject().put("status", "timed_out").put("error", "Compute time limit exceeded");
            else if (overflow.get()) response = error("Compute output limit exceeded");
            else if (child.exitValue() != 0) response = error("Compute process exited (" + child.exitValue() + "); memory limit or execution failure");
            else response = new JSONObject(output.toString("UTF-8"));
            response.put("isolation", new JSONObject().put("androidIsolatedProcess", android.os.Process.myUid() != getApplicationInfo().uid)
                .put("uid", android.os.Process.myUid()).put("heapLimitMiB", 64).put("networkPermission", checkSelfPermission("android.permission.INTERNET") == android.content.pm.PackageManager.PERMISSION_GRANTED));
        } catch (Exception error) {
            response = error(error instanceof InterruptedException ? "Compute stopped" : error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            Process child = process;
            if (child != null) child.destroyForcibly();
            process = null;
        }
        activeRequest = null;
        reply(recipient, id, response);
    }

    private static Thread drain(InputStream input, ByteArrayOutputStream output, int limit, Process child, AtomicBoolean overflow) {
        Thread thread = new Thread(() -> {
            try (InputStream stream = input) {
                byte[] buffer = new byte[4096]; int read;
                while ((read = stream.read(buffer)) != -1) {
                    if (output.size() + read > limit) { overflow.set(true); child.destroyForcibly(); return; }
                    output.write(buffer, 0, read);
                }
            } catch (IOException unreadable) { overflow.set(true); child.destroyForcibly(); }
        }, "compute-output");
        thread.start(); return thread;
    }

    private synchronized void cancel() { cancelled = true; if (process != null) process.destroyForcibly(); }
    private static JSONObject error(String message) {
        try { return new JSONObject().put("status", "failed").put("error", message).put("stdout", "").put("artifacts", new org.json.JSONArray()); }
        catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void reply(Messenger recipient, String id, JSONObject result) {
        if (recipient == null) return;
        Message message = Message.obtain(null, RESULT);
        Bundle bundle = new Bundle(); bundle.putString("requestId", id); bundle.putString("result", result.toString()); message.setData(bundle);
        try { recipient.send(message); } catch (RemoteException disconnected) { /* The task owner has stopped. */ }
    }
    @Override public void onDestroy() { cancel(); worker.shutdownNow(); super.onDestroy(); }
}
