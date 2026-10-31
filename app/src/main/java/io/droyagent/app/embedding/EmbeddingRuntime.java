package io.droyagent.app.embedding;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private inference endpoint. The product service remains the authority for memory ownership. */
public final class EmbeddingRuntime {
    public static final String MODEL = "intfloat/multilingual-e5-small";
    public static final int DIMENSIONS = 384;
    private static EmbeddingRuntime instance;
    private final Context context;
    private final EmbeddingQueue queue = new EmbeddingQueue();
    private NativeEmbeddingEngine engine;
    private JSONObject manifest;

    public static synchronized EmbeddingRuntime get(Context context) {
        if (instance == null) instance = new EmbeddingRuntime(context.getApplicationContext());
        return instance;
    }
    private EmbeddingRuntime(Context context) { this.context = context; }
    public static synchronized void cancelAllIfCreated() { if (instance != null) instance.queue.cancelAll(); }

    public JSONObject request(JSONObject input) throws Exception {
        String operation = input.getString("operation");
        if (operation.equals("status")) {
            fields(input, "operation");
            try {
                JSONObject model = modelManifest();
                return new JSONObject().put("ready", true).put("model", MODEL).put("dimensions", DIMENSIONS)
                    .put("fingerprint", model.getString("fingerprint"));
            } catch (java.io.FileNotFoundException absent) {
                return new JSONObject().put("ready", false).put("model", MODEL).put("dimensions", DIMENSIONS)
                    .put("reason", "EMBEDDING_MODEL_UNAVAILABLE");
            }
        }
        String space = identity(input.getString("memorySpaceId"));
        String requestId = identity(input.getString("requestId"));
        if (operation.equals("cancel")) {
            fields(input, "operation", "memorySpaceId", "requestId");
            return new JSONObject().put("cancelled", queue.cancel(space, requestId));
        }
        if (!operation.equals("embed")) throw new IllegalArgumentException("EMBEDDING_INVALID_OPERATION");
        fields(input, "operation", "requestId", "memorySpaceId", "epoch", "role", "texts", "deadlineAt");
        Object epochValue = input.get("epoch");
        if (!(epochValue instanceof Number number) || number.longValue() < 0 || number.doubleValue() != number.longValue())
            throw new IllegalArgumentException("EMBEDDING_INVALID_EPOCH");
        long epoch = number.longValue();
        String role = input.getString("role");
        if (!role.equals("query") && !role.equals("document")) throw new IllegalArgumentException("EMBEDDING_INVALID_ROLE");
        Object deadlineValue = input.get("deadlineAt");
        if (!(deadlineValue instanceof Number deadline) || deadline.doubleValue() != deadline.longValue())
            throw new IllegalArgumentException("EMBEDDING_DEADLINE");
        long remaining = deadline.longValue() - System.currentTimeMillis();
        JSONArray texts = input.getJSONArray("texts");
        if (texts.length() < 1 || texts.length() > 4) throw new IllegalArgumentException("EMBEDDING_INVALID_INPUT");
        String[] prepared = new String[texts.length()];
        for (int i = 0; i < texts.length(); ++i) {
            if (!(texts.get(i) instanceof String text) || text.isBlank()) throw new IllegalArgumentException("EMBEDDING_INVALID_INPUT");
            if (text.getBytes(StandardCharsets.UTF_8).length > 16384) throw new IllegalArgumentException("EMBEDDING_INVALID_INPUT");
            prepared[i] = (role.equals("query") ? "query: " : "passage: ") + text;
        }
        return queue.execute(space, requestId, remaining, () -> {
            ensureEngine();
            JSONArray vectors = new JSONArray();
            for (String text : prepared) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("EMBEDDING_CANCELLED");
                float[] vector = engine.embed(text);
                if (vector.length != DIMENSIONS) throw new IllegalStateException("EMBEDDING_OUTPUT_SHAPE");
                JSONArray values = new JSONArray();
                for (float value : vector) {
                    if (!Float.isFinite(value)) throw new IllegalStateException("EMBEDDING_OUTPUT_INVALID");
                    values.put((double) value);
                }
                vectors.put(values);
            }
            return new JSONObject().put("requestId", requestId).put("memorySpaceId", space).put("epoch", epoch)
                .put("model", MODEL).put("dimensions", DIMENSIONS).put("fingerprint", modelManifest().getString("fingerprint"))
                .put("vectors", vectors);
        });
    }

    private synchronized JSONObject modelManifest() throws Exception {
        if (manifest != null) return manifest;
        try (InputStream stream = context.getAssets().open("embedding/e5-small/manifest.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            JSONObject value = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (!MODEL.equals(value.getString("id")) || value.getInt("dimensions") != DIMENSIONS
                || value.getInt("maxTokens") != 512 || !value.getString("fingerprint").matches("[a-f0-9]{64}"))
                throw new IllegalStateException("EMBEDDING_MODEL_INVALID");
            manifest = value;
            return value;
        }
    }
    private void ensureEngine() throws Exception {
        if (engine != null) return;
        JSONObject model = modelManifest();
        File root = new File(context.getNoBackupFilesDir().getCanonicalFile(), "embedding-models");
        File directory = new File(root, model.getString("fingerprint"));
        if ((!directory.isDirectory() && !directory.mkdirs()) || !directory.getCanonicalFile().equals(directory.getAbsoluteFile()))
            throw new IllegalStateException("EMBEDDING_MODEL_DIRECTORY");
        JSONArray files = model.getJSONArray("files");
        if (files.length() != 2) throw new IllegalStateException("EMBEDDING_MODEL_INVALID");
        for (int i = 0; i < files.length(); ++i) {
            JSONObject spec = files.getJSONObject(i);
            String name = spec.getString("name");
            if (!Set.of("model.onnx", "sentencepiece.bpe.model").contains(name)) throw new IllegalStateException("EMBEDDING_MODEL_INVALID");
            File target = new File(directory, name);
            if (!target.getCanonicalFile().equals(target.getAbsoluteFile())) throw new IllegalStateException("EMBEDDING_MODEL_DIRECTORY");
            if (matches(target, spec)) continue;
            File temporary = new File(directory, name + ".part");
            if (!temporary.getCanonicalFile().equals(temporary.getAbsoluteFile())) throw new IllegalStateException("EMBEDDING_MODEL_DIRECTORY");
            try (InputStream source = context.getAssets().open("embedding/e5-small/" + name); FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = source.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException("EMBEDDING_CANCELLED");
                    out.write(buffer, 0, count);
                }
                out.getFD().sync();
            }
            if (!matches(temporary, spec)) throw new IllegalStateException("EMBEDDING_MODEL_CHECKSUM");
            if (!temporary.renameTo(target)) throw new IllegalStateException("EMBEDDING_MODEL_INSTALL_FAILED");
        }
        engine = new NativeEmbeddingEngine(directory.getAbsolutePath());
    }
    private static boolean matches(File file, JSONObject spec) throws Exception {
        if (!file.isFile() || file.length() != spec.getLong("bytes")) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = stream.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("EMBEDDING_CANCELLED");
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString().equals(spec.getString("sha256"));
    }
    private static String identity(String value) {
        if (!value.matches("[A-Za-z0-9_-]{1,160}")) throw new IllegalArgumentException("EMBEDDING_INVALID_ID");
        return value;
    }
    private static void fields(JSONObject input, String... allowed) {
        Set<String> keys = Set.of(allowed);
        for (Iterator<String> iterator = input.keys(); iterator.hasNext();) if (!keys.contains(iterator.next()))
            throw new IllegalArgumentException("EMBEDDING_INVALID_INPUT");
    }
}
