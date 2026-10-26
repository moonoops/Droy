package io.droyagent.app.history;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Consent for retained history is independent of permissions for immediate tools. */
public final class HistoryPolicy {
    public static final int PAGE_BYTES = 8192;
    public static final long PAGE_INTERVAL_MS = 5000;
    public static final long SPOOL_BYTES = 64L * 1024 * 1024;
    public static final long PAGE_RETENTION_MS = 48L * 60 * 60 * 1000;
    public static final long LOCATION_RETENTION_MS = 7L * 24 * 60 * 60 * 1000;
    public static final long ACTIVITY_RETENTION_MS = 30L * 24 * 60 * 60 * 1000;
    private static final Set<String> FIELDS = Set.of("enabled", "paused", "activityEnabled", "pageTextEnabled",
        "deviceStateEnabled", "locationEnabled", "allowedPackages", "excludedPackages");
    public final boolean enabled, paused, activityEnabled, pageTextEnabled, deviceStateEnabled, locationEnabled;
    public final Set<String> allowedPackages, excludedPackages;

    private HistoryPolicy(JSONObject value) throws JSONException {
        for (java.util.Iterator<String> keys = value.keys(); keys.hasNext();)
            if (!FIELDS.contains(keys.next())) throw new IllegalArgumentException("HISTORY_INVALID_SETTING");
        enabled = bool(value, "enabled"); paused = bool(value, "paused");
        activityEnabled = bool(value, "activityEnabled"); pageTextEnabled = bool(value, "pageTextEnabled");
        deviceStateEnabled = bool(value, "deviceStateEnabled"); locationEnabled = bool(value, "locationEnabled");
        allowedPackages = packages(value, "allowedPackages"); excludedPackages = packages(value, "excludedPackages");
    }

    public static HistoryPolicy defaults() { try { return new HistoryPolicy(new JSONObject()); } catch (JSONException impossible) { throw new AssertionError(impossible); } }
    public static HistoryPolicy parse(JSONObject value) throws JSONException { return new HistoryPolicy(value); }
    public HistoryPolicy patch(JSONObject patch) throws JSONException {
        JSONObject combined = toJson();
        for (java.util.Iterator<String> keys = patch.keys(); keys.hasNext();) {
            String key = keys.next(); combined.put(key, patch.get(key));
        }
        return parse(combined);
    }
    public boolean wantsCapture() { return !paused && (locationEnabled || enabled && (activityEnabled || pageTextEnabled || deviceStateEnabled)); }
    public boolean expandsConsentFrom(HistoryPolicy previous) {
        return wantsCapture() && ((enabled && !previous.enabled || previous.paused) || activityEnabled && !previous.activityEnabled
            || pageTextEnabled && !previous.pageTextEnabled || deviceStateEnabled && !previous.deviceStateEnabled
            || locationEnabled && !previous.locationEnabled || !previous.allowedPackages.containsAll(allowedPackages)
            || !excludedPackages.containsAll(previous.excludedPackages));
    }
    public boolean allowsPackage(String name, String ownPackage) {
        return name != null && !name.equals(ownPackage) && !name.equals("com.android.systemui")
            && !name.equals("android") && !name.contains("permissioncontroller")
            && allowedPackages.contains(name) && !excludedPackages.contains(name);
    }
    public boolean allowsPage(String name, String ownPackage, boolean unlocked, boolean inputMethod, boolean browser) {
        // Browser private mode/origin cannot be reliably established from a generic window event.
        return enabled && pageTextEnabled && unlocked && !inputMethod && !browser && allowsPackage(name, ownPackage);
    }
    public JSONObject toJson() throws JSONException {
        return new JSONObject().put("enabled", enabled).put("paused", paused).put("activityEnabled", activityEnabled)
            .put("pageTextEnabled", pageTextEnabled).put("deviceStateEnabled", deviceStateEnabled)
            .put("locationEnabled", locationEnabled).put("allowedPackages", new JSONArray(allowedPackages))
            .put("excludedPackages", new JSONArray(excludedPackages));
    }
    private static boolean bool(JSONObject value, String key) throws JSONException {
        if (!value.has(key)) return false;
        Object selected = value.get(key);
        if (!(selected instanceof Boolean)) throw new IllegalArgumentException("HISTORY_INVALID_SETTING");
        return (Boolean) selected;
    }
    private static Set<String> packages(JSONObject value, String key) throws JSONException {
        if (!value.has(key)) return Set.of();
        Object selected = value.get(key);
        if (!(selected instanceof JSONArray) || ((JSONArray) selected).length() > 128) throw new IllegalArgumentException("HISTORY_INVALID_PACKAGES");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        JSONArray values = (JSONArray) selected;
        for (int i = 0; i < values.length(); i++) {
            Object item = values.get(i);
            if (!(item instanceof String) || ((String) item).length() > 255
                || !((String) item).matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"))
                throw new IllegalArgumentException("HISTORY_INVALID_PACKAGES");
            result.add((String) item);
        }
        return Set.copyOf(result);
    }
    public static String boundedUtf8(String text, int byteLimit) {
        if (text == null || byteLimit <= 0) return "";
        if (text.getBytes(StandardCharsets.UTF_8).length <= byteLimit) return text;
        int index = 0, bytes = 0;
        while (index < text.length()) {
            int code = text.codePointAt(index), count = code <= 127 ? 1 : code <= 2047 ? 2 : code <= 65535 ? 3 : 4;
            if (bytes + count > byteLimit) break;
            bytes += count; index += Character.charCount(code);
        }
        return text.substring(0, index);
    }
}
