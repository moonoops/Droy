package io.droyagent.app;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import io.droyagent.androiduse.DroyAccessibilityService;
import io.droyagent.androiduse.ShizukuAccess;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** An Activity-owned authorization queue; a user refusal advances without retrying. */
final class PermissionSetup {
    static final int RUNTIME_REQUEST = 740, SETTINGS_REQUEST = 741;
    private final Activity owner;
    private final Runnable changed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<String> pending = new ArrayList<>();
    private String current = "", waiting = "", error = "";
    private int total, completed;
    private boolean resumed, closed;
    private boolean restoredShizuku;
    private final Runnable stopShizukuListener;

    PermissionSetup(Activity owner, Runnable changed, Bundle saved) {
        this.owner = owner; this.changed = changed;
        if (saved != null) {
            ArrayList<String> restored = saved.getStringArrayList("permissionSetupPending");
            if (restored != null) pending.addAll(restored);
            current = saved.getString("permissionSetupCurrent", "");
            waiting = saved.getString("permissionSetupWaiting", "");
            error = saved.getString("permissionSetupError", "");
            total = saved.getInt("permissionSetupTotal"); completed = saved.getInt("permissionSetupCompleted");
            restoredShizuku = waiting.equals("shizuku");
        }
        stopShizukuListener = ShizukuAccess.get(owner).observePermissionResult(() -> handler.post(() -> {
            if (waiting.equals("shizuku")) finishStep();
        }));
    }

    JSONObject state() throws Exception {
        return new JSONObject().put("active", active()).put("current", current)
            .put("pending", new JSONArray(pending)).put("total", total).put("completed", completed).put("error", error);
    }

    boolean active() { return !current.isEmpty() || !pending.isEmpty(); }

    void configure(String mode) throws Exception {
        beginConfiguration(mode);
        SetupManager.update(owner, new JSONObject().put("mode", mode));
        finishConfiguration(mode);
    }

    void beginConfiguration(String mode) {
        if (!PermissionSetupPlan.validMode(mode))
            throw new IllegalArgumentException(NativeLocale.text("无效的权限配置", "Invalid permission configuration"));
        if (active() && !mode.equals("dialogue")) throw new IllegalStateException(NativeLocale.text("请先完成或停止当前授权", "Finish or stop the current authorization first"));
        if (mode.equals("dialogue")) cancel();
    }

    void finishConfiguration(String mode) throws Exception {
        NotificationManagement.get(owner).rescan();
        if (mode.equals("dialogue")) { cancel(); return; }
        Set<String> enabled = new LinkedHashSet<>();
        SharedPreferences product = ProductPermissions.preferences(owner);
        for (String key : SetupManager.capabilityKeys(owner)) if (ProductPermissions.allowed(product, key)) enabled.add(key);
        start(PermissionSetupPlan.forCapabilities(enabled, hardware(), mode.equals("all")));
    }

    void forPreference(String key) throws Exception {
        start(PermissionSetupPlan.forCapabilities(Set.of(key), hardware(), false));
    }

    void requestOrdinaryBatch(Set<String> enabled) throws Exception {
        start(PermissionSetupPlan.ordinaryForCapabilities(enabled, hardware(), true));
    }

    void forOrdinaryPreference(String key) throws Exception {
        start(PermissionSetupPlan.ordinaryForCapabilities(Set.of(key), hardware(), false));
    }

    void requestOrdinaryPermissions(Set<String> permissions) throws Exception {
        if (!PermissionSetupPlan.ordinaryPermissionNames().containsAll(permissions))
            throw new IllegalArgumentException("Invalid ordinary permission set");
        start(PermissionSetupPlan.ordinaryPermissionNames().stream().filter(permissions::contains).collect(java.util.stream.Collectors.toList()));
    }

    boolean requestIfNeeded(String name) throws Exception {
        if (runtimePermissions(name) == null && !Set.of("accessibility", "notificationAccess", "usage", "brightness", "petOverlay", "shizuku", "root").contains(name)) return false;
        if (!needed(name)) return false;
        start(List.of(name));
        return true;
    }

    private Set<String> hardware() throws Exception {
        return supportedHardware(owner);
    }

    static Set<String> supportedHardware(Context owner) throws Exception {
        JSONObject hardware = HardwarePermissionState.status(owner).getJSONObject("hardware");
        Set<String> supported = new LinkedHashSet<>();
        for (String key : List.of("wifi", "bluetooth", "telephony", "stepCounter", "heartRate"))
            if (hardware.optBoolean(key)) supported.add(key);
        if (owner.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) supported.add("camera");
        if (owner.getPackageManager().hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) supported.add("microphone");
        if (owner.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION)) supported.add("location");
        return supported;
    }

    private void start(List<String> steps) throws Exception {
        if (active()) throw new IllegalStateException(NativeLocale.text("请先完成或停止当前授权", "Finish or stop the current authorization first"));
        error = ""; completed = 0; pending.clear();
        for (String step : steps) if (needed(step)) pending.add(step);
        total = pending.size();
        changed.run(); handler.post(this::advance);
    }

    void cancel() {
        pending.clear(); current = ""; waiting = ""; error = "";
        total = 0; completed = 0; handler.removeCallbacksAndMessages(null); changed.run();
    }

    private boolean needed(String name) throws Exception {
        String[] runtime = runtimePermissions(name);
        if (runtime != null) clearGrantedRequestMarkers(runtime);
        return needsSystemPermission(owner, name);
    }

    static boolean needsSystemPermission(Context owner, String name) throws Exception {
        String[] runtime = runtimePermissions(name);
        if (runtime != null) {
            for (String permission : runtime) if (owner.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return true;
            return false;
        }
        return switch (name) {
            case "accessibility" -> !DroyAccessibilityService.isConnected(owner);
            case "notificationAccess" -> !DroyNotificationService.status(owner).optBoolean("permissionGranted");
            case "brightness" -> !Settings.System.canWrite(owner);
            case "petOverlay" -> !Settings.canDrawOverlays(owner);
            case "usage" -> {
                AppOpsManager appOps = owner.getSystemService(AppOpsManager.class);
                yield appOps == null || appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), owner.getPackageName()) != AppOpsManager.MODE_ALLOWED;
            }
            case "shizuku" -> ShizukuAccess.get(owner).status().optBoolean("running") && !ShizukuAccess.get(owner).available();
            case "root" -> hasSu() && !SetupRootVerification.status().optBoolean("passed");
            default -> throw new IllegalArgumentException("Unknown system permission");
        };
    }

    private static boolean hasSu() {
        String path = System.getenv("PATH");
        if (path != null) for (String directory : path.split(":")) if (new java.io.File(directory, "su").canExecute()) return true;
        return false;
    }

    static String[] runtimePermissions(String name) {
        return switch (name) {
            case "notifications" -> Build.VERSION.SDK_INT >= 33 ? new String[]{Manifest.permission.POST_NOTIFICATIONS} : new String[0];
            case "calendar" -> new String[]{Manifest.permission.READ_CALENDAR};
            case "contacts" -> new String[]{Manifest.permission.READ_CONTACTS};
            case "microphone" -> new String[]{Manifest.permission.RECORD_AUDIO};
            case "camera" -> new String[]{Manifest.permission.CAMERA};
            default -> HardwarePermissionState.runtimePermissions(name, Build.VERSION.SDK_INT);
        };
    }

    private void clearGrantedRequestMarkers(String[] permissions) {
        SharedPreferences saved = ProductPermissions.preferences(owner);
        SharedPreferences.Editor edit = null;
        for (String permission : permissions) {
            String key = "permissionRequested:" + permission;
            if (owner.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED && saved.contains(key)) {
                if (edit == null) edit = saved.edit();
                edit.remove(key);
            }
        }
        if (edit != null && !edit.commit())
            throw new IllegalStateException(NativeLocale.text("授权请求状态未保存，请重试", "Permission request state was not saved. Please retry"));
    }

    private void advance() {
        if (closed || !resumed || !waiting.isEmpty()) return;
        try {
            while (!pending.isEmpty()) {
                current = pending.remove(0);
                if (!needed(current)) { completed++; current = ""; continue; }
                String[] runtime = runtimePermissions(current);
                if (runtime != null) {
                    SharedPreferences requests = ProductPermissions.preferences(owner);
                    boolean blocked = false;
                    for (String permission : runtime)
                        if (owner.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
                                && requests.getBoolean("permissionRequested:" + permission, false) && !owner.shouldShowRequestPermissionRationale(permission)) blocked = true;
                    if (blocked) openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + owner.getPackageName())));
                    else {
                        SharedPreferences.Editor edit = requests.edit();
                        for (String permission : runtime) edit.putBoolean("permissionRequested:" + permission, true);
                        if (!edit.commit()) throw new IllegalStateException(NativeLocale.text("授权请求状态未保存，请重试", "Permission request state was not saved. Please retry"));
                        waiting = "runtime"; owner.requestPermissions(runtime, RUNTIME_REQUEST);
                    }
                } else if (current.equals("root")) {
                    waiting = "root"; SetupRootVerification.start(owner); handler.post(this::pollRoot);
                } else if (current.equals("shizuku")) {
                    waiting = "shizuku"; ShizukuAccess.get(owner).requestPermission();
                } else {
                    String action = switch (current) {
                        case "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS;
                        case "notificationAccess" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS;
                        case "usage" -> Settings.ACTION_USAGE_ACCESS_SETTINGS;
                        case "brightness" -> Settings.ACTION_MANAGE_WRITE_SETTINGS;
                        case "petOverlay" -> Settings.ACTION_MANAGE_OVERLAY_PERMISSION;
                        default -> throw new IllegalStateException("Unknown permission setup step");
                    };
                    Intent destination = new Intent(action);
                    if (current.equals("usage") || current.equals("brightness") || current.equals("petOverlay")) destination.setData(Uri.parse("package:" + owner.getPackageName()));
                    openSettings(destination);
                }
                changed.run(); return;
            }
            current = ""; changed.run();
        } catch (Exception failure) {
            android.util.Log.e("DroyPermissions", "Authorization setup failed at " + current, failure);
            error = NativeLocale.text("系统授权未能打开，请在下方单独配置。", "System authorization could not open. Configure the permission individually below.");
            pending.clear(); current = ""; waiting = ""; changed.run();
        }
    }

    private void openSettings(Intent destination) {
        waiting = "settings"; owner.startActivityForResult(destination, SETTINGS_REQUEST);
    }

    private void pollRoot() {
        if (closed || !waiting.equals("root")) return;
        try {
            if (SetupRootVerification.status().optBoolean("running")) handler.postDelayed(this::pollRoot, 250);
            else finishStep();
        } catch (Exception failure) {
            android.util.Log.e("DroyPermissions", "Root authorization result could not be read", failure);
            error = NativeLocale.text("Root 授权结果无法读取，请单独检查。", "Unable to read the Root authorization result. Check it individually."); finishStep();
        }
    }

    private void finishStep() {
        if (closed || current.isEmpty()) return;
        completed++; current = ""; waiting = ""; changed.run(); handler.post(this::advance);
    }

    boolean onRuntimeResult(int request, String[] permissions) {
        try {
            if (permissions != null) clearGrantedRequestMarkers(permissions);
            if (request == RUNTIME_REQUEST && waiting.equals("runtime")) finishStep();
        } catch (Exception failure) {
            android.util.Log.e("DroyPermissions", "Permission request state could not be saved", failure);
            error = NativeLocale.text("授权请求状态未保存，请重试", "Permission request state was not saved. Please retry");
            pending.clear(); current = ""; waiting = ""; changed.run();
        }
        return request == RUNTIME_REQUEST;
    }

    boolean onActivityResult(int request) {
        if (request != SETTINGS_REQUEST) return false;
        if (waiting.equals("settings")) finishStep();
        return true;
    }

    void resume() {
        resumed = true;
        // Shizuku's Binder result is not replayed after a process recreation.
        // Recheck the actual grant in the status rows instead of hanging the remaining queue.
        if (restoredShizuku) { restoredShizuku = false; finishStep(); return; }
        if (waiting.equals("shizuku")) {
            try {
                if (ShizukuAccess.get(owner).available() || !ShizukuAccess.get(owner).status().optBoolean("running")) { finishStep(); return; }
            } catch (Exception failure) {
                android.util.Log.e("DroyPermissions", "Shizuku authorization state could not be read", failure);
                error = NativeLocale.text("Shizuku 授权状态无法读取，请单独检查。", "Unable to read Shizuku authorization status. Check it individually.");
                finishStep(); return;
            }
        }
        if (waiting.equals("root")) handler.post(this::pollRoot);
        else handler.post(this::advance);
    }
    void pause() { resumed = false; }
    void save(Bundle out) {
        out.putStringArrayList("permissionSetupPending", new ArrayList<>(pending));
        out.putString("permissionSetupCurrent", current); out.putString("permissionSetupWaiting", waiting);
        out.putString("permissionSetupError", error); out.putInt("permissionSetupTotal", total); out.putInt("permissionSetupCompleted", completed);
    }
    void close() { closed = true; handler.removeCallbacksAndMessages(null); stopShizukuListener.run(); }
}
