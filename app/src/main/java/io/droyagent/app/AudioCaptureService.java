package io.droyagent.app;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/** A user-visible audio lifetime separate from the DSH engine's idle lifetime. */
public final class AudioCaptureService extends Service {
    private static final String STOP = "io.droyagent.AUDIO_STOP";
    private String captureId;
    private int latestStartId;
    private String captureOwner = "";
    private static AudioCaptureService running;
    private static boolean cameraActive;
    private boolean microphone;
    private volatile boolean foregroundStarted;

    static void refreshLocale(android.content.Context context) { ForegroundStatusNotifications.refresh(context); }

    static synchronized void setCameraActive(android.content.Context context, boolean active) {
        if (active && context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw new SecurityException(io.droyagent.app.NativeLocale.text("尚未授予摄像头权限", "Camera permission has not been granted"));
        boolean previous = cameraActive;
        cameraActive = active;
        try { if (running != null && running.captureId != null) running.refreshForeground(); }
        catch (RuntimeException failure) { cameraActive = previous; throw failure; }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }
        latestStartId = startId;
        AudioCapture audio = AudioCapture.get(this);
        String id = intent.getStringExtra("captureId");
        try {
            if (STOP.equals(intent.getAction())) {
                if (VoiceWake.stopFromNotification(captureOwner)) return START_NOT_STICKY;
                audio.stopFromUi(id); return START_NOT_STICKY;
            }
            captureId = id;
            captureOwner = intent.getStringExtra("captureOwner");
            if (captureOwner == null) captureOwner = "";
            microphone = audio.sourceForService(id).equals("microphone");
            synchronized (AudioCaptureService.class) { running = this; refreshForeground(); }
            Intent projection = intent.getParcelableExtra("projectionData");
            audio.serviceStarted(this, startId, id, intent.getIntExtra("projectionResult", 0), projection);
        } catch (Exception failure) {
            foregroundStarted = false;
            audio.serviceFailed(id, "foreground_service_failed"); ForegroundStatusNotifications.stop(this); stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void refreshForeground() {
        int type = microphone ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (microphone && cameraActive) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        ForegroundStatusNotifications.startAudio(this, type, captureId, captureOwner, microphone, cameraActive, stopIntent());
        foregroundStarted = true;
    }

    private PendingIntent stopIntent() {
        return PendingIntent.getService(this, 411,
            new Intent(this, AudioCaptureService.class).setAction(STOP).putExtra("captureId", captureId),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    void finishCapture(String id) {
        if (id.equals(captureId) && stopSelfResult(latestStartId)) {
            foregroundStarted = false; ForegroundStatusNotifications.stop(this);
        }
    }

    @Override public void onDestroy() {
        synchronized (AudioCaptureService.class) { if (running == this) { running = null; cameraActive = false; } }
        ForegroundStatusNotifications.stop(this);
        AudioCapture.get(this).serviceDestroyed(this);
        super.onDestroy();
    }
}
