package io.droyagent.app.media;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Activity-result adapter. All engine-facing state lives in MediaInputStore. */
public final class MediaInputController {
    public static final int PICK_IMAGES = 711, TAKE_PHOTO = 712;
    private static final AtomicInteger OPEN_PICKERS = new AtomicInteger();
    private static final java.util.Set<String> PROCESSING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Activity activity;
    private final Runnable changed;
    private final MediaInputStore store;
    private volatile JSONObject pending;
    private boolean pickerOpen;
    private volatile boolean closed;

    public MediaInputController(Activity activity, Runnable changed) {
        this.activity = activity; this.changed = changed; this.store = new MediaInputStore(activity);
    }

    public static boolean isPickerOpen() { return OPEN_PICKERS.get() > 0; }

    public JSONObject request(JSONObject args) throws Exception {
        if (pending != null && pending.optBoolean("resultReady") && !pendingFile().exists()) pending = null;
        return switch (args.getString("operation")) {
            case "pick" -> pick(args);
            case "list" -> new JSONObject().put("imports", store.list());
            case "bind" -> store.bind(args.getString("id"), args.getString("targetSessionId"),
                optionalBoolean(args, "textApplied"), optionalBoolean(args, "textPrepared"),
                args.has("textBeforeHash") ? args.getString("textBeforeHash") : null);
            case "removeItems" -> store.removeItems(args.getString("id"), args.getJSONArray("itemIds"));
            case "complete" -> { store.complete(args.getString("id")); yield new JSONObject().put("completed", true); }
            case "retryAlbum" -> {
                String id = MediaInputPolicy.id(args.getString("id"));
                new Thread(() -> {
                    try { store.saveAlbum(id); }
                    catch (Exception failure) { report("camera", null, failure); }
                    notifyChanged();
                }, "droy-media-album").start();
                yield new JSONObject().put("started", true);
            }
            default -> throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("未知附件操作", "Unknown attachment operation"));
        };
    }

    private JSONObject pick(JSONObject args) throws Exception {
        if (closed || activity.isDestroyed()) throw new IOException(io.droyagent.app.NativeLocale.text("当前页面已关闭", "The current page has closed"));
        if (pending != null || pickerOpen) throw new IOException(io.droyagent.app.NativeLocale.text("请先完成当前照片选择", "Please finish the current photo selection first"));
        String source = args.getString("source");
        if (!source.equals("album") && !source.equals("camera")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("照片来源无效", "Invalid photo source"));
        int remaining = args.getInt("remaining");
        if (remaining < 1 || remaining > MediaInputPolicy.MAX_IMAGES) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("每条消息最多添加10张图片", "Each message supports up to 10 images"));
        String target = MediaInputPolicy.session(args.getString("targetSessionId"));
        pending = new JSONObject().put("operationId", UUID.randomUUID().toString()).put("source", source).put("targetSessionId", target).put("remaining", remaining);
        Intent intent;
        int requestCode;
        if (source.equals("album")) {
            requestCode = PICK_IMAGES;
            intent = new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
            if (remaining > 1) intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,
                Build.VERSION.SDK_INT >= 33 ? Math.min(remaining, MediaStore.getPickImagesMaxLimit()) : remaining);
            if (intent.resolveActivity(activity.getPackageManager()) == null) {
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, remaining > 1);
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            requestCode = TAKE_PHOTO;
            String captureId = pending.getString("operationId");
            File captures = new File(activity.getFilesDir(), "media-input/captures");
            if (!captures.isDirectory() && !captures.mkdirs()) { pending = null; throw new IOException(io.droyagent.app.NativeLocale.text("无法准备拍照文件", "Unable to prepare camera file")); }
            File photo = new File(captures, captureId + ".jpg");
            if (!photo.createNewFile()) { pending = null; throw new IOException(io.droyagent.app.NativeLocale.text("无法准备拍照文件", "Unable to prepare camera file")); }
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".media-input-files", photo);
            pending.put("captureId", captureId).put("captureUri", uri.toString());
            intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("photo", uri));
        }
        try {
            persistPending();
            pickerOpen = true; OPEN_PICKERS.incrementAndGet();
            activity.startActivityForResult(intent, requestCode);
            return new JSONObject().put("started", true);
        } catch (Exception failure) {
            releasePicker(); cleanPendingCapture(); clearPending(); throw failure;
        }
    }

    public boolean onActivityResult(int request, int result, Intent data) {
        if (request != PICK_IMAGES && request != TAKE_PHOTO) return false;
        releasePicker();
        JSONObject operation = pending;
        if (operation == null) return true;
        if (result != Activity.RESULT_OK) {
            deleteCapture(operation);
            try { clearPending(); } catch (IOException failure) { report(operation.optString("source"), operation.optString("targetSessionId"), failure); }
            notifyChanged(); return true;
        }
        try {
            if (operation.getString("source").equals("album")) {
                LinkedHashSet<Uri> uris = collectUris(data);
                if (uris.isEmpty()) throw new IOException(io.droyagent.app.NativeLocale.text("照片选择器未返回照片", "The photo picker returned no photos"));
                if (uris.size() > operation.getInt("remaining")) throw new IOException(io.droyagent.app.NativeLocale.text("所选照片超过剩余数量，请重新选择", "Selected photos exceed the remaining limit. Please select again"));
                JSONArray values = new JSONArray(), persisted = new JSONArray();
                for (Uri uri : uris) {
                    values.put(uri.toString());
                    try {
                        activity.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        persisted.put(uri.toString());
                    } catch (SecurityException unavailable) { /* Some picker providers grant only Activity-lifetime access; copy immediately. */ }
                }
                operation.put("uris", values).put("persistedUris", persisted);
            }
            operation.put("resultReady", true);
            persistPending();
            processResult(operation);
        } catch (Exception failure) {
            report(operation.optString("source"), operation.optString("targetSessionId"), failure);
            deleteCapture(operation);
            try { clearPending(); } catch (IOException cleanup) { report(operation.optString("source"), null, cleanup); }
            notifyChanged();
        }
        return true;
    }

    private void processResult(JSONObject operation) {
        String operationId = operation.optString("operationId");
        if (!PROCESSING.add(operationId)) {
            // A recreated Activity observes the original worker instead of copying twice.
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.post(new Runnable() {
                @Override public void run() {
                    if (closed || activity.isDestroyed()) return;
                    if (PROCESSING.contains(operationId)) { handler.postDelayed(this, 200); return; }
                    if (!pendingFile().exists()) pending = null;
                    notifyChanged();
                }
            });
            return;
        }
        new Thread(() -> {
            String source = operation.optString("source"), target = operation.optString("targetSessionId");
            try {
                if (source.equals("camera")) store.importCapture(captureFile(operation), target, operation.getInt("remaining"), applicationName());
                else {
                    JSONArray values = operation.getJSONArray("uris");
                    java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
                    for (int i = 0; i < values.length(); i++) uris.add(Uri.parse(values.getString(i)));
                    store.importUrisForOperation(operationId, "album", target, uris, "", true, operation.getInt("remaining"));
                }
            } catch (Exception failure) { report(source, target, failure); }
            finally {
                deleteCapture(operation);
                JSONArray persisted = operation.optJSONArray("persistedUris");
                if (persisted != null) for (int i = 0; i < persisted.length(); i++) {
                    try { activity.getContentResolver().releasePersistableUriPermission(Uri.parse(persisted.optString(i)), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                    catch (SecurityException unavailable) { /* The provider may already have revoked an expired grant. */ }
                }
                try {
                    if (pendingFile().isFile() && MediaInputStore.readJson(pendingFile()).optString("operationId").equals(operationId)) Files.deleteIfExists(pendingFile().toPath());
                } catch (Exception cleanup) { report(source, target, cleanup); }
                PROCESSING.remove(operationId);
                activity.runOnUiThread(() -> {
                    if (pending != null && pending.optString("operationId").equals(operationId)) pending = null;
                    notifyChanged();
                });
            }
        }, "droy-media-import").start();
    }

    public void onSaveInstanceState(Bundle state) {
        if (pending != null) state.putString("droyMediaPending", pending.toString());
    }

    public void restore(Bundle state) {
        try {
            if (pendingFile().isFile()) pending = MediaInputStore.readJson(pendingFile());
            if (pending != null) {
                MediaInputPolicy.session(pending.getString("targetSessionId"));
                if (pending.has("captureId")) MediaInputPolicy.id(pending.getString("captureId"));
                if (state == null && !pending.optBoolean("resultReady")) {
                    JSONObject abandoned = pending;
                    deleteCapture(abandoned); clearPending();
                    report(abandoned.optString("source"), abandoned.optString("targetSessionId"),
                        new IOException(io.droyagent.app.NativeLocale.text("上次照片选择已中断，请重新选择", "The previous photo selection was interrupted. Please select again")));
                    return;
                }
                if (pending.optBoolean("resultReady")) processResult(pending);
                else { pickerOpen = true; OPEN_PICKERS.incrementAndGet(); }
            }
        } catch (Exception failure) { pending = null; report("camera", null, failure); }
    }

    public void close() { closed = true; releasePicker(); }

    public static LinkedHashSet<Uri> collectUris(Intent data) throws IOException {
        LinkedHashSet<Uri> result = new LinkedHashSet<>();
        if (data == null) return result;
        boolean sharing = Intent.ACTION_SEND.equals(data.getAction()) || Intent.ACTION_SEND_MULTIPLE.equals(data.getAction());
        ClipData clips = data.getClipData();
        if (clips != null) {
            if (clips.getItemCount() > MediaInputPolicy.MAX_ITEMS) throw new IOException(io.droyagent.app.NativeLocale.text("一次最多导入32个附件", "Up to 32 attachments can be imported at a time"));
            for (int i = 0; i < clips.getItemCount(); i++) {
                Uri uri = clips.getItemAt(i).getUri();
                if (uri != null && (!sharing || !webUri(uri))) result.add(uri);
            }
        }
        if (data.getData() != null && (!sharing || !webUri(data.getData()))) result.add(data.getData());
        if (Intent.ACTION_SEND_MULTIPLE.equals(data.getAction())) {
            java.util.ArrayList<Uri> streams = Build.VERSION.SDK_INT >= 33
                ? data.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class) : data.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) {
                if (streams.size() > MediaInputPolicy.MAX_ITEMS) throw new IOException(io.droyagent.app.NativeLocale.text("一次最多导入32个附件", "Up to 32 attachments can be imported at a time"));
                for (Uri uri : streams) if (uri != null) result.add(uri);
            }
        } else {
            Uri stream = Build.VERSION.SDK_INT >= 33 ? data.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class) : data.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) result.add(stream);
        }
        if (result.size() > MediaInputPolicy.MAX_ITEMS) throw new IOException(io.droyagent.app.NativeLocale.text("一次最多导入32个附件", "Up to 32 attachments can be imported at a time"));
        return result;
    }

    static boolean webUri(Uri uri) { return "https".equals(uri.getScheme()) || "http".equals(uri.getScheme()); }

    private static Boolean optionalBoolean(JSONObject args, String key) throws Exception {
        if (!args.has(key)) return null;
        if (!(args.get(key) instanceof Boolean value)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("附件状态必须为布尔值", "Attachment state must be a boolean"));
        return value;
    }

    private String applicationName() { return activity.getApplicationInfo().loadLabel(activity.getPackageManager()).toString(); }
    private File pendingFile() { return new File(activity.getFilesDir(), "media-input/pending.json"); }
    private File captureFile(JSONObject operation) throws Exception {
        return new File(activity.getFilesDir(), "media-input/captures/" + MediaInputPolicy.id(operation.getString("captureId")) + ".jpg");
    }
    private void persistPending() throws Exception {
        File parent = pendingFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException(io.droyagent.app.NativeLocale.text("无法记录照片选择状态", "Unable to record photo selection state"));
        MediaInputStore.writeJson(pendingFile(), pending);
    }
    private void clearPending() throws IOException { pending = null; Files.deleteIfExists(pendingFile().toPath()); }
    private void cleanPendingCapture() { if (pending != null) deleteCapture(pending); }
    private void deleteCapture(JSONObject operation) {
        if (!operation.has("captureId")) return;
        try {
            if (operation.has("captureUri")) activity.revokeUriPermission(Uri.parse(operation.getString("captureUri")),
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Files.deleteIfExists(captureFile(operation).toPath());
        } catch (Exception failure) { report("camera", operation.optString("targetSessionId", null), failure); }
    }
    private void releasePicker() { if (pickerOpen) { pickerOpen = false; OPEN_PICKERS.decrementAndGet(); } }
    private void notifyChanged() { activity.runOnUiThread(() -> { if (!closed && !activity.isDestroyed()) changed.run(); }); }
    private void report(String source, String target, Exception failure) {
        try { store.error(source, target == null || target.isBlank() ? null : target, failure); }
        catch (Exception storageFailure) {
            activity.runOnUiThread(() -> android.widget.Toast.makeText(activity,
                io.droyagent.app.NativeLocale.text("附件导入失败：", "Attachment import failed: ") + (failure.getMessage() == null ? io.droyagent.app.NativeLocale.text("无法读取附件", "Unable to read attachment") : failure.getMessage()), android.widget.Toast.LENGTH_LONG).show());
        }
    }
}
