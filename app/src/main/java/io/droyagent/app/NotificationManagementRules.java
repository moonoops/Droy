package io.droyagent.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

final class NotificationManagementRules {
    static final Set<String> ACTIONS = Set.of("retain", "clear", "collect", "notify");
    private static final Set<String> APP_MODES = Set.of("managed", "allow", "block");

    static JSONObject configuration(JSONObject source) throws Exception {
        JSONObject config = source == null ? new JSONObject() : new JSONObject(source.toString());
        boolean oldSelection = config.has("packages");
        config.remove("packages");
        config.put("revision", config.optLong("revision", 1) + (oldSelection ? 1 : 0));
        if (!config.has("rules")) config.put("rules", new JSONArray());
        if (!config.has("cloudAnalysisEnabled")) config.put("cloudAnalysisEnabled", true);
        if (!config.has("reminderMinutes")) config.put("reminderMinutes", 5);
        reminderMinutes(config.get("reminderMinutes"));
        if (!config.has("exceptions")) config.put("exceptions", new JSONObject());
        JSONObject exceptions = config.getJSONObject("exceptions");
        for (java.util.Iterator<String> names = exceptions.keys(); names.hasNext(); ) {
            String name = names.next(); packageName(name); appMode(exceptions.get(name));
        }
        return config;
    }

    static String appMode(Object value) {
        if (!(value instanceof String mode) || !APP_MODES.contains(mode)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("通知方式无效", "Invalid notification mode"));
        return (String) value;
    }

    static JSONObject exceptions(JSONArray source) throws Exception {
        if (source.length() > 500) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("例外应用过多", "Too many app exceptions"));
        JSONObject result = new JSONObject();
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.getJSONObject(i);
            String name = packageName(item.get("packageName")), mode = appMode(item.get("mode"));
            if (!mode.equals("managed")) result.put(name, mode);
        }
        return result;
    }

    static boolean eligibleApplication(String name, int uid, boolean system, boolean launchable,
                                       String owner, Set<String> protectedApps) {
        int appId = uid % 100000;
        return appId >= 10000 && appId <= 19999 && !name.equals(owner) && !DroyPackages.contains(name) && !protectedApps.contains(name)
                && (!system || launchable);
    }

    static String packageName(Object value) {
        if (!(value instanceof String) || !((String) value).matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("应用无效", "Invalid app"));
        return (String) value;
    }

    static JSONObject rule(JSONObject source) throws Exception {
        String id = source.optString("id", UUID.randomUUID().toString());
        if (!id.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("规则编号无效", "Invalid rule ID"));
        String fallback = source.optString("defaultAction", "analyze");
        if (!ACTIONS.contains(fallback) && !fallback.equals("analyze")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("规则处理方式无效", "Invalid rule action"));
        String app = source.optString("packageName");
        JSONObject result = new JSONObject().put("id", id).put("packageName", app.equals("*") ? app : packageName(source.get("packageName")))
                .put("instruction", text(source.get("instruction"), 2000, false)).put("defaultAction", fallback)
                .put("repeatMinutes", integer(source, "repeatMinutes", 0, 1440))
                .put("clearAfterHours", integer(source, "clearAfterHours", 0, 720));
        JSONArray entries = source.optJSONArray("entries"), normalized = new JSONArray();
        if (entries != null) {
            if (entries.length() > 32) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("规则条件过多", "Too many rule conditions"));
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                String action = entry.getString("action");
                if (!ACTIONS.contains(action)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("规则处理方式无效", "Invalid rule action"));
                JSONArray any = terms(entry.optJSONArray("containsAny")), all = terms(entry.optJSONArray("containsAll"));
                if (any.length() + all.length() == 0) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("规则条件不能为空", "Rule conditions cannot be empty"));
                normalized.put(new JSONObject().put("action", action).put("containsAny", any).put("containsAll", all));
            }
        }
        return result.put("entries", normalized);
    }

    static JSONObject decision(JSONObject source) throws Exception {
        String action = source.getString("action");
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("通知处理方式无效", "Invalid notification handling mode"));
        JSONObject result = new JSONObject().put("action", action);
        for (String name : new String[]{"title", "text", "groupKey"})
            if (source.has(name)) result.put(name, text(source.get(name), name.equals("text") ? 1000 : 160, true));
        result.put("repeatMinutes", integer(source, "repeatMinutes", 0, 1440));
        result.put("clearAfterHours", integer(source, "clearAfterHours", 0, 720));
        for (String name : new String[]{"important", "advertisement"}) {
            if (!source.has(name)) continue;
            Object value = source.get(name);
            if (!(value instanceof Boolean) || Boolean.TRUE.equals(value)
                    && !(name.equals("important") ? action.equals("retain") : action.equals("clear")))
                throw new IllegalArgumentException(NativeLocale.text("通知分类标记无效", "Invalid notification classification flag"));
            result.put(name, value);
        }
        return result;
    }

    static int reminderMinutes(Object value) {
        if (!(value instanceof Number number) || number.doubleValue() != 5 && number.doubleValue() != 10)
            throw new IllegalArgumentException(NativeLocale.text("提醒间隔只能是 5 或 10 分钟", "Reminder delay must be 5 or 10 minutes"));
        return ((Number) value).intValue();
    }

    static long reminderAt(JSONObject item, JSONObject decision, int minutes) {
        return decision.optString("action").equals("retain") && decision.optBoolean("important")
                ? item.optLong("postedAtMs") + minutes * 60000L : 0;
    }

    static String reminderSummary(JSONObject source) {
        String title = source.optString("title").strip().replaceAll("[\\r\\n]+", " ");
        String body = source.optString("text").strip().replaceAll("[\\r\\n]+", " ");
        if (title.length() > 100) title = title.substring(0, 100) + "…";
        if (body.length() > 180) body = body.substring(0, 180) + "…";
        if (body.isEmpty() || body.equals(title)) return title;
        return title.isEmpty() ? body : title + " · " + body;
    }

    static boolean sameSource(JSONObject receipt, JSONObject source) {
        return receipt.optString("key").equals(source.optString("key"))
                && receipt.optString("fingerprint").equals(source.optString("fingerprint"))
                && receipt.optLong("postedAtMs") == source.optLong("postedAtMs");
    }

    static boolean current(long revision, long expectedRevision, String fingerprint, String expectedFingerprint,
                           boolean enabled, boolean managed, boolean silent, boolean connected, boolean paused) {
        return enabled && managed && silent && connected && !paused && revision == expectedRevision
                && fingerprint.equals(expectedFingerprint);
    }

    static boolean alreadyHandled(JSONObject receipt, JSONObject item, long revision) {
        if (!receipt.optString("key").equals(item.optString("key"))
                || !receipt.optString("fingerprint").equals(item.optString("fingerprint"))
                || receipt.optLong("postedAtMs") != item.optLong("postedAtMs")) return false;
        return receipt.optBoolean("dismissed") || receipt.optBoolean("applied") && receipt.optLong("revision") == revision;
    }

    static boolean scheduled(JSONObject receipt, long revision, boolean managed) {
        return managed && !receipt.optBoolean("dismissed") && receipt.optBoolean("applied") && receipt.optLong("revision") == revision;
    }

    static boolean obsoleteAlert(JSONObject previous, String sourceKey, String action, int nextId) {
        return previous.optString("action").equals("notify") && superseded(previous, sourceKey, nextId)
                && (!action.equals("notify") || previous.optInt("notificationId") != nextId);
    }

    static boolean superseded(JSONObject previous, String sourceKey, int nextId) {
        return previous.optString("key").equals(sourceKey) || previous.optInt("notificationId") == nextId;
    }

    static String fingerprint(JSONObject item) throws Exception {
        String content = item.getString("packageName") + "\n" + item.optString("channelId") + "\n"
                + item.optString("title") + "\n" + item.optString("text") + "\n" + item.optString("subtext");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    static String text(Object value, int max, boolean empty) {
        if (!(value instanceof String) || ((String) value).length() > max || ((String) value).indexOf('\0') >= 0
                || (!empty && ((String) value).trim().isEmpty())) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("通知内容无效", "Invalid notification content"));
        return ((String) value).trim();
    }

    private static JSONArray terms(JSONArray source) throws Exception {
        JSONArray result = new JSONArray();
        if (source == null) return result;
        if (source.length() > 32) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("规则关键词过多", "Too many rule keywords"));
        for (int i = 0; i < source.length(); i++) result.put(text(source.get(i), 160, false));
        return result;
    }

    private static int integer(JSONObject source, String name, int min, int max) throws Exception {
        if (!source.has(name)) return 0;
        Object value = source.get(name);
        if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue())
                || ((Number) value).doubleValue() != Math.rint(((Number) value).doubleValue())
                || ((Number) value).doubleValue() < min || ((Number) value).doubleValue() > max)
            throw new IllegalArgumentException(name + io.droyagent.app.NativeLocale.text(" 无效", " is invalid"));
        return ((Number) value).intValue();
    }
}
