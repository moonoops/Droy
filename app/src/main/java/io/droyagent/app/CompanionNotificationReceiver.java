package io.droyagent.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Notification actions use the same local product endpoint as an in-app read action. */
public final class CompanionNotificationReceiver extends BroadcastReceiver {
    static final String READ = "io.droyagent.READ_COMPANION_NOTIFICATION";
    static final String DISMISS = "io.droyagent.DISMISS_COMPANION_NOTIFICATION";

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction(), companion = intent.getStringExtra("companionId"), message = intent.getStringExtra("messageId");
        if ((!READ.equals(action) && !DISMISS.equals(action)) || !CompanionIdentity.isValid(companion)
            || message == null || !message.matches("[A-Za-z0-9_.:-]{1,160}")) return;
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (READ.equals(action)) markRead(companion, message);
                else ProactiveNotifications.get(app).dismissCompanion(companion, message);
            } catch (Exception failure) {
                android.util.Log.w("DroyNotifications", "COMPANION_NOTIFICATION_ACTION_FAILED: " + failure.getClass().getSimpleName());
                if (READ.equals(action)) new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app,
                    CompanionNotificationPresentation.text(app, R.string.cnotice_read_failed), Toast.LENGTH_LONG).show());
            } finally { pending.finish(); }
        }, "droy-companion-notification-action").start();
    }

    static void markRead(String companion, String message) throws Exception {
        NativeRuntimeHost.Endpoint endpoint = NativeRuntimeHost.connection();
        if (endpoint == null) throw new IOException("COMPANION_RUNTIME_UNAVAILABLE");
        HttpURLConnection connection = endpoint.open("/droy/companions/" + android.net.Uri.encode(companion) + "/read");
        try {
            connection.setRequestMethod("POST"); connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(2000); connection.setReadTimeout(5000);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8"); connection.setDoOutput(true);
            byte[] input = new JSONObject().put("messageId", message).toString().getBytes(StandardCharsets.UTF_8);
            try (var stream = connection.getOutputStream()) { stream.write(input); }
            if (connection.getResponseCode() != 200) throw new IOException("COMPANION_READ_REJECTED");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (var stream = connection.getInputStream()) {
                byte[] buffer = new byte[1024]; int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (bytes.size() + count > 16384) throw new IOException("COMPANION_READ_INVALID_RESPONSE");
                    bytes.write(buffer, 0, count);
                }
            }
            if (!new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).optBoolean("ok")) throw new IOException("COMPANION_READ_UNCONFIRMED");
        } finally { connection.disconnect(); }
    }
}
