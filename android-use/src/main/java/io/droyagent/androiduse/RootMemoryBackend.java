package io.droyagent.androiduse;

import android.content.Context;
import android.os.Process;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** One fixed, APK-packaged root invocation for each bounded memory operation. */
public final class RootMemoryBackend implements MemoryBackend {
    private static final int MAX_FRAME = 16 * 1024 * 1024;
    private final File executable;

    public RootMemoryBackend(Context context) {
        executable = new File(context.getApplicationInfo().nativeLibraryDir, "libdroy_memory.so");
    }

    @Override public JSONObject capabilities() throws Exception {
        boolean packaged = executable.isFile() && executable.canExecute();
        return new JSONObject().put("backend", "proc_mem_root").put("protocolVersion", 1)
                .put("packaged", packaged).put("supported", packaged && Process.is64Bit())
                .put("requiresRoot", true).put("targetBits", 64).put("maxCandidates", 20000)
                .put("maxWriteBytes", 8).put("targetAccess", "checked_on_open")
                .put("atomicWrite", false).put("persistentHelper", false);
    }

    @Override public JSONObject request(JSONObject request, Cancellation cancellation, BeforeWrite beforeWrite) throws Exception {
        cancellation.usingRoot(true);
        if (!executable.isFile() || !executable.canExecute())
            throw new ActionException("memory_backend_missing", "The packaged memory executor is unavailable");
        long budget = Math.min(15000, cancellation.remainingMillis(15000));
        JSONObject input = new JSONObject(request.toString()).put("protocol", 1)
                .put("budgetMs", budget).put("hostUid", Process.myUid());
        byte[] encoded = input.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_FRAME) throw new ActionException("memory_request_limit", "Memory request exceeds the native frame limit");
        // Only the installed path is shell-quoted; no request data becomes shell source.
        String fixed = "exec '" + executable.getAbsolutePath().replace("'", "'\\''") + "'";
        java.lang.Process process;
        try { process = new ProcessBuilder("su", "-c", fixed).start(); }
        catch (IOException unavailable) { throw new ActionException("root_unavailable", "The memory operation requires an available, authorized Root channel"); }
        return exchange(process, encoded, input.optString("op"), budget, cancellation, beforeWrite);
    }

    static JSONObject exchange(java.lang.Process process, byte[] encoded, String operation, long budget,
                               Cancellation cancellation, BeforeWrite beforeWrite) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget);
        ArrayBlockingQueue<Object> frames = new ArrayBlockingQueue<>(3);
        Thread reader = new Thread(() -> {
            try {
                DataInputStream in = new DataInputStream(process.getInputStream());
                for (int count = 0; count < 2; count++) {
                    int size = in.readInt();
                    if (size < 2 || size > MAX_FRAME) throw new IOException("Invalid native memory frame size");
                    byte[] bytes = new byte[size];
                    in.readFully(bytes);
                    JSONObject frame = new JSONObject(StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes)).toString());
                    frames.put(frame);
                    if (!frame.optString("event").equals("prepared")) return;
                }
                frames.offer(new IOException("Unexpected native memory frame sequence"));
            } catch (Exception failure) { frames.offer(failure); }
        }, "droy-memory-output");
        reader.setDaemon(true);
        reader.start();
        Thread stderr = new Thread(() -> {
            try (InputStream in = process.getErrorStream()) {
                byte[] discard = new byte[1024]; int total = 0, count;
                while ((count = in.read(discard)) != -1) {
                    total += count;
                    if (total > 8192) { process.destroy(); return; }
                }
            } catch (IOException ignored) { /* Cleanup does not replace the operation receipt. */ }
        }, "droy-memory-stderr");
        stderr.setDaemon(true);
        stderr.start();
        try {
            DataOutputStream out = new DataOutputStream(process.getOutputStream());
            // su may be waiting for system approval. Do not block cancellation on a full pipe.
            Thread writer = new Thread(() -> {
                try { out.writeInt(encoded.length); out.write(encoded); out.flush(); }
                catch (IOException failure) { frames.offer(failure); }
            }, "droy-memory-input");
            writer.setDaemon(true);
            writer.start();
            boolean prepared = false;
            for (;;) {
                cancellation.check();
                if (System.nanoTime() >= deadline)
                    throw new ActionException("memory_timeout", "Memory executor did not return within its budget; inspect any pending write receipt");
                Object item = frames.poll(25, TimeUnit.MILLISECONDS);
                if (item == null) continue;
                if (item instanceof Exception)
                    throw new ActionException("memory_protocol_error", "Memory executor disconnected without a complete receipt");
                JSONObject frame = (JSONObject) item;
                if (frame.optString("event").equals("prepared")) {
                    if (prepared || !"write".equals(operation) || beforeWrite == null)
                        throw new ActionException("memory_protocol_error", "Unexpected memory write preparation");
                    prepared = true;
                    beforeWrite.prepared(frame.getJSONObject("helper"));
                    cancellation.check();
                    byte[] go = "{\"go\":true}".getBytes(StandardCharsets.UTF_8);
                    out.writeInt(go.length); out.write(go); out.flush();
                    continue;
                }
                if (!frame.optBoolean("ok"))
                    throw new ActionException(frame.optString("code", "memory_native_failed"),
                            frame.optString("message", "The memory executor rejected the operation"));
                JSONObject result = frame.getJSONObject("result");
                while (!process.waitFor(25, TimeUnit.MILLISECONDS)) {
                    cancellation.check();
                    if (System.nanoTime() >= deadline)
                        throw new ActionException("memory_timeout", "Memory executor exit was not confirmed; inspect the write receipt");
                }
                if (process.exitValue() != 0)
                    throw new ActionException("memory_native_failed", "Memory executor exited without a successful completion");
                return result.put("helperExited", true);
            }
        } finally {
            // Stop the su wrapper before closing its buffered pipe: a blocked input
            // writer may otherwise hold the stream lock indefinitely during approval.
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
            // EOF is the independent root helper's cooperative cancellation signal.
            close(process.getOutputStream());
            close(process.getInputStream()); close(process.getErrorStream());
        }
    }

    private static void close(java.io.Closeable stream) {
        try { stream.close(); } catch (IOException ignored) { /* Preserve the actual receipt or error. */ }
    }
}
