package io.droyagent.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import androidx.core.content.FileProvider;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

/** Human capture uses the same product pause and Android permission facts as other entry points. */
public final class NativeCameraInputHost {
    private NativeCameraInputHost() {}
    public static final class Facts {
        public final boolean granted, available, paused, foreground;
        Facts(boolean granted, boolean available, boolean paused, boolean foreground) {
            this.granted = granted; this.available = available; this.paused = paused; this.foreground = foreground;
        }
    }
    public static final class Failure extends Exception {
        public final String code;
        private Failure(String code) { super(code); this.code = code; }
    }
    public static Facts inspect(Context context) {
        return new Facts(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
            context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY), ProductPermissions.stopped(context), BackgroundRuntime.visible());
    }
    public static void checkStart(Context context) throws Failure {
        Facts facts = inspect(context);
        if (facts.paused) throw new Failure("native_camera_paused");
        if (!facts.available) throw new Failure("native_camera_unavailable");
        if (!facts.granted) throw new Failure("native_camera_permission");
        if (!facts.foreground) throw new Failure("native_camera_foreground");
    }
    static String filename(String id) {
        if (id == null || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("native_camera_owner");
        return "native-" + id + ".jpg";
    }
    private static File output(Context context, String id) throws Exception {
        File captures = new File(context.getFilesDir(), "media-input/captures");
        File result = new File(captures, filename(id));
        if (Files.isSymbolicLink(captures.toPath()) || Files.isSymbolicLink(result.toPath())
                || !result.getCanonicalFile().getParentFile().equals(captures.getCanonicalFile())) throw new Failure("native_camera_owner");
        return result;
    }
    public static File prepareOutput(Context context, String id) throws Exception {
        checkStart(context);
        File result = output(context, id), captures = result.getParentFile();
        if ((!captures.isDirectory() && !captures.mkdirs()) || !result.createNewFile()) throw new Failure("native_camera_storage");
        return result;
    }
    public static File completedOutput(Context context, String id) throws Exception { return output(context, id); }
    public static void discardOutput(Context context, String id) throws Exception {
        File result = output(context, id);
        context.revokeUriPermission(FileProvider.getUriForFile(context, context.getPackageName() + ".media-input-files", result),
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        Files.deleteIfExists(result.toPath());
    }
}
