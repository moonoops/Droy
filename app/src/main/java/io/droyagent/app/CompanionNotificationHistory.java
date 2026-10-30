package io.droyagent.app;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** The server's sequence is authoritative; timestamps only order legacy notices without a sequence. */
final class CompanionNotificationHistory {
    static final int DISPLAY_LIMIT = 10;
    private final ProactiveNoticeStore receipts;
    private final JSONObject positions;

    CompanionNotificationHistory(ProactiveNoticeStore receipts, JSONObject positions) throws Exception {
        this.receipts = receipts;
        this.positions = new JSONObject(positions.toString());
    }

    long readThrough(String companion) { return positions.optJSONObject(companion) == null ? 0 : positions.optJSONObject(companion).optLong("readThrough"); }

    void count(String companion, int count) throws Exception { position(companion).put("unreadCount", count); }

    int count(String companion) throws Exception {
        JSONObject position = positions.optJSONObject(companion);
        return position != null && position.has("unreadCount") ? position.getInt("unreadCount") : pending(companion).size();
    }

    void read(String companion, long through, Integer unread) throws Exception {
        long previous = readThrough(companion), cutoff = Math.max(previous, through);
        position(companion).put("readThrough", cutoff);
        if (unread != null && through >= previous) count(companion, unread);
        for (JSONObject record : records(companion)) if (record.optLong("sequence") <= cutoff) record.put("status", "read");
    }

    void remove(String companion, String message, Integer unread) throws Exception {
        for (JSONObject record : records(companion)) if (message.equals(record.optString("messageId"))) record.put("status", "removed");
        if (unread != null) count(companion, unread);
    }

    void dismiss(String companion, String message) throws Exception {
        List<JSONObject> rows = pending(companion);
        int index = -1;
        for (int i = 0; i < rows.size(); i++) if (message.equals(rows.get(i).optString("messageId"))) index = i;
        if (index < 0) return;
        for (int i = 0; i <= index; i++) rows.get(i).put("status", "dismissed");
    }

    List<JSONObject> pending(String companion) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        long read = readThrough(companion);
        for (JSONObject record : records(companion)) {
            String status = record.optString("status");
            if (!java.util.Set.of("published", "dispatching", "unknown").contains(status) || record.optString("text").isEmpty()) continue;
            if (record.optLong("sequence") > 0 && record.optLong("sequence") <= read) continue;
            result.add(record);
        }
        result.sort(Comparator.comparingLong((JSONObject value) -> value.optLong("sequence") > 0 ? value.optLong("sequence") : value.optLong("sentAt")));
        return result;
    }

    List<JSONObject> recent(String companion) throws Exception {
        List<JSONObject> pending = pending(companion);
        return pending.subList(Math.max(0, pending.size() - DISPLAY_LIMIT), pending.size());
    }

    void clear(String companion) throws Exception { positions.remove(companion); receipts.removeCompanion(companion); }

    JSONObject snapshot() throws Exception { return new JSONObject(positions.toString()); }

    private JSONObject position(String companion) throws Exception {
        JSONObject position = positions.optJSONObject(companion);
        if (position == null) { position = new JSONObject(); positions.put(companion, position); }
        return position;
    }

    private List<JSONObject> records(String companion) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        JSONArray saved = receipts.snapshot();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject row = saved.getJSONObject(i);
            if (row.optString("channel").equals("companion") && row.optString("companionId").equals(companion))
                result.add(receipts.find(row.getString("noticeId")));
        }
        return result;
    }
}
