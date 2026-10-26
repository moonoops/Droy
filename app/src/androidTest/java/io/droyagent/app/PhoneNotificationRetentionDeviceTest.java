package io.droyagent.app;

import android.Manifest;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Explicitly approved phone evaluation: existing model, fictional independent-UID sources, real wall-clock waits. */
@RunWith(AndroidJUnit4.class)
public final class PhoneNotificationRetentionDeviceTest {
    private static final String SOURCE = "io.droyagent.app.test";
    private static final String TAG = "droy-phone-retention-fixture";
    private static final String RECEIVER = SOURCE + "/io.droyagent.app.PhoneNotificationFixtureReceiver";
    private static final String[] KEYS = {"notificationManagementData", "notificationManagementEnabled",
        "backgroundRuntimeEnabled", "backgroundRuntimeStopped", "toolsStopped", "allowNotifications",
        "petEnabled", "petInternalDisplay", "petExternalDisplay", "petId"};
    private final String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final Map<String, Object> original = new HashMap<>();
    private final JSONArray outcomes = new JSONArray();
    private Instrumentation instrumentation;
    private android.app.UiAutomation automation;
    private Context context;
    private SharedPreferences preferences;
    private NotificationManagement management;
    private JSONObject originalConfig;
    private JSONArray originalRecords;
    private StatusBarNotification[] originalManaged;
    private boolean owned;
    private MainActivity activity;
    private long nextProgress;
    private String originalScreenTimeout;
    private boolean screenTimeoutOwned;

    @Test public void existingModelKeepsUsefulSourcesAndRemindsAfterFiveRealMinutes() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        assumeTrue("Explicit phone evaluation approval required", "true".equals(arguments.getString("phoneNotificationRetention")));
        instrumentation = InstrumentationRegistry.getInstrumentation();
        automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        assertFalse("Run this opt-in evaluation on the configured phone", NotificationTestState.emulator());
        assertFalse("Do not interrupt an active user task", ToolProgress.current(context).optBoolean("active"));
        JSONObject capture = AudioCapture.get(context).uiStatus().optJSONObject("active");
        assertTrue("Do not interrupt a recording or voice conversation", capture == null || "droy-voice-wake".equals(capture.optString("owner")));
        assertTrue("Notification access must already be authorized", DroyNotificationService.hasAccess(context));
        assertTrue("Droy notifications must already be authorized", context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
        assertEquals("Authorize the test APK's notification permission before this evaluation", PackageManager.PERMISSION_GRANTED,
                context.getPackageManager().checkPermission(Manifest.permission.POST_NOTIFICATIONS, SOURCE));
        assertTrue("The external companion needs the already authorized overlay permission", Settings.canDrawOverlays(context));
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        management = NotificationManagement.get(context);
        Throwable failure = null;
        try {
            originalScreenTimeout = shell("settings --user current get system screen_off_timeout").trim();
            assertTrue("Could not read the original screen timeout", originalScreenTimeout.equals("null") || originalScreenTimeout.matches("-?\\d+"));
            isolate();
            activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            DeviceUi.await("The installed runtime did not become ready", 90000, () -> RuntimeService.ready);
            long listenerDeadline = SystemClock.elapsedRealtime() + 20000;
            while (!DroyNotificationService.status(context).optBoolean("connected") && SystemClock.elapsedRealtime() < listenerDeadline)
                SystemClock.sleep(100);
            if (!DroyNotificationService.status(context).optBoolean("connected")) {
                assertTrue("Never re-enable notification access the user revoked", DroyNotificationService.hasAccess(context));
                progress("Rebinding already granted access after instrumentation force-stop", null);
                shell("cmd notification disallow_listener io.droyagent.app/io.droyagent.app.DroyNotificationService");
                shell("cmd notification allow_listener io.droyagent.app/io.droyagent.app.DroyNotificationService");
                outcomes.put(new JSONObject().put("case", "listener_recovery").put("instrumentationRebindAssisted", true));
                DeviceUi.await("The granted notification listener could not be rebound", 10000,
                        () -> DroyNotificationService.status(context).optBoolean("connected"));
            }
            verifySharedForeground();
            screenTimeoutOwned = true;
            shell("settings --user current put system screen_off_timeout 600000");
            wakeAndShowSettings();
            DeviceUi.await("The external adaptive companion is unavailable", 10000,
                    () -> PetOverlay.notificationState(context, "").optBoolean("petAvailable"));

            JSONObject ad = classify("ad", 731);
            assertEquals("The real model did not remove the explicit fictional advertisement", "clear", ad.optString("action"));
            assertTrue("The real model did not identify advertising", ad.optBoolean("advertisement"));
            DeviceUi.await("No Android receipt confirmed the advertisement cleanup", 10000,
                    () -> !sourcePresent(731) && record("ad").optLong("clearedAt") > 0);
            remember("ad", record("ad"));

            JSONObject ordinary = classify("ordinary", 732);
            assertEquals("The real model removed a useful ordinary notification", "retain", ordinary.optString("action"));
            assertFalse("The real model invented urgency for the ordinary notification", ordinary.optBoolean("important"));
            assertTrue("The useful ordinary source was removed", sourcePresent(732));
            remember("ordinary", ordinary);

            JSONObject important = classify("important", 733);
            assertEquals("The real model did not retain the urgent source in place", "retain", important.optString("action"));
            assertTrue("The real model did not recognize the urgent fictional equipment alert", important.optBoolean("important"));
            awaitRealReminder("important", 733, "companion", true);
            captureCompanion();
            assertTrue("Reminder delivery removed the important original", sourcePresent(733));
            assertFalse("Companion delivery also created a system reminder", managedReminder(record("important")));
            verifyOnceAndCancel("important", 733);

            if ("true".equals(arguments.getString("phoneNotificationSystemWait"))) {
                JSONObject locked = classify("locked", 734);
                assertEquals("The second urgent source must remain in place", "retain", locked.optString("action"));
                assertTrue("The second urgent source was not classified as important", locked.optBoolean("important"));
                restoreScreenTimeout();
                shell("input keyevent KEYCODE_SLEEP");
                DeviceUi.await("The phone did not turn its screen off", 5000,
                        () -> !context.getSystemService(PowerManager.class).isInteractive());
                awaitRealReminder("locked", 734, "notification", false);
                assertTrue("The locked-screen system reminder was not published", managedReminder(record("locked")));
                assertTrue("System reminder delivery removed the original", sourcePresent(734));
                verifyOnceAndCancel("locked", 734);
            }
            progress("Phone notification evaluation passed", null);
        } catch (Exception | AssertionError error) {
            failure = error;
            throw error;
        } finally {
            try { writeResult(failure); }
            finally {
                try { restoreScreenTimeout(); }
                finally { if (owned) restore(); }
            }
        }
    }

    private File recoveryFile() { return new File(context.getFilesDir(), "phone-notification-retention-recovery.json"); }

    private void persistRecovery() throws Exception {
        assertFalse("Recover the interrupted notification fixture before starting another", recoveryFile().exists());
        JSONObject recovery = new JSONObject().put("version", 1).put("keys", new JSONArray(KEYS))
                .put("values", new JSONObject(original)).put("config", originalConfig).put("records", originalRecords)
                .put("screenOffTimeout", originalScreenTimeout);
        android.util.AtomicFile file = new android.util.AtomicFile(recoveryFile());
        FileOutputStream output = file.startWrite();
        try { output.write(recovery.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(output); }
        catch (Exception failure) { file.failWrite(output); throw failure; }
    }

    @Test public void restoreInterruptedConfiguration() throws Exception {
        assumeTrue("Explicit interrupted-fixture recovery required", "true".equals(InstrumentationRegistry.getArguments().getString("phoneNotificationRestore")));
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        assertFalse("Recovery is for the phone evaluation", NotificationTestState.emulator());
        JSONObject recovery = new JSONObject(new String(Files.readAllBytes(recoveryFile().toPath()), StandardCharsets.UTF_8));
        assertEquals(1, recovery.getInt("version"));
        JSONArray keys = recovery.getJSONArray("keys"); JSONObject values = recovery.getJSONObject("values");
        java.util.Set<String> allowed = new java.util.HashSet<>(java.util.Arrays.asList(KEYS));
        SharedPreferences prefs = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        for (int i = 0; i < keys.length(); i++) {
            String key = keys.getString(i); assertTrue("Unexpected recovery key", allowed.contains(key));
            if (!values.has(key)) editor.remove(key);
            else if (values.get(key) instanceof Boolean value) editor.putBoolean(key, value);
            else editor.putString(key, values.getString(key));
        }
        assertTrue("Interrupted fixture preferences were not restored", editor.commit());
        NotificationManagement manager = NotificationManagement.get(context);
        synchronized (manager) {
            field("config").set(manager, NotificationManagementRules.configuration(recovery.getJSONObject("config")));
            field("records").set(manager, recovery.getJSONArray("records"));
        }
        manager.rescan();
        if (recovery.has("screenOffTimeout")) {
            String timeout = recovery.getString("screenOffTimeout");
            assertTrue("Invalid recovered screen timeout", timeout.equals("null") || timeout.matches("-?\\d+"));
            shell(timeout.equals("null") ? "settings --user current delete system screen_off_timeout"
                    : "settings --user current put system screen_off_timeout " + timeout);
        }
        assertTrue("Could not remove completed recovery checkpoint", recoveryFile().delete());
        RuntimeService.refreshNotificationStatus();
    }

    private void isolate() throws Exception {
        Map<String, ?> all = preferences.getAll();
        for (String key : KEYS) if (all.containsKey(key)) original.put(key, all.get(key));
        originalManaged = context.getSystemService(NotificationManager.class).getActiveNotifications();
        synchronized (management) {
            originalConfig = new JSONObject(((JSONObject) field("config").get(management)).toString());
            originalRecords = new JSONArray(((JSONArray) field("records").get(management)).toString());
            persistRecovery();
            owned = true;
            JSONObject temporary = NotificationManagementRules.configuration(null);
            temporary.put("revision", originalConfig.optLong("revision", 1) + 1).put("rules", new JSONArray());
            JSONObject exceptions = new JSONObject();
            JSONArray apps = management.state().getJSONArray("apps");
            boolean fixtureInstalled = false;
            for (int i = 0; i < apps.length(); i++) {
                String name = apps.getJSONObject(i).getString("packageName");
                if (name.equals(SOURCE)) fixtureInstalled = true; else exceptions.put(name, "allow");
            }
            assertTrue("The independent fixture package is not manageable", fixtureInstalled);
            temporary.put("exceptions", exceptions).put("cloudAnalysisEnabled", true).put("reminderMinutes", 5);
            field("config").set(management, temporary);
            field("records").set(management, new JSONArray());
            assertTrue("Could not save the isolated notification policy", preferences.edit()
                    .putString("notificationManagementData", new JSONObject().put("config", temporary).put("records", new JSONArray()).toString())
                    .putBoolean("notificationManagementEnabled", true).putBoolean("backgroundRuntimeStopped", false)
                    .putBoolean("toolsStopped", false).putBoolean("allowNotifications", true)
                    .putBoolean("petEnabled", true).putString("petInternalDisplay", "off")
                    .putString("petExternalDisplay", "adaptive").putString("petId", "default").commit());
        }
        management.rescan();
        BackgroundRuntime.settingsChanged(context);
    }

    private JSONObject classify(String kind, int id) throws Exception {
        progress("Classifying fictional " + kind + " notification", null);
        String reply = shell("am broadcast --include-stopped-packages -n " + RECEIVER + " --es suffix " + suffix
                + " --es kind " + kind + " --ei id " + id);
        assertTrue("The independent fixture was not posted: " + reply, reply.contains("PHONE_FIXTURE_POSTED"));
        progress(reply.trim(), null);
        DeviceUi.await("Android did not publish the independent fictional source", 5000,
                () -> sourcePresent(id) || recordOrNull(kind) != null);
        try {
            DeviceUi.await("The configured model did not classify the fictional " + kind + " source", 90000,
                    () -> recordOrNull(kind) != null && record(kind).optBoolean("applied"));
        } catch (AssertionError failure) {
            JSONObject state = management.state();
            progress("Classification diagnostics " + new JSONObject().put("sourcePresent", sourcePresent(id))
                    .put("runtimeReady", RuntimeService.ready).put("enabled", state.optBoolean("enabled"))
                    .put("connected", state.optBoolean("connected")).put("error", state.optString("error"))
                    .put("fixtureManaged", state.getJSONArray("apps").toString().contains(SOURCE))
                    .put("receiptPresent", recordOrNull(kind) != null).toString(), null);
            throw failure;
        }
        return record(kind);
    }

    private void verifySharedForeground() throws Exception {
        int count = 0;
        for (StatusBarNotification item : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if ((item.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) == 0) continue;
            count++;
            assertEquals("A second service uses a separate status notification", ForegroundStatusNotifications.ID, item.getId());
            String detail = item.getNotification().extras.getString(Notification.EXTRA_TEXT, "");
            assertFalse("Technical access failure leaked into the status", detail.contains("未连接") || detail.contains("disconnected"));
        }
        assertEquals("Exactly one foreground status must remain", 1, count);
        progress("Verified one shared foreground status", null);
    }

    private void awaitRealReminder(String kind, int id, String expected, boolean keepScreenOn) throws Exception {
        JSONObject receipt = record(kind);
        long posted = receipt.getLong("postedAtMs"), due = receipt.getLong("reminderAt");
        assertEquals("The reminder deadline is not five real minutes from publication", posted + 300000L, due);
        assertFalse("Classification delivered a premature reminder", receipt.has("remindedAt"));
        nextProgress = 0;
        long deadline = due + 120000L;
        while (System.currentTimeMillis() < deadline) {
            assertTrue("The original notification disappeared before its reminder", sourcePresent(id));
            JSONObject current = record(kind);
            if (current.has("remindedAt")) {
                assertTrue("The reminder fired before five real minutes elapsed", current.getLong("remindedAt") >= due);
                assertEquals("The phone selected the wrong reminder surface", expected, current.optString("reminderDelivery"));
                remember(kind, current);
                verifySharedForeground();
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now >= nextProgress) {
                progress("Waiting for the real five-minute " + expected + " reminder", Math.max(0, due - System.currentTimeMillis()) / 1000);
                if (keepScreenOn) shell("input keyevent KEYCODE_WAKEUP");
                nextProgress = now + 30000L;
            }
            SystemClock.sleep(200);
        }
        fail("No " + expected + " reminder arrived within two minutes of the real five-minute deadline");
    }

    private void verifyOnceAndCancel(String kind, int id) throws Exception {
        long delivered = record(kind).getLong("remindedAt");
        management.rescan();
        awaitWorker();
        assertEquals("A rescan repeated a reminder", delivered, record(kind).getLong("remindedAt"));
        assertEquals("A delivered reminder was scheduled again", 0, record(kind).getLong("reminderAt"));
        shell("am broadcast --include-stopped-packages -n " + RECEIVER + " --es suffix " + suffix + " --ei cancelId " + id);
        DeviceUi.await("Clearing the original did not cancel the reminder state", 10000,
                () -> !sourcePresent(id) && record(kind).optBoolean("dismissed") && !managedReminder(record(kind)));
    }

    private void wakeAndShowSettings() throws Exception {
        shell("input keyevent KEYCODE_WAKEUP");
        shell("wm dismiss-keyguard");
        assertFalse("Unlock the phone before the visible companion portion", context.getSystemService(KeyguardManager.class).isKeyguardLocked());
        // HyperOS suppresses receiver-only starts after repeated test installs; explicitly launch the fixture UID once.
        shell("am start -W -n " + SOURCE + "/io.droyagent.app.history.HistorySourceFixtureActivity --ei phase -1");
        shell("am start -W -a android.settings.SETTINGS");
        PetOverlay.foreground(activity, false);
        PetOverlay.refresh(context);
    }

    private JSONObject recordOrNull(String kind) throws Exception {
        synchronized (management) {
            JSONArray records = (JSONArray) field("records").get(management);
            for (int i = 0; i < records.length(); i++) {
                JSONObject item = records.getJSONObject(i);
                if (SOURCE.equals(item.optString("packageName")) && channel(kind).equals(item.optString("channelId")))
                    return new JSONObject(item.toString());
            }
        }
        return null;
    }
    private JSONObject record(String kind) throws Exception {
        JSONObject item = recordOrNull(kind); assertNotNull("The fictional receipt is absent", item); return item;
    }
    private boolean sourcePresent(int id) throws Exception {
        DroyNotificationService listener = (DroyNotificationService) field("listener").get(management);
        assertNotNull("Notification access disconnected during evaluation", listener);
        for (StatusBarNotification item : listener.getActiveNotifications())
            if (SOURCE.equals(item.getPackageName()) && TAG.equals(item.getTag()) && item.getId() == id) return true;
        return false;
    }
    private boolean managedReminder(JSONObject receipt) {
        for (StatusBarNotification item : context.getSystemService(NotificationManager.class).getActiveNotifications())
            if (NotificationManagement.TAG.equals(item.getTag()) && item.getId() == receipt.optInt("notificationId")) return true;
        return false;
    }
    private String channel(String kind) { return "droy_phone_" + suffix + "_" + kind; }
    private static Field field(String name) throws Exception {
        Field field = NotificationManagement.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private void awaitWorker() throws Exception {
        ((ExecutorService) field("worker").get(management)).submit(() -> {}).get(90, TimeUnit.SECONDS);
    }
    private String shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor descriptor = automation.executeShellCommand(command);
             java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int length;
            while ((length = input.read(bytes)) != -1) output.write(bytes, 0, length);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
    private void progress(String text, Long seconds) {
        Bundle update = new Bundle(); update.putString("stream", "\nDROY_PHONE_RETENTION " + text + (seconds == null ? "" : " (" + seconds + "s remaining)") + "\n");
        instrumentation.sendStatus(2, update);
    }
    private void remember(String kind, JSONObject item) throws Exception {
        JSONObject safe = new JSONObject().put("case", kind).put("action", item.optString("action"))
                .put("important", item.optBoolean("important")).put("advertisement", item.optBoolean("advertisement"))
                .put("postedAtMs", item.optLong("postedAtMs")).put("clearedAt", item.optLong("clearedAt"))
                .put("reminderAt", item.optLong("reminderAt")).put("remindedAt", item.optLong("remindedAt"))
                .put("delivery", item.optString("reminderDelivery"));
        if (item.optLong("remindedAt") > 0) safe.put("elapsedMs", item.optLong("remindedAt") - item.optLong("postedAtMs"));
        outcomes.put(safe);
    }

    private void captureCompanion() throws Exception {
        List<CompletableFuture<Void>> frames = new ArrayList<>();
        instrumentation.runOnMainSync(() -> {
            try {
                PetOverlay overlay = PetOverlay.get(context);
                for (String name : new String[]{"pet", "bubbleWindow"}) {
                    Field target = PetOverlay.class.getDeclaredField(name); target.setAccessible(true);
                    View view = (View) target.get(overlay);
                    assertNotNull("Missing companion window", view); assertTrue("Hidden companion window", view.isShown());
                    CompletableFuture<Void> frame = new CompletableFuture<>(); frames.add(frame);
                    Runnable committed = () -> view.post(() -> android.view.Choreographer.getInstance().postFrameCallback(first ->
                            android.view.Choreographer.getInstance().postFrameCallback(second -> frame.complete(null))));
                    if (view.isHardwareAccelerated()) view.getViewTreeObserver().registerFrameCommitCallback(committed);
                    else view.postOnAnimation(committed);
                    view.invalidate();
                }
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        CompletableFuture.allOf(frames.toArray(new CompletableFuture<?>[0])).get(3, TimeUnit.SECONDS);
        android.graphics.Rect crop = new android.graphics.Rect();
        instrumentation.runOnMainSync(() -> {
            try {
                PetOverlay overlay = PetOverlay.get(context);
                for (String name : new String[]{"pet", "bubbleWindow"}) {
                    Field target = PetOverlay.class.getDeclaredField(name); target.setAccessible(true);
                    View view = (View) target.get(overlay); assertNotNull(view); assertTrue(view.isShown());
                    int[] location = new int[2]; view.getLocationOnScreen(location);
                    crop.union(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
                }
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        Bitmap screenshot = automation.takeScreenshot(); assertNotNull(screenshot);
        assertTrue("Companion crop is outside the display", crop.intersect(0, 0, screenshot.getWidth(), screenshot.getHeight()));
        Bitmap companion = Bitmap.createBitmap(screenshot, crop.left, crop.top, crop.width(), crop.height());
        try (FileOutputStream output = new FileOutputStream(new File(directory(), "phone-companion.png"))) {
            assertTrue(companion.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { if (companion != screenshot) companion.recycle(); screenshot.recycle(); }
    }
    private File directory() {
        File directory = context.getExternalFilesDir("phone-notification-retention"); assertNotNull(directory);
        assertTrue(directory.isDirectory() || directory.mkdirs()); return directory;
    }
    private void writeResult(Throwable failure) throws Exception {
        JSONObject result = new JSONObject().put("status", failure == null ? "passed" : "failed")
                .put("failureType", failure == null ? "" : failure.getClass().getSimpleName()).put("cases", outcomes);
        Files.write(new File(directory(), "result.json").toPath(), result.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    private void restoreScreenTimeout() throws Exception {
        if (!screenTimeoutOwned) return;
        if ("null".equals(originalScreenTimeout)) shell("settings --user current delete system screen_off_timeout");
        else shell("settings --user current put system screen_off_timeout " + originalScreenTimeout);
        screenTimeoutOwned = false;
    }
    private void restore() throws Exception {
        shell("am broadcast --include-stopped-packages -n " + RECEIVER + " --es suffix " + suffix + " --ez clear true");
        synchronized (management) {
            JSONArray current = (JSONArray) field("records").get(management);
            NotificationManager notifications = context.getSystemService(NotificationManager.class);
            for (int i = 0; i < current.length(); i++) {
                JSONObject item = current.getJSONObject(i);
                if (SOURCE.equals(item.optString("packageName"))) notifications.cancel(NotificationManagement.TAG, item.optInt("notificationId"));
            }
        }
        management.request(new JSONObject().put("operation", "configure").put("enabled", false));
        awaitWorker();
        synchronized (management) {
            field("config").set(management, originalConfig);
            field("records").set(management, originalRecords);
            SharedPreferences.Editor editor = preferences.edit();
            for (String key : KEYS) {
                Object value = original.get(key);
                if (value instanceof Boolean valueBoolean) editor.putBoolean(key, valueBoolean);
                else if (value instanceof String text) editor.putString(key, text);
                else editor.remove(key);
            }
            assertTrue("Could not restore original notification and companion settings", editor.commit());
            Method refresh = NotificationManagement.class.getDeclaredMethod("refreshStatusNotification"); refresh.setAccessible(true); refresh.invoke(management);
        }
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (originalManaged != null) for (StatusBarNotification item : originalManaged)
            if (NotificationManagement.TAG.equals(item.getTag())) notifications.notify(item.getTag(), item.getId(), item.getNotification());
        PetOverlay.refresh(context);
        management.rescan();
        BackgroundRuntime.settingsChanged(context);
        awaitWorker();
        assertTrue("Could not remove the completed notification recovery checkpoint", !recoveryFile().exists() || recoveryFile().delete());
        owned = false;
    }

}
