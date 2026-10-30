package io.droyagent.app;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Runs inside the caller's isolated home; never captures the phone's notification shade. */
final class CompanionNotificationChecks {
    private static final String TITLE = "Droy fictional notification check";
    private static final String TARGET = "maimai";

    static void run(Instrumentation instrumentation, Context context, MainActivity activity, DeviceUi ui) throws Exception {
        assertEquals("Only the development application may run this fixture", "io.droyagent.app.dev", context.getPackageName());
        // The outer runner records and restores this permission after restoring
        // the isolated home: revoking it here would kill the fixture process.
        assertTrue("Grant dev POST_NOTIFICATIONS outside the fixture before running", Build.VERSION.SDK_INT < 33
            || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
        assertTrue("Start this check from Messages", ui.truth("!!document.querySelector('[data-droy-im-screen=list]')"));
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        int notificationId = TARGET.hashCode() & 0x7fffffff;
        for (StatusBarNotification item : manager.getActiveNotifications())
            assertFalse("Do not replace an existing companion notification", ProactiveNotifications.companionTag(TARGET).equals(item.getTag())
                && item.getId() == notificationId);
        SharedPreferences preferences = ProductPermissions.preferences(context);
        Boolean localEnabled = preferences.contains("notifyLocalEnabled") ? preferences.getBoolean("notifyLocalEnabled", true) : null;
        ProactiveNotifications notices = ProactiveNotifications.get(context);
        boolean pluginActive = notices.status().getBoolean("pluginActive");
        try {
            assertTrue(preferences.edit().putBoolean("notifyLocalEnabled", true).commit());
            notices.request(new JSONObject().put("operation", "activate").put("enabled", true));
            DeviceUi.await("Droy did not become visible", 5000, BackgroundRuntime::visible);
            JSONObject foreground = notices.request(message("phone-foreground"));
            assertEquals("published", foreground.getString("status"));
            assertTrue(foreground.getBoolean("published"));
            assertTrue("A foreground notification changed the page", ui.truth("!!document.querySelector('[data-droy-im-screen=list]')"));
            System.out.println("COMPANION_NOTICE other_page_published");

            instrumentation.runOnMainSync(() -> activity.moveTaskToBack(true));
            DeviceUi.await("Droy did not enter the background", 5000, () -> !BackgroundRuntime.visible());
            JSONObject background = notices.request(message("phone-background"));
            assertEquals("published", background.getString("status"));
            assertTrue(background.getBoolean("published"));
            assertFalse("Publishing a notification foregrounded Droy", BackgroundRuntime.visible());
            System.out.println("COMPANION_NOTICE background_published_without_foreground");
            DeviceUi.await("The system did not retain Droy's own test notification", 5000,
                () -> ownNotification(manager, notificationId) != null);
            Notification notification = ownNotification(manager, notificationId);
            assertNotNull("Droy's test notification disappeared before opening the shade", notification);
            assertEquals(TITLE, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
            assertEquals(ProactiveNotifications.MESSAGE_CHANNEL, notification.getChannelId());
            NotificationChannel channel = manager.getNotificationChannel(notification.getChannelId());
            assertNotNull("The notification channel is missing", channel);
            assertTrue("The notification channel is blocked", channel.getImportance() > NotificationManager.IMPORTANCE_NONE);
            System.out.println("COMPANION_NOTICE own_active_title_and_channel_verified");
            assertTrue(ui.automation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS));
            boolean manualClick = "true".equals(InstrumentationRegistry.getArguments().getString("manualSystemClick"));
            if (manualClick) System.out.println("COMPANION_NOTICE awaiting_actual_system_click");
            else DeviceUi.await("System UI did not expose the fictional test notification; no notification-shade capture was taken",
                10000, () -> clickOwnNotification(ui));
            DeviceUi.await("Actual notification click did not open Maimai", manualClick ? 120000 : 15000,
                () -> BackgroundRuntime.visible()
                    && ui.truth("document.querySelector('[data-droy-im-screen=chat]')?.dataset.companionId==='maimai'"));
            System.out.println("COMPANION_NOTICE actual_click_opened_maimai");
            try {
                DeviceUi.await("The notification destination did not regain Activity focus", 10000, () -> {
                    AtomicBoolean focused = new AtomicBoolean();
                    instrumentation.runOnMainSync(() -> focused.set(activity.hasWindowFocus()
                        && ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).contains(activity)));
                    return focused.get() && ui.nativeUiReady();
                });
                ui.awaitPaint();
                ui.pressBack();
                DeviceUi.await("Back from the notification entry did not reach Messages", 5000,
                    () -> ui.truth("!!document.querySelector('[data-droy-im-screen=list]')"));
            } catch (AssertionError failure) {
                System.out.println("COMPANION_NOTICE back_failure_native_state " + ui.nativeNavigationState());
                throw failure;
            }
            System.out.println("COMPANION_NOTICE back_returned_to_messages");
        } finally {
            manager.cancel(ProactiveNotifications.companionTag(TARGET), notificationId);
            notices.request(new JSONObject().put("operation", "activate").put("enabled", pluginActive));
            SharedPreferences.Editor editor = preferences.edit();
            if (localEnabled == null) editor.remove("notifyLocalEnabled"); else editor.putBoolean("notifyLocalEnabled", localEnabled);
            assertTrue(editor.commit());
        }
    }

    private static JSONObject message(String id) throws Exception {
        return new JSONObject().put("operation", "companion").put("companionId", TARGET)
            .put("name", TITLE).put("text", "Fictional local navigation test only").put("messageId", id);
    }

    private static Notification ownNotification(NotificationManager manager, int id) {
        // NotificationManager exposes only notifications posted by this app.
        for (StatusBarNotification item : manager.getActiveNotifications())
            if (ProactiveNotifications.companionTag(TARGET).equals(item.getTag()) && item.getId() == id)
                return item.getNotification();
        return null;
    }

    private static boolean clickOwnNotification(DeviceUi ui) {
        // Search only for this fixture's exact title; do not serialize other
        // notification contents or take screenshots of the system shade.
        for (AccessibilityWindowInfo window : ui.automation().getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(TITLE)) {
                // Some system UIs expose the full title in a combined label or
                // content description instead of a standalone title text node.
                CharSequence text = node.getText(), description = node.getContentDescription();
                if (!(text != null && text.toString().contains(TITLE))
                    && !(description != null && description.toString().contains(TITLE))) continue;
                Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                if (node.isVisibleToUser() && !bounds.isEmpty()) {
                    ui.tapAt(bounds.centerX(), bounds.centerY());
                    return true;
                }
            }
        }
        return false;
    }
}
