package io.droyagent.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** Private execution activity is independent of the user's task presentation. */
final class CompanionRuntimeActivity {
    private CompanionRuntimeActivity() {}

    static boolean busy(int port, String token) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
            "http://127.0.0.1:" + port + "/droy/companions/activity").openConnection();
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(2000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Cookie", "droy_session=" + token);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("COMPANION_ACTIVITY_UNAVAILABLE");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 4096) throw new IOException("COMPANION_ACTIVITY_INVALID");
                    output.write(buffer, 0, count);
                }
                return decode(new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8)));
            }
        } finally { connection.disconnect(); }
    }

    static boolean requestIdleShutdown(int port, String token) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
            "http://127.0.0.1:" + port + "/droy/shutdown").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(2500);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Cookie", "droy_session=" + token);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        byte[] request = "{\"ifIdle\":true}".getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(request.length);
        try {
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(request); }
            int status = connection.getResponseCode();
            if (status == 409) return false;
            if (status != 200) throw new IOException("COMPANION_ACTIVITY_UNAVAILABLE");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 4096) throw new IOException("COMPANION_ACTIVITY_INVALID");
                    output.write(buffer, 0, count);
                }
                JSONObject receipt = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
                if (!Boolean.TRUE.equals(receipt.get("ok")) || !"DROY_SHUTDOWN_READY".equals(receipt.optString("code")))
                    throw new JSONException("COMPANION_ACTIVITY_INVALID");
                return true;
            }
        } finally { connection.disconnect(); }
    }

    static boolean decode(JSONObject value) throws JSONException {
        Object busy = value.get("busy");
        if (!(busy instanceof Boolean)) throw new JSONException("COMPANION_ACTIVITY_INVALID");
        return (Boolean) busy;
    }
}
