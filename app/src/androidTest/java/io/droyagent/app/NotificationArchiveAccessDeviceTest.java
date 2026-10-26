package io.droyagent.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class NotificationArchiveAccessDeviceTest {
    @Test public void archiveReadsRespectProductPermissionAndPauseWithoutLeakingThroughMetadata() throws Exception {
        assumeTrue("Requires an emulator", Build.HARDWARE.toLowerCase(Locale.ROOT).contains("ranchu")
                || Build.HARDWARE.toLowerCase(Locale.ROOT).contains("goldfish"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = ProductPermissions.preferences(context);
        boolean hadAllowed = preferences.contains("allowNotifications"), hadStopped = preferences.contains(ProductPermissions.STOPPED);
        boolean allowed = ProductPermissions.allowed(preferences, "allowNotifications"), stopped = ProductPermissions.stopped(context);
        String token = "fictional-notification-archive-boundary-token";
        ProductBridge bridge = new ProductBridge(context, token);
        try {
            bridge.start(5000, false); bridge.policy.enable();
            preferences.edit().putBoolean("allowNotifications", true).putBoolean(ProductPermissions.STOPPED, false).commit();
            assertTrue(post(bridge, token, "archive").getBoolean("ok"));
            preferences.edit().putBoolean("allowNotifications", false).commit();
            JSONObject disabled = post(bridge, token, "archive");
            assertFalse(disabled.getBoolean("ok"));
            assertEquals("permission_revoked", disabled.getJSONObject("error").getString("reason"));
            JSONObject metadata = post(bridge, token, "get");
            assertTrue(metadata.getBoolean("ok"));
            assertFalse(metadata.getJSONObject("result").has("archive"));
            preferences.edit().putBoolean("allowNotifications", true).putBoolean(ProductPermissions.STOPPED, true).commit();
            assertEquals("operations_paused", post(bridge, token, "archive").getJSONObject("error").getString("reason"));
            preferences.edit().putBoolean(ProductPermissions.STOPPED, false).commit(); bridge.policy.stop();
            assertEquals("operations_paused", post(bridge, token, "archive").getJSONObject("error").getString("reason"));
        } finally {
            bridge.stop();
            SharedPreferences.Editor restore = preferences.edit();
            if (hadAllowed) restore.putBoolean("allowNotifications", allowed); else restore.remove("allowNotifications");
            if (hadStopped) restore.putBoolean(ProductPermissions.STOPPED, stopped); else restore.remove(ProductPermissions.STOPPED);
            restore.commit();
        }
    }

    private static JSONObject post(ProductBridge bridge, String token, String operation) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + bridge.getListeningPort() + "/notifications").openConnection();
        connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setConnectTimeout(3000); connection.setReadTimeout(5000);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        byte[] bytes = new JSONObject().put("operation", operation).toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            assertEquals(200, connection.getResponseCode());
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            }
        } finally { connection.disconnect(); }
    }
}
