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
    private final Set<String> companionIds = new HashSet<>();
    private final Set<Integer> taskIds = new HashSet<>();
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
        for (String companion : companionIds) delivery.clearCompanion(companion);
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
                posted(ProactiveNotifications.companionTag(companion), companion.hashCode() & 0x7fffffff).getNotification().getChannelId());
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
        assertFalse((posted(ProactiveNotifications.companionTag(companion), companion.hashCode() & 0x7fffffff).getNotification().flags
            & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
        long originalPostTime = posted(ProactiveNotifications.companionTag(companion), companion.hashCode() & 0x7fffffff).getPostTime();
        assertTrue(message(companion, "next").getBoolean("duplicate"));
        assertEquals("An unchanged duplicate must not re-post or reorder its notification", originalPostTime,
            posted(ProactiveNotifications.companionTag(companion), companion.hashCode() & 0x7fffffff).getPostTime());
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

    @Test public void silentMessagesRetainConversationHistoryAndOldReadsKeepNewMessages() throws Exception {
        configure(true, false);
        String companion = prefix + "-history";
        assertTrue(sequenced(companion, "old", 11, false, true, 1).getBoolean("published"));
        assertTrue(sequenced(companion, "new", 12, true, false, 2).getBoolean("published"));
        DeviceUi.await("Silent update must retain both unread messages", 3000, () -> {
            StatusBarNotification item = find(ProactiveNotifications.companionTag(companion), companion.hashCode() & 0x7fffffff);
            return item != null && ProactiveNotifications.QUIET_MESSAGE_CHANNEL.equals(item.getNotification().getChannelId())
                && item.getNotification().extras.getParcelableArray(Notification.EXTRA_MESSAGES).length == 2;
        });
        Notification notification = posted(ProactiveNotifications.companionTag(companion), companion.hashCode() & 0x7fffffff).getNotification();
        assertEquals(ProactiveNotifications.QUIET_MESSAGE_CHANNEL, notification.getChannelId());
        assertNull(manager.getNotificationChannel(notification.getChannelId()).getSound());
        assertFalse(manager.getNotificationChannel(notification.getChannelId()).shouldVibrate());
        assertEquals(Notification.GROUP_ALERT_SUMMARY, notification.getGroupAlertBehavior());
        assertEquals(2, notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES).length);
        assertEquals(2, notification.number);
        assertNotNull(notification.getShortcutId());
        assertNotNull(notification.publicVersion);
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility);
        assertEquals(Notification.MessagingStyle.class.getName(), notification.extras.getString(Notification.EXTRA_TEMPLATE));
        assertEquals(2, notification.actions.length);
        assertEquals(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ, notification.actions[1].getSemanticAction());
        delivery.request(new JSONObject().put("operation", "companionRead").put("companionId", companion).put("throughSequence", 11).put("unreadCount", 1));
        DeviceUi.await("An old read must leave the later message", 3000, () -> {
            StatusBarNotification item = find(ProactiveNotifications.companionTag(companion), companion.hashCode() & 0x7fffffff);
            return item != null && item.getNotification().extras.getParcelableArray(Notification.EXTRA_MESSAGES).length == 1;
        });
        delivery.request(new JSONObject().put("operation", "companionRemove").put("companionId", companion).put("messageId", "new").put("unreadCount", 0));
        DeviceUi.await("Removing the final message must clear only its notice", 3000,
            () -> find(ProactiveNotifications.companionTag(companion), companion.hashCode() & 0x7fffffff) == null);
        assertEquals("read", sequenced(companion, "delayed-old", 11, false, false, 0).getString("status"));
    }

    @Test public void unchangedReadRepairsAShedChannelChangeWithoutRepostingItAgain() throws Exception {
        configure(true, false);
        String companion = prefix + "-channel-repair", tag = ProactiveNotifications.companionTag(companion);
        int id = companion.hashCode() & 0x7fffffff;
        sequenced(companion, "normal", 21, false, true, 1);
        Notification stale = posted(tag, id).getNotification();
        sequenced(companion, "quiet", 22, true, false, 2);
        DeviceUi.await("The quiet arrival must first be posted", 3000, () -> {
            StatusBarNotification item = find(tag, id);
            return item != null && ProactiveNotifications.QUIET_MESSAGE_CHANNEL.equals(item.getNotification().getChannelId());
        });
        manager.notify(tag, id, stale);
        DeviceUi.await("Use the actual old system card to reproduce a dropped channel update", 3000, () -> {
            StatusBarNotification item = find(tag, id);
            return item != null && ProactiveNotifications.MESSAGE_CHANNEL.equals(item.getNotification().getChannelId())
                && item.getNotification().number == 1;
        });
        JSONObject read = new JSONObject().put("operation", "companionRead").put("companionId", companion)
            .put("throughSequence", 0).put("unreadCount", 2);
        delivery.request(read);
        DeviceUi.await("An unchanged read must restore the latest receipt's channel and messages", 3000, () -> {
            StatusBarNotification item = find(tag, id);
            return item != null && ProactiveNotifications.QUIET_MESSAGE_CHANNEL.equals(item.getNotification().getChannelId())
                && item.getNotification().number == 2;
        });
        Notification repaired = posted(tag, id).getNotification();
        assertEquals(Notification.GROUP_ALERT_SUMMARY, repaired.getGroupAlertBehavior());
        assertTrue((repaired.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
        long postTime = posted(tag, id).getPostTime();
        delivery.request(read);
        android.os.SystemClock.sleep(300);
        assertEquals("An identical read must not re-post the repaired card", postTime, posted(tag, id).getPostTime());
    }

    @Test public void companionsWithCollidingHashCodesStillHaveIndependentNotifications() throws Exception {
        configure(true, false);
        String first = prefix + "Aa", second = prefix + "BB";
        assertEquals(first.hashCode(), second.hashCode());
        assertTrue(message(first, "first").getBoolean("published"));
        assertTrue(message(second, "second").getBoolean("published"));
        assertNotNull(posted(ProactiveNotifications.companionTag(first), first.hashCode() & 0x7fffffff));
        assertNotNull(posted(ProactiveNotifications.companionTag(second), second.hashCode() & 0x7fffffff));
        configure(false, false);
        assertEquals("messages_disabled", sequenced(first, "disabled-quiet", 1, true, true, 1).getString("reason"));
        assertNull(find(ProactiveNotifications.companionTag(first), first.hashCode() & 0x7fffffff));
    }

    @Test public void laterMessagesHealAMissedReadCallbackAndDuplicateStillClearsReadRows() throws Exception {
        configure(true, false);
        String companion = prefix + "-read-heal";
        assertTrue(sequenced(companion, "old", 31, false, true, 1).getBoolean("published"));
        JSONObject current = new JSONObject().put("operation", "companion").put("companionId", companion)
            .put("name", "Notification fixture").put("messageId", "current").put("text", "Synthetic current")
            .put("sequence", 32).put("throughSequence", 31).put("unreadCount", 1);
        assertTrue(delivery.request(current).getBoolean("published"));
        DeviceUi.await("A new message must apply the latest authoritative read cursor", 3000, () -> {
            StatusBarNotification item = find(ProactiveNotifications.companionTag(companion), companion.hashCode() & 0x7fffffff);
            return item != null && item.getNotification().extras.getParcelableArray(Notification.EXTRA_MESSAGES).length == 1;
        });
        current.put("unreadCount", 3);
        assertTrue(delivery.request(current).getBoolean("duplicate"));
        DeviceUi.await("A changed authoritative unread count must update the existing notification", 3000, () -> {
            StatusBarNotification item = find(ProactiveNotifications.companionTag(companion), companion.hashCode() & 0x7fffffff);
            return item != null && item.getNotification().number == 3;
        });
        current.put("throughSequence", 0).put("unreadCount", 9);
        assertTrue(delivery.request(current).getBoolean("duplicate"));
        assertEquals("A publication delayed behind a newer read cannot restore its stale unread count", 3,
            posted(ProactiveNotifications.companionTag(companion), companion.hashCode() & 0x7fffffff).getNotification().number);
        current.put("throughSequence", 32).put("unreadCount", 0);
        assertTrue(delivery.request(current).getBoolean("duplicate"));
        DeviceUi.await("Even a duplicate payload must clear rows already read on the server", 3000,
            () -> find(ProactiveNotifications.companionTag(companion), companion.hashCode() & 0x7fffffff) == null);
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
                posted(ProactiveNotifications.companionTag(companion), id).getNotification().getChannelId());
            stage("observation.active.utc_ms=" + System.currentTimeMillis() + ".window_ms=15000");
            android.os.SystemClock.sleep(15_000);
            assertNotNull("The synthetic notification must remain active during observation",
                find(ProactiveNotifications.companionTag(companion), id));
        } finally {
            manager.cancel(ProactiveNotifications.companionTag(companion), id);
            DeviceUi.await("Synthetic observation notification cancelled", 3000,
                () -> find(ProactiveNotifications.companionTag(companion), id) == null);
            stage("observation.cancelled.utc_ms=" + System.currentTimeMillis());
        }
    }

    private void configure(boolean messages, boolean tasks) throws Exception {
        delivery.request(new JSONObject().put("operation", "configure").put("messagesEnabled", messages).put("tasksEnabled", tasks));
    }
    private JSONObject message(String companion, String message) throws Exception {
        companionIds.add(companion);
        return delivery.request(new JSONObject().put("operation", "companion").put("companionId", companion)
            .put("name", "Notification fixture").put("messageId", message).put("text", "Synthetic message"));
    }
    private JSONObject sequenced(String companion, String message, long sequence, boolean quiet, boolean alert, int count) throws Exception {
        companionIds.add(companion);
        return delivery.request(new JSONObject().put("operation", "companion").put("companionId", companion)
            .put("name", "Notification fixture").put("messageId", message).put("text", "Synthetic " + message)
            .put("sequence", sequence).put("sentAt", System.currentTimeMillis()).put("quiet", quiet).put("alert", alert).put("unreadCount", count));
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
        return ProactiveNotifications.isCompanionTag(tag) || "droy-task-notice".equals(tag);
    }
}
