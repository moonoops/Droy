package io.droyagent.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import io.droyagent.androiduse.DroyAccessibilityService;
import io.droyagent.androiduse.OverlayInteraction;
import java.lang.ref.WeakReference;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** A non-interactive runtime-owned display border, independent of companion display settings. */
final class AutomationScreenOverlay implements OverlayInteraction.Listener {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static AutomationScreenOverlay instance;
    private static volatile String displayError = "";
    private final Context context;
    private final SharedPreferences preferences;
    private WeakReference<Activity> foregroundActivity = new WeakReference<>(null);
    private ScreenGlowView glow;
    private WindowManager.LayoutParams layout;
    private WindowManager windows;
    private Context windowContext;
    private IBinder attachedToken;
    private int windowId = -1, preparationDepth;
    private long foregroundSequence, restoreSequence;
    private boolean registered, capturing;
    private final Set<CompletableFuture<Void>> pendingCaptures = new LinkedHashSet<>();
    private CompletableFuture<Void> capturePreparation;

    static void refresh(Context context) { MAIN.post(() -> get(context).refreshNow()); }
    static void runtimeStopped() { MAIN.post(() -> { if (instance != null) instance.close(); }); }
    static String error() { return displayError; }

    static void foreground(Context context, boolean visible) {
        if (!(context instanceof Activity activity)) return;
        MAIN.post(() -> {
            AutomationScreenOverlay overlay = get(context);
            if (visible) {
                overlay.foregroundSequence++;
                overlay.foregroundActivity = new WeakReference<>(activity); overlay.refreshNow();
            } else if (overlay.foregroundActivity.get() == activity) {
                long sequence = ++overlay.foregroundSequence;
                MAIN.postDelayed(() -> {
                    if (sequence != overlay.foregroundSequence || overlay.foregroundActivity.get() != activity) return;
                    overlay.foregroundActivity.clear(); overlay.refreshNow();
                }, 80);
            }
        });
    }

    private static AutomationScreenOverlay get(Context context) {
        if (instance == null) instance = new AutomationScreenOverlay(context.getApplicationContext());
        return instance;
    }

    private AutomationScreenOverlay(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
    }

    private boolean active() {
        return RuntimeService.hasActiveSession() && preferences.getBoolean("automationScreenDisplay", true)
            && !ProductPermissions.stopped(context) && ToolProgress.screenOperationActive();
    }

    private boolean screenAvailable() {
        return context.getSystemService(PowerManager.class).isInteractive()
            && !context.getSystemService(KeyguardManager.class).isKeyguardLocked();
    }

    private Activity activity() {
        Activity activity = foregroundActivity.get();
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
            && activity.getWindow().getDecorView().getWindowToken() != null ? activity : null;
    }

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context unused, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) removeWindow();
            else refreshNow();
        }
    };

    private final ComponentCallbacks configuration = new ComponentCallbacks() {
        @Override public void onConfigurationChanged(Configuration unused) {
            if (glow != null) glow.configurationChanged();
            refreshNow();
        }
        @Override public void onLowMemory() {}
    };

    @Override public void onAccessibilityConnectionChanged() {
        refreshNow();
        Choreographer.getInstance().postFrameCallback(frame -> registerWindowId());
    }

    private void refreshNow() {
        if (!RuntimeService.hasActiveSession()) { close(); return; }
        if (!registered) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(screen, filter);
            context.registerComponentCallbacks(configuration);
            OverlayInteraction.addListener(this); registered = true;
        }
        if (!screenAvailable()) { removeWindow(); return; }
        if (!active()) {
            if (glow != null) {
                ScreenGlowView ending = glow;
                ending.hide(() -> { if (glow == ending && !active()) removeWindow(); });
            }
            return;
        }

        Context service = DroyAccessibilityService.overlayWindowContext(context);
        Activity host = service == null ? activity() : null;
        Context owner = service != null ? service : host != null ? host : context;
        int type = service != null ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            : host != null ? WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
            : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY && !Settings.canDrawOverlays(context)) {
            removeWindow(); return;
        }
        IBinder token = host == null ? null : host.getWindow().getDecorView().getWindowToken();
        if (glow != null && (owner != windowContext || type != layout.type || token != attachedToken)) removeWindow();
        if (capturing) return;
        if (glow == null) addWindow(owner, type, token);
        if (glow != null) glow.show();
    }

    private void addWindow(Context owner, int type, IBinder token) {
        windowContext = owner; attachedToken = token;
        windows = owner.getSystemService(WindowManager.class);
        layout = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.LEFT;
        layout.token = token; layout.setTitle("Droy automation screen display");
        layout.setFitInsetsTypes(0);
        layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        layout.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            // Android 12 checks window alpha, even where a full-screen surface has no pixels.
            layout.alpha = Build.VERSION.SDK_INT >= 31
                ? Math.min(.6f, context.getSystemService(InputManager.class).getMaximumObscuringOpacityForTouch()) : .6f;
        }
        ScreenGlowView candidate = new ScreenGlowView(owner);
        try {
            windows.addView(candidate, layout); glow = candidate; displayError = "";
            registerWindowId();
            candidate.post(() -> { if (glow == candidate) registerWindowId(); });
        } catch (WindowManager.BadTokenException | SecurityException failure) {
            candidate.dispose();
            if (candidate.getParent() != null) windows.removeViewImmediate(candidate);
            windowContext = null; attachedToken = null;
            displayError = io.droyagent.app.NativeLocale.text("屏幕边缘提醒暂时无法显示，请检查显示权限后重试。", "Screen edge alert cannot be displayed. Please check display permission and retry.");
            Log.w("DroyScreenGlow", "Cannot attach automation indicator", failure);
        }
    }

    private void registerWindowId() {
        if (glow == null || !glow.isAttachedToWindow()) return;
        AccessibilityNodeInfo node = glow.createAccessibilityNodeInfo();
        int next = node.getWindowId(); node.recycle();
        if (next != windowId) {
            OverlayInteraction.unregisterWindow(windowId); windowId = next; OverlayInteraction.registerWindow(windowId);
        }
    }

    @Override public CompletableFuture<Void> prepare(Rect area, boolean capture) {
        preparationDepth++; restoreSequence++;
        if (!capture) return CompletableFuture.completedFuture(null);
        capturing = true;
        if (glow == null || !glow.isAttachedToWindow()) return CompletableFuture.completedFuture(null);
        if (capturePreparation != null && !capturePreparation.isDone()) return capturePreparation.thenApply(ignored -> null);
        ScreenGlowView captured = glow;
        if (captured.getVisibility() == View.INVISIBLE) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> ready = new CompletableFuture<>(); pendingCaptures.add(ready);
        capturePreparation = ready;
        if (captured.isHardwareAccelerated()) {
            captured.getViewTreeObserver().registerFrameCommitCallback(() -> MAIN.post(() -> captureFrameCommitted(captured, ready)));
        } else afterTwoFrames(() -> captureFrameCommitted(captured, ready));
        // Some compositors retain the last border when onDraw merely emits no
        // commands. Submit an alpha-zero visible buffer before hiding its surface.
        captured.setCaptureSuppressed(true); captured.setAlpha(0f);
        captured.setVisibility(View.VISIBLE); captured.invalidate();
        // Each preparation can be cancelled without cancelling the shared surface barrier.
        return ready.thenApply(ignored -> null);
    }

    private void captureFrameCommitted(ScreenGlowView captured, CompletableFuture<Void> ready) {
        if (captured == glow && capturing) captured.setVisibility(View.INVISIBLE);
        Choreographer.getInstance().postFrameCallback(frame -> completeCapture(ready));
    }

    private void completeCapture(CompletableFuture<Void> ready) {
        pendingCaptures.remove(ready);
        if (capturePreparation == ready) capturePreparation = null;
        ready.complete(null);
    }

    @Override public void finish() {
        preparationDepth = Math.max(0, preparationDepth - 1);
        if (preparationDepth != 0 || !capturing) return;
        long sequence = ++restoreSequence;
        Runnable restore = () -> {
            if (sequence != restoreSequence || preparationDepth != 0) return;
            capturing = false;
            if (glow != null && active() && screenAvailable()) {
                glow.setVisibility(View.VISIBLE); glow.setAlpha(1f); glow.setCaptureSuppressed(false);
            }
            refreshNow();
        };
        // Cancellation may finish before the transparent buffer has committed.
        CompletableFuture<Void> pending = capturePreparation;
        if (pending != null && !pending.isDone()) pending.thenRun(() -> afterTwoFrames(restore));
        else afterTwoFrames(restore);
    }

    private static void afterTwoFrames(Runnable complete) {
        Choreographer.getInstance().postFrameCallback(first ->
            Choreographer.getInstance().postFrameCallback(second -> complete.run()));
    }

    private void removeWindow() {
        if (glow != null) {
            ScreenGlowView removed = glow; glow = null; removed.dispose();
            OverlayInteraction.unregisterWindow(windowId); windowId = -1;
            if (removed.getParent() != null) windows.removeViewImmediate(removed);
        }
        windowContext = null; attachedToken = null;
        if (!pendingCaptures.isEmpty()) {
            Set<CompletableFuture<Void>> removed = new LinkedHashSet<>(pendingCaptures);
            afterTwoFrames(() -> removed.forEach(this::completeCapture));
        }
    }

    private void close() {
        removeWindow(); restoreSequence++; preparationDepth = 0; capturing = false;
        if (registered) {
            OverlayInteraction.removeListener(this);
            context.unregisterReceiver(screen); context.unregisterComponentCallbacks(configuration);
            registered = false;
        }
    }
}
