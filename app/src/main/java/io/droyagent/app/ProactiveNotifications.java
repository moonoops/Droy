package io.droyagent.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Product notification choices and native publication, independent from incoming-notification management. */
final class ProactiveNotifications {
    private static final String DATA = "proactiveNotificationRecords", ALLOWED = "notifyAllowedApps";
    private static final String TAG = "droy-task-notice";
    private static ProactiveNotifications instance;
    private final Context context;
    private final SharedPreferences preferences;
    private final NotificationManager notifications;
    private final ProactiveNoticeStore store;
    private final Map<String, String> appContexts = new ConcurrentHashMap<>();
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
        notifications.createNotificationChannel(new NotificationChannel("task_updates", io.droyagent.app.NativeLocale.text("任务结果", "Task results"), NotificationManager.IMPORTANCE_LOW));
        notifications.createNotificationChannel(new NotificationChannel("task_attention", io.droyagent.app.NativeLocale.text("需要及时处理", "Needs attention"), NotificationManager.IMPORTANCE_DEFAULT));
        notifications.createNotificationChannel(new NotificationChannel("task_urgent", io.droyagent.app.NativeLocale.text("紧急重要事项", "Urgent and important"), NotificationManager.IMPORTANCE_HIGH));
    }

    static void refreshLocale(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
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

    JSONObject request(JSONObject args) throws Exception {
        return switch (args.getString("operation")) {
            case "activate" -> { active = bool(args, "enabled"); if (!active) endAll(); yield status(); }
            case "get" -> status();
            case "configure" -> { configure(args); yield status(); }
            case "send" -> send(args);
            case "complete" -> complete(args);
            case "end" -> { end(string(args, "taskId", 256)); yield new JSONObject().put("ok", true); }
            default -> throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("未知的主动通知操作", "Unknown proactive notification operation"));
        };
    }

    JSONObject status() throws Exception {
        JSONObject pet = PetOverlay.notificationState(context, "");
        boolean local = preferences.getBoolean("notifyLocalEnabled", true), apps = preferences.getBoolean("notifyAppsEnabled", false);
        JSONArray allowed = allowedApps(), installed = installedApps();
        boolean system = active && local && notifications.areNotificationsEnabled() && enabledChannel("task_updates");
        boolean petAvailable = active && local && pet.optBoolean("petAvailable");
        boolean appAvailable = active && apps && allowed.length() > 0 && !ProductPermissions.stopped(context)
            && ProductPermissions.allowed(preferences, "allowApps") && ProductPermissions.allowed(preferences, "allowObserve")
            && ProductPermissions.allowed(preferences, "allowControl");
        return new JSONObject().put("pluginActive", active).put("localEnabled", local).put("appsEnabled", apps)
            .put("allowedApps", allowed).put("apps", installed)
            .put("channels", new JSONArray()
                .put(channel("system", system, !local ? io.droyagent.app.NativeLocale.text("本机通知已关闭", "Local notifications are disabled") : io.droyagent.app.NativeLocale.text("系统通知权限或渠道未开启", "System notification permission or channel is disabled")))
                .put(channel("pet", petAvailable, !local ? io.droyagent.app.NativeLocale.text("本机通知已关闭", "Local notifications are disabled") : io.droyagent.app.NativeLocale.text("当前伙伴形象不可展示", "The current companion artwork cannot be displayed")))
                .put(channel("app", appAvailable, !apps ? io.droyagent.app.NativeLocale.text("App 主动通知已关闭", "App proactive notifications are disabled") : io.droyagent.app.NativeLocale.text("请选择允许的 App，并开启手机操作", "Choose allowed apps and enable phone operations"))))
            .put("context", pet.put("returnOnCompletion", preferences.getBoolean("returnToDroyOnCompletion", false)));
    }

    private static JSONObject channel(String id, boolean available, String reason) throws Exception {
        return new JSONObject().put("id", id).put("available", available).put("reason", available ? "" : reason);
    }

    private void configure(JSONObject args) throws Exception {
        SharedPreferences.Editor editor = preferences.edit();
        if (args.has("localEnabled")) editor.putBoolean("notifyLocalEnabled", bool(args, "localEnabled"));
        if (args.has("appsEnabled")) editor.putBoolean("notifyAppsEnabled", bool(args, "appsEnabled"));
        if (args.has("allowedApps")) {
            JSONArray requested = args.getJSONArray("allowedApps"), checked = new JSONArray();
            if (requested.length() > 100) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("允许的 App 过多", "Too many allowed apps"));
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < requested.length(); i++) {
                JSONObject item = requested.getJSONObject(i);
                String pkg = string(item, "packageName", 255);
                context.getPackageManager().getApplicationInfo(pkg, 0);
                if (!seen.add(pkg) || pkg.equals(context.getPackageName())) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("通知 App 无效或重复", "Invalid or duplicate notification app"));
                checked.put(new JSONObject().put("packageName", pkg).put("target", optional(item, "target", 200)));
            }
            editor.putString(ALLOWED, checked.toString());
        }
        if (!editor.commit()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("主动通知设置未保存", "Proactive notification settings were not saved"));
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
        long appCount = 0;
        for (int i = 0; i < destinations.length(); i++) if (destinations.getJSONObject(i).optString("channel").equals("app")) appCount++;
        if (appCount > 1) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("多个 App 请依次完成发送", "Send notifications to multiple apps one at a time"));
        for (int i = 0; i < destinations.length(); i++) {
            JSONObject dest = destinations.getJSONObject(i);
            String selected = string(dest, "channel", 40), pkg = optional(dest, "packageName", 255), target = optional(dest, "target", 200);
            if (selected.equals("app") && target.isEmpty()) target = defaultTarget(pkg);
            String id = ProactiveNoticeStore.identity(task, turn, key, selected, pkg, target);
            JSONObject record;
            synchronized (store) {
                record = store.find(id);
                if (record != null && !record.optString("status").equals("failed")
                        && (selected.equals("app") || record.optString("text").equals(text) && record.optString("title").equals(title))) {
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
                        checkLocal();
                        publish(record, attention, record.optBoolean("update"));
                        record.put("status", "published");
                    }
                    case "pet" -> {
                        checkLocal();
                        if (record.optBoolean("update")) { PetOverlay.updateNotice(context, id, text); record.put("status", "updated"); }
                        else if (PetOverlay.notify(context, task, id, text, this::localAllowed)) record.put("status", "shown");
                        else throw new IllegalStateException(io.droyagent.app.NativeLocale.text("当前无法展示伙伴形象提示，请选择其他渠道", "The companion alert cannot be displayed now. Please choose another channel"));
                    }
                    case "app" -> {
                        checkApp(pkg);
                        if (target.isEmpty()) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("请指定接收对象，或先设置常用目标", "Specify a recipient or set a frequent target first"));
                        String previous = appContexts.putIfAbsent(task, id);
                        if (previous != null && !previous.equals(id)) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请先完成当前 App 通知", "Please complete the current app notification first"));
                        record.put("status", "requires_action");
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

    private synchronized JSONObject complete(JSONObject args) throws Exception {
        String task = string(args, "taskId", 256), id = string(args, "noticeId", 100), status = string(args, "status", 40);
        if (!java.util.Set.of("sent_in_app", "failed", "unknown", "cancelled").contains(status)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("通知结果无效", "Invalid notification result"));
        JSONObject record = store.find(id);
        if (record == null || !task.equals(record.optString("taskId")) || !record.optString("channel").equals("app"))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("通知记录与当前任务不符", "The notification record does not match the current task"));
        if (!id.equals(appContexts.get(task))) return publicRecord(record);
        if (status.equals("sent_in_app") && args.optJSONObject("evidence") == null) status = "unknown";
        record.put("status", status);
        if (args.optJSONObject("evidence") != null) record.put("evidence", args.getJSONObject("evidence"));
        appContexts.remove(task, id); save();
        return publicRecord(record);
    }

    void checkAppOperation(String task) {
        String id = appContexts.get(task);
        if (id == null) return;
        try {
            JSONObject record = store.find(id);
            if (record == null) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("通知执行上下文已失效", "The notification execution context expired"));
            checkApp(record.optString("packageName"));
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException(io.droyagent.app.NativeLocale.text("通知执行上下文无法读取", "Unable to read notification execution context"), failure); }
    }

    synchronized void appActionDispatched(String task) throws Exception {
        String id = appContexts.get(task);
        if (id != null) { JSONObject record = store.find(id); if (record != null && !record.optBoolean("actionStarted")) {
            record.put("actionStarted", true); save();
        } }
    }

    synchronized void end(String task) throws Exception {
        String id = appContexts.remove(task);
        if (id != null) { JSONObject record = store.find(id); if (record != null) {
            record.put("status", record.optBoolean("actionStarted") ? "unknown" : "cancelled"); save();
        } }
    }
    private void endAll() throws Exception { for (String task : new ArrayList<>(appContexts.keySet())) end(task); }

    private void checkLocal() {
        if (!localAllowed()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("本机主动通知已关闭", "Local proactive notifications are disabled"));
    }
    private boolean localAllowed() { return active && preferences.getBoolean("notifyLocalEnabled", true); }
    private void checkApp(String pkg) throws Exception {
        if (!active || !preferences.getBoolean("notifyAppsEnabled", false)) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("App 主动通知已关闭", "App proactive notifications are disabled"));
        if (ProductPermissions.stopped(context)) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("手机操作已暂停", "Phone operations are paused"));
        JSONArray apps = allowedApps();
        for (int i = 0; i < apps.length(); i++) if (apps.getJSONObject(i).getString("packageName").equals(pkg)) return;
        throw new IllegalStateException(io.droyagent.app.NativeLocale.text("尚未允许此 App 主动通知", "Proactive notifications are not allowed for this app"));
    }
    private String defaultTarget(String pkg) throws Exception {
        JSONArray apps = allowedApps();
        for (int i = 0; i < apps.length(); i++) if (apps.getJSONObject(i).getString("packageName").equals(pkg)) return apps.getJSONObject(i).optString("target");
        return "";
    }
    private boolean enabledChannel(String id) { NotificationChannel channel = notifications.getNotificationChannel(id); return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE; }
    private void publish(JSONObject record, String attention, boolean update) throws Exception {
        String channel = attention.equals("urgent") ? "task_urgent" : attention.equals("normal") ? "task_attention" : "task_updates";
        if (!notifications.areNotificationsEnabled() || !enabledChannel(channel)) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请开启 Droy 对应通知渠道", "Please enable the corresponding Droy notification channel"));
        String id = record.getString("noticeId");
        int numeric = id.hashCode() & 0x7fffffff;
        PendingIntent open = PendingIntent.getActivity(context, numeric,
            new Intent(context, MainActivity.class).putExtra("petTaskId", record.getString("taskId"))
                .setData(android.net.Uri.parse("droy://notification/" + id))
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String title = record.optString("title");
        notifications.notify(TAG, numeric, new Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_notification_droy)
            .setContentTitle(title.isBlank() ? io.droyagent.app.NativeLocale.text("Droy · 任务消息", "Droy · Task message") : title).setContentText(record.getString("text"))
            .setStyle(new Notification.BigTextStyle().bigText(record.getString("text"))).setContentIntent(open)
            .setAutoCancel(true).setOnlyAlertOnce(update).setVisibility(Notification.VISIBILITY_PRIVATE).build());
    }
    private JSONArray allowedApps() throws Exception {
        JSONArray saved = new JSONArray(preferences.getString(ALLOWED, "[]")), result = new JSONArray();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject item = new JSONObject(saved.getJSONObject(i).toString());
            try { item.put("label", context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(item.getString("packageName"), 0))); result.put(item); }
            catch (PackageManager.NameNotFoundException removed) { /* Removed apps are unavailable until selected again. */ }
        }
        return result;
    }
    private JSONArray installedApps() throws Exception {
        java.util.List<ApplicationInfo> apps = new ArrayList<>(context.getPackageManager().getInstalledApplications(0));
        apps.removeIf(app -> app.packageName.equals(context.getPackageName()) || context.getPackageManager().getLaunchIntentForPackage(app.packageName) == null);
        apps.sort(Comparator.comparing(app -> context.getPackageManager().getApplicationLabel(app).toString()));
        JSONArray result = new JSONArray();
        for (ApplicationInfo app : apps) result.put(new JSONObject().put("packageName", app.packageName).put("label", context.getPackageManager().getApplicationLabel(app)));
        return result;
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
