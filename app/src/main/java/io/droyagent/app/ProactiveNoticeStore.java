package io.droyagent.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

/** Small durable receipts keep an interrupted send from being silently replayed. */
final class ProactiveNoticeStore {
    static final int LIMIT = 200;
    private final JSONArray records;

    ProactiveNoticeStore(JSONArray saved) throws Exception {
        records = new JSONArray(saved.toString());
        for (int i = 0; i < records.length(); i++) {
            JSONObject item = records.getJSONObject(i);
            if (item.optString("status").equals("dispatching") || item.optString("status").equals("requires_action"))
                item.put("status", "unknown");
        }
        trim();
    }

    static String identity(String task, String turn, String key, String channel, String pkg, String target) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(new JSONArray()
            .put(task).put(turn).put(key).put(channel).put(pkg).put(target).toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }

    synchronized JSONObject find(String id) throws Exception {
        for (int i = 0; i < records.length(); i++) if (records.getJSONObject(i).optString("noticeId").equals(id))
            return records.getJSONObject(i);
        return null;
    }

    synchronized void removeCompanion(String companionId) throws Exception {
        for (int i = records.length() - 1; i >= 0; i--) {
            JSONObject record = records.getJSONObject(i);
            if (record.optString("channel").equals("companion") && record.optString("companionId").equals(companionId)) records.remove(i);
        }
    }

    synchronized void add(JSONObject record) throws Exception { records.put(record); trim(); }
    synchronized JSONArray snapshot() throws Exception { return new JSONArray(records.toString()); }
    private void trim() { while (records.length() > LIMIT) records.remove(0); }
}
