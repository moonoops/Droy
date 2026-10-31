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
    private static final String MESSAGE_POSITIONS = "companionNotificationPositions";
    static final String MESSAGES = "notifyMessagesEnabled", TASKS = "notifyTasksEnabled";
    static final String MESSAGE_CHANNEL = "companion_messages";
    static final String QUIET_MESSAGE_CHANNEL = "companion_messages_quiet";
    private static final String TAG = "droy-task-notice";
    static final String COMPANION_TAG = "droy-companion-notice";
    private static volatile ProactiveNotifications instance;
    private final Context context;
    private final SharedPreferences preferences;
    private final NotificationManager notifications;
    private final ProactiveNoticeStore store;
    private final CompanionNotificationHistory messageHistory;
    private final android.os.Handler localeUpdates = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.LinkedHashSet<String> pendingLocaleUpdates = new java.util.LinkedHashSet<>();
    private boolean localeUpdateScheduled;
    private long nextLocaleUpdateAt;
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
        messageHistory = new CompanionNotificationHistory(store, new JSONObject(preferences.getString(MESSAGE_POSITIONS, "{}")));
        save();
        createChannel("task_updates", NativeLocale.text("任务结果", "Task results"), NotificationManager.IMPORTANCE_LOW, false);
        createChannel("task_attention", NativeLocale.text("需要及时处理", "Needs attention"), NotificationManager.IMPORTANCE_DEFAULT, true);
        createChannel("task_urgent", NativeLocale.text("紧急重要事项", "Urgent and important"), NotificationManager.IMPORTANCE_HIGH, true);
        if (notifications.getNotificationChannel(MESSAGE_CHANNEL) == null) {
            NotificationChannel source = notifications.getNotificationChannel("task_attention");
            NotificationChannel channel = new NotificationChannel(MESSAGE_CHANNEL,
                CompanionNotificationPresentation.text(context, R.string.cnotice_channel), source.getImportance());
            // Preserve the old shared channel's user choices when splitting message delivery.
            channel.setSound(source.getSound(), source.getAudioAttributes());
            channel.setVibrationPattern(source.getVibrationPattern());
            channel.enableVibration(source.shouldVibrate());
            channel.setShowBadge(source.canShowBadge());
            channel.setLockscreenVisibility(source.getLockscreenVisibility());
            notifications.createNotificationChannel(channel);
        }
        if (notifications.getNotificationChannel(QUIET_MESSAGE_CHANNEL) == null) {
            NotificationChannel source = notifications.getNotificationChannel(MESSAGE_CHANNEL);
            NotificationChannel quiet = new NotificationChannel(QUIET_MESSAGE_CHANNEL,
                CompanionNotificationPresentation.text(context, R.string.cnotice_quiet_channel),
                Math.max(NotificationManager.IMPORTANCE_MIN, Math.min(NotificationManager.IMPORTANCE_LOW, source.getImportance())));
            quiet.setSound(null, null); quiet.enableVibration(false);
            quiet.setShowBadge(source.canShowBadge()); quiet.setLockscreenVisibility(source.getLockscreenVisibility());
            notifications.createNotificationChannel(quiet);
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
        renameChannel(manager, MESSAGE_CHANNEL, CompanionNotificationPresentation.text(context, R.string.cnotice_channel));
        renameChannel(manager, QUIET_MESSAGE_CHANNEL, CompanionNotificationPresentation.text(context, R.string.cnotice_quiet_channel));
        renameChannel(manager, "task_updates", NativeLocale.text("任务结果", "Task results"));
        renameChannel(manager, "task_attention", NativeLocale.text("需要及时处理", "Needs attention"));
        renameChannel(manager, "task_urgent", NativeLocale.text("紧急重要事项", "Urgent and important"));
        ProactiveNotifications current = instance;
        if (current != null) current.scheduleLocaleUpdates();
    }

    private synchronized void scheduleLocaleUpdates() {
        pendingLocaleUpdates.clear();
        for (android.service.notification.StatusBarNotification posted : notifications.getActiveNotifications()) {
            String tag = posted.getTag();
            if (tag != null && tag.startsWith(COMPANION_TAG + ":")) pendingLocaleUpdates.add(tag.substring(COMPANION_TAG.length() + 1));
        }
        if (localeUpdateScheduled || pendingLocaleUpdates.isEmpty()) return;
        localeUpdateScheduled = true;
        localeUpdates.postDelayed(this::refreshNextLocale, Math.max(500, nextLocaleUpdateAt - android.os.SystemClock.uptimeMillis()));
    }

    private synchronized void refreshNextLocale() {
        if (instance != this || pendingLocaleUpdates.isEmpty()) {
            pendingLocaleUpdates.clear(); localeUpdateScheduled = false; return;
        }
        String companion = pendingLocaleUpdates.iterator().next();
        pendingLocaleUpdates.remove(companion);
        try { refreshCompanion(companion); }
        catch (Exception failure) {
            android.util.Log.e("DroyNotifications", "COMPANION_NOTIFICATION_LOCALE_REFRESH_FAILED", failure);
            android.widget.Toast.makeText(context, CompanionNotificationPresentation.text(context, R.string.cnotice_language_update_failed),
                android.widget.Toast.LENGTH_LONG).show();
        }
        // A language change can update several conversations. Leave publication capacity for new messages.
        nextLocaleUpdateAt = android.os.SystemClock.uptimeMillis() + 500;
        if (pendingLocaleUpdates.isEmpty()) localeUpdateScheduled = false;
        else localeUpdates.postDelayed(this::refreshNextLocale, 500);
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
            case "companionRead" -> companionRead(args);
            case "companionRemove" -> companionRemove(args);
            case "end" -> { string(args, "taskId", 256); yield new JSONObject().put("ok", true); }
            default -> throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("未知的主动通知操作", "Unknown proactive notification operation"));
        };
    }

    static boolean isCompanionId(String id) { return CompanionIdentity.isValid(id); }

    static String companionTag(String companionId) { return COMPANION_TAG + ":" + companionId; }
    static boolean isCompanionTag(String tag) { return COMPANION_TAG.equals(tag) || tag != null && tag.startsWith(COMPANION_TAG + ":"); }

    /** IM delivery is local only and never enters the task/app action channel. */
    private synchronized JSONObject companion(JSONObject args) throws Exception {
        fields(args, java.util.Set.of("operation", "companionId", "name", "text", "messageId", "sequence", "sentAt", "quiet", "alert", "avatar", "unreadCount", "throughSequence"));
        String companion = checkedCompanion(args);
        String name = string(args, "name", 160), text = string(args, "text", 4000), message = string(args, "messageId", 160);
        if (text.codePointCount(0, text.length()) > 2000) throw new IllegalArgumentException(NativeLocale.text("伙伴通知正文无效", "Invalid companion notification text"));
        long sequence = integer(args, "sequence", 1, 0), sentAt = integer(args, "sentAt", 1, System.currentTimeMillis());
        boolean quiet = args.has("quiet") && bool(args, "quiet"), alert = !args.has("alert") || bool(args, "alert");
        Integer unread = count(args);
        String avatar = optional(args, "avatar", 1000);
        long previousRead = messageHistory.readThrough(companion);
        int previousDisplayedCount = Math.max(messageHistory.count(companion), messageHistory.pending(companion).size());
        if (args.has("throughSequence")) {
            long through = integer(args, "throughSequence", 0, 0);
            messageHistory.read(companion, through, null);
            // A delayed publication can arrive after a newer read callback.
            if (through < previousRead) unread = null;
        }
        JSONObject result = new JSONObject().put("companionId", companion).put("messageId", message).put("published", false);
        String receiptId = ProactiveNoticeStore.identity(companion, message, "message", "companion", "", "");
        JSONObject previous = store.find(receiptId);
        if (previous != null && !java.util.Set.of("failed", "unknown", "dispatching").contains(previous.optString("status"))) {
            if (unread != null) messageHistory.count(companion, unread);
            save();
            if (messageHistory.readThrough(companion) > previousRead
                || Math.max(messageHistory.count(companion), messageHistory.pending(companion).size()) != previousDisplayedCount)
                refreshCompanion(companion);
            return result.put("status", previous.optString("status")).put("duplicate", true);
        }
        boolean replay = previous != null;
        JSONObject receipt = previous == null ? new JSONObject().put("noticeId", receiptId).put("channel", "companion")
            .put("companionId", companion).put("messageId", message) : previous;
        receipt.put("name", name).put("text", text).put("sequence", sequence).put("sentAt", sentAt).put("quiet", quiet).put("avatar", avatar).put("status", "dispatching");
        if (previous == null) store.add(receipt);
        if (unread != null) messageHistory.count(companion, unread);
        if (sequence > 0 && sequence <= messageHistory.readThrough(companion)) {
            receipt.put("status", "read"); save();
            refreshCompanion(companion);
            return result.put("status", "read").put("reason", "already_read");
        }
        android.service.notification.StatusBarNotification existing = postedCompanion(companion);
        String channel = quiet || ((!alert || replay) && existing == null) ? QUIET_MESSAGE_CHANNEL
            : (!alert || replay) && existing != null ? existing.getNotification().getChannelId() : MESSAGE_CHANNEL;
        String reason = NotificationDeliveryPolicy.messageSuppression(
            preferences.getBoolean(MESSAGES, false), notifications.areNotificationsEnabled(),
            enabledChannel(MESSAGE_CHANNEL) && enabledChannel(channel), NativeRuntimeHost.isCompanionVisible(companion));
        if (reason != null) {
            receipt.put("status", "suppressed"); save();
            refreshCompanion(companion);
            return result.put("status", reason.equals("conversation_visible") ? "suppressed" : "disabled").put("reason", reason);
        }
        receipt.put("postedChannel", channel); save();
        try {
            notifications.notify(companionTag(companion), companion.hashCode() & 0x7fffffff,
                CompanionNotificationPresentation.build(context, companion, messageHistory.recent(companion),
                    Math.max(messageHistory.count(companion), messageHistory.pending(companion).size()), channel, quiet || !alert || replay));
            notifications.cancel(COMPANION_TAG, companion.hashCode() & 0x7fffffff);
        } catch (SecurityException revoked) {
            receipt.put("status", "failed"); save();
            return result.put("status", "disabled").put("reason", "system_disabled");
        }
        receipt.put("status", "published"); save();
        return result.put("status", "published").put("published", true);
    }

    private JSONObject companionRead(JSONObject args) throws Exception {
        fields(args, java.util.Set.of("operation", "companionId", "throughSequence", "unreadCount"));
        String companion = checkedCompanion(args);
        if (!args.has("throughSequence")) throw new IllegalArgumentException(NativeLocale.text("缺少已读位置", "The read position is missing"));
        long through = integer(args, "throughSequence", 0, 0);
        messageHistory.read(companion, through, count(args)); save();
        refreshCompanion(companion);
        return new JSONObject().put("ok", true).put("throughSequence", messageHistory.readThrough(companion));
    }

    private JSONObject companionRemove(JSONObject args) throws Exception {
        fields(args, java.util.Set.of("operation", "companionId", "messageId", "unreadCount"));
        String companion = checkedCompanion(args);
        messageHistory.remove(companion, string(args, "messageId", 160), count(args)); save();
        refreshCompanion(companion);
        return new JSONObject().put("ok", true);
    }

    synchronized void dismissCompanion(String companion, String message) throws Exception {
        messageHistory.dismiss(companion, message); save();
        // A new message can arrive after the swipe but before its delete intent is handled.
        refreshCompanion(companion);
    }

    private void refreshCompanion(String companion) throws Exception {
        android.service.notification.StatusBarNotification existing = postedCompanion(companion);
        if (existing == null) {
            notifications.cancel(COMPANION_TAG, companion.hashCode() & 0x7fffffff);
            return;
        }
        java.util.List<JSONObject> rows = messageHistory.recent(companion);
        if (rows.isEmpty()) { notifications.cancel(existing.getTag(), existing.getId()); return; }
        String channel = existing.getNotification().getChannelId();
        String latestChannel = rows.get(rows.size() - 1).optString("postedChannel");
        if (MESSAGE_CHANNEL.equals(latestChannel) || QUIET_MESSAGE_CHANNEL.equals(latestChannel)) channel = latestChannel;
        if (!preferences.getBoolean(MESSAGES, false) || !notifications.areNotificationsEnabled()
            || !enabledChannel(MESSAGE_CHANNEL) || !enabledChannel(channel)) {
            notifications.cancel(existing.getTag(), existing.getId()); return;
        }
        int unread = Math.max(messageHistory.count(companion), messageHistory.pending(companion).size());
        // Repeated read callbacks must not re-post an unchanged card or consume Android's update budget.
        if (CompanionNotificationPresentation.matches(existing.getNotification(), companion, rows, unread, channel)) return;
        notifications.notify(companionTag(companion), companion.hashCode() & 0x7fffffff,
            CompanionNotificationPresentation.build(context, companion, rows, unread, channel, true));
    }

    private android.service.notification.StatusBarNotification postedCompanion(String companion) {
        for (android.service.notification.StatusBarNotification posted : notifications.getActiveNotifications())
            if (companionTag(companion).equals(posted.getTag()) && posted.getId() == (companion.hashCode() & 0x7fffffff)) return posted;
        return null;
    }

    synchronized void clearCompanion(String companionId) throws Exception {
        if (!isCompanionId(companionId)) throw new IllegalArgumentException(NativeLocale.text("伙伴不存在", "Companion not found"));
        messageHistory.clear(companionId); save();
        notifications.cancel(companionTag(companionId), companionId.hashCode() & 0x7fffffff);
        notifications.cancel(COMPANION_TAG, companionId.hashCode() & 0x7fffffff);
        context.getSystemService(android.content.pm.ShortcutManager.class).removeLongLivedShortcuts(java.util.List.of("companion:" + companionId));
        context.getSystemService(android.content.pm.ShortcutManager.class).removeDynamicShortcuts(java.util.List.of("companion:" + companionId));
    }

    private static void fields(JSONObject args, java.util.Set<String> allowed) {
        for (java.util.Iterator<String> keys = args.keys(); keys.hasNext();) if (!allowed.contains(keys.next()))
            throw new IllegalArgumentException(NativeLocale.text("伙伴通知参数无效", "Invalid companion notification arguments"));
    }
    private static String checkedCompanion(JSONObject args) throws Exception {
        String companion = string(args, "companionId", 160);
        if (!isCompanionId(companion)) throw new IllegalArgumentException(NativeLocale.text("伙伴不存在", "Companion not found"));
        return companion;
    }
    static long integer(JSONObject args, String key, long min, long fallback) throws Exception {
        if (!args.has(key)) return fallback;
        Object value = args.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() != Math.rint(number.doubleValue())
            || number.doubleValue() < min || number.doubleValue() > 9007199254740991d) throw new IllegalArgumentException(key + NativeLocale.text(" 无效", " is invalid"));
        return ((Number)value).longValue();
    }
    private static Integer count(JSONObject args) throws Exception {
        if (!args.has("unreadCount")) return null;
        long count = integer(args, "unreadCount", 0, 0);
        if (count > Integer.MAX_VALUE) throw new IllegalArgumentException(NativeLocale.text("未读消息数量无效", "Invalid unread message count"));
        return (int)count;
    }

    JSONObject status() throws Exception { return status(true); }

    JSONObject status(boolean includeApps) throws Exception {
        JSONObject pet = PetOverlay.notificationState(context, "");
        boolean messages = preferences.getBoolean(MESSAGES, false), tasks = preferences.getBoolean(TASKS, false);
        boolean system = active && tasks && notifications.areNotificationsEnabled()
            && (enabledChannel("task_updates") || enabledChannel("task_attention") || enabledChannel("task_urgent"));
        return new JSONObject().put("pluginActive", active).put("messagesEnabled", messages).put("tasksEnabled", tasks)
            .put("messageDelivery", new JSONObject().put("systemEnabled", notifications.areNotificationsEnabled())
                .put("channelEnabled", enabledChannel(MESSAGE_CHANNEL)).put("quietChannelEnabled", enabledChannel(QUIET_MESSAGE_CHANNEL))
                .put("available", messages && notifications.areNotificationsEnabled() && enabledChannel(MESSAGE_CHANNEL))
                .put("quietAvailable", messages && notifications.areNotificationsEnabled() && enabledChannel(MESSAGE_CHANNEL) && enabledChannel(QUIET_MESSAGE_CHANNEL))
                .put("reason", !messages ? "messages_disabled" : !notifications.areNotificationsEnabled() ? "system_disabled" : !enabledChannel(MESSAGE_CHANNEL) ? "channel_disabled" : "")
                .put("channelId", MESSAGE_CHANNEL).put("quietChannelId", QUIET_MESSAGE_CHANNEL))
            .put("channels", new JSONArray()
                .put(channel("system", system, !tasks ? NativeLocale.text("任务通知已关闭", "Task notifications are off")
                    : NativeLocale.text("系统通知权限或渠道未开启", "System notification permission or channel is disabled")))
                .put(channel("pet", active && pet.optBoolean("petAvailable"), NativeLocale.text("当前无法显示宠物", "The pet cannot be shown now"))))
            .put("context", pet.put("returnOnCompletion", preferences.getBoolean("returnToDroyOnCompletion", true)));
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
            if ((!preferences.getBoolean(MESSAGES, false) && isCompanionTag(posted.getTag()))
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
        if (!preferences.edit().putString(DATA, store.snapshot().toString()).putString(MESSAGE_POSITIONS, messageHistory.snapshot().toString()).commit()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("主动通知结果未保存", "Proactive notification results were not saved"));
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
