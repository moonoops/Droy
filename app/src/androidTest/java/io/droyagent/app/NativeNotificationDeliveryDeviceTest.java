package io.droyagent.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.service.notification.StatusBarNotification;
import androidx.test.core.app.ActivityScenario;
import androidx.lifecycle.Lifecycle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Constructor;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Android publication with private fixture preferences; no model or external dispatch. */
@RunWith(AndroidJUnit4.class)
public final class NativeNotificationDeliveryDeviceTest {
    @Rule public final TestName testName = new TestName();
    private Context context;
    private NotificationManager manager;
    private ProactiveNotifications delivery;
    private String prefix, preferencesName;
    private final Set<Integer> messageIds = new HashSet<>(), taskIds = new HashSet<>();
    private final Object companionOwner = new Object(), conversationOwner = new Object();
    private ActivityScenario<NativeAppActivity> activity;

    private static void stage(String value) { android.util.Log.i("DroyNotificationFixture", value); }

    @Before public void setUp() throws Exception {
        if ("optInSyntheticMessageObservationWindow".equals(testName.getMethodName()))
            org.junit.Assume.assumeTrue("Explicit notification observation opt-in required",
                "true".equals(InstrumentationRegistry.getArguments().getString("nativeNotificationObservation")));
        stage("before.context");
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.droyagent.app.dev", context.getPackageName());
        stage("before.runtime_idle");
        assertTrue("Run only while no engine task is executing", RuntimeService.canManageHistory());
        stage("before.notification_manager");
        manager = context.getSystemService(NotificationManager.class);
        assertTrue("Grant development notification permission before running this fixture", manager.areNotificationsEnabled());
        for (StatusBarNotification item : manager.getActiveNotifications())
            assertFalse("Do not cancel existing user message/task notifications", ownedTag(item.getTag()));
        stage("before.existing_notifications_checked");
        prefix = "fixture-notice-" + UUID.randomUUID();
        preferencesName = prefix + "-preferences";
        Context isolated = new ContextWrapper(context) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(name.equals("product") ? preferencesName : name, mode);
            }
        };
        Constructor<ProactiveNotifications> constructor = ProactiveNotifications.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        stage("before.construct_delivery");
        delivery = constructor.newInstance(isolated);
        stage("before.activate");
        delivery.request(new JSONObject().put("operation", "activate").put("enabled", true));
        stage("before.channels");
        assertChannelAvailable(ProactiveNotifications.MESSAGE_CHANNEL);
        assertChannelAvailable("task_attention");
        stage("before.activity_launch");
        activity = ActivityScenario.launch(NativeAppActivity.class);
        stage("before.activity_launched");
        DeviceUi.await("Native activity visible", 10000, NativeRuntimeHost::isVisible);
        stage("before.ready");
    }

    @After public void tearDown() throws Exception {
        stage("after.start");
        if (context == null || prefix == null) return;
        NativeRuntimeHost.presentCompanion(companionOwner, null, false);
        NativeRuntimeHost.presentConversation(context, conversationOwner, null, false);
        for (int id : messageIds) manager.cancel(ProactiveNotifications.COMPANION_TAG, id);
        for (int id : taskIds) manager.cancel("droy-task-notice", id);
        stage("after.activity_close");
        if (activity != null) activity.close();
        stage("after.preferences_cleanup");
        context.deleteSharedPreferences(preferencesName);
        stage("after.done");
    }

    @Test public void independentSwitchMatrixAndCancellationUseRealSystemChannels() throws Exception {
        NotificationChannel channel = manager.getNotificationChannel(ProactiveNotifications.MESSAGE_CHANNEL);
        boolean vibrates = channel.shouldVibrate();
        int importance = channel.getImportance();
        boolean bypassDnd = channel.canBypassDnd();
        for (boolean messages : new boolean[]{true, false}) for (boolean tasks : new boolean[]{true, false}) {
            stage("matrix.configure.messages=" + messages + ".tasks=" + tasks);
            configure(messages, tasks);
            stage("matrix.message");
            String companion = prefix + "-" + messages + tasks;
            JSONObject message = message(companion, "message-1");
            assertEquals(messages, message.getBoolean("published"));
            stage("matrix.task");
            JSONObject task = task(prefix + "-task-" + messages + tasks, "event-1", "Result");
            assertEquals(tasks ? "published" : "failed", task.getString("status"));
            if (messages) assertEquals(ProactiveNotifications.MESSAGE_CHANNEL,
                posted(ProactiveNotifications.COMPANION_TAG, companion.hashCode() & 0x7fffffff).getNotification().getChannelId());
            if (tasks) assertEquals("task_attention", posted("droy-task-notice", task.getString("noticeId").hashCode() & 0x7fffffff)
                .getNotification().getChannelId());
        }
        stage("matrix.cancel_categories");
        configure(false, false);
        DeviceUi.await("Disabled categories cancel queued notifications", 3000, () -> {
            for (StatusBarNotification item : manager.getActiveNotifications()) if (ownedTag(item.getTag())) return false;
            return true;
        });
        channel = manager.getNotificationChannel(ProactiveNotifications.MESSAGE_CHANNEL);
        assertEquals("Never override the system vibration choice", vibrates, channel.shouldVibrate());
        assertEquals("Never override the system importance choice", importance, channel.getImportance());
        assertEquals("Preserve the system DND choice", bypassDnd, channel.canBypassDnd());
    }

    @Test public void exactVisibleConversationSuppressesAndReplayDoesNotAlertAgain() throws Exception {
        configure(true, true);
        String companion = prefix + "-companion";
        NativeRuntimeHost.presentCompanion(companionOwner, companion, true);
        assertEquals("conversation_visible", message(companion, "visible").getString("reason"));
        NativeRuntimeHost.presentCompanion(companionOwner, null, false);
        assertTrue(message(companion, "visible").getBoolean("duplicate"));
        JSONObject first = message(companion, "next");
        assertTrue("Other pages still receive messages", first.getBoolean("published"));
        assertFalse((posted(ProactiveNotifications.COMPANION_TAG, companion.hashCode() & 0x7fffffff).getNotification().flags
            & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
        assertTrue(message(companion, "next").getBoolean("duplicate"));
        delivery.request(new JSONObject().put("operation", "activate").put("enabled", false));
        assertTrue("Task plugin shutdown cannot suppress companion messages", message(companion, "plugin-off").getBoolean("published"));
        delivery.request(new JSONObject().put("operation", "activate").put("enabled", true));
        String session = prefix + "-session";
        NativeRuntimeHost.presentConversation(context, conversationOwner, session, true);
        assertEquals("suppressed", task(session, "visible-task", "Result").getString("status"));
        NativeRuntimeHost.presentConversation(context, conversationOwner, null, false);
        assertTrue(task(session, "visible-task", "Result").getBoolean("duplicate"));
        JSONObject task = task(session, "next-task", "Result");
        assertEquals("published", task.getString("status"));
        assertTrue(task(session, "next-task", "Result").getBoolean("duplicate"));
        task(session, "next-task", "Updated result");
        assertTrue((posted("droy-task-notice", task.getString("noticeId").hashCode() & 0x7fffffff).getNotification().flags
            & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
    }

    /** Retains one real notification for an external system-vibrator dump; not a motor assertion. */
    @Test public void optInSyntheticMessageObservationWindow() throws Exception {
        configure(true, false);
        boolean background = "true".equals(InstrumentationRegistry.getArguments().getString("nativeNotificationBackground"));
        if (background) {
            activity.moveToState(Lifecycle.State.CREATED);
            DeviceUi.await("Native activity is backgrounded before notification publication", 10000,
                () -> !NativeRuntimeHost.isVisible());
        }
        String companion = prefix + "-vibration-observation";
        int id = companion.hashCode() & 0x7fffffff;
        NotificationChannel channel = manager.getNotificationChannel(ProactiveNotifications.MESSAGE_CHANNEL);
        android.media.AudioManager audio = context.getSystemService(android.media.AudioManager.class);
        stage("observation.before_publish.utc_ms=" + System.currentTimeMillis()
            + ".background=" + background
            + ".vibration=" + channel.shouldVibrate() + ".importance=" + channel.getImportance()
            + ".interruption_filter=" + manager.getCurrentInterruptionFilter()
            + ".ringer_mode=" + audio.getRingerMode());
        try {
            assertTrue(message(companion, "single-observation").getBoolean("published"));
            assertEquals(ProactiveNotifications.MESSAGE_CHANNEL,
                posted(ProactiveNotifications.COMPANION_TAG, id).getNotification().getChannelId());
            stage("observation.active.utc_ms=" + System.currentTimeMillis() + ".window_ms=15000");
            android.os.SystemClock.sleep(15_000);
            assertNotNull("The synthetic notification must remain active during observation",
                find(ProactiveNotifications.COMPANION_TAG, id));
        } finally {
            manager.cancel(ProactiveNotifications.COMPANION_TAG, id);
            DeviceUi.await("Synthetic observation notification cancelled", 3000,
                () -> find(ProactiveNotifications.COMPANION_TAG, id) == null);
            stage("observation.cancelled.utc_ms=" + System.currentTimeMillis());
        }
    }

    private void configure(boolean messages, boolean tasks) throws Exception {
        delivery.request(new JSONObject().put("operation", "configure").put("messagesEnabled", messages).put("tasksEnabled", tasks));
    }
    private JSONObject message(String companion, String message) throws Exception {
        messageIds.add(companion.hashCode() & 0x7fffffff);
        return delivery.request(new JSONObject().put("operation", "companion").put("companionId", companion)
            .put("name", "Notification fixture").put("messageId", message).put("text", "Synthetic message"));
    }
    private JSONObject task(String session, String key, String text) throws Exception {
        String id = ProactiveNoticeStore.identity(session, "1", key, "system", "", "");
        taskIds.add(id.hashCode() & 0x7fffffff);
        return delivery.request(new JSONObject().put("operation", "send").put("taskId", session).put("turnId", "1")
            .put("callId", "fixture-call").put("noticeKey", key).put("text", text).put("attention", "normal")
            .put("destinations", new JSONArray().put(new JSONObject().put("channel", "system")))).getJSONArray("results").getJSONObject(0);
    }
    private StatusBarNotification posted(String tag, int id) throws Exception {
        DeviceUi.await("Notification publication", 3000, () -> find(tag, id) != null);
        return find(tag, id);
    }
    private StatusBarNotification find(String tag, int id) {
        for (StatusBarNotification item : manager.getActiveNotifications()) if (tag.equals(item.getTag()) && item.getId() == id) return item;
        return null;
    }
    private void assertChannelAvailable(String id) {
        assertTrue("Enable fixture channel in development notification settings: " + id,
            manager.getNotificationChannel(id).getImportance() != NotificationManager.IMPORTANCE_NONE);
    }
    private static boolean ownedTag(String tag) {
        return ProactiveNotifications.COMPANION_TAG.equals(tag) || "droy-task-notice".equals(tag);
    }
}
