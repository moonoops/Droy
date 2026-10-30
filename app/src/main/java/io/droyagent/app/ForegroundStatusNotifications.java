package io.droyagent.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.Looper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.json.JSONObject;

/** All product foreground lifetimes share one notification, while retaining their Android service types. */
public final class ForegroundStatusNotifications {
    public static final int ID = 1;
    public static final String CHANNEL = "runtime";
    private record Owner(boolean runtime, StatusNotificationPolicy.Audio audio, String captureId,
                         boolean camera, boolean location, boolean hardware, PendingIntent stop) { }
    private static final Map<Service, Owner> owners = new LinkedHashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static String event = "";
    private static int seed;
    private ForegroundStatusNotifications() { }

    public static void startRuntime(Service service) {
        attach(service, new Owner(true, StatusNotificationPolicy.Audio.NONE, "", false, false, false, null),
                android.os.Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
    }

    public static void startAudio(Service service, int types, String captureId, String captureOwner,
                                  boolean microphone, boolean camera, PendingIntent stop) {
        StatusNotificationPolicy.Audio audio = switch (captureOwner) {
            case "droy-voice-wake" -> StatusNotificationPolicy.Audio.WAKE;
            case "droy-voice" -> StatusNotificationPolicy.Audio.VOICE;
            case "droy-voice-enrollment" -> StatusNotificationPolicy.Audio.ENROLLMENT;
            default -> microphone ? StatusNotificationPolicy.Audio.RECORDING : StatusNotificationPolicy.Audio.PLAYBACK;
        };
        attach(service, new Owner(false, audio, captureId, camera, false, false, stop), types);
    }

    public static void startLocation(Service service, PendingIntent stop) {
        attach(service, new Owner(false, StatusNotificationPolicy.Audio.NONE, "", false, true, false, stop),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
    }

    public static void startHardware(Service service, int types, PendingIntent stop, boolean location) {
        attach(service, new Owner(false, StatusNotificationPolicy.Audio.NONE, "", false, location, true, stop), types);
    }

    private static synchronized void attach(Service service, Owner owner, int types) {
        Owner previous = owners.put(service, owner);
        try {
            service.startForeground(ID, build(service), types);
            // Previous builds used separate IDs for audio and location. Retire them only after promotion succeeds.
            NotificationManager manager = service.getSystemService(NotificationManager.class);
            manager.cancel(41); manager.cancel(8405);
        } catch (RuntimeException failure) {
            if (previous == null) owners.remove(service); else owners.put(service, previous);
            throw failure;
        }
    }

    public static synchronized void stop(Service service) {
        if (owners.remove(service) == null) return;
        // Detaching one owner must not remove the notification still used by another foreground service.
        service.stopForeground(owners.isEmpty() ? Service.STOP_FOREGROUND_REMOVE : Service.STOP_FOREGROUND_DETACH);
        NotificationManager manager = service.getSystemService(NotificationManager.class);
        if (owners.isEmpty()) manager.cancel(ID);
        else manager.notify(ID, build(service));
    }

    public static void refresh(Context context) {
        Context app = context.getApplicationContext();
        main.post(() -> { synchronized (ForegroundStatusNotifications.class) {
            if (!owners.isEmpty()) app.getSystemService(NotificationManager.class).notify(ID, build(app));
        } });
    }

    private static Notification build(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        if (channel == null) {
            channel = new NotificationChannel(CHANNEL, NativeLocale.text("Droy 运行状态", "Droy status"), NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null); channel.enableVibration(false);
        } else channel.setName(NativeLocale.text("Droy 运行状态", "Droy status"));
        manager.createNotificationChannel(channel);
        boolean runtime = false, camera = false, location = false;
        Owner audio = null, position = null, hardware = null;
        for (Owner owner : owners.values()) {
            runtime |= owner.runtime(); camera |= owner.camera(); location |= owner.location();
            if (owner.audio() != StatusNotificationPolicy.Audio.NONE) audio = owner;
            if (owner.location() && !owner.hardware()) position = owner;
            if (owner.hardware()) hardware = owner;
        }
        JSONObject progress = ToolProgress.current(context);
        boolean ready = !runtime || RuntimeService.ready;
        boolean active = runtime && progress.optBoolean("active");
        String phase = progress.optString("phase"), task = progress.optString("taskId");
        StatusNotificationPolicy.Audio audioKind = audio == null ? StatusNotificationPolicy.Audio.NONE : audio.audio();
        String titlePhase = java.util.Set.of("waiting_input", "waiting", "blocked").contains(phase) ? phase : "working";
        String next = ready + ":" + active + ":" + (active ? titlePhase + ":" + task : audioKind + ":" + location);
        if (!event.equals(next)) { event = next; seed = ThreadLocalRandom.current().nextInt(); }
        boolean attention = NotificationManagement.attentionRequired(context);
        String title = StatusNotificationPolicy.title(ready, active, phase, audioKind, location, seed);
        if (!active && audio == null && position == null && hardware != null) title = hardware.location()
            ? NativeLocale.text("正在获取当前位置", "Getting current location") : NativeLocale.text("设备连接进行中", "Device connection active");
        String detail = StatusNotificationPolicy.detail(active, phase, progress.optString("summary"), progress.optString("summaryKind"),
                audioKind, camera, location, attention, ProductPermissions.stopped(context));
        if (!active && audio == null && position == null && hardware != null && hardware.location())
            detail = NativeLocale.text("为当前任务读取位置，完成后停止。", "Reading location for the current task; stops when complete.");
        Intent target = new Intent(context, NativeAppActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (active && !task.isEmpty()) target.putExtra("petTaskId", task);
        JSONObject question = progress.optJSONObject("pending");
        if (active && question != null) target.putExtra("petRequestId", question.optString("requestId"));
        if (!active && audio != null && (audioKind == StatusNotificationPolicy.Audio.RECORDING || audioKind == StatusNotificationPolicy.Audio.PLAYBACK))
            target.putExtra("audioCaptureId", audio.captureId());
        if (!active && audio == null && attention) target.putExtra("notificationManagement", true);
        PendingIntent open = PendingIntent.getActivity(context, 0, target, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification publicVersion = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification_droy)
                .setContentTitle(title).setShowWhen(false).build();
        Notification.Builder notification = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification_droy)
                .setContentTitle(title).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setDefaults(0).setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion);
        if (!detail.isEmpty()) notification.setContentText(detail).setStyle(new Notification.BigTextStyle().bigText(detail));
        int actions = 0;
        if (active) {
            PendingIntent cancel = PendingIntent.getService(context, 2, new Intent(context, RuntimeService.class)
                    .setAction(RuntimeService.CANCEL_TASK).setData(android.net.Uri.parse("droy://task/" + android.net.Uri.encode(task)))
                    .putExtra("taskId", task), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            notification.addAction(new Notification.Action.Builder(null, NativeLocale.text("停止任务", "Stop task"), cancel).build()); actions++;
        }
        if (audio != null && audio.stop() != null) { notification.addAction(new Notification.Action.Builder(null, StatusNotificationPolicy.stopLabel(audioKind), audio.stop()).build()); actions++; }
        if (position != null && position.stop() != null) { notification.addAction(new Notification.Action.Builder(null, position.hardware()
            ? NativeLocale.text("停止定位任务", "Stop location task") : NativeLocale.text("停止位置记录", "Stop location recording"), position.stop()).build()); actions++; }
        if (hardware != null && hardware != position && hardware.stop() != null && actions < 3) {
            notification.addAction(new Notification.Action.Builder(null, NativeLocale.text("暂停手机操作", "Pause phone operations"), hardware.stop()).build()); actions++;
        }
        if (active && actions < 3 && !ProductPermissions.stopped(context)) {
            PendingIntent pause = PendingIntent.getService(context, 1, new Intent(context, RuntimeService.class).setAction(RuntimeService.PAUSE),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            notification.addAction(new Notification.Action.Builder(null, NativeLocale.text("暂停手机操作", "Pause phone operations"), pause).build()); actions++;
        }
        if (attention && actions < 3) {
            PendingIntent settings = PendingIntent.getActivity(context, 3, new Intent(context, NativeAppActivity.class).putExtra("notificationManagement", true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            notification.addAction(new Notification.Action.Builder(null, NativeLocale.text("去设置", "Open settings"), settings).build());
        }
        return notification.build();
    }
}
