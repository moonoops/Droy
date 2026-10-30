package io.droyagent.app;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.webkit.MimeTypeMap;
import androidx.core.content.FileProvider;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** Private verified copies, distinct from engine files and shared attachments. */
public final class NativeToolsFileHost {
    public static final long MAX_BYTES = 100L * 1024 * 1024;
    private NativeToolsFileHost() {}

    public static final class Transfer implements AutoCloseable {
        private final File directory, partial, target;
        private final FileOutputStream output;
        private final long expected;
        private final MessageDigest digest;
        private long bytes;
        private boolean committed, closed;
        private Transfer(File cacheDir, String name, long expected) throws Exception {
            if (expected < -1 || expected > MAX_BYTES) throw new IOException("NATIVE_TOOLS_EXPORT_TOO_LARGE");
            this.expected = expected;
            File root = root(cacheDir); trimExpired(root);
            directory = new File(root, UUID.randomUUID().toString());
            if (!directory.mkdir()) throw new IOException("NATIVE_TOOLS_CACHE_FAILED");
            partial = new File(directory, ".partial-" + UUID.randomUUID()); target = new File(directory, ChatFilePolicy.filename(name));
            output = new FileOutputStream(partial); digest = MessageDigest.getInstance("SHA-256");
        }
        public synchronized void append(byte[] value) throws IOException {
            if (closed || committed) throw new IOException("NATIVE_TOOLS_CACHE_FAILED");
            if (bytes + value.length > MAX_BYTES || expected >= 0 && bytes + value.length > expected) throw new IOException("NATIVE_TOOLS_EXPORT_TOO_LARGE");
            output.write(value); digest.update(value); bytes += value.length;
        }
        public synchronized File commit(String sha256) throws IOException {
            if (closed || committed || expected >= 0 && bytes != expected) throw new IOException("NATIVE_TOOLS_COPY_CHANGED");
            if (sha256 != null) {
                if (!sha256.matches("[a-f0-9]{64}")) throw new IOException("NATIVE_TOOLS_COPY_CHANGED");
                StringBuilder actual = new StringBuilder(); for (byte part : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", part & 255));
                if (!MessageDigest.isEqual(actual.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII), sha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                    throw new IOException("NATIVE_TOOLS_COPY_CHANGED");
            }
            output.flush(); output.getFD().sync(); output.close(); closed = true;
            if (!partial.renameTo(target)) throw new IOException("NATIVE_TOOLS_CACHE_FAILED");
            target.setWritable(false, false); committed = true; return target;
        }
        @Override public synchronized void close() {
            if (!closed) { try { output.close(); } catch (IOException ignored) {} closed = true; }
            if (!committed) { partial.delete(); target.delete(); directory.delete(); }
        }
    }
    public static Transfer begin(Context context, String name, long expectedBytes) throws Exception { return begin(context.getCacheDir(), name, expectedBytes); }
    static Transfer begin(File cacheDir, String name, long expectedBytes) throws Exception { return new Transfer(cacheDir, name, expectedBytes); }
    public static File cacheBytes(Context context, String name, byte[] bytes) throws Exception {
        try (Transfer transfer = begin(context, name, bytes.length)) { transfer.append(bytes); return transfer.commit(null); }
    }
    private static File root(File cacheDir) throws IOException {
        File value = new File(cacheDir, "native-tools-exports").getCanonicalFile();
        if (!value.isDirectory() && !value.mkdirs()) throw new IOException("NATIVE_TOOLS_CACHE_FAILED"); return value;
    }
    private static File verified(Context context, File file) throws IOException {
        File root = root(context.getCacheDir()), value = file.getCanonicalFile();
        if (!value.getPath().startsWith(root.getPath() + File.separator) || !value.isFile() || value.length() > MAX_BYTES)
            throw new IOException("NATIVE_TOOLS_CACHE_FAILED");
        return value;
    }
    private static void trimExpired(File root) {
        File[] directories = root.listFiles(); if (directories == null) return;
        long before = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        for (File directory : directories) {
            if (!directory.isDirectory() || !directory.getName().matches("[a-f0-9-]{36}") || directory.lastModified() >= before) continue;
            try { if (java.nio.file.Files.isSymbolicLink(directory.toPath()) || !root.equals(directory.getCanonicalFile().getParentFile())) continue; }
            catch (IOException unavailable) { continue; }
            File[] files = directory.listFiles(); if (files == null) continue;
            for (File file : files) if (file.isFile()) file.delete();
            directory.delete();
        }
    }
    public static String mediaType(String name) {
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
        return type == null ? "application/octet-stream" : type;
    }
    public static Intent openIntent(Context context, File file, String mediaType) throws IOException {
        File copy = verified(context, file);
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".media-input-files", copy);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mediaType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri(copy.getName(), uri));
        Intent chooser = Intent.createChooser(view, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        chooser.setClipData(view.getClipData()); return chooser;
    }
    public static Intent saveIntent(Context context, File file, String mediaType) throws IOException {
        File copy = verified(context, file);
        return new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mediaType).putExtra(Intent.EXTRA_TITLE, copy.getName());
    }
    public static void saveCopy(Context context, File file, Uri destination) throws IOException {
        File copy = verified(context, file);
        if (destination == null || !"content".equals(destination.getScheme())) throw new IOException("NATIVE_TOOLS_SAVE_FAILED");
        ProviderInfo provider = context.getPackageManager().resolveContentProvider(destination.getAuthority(), 0);
        if (provider == null || provider.applicationInfo == null || provider.applicationInfo.uid == Process.myUid()
            || context.checkUriPermission(destination, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw new IOException("NATIVE_TOOLS_SAVE_FAILED");
        try (InputStream input = new FileInputStream(copy); OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
            if (output == null) throw new IOException("NATIVE_TOOLS_SAVE_FAILED");
            byte[] buffer = new byte[65536]; long bytes = 0; int count;
            while ((count = input.read(buffer)) >= 0) { bytes += count; if (bytes > MAX_BYTES) throw new IOException("NATIVE_TOOLS_EXPORT_TOO_LARGE"); output.write(buffer, 0, count); }
            if (bytes != copy.length()) throw new IOException("NATIVE_TOOLS_COPY_CHANGED");
        } catch (Exception error) {
            try { if (!android.provider.DocumentsContract.deleteDocument(context.getContentResolver(), destination))
                throw new IOException("NATIVE_TOOLS_SAVE_INCOMPLETE"); }
            catch (Exception cleanup) { IOException incomplete = new IOException("NATIVE_TOOLS_SAVE_INCOMPLETE", error); incomplete.addSuppressed(cleanup); throw incomplete; }
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("NATIVE_TOOLS_SAVE_FAILED", error);
        }
    }
    public static final class PdfPage {
        public final Bitmap bitmap; public final int pageCount;
        PdfPage(Bitmap bitmap, int pageCount) { this.bitmap = bitmap; this.pageCount = pageCount; }
    }
    public static PdfPage renderPdfPage(Context context, File file, int index, int width) throws IOException {
        File copy = verified(context, file);
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer renderer = new PdfRenderer(descriptor)) {
            if (index < 0 || index >= renderer.getPageCount()) throw new IOException("NATIVE_TOOLS_PDF_UNAVAILABLE");
            try (PdfRenderer.Page page = renderer.openPage(index)) {
                int targetWidth = Math.max(1, Math.min(2048, width));
                double scale = Math.min((double) targetWidth / page.getWidth(), 4096d / page.getHeight());
                int w = Math.max(1, (int) Math.round(page.getWidth() * scale)), h = Math.max(1, (int) Math.round(page.getHeight() * scale));
                if ((long) w * h > 8_000_000) throw new IOException("NATIVE_TOOLS_PDF_UNAVAILABLE");
                Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.WHITE);
                Matrix matrix = new Matrix(); matrix.setScale((float) scale, (float) scale);
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return new PdfPage(bitmap, renderer.getPageCount());
            }
        }
    }
}
