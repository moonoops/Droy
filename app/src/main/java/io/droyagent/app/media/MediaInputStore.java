package io.droyagent.app.media;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Process;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Durable import handoff. No engine types or conversation storage are owned here. */
public final class MediaInputStore {
    private static final Object LOCK = new Object();
    private static final long MAX_PENDING_BYTES = 512L * 1024 * 1024;
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");
    private final Context context;
    private final File root;

    public MediaInputStore(Context context) {
        this.context = context.getApplicationContext();
        root = new File(context.getFilesDir(), "media-input/v1");
    }

    public File root() { return root; }

    public JSONObject importUris(String source, String target, List<Uri> uris, String text, boolean imagesOnly, int remaining) throws Exception {
        return importUrisForOperation(UUID.randomUUID().toString(), source, target, uris, text, imagesOnly, remaining);
    }

    public JSONObject importUrisForOperation(String importId, String source, String target, List<Uri> uris, String text, boolean imagesOnly, int remaining) throws Exception {
        MediaInputPolicy.id(importId);
        if (uris.size() > MediaInputPolicy.MAX_ITEMS) throw new IOException(io.droyagent.app.NativeLocale.text("一次最多导入32个附件", "Up to 32 attachments can be imported at a time"));
        if (uris.isEmpty() && text.isBlank()) throw new IOException(io.droyagent.app.NativeLocale.text("分享内容为空", "Shared content is empty"));
        MediaInputPolicy.text(text);
        if (target != null) MediaInputPolicy.session(target);
        synchronized (LOCK) {
            File existing = new File(directory(importId), "manifest.json");
            if (existing.isFile()) return readJson(existing);
            checkCapacity();
            String id = importId;
            File staging = new File(root, "." + id + ".tmp");
            if (staging.exists()) deleteTree(staging);
            if (!staging.mkdirs()) throw new IOException(io.droyagent.app.NativeLocale.text("无法创建附件导入目录", "Unable to create attachment import directory"));
            try {
                JSONArray items = new JSONArray();
                long total = 0;
                int images = 0;
                for (Uri uri : uris) {
                    validateUri(uri);
                    String declared = MediaInputPolicy.mediaType(context.getContentResolver().getType(uri));
                    String name = displayName(uri);
                    String itemId = UUID.randomUUID().toString();
                    File destination = new File(staging, itemId + ".blob");
                    long maximum = Math.min(declared.startsWith("image/") || imagesOnly ? MediaInputPolicy.MAX_IMAGE_BYTES : MediaInputPolicy.MAX_FILE_BYTES,
                        MediaInputPolicy.MAX_BATCH_BYTES - total);
                    long bytes;
                    try (InputStream input = context.getContentResolver().openInputStream(uri); FileOutputStream output = new FileOutputStream(destination)) {
                        bytes = MediaInputPolicy.copy(input, output, maximum); output.getFD().sync();
                    }
                    String actual = imageType(destination);
                    boolean image = actual != null || declared.startsWith("image/") || imagesOnly;
                    if (image) {
                        if (++images > remaining || images > MediaInputPolicy.MAX_IMAGES) throw new IOException(io.droyagent.app.NativeLocale.text("每条消息最多添加10张图片", "Each message supports up to 10 images"));
                        if (actual == null || bytes == 0) throw new IOException(io.droyagent.app.NativeLocale.text("无法解码所选图片：", "Unable to decode selected image: ") + name);
                        if (bytes > MediaInputPolicy.MAX_IMAGE_BYTES) throw new IOException(io.droyagent.app.NativeLocale.text("单张图片不能超过20 MiB", "Each image must be at most 20 MiB"));
                        if (!IMAGE_TYPES.contains(actual) || dimensionsNeedSampling(destination)) {
                            convertImage(destination);
                            actual = "image/jpeg";
                            name = name.replaceFirst("(?i)\\.[^.]+$", "") + ".jpg";
                        }
                        declared = actual;
                        bytes = destination.length();
                    }
                    total += bytes;
                    if (total > MediaInputPolicy.MAX_BATCH_BYTES) throw new IOException(io.droyagent.app.NativeLocale.text("本批附件总大小不能超过200 MiB", "Attachments in this batch must total at most 200 MiB"));
                    items.put(new JSONObject().put("id", itemId).put("name", name).put("mediaType", declared).put("bytes", bytes));
                }
                if (pendingBytes() + total > MAX_PENDING_BYTES) throw new IOException(io.droyagent.app.NativeLocale.text("待发送附件占用过多空间，请先发送或移除已有附件", "Pending attachments use too much space. Please send or remove existing attachments first"));
                JSONObject manifest = new JSONObject().put("id", id).put("source", source).put("createdAt", System.currentTimeMillis())
                    .put("text", text).put("textApplied", false).put("items", items);
                if (target != null) manifest.put("targetSessionId", target);
                writeJson(new File(staging, "manifest.json"), manifest);
                Files.move(staging.toPath(), new File(root, id).toPath(), StandardCopyOption.ATOMIC_MOVE);
                return manifest;
            } catch (Exception failure) { deleteTree(staging); throw failure; }
        }
    }

    public JSONObject importCapture(File original, String target, int remaining, String albumName) throws Exception {
        synchronized (LOCK) {
            String id = MediaInputPolicy.id(original.getName().replaceFirst("\\.jpg$", ""));
            File directory = new File(root, id), record = new File(directory, "manifest.json");
            JSONObject manifest;
            if (record.isFile()) {
                manifest = readJson(record);
                if (!manifest.optBoolean("attachmentPending")) return saveAlbum(id);
            } else {
                checkCapacity();
                if (original.length() == 0 || original.length() > MediaInputPolicy.MAX_FILE_BYTES)
                    throw new IOException(io.droyagent.app.NativeLocale.text("相机照片为空或超过100 MiB", "Camera photo is empty or exceeds 100 MiB"));
                if (remaining < 1) throw new IOException(io.droyagent.app.NativeLocale.text("每条消息最多添加10张图片", "Each message supports up to 10 images"));
                String type = imageType(original);
                if (type == null) throw new IOException(io.droyagent.app.NativeLocale.text("相机未生成有效照片", "The camera did not produce a valid photo"));
                String extension = switch (type) {
                    case "image/jpeg" -> ".jpg";
                    case "image/png" -> ".png";
                    case "image/webp" -> ".webp";
                    case "image/gif" -> ".gif";
                    case "image/heif" -> ".heif";
                    case "image/heic" -> ".heic";
                    case "image/avif" -> ".avif";
                    case "image/bmp", "image/x-ms-bmp" -> ".bmp";
                    default -> "." + type.substring(type.indexOf('/') + 1).replaceAll("[^a-z0-9]", "");
                };
                manifest = new JSONObject().put("id", id).put("source", "camera")
                    .put("createdAt", System.currentTimeMillis()).put("targetSessionId", MediaInputPolicy.session(target))
                    .put("text", "").put("textApplied", false).put("albumName", MediaInputPolicy.filename(albumName))
                    .put("originalMediaType", type).put("originalName", "photo-" + id.substring(0, 8) + extension)
                    .put("albumSaved", false).put("attachmentPending", true).put("items", new JSONArray());
                if (pendingBytes() + original.length() * 2 > MAX_PENDING_BYTES)
                    throw new IOException(io.droyagent.app.NativeLocale.text("待发送附件占用过多空间，请先发送或移除已有附件", "Pending attachments use too much space. Please send or remove existing attachments first"));
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException(io.droyagent.app.NativeLocale.text("无法创建照片导入目录", "Unable to create photo import directory"));
                // Record the deterministic capture identity before moving its only original.
                writeJson(record, manifest);
            }
            File retained = new File(directory, "camera-original");
            if (!retained.isFile()) Files.move(original.toPath(), retained.toPath());
            // Album publication and model attachment normalization have independent outcomes.
            manifest = saveAlbum(id);
            String itemId = UUID.randomUUID().toString();
            File attachment = new File(directory, itemId + ".blob");
            try {
                Files.copy(retained.toPath(), attachment.toPath());
                String type = manifest.getString("originalMediaType"), name = manifest.getString("originalName");
                if (!IMAGE_TYPES.contains(type) || attachment.length() > MediaInputPolicy.MAX_IMAGE_BYTES || dimensionsNeedSampling(attachment)) {
                    convertImage(attachment); type = "image/jpeg"; name = name.replaceFirst("(?i)\\.[^.]+$", "") + ".jpg";
                }
                manifest.put("items", new JSONArray().put(new JSONObject().put("id", itemId).put("name", name)
                    .put("mediaType", type).put("bytes", attachment.length())));
                manifest.remove("error");
            } catch (Exception failure) {
                Files.deleteIfExists(attachment.toPath());
                manifest.put("error", io.droyagent.app.NativeLocale.text("照片附件准备失败：", "Photo attachment preparation failed: ") + (failure.getMessage() == null ? io.droyagent.app.NativeLocale.text("无法解码图片", "Unable to decode image") : failure.getMessage()));
            }
            manifest.put("attachmentPending", false);
            writeJson(record, manifest);
            return manifest;
        }
    }

    public JSONArray list() throws Exception {
        synchronized (LOCK) {
            List<JSONObject> ordered = new ArrayList<>();
            for (File directory : directories()) {
                File manifest = new File(directory, "manifest.json");
                if (manifest.isFile()) ordered.add(readJson(manifest));
            }
            ordered.sort(Comparator.comparingLong(value -> value.optLong("createdAt")));
            JSONArray result = new JSONArray();
            for (JSONObject manifest : ordered) result.put(manifest);
            return result;
        }
    }

    public JSONObject bind(String id, String target, Boolean textApplied) throws Exception {
        return bind(id, target, textApplied, null, null);
    }

    public JSONObject bind(String id, String target, Boolean textApplied, Boolean textPrepared, String textBeforeHash) throws Exception {
        if (Boolean.TRUE.equals(textPrepared)) MediaInputPolicy.draftHash(textBeforeHash);
        else if (textBeforeHash != null) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("草稿校验值缺少文字准备标记", "The draft checksum is missing the text preparation marker"));
        synchronized (LOCK) {
            JSONObject manifest = manifest(id);
            String previous = manifest.optString("targetSessionId", "");
            if (!previous.isEmpty() && !previous.equals(target)) throw new IOException(io.droyagent.app.NativeLocale.text("附件已绑定其他对话，请先完成当前导入", "Attachments belong to another conversation. Complete the current import first"));
            manifest.put("targetSessionId", MediaInputPolicy.session(target));
            if (textApplied != null) manifest.put("textApplied", textApplied);
            if (textPrepared != null) {
                manifest.put("textPrepared", textPrepared);
                if (textPrepared) manifest.put("textBeforeHash", textBeforeHash);
                else manifest.remove("textBeforeHash");
            }
            writeJson(new File(directory(id), "manifest.json"), manifest);
            return manifest;
        }
    }

    public JSONObject removeItems(String id, JSONArray ids) throws Exception {
        synchronized (LOCK) {
            JSONObject manifest = manifest(id);
            Set<String> remove = new java.util.HashSet<>();
            if (ids.length() > MediaInputPolicy.MAX_ITEMS) throw new IOException(io.droyagent.app.NativeLocale.text("附件数量无效", "Invalid attachment count"));
            for (int i = 0; i < ids.length(); i++) remove.add(MediaInputPolicy.id(ids.getString(i)));
            JSONArray kept = new JSONArray(), items = manifest.getJSONArray("items");
            List<File> discarded = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (remove.contains(item.getString("id"))) discarded.add(new File(directory(id), item.getString("id") + ".blob"));
                else kept.put(item);
            }
            manifest.put("items", kept);
            writeJson(new File(directory(id), "manifest.json"), manifest);
            for (File file : discarded) Files.deleteIfExists(file.toPath());
            return manifest;
        }
    }

    public void complete(String id) throws Exception {
        synchronized (LOCK) {
            JSONObject manifest = manifest(id);
            if (manifest.optString("source").equals("camera") && manifest.has("albumUri")) {
                Uri album = Uri.parse(manifest.getString("albumUri"));
                int state = albumState(album);
                // A crash after publication can leave stale metadata. Never delete a visible photo.
                if (state == 1) context.getContentResolver().delete(album, null, null);
            }
            deleteTree(directory(id));
        }
    }

    public JSONObject saveAlbum(String id) throws Exception {
        synchronized (LOCK) {
            JSONObject manifest = manifest(id);
            if (!manifest.optString("source").equals("camera")) throw new IOException(io.droyagent.app.NativeLocale.text("此附件没有待保存照片", "This attachment has no photo waiting to be saved"));
            if (manifest.optBoolean("albumSaved")) return manifest;
            File original = new File(directory(id), "camera-original");
            Uri uri = null;
            try {
                // Persist the row before writing, so retries reuse it after process death.
                if (manifest.has("albumUri")) {
                    uri = Uri.parse(manifest.getString("albumUri"));
                    int state = albumState(uri);
                    if (state == -1) { uri = null; manifest.remove("albumUri"); }
                    else if (state == 0) {
                        manifest.put("albumSaved", true); manifest.remove("albumError");
                        writeJson(new File(directory(id), "manifest.json"), manifest);
                        return manifest;
                    }
                }
                if (uri == null) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, manifest.getString("originalName"));
                    values.put(MediaStore.Images.Media.MIME_TYPE, manifest.getString("originalMediaType"));
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/" + MediaInputPolicy.filename(manifest.getString("albumName")));
                    values.put(MediaStore.Images.Media.IS_PENDING, 1);
                    uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new IOException(io.droyagent.app.NativeLocale.text("无法创建相册照片", "Unable to create album photo"));
                    manifest.put("albumUri", uri.toString());
                    writeJson(new File(directory(id), "manifest.json"), manifest);
                }
                try (InputStream input = new FileInputStream(original); var output = context.getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException(io.droyagent.app.NativeLocale.text("无法写入相册照片", "Unable to write album photo"));
                    MediaInputPolicy.copy(input, output, MediaInputPolicy.MAX_FILE_BYTES);
                }
                ContentValues ready = new ContentValues(); ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (context.getContentResolver().update(uri, ready, null, null) != 1) throw new IOException(io.droyagent.app.NativeLocale.text("相册照片尚未发布", "Album photo has not been published"));
                manifest.put("albumSaved", true).remove("albumError");
                writeJson(new File(directory(id), "manifest.json"), manifest);
            } catch (Exception failure) {
                manifest.put("albumSaved", false).put("albumError", io.droyagent.app.NativeLocale.text("照片未保存到相册，请重试", "Photo was not saved to the album. Please retry"));
                writeJson(new File(directory(id), "manifest.json"), manifest);
            }
            return manifest;
        }
    }

    public JSONObject error(String source, String target, Exception failure) throws Exception {
        synchronized (LOCK) {
            checkCapacity();
            String id = UUID.randomUUID().toString(); File directory = new File(root, id);
            if (!directory.mkdirs()) throw new IOException(io.droyagent.app.NativeLocale.text("无法保存附件导入结果", "Unable to save attachment import result"));
            JSONObject value = new JSONObject().put("id", id).put("source", source).put("items", new JSONArray())
                .put("text", "").put("createdAt", System.currentTimeMillis())
                .put("error", failure.getMessage() == null ? io.droyagent.app.NativeLocale.text("附件导入失败", "Attachment import failed") : failure.getMessage());
            if (target != null) value.put("targetSessionId", target);
            writeJson(new File(directory, "manifest.json"), value); return value;
        }
    }

    public void cleanAbandoned() throws IOException {
        synchronized (LOCK) {
            File[] files = root.listFiles();
            if (files == null) return;
            long cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
            for (File file : files) if ((file.getName().endsWith(".tmp") || !new File(file, "manifest.json").exists())
                    && file.lastModified() < cutoff) deleteTree(file);
        }
    }

    private JSONObject manifest(String id) throws Exception { return readJson(new File(directory(id), "manifest.json")); }
    private File directory(String id) { return new File(root, MediaInputPolicy.id(id)); }

    private File[] directories() {
        File[] result = root.listFiles(file -> file.isDirectory() && file.getName().matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"));
        if (result == null) return new File[0];
        Arrays.sort(result, Comparator.comparing(File::getName));
        return result;
    }

    private void checkCapacity() throws Exception {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException(io.droyagent.app.NativeLocale.text("无法创建附件目录", "Unable to create attachment directory"));
        if (directories().length >= MediaInputPolicy.MAX_PENDING_IMPORTS) throw new IOException(io.droyagent.app.NativeLocale.text("待处理分享过多，请先处理已有分享", "Too many pending shares. Complete existing shares first"));
        if (pendingBytes() >= MAX_PENDING_BYTES) throw new IOException(io.droyagent.app.NativeLocale.text("待发送附件占用过多空间", "Pending attachments use too much space"));
    }

    private long pendingBytes() throws Exception {
        long total = 0;
        for (File directory : directories()) {
            File manifest = new File(directory, "manifest.json");
            if (!manifest.isFile()) continue;
            JSONArray items = readJson(manifest).getJSONArray("items");
            for (int i = 0; i < items.length(); i++) total += items.getJSONObject(i).getLong("bytes");
            total += new File(directory, "camera-original").length();
        }
        return total;
    }

    private void validateUri(Uri uri) throws IOException {
        if (uri == null || !MediaInputPolicy.contentUri(uri.toString())) throw new IOException(io.droyagent.app.NativeLocale.text("分享文件地址无效", "Invalid shared file address"));
        ProviderInfo provider = context.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
        if (provider == null || provider.applicationInfo == null || provider.applicationInfo.uid == Process.myUid())
            throw new IOException(io.droyagent.app.NativeLocale.text("此文件提供方不可用于外部附件导入", "This file provider cannot be used for external attachment import"));
        if (context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED)
            throw new IOException(io.droyagent.app.NativeLocale.text("未取得分享文件的读取授权，请从原应用重新分享", "Shared file read permission is missing. Please share again from the original app"));
    }

    private int albumState(Uri uri) throws IOException {
        if (!MediaInputPolicy.contentUri(uri.toString()) || !uri.getAuthority().equals(MediaStore.AUTHORITY))
            throw new IOException(io.droyagent.app.NativeLocale.text("相册照片地址无效", "Invalid album photo address"));
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{MediaStore.Images.Media.IS_PENDING, MediaStore.MediaColumns.OWNER_PACKAGE_NAME}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return -1;
            if (!context.getPackageName().equals(cursor.getString(1))) throw new IOException(io.droyagent.app.NativeLocale.text("相册照片不属于当前应用", "The album photo does not belong to this app"));
            return cursor.getInt(0);
        }
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return MediaInputPolicy.filename(cursor.getString(0));
        } catch (RuntimeException ignored) { /* Name is optional provider metadata; byte read errors remain visible. */ }
        return "attachment";
    }

    private static String imageType(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        return options.outWidth > 0 && options.outHeight > 0 && options.outMimeType != null ? options.outMimeType : null;
    }

    private static boolean dimensionsNeedSampling(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        return options.outWidth > 8192 || options.outHeight > 8192 || (long) options.outWidth * options.outHeight > 64_000_000;
    }

    private static void convertImage(File file) throws Exception {
        Bitmap bitmap = null; File converted = new File(file.getParentFile(), file.getName() + ".jpeg");
        try {
            bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
                int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                double ratio = Math.min(1, Math.min(4096d / Math.max(width, height), Math.sqrt(8_000_000d / ((long) width * height))));
                decoder.setTargetSize(Math.max(1, (int) (width * ratio)), Math.max(1, (int) (height * ratio)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
            try (FileOutputStream output = new FileOutputStream(converted)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) throw new IOException(io.droyagent.app.NativeLocale.text("无法转换所选图片", "Unable to convert selected image"));
                output.getFD().sync();
            }
            if (converted.length() > MediaInputPolicy.MAX_IMAGE_BYTES) throw new IOException(io.droyagent.app.NativeLocale.text("转换后图片仍超过20 MiB", "Converted image still exceeds 20 MiB"));
            Files.move(converted.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { if (bitmap != null) bitmap.recycle(); Files.deleteIfExists(converted.toPath()); }
    }

    static JSONObject readJson(File file) throws Exception {
        if (!file.isFile() || file.length() > 512 * 1024) throw new IOException(io.droyagent.app.NativeLocale.text("附件导入记录不可用", "Attachment import record is unavailable"));
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    static void writeJson(File file, JSONObject value) throws Exception {
        File temporary = new File(file.getParentFile(), file.getName() + ".next");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(value.toString().getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static void deleteTree(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) { Files.deleteIfExists(file.toPath()); return; }
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        Files.deleteIfExists(file.toPath());
    }
}
