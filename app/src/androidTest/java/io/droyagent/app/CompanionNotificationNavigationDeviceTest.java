package io.droyagent.app;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real local notification click and bundled IM navigation; no model or third-party messages. */
@RunWith(AndroidJUnit4.class)
public class CompanionNotificationNavigationDeviceTest {
    private Context context;
    private SharedPreferences product, installation;
    private Boolean completed;
    private final Map<String, Object> saved = new HashMap<>();
    private static final String[] KEYS = {"notifyLocalEnabled", BackgroundRuntime.ENABLED, BackgroundRuntime.STOPPED};
    private ProactiveNotifications notices;
    private boolean pluginActive;

    @After public void restore() throws Exception {
        if (product == null) return;
        InstrumentationRegistry.getInstrumentation().getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        for (StatusBarNotification item : manager.getActiveNotifications())
            if (ProactiveNotifications.COMPANION_TAG.equals(item.getTag())) manager.cancel(item.getTag(), item.getId());
        if (notices != null) notices.request(new JSONObject().put("operation", "activate").put("enabled", pluginActive));
        SharedPreferences.Editor editor = product.edit();
        for (String key : KEYS) {
            if (saved.get(key) instanceof Boolean value) editor.putBoolean(key, value); else editor.remove(key);
        }
        assertTrue(editor.commit());
        SharedPreferences.Editor setup = installation.edit();
        if (completed == null) setup.remove("completed"); else setup.putBoolean("completed", completed);
        assertTrue(setup.commit());
        BackgroundRuntime.settingsChanged(context);
    }

    @Test public void notificationTargetsItsCompanionAfterColdStartAndOnlyNavigatesWhenClicked() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertEquals("Use the development application", "io.droyagent.app.dev", context.getPackageName());
        assertTrue("Use an emulator", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk") || Build.PRODUCT.contains("sdk"));
        assertTrue("Grant POST_NOTIFICATIONS before running this fixture", Build.VERSION.SDK_INT < 33
            || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
        product = ProductPermissions.preferences(context);
        for (String key : KEYS) if (product.contains(key)) saved.put(key, product.getAll().get(key));
        installation = context.getSharedPreferences("installation", Context.MODE_PRIVATE);
        completed = installation.contains("completed") ? installation.getBoolean("completed", false) : null;
        assertTrue(installation.edit().putBoolean("completed", true).commit());
        assertTrue(product.edit().putBoolean("notifyLocalEnabled", true).putBoolean(BackgroundRuntime.ENABLED, true)
            .putBoolean(BackgroundRuntime.STOPPED, false).commit());
        instrumentation.getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .putExtra("companionId", "kaka").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        DeviceUi.await("Bundled host did not become ready", 90000, () -> RuntimeService.ready);
        DeviceUi ui = new DeviceUi(instrumentation, activity); ui.awaitPage();
        DeviceUi.await("Cold launch did not open Kaka", 10000,
            () -> ui.truth("document.querySelector('[data-droy-im-screen=chat]')?.dataset.companionId==='kaka'"));
        notices = ProactiveNotifications.get(context); pluginActive = notices.status().getBoolean("pluginActive");
        notices.request(new JSONObject().put("operation", "activate").put("enabled", true));
        assertFalse("The IM fixture must not require Root", MemoryRuntime.get(context).status().getJSONObject("root").getBoolean("authorized"));

        ui.pressBack();
        DeviceUi.await("Back did not reach Messages", 5000, () -> ui.truth("!!document.querySelector('[data-droy-im-screen=list]')"));
        ui.tap(".droy-im-mode-trigger");
        DeviceUi.await("Workbench did not open", 5000, () -> ui.truth("!document.querySelector('[data-droy-im]')"));
        JSONObject foreground = notices.request(message("foreground"));
        assertEquals("suppressed", foreground.getString("status")); assertFalse(foreground.getBoolean("published"));
        assertTrue("Incoming message changed the foreground page", ui.truth("!document.querySelector('[data-droy-im]')"));

        instrumentation.runOnMainSync(() -> activity.moveTaskToBack(true));
        DeviceUi.await("Activity did not enter the background", 5000, () -> !BackgroundRuntime.visible());
        JSONObject published = notices.request(message("background"));
        assertEquals("published", published.getString("status")); assertTrue(published.getBoolean("published"));
        assertFalse("Publication itself foregrounded the app", BackgroundRuntime.visible());
        assertTrue(ui.automation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS));
        boolean manualClick = "true".equals(InstrumentationRegistry.getArguments().getString("manualSystemClick"));
        try {
            if (!manualClick) DeviceUi.await("The local notification could not be clicked", 5000, () -> clickNotification(ui));
        }
        catch (AssertionError failure) {
            capture(ui, "companion-notification-click-failure.png");
            throw failure;
        }
        DeviceUi.await("Notification click did not open Maimai", manualClick ? 120000 : 10000,
            () -> ui.truth("document.querySelector('[data-droy-im-screen=chat]')?.dataset.companionId==='maimai'"));
        if (manualClick) { ui.awaitPaint(); capture(ui, "companion-notification-click-result.png"); }
        ui.pressBack();
        DeviceUi.await("Notification entry did not preserve the Messages return level", 5000,
            () -> ui.truth("!!document.querySelector('[data-droy-im-screen=list]')"));
    }

    private JSONObject message(String id) throws Exception {
        return new JSONObject().put("operation", "companion").put("companionId", "maimai")
            .put("name", "Fictional companion notification").put("text", "Local navigation fixture only").put("messageId", id);
    }
    private void capture(DeviceUi ui, String name) throws Exception {
        android.graphics.Bitmap screenshot = ui.automation().takeScreenshot();
        if (screenshot == null) return;
        java.io.File image = new java.io.File(context.getExternalFilesDir(null), name);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(image)) {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally { screenshot.recycle(); }
    }
    private boolean clickNotification(DeviceUi ui) {
        // System UI can leave the app as the active accessibility window while
        // presenting the notification shade in another interactive window.
        for (android.view.accessibility.AccessibilityWindowInfo window : ui.automation().getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            for (AccessibilityNodeInfo text : root.findAccessibilityNodeInfosByText("Fictional companion")) {
                android.graphics.Rect bounds = new android.graphics.Rect();
                text.getBoundsInScreen(bounds);
                if (text.isVisibleToUser() && !bounds.isEmpty()) {
                    ui.tapAt(bounds.centerX(), bounds.centerY());
                    return true;
                }
            }
        }
        return false;
    }
}
