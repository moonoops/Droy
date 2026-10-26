package io.droyagent.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Saves only settings this feature owns and restores them without replacing later user changes. */
final class NotificationChannelControl {
    private final Context context;
    private final SharedPreferences saved;
    private final Map<String, Boolean> silent = new HashMap<>();
    private volatile Map<String, Boolean> silentSnapshot = Map.of();
    private volatile JSONObject status = new JSONObject();
    private volatile Process runningProcess;

    NotificationChannelControl(Context context) { this.context = context.getApplicationContext(); saved = context.getSharedPreferences("notification-channels", Context.MODE_PRIVATE); }

    Boolean packageSilent(String packageName) { return silentSnapshot.get(packageName); }
    JSONObject status() { return status; }

    void cancelCurrent() {
        Process process = runningProcess;
        if (process != null) process.destroy();
    }

    synchronized JSONObject synchronize(Map<String, String> modes, boolean enabled) {
        return synchronize(modes, enabled, () -> true);
    }

    synchronized JSONObject synchronize(Map<String, String> modes, boolean enabled, java.util.function.BooleanSupplier policyCurrent) {
        int changed = 0, restored = 0;
        try {
            JSONObject originals = new JSONObject(saved.getString("originals", "{}"));
            JSONObject packageOriginals = new JSONObject(saved.getString("packages", "{}"));
            if (!enabled && originals.length() == 0 && packageOriginals.length() == 0) return result("idle", 0, 0, "");
            if (!ProductPermissions.allowed(ProductPermissions.preferences(context), "allowRoot"))
                return result("root_required", 0, 0, io.droyagent.app.NativeLocale.text("需要 Root 授权以自动设置静默", "Root authorization is required to set silent mode automatically"));
            Map<String, Integer> apps = new HashMap<>();
            for (String name : modes.keySet()) addApp(apps, name);
            for (String key : keys(originals)) addApp(apps, originals.getJSONObject(key).getString("packageName"));
            for (String name : keys(packageOriginals)) addApp(apps, name);
            JSONArray requested = new JSONArray();
            for (Map.Entry<String, Integer> app : apps.entrySet()) requested.put(new JSONObject().put("packageName", app.getKey()).put("uid", app.getValue()));
            if (!policyCurrent.getAsBoolean()) return result("superseded", 0, 0, "");
            if (!ProductPermissions.allowed(ProductPermissions.preferences(context), "allowRoot"))
                return result("root_required", 0, 0, io.droyagent.app.NativeLocale.text("需要 Root 授权以自动设置静默", "Root authorization is required to set silent mode automatically"));
            JSONObject read = run(new JSONObject().put("operation", "read").put("packages", requested));
            JSONArray changes = new JSONArray(), packageChanges = new JSONArray();
            Set<String> restoring = new HashSet<>(), restoringPackages = new HashSet<>();
            JSONObject next = new JSONObject(originals.toString()), nextPackages = new JSONObject(packageOriginals.toString());
            Set<String> seen = new HashSet<>(), readPackages = new HashSet<>(); silent.clear();
            JSONArray packages = read.getJSONArray("packages");
            for (int i = 0; i < packages.length(); i++) {
                JSONObject app = packages.getJSONObject(i); String name = app.getString("packageName");
                int uid = app.getInt("uid"); readPackages.add(name);
                boolean controlled = enabled && modes.containsKey(name);
                boolean shouldSilence = controlled && !modes.get(name).equals("allow");
                boolean currentEnabled = app.getBoolean("enabled"), permissionRequested = requestsNotificationPermission(name);
                boolean cannotNotify = !currentEnabled && !permissionRequested;
                JSONArray channels = app.getJSONArray("channels"); boolean allQuiet = true;
                for (int j = 0; j < channels.length(); j++) {
                    JSONObject item = channels.getJSONObject(j); String id = item.getString("id"), key = name + "\n" + id;
                    seen.add(key);
                    NotificationChannel current = NotificationChannelShell.decode(item.getString("parcel"));
                    JSONObject currentState = item.getJSONObject("state"), owned = originals.optJSONObject(key);
                    if (owned != null && owned.optInt("uid") != uid) { next.remove(key); owned = null; }
                    NotificationChannel desired;
                    if (shouldSilence) {
                        if (cannotNotify) continue;
                        if (current.getImportance() == NotificationManager.IMPORTANCE_NONE) { next.remove(key); continue; }
                        if (quiet(currentState)) continue;
                        desired = NotificationChannelShell.decode(item.getString("parcel"));
                        desired.setImportance(Math.min(current.getImportance(), NotificationManager.IMPORTANCE_LOW));
                        desired.setSound(null, null); desired.enableVibration(false);
                        if (desired.canBubble()) desired.setAllowBubbles(false);
                        next.put(key, new JSONObject().put("packageName", name).put("uid", uid).put("id", id)
                                .put("original", item.getString("parcel")).put("applied", NotificationChannelShell.state(desired)));
                        allQuiet = false;
                    } else {
                        if (owned == null) continue;
                        NotificationChannel original = NotificationChannelShell.decode(owned.getString("original"));
                        JSONObject appliedState = owned.getJSONObject("applied");
                        JSONObject merged = restoredState(currentState, appliedState, NotificationChannelShell.state(original));
                        if (NotificationChannelShell.sameState(currentState, merged)) { next.remove(key); continue; }
                        desired = NotificationChannelShell.decode(item.getString("parcel"));
                        desired.setImportance(merged.getInt("importance")); desired.enableVibration(merged.getBoolean("vibration"));
                        if (java.util.Objects.equals(currentState.opt("sound"), appliedState.opt("sound")))
                            desired.setSound(original.getSound(), original.getAudioAttributes());
                        if (merged.getBoolean("bubbles") != desired.canBubble()) desired.setAllowBubbles(merged.getBoolean("bubbles"));
                        restoring.add(key);
                    }
                    changes.put(new JSONObject().put("packageName", name).put("uid", uid).put("id", id)
                            .put("expected", currentState).put("desired", NotificationChannelShell.encode(desired)));
                }
                JSONObject ownedPackage = packageOriginals.optJSONObject(name);
                if (ownedPackage != null && ownedPackage.optInt("uid") != uid) { nextPackages.remove(name); ownedPackage = null; }
                Boolean desiredEnabled = null;
                if (shouldEnable(shouldSilence, currentEnabled, permissionRequested)) {
                    desiredEnabled = true;
                    nextPackages.put(name, new JSONObject().put("uid", uid).put("original", currentEnabled).put("applied", true));
                } else if (!controlled && ownedPackage != null) {
                    if (currentEnabled == ownedPackage.getBoolean("applied")) {
                        desiredEnabled = ownedPackage.getBoolean("original"); restoringPackages.add(name);
                    } else nextPackages.remove(name);
                }
                if (desiredEnabled != null) packageChanges.put(new JSONObject().put("packageName", name).put("uid", uid)
                        .put("expected", currentEnabled).put("enabled", desiredEnabled).put("requireQuiet", shouldSilence));
                silent.put(name, shouldSilence && (cannotNotify || allQuiet && currentEnabled));
            }
            for (String key : keys(next)) {
                String name = next.getJSONObject(key).getString("packageName");
                if (!apps.containsKey(name) || readPackages.contains(name) && !seen.contains(key)) next.remove(key);
            }
            for (String name : keys(nextPackages)) if (!apps.containsKey(name)) nextPackages.remove(name);
            if (!policyCurrent.getAsBoolean()) return result("superseded", 0, 0, "");
            persist(next, nextPackages);
            Set<String> applied = new HashSet<>(), packagesApplied = new HashSet<>();
            if (changes.length() + packageChanges.length() > 0) {
                if (!policyCurrent.getAsBoolean()) return result("superseded", 0, 0, "");
                if (!ProductPermissions.allowed(ProductPermissions.preferences(context), "allowRoot"))
                    return result("root_required", 0, 0, io.droyagent.app.NativeLocale.text("需要 Root 授权以自动设置静默", "Root authorization is required to set silent mode automatically"));
                JSONObject receipt = run(new JSONObject().put("operation", "apply").put("changes", changes).put("packageChanges", packageChanges), policyCurrent);
                JSONArray keys = receipt.getJSONArray("applied");
                for (int i = 0; i < keys.length(); i++) {
                    String key = keys.getString(i); applied.add(key);
                    if (restoring.contains(key)) { next.remove(key); restored++; } else changed++;
                }
                JSONArray packageKeys = receipt.getJSONArray("packagesApplied");
                for (int i = 0; i < packageKeys.length(); i++) {
                    String name = packageKeys.getString(i); packagesApplied.add(name);
                    if (restoringPackages.contains(name)) { nextPackages.remove(name); restored++; } else changed++;
                }
                persist(next, nextPackages);
                for (String name : readPackages) {
                    boolean packageQuiet = enabled && modes.containsKey(name) && !modes.get(name).equals("allow");
                    for (int i = 0; i < changes.length(); i++) {
                        JSONObject change = changes.getJSONObject(i);
                        if (name.equals(change.getString("packageName")) && !applied.contains(name + "\n" + change.getString("id"))) packageQuiet = false;
                    }
                    for (int i = 0; i < packageChanges.length(); i++) {
                        JSONObject change = packageChanges.getJSONObject(i);
                        if (name.equals(change.getString("packageName")) && !packagesApplied.contains(name)) packageQuiet = false;
                    }
                    silent.put(name, packageQuiet);
                }
            }
            boolean complete = read.getJSONArray("failed").length() == 0 && applied.size() == changes.length() && packagesApplied.size() == packageChanges.length();
            return result(complete ? "ready" : "partial", changed, restored, complete ? "" : io.droyagent.app.NativeLocale.text("部分应用的静默设置未完成", "Silent mode setup is incomplete for some apps"));
        } catch (PolicySuperseded failure) { return result("superseded", changed, restored, ""); }
        catch (RootUnavailable failure) { return policyCurrent.getAsBoolean() ? result("root_required", changed, restored, io.droyagent.app.NativeLocale.text("需要 Root 授权以自动设置静默", "Root authorization is required to set silent mode automatically")) : result("superseded", changed, restored, ""); }
        catch (Exception failure) { return policyCurrent.getAsBoolean() ? result("failed", changed, restored, io.droyagent.app.NativeLocale.text("自动静默设置未完成", "Automatic silent mode setup is incomplete")) : result("superseded", changed, restored, ""); }
    }

    private void addApp(Map<String, Integer> apps, String name) throws Exception {
        if (name.equals(context.getPackageName()) || name.equals("android") || name.equals("com.android.systemui") || name.equals("com.android.phone")) return;
        try {
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(name, 0);
            if (app.uid >= 10000) apps.put(name, app.uid);
        } catch (android.content.pm.PackageManager.NameNotFoundException removed) { }
    }

    static boolean quiet(JSONObject state) {
        int importance = state.optInt("importance", -1);
        return importance == NotificationManager.IMPORTANCE_NONE || importance > 0 && importance <= NotificationManager.IMPORTANCE_LOW
                && state.isNull("sound") && !state.optBoolean("vibration") && !state.optBoolean("bubbles");
    }

    static boolean shouldEnable(boolean shouldSilence, boolean currentEnabled, boolean permissionRequested) {
        return shouldSilence && !currentEnabled && permissionRequested;
    }

    private boolean requestsNotificationPermission(String packageName) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 33) return true;
        String[] requested = context.getPackageManager().getPackageInfo(packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions;
        if (requested != null) for (String permission : requested)
            if (android.Manifest.permission.POST_NOTIFICATIONS.equals(permission)) return true;
        return false;
    }

    static JSONObject restoredState(JSONObject current, JSONObject applied, JSONObject original) throws Exception {
        JSONObject restored = new JSONObject(current.toString());
        for (String key : new String[]{"importance", "sound", "vibration", "bubbles"})
            if (java.util.Objects.equals(current.opt(key), applied.opt(key))) restored.put(key, original.get(key));
        return restored;
    }

    private static Set<String> keys(JSONObject object) {
        Set<String> result = new HashSet<>(); java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) result.add(keys.next());
        return result;
    }

    private void persist(JSONObject originals, JSONObject packages) throws Exception {
        if (!saved.edit().putString("originals", originals.toString()).putString("packages", packages.toString()).commit()) throw new java.io.IOException("channel_settings_not_saved");
    }

    private JSONObject result(String state, int changed, int restored, String message) {
        try {
            JSONObject packages = new JSONObject();
            for (Map.Entry<String, Boolean> entry : silent.entrySet()) packages.put(entry.getKey(), entry.getValue());
            silentSnapshot = java.util.Collections.unmodifiableMap(new HashMap<>(silent));
            status = new JSONObject().put("state", state).put("changed", changed).put("restored", restored)
                    .put("packages", packages).put("message", message);
            return status;
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private JSONObject run(JSONObject input) throws Exception {
        return run(input, () -> true);
    }

    private JSONObject run(JSONObject input, java.util.function.BooleanSupplier policyCurrent) throws Exception {
        String apk = context.getApplicationInfo().sourceDir;
        String command = "CLASSPATH=" + quote(apk) + " /system/bin/app_process /system/bin io.droyagent.app.NotificationChannelShell";
        Process process;
        try { process = new ProcessBuilder("su", "-c", command).start(); }
        catch (java.io.IOException missing) { throw new RootUnavailable(); }
        runningProcess = process;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread stdout = drain(process.getInputStream(), output, failure, process);
        Thread stderr = drain(process.getErrorStream(), new ByteArrayOutputStream(), failure, process);
        try {
            if (input.getString("operation").equals("apply")) {
                if (!policyCurrent.getAsBoolean()) throw new PolicySuperseded();
                if (!ProductPermissions.allowed(ProductPermissions.preferences(context), "allowRoot")) throw new RootUnavailable();
            }
            try (java.io.OutputStream stream = process.getOutputStream()) { stream.write(input.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new java.io.IOException("channel_control_timeout");
            stdout.join(1000); stderr.join(1000);
            if (stdout.isAlive() || stderr.isAlive() || failure.get() != null) throw new java.io.IOException("channel_control_output_failed");
            JSONObject result;
            try { result = new JSONObject(output.toString(StandardCharsets.UTF_8.name())); }
            catch (org.json.JSONException invalid) {
                if (process.exitValue() != 0) throw new RootUnavailable();
                throw invalid;
            }
            if (!result.optBoolean("ok")) throw new IllegalStateException("channel_control_failed");
            if (process.exitValue() != 0) throw new java.io.IOException("channel_control_failed");
            return result;
        } finally {
            if (runningProcess == process) runningProcess = null;
            process.destroy(); if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close(); process.getErrorStream().close();
        }
    }

    private static Thread drain(InputStream input, ByteArrayOutputStream output, AtomicReference<Exception> failure, Process process) {
        Thread thread = new Thread(() -> {
            try {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 4 * 1024 * 1024) throw new java.io.IOException("channel_output_too_large");
                    output.write(buffer, 0, count);
                }
            } catch (Exception error) { failure.set(error); process.destroy(); }
        }, "droy-channel-output");
        thread.setDaemon(true); thread.start(); return thread;
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static final class RootUnavailable extends Exception { }
    private static final class PolicySuperseded extends Exception { }
}
