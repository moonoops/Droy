package io.droyagent.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.droyagent.androiduse.DroyAccessibilityService;
import io.droyagent.app.history.HistoryCapture;
import java.io.File;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Trusted native settings and runtime lease; ordinary tool grants never enable memory. */
final class MemoryRuntime {
    private static MemoryRuntime instance;
    private final Context context;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HistoryCapture history;
    private final Object lock = new Object();
    private volatile boolean runtimeActive, activated, hostReady;
    private volatile String runtimeEpoch = UUID.randomUUID().toString(), reason = "MEMORY_RUNTIME_STOPPED";
    private String challenge = "";
    private long challengeExpiresAt;
    private WeakReference<Activity> resumedActivity = new WeakReference<>(null);

    static synchronized MemoryRuntime get(Context context) {
        if (instance == null) instance = new MemoryRuntime(context.getApplicationContext());
        return instance;
    }
    private MemoryRuntime(Context context) {
        this.context = context; preferences = context.getSharedPreferences("droy_memory", Context.MODE_PRIVATE);
        history = HistoryCapture.get(context, this::captureReady);
        history.setStateListener(RuntimeService::refreshExecutionProgress);
        DroyAccessibilityService.setEventObserver(new DroyAccessibilityService.EventObserver() {
            @Override public void onConnectionChanged(boolean connected) { history.accessibilityConnectionChanged(connected); }
            @Override public void onEvent(android.view.accessibility.AccessibilityEvent event, DroyAccessibilityService service) {
                JSONObject actor = new JSONObject();
                try {
                    if (ToolProgress.screenOperationActive()) {
                        JSONObject current = ToolProgress.current(context);
                        actor.put("kind", "droy_activity").put("taskId", current.optString("taskId"));
                    }
                    history.onAccessibilityEvent(event, service, actor);
                } catch (Exception failure) { history.stop("host_observation_failed"); }
            }
        });
        // Starts deletion-only housekeeping even when every collection switch is off.
        history.start();
    }
    private boolean captureReady() {
        return executionReady() && preferences.getBoolean("enabled",false) && !ProductPermissions.stopped(context);
    }
    private boolean executionReady() { return runtimeActive && activated && hostReady; }
    JSONObject settings() throws Exception {
        return new JSONObject().put("enabled", preferences.getBoolean("enabled", false))
            .put("recallEnabled", true)
            .put("automaticExtraction", preferences.getBoolean("automaticExtraction", false))
            .put("privacyEverActivated", preferences.getBoolean("privacyEverActivated", false));
    }
    JSONObject status() throws Exception {
        return new JSONObject().put("settings", settings())
            .put("root", rootStatus())
            .put("execution", new JSONObject().put("ready", executionReady())
                .put("epoch", runtimeEpoch).put("reason", executionReady() ? "ready" : reason)
                .put("host", hostConfiguration()))
            .put("activation", new JSONObject().put("restartRequired", false)
                .put("pendingEnable", preferences.getBoolean("pendingEnable", false)))
            .put("capture", historyStatus());
    }
    private JSONObject rootStatus() throws Exception {
        return new JSONObject().put("required", false).put("authorized", false).put("checkedAt", 0)
            .put("version", "").put("reason", "not_required")
            .put("productAllowed",ProductPermissions.allowed(ProductPermissions.preferences(context),"allowRoot"));
    }
    private static void fields(JSONObject value, String... allowed) {
        Set<String> names = Set.of(allowed);
        for (Iterator<String> keys = value.keys(); keys.hasNext();) if (!names.contains(keys.next())) throw new IllegalArgumentException("MEMORY_INVALID_REQUEST");
    }
    private static boolean bool(JSONObject value, String key, boolean previous) throws Exception {
        if (!value.has(key)) return previous;
        if (!(value.get(key) instanceof Boolean selected)) throw new IllegalArgumentException("MEMORY_INVALID_SETTING");
        return selected;
    }
    JSONObject request(JSONObject input) throws Exception {
        String operation = input.getString("operation");
        switch (operation) {
            case "status": fields(input, "operation"); return status();
            case "settings": fields(input, "operation", "patch"); return saveSettings(input.getJSONObject("patch"));
            case "root-check": fields(input, "operation"); return rootStatus();
            case "activation.prepare":
                fields(input, "operation");
                try { return prepareActivation(); }
                catch (Exception failure) { invalidate("MEMORY_ACTIVATION_FAILED"); throw failure; }
            case "protection-activate":
                fields(input, "operation", "challenge", "runtimeEpoch", "qualificationId"); return activate(input);
            case "host-ready": fields(input, "operation", "runtimeEpoch"); return hostReady(input.getString("runtimeEpoch"));
            case "activation.invalidate":
                fields(input, "operation"); invalidate("MEMORY_EXECUTION_INVALIDATED"); return status();
            case "host-stop":
                fields(input, "operation"); invalidate("MEMORY_HOST_STOPPED"); return status();
            case "initialization.prepare": fields(input, "operation"); return prepareInitialization();
            case "authorization.inspect":
                fields(input, "operation");
                return new JSONObject().put("available", protectionRequired() && preferences.getBoolean("memoryStoreInitializedIntent", false))
                    .put("host", hostConfiguration());
            default: throw new IllegalArgumentException("MEMORY_UNKNOWN_OPERATION");
        }
    }
    private JSONObject saveSettings(JSONObject patch) throws Exception {
        fields(patch, "enabled", "recallEnabled", "automaticExtraction");
        boolean enabling = bool(patch, "enabled", preferences.getBoolean("enabled", false));
        boolean recall = true;
        boolean extraction = bool(patch, "automaticExtraction", preferences.getBoolean("automaticExtraction", false));
        synchronized (lock) {
            if (patch.has("enabled") && enabling && !executionReady()) throw new IllegalStateException("MEMORY_EXECUTION_NOT_READY");
            SharedPreferences.Editor edit=preferences.edit().putBoolean("enabled",enabling).putBoolean("recallEnabled",recall).putBoolean("automaticExtraction",extraction);
            if(patch.has("enabled")){edit.putBoolean("pendingEnable",false);if(!enabling)challenge="";}
            if (!edit.commit()) throw new IllegalStateException("MEMORY_SETTINGS_WRITE_FAILED");
        }
        refreshCapture(); return settings();
    }
    private JSONObject prepareActivation() throws Exception {
        synchronized (lock) {
            if (!runtimeActive) throw new IllegalStateException("MEMORY_RUNTIME_STOPPED");
            challenge = RuntimeService.randomToken();
            challengeExpiresAt = SystemClock.elapsedRealtime() + 30000;
            return new JSONObject().put("restartRequired", false).put("challenge", challenge).put("runtimeEpoch", runtimeEpoch)
                .put("expiresInMs", 30000).put("nativeQualificationId", "app-sandbox").put("host", hostConfiguration());
        }
    }
    private JSONObject activate(JSONObject input) throws Exception {
        String supplied = input.getString("challenge"), hostQualification = input.getString("qualificationId");
        if (!hostQualification.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("MEMORY_INVALID_QUALIFICATION");
        synchronized (lock) {
            if (!runtimeActive || !runtimeEpoch.equals(input.getString("runtimeEpoch"))
                || SystemClock.elapsedRealtime() >= challengeExpiresAt || challenge.isEmpty()
                || !MessageDigest.isEqual(challenge.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                challenge = ""; throw new IllegalStateException("MEMORY_ACTIVATION_EXPIRED");
            }
            challenge = "";
            if (!preferences.edit().putBoolean("privacyEverActivated", true).commit()) throw new IllegalStateException("MEMORY_SETTINGS_WRITE_FAILED");
            activated = true; hostReady = false; reason = "MEMORY_HOST_INITIALIZING";
        }
        history.refreshAuthorization(); return status();
    }
    private JSONObject hostReady(String epoch) throws Exception {
        synchronized (lock) {
            if (!runtimeActive || !activated || !runtimeEpoch.equals(epoch)) throw new IllegalStateException("MEMORY_EXECUTION_NOT_READY");
            hostReady = true; reason = "ready";
        }
        refreshCapture(); return status();
    }
    private JSONObject prepareInitialization() throws Exception {
        synchronized (lock) {
            if (!runtimeActive || !activated) throw new IllegalStateException("MEMORY_EXECUTION_NOT_READY");
            boolean initialize = !preferences.getBoolean("memoryStoreInitializedIntent", false);
            if (initialize) {
                for (String name : new String[]{"droy-memory", "droy-memory.identity.json", "droy-privacy", "droy-privacy.identity.json"})
                    if (new File(context.getFilesDir(),name).exists()) throw new IllegalStateException("MEMORY_INITIALIZATION_INCOMPLETE");
                if (!preferences.edit().putBoolean("memoryStoreInitializedIntent",true).commit()) throw new IllegalStateException("MEMORY_SETTINGS_WRITE_FAILED");
            }
            return new JSONObject().put("host",hostConfiguration()).put("directory",new File(context.getFilesDir(),"droy-memory").getAbsolutePath()).put("initialize",initialize);
        }
    }
    private JSONObject hostConfiguration() throws Exception {
        // Keep Android's per-user path; canonical user-0 paths can resolve to the /data/data alias.
        return new JSONObject().put("filesDir", context.getFilesDir().getAbsolutePath())
            .put("nativeDir", context.getApplicationInfo().nativeLibraryDir).put("appUid", android.os.Process.myUid())
            .put("memoryDirectory", new File(context.getFilesDir(), "droy-memory").getAbsolutePath())
            .put("privacyDirectory", new File(context.getFilesDir(), "droy-privacy").getAbsolutePath())
            .put("privacyMarkerPath", new File(context.getFilesDir(), "droy-privacy.identity.json").getAbsolutePath());
    }
    boolean protectionRequired() { return preferences.getBoolean("privacyEverActivated",false); }
    void prepareRuntimeStart() throws Exception {
        synchronized (lock) { activated = false; hostReady = false; challenge = ""; }
    }
    void runtimeStarted() {
        synchronized (lock) { runtimeEpoch = UUID.randomUUID().toString(); runtimeActive = true; activated = false; hostReady = false;
            challenge = ""; reason = "MEMORY_EXECUTION_NOT_READY"; }
        refreshCapture();
    }
    void runtimeStopped() {
        synchronized (lock) { runtimeActive = false; hostReady = false; runtimeEpoch = UUID.randomUUID().toString(); }
        invalidate("MEMORY_RUNTIME_STOPPED");
    }
    private void invalidate(String code) {
        synchronized (lock) { activated = false; hostReady = false; challenge = ""; reason = code;
            if (!code.equals("MEMORY_RUNTIME_STOPPED") && !code.equals("MEMORY_HOST_STOPPED") && preferences.getBoolean("pendingEnable",false))
                preferences.edit().putBoolean("pendingEnable",false).commit(); }
        history.stop("execution_unavailable"); history.refreshAuthorization();
    }
    void operationsChanged() { refreshCapture(); }
    private void refreshCapture() {
        if (captureReady()) history.start(); else history.stop("memory_ineligible");
        history.refreshAuthorization();
    }
    void activityResumed(Activity activity) {
        synchronized (lock) { resumedActivity = new WeakReference<>(activity); }
        activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        history.refreshAuthorization();
    }
    boolean ownUiVisible() { synchronized (lock) { Activity activity = resumedActivity.get(); return protectionRequired() && activity != null && !activity.isFinishing() && !activity.isDestroyed(); } }
    boolean requiresBackgroundRuntime() { return history.requiresBackgroundRuntime(); }
    void startupFailed() { invalidate("MEMORY_PROTECTED_START_FAILED"); }
    void activityPaused(Activity activity) {
        synchronized (lock) { if (resumedActivity.get() == activity) resumedActivity.clear(); }
    }
    JSONObject historyRequest(JSONObject input) throws Exception {
        String operation = input.getString("operation");
        switch (operation) {
            case "status": fields(input, "operation"); return historyStatus();
            case "configure": fields(input, "operation", "patch"); history.configure(input.getJSONObject("patch")); return historyStatus();
            case "pause": fields(input, "operation"); history.configure(new JSONObject().put("paused", true)); return historyStatus();
            case "resume": fields(input, "operation"); history.configure(new JSONObject().put("paused", false)); return historyStatus();
            case "apps": fields(input, "operation"); return apps();
            case "permission": fields(input, "operation", "source"); return permission(input.getString("source"));
            case "read": fields(input, "operation", "limit", "maxBytes"); return history.readBatch(input.optInt("limit", 64), input.optInt("maxBytes", 196608)).get(10, TimeUnit.SECONDS);
            case "ack": fields(input, "operation", "batchId"); return history.acknowledge(input.getString("batchId")).get(10, TimeUnit.SECONDS);
            default: throw new IllegalArgumentException("HISTORY_UNKNOWN_OPERATION");
        }
    }
    private JSONObject historyStatus() throws Exception {
        return history.status().put("configuration", history.configuration());
    }
    private JSONObject apps() throws Exception {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        TreeMap<String, String> installed = new TreeMap<>();
        for (var item : context.getPackageManager().queryIntentActivities(query, PackageManager.MATCH_ALL)) {
            if (item.activityInfo == null) continue;
            String name = item.activityInfo.packageName;
            if (name.equals(context.getPackageName())) continue;
            String label = String.valueOf(item.loadLabel(context.getPackageManager()));
            installed.put(name, label.length() > 128 ? label.substring(0, 128) : label);
        }
        JSONArray items = new JSONArray();
        for (var item : installed.entrySet()) { if (items.length() == 512) break; items.put(new JSONObject().put("packageName", item.getKey()).put("label", item.getValue())); }
        return new JSONObject().put("items", items).put("truncated", installed.size() > 512);
    }
    private JSONObject permission(String source) throws Exception {
        if (!Set.of("location", "backgroundLocation", "accessibility").contains(source)) throw new IllegalArgumentException("HISTORY_INVALID_PERMISSION");
        CompletableFuture<Boolean> opened = new CompletableFuture<>();
        main.post(() -> {
            Activity activity; synchronized (lock) { activity = resumedActivity.get(); }
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) { opened.completeExceptionally(new IllegalStateException("HISTORY_VISIBLE_ACTIVITY_REQUIRED")); return; }
            try { activity.startActivity(new Intent(activity, MemoryPermissionActivity.class).putExtra("source", source)); opened.complete(true); }
            catch (RuntimeException failure) { opened.completeExceptionally(failure); }
        });
        opened.get(3, TimeUnit.SECONDS);
        return new JSONObject().put("opened", true).put("source", source).put("collectionEnabled", false);
    }
}
