package io.droyagent.app.history;

import android.app.*;
import android.content.Intent;
import android.os.IBinder;
import org.json.JSONObject;

/** Visible location lifetime inside the Droy APK; never grants collection consent. */
public final class HistoryLocationService extends Service {
    private static final int NOTIFICATION = 8405;
    private static final String STOP = "io.droyagent.history.STOP_LOCATION";
    private static volatile HistoryLocationService running;

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        HistoryCapture capture = HistoryCapture.current();
        if (capture == null) { stopSelf(); return START_NOT_STICKY; }
        if (intent != null && STOP.equals(intent.getAction())) {
            try { capture.configure(new JSONObject().put("locationEnabled", false)); }
            catch (Exception failure) { capture.stop("location_stop_failed"); }
            stopSelf(); return START_NOT_STICKY;
        }
        if (!capture.wantsLocation()) { stopSelf(); return START_NOT_STICKY; }
        try {
            io.droyagent.app.ForegroundStatusNotifications.startLocation(this, stopIntent());
            running = this;
            capture.locationServiceStarted();
        } catch (RuntimeException restricted) { capture.locationServiceFailed(); stopSelf(); }
        return START_NOT_STICKY;
    }
    public static void refreshLocale(android.content.Context context) { io.droyagent.app.ForegroundStatusNotifications.refresh(context); }

    private PendingIntent stopIntent() {
        return PendingIntent.getService(this, NOTIFICATION, new Intent(this, HistoryLocationService.class).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    @Override public void onDestroy() {
        if (running == this) running = null;
        HistoryCapture capture = HistoryCapture.current();
        if (capture != null) capture.locationServiceStopped();
        io.droyagent.app.ForegroundStatusNotifications.stop(this); super.onDestroy();
    }
}
