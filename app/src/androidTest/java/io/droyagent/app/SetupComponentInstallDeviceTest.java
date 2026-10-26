package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Explicit opt-in emulator test: downloads the pinned official APK and accepts Android's installer UI. */
@RunWith(AndroidJUnit4.class)
public final class SetupComponentInstallDeviceTest {
    private static final String COMPONENT = "[data-droy-setup-component=";
    private static final Set<String> INSTALLERS = Set.of("com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller");
    private static final String[] SOURCE_LABELS = {"Allow from this source", "允许来自此来源", "允许安装未知应用", "允许此来源"};
    private Instrumentation instrumentation;
    private UiAutomation automation;
    private Context context;
    private MainActivity activity;
    private DeviceUi ui;
    private SharedPreferences product, installation, wake;
    private Map<String, ?> originalProduct, originalInstallation, originalWake;
    private File home, originalHome, componentCache, originalComponentCache;
    private Map<String, String> originalFiles;
    private boolean owned, hadHome, hadComponentCache, sourceChanged, rotated, passed;

    @Before public void prepare() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Component installation requires a dedicated emulator", Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone"));
        assertTrue("Run this network/system-install test only with explicit opt-in",
            "true".equals(InstrumentationRegistry.getArguments().getString("installOfficialShizuku")));
        assertTrue("Stop tasks and the engine before component installation", RuntimeService.canManageHistory());
        assertTrue("Do not interrupt audio capture", AudioCapture.get(context).uiStatus().isNull("active"));
        assertFalse("Uninstall Shizuku on the disposable emulator before this test", SetupComponents.status(context).getBoolean("installed"));
        assertFalse("Start with Droy's unknown-source installation permission disabled",
            context.getPackageManager().canRequestPackageInstalls());
        assertFalse("Do not interrupt another component download", "downloading".equals(SetupComponents.status(context).getString("downloadState")));
        product = ProductPermissions.preferences(context);
        installation = context.getSharedPreferences("installation", Context.MODE_PRIVATE);
        wake = context.getSharedPreferences("droy-voice-wake", Context.MODE_PRIVATE);
        originalProduct = new HashMap<>(product.getAll());
        originalInstallation = new HashMap<>(installation.getAll());
        originalWake = new HashMap<>(wake.getAll());
        home = new File(context.getFilesDir(), "dsh-home");
        originalHome = new File(context.getFilesDir(), "setup-component-home-backup-" + UUID.randomUUID());
        originalFiles = snapshot(home.toPath());
        hadHome = home.exists();
        if (hadHome) assertTrue("Could not preserve the original home", home.renameTo(originalHome));
        owned = true;
        componentCache = new File(context.getCacheDir(), "setup-components");
        originalComponentCache = new File(context.getCacheDir(), "setup-component-cache-backup-" + UUID.randomUUID());
        SetupComponents.cancel(context);
        hadComponentCache = componentCache.exists();
        if (hadComponentCache) assertTrue("Could not isolate the component cache", componentCache.renameTo(originalComponentCache));
        SetupComponents.cancel(context);
        assertTrue(product.edit().clear().commit());
        assertTrue(installation.edit().clear().commit());
        SetupManager.initialize(context);
        SetupManager.update(context, new JSONObject().put("mode", "dialogue").put("step", 3).put("shizukuChoice", "skip"));
        assertTrue(product.edit().putBoolean("petEnabled", false).putString("petExternalDisplay", "off")
            .putString("petInternalDisplay", "off").putBoolean("backgroundRuntimeEnabled", false)
            .putBoolean(BackgroundRuntime.STOPPED, false).commit());
        assertTrue(wake.edit().putBoolean("enabled", false).commit());
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0)); rotated = true;
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity);
        awaitWizard();
    }

    @Test public void officialComponentUsesDownloadSourceConsentAndAndroidInstallConfirmation() throws Exception {
        assertNotInstalled("before download");
        capture("before-download");
        ui.tap(COMPONENT + "download]");
        DeviceUi.await("The pinned official component did not finish downloading", 150000, () -> {
            JSONObject state = SetupComponents.status(context);
            assertNotEquals("Official download failed: " + state.optString("error"), "failed", state.getString("downloadState"));
            return state.getString("downloadState").equals("ready");
        });
        assertNotInstalled("after download");
        awaitButton("install");
        capture("download-verified");
        ui.tap(COMPONENT + "install]");
        DeviceUi.await("The unknown-source permission page did not open", 15000, this::sourcePageVisible);
        assertFalse(context.getPackageManager().canRequestPackageInstalls());
        assertNotInstalled("after requesting unknown-source permission");
        capture("source-permission-before");
        setSourceAllowed(true);
        sourceChanged = true;
        DeviceUi.await("The source permission toggle did not take effect", 5000,
            () -> context.getPackageManager().canRequestPackageInstalls());
        assertNotInstalled("after granting unknown-source permission");
        capture("source-permission-authorized");
        systemBack();
        awaitWizard();
        awaitButton("install");
        ui.tap(COMPONENT + "install]");
        DeviceUi.await("Android's package installation confirmation did not open", 15000,
            () -> systemButtonVisible(INSTALLERS, "Install", "安装"));
        assertNotInstalled("before accepting Android's installation confirmation");
        capture("android-install-confirmation");
        tapSystemButton(INSTALLERS, "Install", "安装");
        DeviceUi.await("Android did not install the verified official component", 45000,
            () -> SetupComponents.status(context).getBoolean("installed"));
        JSONObject installed = SetupComponents.status(context);
        assertTrue("Installed Shizuku did not match the pinned official signing certificate", installed.getBoolean("trusted"));
        assertEquals("13.6.0.r1086.2650830c", installed.getString("versionName"));
        assertEquals(1086, installed.getLong("versionCode"));
        if (systemButtonVisible(INSTALLERS, "Done", "完成")) tapSystemButton(INSTALLERS, "Done", "完成");
        else if (!activity.hasWindowFocus()) systemBack();
        awaitWizard();
        awaitButton("open");
        assertFalse("Installing a component must not complete Droy setup", SetupManager.status(context).getBoolean("completed"));
        assertFalse("This test must not record a model check", SetupManager.status(context)
            .getJSONObject("modelVerification").optBoolean("passed"));
        capture("component-installed");
        JSONObject receipt = installed.put("systemSourceConsent", true).put("systemInstallConsent", true)
            .put("modelRequests", 0).put("serviceStarted", false);
        writeEvidence("installation-result.json", receipt.toString(2));
        passed = true;
    }

    @After public void restore() throws Exception {
        if (!owned) return;
        try {
            if (!passed && automation != null) {
                try { capture("failure"); }
                catch (Exception failure) { report("Could not capture component failure: " + failure.getClass().getSimpleName()); }
            }
            if (sourceChanged && context.getPackageManager().canRequestPackageInstalls()) {
                // Android can kill this UID when install-source access is revoked;
                // restore it from the host only after instrumentation has exited.
                JSONObject recovery = new JSONObject().put("recoveryNeeded", true)
                    .put("packageName", context.getPackageName()).put("permission", "REQUEST_INSTALL_PACKAGES")
                    .put("initialAllowed", false).put("currentAllowed", true)
                    .put("restoreAfterInstrumentation", true);
                writeEvidence("recovery-needed.json", recovery.toString(2));
                report("DROY_SETUP_COMPONENT_RECOVERY " + recovery);
            }
        } finally {
            try {
                SetupComponents.cancel(context);
                if (activity != null && !activity.isDestroyed()) instrumentation.runOnMainSync(activity::finish);
                instrumentation.runOnMainSync(() -> RuntimeService.requestStop(context));
                DeviceUi.await("Component test left the engine running", 25000, RuntimeService::canManageHistory);
            } finally {
                if (rotated) automation.setRotation(UiAutomation.ROTATION_UNFREEZE);
                assertTrue("Keep original data until the engine stops", RuntimeService.canManageHistory());
                HistoryData.deleteTree(home.toPath());
                if (hadHome) assertTrue("Could not restore the original home", originalHome.renameTo(home));
                assertEquals("Component test changed the original home", originalFiles, snapshot(home.toPath()));
                HistoryData.deleteTree(componentCache.toPath());
                if (hadComponentCache) assertTrue("Could not restore the component cache", originalComponentCache.renameTo(componentCache));
                restorePreferences(product, originalProduct);
                restorePreferences(installation, originalInstallation);
                restorePreferences(wake, originalWake);
                owned = false;
            }
        }
    }

    private void awaitWizard() throws Exception {
        DeviceUi.await("The installation environment page did not become ready: " + RuntimeService.status, 120000,
            () -> RuntimeService.ready && ui.web() != null && ui.nativeUiReady() && !ui.loadingOverlayShown()
                && ui.truth("document.documentElement.dataset.droyUiReady==='true'&&document.querySelector('[data-droy-setup]')?.getAttribute('data-droy-setup-step')==='3'"));
    }

    private void awaitButton(String operation) throws Exception {
        DeviceUi.await("The component " + operation + " action did not become available", 15000,
            () -> ui.truth("!!document.querySelector('" + COMPONENT + operation + "]')&&!document.querySelector('" + COMPONENT + operation + "]').disabled"));
    }

    private void assertNotInstalled(String stage) throws Exception {
        assertFalse("Installation was reported prematurely " + stage, SetupComponents.status(context).getBoolean("installed"));
    }

    private boolean sourcePageVisible() {
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            try { if (root != null && "com.android.settings".equals(String.valueOf(root.getPackageName())) && hasLabel(root, SOURCE_LABELS)) return true; }
            finally { if (root != null) root.recycle(); window.recycle(); }
        }
        return false;
    }

    private void setSourceAllowed(boolean allowed) throws Exception {
        Rect[] bounds = {null};
        DeviceUi.await("The unknown-source permission switch was not actionable", 10000, () -> {
            for (AccessibilityWindowInfo window : automation.getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                try {
                    if (root == null || !"com.android.settings".equals(String.valueOf(root.getPackageName())) || !hasLabel(root, SOURCE_LABELS)) continue;
                    AccessibilityNodeInfo toggle = checkable(root);
                    if (toggle == null) continue;
                    try {
                        if (toggle.isChecked() == allowed) return true;
                        Rect location = new Rect(); toggle.getBoundsInScreen(location);
                        if (toggle.isEnabled() && toggle.isVisibleToUser() && !location.isEmpty()) bounds[0] = location;
                    } finally { toggle.recycle(); }
                } finally { if (root != null) root.recycle(); window.recycle(); }
            }
            return bounds[0] != null;
        });
        if (bounds[0] != null) ui.tapAt(bounds[0].centerX(), bounds[0].centerY());
    }

    private boolean systemButtonVisible(Set<String> packages, String... labels) {
        return systemButtonBounds(packages, labels) != null;
    }

    private void tapSystemButton(Set<String> packages, String... labels) throws Exception {
        Rect[] target = {null};
        DeviceUi.await("The Android confirmation button was not actionable", 10000,
            () -> (target[0] = systemButtonBounds(packages, labels)) != null);
        ui.tapAt(target[0].centerX(), target[0].centerY());
    }

    private Rect systemButtonBounds(Set<String> packages, String... labels) {
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            try {
                if (root == null || root.getPackageName() == null || !packages.contains(root.getPackageName().toString())) continue;
                AccessibilityNodeInfo button = labeledButton(root, labels);
                if (button == null) continue;
                try { Rect bounds = new Rect(); button.getBoundsInScreen(bounds); if (!bounds.isEmpty()) return bounds; }
                finally { button.recycle(); }
            } finally { if (root != null) root.recycle(); window.recycle(); }
        }
        return null;
    }

    private static AccessibilityNodeInfo labeledButton(AccessibilityNodeInfo node, String... labels) {
        String text = node.getText() == null ? "" : node.getText().toString().trim();
        if (node.isVisibleToUser() && node.isEnabled() && node.isClickable())
            for (String label : labels) if (label.equalsIgnoreCase(text)) return AccessibilityNodeInfo.obtain(node);
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try { AccessibilityNodeInfo found = labeledButton(child, labels); if (found != null) return found; }
            finally { child.recycle(); }
        }
        return null;
    }

    private static AccessibilityNodeInfo checkable(AccessibilityNodeInfo node) {
        if (node.isCheckable() && node.isVisibleToUser()) return AccessibilityNodeInfo.obtain(node);
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try { AccessibilityNodeInfo found = checkable(child); if (found != null) return found; }
            finally { child.recycle(); }
        }
        return null;
    }

    private static boolean hasLabel(AccessibilityNodeInfo node, String... labels) {
        String text = node.getText() == null ? "" : node.getText().toString();
        for (String label : labels) if (text.toLowerCase(java.util.Locale.ROOT).contains(label.toLowerCase(java.util.Locale.ROOT))) return true;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            try { if (hasLabel(child, labels)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }

    private void systemBack() {
        long now = SystemClock.uptimeMillis();
        assertTrue(automation.injectInputEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true));
        assertTrue(automation.injectInputEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true));
    }

    private void capture(String stage) throws Exception {
        if (Set.of("before-download", "download-verified", "component-installed").contains(stage))
            ui.awaitPaint(".droy-setup-progress");
        else automation.waitForIdle(150, 5000);
        Bitmap bitmap = automation.takeScreenshot(); assertNotNull(bitmap);
        try {
            File directory = evidenceDirectory();
            File path = new File(directory, stage + ".png");
            try (FileOutputStream output = new FileOutputStream(path)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); }
            report("DROY_SETUP_COMPONENT_EVIDENCE " + path.getAbsolutePath());
        } finally { bitmap.recycle(); }
    }

    private File evidenceDirectory() {
        File directory = new File(context.getExternalFilesDir(null), "setup-component-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs()); return directory;
    }

    private void writeEvidence(String name, String value) throws Exception {
        Files.write(new File(evidenceDirectory(), name).toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

    private void report(String value) {
        Bundle status = new Bundle(); status.putString("stream", "\n" + value + "\n"); instrumentation.sendStatus(2, status);
    }

    private static void restorePreferences(SharedPreferences preferences, Map<String, ?> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof Boolean flag) editor.putBoolean(key, flag);
            else if (value instanceof String text) editor.putString(key, text);
            else if (value instanceof Integer number) editor.putInt(key, number);
            else if (value instanceof Long number) editor.putLong(key, number);
            else if (value instanceof Float number) editor.putFloat(key, number);
            else if (value instanceof Set<?> set) {
                Set<String> copy = new HashSet<>(); for (Object item : set) copy.add((String) item); editor.putStringSet(key, copy);
            } else throw new AssertionError("Unsupported preference type: " + key);
        }
        assertTrue(editor.commit());
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> result = new TreeMap<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return result;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                String name = root.relativize(path).toString();
                if (Files.isSymbolicLink(path)) result.put(name, "link:" + Files.readSymbolicLink(path));
                else if (Files.isDirectory(path)) result.put(name, "directory");
                else result.put(name, RuntimeInstaller.hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            }
        }
        return result;
    }
}
