package io.droyagent.app;

import android.content.Context;
import android.content.Intent;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import org.json.JSONObject;

/** Connects Voice's native consumers to the existing shared microphone. */
final class VoiceRuntime {
    private static final int WAKE_NOTIFICATION = 43;
    private VoiceRuntime() {}

    static JSONObject request(Context context, JSONObject args) throws Exception {
        String operation = args.optString("operation");
        if (operation.startsWith("wake.")) return VoiceWake.get(context).request(args);
        if (operation.equals("dispose")) {
            VoiceWake.get(context).request(new JSONObject().put("operation", "wake.suspend"));
            return VoiceMedia.get(context).request(args);
        }
        if (operation.equals("start")) {
            if (!args.optString("mode", "standard").equals("standard"))
                throw new IllegalStateException(io.droyagent.app.NativeLocale.text("实时音视频对话暂未开放", "Live audio/video chat is not available yet"));
            VoiceWake.get(context).claimForVoice();
            try {
                if (args.has("wakeEventId")) VoiceWake.get(context).requireStartingEvent(args.getLong("wakeEventId"));
                return VoiceMedia.get(context).request(args);
            } catch (Exception failure) {
                VoiceWake.get(context).releaseVoice();
                throw failure;
            }
        }
        if (operation.equals("stop")) {
            try { return VoiceMedia.get(context).request(args); }
            finally { VoiceWake.get(context).releaseVoice(); }
        }
        if ((operation.equals("speak") || operation.equals("play")) && args.has("wakeEventId"))
            VoiceWake.get(context).requireStartingEvent(args.getLong("wakeEventId"));
        return VoiceMedia.get(context).request(args);
    }

    static void stop(String reason) {
        VoiceWake.stopIfCreated(reason);
        VoiceMedia.stopIfCreated(reason);
    }

    static boolean active(Context context) {
        return VoiceMedia.hasActiveWork(context) || VoiceWake.hasActiveWork(context);
    }

    static void resume(Context context) {
        Context app = context.getApplicationContext();
        VoiceWake wake = VoiceWake.get(app);
        wake.setWakeListener((eventId, target) -> {
            if (!wake.validWakeEvent(eventId)) return;
            Intent open = new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("voiceWakeEventId", eventId).putExtra("voiceWakeTarget", target);
            if (!BackgroundRuntime.visible()) {
                NotificationManager manager = app.getSystemService(NotificationManager.class);
                manager.createNotificationChannel(new NotificationChannel("voice_wake", io.droyagent.app.NativeLocale.text("语音唤醒", "Voice wake-up"), NotificationManager.IMPORTANCE_DEFAULT));
                PendingIntent pending = PendingIntent.getActivity(app, WAKE_NOTIFICATION, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                manager.notify(WAKE_NOTIFICATION, new Notification.Builder(app, "voice_wake")
                    .setSmallIcon(R.drawable.ic_notification_droy).setContentTitle(io.droyagent.app.NativeLocale.text("Droy 已唤醒", "Droy is awake"))
                    .setContentText(io.droyagent.app.NativeLocale.text("点按开始交谈", "Tap to start talking")).setContentIntent(pending).setAutoCancel(true).build());
            }
            try { app.startActivity(open); }
            catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
                android.util.Log.w("DroyVoice", "Wake is waiting for the notification to be opened");
            }
        });
        wake.resumeIfEnabled();
    }

    static void clearWakeNotification(Context context) {
        context.getSystemService(NotificationManager.class).cancel(WAKE_NOTIFICATION);
    }
}
