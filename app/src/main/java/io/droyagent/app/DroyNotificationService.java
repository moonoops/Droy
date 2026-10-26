package io.droyagent.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.Cancellation;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Android owns the access grant. Revocation/disconnection discards the entire active view. */
public final class DroyNotificationService extends NotificationListenerService {
    private static final NotificationStore STORE = new NotificationStore();
    private static volatile DroyNotificationService connected;

    public static JSONObject status(Context context) throws Exception {
        boolean granted = hasAccess(context);
        if (!granted) STORE.clear();
        boolean live = granted && connected != null;
        return new JSONObject().put("permissionGranted", granted).put("connected", live).put("available", live)
                .put("scope", "current_active_only").put("historyStored", false)
                .put("reason", !granted ? "notification_access_required" : !live ? "listener_disconnected" : "available");
    }

    public static JSONObject query(Context context, JSONObject args, Cancellation cancellation) throws Exception {
        cancellation.check();
        if (!hasAccess(context)) {
            STORE.clear();
            throw new ActionException("notification_permission_required", io.droyagent.app.NativeLocale.text("请在 Android 设置中允许 Droy 读取通知", "Please allow Droy to read notifications in Android settings"));
        }
        DroyNotificationService service = connected;
        if (service == null) throw new ActionException("notification_listener_unavailable", "Notification access is granted but the listener is disconnected");
        service.refresh(cancellation);
        cancellation.check();
        if (!hasAccess(context) || connected != service) {
            STORE.clear();
            throw new ActionException("notification_listener_unavailable", "Notification access changed while reading");
        }
        return STORE.query(args, cancellation);
    }

    static boolean hasAccess(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager != null && manager.isNotificationListenerAccessGranted(new ComponentName(context, DroyNotificationService.class));
    }

    static boolean connected() { return connected != null; }

    static void requestReconnect(Context context) {
        if (hasAccess(context)) requestRebind(new ComponentName(context, DroyNotificationService.class));
    }

    @Override public void onListenerConnected() {
        connected = this;
        STORE.clear();
        NotificationManagement.get(this).connected(this);
    }

    @Override public void onListenerDisconnected() { disconnect(); }
    @Override public boolean onUnbind(Intent intent) { disconnect(); return super.onUnbind(intent); }
    @Override public void onDestroy() { disconnect(); super.onDestroy(); }

    private void disconnect() {
        if (connected == this) { connected = null; STORE.clear(); NotificationManagement.get(this).disconnected(this); }
    }

    static void processCurrentNotifications(Context context) { NotificationManagement.get(context).rescan(); }

    @Override public void onNotificationPosted(StatusBarNotification notification) {
        if (connected != this || !hasAccess(this)) { STORE.clear(); return; }
        NotificationStore.Entry entry = entry(notification);
        if (entry != null) STORE.put(entry);
        NotificationManagement.get(this).posted(notification, getCurrentRanking());
    }

    @Override public void onNotificationRemoved(StatusBarNotification notification) {
        if (notification != null) STORE.remove(notification.getKey());
    }

    @Override public void onNotificationRemoved(StatusBarNotification notification, RankingMap ranking, int reason) {
        onNotificationRemoved(notification);
        NotificationManagement.get(this).removed(notification, reason);
    }


    private void refresh(Cancellation cancellation) throws Exception {
        cancellation.check();
        if (!hasAccess(this) || connected != this) throw new ActionException("notification_listener_unavailable", "Notification listener is no longer available");
        StatusBarNotification[] active = getActiveNotifications();
        cancellation.check();
        if (active == null) throw new ActionException("notification_listener_unavailable", "Android did not return current notifications");
        List<NotificationStore.Entry> entries = new ArrayList<>();
        for (StatusBarNotification item : active) {
            cancellation.check();
            NotificationStore.Entry converted = entry(item);
            if (converted != null) entries.add(converted);
        }
        if (!hasAccess(this) || connected != this) throw new ActionException("notification_listener_unavailable", "Notification listener disconnected while reading");
        STORE.replace(entries);
    }

    private NotificationStore.Entry entry(StatusBarNotification item) {
        if (item == null || getPackageName().equals(item.getPackageName())) return null;
        Notification notification = item.getNotification();
        if (notification == null) return null;
        Bundle extras = notification.extras;
        return new NotificationStore.Entry(item.getKey(), item.getPackageName(), text(extras, Notification.EXTRA_TITLE),
                text(extras, Notification.EXTRA_TEXT), text(extras, Notification.EXTRA_SUB_TEXT), item.getPostTime(), item.isOngoing());
    }

    private static String text(Bundle extras, String key) {
        if (extras == null) return "";
        CharSequence value = extras.getCharSequence(key);
        return value == null ? "" : value.toString();
    }
}
