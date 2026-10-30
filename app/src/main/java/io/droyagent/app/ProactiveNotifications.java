package io.droyagent.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/** Product notification choices and native publication, independent from incoming-notification management. */
final class ProactiveNotifications {
    private static final String DATA = "proactiveNotificationRecords";
    static final String MESSAGES = "notifyMessagesEnabled", TASKS = "notifyTasksEnabled";
    static final String MESSAGE_CHANNEL = "companion_messages";
    private static final String TAG = "droy-task-notice";
    static final String COMPANION_TAG = "droy-companion-notice";
    private static ProactiveNotifications instance;
    private final Context context;
    private final SharedPreferences preferences;
    private final NotificationManager notifications;
    private final ProactiveNoticeStore store;
    private volatile boolean active;

    static synchronized ProactiveNotifications get(Context context) {
        if (instance == null) {
            try { instance = new ProactiveNotifications(context.getApplicationContext()); }
            catch (Exception failure) { throw new IllegalStateException(io.droyagent.app.NativeLocale.text("主动通知记录无法读取", "Unable to read proactive notification records"), failure); }
        }
        return instance;
    }

    private ProactiveNotifications(Context context) throws Exception {
        this.context = context;
        preferences = ProductPermissions.preferences(context);
        notifications = context.getSystemService(NotificationManager.class);
        store = new ProactiveNoticeStore(new JSONArray(preferences.getString(DATA, "[]")));
        save();
        createChannel("task_updates", NativeLocale.text("任务结果", "Task results"), NotificationManager.IMPORTANCE_LOW, false);
        createChannel("task_attention", NativeLocale.text("需要及时处理", "Needs attention"), NotificationManager.IMPORTANCE_DEFAULT, true);
        createChannel("task_urgent", NativeLocale.text("紧急重要事项", "Urgent and important"), NotificationManager.IMPORTANCE_HIGH, true);
        if (notifications.getNotificationChannel(MESSAGE_CHANNEL) == null) {
            NotificationChannel source = notifications.getNotificationChannel("task_attention");
            NotificationChannel channel = new NotificationChannel(MESSAGE_CHANNEL,
                NativeLocale.text("伙伴消息", "Companion messages"), source.getImportance());
            // Preserve the old shared channel's user choices when splitting message delivery.
            channel.setSound(source.getSound(), source.getAudioAttributes());
            channel.setVibrationPattern(source.getVibrationPattern());
            channel.enableVibration(source.shouldVibrate());
            channel.setShowBadge(source.canShowBadge());
            channel.setLockscreenVisibility(source.getLockscreenVisibility());
            notifications.createNotificationChannel(channel);
        }
    }

    private void createChannel(String id, String title, int importance, boolean vibrate) {
        if (notifications.getNotificationChannel(id) != null) return;
        NotificationChannel channel = new NotificationChannel(id, title, importance);
        channel.enableVibration(vibrate);
        if (vibrate) channel.setVibrationPattern(new long[]{0, 80});
        notifications.createNotificationChannel(channel);
    }

    private synchronized void migratePreferences(boolean legacyPluginEnabled) {
        boolean inherited = NotificationDeliveryPolicy.inheritedEnabled(legacyPluginEnabled,
            preferences.getBoolean("notifyLocalEnabled", true));
        SharedPreferences.Editor editor = preferences.edit();
        if (!preferences.contains(MESSAGES)) editor.putBoolean(MESSAGES, inherited);
        if (!preferences.contains(TASKS)) editor.putBoolean(TASKS, inherited);
        editor.remove("notifyAppsEnabled").remove("notifyAllowedApps");
        if (!editor.commit()) throw new IllegalStateException(NativeLocale.text("通知设置未保存", "Notification settings were not saved"));
    }

    static void refreshLocale(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        renameChannel(manager, MESSAGE_CHANNEL, NativeLocale.text("伙伴消息", "Companion messages"));
        renameChannel(manager, "task_updates", NativeLocale.text("任务结果", "Task results"));
        renameChannel(manager, "task_attention", NativeLocale.text("需要及时处理", "Needs attention"));
        renameChannel(manager, "task_urgent", NativeLocale.text("紧急重要事项", "Urgent and important"));
    }

    private static void renameChannel(NotificationManager manager, String id, String name) {
        NotificationChannel channel = manager.getNotificationChannel(id);
        if (channel == null) return;
        channel.setName(name);
        manager.createNotificationChannel(channel);
    }

    JSONObject request(JSONObject args) throws Exception { return request(args, true); }

    synchronized JSONObject request(JSONObject args, boolean includeApps) throws Exception {
        return switch (args.getString("operation")) {
            case "activate" -> { active = bool(args, "enabled"); if (active) migratePreferences(true); yield status(includeApps); }
            case "get" -> status(includeApps);
            case "configure" -> { configure(args); yield status(includeApps); }
            case "send" -> send(args);
            case "companion" -> companion(args);
            case "end" -> { string(args, "taskId", 256); yield new JSONObject().put("ok", true); }
            default -> throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("未知的主动通知操作", "Unknown proactive notification operation"));
        };
    }

    static boolean isCompanionId(String id) { return CompanionIdentity.isValid(id); }

    /** IM delivery is local only and never enters the task/app action channel. */
    private synchronized JSONObject companion(JSONObject args) throws Exception {
        for (java.util.Iterator<String> keys = args.keys(); keys.hasNext();) {
            if (!java.util.Set.of("operation", "companionId", "name", "text", "messageId").contains(keys.next()))
                throw new IllegalArgumentException(NativeLocale.text("伙伴通知参数无效", "Invalid companion notification arguments"));
        }
        String companion = string(args, "companionId", 160);
        if (!isCompanionId(companion)) throw new IllegalArgumentException(NativeLocale.text("伙伴不存在", "Companion not found"));
        String name = string(args, "name", 160), text = string(args, "text", 2000), message = string(args, "messageId", 160);
        JSONObject result = new JSONObject().put("companionId", companion).put("messageId", message).put("published", false);
        String reason = NotificationDeliveryPolicy.messageSuppression(
            preferences.getBoolean(MESSAGES, false), notifications.areNotificationsEnabled(),
            enabledChannel(MESSAGE_CHANNEL), NativeRuntimeHost.isCompanionVisible(companion));
        String receiptId = ProactiveNoticeStore.identity(companion, message, "message", "companion", "", "");
        JSONObject previous = store.find(receiptId);
        if (previous != null && !previous.optString("status").equals("failed"))
            return result.put("status", previous.optString("status")).put("duplicate", true);
        JSONObject receipt = new JSONObject().put("noticeId", receiptId).put("channel", "companion")
            .put("companionId", companion).put("messageId", message).put("status", "dispatching");
        if (previous == null) store.add(receipt); else { previous.put("status", "dispatching"); receipt = previous; }
        if (reason != null) {
            receipt.put("status", "suppressed"); save();
            return result.put("status", reason.equals("conversation_visible") ? "suppressed" : "disabled").put("reason", reason);
        }
        save();
        int id = companion.hashCode() & 0x7fffffff;
        PendingIntent open = PendingIntent.getActivity(context, id,
            new Intent(context, NativeAppActivity.class).putExtra("companionId", companion)
                .setData(new android.net.Uri.Builder().scheme("droy").authority("companion").appendPath(companion).build())
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            // Keep one current notification per companion; publication itself
            // never foregrounds Droy or changes the currently selected chat.
            notifications.notify(COMPANION_TAG, id, new Notification.Builder(context, MESSAGE_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_droy).setContentTitle(name).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(false)
                .setVisibility(Notification.VISIBILITY_PRIVATE).build());
        } catch (SecurityException revoked) {
            receipt.put("status", "failed"); save();
            return result.put("status", "disabled").put("reason", "system_disabled");
        }
        receipt.put("status", "published"); save();
        return result.put("status", "published").put("published", true);
    }

    synchronized void clearCompanion(String companionId) throws Exception {
        if (!isCompanionId(companionId)) throw new IllegalArgumentException(NativeLocale.text("伙伴不存在", "Companion not found"));
        store.removeCompanion(companionId);
        save();
        notifications.cancel(COMPANION_TAG, companionId.hashCode() & 0x7fffffff);
    }

    JSONObject status() throws Exception { return status(true); }

    JSONObject status(boolean includeApps) throws Exception {
        JSONObject pet = PetOverlay.notificationState(context, "");
        boolean messages = preferences.getBoolean(MESSAGES, false), tasks = preferences.getBoolean(TASKS, false);
        boolean system = active && tasks && notifications.areNotificationsEnabled()
            && (enabledChannel("task_updates") || enabledChannel("task_attention") || enabledChannel("task_urgent"));
        return new JSONObject().put("pluginActive", active).put("messagesEnabled", messages).put("tasksEnabled", tasks)
            .put("channels", new JSONArray()
                .put(channel("system", system, !tasks ? NativeLocale.text("任务通知已关闭", "Task notifications are off")
                    : NativeLocale.text("系统通知权限或渠道未开启", "System notification permission or channel is disabled")))
                .put(channel("pet", active && pet.optBoolean("petAvailable"), NativeLocale.text("当前无法显示宠物", "The pet cannot be shown now"))))
            .put("context", pet.put("returnOnCompletion", preferences.getBoolean("returnToDroyOnCompletion", false)));
    }

    private static JSONObject channel(String id, boolean available, String reason) throws Exception {
        return new JSONObject().put("id", id).put("available", available).put("reason", available ? "" : reason);
    }

    private synchronized void configure(JSONObject args) throws Exception {
        for (java.util.Iterator<String> keys = args.keys(); keys.hasNext();) {
            if (!java.util.Set.of("operation", "messagesEnabled", "tasksEnabled").contains(keys.next()))
                throw new IllegalArgumentException(NativeLocale.text("通知设置无效", "Invalid notification settings"));
        }
        migratePreferences(active);
        SharedPreferences.Editor editor = preferences.edit();
        if (args.has("messagesEnabled")) editor.putBoolean(MESSAGES, bool(args, "messagesEnabled"));
        if (args.has("tasksEnabled")) editor.putBoolean(TASKS, bool(args, "tasksEnabled"));
        if (!editor.commit()) throw new IllegalStateException(NativeLocale.text("通知设置未保存", "Notification settings were not saved"));
        for (android.service.notification.StatusBarNotification posted : notifications.getActiveNotifications()) {
            if ((!preferences.getBoolean(MESSAGES, false) && COMPANION_TAG.equals(posted.getTag()))
                || (!preferences.getBoolean(TASKS, false) && TAG.equals(posted.getTag())))
                notifications.cancel(posted.getTag(), posted.getId());
        }
    }

    private synchronized JSONObject send(JSONObject args) throws Exception {
        if (!active) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("主动通知插件已关闭", "The proactive notification plugin is disabled"));
        String task = string(args, "taskId", 256), turn = String.valueOf(args.get("turnId"));
        if (turn.isBlank() || turn.equals("null") || turn.length() > 100) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("缺少当前执行轮", "The current execution turn is missing"));
        string(args, "callId", 256);
        String key = string(args, "noticeKey", 128), title = optional(args, "title", 160), text = string(args, "text", 2000);
        String attention = args.optString("attention", "quiet");
        if (!java.util.Set.of("quiet", "normal", "urgent").contains(attention)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("提醒强度无效", "Invalid alert level"));
        JSONArray destinations = args.getJSONArray("destinations"), results = new JSONArray();
        if (destinations.length() < 1 || destinations.length() > 3) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("请选择少量通知渠道", "Please select a small number of notification channels"));
        for (int i = 0; i < destinations.length(); i++) {
            if (!java.util.Set.of("system", "pet").contains(destinations.getJSONObject(i).optString("channel")))
                throw new IllegalArgumentException(NativeLocale.text("通知渠道不可用", "Notification channel is unavailable"));
        }
        for (int i = 0; i < destinations.length(); i++) {
            JSONObject dest = destinations.getJSONObject(i);
            String selected = string(dest, "channel", 40), pkg = "", target = "";
            String id = ProactiveNoticeStore.identity(task, turn, key, selected, pkg, target);
            JSONObject record;
            synchronized (store) {
                record = store.find(id);
                if (record != null && !record.optString("status").equals("failed")
                        && (record.optString("text").equals(text) && record.optString("title").equals(title))) {
                    results.put(publicRecord(record).put("duplicate", true)); continue;
                }
                boolean existing = record != null;
                boolean update = existing && !record.optString("status").equals("failed");
                if (!existing) {
                    record = new JSONObject().put("noticeId", id).put("taskId", task).put("turnId", turn)
                        .put("noticeKey", key).put("channel", selected).put("packageName", pkg).put("target", target);
                    store.add(record);
                }
                record.put("title", title).put("text", text).put("time", System.currentTimeMillis())
                    .put("status", "dispatching").put("update", update);
                record.remove("reason");
                if (!update) { record.remove("actionStarted"); record.remove("evidence"); }
                save();
            }
            try {
                switch (selected) {
                    case "system" -> {
                        checkTask();
                        record.put("status", publish(record, attention, record.optBoolean("update")) ? "published" : "suppressed");
                    }
                    case "pet" -> {
                        if (record.optBoolean("update")) { PetOverlay.updateNotice(context, id, text); record.put("status", "updated"); }
                        else if (PetOverlay.notify(context, task, id, text, () -> active)) record.put("status", "shown");
                        else throw new IllegalStateException(io.droyagent.app.NativeLocale.text("当前无法展示伙伴形象提示，请选择其他渠道", "The companion alert cannot be displayed now. Please choose another channel"));
                    }
                    default -> throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("通知渠道尚未接入", "This notification channel is not connected yet"));
                }
            } catch (java.util.concurrent.TimeoutException unconfirmed) {
                record.put("status", "unknown").put("reason", io.droyagent.app.NativeLocale.text("伙伴形象显示结果尚未确认", "The companion display result is unconfirmed"));
            } catch (IllegalArgumentException | IllegalStateException | SecurityException failure) {
                record.put("status", "failed").put("reason", failure.getMessage());
            }
            save(); results.put(publicRecord(record));
        }
        return new JSONObject().put("results", results);
    }

    private void checkTask() {
        if (!NotificationDeliveryPolicy.taskAllowed(preferences.getBoolean(TASKS, false), active))
            throw new IllegalStateException(NativeLocale.text("任务通知已关闭", "Task notifications are off"));
    }
    private boolean enabledChannel(String id) { NotificationChannel channel = notifications.getNotificationChannel(id); return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE; }
    private boolean publish(JSONObject record, String attention, boolean update) throws Exception {
        checkTask();
        if (NativeRuntimeHost.isConversationVisible(record.getString("taskId"))) return false;
        String channel = attention.equals("urgent") ? "task_urgent" : attention.equals("normal") ? "task_attention" : "task_updates";
        if (!notifications.areNotificationsEnabled() || !enabledChannel(channel)) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请开启 Droy 对应通知渠道", "Please enable the corresponding Droy notification channel"));
        String id = record.getString("noticeId");
        int numeric = id.hashCode() & 0x7fffffff;
        PendingIntent open = PendingIntent.getActivity(context, numeric,
            new Intent(context, NativeAppActivity.class).putExtra("petTaskId", record.getString("taskId"))
                .setData(android.net.Uri.parse("droy://notification/" + id))
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String title = record.optString("title");
        notifications.notify(TAG, numeric, new Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification_droy)
            .setContentTitle(title.isBlank() ? io.droyagent.app.NativeLocale.text("Droy · 任务消息", "Droy · Task message") : title).setContentText(record.getString("text"))
            .setStyle(new Notification.BigTextStyle().bigText(record.getString("text"))).setContentIntent(open)
            .setAutoCancel(true).setOnlyAlertOnce(update).setVisibility(Notification.VISIBILITY_PRIVATE).build());
        return true;
    }
    private JSONObject publicRecord(JSONObject record) throws Exception {
        JSONObject result = new JSONObject(record.toString());
        if (result.optString("status").equals("dispatching")) result.put("status", "unknown");
        return result;
    }
    private void save() throws Exception {
        if (!preferences.edit().putString(DATA, store.snapshot().toString()).commit()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("主动通知结果未保存", "Proactive notification results were not saved"));
    }
    private static boolean bool(JSONObject args, String key) throws Exception {
        Object value = args.get(key); if (!(value instanceof Boolean)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("此设置需要开关值", "This setting requires a boolean value")); return (Boolean)value;
    }
    private static String string(JSONObject args, String key, int max) throws Exception {
        Object value = args.get(key);
        if (!(value instanceof String text) || text.isBlank() || text.length() > max) throw new IllegalArgumentException(key + io.droyagent.app.NativeLocale.text(" 无效", " is invalid"));
        return text.trim();
    }
    private static String optional(JSONObject args, String key, int max) throws Exception {
        if (!args.has(key)) return "";
        Object value = args.get(key);
        if (!(value instanceof String text) || text.length() > max) throw new IllegalArgumentException(key + io.droyagent.app.NativeLocale.text(" 无效", " is invalid"));
        return text.trim();
    }
}
