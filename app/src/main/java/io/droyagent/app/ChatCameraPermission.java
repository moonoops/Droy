package io.droyagent.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.webkit.PermissionRequest;
import java.util.function.BooleanSupplier;

/** Camera-only WebView grants leave audio ownership with the native Voice session. */
final class ChatCameraPermission {
    static final int REQUEST = 72;
    private final Activity activity;
    private PermissionRequest pending;
    private BooleanSupplier current;
    private int port;

    ChatCameraPermission(Activity activity) { this.activity = activity; }

    void request(PermissionRequest request, int sessionPort, BooleanSupplier valid) {
        cancelPending();
        if (!allowed(request, sessionPort, valid)) { request.deny(); return; }
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            grant(request); return;
        }
        pending = request; current = valid; port = sessionPort;
        activity.requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST);
    }

    boolean result(int code) {
        if (code != REQUEST) return false;
        PermissionRequest request = pending;
        BooleanSupplier valid = current;
        pending = null; current = null;
        if (request == null) return true;
        if (allowed(request, port, valid)
                && activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            grant(request);
        else request.deny();
        return true;
    }

    private void grant(PermissionRequest request) {
        try {
            AudioCaptureService.setCameraActive(activity, true);
            request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
        } catch (RuntimeException failure) {
            request.deny();
            android.widget.Toast.makeText(activity, io.droyagent.app.NativeLocale.text("摄像头未能启动，请重新进入通话", "Camera could not start. Please rejoin the call"), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private boolean allowed(PermissionRequest request, int sessionPort, BooleanSupplier valid) {
        return !activity.isDestroyed() && valid != null && valid.getAsBoolean()
            && !ProductPermissions.stopped(activity)
            && ProductPermissions.allowed(ProductPermissions.preferences(activity), "allowAudioMicrophone")
            && NativeUiProtocol.trustedCameraRequest(request.getOrigin().toString(), sessionPort, request.getResources());
    }

    void cancelled(PermissionRequest request) {
        if (pending == request) { pending = null; current = null; }
    }

    void cancel() {
        cancelPending();
        AudioCaptureService.setCameraActive(activity, false);
    }

    private void cancelPending() {
        PermissionRequest previous = pending;
        pending = null; current = null;
        if (previous != null) previous.deny();
    }
}
