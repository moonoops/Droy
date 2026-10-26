package io.droyagent.app;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.json.JSONArray;

/** Uses the same authenticated local DSH session as the main conversation. */
final class PetActions {
    private PetActions() {}

    static void send(String taskId, String action, String requestId, String optionId) throws Exception {
        send(taskId, action, requestId, optionId, null);
    }

    static void answer(String taskId, String requestId, JSONArray answers) throws Exception {
        send(taskId, "answer", requestId, null, answers);
    }

    private static void send(String taskId, String action, String requestId, String optionId, JSONArray answers) throws Exception {
        if (!RuntimeService.ready) throw new IOException(io.droyagent.app.NativeLocale.text("任务服务尚未就绪，请打开 Droy 查看", "The task service is not ready. Please open Droy"));
        JSONObject body = new JSONObject().put("taskId", taskId).put("action", action);
        if (requestId != null) body.put("requestId", requestId);
        if (optionId != null) body.put("optionId", optionId);
        if (answers != null) body.put("answers", answers);
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:"
            + RuntimeService.webPort + "/droy/pet/action").openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(2000); connection.setReadTimeout(15000);
            connection.setRequestProperty("Cookie", "droy_session=" + RuntimeService.webToken);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            try (var stream = connection.getOutputStream()) { stream.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int status = connection.getResponseCode();
            if (status == 409) throw new IOException(io.droyagent.app.NativeLocale.text("这个问题已更新，请查看当前任务", "This question has changed. Please view the current task"));
            if (status != 200) throw new IOException(io.droyagent.app.NativeLocale.text("操作未完成，请打开任务重试", "The operation did not complete. Please open the task and retry"));
            try (var stream = connection.getInputStream()) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[1024]; int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (bytes.size() + count > 16384) throw new IOException(io.droyagent.app.NativeLocale.text("任务服务返回了无效结果", "The task service returned an invalid result"));
                    bytes.write(buffer, 0, count);
                }
                JSONObject response = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                if (!response.optBoolean("ok")) throw new IOException(io.droyagent.app.NativeLocale.text("任务尚未确认此操作，请查看当前任务", "The task has not confirmed this action. Please view the current task"));
            }
        } finally { connection.disconnect(); }
    }
}
