package io.droyagent.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Process;
import android.provider.DocumentsContract;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.widget.Toast;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Android document pickers for DSH's browser uploads and authenticated downloads. */
final class ChatFileTransfers {
    private static final int PICK_UPLOAD = 601, SAVE_DOWNLOAD = 602;
    private static final AtomicInteger OPEN_PICKERS = new AtomicInteger();
    private static final AtomicInteger ACTIVE_DOWNLOADS = new AtomicInteger();
    private final Activity activity;
    private final int port;
    private final BooleanSupplier current;
    private final BiConsumer<String, Exception> failure;
    private ValueCallback<Uri[]> upload;
    private boolean multiple;
    private Download download;
    private boolean pickerOpen;
    private boolean closed;

    private record Download(String address, String cookie, String filename) {}

    ChatFileTransfers(Activity activity, int port, BooleanSupplier current, BiConsumer<String, Exception> failure) {
        this.activity = activity; this.port = port; this.current = current; this.failure = failure;
    }

    static boolean isPickerOpen() { return OPEN_PICKERS.get() > 0; }
    static boolean hasRuntimeWork() { return isPickerOpen() || ACTIVE_DOWNLOADS.get() > 0; }

    boolean chooseFiles(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (closed || !current.getAsBoolean() || pickerOpen) { callback.onReceiveValue(null); return true; }
        int mode = params.getMode();
        if (mode != WebChromeClient.FileChooserParams.MODE_OPEN && mode != WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            callback.onReceiveValue(null);
            failure.accept(io.droyagent.app.NativeLocale.text("文件选择未完成", "File selection is incomplete"), new IOException(io.droyagent.app.NativeLocale.text("请使用附件入口选择文件", "Please select files using the attachment button")));
            return true;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
        String[] types = ChatFilePolicy.acceptedMimeTypes(params.getAcceptTypes());
        intent.setType(types.length == 1 ? types[0] : "*/*");
        if (types.length > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
        multiple = mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        upload = callback;
        try { openPicker(intent, PICK_UPLOAD); }
        catch (RuntimeException error) {
            upload = null; callback.onReceiveValue(null);
            failure.accept(io.droyagent.app.NativeLocale.text("无法打开文件选择器", "Unable to open file picker"), error);
        }
        return true;
    }

    void download(String address, String disposition, String mediaType) {
        if (closed || !current.getAsBoolean()) return;
        if (pickerOpen) { Toast.makeText(activity, io.droyagent.app.NativeLocale.text("请先完成当前文件选择", "Please finish the current file selection first"), Toast.LENGTH_SHORT).show(); return; }
        if (!ChatFilePolicy.isLocalDownload(address, port)) {
            failure.accept(io.droyagent.app.NativeLocale.text("下载未开始", "Download did not start"), new IOException(io.droyagent.app.NativeLocale.text("下载链接不属于当前引擎", "The download link does not belong to the current engine"))); return;
        }
        String filename = ChatFilePolicy.filename(URLUtil.guessFileName(address, disposition, mediaType));
        download = new Download(address, CookieManager.getInstance().getCookie(address), filename);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(ChatFilePolicy.mimeType(mediaType)).putExtra(Intent.EXTRA_TITLE, filename)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { openPicker(intent, SAVE_DOWNLOAD); }
        catch (RuntimeException error) { download = null; failure.accept(io.droyagent.app.NativeLocale.text("无法选择保存位置", "Unable to choose save location"), error); }
    }

    private void openPicker(Intent intent, int request) {
        pickerOpen = true; OPEN_PICKERS.incrementAndGet();
        RuntimeService.refreshBackgroundPolicy();
        try { activity.startActivityForResult(intent, request); }
        catch (RuntimeException error) { releasePicker(); throw error; }
    }

    boolean onActivityResult(int request, int result, Intent data) {
        if (request != PICK_UPLOAD && request != SAVE_DOWNLOAD) return false;
        releasePicker();
        if (request == PICK_UPLOAD) {
            ValueCallback<Uri[]> callback = upload; upload = null;
            if (callback == null) return true;
            Uri[] selected = null;
            try {
                if (result == Activity.RESULT_OK && data != null && !closed && current.getAsBoolean()) {
                    LinkedHashSet<Uri> uris = new LinkedHashSet<>();
                    ClipData clips = data.getClipData();
                    if (clips != null) for (int i = 0; i < clips.getItemCount(); i++) uris.add(clips.getItemAt(i).getUri());
                    else if (data.getData() != null) uris.add(data.getData());
                    if (!multiple && uris.size() > 1) throw new IOException(io.droyagent.app.NativeLocale.text("此次只能选择一个文件", "Only one file can be selected this time"));
                    for (Uri uri : uris) validateDocument(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    if (!uris.isEmpty()) selected = uris.toArray(new Uri[0]);
                }
            } catch (Exception error) { failure.accept(io.droyagent.app.NativeLocale.text("文件选择未完成", "File selection is incomplete"), error); }
            callback.onReceiveValue(selected);
        } else {
            Download pending = download; download = null;
            if (pending == null || result != Activity.RESULT_OK || data == null || data.getData() == null || closed) return true;
            Uri destination = data.getData();
            try { validateDocument(destination, Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
            catch (Exception error) { failure.accept(io.droyagent.app.NativeLocale.text("保存位置不可用", "Save location is unavailable"), error); return true; }
            saveDownload(pending, destination);
        }
        return true;
    }

    private void validateDocument(Uri uri, int permission) throws IOException {
        if (uri == null || !ChatFilePolicy.isContentUri(uri.toString())) throw new IOException(io.droyagent.app.NativeLocale.text("文件选择器返回了无效文件", "The file picker returned an invalid file"));
        ProviderInfo provider = activity.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
        // A picker cannot turn the app's own private provider into an upload.
        if (provider == null || provider.applicationInfo == null || provider.applicationInfo.uid == Process.myUid())
            throw new IOException(io.droyagent.app.NativeLocale.text("此文件提供方不可用于附件传输", "This file provider cannot be used for attachments"));
        if (activity.checkUriPermission(uri, Process.myPid(), Process.myUid(), permission) != PackageManager.PERMISSION_GRANTED)
            throw new IOException(io.droyagent.app.NativeLocale.text("尚未取得所选文件的访问授权", "Access to the selected file has not been granted"));
    }

    private void saveDownload(Download pending, Uri destination) {
        Toast.makeText(activity, io.droyagent.app.NativeLocale.text("正在保存 ", "Saving ") + pending.filename(), Toast.LENGTH_SHORT).show();
        // A one-use archive remains on the local server while the system picker
        // is foreground, and until its selected document has finished writing.
        ACTIVE_DOWNLOADS.incrementAndGet();
        RuntimeService.refreshBackgroundPolicy();
        Thread transfer = new Thread(() -> {
          try {
            try (OutputStream output = activity.getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException(io.droyagent.app.NativeLocale.text("无法打开保存位置", "Unable to open save location"));
                ChatDownload.copy(pending.address(), port, pending.cookie(), output, current);
            } catch (Exception error) {
                // CREATE_DOCUMENT creates a new document; remove an incomplete
                // result so a failed ZIP is never mistaken for a saved archive.
                try {
                    if (!DocumentsContract.deleteDocument(activity.getContentResolver(), destination))
                        error.addSuppressed(new IOException(io.droyagent.app.NativeLocale.text("未能删除未完成的文件", "Unable to delete incomplete file")));
                } catch (Exception cleanup) { error.addSuppressed(cleanup); }
                activity.runOnUiThread(() -> {
                    if (!activity.isDestroyed()) {
                        String title = error.getSuppressed().length == 0 ? io.droyagent.app.NativeLocale.text("文件保存失败", "File could not be saved") : io.droyagent.app.NativeLocale.text("文件保存失败，请删除未完成的文件", "File could not be saved. Please delete the incomplete file");
                        failure.accept(title, error);
                    }
                });
                return;
            }
            activity.runOnUiThread(() -> {
                if (!activity.isDestroyed()) Toast.makeText(activity, io.droyagent.app.NativeLocale.text("已保存 ", "Saved ") + pending.filename(), Toast.LENGTH_LONG).show();
            });
          } finally {
              ACTIVE_DOWNLOADS.decrementAndGet();
              RuntimeService.refreshBackgroundPolicy();
          }
        }, "droy-chat-download");
        try { transfer.start(); }
        catch (RuntimeException error) {
            ACTIVE_DOWNLOADS.decrementAndGet();
            RuntimeService.refreshBackgroundPolicy();
            failure.accept(io.droyagent.app.NativeLocale.text("文件保存未开始", "File saving did not start"), error);
        }
    }

    private void releasePicker() {
        if (pickerOpen) {
            pickerOpen = false; OPEN_PICKERS.decrementAndGet();
            RuntimeService.refreshBackgroundPolicy();
        }
    }

    void close() {
        closed = true; releasePicker(); download = null;
        ValueCallback<Uri[]> pending = upload; upload = null;
        if (pending != null) pending.onReceiveValue(null);
    }
}
