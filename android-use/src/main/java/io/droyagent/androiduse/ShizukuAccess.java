package io.droyagent.androiduse;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

/** Optional access to an already running and authorized Shizuku service. */
public final class ShizukuAccess {
    private static ShizukuAccess instance;
    private final Shizuku.UserServiceArgs args;
    private CompletableFuture<IShizukuOperations> connection;
    private final ServiceConnection listener = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (ShizukuAccess.this) { if (connection != null) connection.complete(IShizukuOperations.Stub.asInterface(binder)); }
        }
        @Override public void onServiceDisconnected(ComponentName name) { disconnected(); }
    };

    public static synchronized ShizukuAccess get(Context context) {
        if (instance == null) instance = new ShizukuAccess(context.getApplicationContext());
        return instance;
    }

    private ShizukuAccess(Context context) {
        int installed;
        try { installed = (int) (context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime / 1000); }
        catch (PackageManager.NameNotFoundException impossible) { throw new IllegalStateException(impossible); }
        args = new Shizuku.UserServiceArgs(new ComponentName(context, ShizukuUserService.class))
                .daemon(false).processNameSuffix("droy_operations").version(installed);
        Shizuku.addBinderDeadListener(this::disconnected);
    }

    private synchronized void disconnected() {
        if (connection != null) connection.completeExceptionally(new IllegalStateException("Shizuku disconnected"));
        connection = null;
    }

    public boolean available() {
        try { return Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (IllegalStateException | SecurityException unavailable) { return false; }
    }

    public int uid() {
        try { return available() ? Shizuku.getUid() : -1; }
        catch (IllegalStateException unavailable) { return -1; }
    }

    public JSONObject status() throws org.json.JSONException {
        return new JSONObject().put("running", Shizuku.pingBinder()).put("authorized", available()).put("uid", uid());
    }

    public void requestPermission() {
        if (!Shizuku.pingBinder()) throw new IllegalStateException("请先安装并启动 Shizuku；普通手机操作可继续使用无障碍");
        if (!available()) Shizuku.requestPermission(30);
    }

    IShizukuOperations connect(Cancellation cancellation) throws Exception {
        if (!available()) throw new ActionException("shizuku_unavailable", "Shizuku is not running or authorized");
        CompletableFuture<IShizukuOperations> pending;
        synchronized (this) {
            if (connection == null) {
                connection = new CompletableFuture<>();
                try { Shizuku.bindUserService(args, listener); }
                catch (IllegalStateException | SecurityException failure) { connection = null; throw new ActionException("shizuku_unavailable", "Shizuku service could not be bound", failure); }
            }
            pending = connection;
        }
        try {
            IShizukuOperations service = waitFor(pending, 5000, cancellation);
            if (!service.asBinder().pingBinder()) throw new ActionException("shizuku_unavailable", "Shizuku helper disconnected");
            return service;
        } catch (ActionException | java.util.concurrent.ExecutionException failure) {
            disconnected();
            throw new ActionException("shizuku_unavailable", "Shizuku helper is unavailable", failure);
        }
    }

    CommandRunner.Result run(String action, JSONObject input, Cancellation cancellation) throws Exception {
        IShizukuOperations service = connect(cancellation);
        cancellation.usingRoot(service.uid() == 0);
        int[] values = switch (action) {
            case "tap" -> new int[]{input.getInt("x"), input.getInt("y")};
            case "swipe" -> new int[]{input.getInt("startX"), input.getInt("startY"), input.getInt("endX"), input.getInt("endY"), input.optInt("durationMs", 300)};
            default -> new int[0];
        };
        cancellation.check();
        try (ParcelFileDescriptor pipe = service.execute(action, values);
             DataInputStream output = new DataInputStream(new ParcelFileDescriptor.AutoCloseInputStream(pipe))) {
            CompletableFuture<CommandRunner.Result> result = new CompletableFuture<>();
            Thread reader = new Thread(() -> {
                try {
                    int exit = output.readInt();
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    int limit = action.equals("screenshot") ? 16 * 1024 * 1024 : 8192;
                    while ((count = output.read(buffer)) != -1) {
                        if ((long) bytes.size() + count > limit) throw new java.io.IOException("Shizuku output exceeded limit");
                        bytes.write(buffer, 0, count);
                    }
                    result.complete(new CommandRunner.Result(exit, bytes.toByteArray()));
                } catch (Exception failure) { result.completeExceptionally(failure); }
            }, "droy-shizuku-output");
            reader.setDaemon(true);
            reader.start();
            try { return waitFor(result, 15000, cancellation); }
            finally { if (!result.isDone()) { output.close(); service.cancel(); } }
        } catch (RemoteException failure) {
            disconnected();
            throw new ActionException("shizuku_failed", "Shizuku disconnected during execution; observe the result before continuing", failure);
        }
    }

    private static <T> T waitFor(CompletableFuture<T> future, long timeout, Cancellation cancellation) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + cancellation.remainingMillis(timeout);
        while (true) {
            cancellation.check();
            if (android.os.SystemClock.elapsedRealtime() >= deadline) throw new ActionException("shizuku_timeout", "Shizuku operation timed out");
            try { return future.get(50, TimeUnit.MILLISECONDS); }
            catch (TimeoutException waiting) { /* Check task cancellation while waiting for Binder or output. */ }
        }
    }
}
