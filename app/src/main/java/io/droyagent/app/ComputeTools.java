package io.droyagent.app;

import android.content.*;
import android.os.*;
import io.droyagent.androiduse.Cancellation;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Product-owned compute jobs. Callers must apply authorization and task cancellation. */
final class ComputeTools {
    private static final Set<String> ACTIVE = ConcurrentHashMap.newKeySet();
    private final Context context;
    private final File jobs;
    ComputeTools(Context context) {
        this.context = context.getApplicationContext();
        jobs = new File(context.getCacheDir(), "compute-jobs");
    }

    JSONObject execute(String action, JSONObject args, Cancellation cancellation) throws Exception {
        cancellation.check();
        if ("compute_run".equals(action)) return run(args, cancellation);
        if ("compute_jobs".equals(action)) return list(cancellation);
        if ("compute_artifact".equals(action)) return artifact(args, cancellation);
        throw new IllegalArgumentException("Unknown compute action");
    }

    private JSONObject run(JSONObject args, Cancellation cancellation) throws Exception {
        if (!args.has("code") || !(args.get("code") instanceof String)) throw new IllegalArgumentException("code must be a string");
        String code = args.getString("code");
        Object input = args.has("input") ? args.get("input") : JSONObject.NULL;
        if (bytes(code) > 16384 || bytes(jsonValue(input)) > 16384) throw new IllegalArgumentException("Code and input are each limited to 16384 bytes");
        Object requestedTimeout = args.opt("timeoutMs");
        if (requestedTimeout != null && (!(requestedTimeout instanceof Number) || ((Number) requestedTimeout).doubleValue() != ((Number) requestedTimeout).intValue())) throw new IllegalArgumentException("timeoutMs must be an integer");
        int timeout = args.optInt("timeoutMs", 5000);
        if (timeout < 100 || timeout > 30000) throw new IllegalArgumentException("timeoutMs must be 100..30000");
        String id = UUID.randomUUID().toString();
        File directory;
        synchronized (ComputeTools.class) {
            if (!jobs.isDirectory() && !jobs.mkdirs()) throw new IOException("Cannot create compute jobs");
            prune();
            directory = new File(jobs, id);
            if (!directory.mkdir()) throw new IOException("Cannot create job workspace");
            ACTIVE.add(id);
        }
        long started = System.currentTimeMillis();
        JSONObject metadata = new JSONObject().put("jobId", id).put("startedAt", started).put("status", "running");
        try {
            writeMetadata(directory, metadata);
            File runtime = RuntimeInstaller.install(context);
            byte[] runnerBytes = Files.readAllBytes(new File(runtime, "mobile/compute-runner.mjs").toPath());
            if (runnerBytes.length > 32768) throw new IOException("Invalid compute runner");
            String runner = new String(runnerBytes, StandardCharsets.UTF_8);
            JSONObject request = new JSONObject().put("code", code).put("input", input).put("timeoutMs", timeout);
            JSONObject result = invoke(id, runner, request, timeout, cancellation);
            cancellation.check();
            if (!Arrays.asList("completed", "failed", "timed_out", "stopped").contains(result.optString("status"))) throw new IOException("Invalid compute result status");
            if (bytes(result.optString("stdout", "")) > 32768 || bytes(jsonValue(result.opt("result"))) > 32768) throw new IOException("Compute result limit exceeded");
            if ("completed".equals(result.optString("status")) && (result.optJSONObject("isolation") == null || !result.getJSONObject("isolation").optBoolean("androidIsolatedProcess"))) throw new IOException("Compute result was not isolated");
            JSONArray artifacts = result.optJSONArray("artifacts"), published = new JSONArray();
            if (artifacts == null) artifacts = new JSONArray();
            if (artifacts.length() > 8) throw new IOException("Too many compute artifacts");
            int total = 0;
            for (int index = 0; index < artifacts.length(); index++) {
                cancellation.check();
                JSONObject item = artifacts.getJSONObject(index);
                String name = validName(item.getString("name")), text = item.getString("text"), mime = validMime(item.getString("mime"));
                int size = bytes(text);
                total += size;
                if (size > 65536 || total > 131072) throw new IOException("Compute artifact limit exceeded");
                File target = new File(directory, name);
                Files.write(target.toPath(), text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
                published.put(new JSONObject().put("name", name).put("mime", mime).put("bytes", size));
            }
            result.put("artifacts", published).put("jobId", id);
            metadata.put("status", result.getString("status")).put("finishedAt", System.currentTimeMillis()).put("artifacts", published);
            if (result.has("error")) metadata.put("error", result.optString("error").substring(0, Math.min(1000, result.optString("error").length())));
            writeMetadata(directory, metadata);
            return result;
        } catch (Exception error) {
            // Never retain partially published output after cancellation or failure.
            RuntimeInstaller.deleteTree(directory);
            if (!directory.mkdir()) throw new IOException("Cannot record failed compute job", error);
            metadata.put("status", error instanceof InterruptedException ? "stopped" : "failed").put("finishedAt", System.currentTimeMillis());
            writeMetadata(directory, metadata);
            throw error;
        } finally {
            ACTIVE.remove(id);
        }
    }

    private JSONObject invoke(String id, String runner, JSONObject request, int timeout, Cancellation cancellation) throws Exception {
        CompletableFuture<JSONObject> response = new CompletableFuture<>();
        CompletableFuture<Messenger> connected = new CompletableFuture<>();
        Handler handler = new Handler(Looper.getMainLooper(), message -> {
            if (message.what != ComputeService.RESULT || !id.equals(message.getData().getString("requestId"))) return true;
            try { response.complete(new JSONObject(message.getData().getString("result", "{}"))); }
            catch (Exception error) { response.completeExceptionally(error); }
            return true;
        });
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) { connected.complete(new Messenger(binder)); }
            private void fail(String message) {
                IOException error = new IOException(message);
                connected.completeExceptionally(error);
                response.completeExceptionally(error);
            }
            @Override public void onServiceDisconnected(ComponentName name) { fail("Compute isolated process disconnected"); }
            @Override public void onBindingDied(ComponentName name) { fail("Compute service binding died"); }
            @Override public void onNullBinding(ComponentName name) { fail("Compute service unavailable"); }
        };
        // A prior isolated service can still be tearing down after unbind. Give
        // every job its own instance so the next bind cannot reuse that service.
        String instance = "compute_" + id.replace("-", "");
        if (!context.bindIsolatedService(new Intent(context, ComputeService.class), Context.BIND_AUTO_CREATE,
            instance, context.getMainExecutor(), connection)) throw new IOException("Cannot bind compute isolated process");
        Messenger remote = null;
        try {
            long deadline = android.os.SystemClock.elapsedRealtime() + timeout + 10000L;
            remote = await(connected, deadline, cancellation, "service binding");
            Message message = Message.obtain(null, ComputeService.RUN);
            Bundle data = new Bundle(); data.putString("requestId", id); data.putString("runner", runner); data.putString("request", request.toString()); data.putInt("timeoutMs", timeout);
            message.setData(data); message.replyTo = new Messenger(handler); remote.send(message);
            return await(response, deadline, cancellation, "execution");
        } finally {
            if (remote != null) {
                Message cancel = Message.obtain(null, ComputeService.CANCEL); Bundle data = new Bundle(); data.putString("requestId", id); cancel.setData(data);
                try { remote.send(cancel); } catch (RemoteException disconnected) { /* Isolated worker has already exited. */ }
            }
            context.unbindService(connection);
            handler.removeCallbacksAndMessages(null);
        }
    }

    private static <T> T await(CompletableFuture<T> future, long deadline, Cancellation cancellation, String phase) throws Exception {
        for (;;) {
            cancellation.check();
            if (android.os.SystemClock.elapsedRealtime() >= deadline) throw new IOException("Compute " + phase + " did not respond before deadline");
            try { return future.get(50, TimeUnit.MILLISECONDS); }
            catch (TimeoutException waiting) { /* Poll authorization and stop while Binder is in flight. */ }
            catch (ExecutionException error) { throw new IOException("Compute " + phase + " failed", error.getCause()); }
        }
    }

    private JSONObject list(Cancellation cancellation) throws Exception {
        JSONArray items = new JSONArray();
        File[] directories = jobs.listFiles(File::isDirectory);
        if (directories == null) directories = new File[0];
        Arrays.sort(directories, Comparator.comparingLong(File::lastModified).reversed());
        for (File directory : directories) {
            cancellation.check();
            if (!validJobId(directory.getName())) continue;
            File metadata = new File(directory, "job.json");
            if (metadata.isFile()) {
                JSONObject item = new JSONObject(readString(metadata));
                if ("running".equals(item.optString("status")) && !ACTIVE.contains(directory.getName())) {
                    item.put("status", "interrupted"); writeMetadata(directory, item);
                }
                items.put(item);
            }
        }
        return new JSONObject().put("jobs", items).put("retention", "last 20 jobs in app cache; system may clear cached artifacts");
    }

    private JSONObject artifact(JSONObject args, Cancellation cancellation) throws Exception {
        String id = args.getString("jobId"), name = validName(args.getString("name"));
        if (!validJobId(id)) throw new IllegalArgumentException("Invalid jobId");
        File directory = new File(jobs, id), metadataFile = new File(directory, "job.json");
        if (!metadataFile.isFile()) throw new FileNotFoundException("Compute job expired or not found");
        JSONObject metadata = new JSONObject(readString(metadataFile));
        JSONArray artifacts = metadata.optJSONArray("artifacts");
        for (int index = 0; artifacts != null && index < artifacts.length(); index++) {
            JSONObject item = artifacts.getJSONObject(index);
            if (!name.equals(item.getString("name"))) continue;
            cancellation.check();
            File file = new File(directory, name);
            if (file.length() > 65536) throw new IOException("Invalid artifact size");
            return new JSONObject().put("jobId", id).put("name", name).put("mime", item.getString("mime")).put("text", readString(file));
        }
        throw new FileNotFoundException("Artifact not found in job");
    }

    private void prune() throws Exception {
        File[] directories = jobs.listFiles(File::isDirectory);
        if (directories == null) return;
        Arrays.sort(directories, Comparator.comparingLong(File::lastModified));
        int retained = directories.length;
        for (File directory : directories) {
            if (retained < 20) break;
            if (ACTIVE.contains(directory.getName())) continue;
            RuntimeInstaller.deleteTree(directory); retained--;
        }
    }
    static void clearJobs(Context context) throws IOException {
        synchronized (ComputeTools.class) {
            if (!ACTIVE.isEmpty()) throw new IllegalStateException("Stop active compute jobs before deleting their outputs");
            RuntimeInstaller.deleteTree(new File(context.getCacheDir(), "compute-jobs"));
        }
    }
    private static void writeMetadata(File directory, JSONObject metadata) throws IOException {
        Path temporary = new File(directory, ".job.tmp").toPath();
        Files.write(temporary, metadata.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(temporary, new File(directory, "job.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static String readString(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static String jsonValue(Object value) {
        String array = new JSONArray().put(value == null ? JSONObject.NULL : value).toString();
        return array.substring(1, array.length() - 1);
    }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    private static boolean validJobId(String id) { return id.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"); }
    private static String validName(String name) {
        if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}") || name.contains("..") || name.equals("job.json")) throw new IllegalArgumentException("Invalid artifact name");
        return name;
    }
    private static String validMime(String mime) {
        if (!Arrays.asList("text/plain", "text/csv", "application/json", "text/markdown").contains(mime)) throw new IllegalArgumentException("Invalid artifact MIME type");
        return mime;
    }
}
