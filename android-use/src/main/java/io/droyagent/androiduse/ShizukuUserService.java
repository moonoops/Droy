package io.droyagent.androiduse;

import android.content.Context;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shizuku's non-daemon helper accepts fixed operations, never shell source. */
public final class ShizukuUserService extends IShizukuOperations.Stub {
    private final int appUid;
    private final InputManager input;
    private java.lang.reflect.Method injector;
    private AtomicBoolean cancelled;
    private Thread worker;

    public ShizukuUserService(Context context) {
        appUid = context.getApplicationInfo().uid;
        input = context.getSystemService(InputManager.class);
    }

    @Override public synchronized ParcelFileDescriptor execute(String action, int[] values) throws RemoteException {
        if (Binder.getCallingUid() != appUid) throw new SecurityException("Only the owning app may execute operations");
        if (cancelled != null) throw new IllegalStateException("An operation is still running");
        List<String> command = command(action, values);
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
            AtomicBoolean stop = new AtomicBoolean();
            cancelled = stop;
            worker = new Thread(() -> {
                DataOutputStream output = new DataOutputStream(new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]));
                boolean failed = false;
                try {
                    CommandRunner.Result result;
                    if (action.equals("tap") || action.equals("swipe")) {
                        touch(action, values, stop);
                        result = new CommandRunner.Result(0, new byte[0]);
                    } else result = CommandRunner.run(command, action.equals("screenshot") ? 16 * 1024 * 1024 : 8192,
                            15000, () -> { if (stop.get()) throw new InterruptedException("Operation cancelled"); });
                    output.writeInt(result.exitCode);
                    output.write(result.stdout);
                    output.flush();
                } catch (Exception failure) {
                    failed = true;
                } finally {
                    synchronized (this) { if (cancelled == stop) cancelled = null; }
                    // EOF is the completion receipt; release ownership before the client can observe it.
                    try {
                        if (failed) pipe[1].closeWithError("Shizuku operation failed");
                        output.close();
                    } catch (IOException ignored) { /* Reader already closed. */ }
                }
            }, "droy-shizuku-operation");
            worker.start();
            return pipe[0];
        } catch (IOException failure) { throw new RemoteException("Could not create operation pipe"); }
    }

    @Override public void cancel() {
        if (Binder.getCallingUid() != appUid) throw new SecurityException("Only the owning app may cancel operations");
        Thread pending;
        synchronized (this) {
            if (cancelled != null) cancelled.set(true);
            pending = worker;
        }
        try { if (pending != null) pending.join(2000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Cancellation interrupted", interrupted); }
        if (pending != null && pending.isAlive()) throw new IllegalStateException("Shizuku operation has not stopped");
    }

    @Override public void destroy() { System.exit(0); }

    @Override public int uid() { return android.os.Process.myUid(); }

    private void touch(String action, int[] values, AtomicBoolean stop) throws Exception {
        if (injector == null) injector = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
        int deviceId = 0;
        for (int id : InputDevice.getDeviceIds()) if (InputDevice.getDevice(id).supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) { deviceId = id; break; }
        long downTime = SystemClock.uptimeMillis();
        int duration = action.equals("tap") ? 70 : values[4];
        float x = values[0], y = values[1];
        boolean held = false;
        try {
            if (stop.get()) throw new InterruptedException("Operation cancelled");
            motion(deviceId, downTime, MotionEvent.ACTION_DOWN, x, y);
            held = true;
            while (SystemClock.uptimeMillis() - downTime < duration) {
                if (stop.get()) throw new InterruptedException("Operation cancelled");
                float progress = Math.min(1f, (SystemClock.uptimeMillis() - downTime) / (float) duration);
                if (action.equals("swipe")) {
                    x = values[0] + (values[2] - values[0]) * progress;
                    y = values[1] + (values[3] - values[1]) * progress;
                    motion(deviceId, downTime, MotionEvent.ACTION_MOVE, x, y);
                }
                Thread.sleep(8);
            }
            if (stop.get()) throw new InterruptedException("Operation cancelled");
            motion(deviceId, downTime, MotionEvent.ACTION_UP,
                    action.equals("tap") ? x : values[2], action.equals("tap") ? y : values[3]);
            held = false;
        } finally {
            // Killing `input swipe` cannot cancel the loop inside system_server.
            // Own the event sequence here so stop ends it with CANCEL before returning.
            if (held) motion(deviceId, downTime, MotionEvent.ACTION_CANCEL, x, y);
        }
    }

    private void motion(int deviceId, long downTime, int action, float x, float y) throws Exception {
        MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
        pointer.id = 0; pointer.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords position = new MotionEvent.PointerCoords();
        position.x = x; position.y = y; position.size = 1; position.pressure = action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP ? 0 : 1;
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                new MotionEvent.PointerProperties[]{pointer}, new MotionEvent.PointerCoords[]{position},
                0, 0, 1f, 1f, deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            if (!Boolean.TRUE.equals(injector.invoke(input, event, 2))) throw new IllegalStateException("Android rejected the injected motion");
        } finally { event.recycle(); }
    }

    static List<String> command(String action, int[] values) {
        if (values == null) throw new IllegalArgumentException("Missing operation arguments");
        int expected = action.equals("tap") ? 2 : action.equals("swipe") ? 5 : 0;
        if (values.length != expected) throw new IllegalArgumentException("Invalid operation arguments");
        for (int value : values) if (value < 0 || value > 8192) throw new IllegalArgumentException("Invalid coordinate");
        return switch (action) {
            case "tap" -> List.of("/system/bin/input", "tap", "" + values[0], "" + values[1]);
            case "swipe" -> {
                if (values[4] < 50 || values[4] > 2000) throw new IllegalArgumentException("Invalid duration");
                yield List.of("/system/bin/input", "swipe", "" + values[0], "" + values[1], "" + values[2], "" + values[3], "" + values[4]);
            }
            case "back" -> List.of("/system/bin/input", "keyevent", "4");
            case "screenshot" -> List.of("/system/bin/screencap", "-p");
            case "foreground" -> List.of("/system/bin/sh", "-c", "/system/bin/dumpsys window | /system/bin/grep -m 1 mCurrentFocus");
            default -> throw new IllegalArgumentException("Unsupported Shizuku operation");
        };
    }
}
