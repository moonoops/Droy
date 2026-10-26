package io.droyagent.app;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** User-requested TTS downloads; only a complete, verified set can be used for speech. */
final class TtsModels {
    private static final class Model {
        final JSONObject specification;
        final File directory;
        final String stamp;
        final long total;
        volatile String state = "not_downloaded", error;
        volatile long completed;
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        Thread worker;

        Model(File root, JSONObject specification) throws Exception {
            this.specification = specification;
            directory = new File(root, specification.getString("directory"));
            stamp = hex(MessageDigest.getInstance("SHA-256").digest(specification.toString().getBytes(StandardCharsets.UTF_8)));
            long bytes = 0;
            JSONArray files = specification.getJSONArray("files");
            for (int index = 0; index < files.length(); index++) bytes += files.getJSONObject(index).getLong("bytes");
            total = bytes;
            if (installed()) { state = "ready"; completed = total; }
        }

        boolean installed() throws Exception {
            File marker = new File(directory, ".verified");
            if (!marker.isFile() || !stamp.equals(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8))) return false;
            JSONArray files = specification.getJSONArray("files");
            for (int index = 0; index < files.length(); index++) {
                JSONObject asset = files.getJSONObject(index);
                File file = new File(directory, asset.getString("name"));
                if (!file.isFile() || file.length() != asset.getLong("bytes")) return false;
            }
            return true;
        }

        JSONObject status() throws Exception {
            if (state.equals("ready") && !installed()) { state = "not_downloaded"; completed = 0; }
            return new JSONObject().put("provider", specification.getString("provider"))
                .put("name", specification.getString("name")).put("state", state).put("ready", state.equals("ready"))
                .put("downloadedBytes", completed).put("totalBytes", total).put("error", error == null ? JSONObject.NULL : error);
        }
    }

    private final Map<String, Model> models = new LinkedHashMap<>();

    TtsModels(Context context) throws Exception {
        this(new File(context.getFilesDir(), "audio-models/tts"), manifest(context));
    }

    TtsModels(File root, byte[] manifest) throws Exception {
        JSONArray entries = new JSONObject(new String(manifest, StandardCharsets.UTF_8)).getJSONArray("models");
        for (int index = 0; index < entries.length(); index++) {
            JSONObject specification = entries.getJSONObject(index);
            validate(specification);
            String provider = specification.getString("provider");
            if (models.put(provider, new Model(root, specification)) != null) throw new IOException(io.droyagent.app.NativeLocale.text("朗读资源清单重复", "Duplicate speech resource manifest"));
        }
    }

    synchronized JSONObject status() throws Exception {
        JSONArray rows = new JSONArray();
        for (Model model : models.values()) rows.put(model.status());
        return new JSONObject().put("models", rows);
    }

    synchronized File requireReady(String provider) throws Exception {
        Model model = model(provider);
        if (!model.state.equals("ready") || !model.installed()) {
            if (model.state.equals("ready")) { model.state = "not_downloaded"; model.completed = 0; }
            throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请先在语音设置中下载完整的 ", "Download the complete ") + model.specification.getString("name") + io.droyagent.app.NativeLocale.text(" 资源", " resources in voice settings first"));
        }
        return model.directory;
    }

    synchronized JSONObject download(String provider) throws Exception {
        Model model = model(provider);
        if (model.state.equals("ready") && !model.installed()) { model.state = "not_downloaded"; model.completed = 0; }
        if (model.worker != null || model.state.equals("ready")) return status();
        model.cancelled = false; model.error = null; model.completed = 0; model.state = "downloading";
        model.worker = new Thread(() -> download(model), "droy-tts-download-" + provider);
        model.worker.setDaemon(true); model.worker.start();
        return status();
    }

    synchronized JSONObject cancel(String provider) throws Exception {
        Model model = model(provider);
        if (model.worker != null) {
            model.cancelled = true;
            HttpURLConnection connection = model.connection;
            if (connection != null) connection.disconnect();
            model.worker.interrupt();
        }
        return status();
    }

    synchronized void cancelAll() {
        for (Model model : models.values()) if (model.worker != null) {
            model.cancelled = true;
            HttpURLConnection connection = model.connection;
            if (connection != null) connection.disconnect();
            model.worker.interrupt();
        }
    }

    private Model model(String provider) {
        Model model = models.get(provider);
        if (model == null) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("所选朗读资源不可用", "The selected speech resources are unavailable"));
        return model;
    }

    private void download(Model model) {
        try {
            Files.createDirectories(model.directory.toPath());
            Files.deleteIfExists(new File(model.directory, ".verified").toPath());
            JSONArray files = model.specification.getJSONArray("files");
            for (int index = 0; index < files.length(); index++) {
                check(model);
                JSONObject asset = files.getJSONObject(index);
                File target = new File(model.directory, asset.getString("name"));
                long size = asset.getLong("bytes"), before = model.completed;
                model.state = "verifying";
                if (matches(target, size, asset.getString("sha256"), () -> model.cancelled)) {
                    model.completed += size; continue;
                }
                model.state = "downloading";
                HttpURLConnection connection = open(model, new URL(asset.getString("url")));
                try {
                    long length = connection.getContentLengthLong();
                    if (length >= 0 && length != size) throw new IOException(io.droyagent.app.NativeLocale.text("朗读资源大小与清单不符", "Speech resource size does not match the manifest"));
                    try (InputStream input = connection.getInputStream()) {
                        publish(input, target, size, asset.getString("sha256"), () -> model.cancelled,
                            count -> model.completed = before + count);
                    }
                } finally { connection.disconnect(); model.connection = null; }
            }
            check(model); model.state = "verifying";
            File marker = new File(model.directory, ".verified");
            File partial = new File(model.directory, ".verified.part");
            try {
                Files.write(partial.toPath(), model.stamp.getBytes(StandardCharsets.UTF_8));
                synchronized (this) {
                    check(model);
                    Files.move(partial.toPath(), marker.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    model.completed = model.total; model.state = "ready";
                }
            } finally { Files.deleteIfExists(partial.toPath()); }
        } catch (Exception failure) {
            model.state = model.cancelled ? "not_downloaded" : "failed";
            model.error = model.cancelled ? null : io.droyagent.app.NativeLocale.text("下载失败：", "Download failed: ") + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        } finally {
            synchronized (this) { model.connection = null; model.worker = null; }
        }
    }

    private static HttpURLConnection open(Model model, URL url) throws Exception {
        for (int redirects = 0; redirects <= 5; redirects++) {
            check(model);
            if (!url.getProtocol().equals("https")) throw new IOException(io.droyagent.app.NativeLocale.text("朗读资源必须通过 HTTPS 下载", "Speech resources must be downloaded over HTTPS"));
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            model.connection = connection;
            if (model.cancelled) { connection.disconnect(); throw new CancellationException(io.droyagent.app.NativeLocale.text("下载已取消", "Download cancelled")); }
            connection.setConnectTimeout(20000); connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "Droy-TTS/1");
            int status;
            try { status = connection.getResponseCode(); }
            catch (Exception failure) { connection.disconnect(); throw failure; }
            if (status == 200) return connection;
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if ((status == 301 || status == 302 || status == 303 || status == 307 || status == 308) && location != null) {
                url = new URL(url, location); continue;
            }
            throw new IOException(io.droyagent.app.NativeLocale.text("资源服务器返回 HTTP ", "Resource server returned HTTP ") + status);
        }
        throw new IOException(io.droyagent.app.NativeLocale.text("资源服务器重定向过多", "Too many resource server redirects"));
    }

    static void publish(InputStream input, File target, long size, String hash,
            BooleanSupplier cancelled, LongConsumer progress) throws Exception {
        File partial = new File(target.getParentFile(), target.getName() + ".part");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long written = 0;
            try (FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[131072]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (cancelled.getAsBoolean()) throw new CancellationException(io.droyagent.app.NativeLocale.text("下载已取消", "Download cancelled"));
                    written += count;
                    if (written > size) throw new IOException(io.droyagent.app.NativeLocale.text("朗读资源超过预期大小", "Speech resources exceed the expected size"));
                    output.write(buffer, 0, count); digest.update(buffer, 0, count); progress.accept(written);
                }
                output.getFD().sync();
            }
            if (cancelled.getAsBoolean()) throw new CancellationException(io.droyagent.app.NativeLocale.text("下载已取消", "Download cancelled"));
            if (written != size || !hex(digest.digest()).equals(hash)) throw new IOException(io.droyagent.app.NativeLocale.text("朗读资源校验失败", "Speech resource verification failed"));
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(partial.toPath()); }
    }

    private static boolean matches(File file, long size, String hash, BooleanSupplier cancelled) throws Exception {
        if (!file.isFile() || file.length() != size) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[131072]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (cancelled.getAsBoolean()) throw new CancellationException(io.droyagent.app.NativeLocale.text("下载已取消", "Download cancelled"));
                digest.update(buffer, 0, count);
            }
        }
        return hex(digest.digest()).equals(hash);
    }

    private static void validate(JSONObject specification) throws Exception {
        if (!specification.getString("directory").matches("[a-z0-9_-]+")) throw new IOException(io.droyagent.app.NativeLocale.text("朗读资源目录无效", "Invalid speech resource directory"));
        JSONArray files = specification.getJSONArray("files");
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int index = 0; index < files.length(); index++) {
            JSONObject asset = files.getJSONObject(index); String name = asset.getString("name");
            if (!name.matches("[A-Za-z0-9_.-]+") || name.equals(".") || name.equals("..") || !names.add(name)
                || asset.getLong("bytes") <= 0 || !asset.getString("sha256").matches("[a-f0-9]{64}")
                || !new URL(asset.getString("url")).getProtocol().equals("https")) throw new IOException(io.droyagent.app.NativeLocale.text("朗读资源清单无效", "Invalid speech resource manifest"));
        }
    }

    private static void check(Model model) {
        if (model.cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException(io.droyagent.app.NativeLocale.text("下载已取消", "Download cancelled"));
    }

    private static byte[] manifest(Context context) throws IOException {
        try (InputStream input = context.getAssets().open("tts-models.json"); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(Character.forDigit((value >> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return result.toString();
    }
}
