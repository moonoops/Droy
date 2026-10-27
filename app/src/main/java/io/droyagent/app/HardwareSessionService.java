package io.droyagent.app;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import io.droyagent.androiduse.ActionException;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Foreground lifetime is held only while a task has an active hardware operation. */
public final class HardwareSessionService extends Service {
    private static final String STOP = "io.droyagent.HARDWARE_STOP";
    private static final Object LOCK = new Object();
    private static final Map<String, Lease> leases = new LinkedHashMap<>();
    private static HardwareSessionService running;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static final class Lease implements Closeable {
        final String id = UUID.randomUUID().toString();
        final int type;
        final CompletableFuture<Void> started = new CompletableFuture<>();
        boolean ready;
        HardwareSessionService service;
        Lease(int type) { this.type = type; }
        @Override public void close() {
            synchronized (LOCK) {
                if (leases.remove(id) == null) return;
                if (!started.isDone()) started.completeExceptionally(new IOException(
                    NativeLocale.text("硬件操作启动已取消", "Hardware operation startup was cancelled")));
            }
            main.post(HardwareSessionService::refreshRunning);
        }
    }

    /** Called by the product's tool worker, before accessing hardware. */
    static Closeable begin(Context context, String type) throws ActionException, InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException(NativeLocale.text("硬件任务需从工作线程启动", "Start hardware tasks from a worker thread"));
        int serviceType = switch (type) {
            case "connectedDevice" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            case "location" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            default -> throw new IllegalArgumentException(NativeLocale.text("不支持的硬件服务类型", "Unsupported hardware service type"));
        };
        Lease lease = new Lease(serviceType);
        synchronized (LOCK) { leases.put(lease.id, lease); }
        try {
            context.getApplicationContext().startForegroundService(new Intent(context, HardwareSessionService.class));
        } catch (RuntimeException failure) {
            lease.close();
            throw new ActionException("foreground_service_unavailable", NativeLocale.text("系统不允许启动硬件前台任务", "Android did not allow the hardware foreground task to start"), failure);
        }
        try {
            lease.started.get(5, TimeUnit.SECONDS);
            return lease;
        } catch (InterruptedException interrupted) {
            lease.close();
            throw interrupted;
        } catch (ExecutionException failed) {
            lease.close();
            throw new ActionException("foreground_service_unavailable", NativeLocale.text("硬件前台任务未能启动", "The hardware foreground task could not start"), failed.getCause());
        } catch (TimeoutException timeout) {
            lease.close();
            throw new ActionException("foreground_service_start_timeout", NativeLocale.text("硬件前台任务启动超时", "Hardware foreground task startup timed out"), timeout);
        }
    }

    static void closeAll() {
        ArrayList<Lease> closing;
        synchronized (LOCK) { closing = new ArrayList<>(leases.values()); }
        for (Lease lease : closing) lease.close();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        synchronized (LOCK) { running = this; }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            RuntimeService.pauseOperations(this);
            closeAll();
        }
        refreshForeground();
        return START_NOT_STICKY;
    }

    private static void refreshRunning() {
        HardwareSessionService current;
        synchronized (LOCK) { current = running; }
        if (current != null) current.refreshForeground();
    }

    private void refreshForeground() {
        int types = 0;
        ArrayList<Lease> pending = new ArrayList<>();
        synchronized (LOCK) {
            for (Lease lease : leases.values()) {
                types |= lease.type;
                if (!lease.ready) pending.add(lease);
            }
        }
        if (types == 0) {
            ForegroundStatusNotifications.stop(this);
            stopSelf();
            return;
        }
        PendingIntent stop = PendingIntent.getService(this, 8406,
            new Intent(this, HardwareSessionService.class).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            ForegroundStatusNotifications.startHardware(this, types, stop,
                (types & ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0);
            synchronized (LOCK) {
                for (Lease lease : pending) {
                    if (!leases.containsKey(lease.id)) continue;
                    lease.ready = true;
                    lease.service = this;
                    lease.started.complete(null);
                }
            }
        } catch (RuntimeException failure) {
            // Failed promotion must reach its callers; an existing valid lease remains attached.
            synchronized (LOCK) {
                for (Lease lease : pending) {
                    leases.remove(lease.id);
                    lease.started.completeExceptionally(failure);
                }
            }
            if (pending.isEmpty()) {
                closeAll();
                RuntimeService.pauseOperations(this);
            }
            synchronized (LOCK) {
                if (!leases.isEmpty()) return;
            }
            ForegroundStatusNotifications.stop(this);
            stopSelf();
        }
    }

    @Override public void onDestroy() {
        ArrayList<Lease> interrupted = new ArrayList<>();
        synchronized (LOCK) {
            if (running == this) running = null;
            for (Lease lease : leases.values()) if (lease.service == this) interrupted.add(lease);
        }
        for (Lease lease : interrupted) lease.close();
        ForegroundStatusNotifications.stop(this);
        if (!interrupted.isEmpty()) RuntimeService.pauseOperations(this);
        super.onDestroy();
    }
}
