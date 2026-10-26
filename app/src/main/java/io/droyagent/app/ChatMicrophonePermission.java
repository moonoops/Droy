package io.droyagent.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.webkit.PermissionRequest;
import java.util.function.BooleanSupplier;

/** A WebView grant belongs to the requesting page, not to a later navigation. */
final class ChatMicrophonePermission {
    static final int REQUEST = 71;
    private final Activity activity;
    private PermissionRequest pending;
    private BooleanSupplier current;
    private int port;

    ChatMicrophonePermission(Activity activity) { this.activity = activity; }

    void request(PermissionRequest request, int sessionPort, BooleanSupplier isCurrent) {
        cancelPending();
        if (!allowed(request, sessionPort, isCurrent)) { request.deny(); return; }
        try { AudioCapture.get(activity).claimDictation(); }
        catch (IllegalStateException | SecurityException occupied) {
            request.deny(); android.widget.Toast.makeText(activity, occupied.getMessage(), android.widget.Toast.LENGTH_LONG).show(); return;
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}); return;
        }
        pending = request; current = isCurrent; port = sessionPort;
        activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST);
    }

    boolean result(int requestCode) {
        if (requestCode != REQUEST) return false;
        PermissionRequest request = pending;
        BooleanSupplier valid = current;
        pending = null; current = null;
        if (request == null) return true;
        if (allowed(request, port, valid)
                && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        else { request.deny(); AudioCapture.get(activity).releaseDictation(); }
        return true;
    }

    private boolean allowed(PermissionRequest request, int sessionPort, BooleanSupplier valid) {
        return !activity.isDestroyed() && valid != null && valid.getAsBoolean()
            && !ProductPermissions.stopped(activity)
            && ProductPermissions.allowed(ProductPermissions.preferences(activity), "allowAudioMicrophone")
            && NativeUiProtocol.trustedMicrophoneRequest(request.getOrigin().toString(), sessionPort, request.getResources());
    }

    void cancelled(PermissionRequest request) {
        if (pending == request) { pending = null; current = null; AudioCapture.get(activity).releaseDictation(); }
    }

    void cancel() {
        cancelPending();
        AudioCapture.get(activity).releaseDictation();
    }

    private void cancelPending() {
        PermissionRequest previous = pending;
        pending = null; current = null;
        if (previous != null) previous.deny();
    }
}
