package io.droyagent.app;

import android.Manifest;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real listener callbacks and a separate posting UID, with fictional emulator notifications. */
@RunWith(AndroidJUnit4.class)
public final class NotificationManagementDeviceTest {
    private static final String SOURCE = InstrumentationRegistry.getInstrumentation().getContext().getPackageName();
    private static final String SOURCE_TAG = "droy-notification-fixture";
    private static final String RECEIVER = SOURCE + "/io.droyagent.app.NotificationManagementDeviceTest$FixtureReceiver";
    private Instrumentation instrumentation;
    private Context context;
    private NativeAppActivity activity;
    private NotificationTestState saved;
    private boolean listenerWasGranted, sourceNotificationsGranted, targetNotificationsGranted;
    private final String fixtureSuffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final String silentChannel = "droy_fixture_silent_" + fixtureSuffix;
    private final String highChannel = "droy_fixture_high_" + fixtureSuffix;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Notification fixtures require a dedicated emulator", NotificationTestState.emulator());
        assertTrue("Stop existing tasks before notification tests", RuntimeService.canManageHistory());
        saved = new NotificationTestState(context);
        saved.prepare();
        File home = new File(context.getFilesDir(), "dsh-home");
        File profile = new File(home, "profiles/mobile");
        assertTrue(profile.isDirectory() || profile.mkdirs());
        File provider = new File(home, "notification-organizer-provider.mjs");
        try (InputStream input = instrumentation.getContext().getAssets().open("notification-organizer-provider.mjs")) {
            java.nio.file.Files.copy(input, provider.toPath());
        }
        java.nio.file.Files.writeString(new File(profile, "cordis.patch.yml").toPath(), new JSONArray()
                .put(new JSONObject().put("id", "agent-default-model").put("config", new JSONObject()
                        .put("provider", "droy-native-fixture").put("model", "native-local")))
                .put(new JSONObject().put("insert", new JSONArray().put(new JSONObject()
                        .put("id", "notification-organizer-fixture").put("name", provider.getAbsolutePath())))).toString());
        listenerWasGranted = DroyNotificationService.hasAccess(context);
        sourceNotificationsGranted = context.getPackageManager().checkPermission(Manifest.permission.POST_NOTIFICATIONS, SOURCE) == PackageManager.PERMISSION_GRANTED;
        targetNotificationsGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        if (Build.VERSION.SDK_INT >= 33) {
            shell("pm grant " + SOURCE + " android.permission.POST_NOTIFICATIONS");
            shell("pm grant " + context.getPackageName() + " android.permission.POST_NOTIFICATIONS");
        }
        shell("cmd notification allow_listener " + new ComponentName(context, DroyNotificationService.class).flattenToString());
        activity = (NativeAppActivity) instrumentation.startActivitySync(new Intent(context, NativeAppActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        DeviceUi.await("The local notification fixture Host did not become ready", 120000, () -> RuntimeService.ready);
        DeviceUi.await("Notification listener did not connect", 10000,
                () -> DroyNotificationService.status(context).getBoolean("connected"));
        NotificationManagement management = NotificationManagement.get(context);
        management.request(new JSONObject().put("operation", "configure").put("enabled", true)
                .put("ruleText", "Use each fictional DroyFixture marker to retain, clear, collect, or notify. Keep all other notifications."));
        management.request(new JSONObject().put("operation", "setAppMode").put("packageName", SOURCE).put("mode", "managed"));
    }

    @After public void tearDown() throws Exception {
        if (saved == null) return;
        try {
            NotificationManagement.get(context).request(new JSONObject().put("operation", "configure").put("enabled", false));
            shell("am broadcast --include-stopped-packages -n " + RECEIVER + " --ez clear true --es suffix " + fixtureSuffix);
            if (!listenerWasGranted) shell("cmd notification disallow_listener "
                    + new ComponentName(context, DroyNotificationService.class).flattenToString());
            if (activity != null) instrumentation.runOnMainSync(activity::finish);
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("Notification tests left the engine active", 15000, RuntimeService::canManageHistory);
            saved.restore();
        } finally {
            if (Build.VERSION.SDK_INT >= 33) {
                if (!sourceNotificationsGranted) shell("pm revoke " + SOURCE + " android.permission.POST_NOTIFICATIONS");
                // Revoking the target runtime grant can kill instrumentation; the host may restore it after the run.
                if (!targetNotificationsGranted) {
                    android.os.Bundle report = new android.os.Bundle();
                    report.putString("stream", "\nDROY_NOTIFICATION_RESTORE_PERMISSION " + context.getPackageName()
                            + " android.permission.POST_NOTIFICATIONS\n");
                    instrumentation.sendStatus(2, report);
                }
            }
        }
    }

    @Test public void authorizedListenerReconnectsWithoutExposingTransientTechnicalErrors() throws Exception {
        NotificationManagement management = NotificationManagement.get(context);
        listener().requestUnbind();
        DeviceUi.await("The listener did not disconnect for the rebind fixture", 5000,
                () -> !DroyNotificationService.status(context).getBoolean("connected"));
        DeviceUi.await("Transient disconnection was not classified as preparation", 2000,
                () -> management.state().getJSONObject("access").optString("state").equals("connecting"));
        assertTrue("The test must retain the original notification access grant", DroyNotificationService.hasAccess(context));
        assertFalse("Transient preparation should not require user intervention", NotificationManagement.attentionRequired(context));
        for (StatusBarNotification item : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            if (item.getTag() == null && item.getId() == ForegroundStatusNotifications.ID) {
                String detail = item.getNotification().extras.getString(Notification.EXTRA_TEXT, "");
                assertFalse(detail.contains("未连接") || detail.contains("disconnected"));
            }
        }
        DeviceUi.await("The authorized listener did not recover after its bounded rebind", 15000,
                () -> DroyNotificationService.status(context).getBoolean("connected")
                        && management.state().getJSONObject("access").optString("state").equals("ready"));
        assertFalse("Successful reconnection left a stale setup warning", NotificationManagement.attentionRequired(context));
    }

    @Test public void silentNotificationsFollowRulesAndDisabledManagementLeavesOriginals() throws Exception {
        post(101, "retain", false);
        DeviceUi.await("Retained notification was not processed", 15000,
                () -> recordsContain("DroyFixture retain"));
        assertTrue("Retain removed the original", sourcePresent(101));

        post(102, "clear", false);
        DeviceUi.await("Clear did not remove the original", 15000,
                () -> recordsContain("DroyFixture clear") && !sourcePresent(102));

        post(103, "collect", false);
        DeviceUi.await("Collect did not replace the original with a summary", 15000,
                () -> !sourcePresent(103) && archiveContains("DroyFixture collect") && managedCount(NotificationManagement.SUMMARY_ID) == 1);
        Notification summary = managed(NotificationManagement.SUMMARY_ID).getNotification();
        assertEquals("managed_summary", summary.getChannelId());
        assertEquals("Summary unexpectedly offers confirmation actions", 0, summary.actions == null ? 0 : summary.actions.length);

        post(104, "notify", false);
        DeviceUi.await("Important notification was not replaced", 15000,
                () -> !sourcePresent(104) && archiveContains("DroyFixture notify") && alertCount() == 1);
        StatusBarNotification first = alert();
        assertEquals("Important notification unexpectedly offers confirmation actions", 0,
                first.getNotification().actions == null ? 0 : first.getNotification().actions.length);
        long firstPost = first.getPostTime();
        NotificationManagement.get(context).rescan();
        SystemClock.sleep(500);
        assertEquals("Rescanning reissued an unchanged important notification", firstPost, alert().getPostTime());
        post(104, "notify", false);
        SystemClock.sleep(500);
        DeviceUi.await("A newer repost was not processed as a quiet update", 15000,
                () -> !sourcePresent(104) && alert().getPostTime() > firstPost);
        assertTrue("A newer source update would alert again while its Droy notification still exists",
                (alert().getNotification().flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);

        post(104, "retain_update", false);
        DeviceUi.await("A changed notification did not replace its earlier decision", 15000,
                () -> sourcePresent(104) && recordsContain("DroyFixture retain_update") && alertCount() == 0);

        post(105, "clear", true);
        DeviceUi.await("The high-channel fixture was neither delivered nor processed", 15000,
                () -> sourcePresent(105) || recordsContain("DroyFixture clear", highChannel));
        NotificationManagement.get(context).rescan(); saved.awaitWorker();
        assertTrue("Clearing an advertisement must not require silencing its source", recordsContain("DroyFixture clear", highChannel));
        assertFalse("The safe default unexpectedly opted into automatic silence",
                NotificationManagement.get(context).state().getBoolean("silentTakeoverEnabled"));

        String mixed = shell("am broadcast --include-stopped-packages -n " + RECEIVER + " --ei id 107 --es kind clear_mixed --ez mixed true --es suffix " + fixtureSuffix);
        assertTrue("External mixed-message fixture was not posted: " + mixed, mixed.contains("DROY_FIXTURE_POSTED"));
        DeviceUi.await("Mixed-message fixture did not reach Android", 5000, () -> sourcePresent(107));
        SystemClock.sleep(700);
        assertTrue("A notification containing multiple messages was cleared as a single item", sourcePresent(107));
        assertFalse("Mixed-message notification was classified for removal", recordsContain("DroyFixture clear_mixed"));

        NotificationManagement.get(context).request(new JSONObject().put("operation", "setAppMode")
                .put("packageName", SOURCE).put("mode", "allow"));
        post(108, "clear", false);
        DeviceUi.await("Allow exception did not preserve the original", 5000, () -> sourcePresent(108));
        SystemClock.sleep(500);
        assertTrue("An allow exception was still classified or removed", sourcePresent(108));

        post(109, "retain", false);
        DeviceUi.await("The allowed source did not reach Android", 5000, () -> sourcePresent(109));
        try {
            NotificationManagement.get(context).request(new JSONObject().put("operation", "setAppMode")
                    .put("packageName", SOURCE).put("mode", "block"));
            fail("A retired block mode must not become a hidden notification policy");
        } catch (IllegalArgumentException expected) { assertTrue(sourcePresent(108) && sourcePresent(109)); }
        NotificationManagement.get(context).request(new JSONObject().put("operation", "setAppMode")
                .put("packageName", SOURCE).put("mode", "managed"));
        DeviceUi.await("Restoring management did not classify the existing sources", 15000,
                () -> !sourcePresent(108) && sourcePresent(109) && recordsContain("DroyFixture retain"));
        assertFalse("Resetting one application kept a hidden exception", hasException(SOURCE));

        NotificationManagement.get(context).request(new JSONObject().put("operation", "configure").put("enabled", false));
        post(106, "clear", false);
        DeviceUi.await("Disabled fixture did not reach Android", 5000, () -> sourcePresent(106));
        SystemClock.sleep(500);
        assertTrue("Disabled management still cleared a notification", sourcePresent(106));
        assertEquals("Disabled management left its reminders", 0, alertCount());
        assertEquals("Disabled management left its summary", 0, managedCount(NotificationManagement.SUMMARY_ID));
    }

    /** Injects a synthetic classification; this exercises Android delivery, not model accuracy. */
    @Test public void retainedImportantOriginalsRemindOnceAfterConfiguredDelayAndCancelWithSource() throws Exception {
        NotificationManagement management = NotificationManagement.get(context);
        for (int minutes : new int[]{5, 10}) {
            management.request(new JSONObject().put("operation", "configure").put("reminderMinutes", minutes));
            int id = 200 + minutes;
            String kind = "important_" + minutes;
            post(id, kind, false);
            DeviceUi.await("Original notification was not retained", 15000,
                    () -> sourcePresent(id) && recordsContain("DroyFixture " + kind));
            StatusBarNotification original = source(id);
            assertNotEquals("Fixture must come from an independent posting UID", context.getApplicationInfo().uid, original.getUid());
            injectClassification(id, new JSONObject().put("action", "retain").put("important", true));
            JSONObject retained = record(original.getKey());
            assertEquals(original.getPostTime() + minutes * 60000L, retained.getLong("reminderAt"));
            assertTrue("Important classification removed the original", sourcePresent(id));
            assertEquals("Important classification alerted before its delay", 0, alertCount());
            maintain(false, original.getKey());
            assertFalse("Maintenance reminded before the configured delay", record(original.getKey()).has("remindedAt"));

            if (minutes == 10) {
                BackgroundRuntime.stoppedByUser(context);
                maintain(true, original.getKey());
                assertFalse("An explicit engine stop consumed a pending reminder", record(original.getKey()).has("reminderAttemptedAt"));
                assertEquals("An explicit engine stop published a reminder", 0, alertCount());
                BackgroundRuntime.startedByUser(context);
            }
            maintain(true, original.getKey());
            DeviceUi.await("Due important notification did not produce a system reminder", 5000,
                    () -> "notification".equals(record(original.getKey()).optString("reminderDelivery")));
            JSONObject reminded = record(original.getKey());
            StatusBarNotification reminder = managed(reminded.getInt("notificationId"));
            assertNotNull("Reminder receipt has no Android notification", reminder);
            assertTrue("Reminder removed its useful original", sourcePresent(id));
            if (minutes == 5) captureShade("important-reminder");
            assertEquals(0, reminded.getLong("reminderAt"));
            long remindedAt = reminded.getLong("remindedAt"), reminderPostedAt = reminder.getPostTime();
            maintain(false, original.getKey());
            assertEquals("Maintenance repeated the same reminder", remindedAt, record(original.getKey()).getLong("remindedAt"));
            assertEquals("Maintenance republished the same system reminder", reminderPostedAt,
                    managed(reminded.getInt("notificationId")).getPostTime());

            cancelSource(id);
            DeviceUi.await("Clearing the original did not cancel its pending state and reminder", 5000,
                    () -> !sourcePresent(id) && record(original.getKey()).optBoolean("dismissed")
                            && record(original.getKey()).optLong("reminderAt") == 0
                            && managed(reminded.getInt("notificationId")) == null);
        }
    }

    /** The ad label is synthetic; removal and its listener receipt are real Android operations. */
    @Test public void advertisingCleanupCountsOnlyTheConfirmedOriginalRemoval() throws Exception {
        post(221, "advertising", false);
        DeviceUi.await("Advertising fixture did not arrive", 15000,
                () -> sourcePresent(221) && recordsContain("DroyFixture advertising"));
        StatusBarNotification original = source(221);
        assertNotEquals(context.getApplicationInfo().uid, original.getUid());
        injectClassification(221, new JSONObject().put("action", "clear").put("advertisement", true));
        DeviceUi.await("Ad cleanup has no confirmed source-removal receipt", 5000,
                () -> !sourcePresent(221) && record(original.getKey()).optLong("clearedAt") > 0);
        assertEquals("clear", record(original.getKey()).getString("action"));
        assertTrue(record(original.getKey()).getBoolean("advertisement"));
        captureShade("advertisements-cleared");
        assertEquals("Confirmed ad count did not reach settings", 1, NotificationManagement.get(context).state().getInt("clearedAdsCount"));
        NotificationManagement.get(context).rescan(); saved.awaitWorker();
        assertEquals("Rescanning counted the removed advertisement twice", 1, NotificationManagement.get(context).state().getInt("clearedAdsCount"));
        for (StatusBarNotification item : context.getSystemService(NotificationManager.class).getActiveNotifications())
            if (item.getTag() == null && item.getId() == ForegroundStatusNotifications.ID) {
                String detail = item.getNotification().extras.getString(Notification.EXTRA_TEXT, "");
                assertFalse("Ad statistics overwrote the runtime status", detail.contains("广告") || detail.toLowerCase(java.util.Locale.ROOT).contains("ads"));
            }
    }

    /** Uses the real companion window and saves a screenshot for visual review. */
    @Test public void foregroundAdaptiveCompanionShowsTheDueImportantReminder() throws Exception {
        assertTrue(context.getSharedPreferences("product", Context.MODE_PRIVATE).edit()
                .putBoolean("petEnabled", true).putString("petInternalDisplay", "adaptive")
                .putString("petExternalDisplay", "off").commit());
        shell("input keyevent KEYCODE_WAKEUP");
        shell("wm dismiss-keyguard");
        PetOverlay.foreground(activity, true);
        PetOverlay.conversation(context, "", false);
        PetOverlay.refresh(context);
        DeviceUi.await("Foreground companion is unavailable", 5000,
                () -> PetOverlay.notificationState(context, "").optBoolean("petAvailable"));
        post(222, "companion", false);
        DeviceUi.await("Companion reminder source did not arrive", 15000,
                () -> sourcePresent(222) && recordsContain("DroyFixture companion"));
        StatusBarNotification original = source(222);
        injectClassification(222, new JSONObject().put("action", "retain").put("important", true));
        maintain(true, original.getKey());
        DeviceUi.await("Due reminder did not render through the companion", 5000,
                () -> "companion".equals(record(original.getKey()).optString("reminderDelivery")));
        assertTrue("Companion reminder removed the original notification", sourcePresent(222));
        assertEquals("Companion delivery also published a system reminder", 0, alertCount());
        instrumentation.runOnMainSync(() -> {
            try {
                PetOverlay overlay = PetOverlay.get(context);
                for (String name : new String[]{"pet", "bubbleWindow"}) {
                    Field field = PetOverlay.class.getDeclaredField(name); field.setAccessible(true);
                    android.view.View view = (android.view.View) field.get(overlay);
                    assertNotNull("Companion window missing: " + name, view);
                    assertTrue("Companion window is not attached: " + name, view.isAttachedToWindow());
                    assertEquals("Companion window is hidden: " + name, android.view.View.VISIBLE, view.getVisibility());
                }
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        awaitCompanionFrame();
        android.graphics.Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("Unable to capture companion reminder", screenshot);
        File directory = context.getExternalFilesDir("notification-tests");
        assertNotNull(directory);
        File output = new File(directory, "important-companion-" + fixtureSuffix + ".png");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            assertTrue("Unable to save companion screenshot", screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream));
        } finally { screenshot.recycle(); }
        android.os.Bundle report = new android.os.Bundle();
        report.putString("stream", "\nDROY_NOTIFICATION_COMPANION_SCREENSHOT " + output.getAbsolutePath() + "\n");
        instrumentation.sendStatus(2, report);
    }

    private void awaitCompanionFrame() throws Exception {
        java.util.List<java.util.concurrent.CompletableFuture<Void>> frames = new java.util.ArrayList<>();
        instrumentation.runOnMainSync(() -> {
            try {
                PetOverlay overlay = PetOverlay.get(context);
                for (String name : new String[]{"pet", "bubbleWindow"}) {
                    Field field = PetOverlay.class.getDeclaredField(name); field.setAccessible(true);
                    android.view.View window = (android.view.View) field.get(overlay);
                    assertNotNull(window); assertTrue(window.isShown());
                    java.util.concurrent.CompletableFuture<Void> frame = new java.util.concurrent.CompletableFuture<>(); frames.add(frame);
                    Runnable painted = () -> window.post(() -> android.view.Choreographer.getInstance().postFrameCallback(first ->
                            android.view.Choreographer.getInstance().postFrameCallback(second -> frame.complete(null))));
                    if (window.isHardwareAccelerated()) window.getViewTreeObserver().registerFrameCommitCallback(painted);
                    else window.postOnAnimation(painted);
                    window.invalidate();
                }
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        java.util.concurrent.CompletableFuture.allOf(frames.toArray(new java.util.concurrent.CompletableFuture<?>[0])).get(3, TimeUnit.SECONDS);
    }

    private void captureShade(String label) throws Exception {
        shell("cmd statusbar expand-notifications");
        try {
            instrumentation.getUiAutomation().waitForIdle(300, 3000);
            android.graphics.Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
            assertNotNull("Unable to capture the notification shade", screenshot);
            File directory = context.getExternalFilesDir("notification-tests");
            assertNotNull(directory);
            File output = new File(directory, label + "-" + fixtureSuffix + ".png");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream));
            } finally { screenshot.recycle(); }
        } finally { shell("cmd statusbar collapse"); }
    }

    private JSONObject record(String key) throws Exception {
        String data = context.getSharedPreferences("product", Context.MODE_PRIVATE).getString("notificationManagementData", "{}");
        JSONArray items = new JSONObject(data).optJSONArray("records");
        for (int i = 0; items != null && i < items.length(); i++)
            if (key.equals(items.getJSONObject(i).optString("key"))) return items.getJSONObject(i);
        throw new AssertionError("Notification receipt is absent");
    }

    private ExecutorService notificationWorker() throws Exception {
        Field executor = NotificationManagement.class.getDeclaredField("worker"); executor.setAccessible(true);
        return (ExecutorService) executor.get(NotificationManagement.get(context));
    }

    private void injectClassification(int id, JSONObject decision) throws Exception {
        saved.awaitWorker();
        notificationWorker().submit(() -> {
            NotificationManagement management = NotificationManagement.get(context);
            StatusBarNotification original = source(id);
            assertNotNull("Synthetic classification requires an active real source", original);
            Method snapshot = NotificationManagement.class.getDeclaredMethod("snapshot", StatusBarNotification.class);
            Method apply = NotificationManagement.class.getDeclaredMethod("apply", StatusBarNotification.class, JSONObject.class, long.class, JSONObject.class);
            snapshot.setAccessible(true); apply.setAccessible(true);
            apply.invoke(management, original, snapshot.invoke(management, original), management.state().getLong("revision"),
                    NotificationManagementRules.decision(decision));
            return null;
        }).get(15, TimeUnit.SECONDS);
    }

    private void maintain(boolean makeDue, String key) throws Exception {
        notificationWorker().submit(() -> {
            NotificationManagement management = NotificationManagement.get(context);
            if (makeDue) {
                Field records = NotificationManagement.class.getDeclaredField("records"); records.setAccessible(true);
                synchronized (management) {
                    JSONArray items = (JSONArray) records.get(management);
                    for (int i = 0; i < items.length(); i++)
                        if (key.equals(items.getJSONObject(i).optString("key")))
                            items.getJSONObject(i).put("reminderAt", System.currentTimeMillis() - 1);
                }
            }
            Method maintain = NotificationManagement.class.getDeclaredMethod("maintain"); maintain.setAccessible(true);
            maintain.invoke(management);
            return null;
        }).get(15, TimeUnit.SECONDS);
    }

    private void cancelSource(int id) throws Exception {
        String result = shell("am broadcast --include-stopped-packages -n " + RECEIVER + " --ez cancel true --ei id " + id + " --es suffix " + fixtureSuffix);
        assertTrue("External fixture was not cancelled", result.contains("DROY_FIXTURE_CANCELED"));
    }

    private boolean hasException(String packageName) throws Exception {
        JSONArray exceptions = NotificationManagement.get(context).state().getJSONArray("exceptions");
        for (int i = 0; i < exceptions.length(); i++)
            if (packageName.equals(exceptions.getJSONObject(i).getString("packageName"))) return true;
        return false;
    }

    private void post(int id, String kind, boolean noisy) throws Exception {
        String result = shell("am broadcast --include-stopped-packages -n " + RECEIVER + " --ei id " + id + " --es kind " + kind + " --ez noisy " + noisy + " --es suffix " + fixtureSuffix);
        assertTrue("External fixture receiver did not post: " + result, result.contains("DROY_FIXTURE_POSTED"));
    }

    private DroyNotificationService listener() throws Exception {
        Field field = DroyNotificationService.class.getDeclaredField("connected"); field.setAccessible(true);
        DroyNotificationService listener = (DroyNotificationService) field.get(null);
        assertNotNull("Notification listener disconnected", listener);
        return listener;
    }

    private StatusBarNotification source(int id) throws Exception {
        for (StatusBarNotification item : listener().getActiveNotifications())
            if (SOURCE.equals(item.getPackageName()) && SOURCE_TAG.equals(item.getTag()) && item.getId() == id) return item;
        return null;
    }

    private boolean sourcePresent(int id) throws Exception { return source(id) != null; }

    private boolean archiveContains(String title) throws Exception {
        JSONArray archive = NotificationManagement.get(context).state().getJSONArray("archive");
        for (int i = 0; i < archive.length(); i++) if (title.equals(archive.getJSONObject(i).optString("title"))) return true;
        return false;
    }

    private boolean recordsContain(String title) throws Exception { return recordsContain(title, silentChannel); }

    private boolean recordsContain(String title, String channelId) throws Exception {
        String expected = NotificationManagementRules.fingerprint(new JSONObject().put("packageName", SOURCE)
                .put("channelId", channelId).put("title", title).put("text", "Fictional notification content").put("subtext", ""));
        String json = context.getSharedPreferences("product", Context.MODE_PRIVATE).getString("notificationManagementData", "{}");
        JSONArray records = new JSONObject(json).optJSONArray("records");
        for (int i = 0; records != null && i < records.length(); i++)
            if (expected.equals(records.getJSONObject(i).optString("fingerprint")) && records.getJSONObject(i).optBoolean("applied")) return true;
        return false;
    }

    private StatusBarNotification managed(int id) {
        for (StatusBarNotification item : context.getSystemService(NotificationManager.class).getActiveNotifications())
            if (NotificationManagement.TAG.equals(item.getTag()) && item.getId() == id) return item;
        return null;
    }

    private int managedCount(int id) { return managed(id) == null ? 0 : 1; }
    private int alertCount() {
        int count = 0;
        for (StatusBarNotification item : context.getSystemService(NotificationManager.class).getActiveNotifications())
            if (NotificationManagement.TAG.equals(item.getTag()) && "managed_alerts".equals(item.getNotification().getChannelId())) count++;
        return count;
    }
    private StatusBarNotification alert() {
        for (StatusBarNotification item : context.getSystemService(NotificationManager.class).getActiveNotifications())
            if (NotificationManagement.TAG.equals(item.getTag()) && "managed_alerts".equals(item.getNotification().getChannelId())) return item;
        throw new AssertionError("Important notification is absent");
    }

    private String shell(String command) throws Exception { return NotificationTestState.shell(instrumentation, command); }

    /** The system launches this receiver under the test APK UID, rather than the instrumented target UID. */
    public static final class FixtureReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String suffix = intent.getStringExtra("suffix");
            if (suffix == null || !suffix.matches("[a-f0-9]{12}")) return;
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (intent.getBooleanExtra("cancel", false)) {
                manager.cancel(SOURCE_TAG, intent.getIntExtra("id", 1));
                setResultData("DROY_FIXTURE_CANCELED");
                return;
            }
            if (intent.getBooleanExtra("clear", false)) {
                manager.cancelAll();
                manager.deleteNotificationChannel("droy_fixture_silent_" + suffix);
                manager.deleteNotificationChannel("droy_fixture_high_" + suffix);
                return;
            }
            boolean noisy = intent.getBooleanExtra("noisy", false);
            String channelId = (noisy ? "droy_fixture_high_" : "droy_fixture_silent_") + suffix;
            NotificationChannel channel = new NotificationChannel(channelId, "Fictional Droy notification tests",
                    noisy ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_LOW);
            channel.enableVibration(false); channel.setSound(null, null); manager.createNotificationChannel(channel);
            String kind = intent.getStringExtra("kind");
            Notification.Builder notification = new Notification.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(fixtureText(intent, "title", "DroyFixture " + kind))
                    .setContentText(fixtureText(intent, "text", "Fictional notification content"));
            if (intent.getBooleanExtra("mixed", false)) notification.setStyle(new Notification.InboxStyle()
                    .addLine("Fictional advertisement").addLine("Fictional delivery update"));
            manager.notify(SOURCE_TAG, intent.getIntExtra("id", 1), notification.build());
            setResultData("DROY_FIXTURE_POSTED");
        }

        private static String fixtureText(Intent intent, String key, String fallback) {
            String encoded = intent.getStringExtra(key + "Base64");
            if (encoded != null) {
                if (!encoded.startsWith("b64:")) throw new IllegalArgumentException("Invalid fictional notification encoding");
                return new String(android.util.Base64.decode(encoded.substring(4), android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            return intent.hasExtra(key) ? intent.getStringExtra(key) : fallback;
        }
    }
}

final class NotificationTestState {
    private static final String[] KEYS = {"notificationManagementData", "notificationManagementEnabled", "backgroundRuntimeEnabled",
        "backgroundRuntimeStopped", "toolsStopped", "allowNotifications", "petEnabled", "petInternalDisplay", "petExternalDisplay",
        "themePreference", "themeColorScheme", "themeBackgroundColor", "resetTheme", "darkTheme"};
    private final Context context;
    private final SharedPreferences preferences;
    private final Map<String, Object> original = new HashMap<>();
    private File home, preservedHome;
    private boolean homeOwned, hadOriginalHome, setupOwned;
    private Boolean originalSetupCompleted;

    NotificationTestState(Context context) {
        this.context = context; preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        Map<String, ?> all = preferences.getAll();
        for (String key : KEYS) if (all.containsKey(key)) original.put(key, all.get(key));
    }

    void prepare() throws Exception {
        if (emulator()) {
            SharedPreferences setup = context.getSharedPreferences("installation", Context.MODE_PRIVATE);
            originalSetupCompleted = setup.contains("completed") ? setup.getBoolean("completed", false) : null;
            setupOwned = true;
            assertTrue("Could not bypass unrelated setup in the isolated fixture", setup.edit().putBoolean("completed", true).commit());
            home = new File(context.getFilesDir(), "dsh-home");
            preservedHome = new File(context.getFilesDir(), "notification-test-home-" + java.util.UUID.randomUUID());
            hadOriginalHome = home.exists();
            if (hadOriginalHome) assertTrue("Could not preserve DSH home", home.renameTo(preservedHome));
            homeOwned = true;
            assertTrue("Could not create isolated notification-test home", home.mkdirs());
        }
        assertTrue(preferences.edit().putBoolean("notificationManagementEnabled", false).putBoolean("backgroundRuntimeEnabled", false)
                .putBoolean("backgroundRuntimeStopped", false).putBoolean("toolsStopped", false).putBoolean("allowNotifications", true)
                .putBoolean("petEnabled", false).putString("petInternalDisplay", "off").putString("petExternalDisplay", "off").commit());
        NotificationManagement manager = NotificationManagement.get(context);
        reloadNotificationMemory(context, new JSONObject().put("config", NotificationManagementRules.configuration(null))
                .put("records", new JSONArray()).toString());
        manager.request(new JSONObject().put("operation", "configure").put("enabled", false)
                .put("resetRule", true).put("silentTakeoverEnabled", false));
        JSONArray apps = manager.state().getJSONArray("apps"), packageNames = new JSONArray();
        for (int i = 0; i < apps.length(); i++) packageNames.put(apps.getJSONObject(i).getString("packageName"));
        manager.request(new JSONObject().put("operation", "setApps").put("enabled", false).put("packageNames", packageNames));
    }

    JSONArray allowOthers(String managedPackage) throws Exception {
        JSONArray exceptions = new JSONArray(), apps = NotificationManagement.get(context).state().getJSONArray("apps");
        for (int i = 0; i < apps.length(); i++) {
            String name = apps.getJSONObject(i).getString("packageName");
            if (!name.equals(managedPackage)) exceptions.put(new JSONObject().put("packageName", name).put("mode", "allow"));
        }
        return exceptions;
    }

    void awaitWorker() throws Exception {
        NotificationManagement manager = NotificationManagement.get(context);
        Field executor = NotificationManagement.class.getDeclaredField("worker"); executor.setAccessible(true);
        ((ExecutorService) executor.get(manager)).submit(() -> {}).get(90, TimeUnit.SECONDS);
    }

    void restore() throws Exception {
        NotificationManagement manager = NotificationManagement.get(context);
        manager.request(new JSONObject().put("operation", "configure").put("enabled", false));
        awaitWorker();
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : KEYS) {
            Object value = original.get(key);
            if (value instanceof Boolean bool) editor.putBoolean(key, bool);
            else if (value instanceof String text) editor.putString(key, text);
            else editor.remove(key);
        }
        assertTrue("Could not restore notification preferences", editor.commit());
        reloadNotificationMemory(context, (String) original.get("notificationManagementData"));
        if (setupOwned) {
            SharedPreferences.Editor setup = context.getSharedPreferences("installation", Context.MODE_PRIVATE).edit();
            if (originalSetupCompleted == null) setup.remove("completed"); else setup.putBoolean("completed", originalSetupCompleted);
            assertTrue("Could not restore original setup completion", setup.commit()); setupOwned = false;
        }
        if (homeOwned) {
            assertTrue("Notification-test engine must stop before restoring home", RuntimeService.canManageHistory());
            HistoryData.deleteTree(home.toPath());
            if (hadOriginalHome) assertTrue("Could not restore DSH home", preservedHome.renameTo(home));
            homeOwned = false;
        }
    }

    /** Runs after preference restoration; never creates a singleton from fixture preferences. */
    static void reloadNotificationMemory(Context context, String savedData) throws Exception {
        Field singleton = notificationField("singleton");
        NotificationManagement manager = (NotificationManagement) singleton.get(null);
        if (manager == null) return;
        ExecutorService worker = (ExecutorService) notificationField("worker").get(manager);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) instrumentation.waitForIdleSync();
        worker.submit(() -> {}).get(90, TimeUnit.SECONDS);
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) instrumentation.waitForIdleSync();
        worker.submit(() -> {
            JSONObject data = new JSONObject(savedData == null ? "{}" : savedData);
            synchronized (manager) {
                android.os.Handler handler = (android.os.Handler) notificationField("handler").get(manager);
                handler.removeCallbacks((Runnable) notificationField("maintenance").get(manager));
                notificationField("config").set(manager, NotificationManagementRules.configuration(data.optJSONObject("config")));
                notificationField("records").set(manager, data.optJSONArray("records") == null ? new JSONArray() : data.getJSONArray("records"));
                notificationField("lastResult").set(manager, data.optJSONObject("lastResult") == null ? new JSONObject() : data.getJSONObject("lastResult"));
                notificationField("lastFailure").set(manager, data.optJSONObject("lastFailure") == null ? new JSONObject() : data.getJSONObject("lastFailure"));
                notificationField("takeoverSuspended").setBoolean(manager, data.optBoolean("silentTakeoverSuspended"));
                notificationField("takeoverSuspensionCode").set(manager, data.optString("takeoverSuspensionCode"));
                notificationField("modelConfigured").setBoolean(manager, false);
                notificationField("modelCode").set(manager, "DROY_NOTIFICATION_MODEL_UNAVAILABLE");
                notificationField("checkingModel").setBoolean(manager, false);
                notificationField("modelStatusCheckedAt").setLong(manager, 0);
                notificationField("scanQueued").setBoolean(manager, false);
                notificationField("applyingChannels").setBoolean(manager, false);
                for (String name : new String[]{"intents", "silentApps", "applicationNames", "inFlight"})
                    ((Map<?, ?>) notificationField(name).get(manager)).clear();
                for (String name : new String[]{"internalRemovals", "channelSyncAttempts"})
                    ((java.util.Set<?>) notificationField(name).get(manager)).clear();
                JSONObject result = data.optJSONObject("lastResult");
                String error = "";
                if (result != null && !result.optBoolean("success", true)) {
                    Method message = NotificationManagement.class.getDeclaredMethod("resultMessage", String.class); message.setAccessible(true);
                    error = (String) message.invoke(null, result.optString("code"));
                }
                notificationField("error").set(manager, error);
                SharedPreferences.Editor restore = context.getSharedPreferences("product", Context.MODE_PRIVATE).edit();
                if (savedData == null) restore.remove("notificationManagementData"); else restore.putString("notificationManagementData", savedData);
                assertTrue("Could not restore notification memory's persisted source", restore.commit());
            }
            return null;
        }).get(90, TimeUnit.SECONDS);
        worker.submit(() -> {}).get(90, TimeUnit.SECONDS);
    }

    private static Field notificationField(String name) throws Exception {
        Field field = NotificationManagement.class.getDeclaredField(name); field.setAccessible(true); return field;
    }

    static boolean emulator() {
        return Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")
                || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk_gphone");
    }

    static String shell(Instrumentation instrumentation, String command) throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int length;
            while ((length = input.read(bytes)) != -1) output.write(bytes, 0, length);
            return output.toString(java.nio.charset.StandardCharsets.UTF_8.name());
        }
    }
}
