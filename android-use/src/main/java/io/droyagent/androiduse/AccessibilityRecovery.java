package io.droyagent.androiduse;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

/** Startup maintenance; this is not an agent tool and never resumes paused tasks. */
public final class AccessibilityRecovery {
    private static AccessibilityRecovery instance;
    private static final String SERVICES = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
    private static final String ENABLED = Settings.Secure.ACCESSIBILITY_ENABLED;
    private final Context context;
    private final SharedPreferences preferences;
    private final Handler worker;
    private final String component, settingsPackage;
    private volatile String status = "idle";
    private volatile String failure = "";
    private long attempt;
    private boolean rootAllowed;
    private boolean repairing;
    private SwitchState lastObserved;

    private record SwitchState(String services, String master) {}
    private static final class SettingsChanged extends IOException {}

    private AccessibilityRecovery(Context supplied) {
        context = supplied.getApplicationContext();
        preferences = context.getSharedPreferences("accessibility_recovery", Context.MODE_PRIVATE);
        component = new ComponentName(context, DroyAccessibilityService.class).flattenToString();
        ComponentName settings = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).resolveActivity(context.getPackageManager());
        settingsPackage = settings == null ? "" : settings.getPackageName();
        HandlerThread thread = new HandlerThread("droy-accessibility-recovery");
        thread.start();
        worker = new Handler(thread.getLooper());
        ContentObserver observer = new ContentObserver(worker) {
            @Override public void onChange(boolean selfChange) { guarded(() -> observe()); }
        };
        context.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(SERVICES), false, observer);
        context.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(ENABLED), false, observer);
        worker.post(() -> guarded(() -> observe()));
    }

    public static synchronized AccessibilityRecovery get(Context context) {
        if (instance == null) instance = new AccessibilityRecovery(context);
        return instance;
    }

    public void onAppOpened(boolean allowRoot) {
        worker.post(() -> guarded(() -> {
            rootAllowed = allowRoot;
            if (repairing) return;
            observe();
            if (!authorized()) return;
            ensureMaintenancePermissions();
            // Read provenance after obtaining DUMP, before the first write.
            observe();
            if (!authorized()) return;
            if (!maintenanceReady()) { status = "setup_required"; return; }
            if (DroyAccessibilityService.isConnected(context)) { markReady(); return; }
            long token = ++attempt;
            status = "restoring";
            if (!enabled()) writeEnabled(true);
            repairing = true;
            waitForConnection(token, SystemClock.elapsedRealtime() + 5000, false);
        }));
    }

    void serviceConnected() { worker.post(() -> guarded(() -> observe())); }

    public boolean authorized() { return preferences.getBoolean("authorized", false); }

    public boolean enabled() {
        return enabled(readSwitchState());
    }

    private SwitchState readSwitchState() {
        return new SwitchState(serviceList(), Settings.Secure.getString(context.getContentResolver(), ENABLED));
    }

    private boolean enabled(SwitchState state) {
        return "1".equals(state.master()) && AccessibilityRecoveryRules.contains(state.services(), component);
    }

    public boolean maintenanceReady() {
        return granted(Manifest.permission.WRITE_SECURE_SETTINGS) && granted(Manifest.permission.DUMP);
    }

    public String status() { return status; }

    public JSONObject snapshot() throws JSONException {
        return new JSONObject().put("enabled", enabled()).put("authorized", authorized())
            .put("ready", maintenanceReady()).put("status", status).put("failure", failure);
    }

    private boolean granted(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private String serviceList() { return Settings.Secure.getString(context.getContentResolver(), SERVICES); }

    private void observe() throws Exception {
        SwitchState state = readSwitchState();
        String list = state.services();
        boolean switchedOn = enabled(state);
        String writer = "";
        if (!switchedOn && granted(Manifest.permission.DUMP)) {
            String dump = readSettingsAudit();
            writer = AccessibilityRecoveryRules.writer(dump, SERVICES, list);
            if (AccessibilityRecoveryRules.contains(list, component)) {
                writer = AccessibilityRecoveryRules.writer(dump, ENABLED, state.master());
            }
        }
        if (!state.equals(readSwitchState())) throw new SettingsChanged();
        lastObserved = state;
        boolean wasAuthorized = authorized();
        boolean nowAuthorized = AccessibilityRecoveryRules.authorized(wasAuthorized, switchedOn, writer, settingsPackage);
        if (nowAuthorized != wasAuthorized) {
            SharedPreferences.Editor edit = preferences.edit().putBoolean("authorized", nowAuthorized);
            if (nowAuthorized) edit.remove("rootAttempted");
            if (!edit.commit()) throw new IOException("Accessibility authorization could not be saved");
        }
        if (!nowAuthorized) {
            ++attempt; repairing = false;
            status = preferences.contains("authorized") ? "revoked" : "not_authorized";
            failure = "";
        } else if (!repairing) {
            if (DroyAccessibilityService.isConnected(context)) markReady();
            else status = maintenanceReady() ? switchedOn ? "disconnected" : "pending_restore" : "setup_required";
        }
    }

    private String readSettingsAudit() throws Exception {
        // Filter inside the device process: unrelated secure settings never enter
        // the app's diagnostics or command output buffer. Android UID user stride is 100000.
        int user = android.os.Process.myUid() / 100000;
        String command = "/system/bin/dumpsys -t 3 settings | /system/bin/sed -n '/^SECURE SETTINGS (user "
            + user + ")$/,/^SYSTEM SETTINGS /{/ name:enabled_accessibility_services /p; / name:accessibility_enabled /p;}'";
        CommandRunner.Result result = CommandRunner.run(List.of("/system/bin/sh", "-c", command), 65536, 4500, () -> {});
        if (result.exitCode != 0) throw new IOException("Accessibility settings audit failed");
        String output = new String(result.stdout, StandardCharsets.UTF_8);
        if (!output.contains(" name:" + SERVICES + " ")) throw new IOException("Accessibility settings audit unavailable");
        return output;
    }

    private void ensureMaintenancePermissions() throws Exception {
        if (maintenanceReady()) return;
        status = "setup_required";
        if (!rootAllowed || preferences.getBoolean("rootAttempted", false)) return;
        if (!preferences.edit().putBoolean("rootAttempted", true).commit()) throw new IOException("Root setup state could not be saved");
        String pkg = context.getPackageName();
        if (!pkg.matches("[A-Za-z0-9_.]+")) throw new IllegalStateException("Invalid application package");
        int user = android.os.Process.myUid() / 100000;
        String command = "/system/bin/pm grant --user " + user + " " + pkg + " android.permission.WRITE_SECURE_SETTINGS"
            + " && /system/bin/pm grant --user " + user + " " + pkg + " android.permission.DUMP";
        CommandRunner.Result result = CommandRunner.run(List.of("su", "-c", command), 1024, 12000, () -> {});
        if (result.exitCode != 0 || !maintenanceReady()) {
            failure = "maintenance_permission_denied";
            status = "setup_required";
        }
    }

    private void writeEnabled(boolean on) throws IOException {
        SwitchState current = readSwitchState();
        if (!Objects.equals(current, lastObserved)) throw new SettingsChanged();
        if (!authorized()) return;
        String before = current.services();
        String after = AccessibilityRecoveryRules.withService(before, component, on);
        if (!after.equals(before) && !Settings.Secure.putString(context.getContentResolver(), SERVICES, after))
            throw new IOException("Accessibility service setting was rejected");
        if (on && !Settings.Secure.putInt(context.getContentResolver(), ENABLED, 1))
            throw new IOException("Accessibility enable setting was rejected");
        String actual = serviceList();
        for (String other : AccessibilityRecoveryRules.components(before)) {
            if (!AccessibilityRecoveryRules.expanded(other).equals(component)
                && !AccessibilityRecoveryRules.contains(actual, other))
                throw new IOException("Another accessibility service changed during recovery");
        }
        if (AccessibilityRecoveryRules.contains(actual, component) != on)
            throw new IOException("Accessibility service setting was not confirmed");
        lastObserved = readSwitchState();
    }

    private void waitForConnection(long token, long deadline, boolean rebound) {
        worker.postDelayed(() -> guarded(() -> {
            if (token != attempt || !authorized()) return;
            observe();
            if (token != attempt || !authorized()) return;
            if (DroyAccessibilityService.isConnected(context)) {
                repairing = false; markReady(); return;
            }
            if (SystemClock.elapsedRealtime() < deadline) {
                waitForConnection(token, deadline, rebound); return;
            }
            if (rebound) { repairing = false; status = "disconnected"; failure = "service_not_connected"; return; }
            // An enabled but crashed service can require one off/on cycle.
            // Only our component is changed; the process-wide observer stays active.
            writeEnabled(false);
            worker.postDelayed(() -> guarded(() -> {
                if (token != attempt || !authorized()) return;
                observe();
                if (token != attempt || !authorized()) return;
                writeEnabled(true);
                waitForConnection(token, SystemClock.elapsedRealtime() + 5000, true);
            }), 500);
        }), 150);
    }

    private interface Operation { void run() throws Exception; }
    private void markReady() { status = "ready"; failure = ""; }
    private void guarded(Operation operation) { guarded(operation, 2); }
    private void guarded(Operation operation, int retries) {
        try { operation.run(); }
        catch (SettingsChanged changed) {
            if (retries > 0) worker.postDelayed(() -> guarded(operation, retries - 1), 150);
            else { repairing = false; ++attempt; failure = "settings_changed"; status = "error"; }
        }
        catch (Exception error) {
            repairing = false; ++attempt;
            failure = error.getClass().getSimpleName();
            status = maintenanceReady() ? "error" : "setup_required";
            android.util.Log.w("DroyAccessibility", "Accessibility recovery failed: " + failure);
        }
    }
}
