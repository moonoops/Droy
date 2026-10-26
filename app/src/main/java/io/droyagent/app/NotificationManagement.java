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
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Product-owned notification choices and receipts; the listener supplies Android authority. */
final class NotificationManagement {
    private static final String DATA = "notificationManagementData";
    static final String TAG = "droy-managed";
    static final int SUMMARY_ID = 2000;
    private static final long ARCHIVE_LIFETIME = 7L * 24 * 60 * 60 * 1000;
    private static volatile NotificationManagement singleton;
    private final Context context;
    private final SharedPreferences preferences;
    private final NotificationManager notifications;
    private final NotificationChannelControl channels;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "droy-notifications"));
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, PendingIntent> intents = new HashMap<>();
    private final Map<String, Boolean> silentApps = new HashMap<>();
    private Map<String, String> applicationNames = new LinkedHashMap<>();
    private final Set<String> internalRemovals = new HashSet<>();
    private final Set<String> channelSyncAttempts = new HashSet<>();
    private final Map<String, String> inFlight = new HashMap<>();
    private final Runnable maintenance = () -> worker.execute(this::maintain);
    private JSONObject config;
    private JSONArray records;
    private volatile DroyNotificationService listener;
    private volatile String error = "";
    private final NotificationAccessPolicy accessPolicy = new NotificationAccessPolicy();
    private record AccessStatus(String state, boolean actionRequired, String action) { }
    private volatile AccessStatus accessStatus = new AccessStatus("connecting", false, "");
    private final Runnable accessCheck = this::checkAccess;

    static boolean attentionRequired(Context context) {
        NotificationManagement current = singleton;
        return current != null && enabled(context) && current.accessStatus.actionRequired();
    }

    static void refreshAccess(Context context) {
        Context application = context.getApplicationContext();
        NotificationManagement current = singleton;
        if (current == null) new Handler(Looper.getMainLooper()).post(() -> get(application).queueAccessCheck());
        else current.queueAccessCheck();
    }

    private void queueAccessCheck() {
        handler.removeCallbacks(accessCheck);
        handler.post(accessCheck);
    }

    private void checkAccess() {
        handler.removeCallbacks(accessCheck);
        NotificationChannel alerts = notifications.getNotificationChannel("managed_alerts");
        boolean postGranted = notifications.areNotificationsEnabled() && alerts != null
                && alerts.getImportance() != NotificationManager.IMPORTANCE_NONE;
        boolean paused = ProductPermissions.stopped(context) || BackgroundRuntime.explicitlyStopped(context)
                || !ProductPermissions.allowed(preferences, "allowNotifications");
        NotificationAccessPolicy.Result result = accessPolicy.observe(enabled(), paused,
                DroyNotificationService.hasAccess(context), DroyNotificationService.connected(), postGranted,
                android.os.SystemClock.elapsedRealtime());
        AccessStatus next = new AccessStatus(result.state(), result.actionRequired(), result.action());
        boolean changed = !next.equals(accessStatus);
        accessStatus = next;
        if (result.requestRebind()) {
            try { DroyNotificationService.requestReconnect(context); }
            catch (SecurityException unavailable) { Log.w("DroyNotifications", "notification_rebind_not_authorized"); }
        }
        if (result.checkAfterMs() > 0) handler.postDelayed(accessCheck, result.checkAfterMs());
        if (changed) ForegroundStatusNotifications.refresh(context);
    }

    private JSONObject accessState() throws Exception {
        AccessStatus status = accessStatus;
        String message = switch (status.state()) {
            case "read_permission_required" -> NativeLocale.text("请允许 Droy 读取其他应用的通知，才能帮你整理。", "Allow Droy to read other apps’ notifications so it can organize them.");
            case "post_permission_required" -> NativeLocale.text("请允许 Droy 发送通知，才能提醒你重要事项。", "Allow Droy to send notifications so it can remind you about important matters.");
            case "listener_unavailable" -> NativeLocale.text("系统暂时没有送来通知。请打开设置，将 Droy 的通知读取权限关闭后重新开启。", "The system is not delivering notifications to Droy. Open settings, turn Droy’s notification access off, then on again.");
            case "connecting" -> NativeLocale.text("正在准备通知整理，稍等一下。", "Preparing notification organization. One moment.");
            case "paused" -> NativeLocale.text("通知整理已暂停，恢复 Droy 后继续。", "Notification organization is paused. Resume Droy to continue.");
            default -> "";
        };
        return new JSONObject().put("state", status.state()).put("message", message)
                .put("actionRequired", status.actionRequired()).put("action", status.action());
    }
    private int clearedAdsCount() throws Exception {
        int total = 0;
        long since = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
        for (int i = 0; i < records.length(); i++) if (records.getJSONObject(i).optLong("clearedAt") >= since) total++;
        return total;
    }

    private void refreshStatusNotification() { ForegroundStatusNotifications.refresh(context); }
    private boolean scanQueued;
    private boolean applyingChannels;
    private volatile HttpURLConnection activeAnalysis;

    static boolean enabled(Context context) {
        return context.getSharedPreferences("product", Context.MODE_PRIVATE).getBoolean("notificationManagementEnabled", true);
    }

    static void synchronizeInstalledApps(Context context) { get(context).rescan(); }

    static synchronized NotificationManagement get(Context context) {
        if (singleton == null) singleton = new NotificationManagement(context.getApplicationContext());
        return singleton;
    }

    private NotificationManagement(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        notifications = context.getSystemService(NotificationManager.class);
        channels = new NotificationChannelControl(context);
        try {
            JSONObject data = new JSONObject(preferences.getString(DATA, "{}"));
            config = NotificationManagementRules.configuration(data.optJSONObject("config"));
            records = data.optJSONArray("records");
            if (records == null) records = new JSONArray();
        } catch (Exception invalid) { throw new IllegalStateException(io.droyagent.app.NativeLocale.text("通知管理配置无法读取", "Unable to read notification management configuration"), invalid); }
        notifications.createNotificationChannel(new NotificationChannel("managed_alerts", io.droyagent.app.NativeLocale.text("重要通知", "Important notifications"), NotificationManager.IMPORTANCE_HIGH));
        NotificationChannel summary = new NotificationChannel("managed_summary", io.droyagent.app.NativeLocale.text("通知汇总", "Notification summary"), NotificationManager.IMPORTANCE_LOW);
        summary.setSound(null, null); summary.enableVibration(false);
        notifications.createNotificationChannel(summary);
        installedApps();
        queueAccessCheck();
    }

    synchronized JSONObject request(JSONObject request) throws Exception {
        switch (request.getString("operation")) {
            case "get": return state();
            case "archive": return new JSONObject().put("items", archive());
            case "configure": {
                Boolean nextEnabled = booleanOption(request, "enabled"), nextCloud = booleanOption(request, "cloudAnalysisEnabled");
                JSONObject nextConfig = new JSONObject(config.toString());
                if (request.has("packages")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("请通过例外应用调整通知方式", "Please adjust notification handling through app exceptions"));
                if (request.has("exceptions")) {
                    JSONObject exceptions = NotificationManagementRules.exceptions(request.getJSONArray("exceptions"));
                    Set<String> installed = installedApps().keySet();
                    for (java.util.Iterator<String> names = exceptions.keys(); names.hasNext(); )
                        if (!installed.contains(names.next())) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("例外应用无效", "Invalid app exception"));
                    nextConfig.put("exceptions", exceptions);
                }
                if (request.has("reminderMinutes")) nextConfig.put("reminderMinutes", NotificationManagementRules.reminderMinutes(request.get("reminderMinutes")));
                if (nextCloud != null) nextConfig.put("cloudAnalysisEnabled", nextCloud);
                config = nextConfig;
                if (nextEnabled != null) preferences.edit().putBoolean("notificationManagementEnabled", nextEnabled).apply();
                changed();
                if (request.optBoolean("enabled")) BackgroundRuntime.startedByUser(context);
                BackgroundRuntime.settingsChanged(context);
                if (!enabled()) { cancelManaged(); error = ""; }
                rescan();
                return state();
            }
            case "setAppMode": {
                String packageName = NotificationManagementRules.packageName(request.get("packageName"));
                String mode = NotificationManagementRules.appMode(request.get("mode"));
                if (!installedApps().containsKey(packageName)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("此应用不支持通知接管", "Notification management is not supported for this app"));
                JSONObject exceptions = config.getJSONObject("exceptions");
                if (mode.equals("managed")) exceptions.remove(packageName); else exceptions.put(packageName, mode);
                changed(); rescan(); return state();
            }
            case "upsertRule": {
                JSONArray next = new JSONArray(), rules = config.getJSONArray("rules");
                JSONObject source = new JSONObject(request.getJSONObject("rule").toString());
                if (!source.has("id")) for (int i = rules.length() - 1; i >= 0; i--)
                    if (rules.getJSONObject(i).optString("packageName").equals(source.optString("packageName"))) {
                        source.put("id", rules.getJSONObject(i).getString("id")); break;
                    }
                JSONObject rule = NotificationManagementRules.rule(source);
                for (int i = 0; i < rules.length(); i++)
                    if (!rules.getJSONObject(i).getString("id").equals(rule.getString("id"))
                            && !rules.getJSONObject(i).getString("packageName").equals(rule.getString("packageName"))) next.put(rules.get(i));
                if (next.length() >= 100) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("规则过多", "Too many rules"));
                next.put(rule); config.put("rules", next); changed(); rescan(); return state();
            }
            case "deleteRule": {
                String id = NotificationManagementRules.text(request.get("id"), 80, false);
                JSONArray next = new JSONArray(), rules = config.getJSONArray("rules");
                for (int i = 0; i < rules.length(); i++) if (!rules.getJSONObject(i).getString("id").equals(id)) next.put(rules.get(i));
                config.put("rules", next); changed(); rescan(); return state();
            }
            default: throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("通知管理操作无效", "Invalid notification management operation"));
        }
    }

    synchronized JSONObject state() throws Exception {
        queueAccessCheck();
        JSONArray apps = new JSONArray(), exceptions = new JSONArray();
        for (Map.Entry<String, String> app : installedApps().entrySet()) {
            String mode = appMode(app.getKey());
            Boolean confirmed = silentApps.get(app.getKey());
            if (confirmed == null) confirmed = channels.packageSilent(app.getKey());
            JSONObject item = new JSONObject().put("packageName", app.getKey()).put("label", app.getValue())
                    .put("mode", mode).put("managed", !mode.equals("allow"))
                    .put("silent", confirmed == null ? JSONObject.NULL : confirmed);
            apps.put(item);
            if (!mode.equals("managed")) exceptions.put(new JSONObject(item.toString()));
        }
        JSONObject access = DroyNotificationService.status(context);
        JSONObject autoSilence = new JSONObject(channels.status().toString());
        if (scanQueued || applyingChannels) autoSilence.put("state", "syncing").put("message", "");
        autoSilence.put("actionRequired", !autoSilence.optString("state").equals("ready") && !autoSilence.optString("message").isEmpty());
        return new JSONObject().put("enabled", enabled()).put("revision", revision()).put("apps", apps)
                .put("exceptions", exceptions).put("rules", new JSONArray(config.getJSONArray("rules").toString()))
                .put("reminderMinutes", config.optInt("reminderMinutes", 5)).put("cloudAnalysisEnabled", config.optBoolean("cloudAnalysisEnabled", true)).put("autoSilence", autoSilence)
                .put("permissionGranted", access.optBoolean("permissionGranted")).put("connected", listener != null && access.optBoolean("connected"))
                .put("error", error).put("access", accessState()).put("clearedAdsCount", clearedAdsCount()).put("archive", archive());
    }

    synchronized void connected(DroyNotificationService service) {
        listener = service; queueAccessCheck(); ForegroundStatusNotifications.refresh(context); rescan(); schedule();
    }
    synchronized void disconnected(DroyNotificationService service) {
        if (listener != service) return;
        listener = null; queueAccessCheck(); ForegroundStatusNotifications.refresh(context); intents.clear(); inFlight.clear(); internalRemovals.clear(); silentApps.clear(); handler.removeCallbacks(maintenance);
        rescan();
    }

    void posted(StatusBarNotification item, NotificationListenerService.RankingMap ranking) {
        if (item == null || item.getPackageName().equals(context.getPackageName())) return;
        worker.execute(() -> {
            try {
                if (needsChannelSync(item, ranking)) synchronizeChannels();
                DroyNotificationService service;
                synchronized (this) { service = listener; }
                receive(item, service == null ? ranking : service.getCurrentRanking());
            }
            catch (Exception failure) { failed(failure); }
        });
    }

    void removed(StatusBarNotification item, int reason) {
        if (item == null) return;
        worker.execute(() -> {
            try {
                synchronized (this) {
                    if (context.getPackageName().equals(item.getPackageName()) && TAG.equals(item.getTag())) {
                        if (reason == NotificationListenerService.REASON_CANCEL || reason == NotificationListenerService.REASON_CANCEL_ALL
                                || reason == NotificationListenerService.REASON_CLICK) {
                            for (int i = 0; i < records.length(); i++) {
                                JSONObject record = records.getJSONObject(i);
                                if (record.optInt("notificationId") == item.getId() || item.getId() == SUMMARY_ID && record.optString("action").equals("collect"))
                                    record.put("dismissed", true).put("repeatAt", 0).put("reminderAt", 0);
                            }
                            save(); refreshStatusNotification(); schedule();
                        }
                        return;
                    }
                    inFlight.remove(item.getKey());
                    if (internalRemovals.remove(item.getKey())) {
                        for (int i = 0; i < records.length(); i++) {
                            JSONObject record = records.getJSONObject(i);
                            if (reason == NotificationListenerService.REASON_LISTENER_CANCEL
                                    && record.optString("key").equals(item.getKey()) && record.optString("action").equals("clear")
                                    && record.optBoolean("advertisement") && NotificationManagementRules.sameSource(record, snapshot(item)))
                                record.put("clearedAt", System.currentTimeMillis());
                        }
                        save(); refreshStatusNotification(); schedule(); return;
                    }
                    intents.remove(item.getKey());
                    for (int i = 0; i < records.length(); i++) {
                        JSONObject record = records.getJSONObject(i);
                        if (NotificationManagementRules.sameSource(record, snapshot(item))) {
                            record.put("repeatAt", 0).put("reminderAt", 0).put("dismissed", true);
                            if (record.optString("action").equals("notify") || record.optBoolean("important")) notifications.cancel(TAG, record.optInt("notificationId"));
                        }
                    }
                    save(); refreshStatusNotification(); schedule();
                }
            } catch (Exception failure) { failed(failure); }
        });
    }

    synchronized void rescan() {
        queueAccessCheck();
        if (!processingActive()) { cancelAnalysis(); channels.cancelCurrent(); }
        else if (!ProductPermissions.allowed(preferences, "allowRoot")) channels.cancelCurrent();
        if (scanQueued) return;
        scanQueued = true;
        channelSyncAttempts.clear();
        worker.execute(() -> {
            synchronized (this) { scanQueued = false; }
            try {
                synchronizeChannels();
                DroyNotificationService service;
                synchronized (this) { service = listener; if (service == null || !enabled()) return; }
                StatusBarNotification[] items = service.getActiveNotifications();
                NotificationListenerService.RankingMap rankings = service.getCurrentRanking();
                if (items != null) for (StatusBarNotification item : items) receive(item, rankings);
                maintain();
            } catch (Exception failure) { failed(failure); }
        });
    }

    private synchronized boolean needsChannelSync(StatusBarNotification item, NotificationListenerService.RankingMap rankings) {
        if (!enabled() || listener == null || !managed(item.getPackageName()) || !RuntimeService.ready
                || ProductPermissions.stopped(context) || !ProductPermissions.allowed(preferences, "allowNotifications")
                || channels.status().optString("state").equals("root_required")) return false;
        NotificationListenerService.Ranking ranking = new NotificationListenerService.Ranking();
        if (rankings != null && rankings.getRanking(item.getKey(), ranking) && silent(ranking.getChannel())) return false;
        return channelSyncAttempts.add(item.getPackageName() + "\n" + item.getNotification().getChannelId());
    }

    private void synchronizeChannels() throws Exception {
        Map<String, String> modes = new LinkedHashMap<>();
        boolean active;
        long expectedRevision;
        synchronized (this) {
            for (String packageName : installedApps().keySet()) modes.put(packageName, appMode(packageName));
            active = processingActive();
            expectedRevision = revision();
            applyingChannels = true;
        }
        try { channels.synchronize(modes, active, () -> {
            synchronized (NotificationManagement.this) { return revision() == expectedRevision && processingActive() == active; }
        }); }
        finally { synchronized (this) { applyingChannels = false; } }
    }

    private synchronized Map<String, String> installedApps() {
        PackageManager packages = context.getPackageManager();
        Set<String> launchable = new HashSet<>(), protectedApps = new HashSet<>(Set.of(
                "android", "com.android.systemui", "com.android.phone", "com.android.server.telecom",
                "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.miui.securitycenter"));
        for (ResolveInfo info : packages.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0))
            launchable.add(info.activityInfo.packageName);
        TelecomManager telecom = context.getSystemService(TelecomManager.class);
        if (telecom != null && telecom.getDefaultDialerPackage() != null) protectedApps.add(telecom.getDefaultDialerPackage());
        for (ResolveInfo alarm : packages.queryIntentActivities(new Intent(AlarmClock.ACTION_SET_ALARM), 0))
            protectedApps.add(alarm.activityInfo.packageName);
        List<ApplicationInfo> applications = new ArrayList<>(packages.getInstalledApplications(0));
        applications.sort(Comparator.comparing(info -> info.loadLabel(packages).toString(), String.CASE_INSENSITIVE_ORDER));
        Map<String, String> names = new LinkedHashMap<>();
        for (ApplicationInfo info : applications)
            if (NotificationManagementRules.eligibleApplication(info.packageName, info.uid,
                    (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0, launchable.contains(info.packageName),
                    context.getPackageName(), protectedApps)) names.put(info.packageName, info.loadLabel(packages).toString());
        applicationNames = names;
        return new LinkedHashMap<>(names);
    }

    private void receive(StatusBarNotification source, NotificationListenerService.RankingMap rankings) throws Exception {
        JSONObject item;
        long revision;
        JSONArray rules;
        boolean cloud;
        synchronized (this) {
            if (!enabled() || listener == null || BackgroundRuntime.explicitlyStopped(context) || !managed(source.getPackageName()) || ProductPermissions.stopped(context)
                    || !ProductPermissions.allowed(preferences, "allowNotifications")) return;
            if (source.isOngoing() || !source.isClearable()) return;
            NotificationListenerService.Ranking ranking = new NotificationListenerService.Ranking();
            boolean silent = rankings != null && rankings.getRanking(source.getKey(), ranking) && silent(ranking.getChannel());
            silentApps.put(source.getPackageName(), silent);
            if (appMode(source.getPackageName()).equals("block")) {
                internalRemovals.add(source.getKey()); listener.cancelNotification(source.getKey()); return;
            }
            if ((source.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            android.os.Bundle extras = source.getNotification().extras;
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            android.os.Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (lines != null && lines.length > 1 || messages != null && messages.length > 1) return;
            item = snapshot(source);
            if (protectedContent(item)) return;
            if (source.getNotification().contentIntent != null) intents.put(source.getKey(), source.getNotification().contentIntent);
            String fingerprint = item.getString("fingerprint");
            for (int i = 0; i < records.length(); i++) {
                JSONObject record = records.getJSONObject(i);
                if (NotificationManagementRules.alreadyHandled(record, item, revision())) {
                    if (record.optBoolean("applied") && !record.optString("action").equals("retain")) {
                        internalRemovals.add(source.getKey()); listener.cancelNotification(source.getKey());
                    }
                    return;
                }
            }
            if (fingerprint.equals(inFlight.get(source.getKey())) || !RuntimeService.ready) return;
            revision = revision(); rules = new JSONArray(config.getJSONArray("rules").toString()); cloud = config.optBoolean("cloudAnalysisEnabled", true);
            inFlight.put(source.getKey(), fingerprint);
        }
        try {
            JSONObject response = analyze(new JSONObject().put("revision", revision).put("notification", item)
                    .put("rules", rules).put("cloudAnalysisEnabled", cloud));
            apply(source, item, revision, NotificationManagementRules.decision(response));
        } finally {
            synchronized (this) { if (item.getString("fingerprint").equals(inFlight.get(source.getKey()))) inFlight.remove(source.getKey()); }
        }
    }

    private synchronized void apply(StatusBarNotification source, JSONObject item, long expectedRevision, JSONObject decision) throws Exception {
        DroyNotificationService service = listener;
        if (service == null) return;
        StatusBarNotification[] active = service.getActiveNotifications(new String[]{source.getKey()});
        if (active == null || active.length != 1) return;
        JSONObject latest = snapshot(active[0]);
        if (!NotificationManagementRules.sameSource(item, latest)) return;
        NotificationListenerService.Ranking ranking = new NotificationListenerService.Ranking();
        boolean silent = service.getCurrentRanking().getRanking(source.getKey(), ranking) && silent(ranking.getChannel());
        String action = decision.getString("action");
        boolean preservesOrClears = action.equals("retain") || action.equals("clear");
        if (!NotificationManagementRules.current(revision(), expectedRevision, latest.getString("fingerprint"), item.getString("fingerprint"),
                enabled(), managed(source.getPackageName()), silent || preservesOrClears, DroyNotificationService.hasAccess(context),
                ProductPermissions.stopped(context) || BackgroundRuntime.explicitlyStopped(context) || !RuntimeService.ready
                        || !ProductPermissions.allowed(preferences, "allowNotifications"))) return;
        int repeat = explicitLimit(source.getPackageName(), "repeatMinutes", decision.optInt("repeatMinutes"));
        int clear = explicitLimit(source.getPackageName(), "clearAfterHours", decision.optInt("clearAfterHours"));
        JSONObject record = new JSONObject(item.toString()).put("action", action).put("revision", expectedRevision)
                .put("savedAtMs", System.currentTimeMillis()).put("dismissed", false)
                .put("copySeed", java.util.concurrent.ThreadLocalRandom.current().nextInt())
                .put("important", decision.optBoolean("important")).put("advertisement", decision.optBoolean("advertisement"))
                .put("reminderAt", NotificationManagementRules.reminderAt(item, decision, config.optInt("reminderMinutes", 5)))
                .put("displayTitle", decision.optString("title", item.optString("title")))
                .put("displayText", decision.optString("text", item.optString("text")))
                .put("repeatAt", action.equals("notify") && repeat > 0 ? System.currentTimeMillis() + repeat * 60000L : 0)
                .put("clearAt", clear > 0 ? item.optLong("postedAtMs") + clear * 3600000L : 0)
                .put("notificationId", stableId(source.getPackageName() + ":" + (action.equals("retain") ? source.getKey() : decision.optString("groupKey", source.getKey()))));
        JSONArray next = new JSONArray();
        List<Integer> obsolete = new ArrayList<>();
        boolean previousCollection = false;
        for (int i = 0; i < records.length(); i++) {
            JSONObject old = records.getJSONObject(i);
            if (NotificationManagementRules.sameSource(old, item) && old.optLong("reminderAttemptedAt") > 0) {
                record.put("reminderAt", 0).put("reminderAttemptedAt", old.optLong("reminderAttemptedAt"));
                if (old.has("remindedAt")) record.put("remindedAt", old.optLong("remindedAt")).put("reminderDelivery", old.optString("reminderDelivery"));
            }
            if (old.optBoolean("important") && old.optString("key").equals(source.getKey())
                    && (!action.equals("notify") || old.optInt("notificationId") != record.optInt("notificationId")))
                obsolete.add(old.optInt("notificationId"));
            if (NotificationManagementRules.obsoleteAlert(old, source.getKey(), action, record.getInt("notificationId")))
                obsolete.add(old.getInt("notificationId"));
            if (NotificationManagementRules.superseded(old, source.getKey(), record.getInt("notificationId"))) {
                if (old.optString("action").equals("collect")) previousCollection = true;
                old.put("dismissed", true).put("repeatAt", 0).put("reminderAt", 0).put("clearAt", 0);
            }
            if (!old.optString("key").equals(source.getKey())) next.put(old);
        }
        if (action.equals("retain") || action.equals("clear"))
            for (String field : new String[]{"title", "text", "subtext", "displayTitle", "displayText"}) record.remove(field);
        records = next; records.put(record); trim(); save();
        if (action.equals("notify")) publish(record, false);
        if (action.equals("collect") || previousCollection) publishSummary();
        for (int id : obsolete) notifications.cancel(TAG, id);
        for (StatusBarNotification own : notifications.getActiveNotifications())
            if (TAG.equals(own.getTag()) && source.getKey().equals(own.getNotification().extras.getString("droySourceKey"))
                    && (!action.equals("notify") || own.getId() != record.getInt("notificationId"))) notifications.cancel(TAG, own.getId());
        record.put("applied", true);
        save();
        if (!action.equals("retain")) {
            internalRemovals.add(source.getKey()); service.cancelNotification(source.getKey());
        }
        error = ""; refreshStatusNotification(); schedule();
    }

    private JSONObject analyze(JSONObject payload) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + RuntimeService.webPort + "/droy/notifications/process").openConnection();
        connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setConnectTimeout(1000); connection.setReadTimeout(25000);
        connection.setRequestProperty("Cookie", "droy_session=" + RuntimeService.webToken);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        synchronized (this) {
            if (!processingActive() || revision() != payload.getLong("revision")) throw new PolicyChanged();
            activeAnalysis = connection;
        }
        try {
            byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(data.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(data); }
            if (connection.getResponseCode() != 200) throw new java.io.IOException(io.droyagent.app.NativeLocale.text("通知分析不可用", "Notification analysis is unavailable"));
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) { if (output.size() + count > 16384) throw new java.io.IOException(io.droyagent.app.NativeLocale.text("通知分析结果过长", "Notification analysis result is too long")); output.write(buffer, 0, count); }
                return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            }
        } catch (java.io.IOException failure) {
            synchronized (this) {
                if (!processingActive() || revision() != payload.getLong("revision")) throw new PolicyChanged();
            }
            throw failure;
        } finally {
            if (activeAnalysis == connection) activeAnalysis = null;
            connection.disconnect();
        }
    }

    private void maintain() {
        List<JSONObject> due = new ArrayList<>();
        try {
            synchronized (this) {
                if (!enabled() || listener == null || !RuntimeService.ready || BackgroundRuntime.explicitlyStopped(context)
                        || !DroyNotificationService.hasAccess(context) || ProductPermissions.stopped(context)
                        || !ProductPermissions.allowed(preferences, "allowNotifications")) return;
                long now = System.currentTimeMillis();
                StatusBarNotification[] active = notifications.getActiveNotifications();
                for (int i = 0; i < records.length(); i++) {
                    JSONObject record = records.getJSONObject(i);
                    if (!NotificationManagementRules.scheduled(record, revision(), managed(record.optString("packageName")))) continue;
                    if (record.optBoolean("important") && record.optString("action").equals("retain") && !originalPresent(record)) {
                        record.put("dismissed", true).put("reminderAt", 0);
                        notifications.cancel(TAG, record.optInt("notificationId"));
                        continue;
                    }
                    if (record.optLong("clearAt") > 0 && record.optLong("clearAt") <= now) {
                        String key = record.optString("key");
                        if (originalPresent(record)) {
                            internalRemovals.add(key); listener.cancelNotification(key);
                        }
                        notifications.cancel(TAG, record.optInt("notificationId"));
                        record.put("clearAt", 0).put("repeatAt", 0).put("reminderAt", 0).put("dismissed", true);
                    }
                    if (!record.optBoolean("dismissed") && record.optLong("repeatAt") > 0 && record.optLong("repeatAt") <= now) {
                        record.put("repeatAt", 0);
                        for (StatusBarNotification own : active) if (TAG.equals(own.getTag()) && own.getId() == record.optInt("notificationId")) {
                            publish(record, true); break;
                        }
                    }
                    if (!record.optBoolean("dismissed") && record.optLong("reminderAt") > 0 && record.optLong("reminderAt") <= now) {
                        // Persist the single attempt before showing UI, including across process restarts.
                        record.put("reminderAt", 0).put("reminderAttemptedAt", now);
                        due.add(record);
                    }
                }
                trim(); save(); publishSummary(); refreshStatusNotification(); schedule();
            }
            // Rendering waits for the main thread. Never hold the management lock while waiting.
            boolean bubbleUsed = false;
            for (JSONObject record : due) {
                try { bubbleUsed |= remind(record, !bubbleUsed); }
                catch (Exception failure) { failed(failure); }
            }
        } catch (Exception failure) { failed(failure); }
    }

    private boolean originalPresent(JSONObject record) throws Exception {
        if (listener == null) return false;
        StatusBarNotification[] originals = listener.getActiveNotifications(new String[]{record.optString("key")});
        return originals != null && originals.length == 1
                && NotificationManagementRules.sameSource(record, snapshot(originals[0]));
    }

    private synchronized boolean reminderAllowed(JSONObject record) {
        try {
            return enabled() && RuntimeService.ready && !BackgroundRuntime.explicitlyStopped(context)
                    && listener != null && DroyNotificationService.hasAccess(context)
                    && !ProductPermissions.stopped(context) && ProductPermissions.allowed(preferences, "allowNotifications")
                    && NotificationManagementRules.scheduled(record, revision(), managed(record.optString("packageName")))
                    && originalPresent(record);
        } catch (Exception failure) { failed(failure); return false; }
    }

    private boolean remind(JSONObject record, boolean allowBubble) throws Exception {
        if (!reminderAllowed(record)) return false;
        String excerpt;
        synchronized (this) {
            StatusBarNotification[] sources = listener == null ? null : listener.getActiveNotifications(new String[]{record.optString("key")});
            if (sources == null || sources.length != 1) return false;
            JSONObject source = snapshot(sources[0]);
            if (!NotificationManagementRules.sameSource(record, source)) return false;
            excerpt = NotificationManagementRules.reminderSummary(source);
        }
        String text = NotificationCopy.reminder(record.optString("appName"), record.optInt("copySeed"));
        if (!excerpt.isEmpty()) text += "\n" + excerpt;
        android.os.PowerManager power = context.getSystemService(android.os.PowerManager.class);
        android.app.KeyguardManager keyguard = context.getSystemService(android.app.KeyguardManager.class);
        boolean visible = power != null && power.isInteractive() && keyguard != null && !keyguard.isKeyguardLocked();
        boolean shown = false;
        if (visible && allowBubble) {
            try {
                shown = PetOverlay.notify(context, "", "notification:" + record.optString("key") + ":" + record.optLong("postedAtMs"),
                        text, () -> reminderAllowed(record));
            } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException unavailable) {
                Log.w("DroyNotifications", "companion_reminder_unavailable: " + unavailable.getClass().getSimpleName());
            }
        }
        synchronized (this) {
            if (!reminderAllowed(record)) return false;
            if (!shown) {
                if (!notifications.areNotificationsEnabled() || notifications.getNotificationChannel("managed_alerts").getImportance() == NotificationManager.IMPORTANCE_NONE)
                    throw new IllegalStateException(NativeLocale.text("请开启 Droy 通知", "Please enable Droy notifications"));
                PendingIntent open = intents.get(record.optString("key"));
                if (open == null) open = openDroy();
                Notification publicVersion = publicReminder();
                notifications.notify(TAG, record.getInt("notificationId"), new Notification.Builder(context, "managed_alerts")
                        .setSmallIcon(R.drawable.ic_notification_droy)
                        .setContentTitle(String.format(java.util.Locale.ROOT, NativeLocale.text("Droy · %s 有重要通知", "Droy · Important notification from %s"), record.optString("appName")))
                        .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                        .setContentIntent(open).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion).build());
            }
            record.put("remindedAt", System.currentTimeMillis()).put("reminderDelivery", shown ? "companion" : "notification");
            save();
        }
        return shown;
    }

    private Notification publicReminder() {
        return new Notification.Builder(context, "managed_alerts").setSmallIcon(R.drawable.ic_notification_droy)
                .setContentTitle(NativeLocale.text("Droy · 有条重要通知等你查看", "Droy · An important notification is waiting"))
                .setContentText(NativeLocale.text("解锁后查看", "Unlock to view")).build();
    }

    private void publish(JSONObject record, boolean repeat) throws Exception {
        if (!notifications.areNotificationsEnabled()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请开启 Droy 通知", "Please enable Droy notifications"));
        NotificationChannel channel = notifications.getNotificationChannel("managed_alerts");
        if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请开启 Droy 通知", "Please enable Droy notifications"));
        PendingIntent open = intents.get(record.optString("key"));
        if (open == null) open = openDroy();
        boolean onlyAlertOnce = false;
        if (!repeat) for (StatusBarNotification own : notifications.getActiveNotifications())
            if (TAG.equals(own.getTag()) && own.getId() == record.getInt("notificationId")
                    && record.getString("fingerprint").equals(own.getNotification().extras.getString("droySourceFingerprint"))) {
                onlyAlertOnce = true; break;
            }
        android.os.Bundle metadata = new android.os.Bundle(); metadata.putString("droySourceKey", record.optString("key"));
        metadata.putString("droySourceFingerprint", record.getString("fingerprint"));
        Notification notification = new Notification.Builder(context, channel.getId()).setSmallIcon(io.droyagent.app.R.drawable.ic_notification_droy)
                .setContentTitle(record.optString("appName") + " · " + record.optString("displayTitle"))
                .setContentText(record.optString("displayText")).setStyle(new Notification.BigTextStyle().bigText(record.optString("displayText")))
                .addExtras(metadata).setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(onlyAlertOnce)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicReminder()).build();
        notifications.notify(TAG, record.getInt("notificationId"), notification);
    }

    private void publishSummary() throws Exception {
        List<JSONObject> collected = new ArrayList<>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject item = records.getJSONObject(i);
            if (item.optString("action").equals("collect") && !item.optBoolean("dismissed") && managed(item.optString("packageName"))) collected.add(item);
        }
        if (collected.isEmpty()) { notifications.cancel(TAG, SUMMARY_ID); return; }
        if (!notifications.areNotificationsEnabled() || notifications.getNotificationChannel("managed_summary").getImportance() == NotificationManager.IMPORTANCE_NONE)
            throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请开启 Droy 通知", "Please enable Droy notifications"));
        Notification.InboxStyle style = new Notification.InboxStyle();
        for (int i = Math.max(0, collected.size() - 5); i < collected.size(); i++) {
            JSONObject item = collected.get(i); style.addLine(item.optString("appName") + " · " + item.optString("displayTitle"));
        }
        JSONObject last = collected.get(collected.size() - 1);
        notifications.notify(TAG, SUMMARY_ID, new Notification.Builder(context, "managed_summary").setSmallIcon(io.droyagent.app.R.drawable.ic_notification_droy)
                .setContentTitle("Droy · " + collected.size() + io.droyagent.app.NativeLocale.text(" 条通知", " notifications")).setContentText(last.optString("displayTitle"))
                .setStyle(style).setContentIntent(openDroy()).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).build());
    }

    private PendingIntent openDroy() {
        return PendingIntent.getActivity(context, 2000, new Intent(context, MainActivity.class).putExtra("notificationManagement", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private synchronized void schedule() {
        handler.removeCallbacks(maintenance);
        if (!enabled() || listener == null || !RuntimeService.ready || BackgroundRuntime.explicitlyStopped(context)) return;
        long next = Long.MAX_VALUE;
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record == null) continue;
            long adExpires = record.optLong("clearedAt") + 24L * 60 * 60 * 1000;
            if (record.has("clearedAt") && adExpires > System.currentTimeMillis()) next = Math.min(next, adExpires);
            if (!NotificationManagementRules.scheduled(record, revision(), managed(record.optString("packageName")))) continue;
            for (String key : new String[]{"repeatAt", "clearAt", "reminderAt"}) if (record.optLong(key) > 0) next = Math.min(next, record.optLong(key));
        }
        if (next != Long.MAX_VALUE) handler.postDelayed(maintenance, Math.max(1000, next - System.currentTimeMillis()));
    }

    private synchronized JSONArray archive() throws Exception {
        JSONArray result = new JSONArray();
        for (int i = records.length() - 1; i >= 0 && result.length() < 50; i--) {
            JSONObject item = records.getJSONObject(i);
            if (item.optBoolean("applied") && (item.optString("action").equals("collect") || item.optString("action").equals("notify"))) result.put(new JSONObject(item.toString()));
        }
        return result;
    }

    private void trim() throws Exception {
        JSONArray next = new JSONArray(); long earliest = System.currentTimeMillis() - ARCHIVE_LIFETIME;
        for (int i = Math.max(0, records.length() - 200); i < records.length(); i++)
            if (records.getJSONObject(i).optLong("savedAtMs") >= earliest) next.put(records.get(i));
        records = next;
        Set<String> kept = new HashSet<>();
        for (int i = 0; i < records.length(); i++) kept.add(records.getJSONObject(i).optString("key"));
        intents.keySet().retainAll(kept);
    }

    private int explicitLimit(String packageName, String key, int requested) throws Exception {
        if (requested == 0) return 0;
        JSONArray rules = config.getJSONArray("rules");
        for (int i = rules.length() - 1; i >= 0; i--) {
            JSONObject rule = rules.getJSONObject(i);
            if (rule.optString("packageName").equals(packageName) || rule.optString("packageName").equals("*"))
                if (rule.optInt(key) == requested) return requested;
        }
        return 0;
    }

    private boolean enabled() { return enabled(context); }
    private boolean processingActive() {
        NotificationChannel alerts = notifications.getNotificationChannel("managed_alerts");
        return enabled() && RuntimeService.ready && !BackgroundRuntime.explicitlyStopped(context)
                    && listener != null && DroyNotificationService.hasAccess(context) && RuntimeService.ready
                && notifications.areNotificationsEnabled() && alerts != null && alerts.getImportance() > NotificationManager.IMPORTANCE_NONE
                && !ProductPermissions.stopped(context)
                && ProductPermissions.allowed(preferences, "allowNotifications");
    }
    private void cancelAnalysis() {
        HttpURLConnection connection = activeAnalysis;
        if (connection != null) connection.disconnect();
    }
    private long revision() { return config.optLong("revision", 1); }
    private boolean managed(String packageName) {
        return applicationNames.containsKey(packageName) && !appMode(packageName).equals("allow");
    }
    private String appMode(String packageName) { return config.optJSONObject("exceptions").optString(packageName, "managed"); }
    private void changed() throws Exception {
        cancelAnalysis();
        channels.cancelCurrent();
        config.put("revision", revision() + 1);
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i).put("repeatAt", 0).put("reminderAt", 0).put("clearAt", 0);
            if (!managed(record.optString("packageName")) || !appMode(record.optString("packageName")).equals("managed")) {
                record.put("dismissed", true);
                if (record.optString("action").equals("notify") || record.optBoolean("important")) notifications.cancel(TAG, record.optInt("notificationId"));
            }
        }
        save(); refreshStatusNotification(); schedule();
    }
    private void save() throws Exception {
        if (!preferences.edit().putString(DATA, new JSONObject().put("config", config).put("records", records).toString()).commit())
            throw new java.io.IOException(io.droyagent.app.NativeLocale.text("通知管理设置未保存", "Notification management settings were not saved"));
    }
    private void cancelManaged() {
        handler.removeCallbacks(maintenance);
        for (StatusBarNotification own : notifications.getActiveNotifications()) if (TAG.equals(own.getTag())) notifications.cancel(TAG, own.getId());
    }
    private synchronized void failed(Exception failure) {
        if (failure instanceof PolicyChanged) return;
        error = failure instanceof SecurityException ? io.droyagent.app.NativeLocale.text("通知访问已断开", "Notification access disconnected")
                : failure instanceof IllegalStateException && io.droyagent.app.NativeLocale.text("请开启 Droy 通知", "Please enable Droy notifications").equals(failure.getMessage()) ? failure.getMessage() : io.droyagent.app.NativeLocale.text("通知处理失败", "Notification processing failed");
        ForegroundStatusNotifications.refresh(context);
        Log.w("DroyNotifications", "notification_processing_failed: " + failure.getClass().getSimpleName());
    }
    private String appName(String packageName) {
        try { return context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(packageName, 0)).toString(); }
        catch (android.content.pm.PackageManager.NameNotFoundException missing) { return packageName; }
    }
    private JSONObject snapshot(StatusBarNotification source) throws Exception {
        Notification item = source.getNotification(); android.os.Bundle extras = item.extras;
        JSONObject result = new JSONObject().put("key", source.getKey()).put("packageName", source.getPackageName())
                .put("appName", appName(source.getPackageName())).put("title", bounded(extras.getCharSequence(Notification.EXTRA_TITLE), 1000))
                .put("text", bounded(extras.getCharSequence(Notification.EXTRA_BIG_TEXT, extras.getCharSequence(Notification.EXTRA_TEXT)), 4000))
                .put("subtext", bounded(extras.getCharSequence(Notification.EXTRA_SUB_TEXT), 1000))
                .put("postedAtMs", source.getPostTime()).put("channelId", item.getChannelId());
        return result.put("fingerprint", NotificationManagementRules.fingerprint(result));
    }
    private static boolean silent(NotificationChannel channel) {
        return channel != null && channel.getImportance() > NotificationManager.IMPORTANCE_NONE
                && channel.getImportance() <= NotificationManager.IMPORTANCE_LOW && !channel.shouldVibrate();
    }
    private static boolean protectedContent(JSONObject item) {
        return java.util.regex.Pattern.compile("验证码|动态码|校验码|口令|一次性密码|verification code|one[ -]?time (?:pass|code)|\\botp\\b|sensitive.*(?:hidden|redacted)|敏感.*(?:隐藏|内容)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(item.optString("title") + "\n" + item.optString("text")).find();
    }
    private static int stableId(String key) { return 10000 + (key.hashCode() & 0x3fffffff); }
    private static String bounded(CharSequence value, int max) { String text = value == null ? "" : value.toString(); return text.length() > max ? text.substring(0, max) : text; }
    private static Boolean booleanOption(JSONObject source, String key) throws Exception {
        if (!source.has(key)) return null;
        Object value = source.get(key);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("通知管理开关无效", "Invalid notification management switch"));
        return (Boolean) value;
    }
    private static final class PolicyChanged extends Exception { }
}
