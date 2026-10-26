package io.droyagent.app;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.service.notification.StatusBarNotification;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native publication and permissions with fictional recipients; no messages leave the device. */
@RunWith(AndroidJUnit4.class)
public class ProactiveNotificationsDeviceTest {
    private static final String[] KEYS = {"notifyLocalEnabled", "notifyAppsEnabled", "notifyAllowedApps", "proactiveNotificationRecords", "toolsStopped"};
    private Context context;
    private SharedPreferences preferences;
    private ProactiveNotifications notices;
    private final Map<String, Object> saved = new HashMap<>();
    private String task;

    @Before public void setup() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Use a dedicated emulator for notification fixtures", NotificationTestState.emulator());
        assertTrue("Stop the runtime and existing tasks first", RuntimeService.canManageHistory());
        preferences = ProductPermissions.preferences(context);
        Map<String, ?> all = preferences.getAll();
        for (String key : KEYS) if (all.containsKey(key)) saved.put(key, all.get(key));
        assertTrue("Grant POST_NOTIFICATIONS before instrumentation; revoking it kills the test process",
            Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
        preferences.edit().putBoolean("notifyLocalEnabled", true).putBoolean("notifyAppsEnabled", false).putString("notifyAllowedApps", "[]").putBoolean("toolsStopped", false).commit();
        notices = ProactiveNotifications.get(context);
        notices.request(new JSONObject().put("operation", "activate").put("enabled", true));
        task = "fictional-notice-" + java.util.UUID.randomUUID();
    }

    @After public void restore() throws Exception {
        if (notices != null) notices.request(new JSONObject().put("operation", "activate").put("enabled", false));
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        for (StatusBarNotification item : manager.getActiveNotifications()) if ("droy-task-notice".equals(item.getTag())) manager.cancel(item.getTag(), item.getId());
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : KEYS) {
            Object value = saved.get(key);
            if (value instanceof Boolean b) editor.putBoolean(key, b);
            else if (value instanceof String s) editor.putString(key, s);
            else editor.remove(key);
        }
        assertTrue(editor.commit());
    }

    @Test public void quietPublicationUpdatesOneItemAndSeparatesNextTurn() throws Exception {
        JSONObject first = result(send("1", "result", "system", "虚构结果已经就绪"));
        assertEquals("published", first.getString("status"));
        StatusBarNotification actual = find(first.getString("noticeId"));
        assertNotNull(actual);
        assertEquals("task_updates", actual.getNotification().getChannelId());
        JSONObject duplicate = result(send("1", "result", "system", "虚构结果已经就绪"));
        assertTrue(duplicate.getBoolean("duplicate"));
        JSONObject updated = result(send("1", "result", "system", "虚构结果已经更新"));
        assertEquals(first.getString("noticeId"), updated.getString("noticeId"));
        assertEquals("虚构结果已经更新", find(first.getString("noticeId")).getNotification().extras.getCharSequence("android.text").toString());
        JSONObject next = result(send("2", "result", "system", "虚构结果已经更新"));
        assertNotEquals(first.getString("noticeId"), next.getString("noticeId"));
    }

    @Test public void aDisabledLocalChannelCanBeExplicitlyRetriedAfterEnabling() throws Exception {
        notices.request(new JSONObject().put("operation", "configure").put("localEnabled", false));
        assertEquals("failed", result(send("1", "result", "system", "虚构结果")).getString("status"));
        notices.request(new JSONObject().put("operation", "configure").put("localEnabled", true));
        assertEquals("published", result(send("1", "result", "system", "虚构结果")).getString("status"));
    }

    @Test public void appPreparationIsNotSendingAndRevocationStopsSubsequentOperations() throws Exception {
        String pkg = context.getPackageName() + ".test";
        JSONObject request = send("1", "app-message", "app", "仅存在本地的虚构消息");
        request.getJSONArray("destinations").getJSONObject(0).put("packageName", pkg).put("target", "虚构测试会话");
        assertEquals("failed", result(request).getString("status"));
        notices.request(new JSONObject().put("operation", "configure").put("appsEnabled", true)
            .put("allowedApps", new JSONArray().put(new JSONObject().put("packageName", pkg).put("target", "虚构测试会话"))));
        JSONObject prepared = result(request);
        assertEquals("requires_action", prepared.getString("status"));
        notices.checkAppOperation(task);
        notices.appActionDispatched(task);
        notices.request(new JSONObject().put("operation", "configure").put("appsEnabled", false));
        try { notices.checkAppOperation(task); fail("Revoked app notification continued"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("关闭")); }
        notices.end(task);
        assertEquals("unknown", result(request).getString("status"));
        assertTrue(result(request).getBoolean("duplicate"));
    }

    @Test public void multiChannelRetryKeepsTheAlreadyPublishedDestinationQuiet() throws Exception {
        String pkg = context.getPackageName() + ".test";
        JSONObject request = send("1", "urgent-matter", "system", "虚构紧急重要事项").put("attention", "urgent");
        request.getJSONArray("destinations").put(new JSONObject().put("channel", "app")
            .put("packageName", pkg).put("target", "虚构测试会话"));
        JSONArray first = notices.request(request).getJSONArray("results");
        assertEquals("published", first.getJSONObject(0).getString("status"));
        assertEquals("task_urgent", find(first.getJSONObject(0).getString("noticeId")).getNotification().getChannelId());
        assertEquals("failed", first.getJSONObject(1).getString("status"));
        notices.request(new JSONObject().put("operation", "configure").put("appsEnabled", true)
            .put("allowedApps", new JSONArray().put(new JSONObject().put("packageName", pkg).put("target", "虚构测试会话"))));
        JSONArray retried = notices.request(request).getJSONArray("results");
        assertEquals(first.getJSONObject(0).getString("noticeId"), retried.getJSONObject(0).getString("noticeId"));
        assertTrue("Do not alert the successful channel again", retried.getJSONObject(0).getBoolean("duplicate"));
        assertEquals("requires_action", retried.getJSONObject(1).getString("status"));
    }

    @Test public void androidPauseDoesNotDisableLocalPublicationAndPluginDisableDoes() throws Exception {
        ProductPermissions.setStopped(context, true);
        assertEquals("published", result(send("1", "result", "system", "虚构本机结果")).getString("status"));
        notices.request(new JSONObject().put("operation", "activate").put("enabled", false));
        try { notices.request(send("1", "another", "system", "虚构本机结果")); fail("Disabled plugin sent a notification"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("关闭")); }
    }

    private JSONObject send(String turn, String key, String channel, String text) throws Exception {
        return new JSONObject().put("operation", "send").put("taskId", task).put("turnId", turn).put("callId", java.util.UUID.randomUUID().toString())
            .put("noticeKey", key).put("text", text).put("attention", "quiet")
            .put("destinations", new JSONArray().put(new JSONObject().put("channel", channel)));
    }
    private JSONObject result(JSONObject request) throws Exception { return notices.request(request).getJSONArray("results").getJSONObject(0); }
    private StatusBarNotification find(String id) {
        for (StatusBarNotification item : context.getSystemService(NotificationManager.class).getActiveNotifications())
            if ("droy-task-notice".equals(item.getTag()) && item.getId() == (id.hashCode() & 0x7fffffff)) return item;
        return null;
    }
    private void shell(String command) throws Exception {
        try (var input = new ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command))) { input.readAllBytes(); }
    }
}
