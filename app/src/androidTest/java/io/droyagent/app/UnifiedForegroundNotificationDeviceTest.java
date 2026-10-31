package io.droyagent.app;

import android.Manifest;
import android.app.Activity;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real runtime/audio services with local microphone frames; this does not test keyword recognition. */
@RunWith(AndroidJUnit4.class)
public final class UnifiedForegroundNotificationDeviceTest {
    private static final String RECORDING_OWNER = "unified-status-recording-fixture";
    private static final String WAKE_OWNER = "droy-voice-wake";
    private static final String[] PRODUCT_KEYS = {"allowAudioMicrophone", "languagePreference"};
    private Instrumentation instrumentation;
    private UiAutomation automation;
    private Context context;
    private MainActivity activity;
    private DeviceUi ui;
    private SharedPreferences product, wakePreferences;
    private NotificationTestState saved;
    private VoiceWake wake;
    private Boolean originalWakeEnabled;
    private boolean originalWakeSuspended, ownPreferences;
    private int originalAccessibilityFlags;
    private boolean ownAccessibilityFlags;
    private final Map<String, Object> original = new HashMap<>();
    private final ArrayList<String> captureIds = new ArrayList<>();

    @Before public void prepare() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        assertTrue("Unified notification fixtures require a dedicated emulator", NotificationTestState.emulator());
        assertTrue("Stop the existing engine before this isolated fixture", RuntimeService.canManageHistory());
        assertTrue("Do not interrupt an existing capture", AudioCapture.get(context).uiStatus().isNull("active"));
        assertEquals("Grant the emulator microphone permission before running this fixture", PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
        assertEquals("Grant the emulator notification permission before running this fixture", PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS));
        product = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        for (String key : PRODUCT_KEYS) if (product.contains(key)) original.put(key, product.getAll().get(key));
        wakePreferences = context.getSharedPreferences("droy-voice-wake", Context.MODE_PRIVATE);
        originalWakeEnabled = wakePreferences.contains("enabled") ? wakePreferences.getBoolean("enabled", false) : null;
        wake = VoiceWake.get(context);
        originalWakeSuspended = wake.request(new JSONObject().put("operation", "wake.status")).getBoolean("suspended");
        ownPreferences = true;
        AccessibilityServiceInfo service = automation.getServiceInfo();
        originalAccessibilityFlags = service.flags; ownAccessibilityFlags = true;
        service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(service);
        wake.request(new JSONObject().put("operation", "wake.suspend"));
        assertTrue(wakePreferences.edit().putBoolean("enabled", false).commit());
        saved = new NotificationTestState(context); saved.prepare();
        assertTrue(product.edit().putBoolean("allowAudioMicrophone", true).putBoolean("backgroundRuntimeEnabled", true).commit());
        String locale = InstrumentationRegistry.getArguments().getString("unifiedStatusLocale", "zh");
        assertTrue("unifiedStatusLocale must be zh or en", locale.equals("zh") || locale.equals("en"));
        NativeLocale.adopt(locale);
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity); ui.awaitPage();
        DeviceUi.await("Runtime did not become ready", 30000, () -> RuntimeService.ready);
        DeviceUi.await("The runtime status notification was not published", 5000, () -> foregroundCount() == 1 && shared() != null);
    }

    @After public void restore() throws Exception {
        if (!ownPreferences) return;
        Throwable cleanupFailure = null;
        boolean stopped = false;
        for (String capture : captureIds) {
            try {
                JSONObject active = AudioCapture.get(context).uiStatus().optJSONObject("active");
                if (active != null && capture.equals(active.optString("captureId"))) {
                    AudioCapture.get(context).stopFromUi(capture);
                    DeviceUi.await("The fixture microphone was not released", 10000,
                            () -> AudioCapture.get(context).uiStatus().isNull("active"));
                }
                // Transient captures may already have been removed after finalization.
                try { AudioCapture.get(context).deleteFromUi(capture); }
                catch (IllegalArgumentException removed) { /* The owned session no longer exists. */ }
            } catch (Exception | AssertionError failure) { cleanupFailure = appendFailure(cleanupFailure, failure); }
        }
        try {
            instrumentation.runOnMainSync(RuntimeService::requestStop);
        } catch (RuntimeException | AssertionError failure) { cleanupFailure = appendFailure(cleanupFailure, failure); }
        try {
            instrumentation.runOnMainSync(() -> {
                java.util.Set<Activity> activities = new java.util.HashSet<>();
                for (Stage stage : Stage.values()) if (stage != Stage.DESTROYED)
                    activities.addAll(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage));
                for (Activity current : activities)
                    if (current instanceof MainActivity || current instanceof AudioPermissionActivity) current.finish();
                if (activity != null && !activity.isDestroyed()) activity.finish();
            });
            instrumentation.waitForIdleSync();
        } catch (RuntimeException | AssertionError failure) { cleanupFailure = appendFailure(cleanupFailure, failure); }
        try {
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("The fixture left the engine running", 20000, RuntimeService::canManageHistory);
            DeviceUi.await("The fixture left an audio capture running", 10000,
                    () -> AudioCapture.get(context).uiStatus().isNull("active"));
            DeviceUi.await("The final foreground notification remained after all services exited", 5000,
                    () -> foregroundCount() == 0 && shared() == null);
            stopped = true;
        } catch (Exception | AssertionError failure) { cleanupFailure = appendFailure(cleanupFailure, failure); }
        if (stopped) {
            try { if (saved != null) saved.restore(); }
            catch (Exception | AssertionError failure) { cleanupFailure = appendFailure(cleanupFailure, failure); }
            try {
                SharedPreferences.Editor restore = product.edit();
                for (String key : PRODUCT_KEYS) {
                    Object value = original.get(key);
                    if (value instanceof Boolean bool) restore.putBoolean(key, bool);
                    else if (value instanceof String text) restore.putString(key, text);
                    else restore.remove(key);
                }
                assertTrue("Could not restore microphone/locale preferences", restore.commit());
                SharedPreferences.Editor restoreWake = wakePreferences.edit();
                if (originalWakeEnabled == null) restoreWake.remove("enabled"); else restoreWake.putBoolean("enabled", originalWakeEnabled);
                assertTrue("Could not restore wake-up preference", restoreWake.commit());
                wake.request(new JSONObject().put("operation", originalWakeSuspended ? "wake.suspend" : "wake.resume"));
            } catch (Exception | AssertionError failure) { cleanupFailure = appendFailure(cleanupFailure, failure); }
        }
        if (ownAccessibilityFlags) {
            AccessibilityServiceInfo service = automation.getServiceInfo();
            service.flags = originalAccessibilityFlags; automation.setServiceInfo(service); ownAccessibilityFlags = false;
        }
        if (cleanupFailure != null) throw new AssertionError("Unified status cleanup failed after attempting all owned resources; preserved home was not overwritten while services ran", cleanupFailure);
    }

    private static Throwable appendFailure(Throwable previous, Throwable next) {
        if (previous == null) return next;
        if (previous != next) previous.addSuppressed(next); return previous;
    }

    @Test public void runtimeAndAudioShareOneStatusAndRecordingStopKeepsRuntime() throws Exception {
        assertShared();
        String wakeCapture = startCapture(WAKE_OWNER);
        assertShared();
        String wakeText = shared().getNotification().extras.getString(Notification.EXTRA_TEXT, "");
        assertFalse("The status still exposes technical listener errors", wakeText.contains("未连接") || wakeText.contains("disconnected"));
        captureShade("runtime-and-wake");
        verifyWakeActionLocaleAndNavigation();
        stopCapture(wakeCapture);
        DeviceUi.await("Stopping wake capture removed the running engine status", 5000,
                () -> RuntimeService.ready && foregroundCount() == 1 && shared() != null);
        assertShared();

        String voice = startCapture("droy-voice");
        assertEquals("The conversation capture used a generic recording title",
                NativeLocale.text("正在和你交谈", "Talking with you"),
                shared().getNotification().extras.getString(Notification.EXTRA_TITLE));
        captureShade("runtime-and-voice");
        touchSharedStatusAndAssertConversation();
        assertEquals("Opening the voice status interrupted capture", "recording",
                AudioCapture.get(context).status(voice, "droy-voice").getString("state"));
        stopCapture(voice);

        String recording = startCapture(RECORDING_OWNER);
        assertShared();
        Notification current = shared().getNotification();
        Notification.Action stop = null;
        if (current.actions != null) for (Notification.Action action : current.actions) {
            String title = action.title.toString();
            if (title.contains("停止") || title.startsWith("Stop")) { stop = action; break; }
        }
        assertNotNull("The shared status lost the recording stop action", stop);
        captureShade("runtime-and-recording");
        stop.actionIntent.send();
        DeviceUi.await("The real notification action did not stop microphone capture", 10000,
                () -> AudioCapture.get(context).uiStatus().isNull("active"));
        DeviceUi.await("Stopping recording removed the runtime status", 5000,
                () -> RuntimeService.ready && foregroundCount() == 1 && shared() != null);
        assertShared();
        assertTrue("Recording remained active after its notification action", !"recording".equals(
                AudioCapture.get(context).status(recording, RECORDING_OWNER).optString("state")));
    }

    @Test public void audioCanOwnStatusBeforeRuntimeJoins() throws Exception {
        instrumentation.runOnMainSync(RuntimeService::requestStop);
        DeviceUi.await("Runtime did not stop before the audio-first scenario", 15000, RuntimeService::canManageHistory);
        assertTrue("No visible activity remains to authorize microphone start", BackgroundRuntime.visible());
        String capture = startCapture(WAKE_OWNER);
        assertFalse("The audio-only scenario unexpectedly started the engine", RuntimeService.ready);
        assertShared();
        captureShade("audio-only");
        instrumentation.runOnMainSync(() -> context.startForegroundService(new Intent(context, RuntimeService.class)));
        DeviceUi.await("Runtime did not join the already running audio service", 30000, () -> RuntimeService.ready);
        assertEquals("Runtime startup interrupted the independent microphone session", "recording",
                AudioCapture.get(context).status(capture, WAKE_OWNER).getString("state"));
        assertShared();
        stopCapture(capture);
        DeviceUi.await("Audio release removed the runtime's shared status", 5000,
                () -> RuntimeService.ready && foregroundCount() == 1 && shared() != null);
        assertShared();
    }

    private void verifyWakeActionLocaleAndNavigation() throws Exception {
        String previous = NativeLocale.active();
        try {
            for (String language : new String[]{"zh", "en", "zh"}) {
                NativeLocale.adopt(language);
                AudioCaptureService.refreshLocale(context);
                String expected = language.equals("en") ? "Stop listening" : "关闭监听";
                DeviceUi.await("The active audio action did not follow the language switch: " + language, 5000,
                        () -> hasAction(expected));
                assertShared();
                captureShade("wake-language-" + language);
            }
            touchSharedStatusAndAssertConversation();
        } finally {
            NativeLocale.adopt(previous);
            AudioCaptureService.refreshLocale(context);
        }
    }

    private boolean hasAction(String expected) {
        StatusBarNotification status = shared();
        if (status == null || status.getNotification().actions == null) return false;
        for (Notification.Action action : status.getNotification().actions)
            if (expected.contentEquals(action.title)) return true;
        return false;
    }

    private void touchSharedStatusAndAssertConversation() throws Exception {
        Rect[] actual = new Rect[1];
        Instrumentation.ActivityMonitor wrongDestination = instrumentation.addMonitor(AudioPermissionActivity.class.getName(), null, false);
        Throwable primary = null;
        shell("cmd statusbar expand-notifications");
        try {
            try {
                DeviceUi.await("The status title is absent from the actual notification shade", 5000, () -> {
                    actual[0] = currentStatusBounds();
                    return actual[0] != null && !actual[0].isEmpty();
                });
            } catch (Exception | AssertionError failure) {
                try { reportShadeFailure("status-title-missing"); }
                catch (Exception | AssertionError reporting) { failure.addSuppressed(reporting); }
                throw failure;
            }
            // DeviceUi injects touchscreen MotionEvents at the live accessibility bounds.
            ui.tapAt(actual[0].exactCenterX(), actual[0].exactCenterY());
            DeviceUi.await("Touching the status did not return to the main conversation", 10000, () -> {
                boolean[] focused = new boolean[1];
                instrumentation.runOnMainSync(() -> focused[0] = activity.hasWindowFocus());
                return focused[0] && topActivity().contains("MainActivity");
            });
            assertFalse("Wake/voice status incorrectly opened recording authorization", topActivity().contains("AudioPermissionActivity"));
            assertEquals("Wake/voice status launched recording authorization before returning", 0, wrongDestination.getHits());
            assertShared();
        } catch (Exception | AssertionError failure) { primary = failure; throw failure; }
        finally {
            instrumentation.removeMonitor(wrongDestination);
            try { closeShade(); }
            catch (Exception | AssertionError closing) { if (primary == null) throw closing; else primary.addSuppressed(closing); }
        }
    }

    private java.util.List<AccessibilityNodeInfo> windowRoots() {
        java.util.List<AccessibilityNodeInfo> roots = new ArrayList<>();
        AccessibilityNodeInfo active = automation.getRootInActiveWindow();
        if (active != null) roots.add(active);
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null) roots.add(root);
        }
        return roots;
    }

    private Rect currentStatusBounds() {
        StatusBarNotification current = shared();
        if (current == null) return null;
        String title = current.getNotification().extras.getString(Notification.EXTRA_TITLE, "");
        if (title.isEmpty()) return null;
        for (AccessibilityNodeInfo root : windowRoots()) {
            if (!"com.android.systemui".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
            Rect bounds = visibleTextBounds(root, title);
            if (bounds != null) return bounds;
        }
        return null;
    }

    private void closeShade() throws Exception {
        shell("cmd statusbar collapse");
        DeviceUi.await("The notification shade did not close", 5000, () -> currentStatusBounds() == null);
    }

    private void reportShadeFailure(String phase) throws Exception {
        saveShadeScreenshot(phase);
        StatusBarNotification current = shared();
        JSONObject details = new JSONObject().put("locale", NativeLocale.active()).put("title", current == null ? ""
                : current.getNotification().extras.getString(Notification.EXTRA_TITLE, ""));
        org.json.JSONArray roots = new org.json.JSONArray();
        for (AccessibilityNodeInfo root : windowRoots()) {
            JSONObject one = new JSONObject().put("package", String.valueOf(root.getPackageName()));
            if ("com.android.systemui".contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                java.util.List<String> text = new ArrayList<>(); collectVisibleText(root, text);
                one.put("visibleText", new org.json.JSONArray(text));
            }
            roots.put(one);
        }
        details.put("roots", roots);
        Bundle result = new Bundle(); result.putString("stream", "\nDROY_UNIFIED_STATUS_FAILURE " + details + "\n");
        instrumentation.sendStatus(2, result);
    }

    private void collectVisibleText(AccessibilityNodeInfo node, java.util.List<String> text) {
        if (node == null || text.size() >= 32) return;
        if (node.isVisibleToUser() && node.getText() != null && !node.getText().toString().isBlank()) {
            String value = node.getText().toString();
            text.add(value.length() > 160 ? value.substring(0, 160) : value);
        }
        for (int i = 0; i < node.getChildCount() && text.size() < 32; i++) collectVisibleText(node.getChild(i), text);
    }

    private Rect visibleTextBounds(AccessibilityNodeInfo node, String expected) {
        if (node == null) return null;
        if (node.isVisibleToUser() && expected.contentEquals(node.getText() == null ? "" : node.getText())) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) return bounds;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            Rect bounds = visibleTextBounds(node.getChild(i), expected);
            if (bounds != null) return bounds;
        }
        return null;
    }

    private String topActivity() throws Exception {
        StringBuilder result = new StringBuilder();
        for (String line : shell("dumpsys activity activities").split("\n"))
            if (line.contains("topResumedActivity=") || line.contains("mResumedActivity:")) result.append(line.trim());
        return result.toString();
    }

    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = automation.executeShellCommand(command);
             java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        }
    }

    private String startCapture(String owner) throws Exception {
        JSONObject result = AudioCapture.get(context).start(new JSONObject().put("source", "microphone").put("saveFile", false),
                owner, UUID.randomUUID().toString());
        String id = result.getString("captureId"); captureIds.add(id);
        DeviceUi.await("The real microphone service did not start", 10000,
                () -> "recording".equals(AudioCapture.get(context).status(id, owner).optString("state")));
        DeviceUi.await("The microphone did not join the shared notification", 5000, () -> foregroundCount() == 1 && shared() != null);
        return id;
    }
    private void stopCapture(String id) throws Exception {
        AudioCapture.get(context).stopFromUi(id);
        DeviceUi.await("The real microphone service did not finish", 10000,
                () -> AudioCapture.get(context).uiStatus().isNull("active"));
    }
    private StatusBarNotification shared() {
        for (StatusBarNotification item : context.getSystemService(NotificationManager.class).getActiveNotifications())
            if (item.getTag() == null && item.getId() == ForegroundStatusNotifications.ID) return item;
        return null;
    }
    private int foregroundCount() {
        int count = 0;
        for (StatusBarNotification item : context.getSystemService(NotificationManager.class).getActiveNotifications())
            if ((item.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) count++;
        return count;
    }
    private void assertShared() {
        assertEquals("Droy has more than one foreground status notification", 1, foregroundCount());
        StatusBarNotification shared = shared(); assertNotNull("The shared status ID is absent", shared);
        assertEquals("The shared status used a different channel", ForegroundStatusNotifications.CHANNEL, shared.getNotification().getChannelId());
        assertTrue("The status is not ongoing", (shared.getNotification().flags & Notification.FLAG_ONGOING_EVENT) != 0);
        for (StatusBarNotification item : context.getSystemService(NotificationManager.class).getActiveNotifications())
            assertFalse("An old audio or location status notification remains", item.getTag() == null && (item.getId() == 41 || item.getId() == 8405));
    }
    private void captureShade(String phase) throws Exception {
        Throwable primary = null;
        shell("cmd statusbar expand-notifications");
        try {
            try { DeviceUi.await("The shared notification did not appear before its screenshot", 5000, () -> currentStatusBounds() != null); }
            catch (Exception | AssertionError failure) {
                try { reportShadeFailure("screenshot-status-missing"); }
                catch (Exception | AssertionError reporting) { failure.addSuppressed(reporting); }
                throw failure;
            }
            automation.waitForIdle(300, 3000);
            saveShadeScreenshot(phase);
        } catch (Exception | AssertionError failure) { primary = failure; throw failure; }
        finally {
            try { closeShade(); }
            catch (Exception | AssertionError closing) { if (primary == null) throw closing; else primary.addSuppressed(closing); }
        }
    }

    private void saveShadeScreenshot(String phase) throws Exception {
        Bitmap screenshot = automation.takeScreenshot(); assertNotNull(screenshot);
        File directory = context.getExternalFilesDir("unified-status-evidence"); assertNotNull(directory);
        String locale = NativeLocale.active();
        File file = new File(directory, locale + "-" + phase + "-" + SystemClock.elapsedRealtime() + ".png");
        try (FileOutputStream output = new FileOutputStream(file)) { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)); }
        finally { screenshot.recycle(); }
        Bundle result = new Bundle(); result.putString("stream", "\nDROY_UNIFIED_STATUS_SCREENSHOT " + file.getAbsolutePath() + "\n");
        instrumentation.sendStatus(2, result);
    }
}
