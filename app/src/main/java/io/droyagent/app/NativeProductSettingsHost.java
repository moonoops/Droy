package io.droyagent.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.util.Set;
import org.json.JSONObject;

/** Product settings service shared by native presenters. Never exposes a generic preference writer. */
public final class NativeProductSettingsHost {
    private final Context context;
    private final SharedPreferences preferences;
    public NativeProductSettingsHost(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("product", Context.MODE_PRIVATE);
    }
    public JSONObject snapshot(String section) throws Exception {
        return switch (section) {
            case "browser-network" -> new JSONObject()
                .put("browserEncryptedDns", preferences.getBoolean("browserEncryptedDns", false));
            case "general" -> new JSONObject()
                .put("browserEncryptedDns", preferences.getBoolean("browserEncryptedDns", false))
                .put("automationScreenDisplay", preferences.getBoolean("automationScreenDisplay", true))
                .put("automationScreenDisplayReady", io.droyagent.androiduse.DroyAccessibilityService.isConnected(context) || Settings.canDrawOverlays(context))
                .put("returnToDroyOnCompletion", preferences.getBoolean("returnToDroyOnCompletion", false));
            case "notifications" -> NotificationManagement.get(context).state(true);
            case "proactive" -> ProactiveNotifications.get(context).status(true);
            case "pet" -> new JSONObject().put("petEnabled", preferences.getBoolean("petEnabled", true))
                .put("petExternalDisplay", PetOverlay.displayMode(context, false))
                .put("petInternalDisplay", PetOverlay.displayMode(context, true))
                .put("petId", preferences.getString("petId", "default"))
                .put("petPermissionGranted", Settings.canDrawOverlays(context));
            case "diagnostics" -> diagnostics();
            default -> throw new IllegalArgumentException("unsupported_section");
        };
    }
    static Object checkedDisplay(String key, Object value) throws Exception {
        if (Set.of("petExternalDisplay", "petInternalDisplay").contains(key))
            return NativeUiProtocol.petDisplayArgument(new JSONObject().put("value", value));
        if (Set.of("petEnabled", "returnToDroyOnCompletion", "automationScreenDisplay", "browserEncryptedDns").contains(key))
            return NativeUiProtocol.booleanArgument(new JSONObject().put("value", value), "value");
        throw new IllegalArgumentException("unsupported_preference");
    }
    public void setDisplay(String key, Object value) throws Exception {
        Object checked = checkedDisplay(key, value);
        SharedPreferences.Editor editor = preferences.edit();
        if (checked instanceof Boolean) editor.putBoolean(key, (Boolean) checked);
        else editor.putString(key, (String) checked);
        if (!editor.commit()) throw new IllegalStateException("settings_not_saved");
        if (Set.of("petEnabled", "petExternalDisplay", "petInternalDisplay").contains(key)) PetOverlay.refresh(context);
        if (key.equals("automationScreenDisplay")) AutomationScreenOverlay.refresh(context);
    }
    static void fields(JSONObject value, Set<String> allowed) {
        for (java.util.Iterator<String> keys = value.keys(); keys.hasNext();)
            if (!allowed.contains(keys.next())) throw new IllegalArgumentException("unsupported_field");
    }
    public JSONObject notificationManagement(JSONObject request) throws Exception {
        Set<String> allowed = switch (request.getString("operation")) {
            case "configure" -> Set.of("operation", "enabled", "ruleText", "resetRule");
            case "setApps" -> Set.of("operation", "enabled", "packageNames");
            case "setAppMode" -> Set.of("operation", "packageName", "mode");
            case "deleteRule" -> Set.of("operation", "id");
            case "upsertRule" -> Set.of("operation", "rule", "expectedRevision");
            case "archive" -> Set.of("operation");
            default -> throw new IllegalArgumentException("unsupported_operation");
        };
        fields(request, allowed);
        NotificationManagement service = NotificationManagement.get(context);
        if (request.getString("operation").equals("upsertRule")) {
            // The service uses this same monitor for all config mutations, including model-created rules.
            synchronized (service) {
                JSONObject checked = prepareRule(request, service.state(false));
                return service.request(new JSONObject().put("operation", "upsertRule").put("rule", checked), true);
            }
        }
        return service.request(request, true);
    }
    public static final class RuleConflictException extends IllegalStateException {
        RuleConflictException() { super("notification_rule_conflict"); }
    }
    static JSONObject prepareRule(JSONObject request, JSONObject current) throws Exception {
        fields(request, Set.of("operation", "rule", "expectedRevision"));
        Object revision = request.get("expectedRevision");
        if (!(revision instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != Math.rint(number.doubleValue()) || number.longValue() < 1)
            throw new IllegalArgumentException("invalid_revision");
        if (number.longValue() != current.getLong("revision")) throw new RuleConflictException();
        JSONObject input = request.getJSONObject("rule");
        fields(input, Set.of("id", "packageName", "instruction", "defaultAction", "repeatMinutes", "clearAfterHours", "entries"));
        if (input.has("entries")) {
            org.json.JSONArray entries = input.getJSONArray("entries");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                fields(entry, Set.of("action", "containsAny", "containsAll"));
                if (entry.has("containsAny")) entry.getJSONArray("containsAny");
                if (entry.has("containsAll")) entry.getJSONArray("containsAll");
            }
        }
        JSONObject checked = NotificationManagementRules.rule(input);
        org.json.JSONArray rules = current.getJSONArray("rules");
        boolean foundId = false;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject existing = rules.getJSONObject(i);
            boolean sameId = input.has("id") && existing.getString("id").equals(input.getString("id"));
            foundId |= sameId;
            if (existing.getString("packageName").equals(checked.getString("packageName")) && !sameId)
                throw new RuleConflictException();
        }
        if (input.has("id") && !foundId) throw new RuleConflictException();
        return checked;
    }
    public JSONObject configureProactive(JSONObject request) throws Exception {
        fields(request, Set.of("messagesEnabled", "tasksEnabled"));
        return ProactiveNotifications.get(context).request(new JSONObject(request.toString()).put("operation", "configure"), true);
    }
    public android.view.View createPetPreview() {
        PetSpriteView preview = new PetSpriteView(context);
        preview.setContentDescription(NativeLocale.text("宠物形象", "Pet appearance"));
        preview.showState("idle", true);
        return preview;
    }
    public void selectPet(String id) throws Exception { PetOverlay.select(context, id); }
    /** Call from the Android main thread: preview owns overlay views. */
    public void previewPet() { PetOverlay.get(context).preview(); }
    public JSONObject diagnostics() throws Exception {
        JSONObject versions = new JSONObject();
        try {
            RuntimeRelease release = RuntimeInstaller.release(context);
            versions.put("droyVersion", release.droyVersion).put("dshVersion", release.dshVersion).put("mobileVersion", release.mobileVersion);
        } catch (Exception unavailable) {
            // Diagnostics remains usable when the runtime release cannot be verified; never export its exception text.
        }
        return diagnosticSnapshot(versions, android.os.Build.VERSION.RELEASE, android.os.Build.VERSION.SDK_INT,
            RuntimeService.ready, RuntimeService.isStartingOrRunning(), ProductPermissions.stopped(context),
            ToolProgress.current(context), RuntimeService.diagnosticsSnapshot());
    }
    static JSONObject diagnosticSnapshot(JSONObject versions, String androidRelease, int androidApi, boolean ready,
            boolean present, boolean paused, JSONObject progress, String rawLog) throws Exception {
        JSONObject result = new JSONObject().put("runtimeReady", ready).put("runtimePresent", present)
            .put("toolsPaused", paused).put("androidApi", androidApi);
        for (String key : new String[]{"droyVersion", "dshVersion", "mobileVersion"}) {
            Object value = versions.opt(key);
            if (value instanceof String version && version.matches("v?[0-9][0-9A-Za-z.+_-]{0,79}")) result.put(key, version);
        }
        if (androidRelease != null && androidRelease.matches("[0-9][0-9A-Za-z._-]{0,39}")) result.put("androidRelease", androidRelease);
        String phase = progress.optString("phase");
        if (Set.of("thinking", "executing", "verifying", "waiting", "waiting_input", "blocked", "completed", "interrupted").contains(phase))
            result.put("phase", phase).put("phaseActive", Boolean.TRUE.equals(progress.opt("active")));
        java.util.LinkedHashMap<String, Integer> codes = new java.util.LinkedHashMap<>();
        if (rawLog != null) {
            // Only recognized code constants can cross this boundary. Unknown text is never a fallback.
            String bounded = rawLog.substring(Math.max(0, rawLog.length() - 131072));
            String[] lines = bounded.split("\\r?\\n", 257);
            for (int i = 0; i < Math.min(256, lines.length); i++) {
                String line = lines[i];
                String code = DiagnosticCodes.fromRuntimeOutput(line.length() > 16384 ? line.substring(0, 16384) : line);
                if (code != null) codes.merge(code, 1, Integer::sum);
            }
        }
        org.json.JSONArray notices = new org.json.JSONArray();
        for (java.util.Map.Entry<String, Integer> entry : codes.entrySet())
            notices.put(new JSONObject().put("code", entry.getKey()).put("count", entry.getValue()));
        return result.put("notices", notices);
    }
}
