package io.droyagent.app;

import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.app.Activity;
import java.lang.ref.WeakReference;
import android.content.*;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import io.droyagent.androiduse.OverlayInteraction;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.CompletableFuture;

/** Two bounded windows keep the rest of the phone touchable; typing temporarily owns input focus. */
final class PetOverlay implements OverlayInteraction.Listener {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static PetOverlay instance;
    private final Context context;
    private final SharedPreferences preferences;
    private final WindowManager systemWindows;
    private WindowManager windows;
    private WeakReference<Activity> foregroundActivity = new WeakReference<>(null);
    private boolean internalWindow;
    private android.os.IBinder attachedToken;
    private final PetDisplayPolicy displayPolicy = new PetDisplayPolicy();
    private long previewUntil, lastEntryAt;
    private String observedModes;
    private boolean entering, exiting, holding;
    private ValueAnimator transition;
    private long transitionSequence, foregroundSequence;
    private int transitionTargetX, transitionTargetY;
    private FrameLayout pet;
    private PetSpriteView sprite;
    private TextView pendingMarker;
    private LinearLayout bubble;
    private ScrollView bubbleWindow;
    private PetBubbleWindow bubbleHost;
    private View effectWindow;
    private int effectWindowId = -1;
    private boolean interactionMenu;
    private String interactionState = "";
    private String interactionTask = "";
    private boolean interactionTaskActive;
    private long interactionSequence;
    private Runnable finishInteraction;
    private PetBubbleDrawable bubbleBackground;
    private PetBubbleColors bubbleColors;
    private int bubbleMaxWidth;
    private WindowManager.LayoutParams petLayout, bubbleLayout;
    private int petWindowId = -1, bubbleWindowId = -1;
    private Rect safe = new Rect();
    private Rect keyboard = new Rect();
    private int screenWidth, screenHeight;
    private boolean onLeft, docked, dragging, manualBubble, busy;
    private boolean appVisible, chatVisible;
    private String visibleTask = "", dismissedRequest = "", lastAnnouncement = "";
    private String appearance = "";
    private final PetMessages messages = new PetMessages();
    private final PetChatterPolicy chatter = new PetChatterPolicy();
    private boolean stageBubble;
    private boolean problemBubble;
    private String displayedMessage = "", displayedMessageKey = "";
    private String bubbleContent = "";
    private PetQuestionDraft questionDraft;
    private boolean editingAnswer;
    private long lastAnnouncedAt;
    private String proactiveText = "", proactiveTask = "", proactiveId = "";
    private long proactiveUntil;
    private boolean petHidden, bubbleHidden, petTouchPaused;
    private boolean temporarilyMoved;
    private int clearanceDepth, idlePulse;
    private long restoreSequence;
    private JSONObject snapshot = new JSONObject();
    private ValueAnimator movement;
    private boolean registered;
    private final android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener accessibility = enabled ->
        MAIN.post(() -> Choreographer.getInstance().postFrameCallback(frame -> registerWindowIds()));

    static PetOverlay get(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Pet UI requires the main thread");
        if (instance == null) instance = new PetOverlay(context.getApplicationContext());
        return instance;
    }
    static void refresh(Context context) { MAIN.post(() -> get(context).refreshNow()); }
    static void runtimeStopped() { MAIN.post(() -> { if (instance != null) instance.close(); }); }
    static void foreground(Context context, boolean visible) {
        MAIN.post(() -> {
            PetOverlay overlay = get(context);
            if (!(context instanceof Activity activity)) return;
            if (visible) {
                overlay.foregroundSequence++;
                overlay.foregroundActivity = new WeakReference<>(activity); overlay.appVisible = true;
                if (overlay.showingConversation() && !overlay.manualBubble) overlay.collapse();
                overlay.refreshNow();
            } else if (overlay.foregroundActivity.get() == activity) {
                long sequence = ++overlay.foregroundSequence;
                // A sibling Activity resumes in the same navigation; don't create an external window between them.
                MAIN.postDelayed(() -> {
                    if (sequence != overlay.foregroundSequence || overlay.foregroundActivity.get() != activity) return;
                    overlay.foregroundActivity.clear(); overlay.appVisible = false; overlay.refreshNow();
                }, 80);
            }
        });
    }
    static void conversation(Context context, String taskId, boolean visible) {
        MAIN.post(() -> { PetOverlay overlay = get(context); overlay.visibleTask = taskId; overlay.chatVisible = visible;
            if (overlay.showingConversation() && !overlay.manualBubble) overlay.collapse(); overlay.refreshNow(); });
    }

    static JSONObject notificationState(Context context, String taskId) throws Exception {
        return onMain(() -> {
            PetOverlay overlay = get(context);
            JSONObject current = ToolProgress.current(context);
            boolean sameTask = taskId.isEmpty() || taskId.equals(current.optString("taskId"));
            boolean available = RuntimeService.hasActiveSession() && sameTask && overlay.permitted() && !overlay.locked()
                && enabled(context) && !"off".equals(displayMode(context, overlay.appVisible)) && !overlay.showingConversation();
            return new JSONObject().put("petAvailable", available).put("conversationVisible", overlay.showingConversation());
        });
    }

    static boolean notify(Context context, String taskId, String noticeId, String text) throws Exception {
        return notify(context, taskId, noticeId, text, () -> true);
    }

    static boolean notify(Context context, String taskId, String noticeId, String text, java.util.function.BooleanSupplier authorized) throws Exception {
        CompletableFuture<Boolean> shown = new CompletableFuture<>();
        Runnable display = () -> {
            if (shown.isCancelled() || !authorized.getAsBoolean()) { shown.complete(false); return; }
            PetOverlay overlay = get(context);
            try {
            if (!notificationState(context, taskId).optBoolean("petAvailable")) { shown.complete(false); return; }
            if (overlay.busy || overlay.dragging || overlay.holding || overlay.clearanceDepth > 0 || overlay.petHidden) { shown.complete(false); return; }
            overlay.proactiveText = text; overlay.proactiveTask = taskId; overlay.proactiveId = noticeId;
            overlay.proactiveUntil = SystemClock.elapsedRealtime() + PetDisplayPolicy.NOTICE_MS;
            overlay.displayPolicy.announce(noticeId, SystemClock.elapsedRealtime());
            overlay.refreshNow();
            if (overlay.pet == null) { shown.complete(false); return; }
            overlay.stopTransition(true); overlay.showBubble(false);
            View window = overlay.bubbleWindow;
            // The first animation callback precedes layout/draw; wait through a full frame before acknowledging display.
            window.postOnAnimation(() -> window.postOnAnimation(() -> {
                Rect visible = new Rect();
                shown.complete(overlay.bubbleWindow == window && window.isShown() && window.getWidth() > 0
                        && window.getGlobalVisibleRect(visible) && !visible.isEmpty());
            }));
            } catch (Exception failure) { shown.completeExceptionally(failure); }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException(NativeLocale.text("通知显示需要在执行线程等待实际绘制", "Notification display must wait for rendering on a worker thread"));
        MAIN.post(display);
        try { return shown.get(3, java.util.concurrent.TimeUnit.SECONDS); }
        finally {
            if (shown.cancel(false)) MAIN.post(() -> {
                PetOverlay overlay = instance;
                if (overlay != null && noticeId.equals(overlay.proactiveId)) {
                    overlay.proactiveUntil = 0; overlay.collapse(); overlay.refreshNow();
                }
            });
        }
    }

    static void updateNotice(Context context, String noticeId, String text) throws Exception {
        onMain(() -> {
            PetOverlay overlay = get(context);
            if (noticeId.equals(overlay.proactiveId) && SystemClock.elapsedRealtime() < overlay.proactiveUntil) {
                overlay.proactiveText = text;
                if (overlay.bubble != null) overlay.showBubble(overlay.manualBubble);
            }
            return null;
        });
    }

    private static <T> T onMain(java.util.concurrent.Callable<T> work) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return work.call();
        java.util.concurrent.FutureTask<T> result = new java.util.concurrent.FutureTask<>(work);
        MAIN.post(result);
        try { return result.get(3, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception failure) { result.cancel(false); throw failure; }
    }

    private PetOverlay(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE); observedModes = modeKey();
        systemWindows = context.getSystemService(WindowManager.class); windows = systemWindows;
    }
    static String displayMode(Context context, boolean internal) {
        SharedPreferences preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        String value = preferences.getString(internal ? "petInternalDisplay" : "petExternalDisplay",
            PetDisplayPolicy.initial(internal, preferences.getBoolean("petEnabled", true)));
        return PetDisplayPolicy.valid(value) ? value : "off";
    }
    static boolean enabled(Context context) { return context.getSharedPreferences("product", Context.MODE_PRIVATE).getBoolean("petEnabled", true)
        && (!"off".equals(displayMode(context, false)) || !"off".equals(displayMode(context, true))); }
    static String loadError() { return PetPackage.error(); }
    static void select(Context context, String id) throws Exception {
        PetPackage candidate = PetPackage.load(context, id);
        if (!context.getSharedPreferences("product", Context.MODE_PRIVATE).edit().putString("petId", id).commit())
            throw new IllegalStateException(NativeLocale.text("宠物形象未保存，请重试", "Pet appearance was not saved. Please try again"));
        PetPackage.use(candidate);
        MAIN.post(() -> { PetOverlay overlay = get(context); overlay.removeWindows(); overlay.displayedMessageKey = ""; overlay.refreshNow(); });
    }
    static String name(Context context) { return PetMessages.identity(); }
    private Activity activity() {
        Activity activity = foregroundActivity.get();
        return appVisible && activity != null && !activity.isFinishing() && !activity.isDestroyed()
            && activity.getWindow().getDecorView().getWindowToken() != null ? activity : null;
    }
    private boolean permitted() { return appVisible ? activity() != null : Settings.canDrawOverlays(context); }
    private String modeKey() { return preferences.getBoolean("petEnabled", true) + ":" + displayMode(context, false) + ":" + displayMode(context, true); }
    private boolean shouldDisplay() {
        long now = SystemClock.elapsedRealtime();
        boolean preview = now < previewUntil;
        return (enabled(context) || preview) && displayPolicy.visible(displayMode(context, appVisible), snapshot.optBoolean("active"), pending() != null,
            preview, now);
    }
    private boolean locked() { return context.getSystemService(KeyguardManager.class).isKeyguardLocked(); }
    private boolean showingConversation() {
        return appVisible && chatVisible && (snapshot.optString("taskId").isEmpty() || visibleTask.equals(snapshot.optString("taskId")));
    }

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) { removeWindows(); }
            else refreshNow();
        }
    };
    private final ComponentCallbacks configuration = new ComponentCallbacks() {
        @Override public void onConfigurationChanged(android.content.res.Configuration c) {
            cancelInteraction(true);
            updateScreen(); if (pet != null) { applySavedPosition(); if (bubble != null) positionBubble(); }
        }
        @Override public void onLowMemory() {}
    };
    private final Runnable refreshDisplay = this::refreshNow;
    private final Runnable hideMessage = () -> { if (pending() == null) collapse(); };
    private final Runnable dock = () -> { if (pet != null && !dragging && !holding && !entering && !exiting && bubble == null && interactionState.isEmpty()) dockNow(); };
    private final Runnable delayedAnnouncement = this::refreshNow;
    private final Runnable restore = () -> {
        if (clearanceDepth != 0) return;
        petHidden = false; bubbleHidden = false; petTouchPaused = false;
        if (!permitted() || !shouldDisplay()) { refreshNow(); return; }
        applyVisibility();
        refreshNow();
        if (pet != null && bubble == null) { MAIN.removeCallbacks(dock); MAIN.postDelayed(dock, 1200); }
    };
    private final Runnable blink = new Runnable() {
        @Override public void run() {
            if (pet == null) return;
            if (!dragging && !holding && !entering && !exiting && !petHidden && bubble == null && interactionState.isEmpty()) {
                if (docked && !snapshot.optBoolean("active") && ++idlePulse % 3 == 0) {
                    setDocked(false); sprite.showState(sprite.variant("idle", "idle"), true,
                        () -> { if (pet != null && !holding && !dragging && bubble == null) MAIN.postDelayed(dock, 1200); });
                } else if (docked) sprite.showState(onLeft ? "peekLeft" : "peekRight", true);
                else sprite.showVariant(snapshot.optBoolean("active") ? "working" : "idle", snapshot.optBoolean("active") ? "working" : "idle", true);
            }
            MAIN.postDelayed(this, 11000);
        }
    };

    private void refreshNow() {
        snapshot = ToolProgress.current(context);
        if (!snapshot.optBoolean("active")) chatter.reset();
        Activity current = foregroundActivity.get();
        if (current instanceof MainActivity main) main.withdrawResolvedQuestion();
        if (pending() == null) { dismissedRequest = ""; questionDraft = null; }
        else if (showingConversation()) dismissedRequest = pending().optString("requestId");
        String modes = modeKey();
        if (!modes.equals(observedModes)) { previewUntil = 0; observedModes = modes; }
        if (!RuntimeService.hasActiveSession()) { close(); return; }
        long now = SystemClock.elapsedRealtime();
        boolean problem = "blocked".equals(snapshot.optString("phase")) || "error".equals(snapshot.optString("summaryKind"));
        String stage = snapshot.optBoolean("active") && pending() == null && !problem
            ? snapshot.optString("activityKind") : "";
        if (now >= proactiveUntil) { proactiveText = ""; proactiveTask = ""; proactiveId = ""; }
        String notice = snapshot.optString("taskId") + ":" + snapshot.optString("phase") + ":" + snapshot.optLong("startedAt") + ":" + summary();
        displayPolicy.observe(notice, snapshot.optBoolean("active"), pending() != null, !summary().isBlank(), showingConversation(), now);
        if (problem && !summary().isBlank() && !showingConversation())
            displayPolicy.announce(notice, now);
        MAIN.removeCallbacks(refreshDisplay);
        long remaining = Math.max(displayPolicy.noticeRemaining(now), Math.max(0, previewUntil - now));
        if (remaining > 0) MAIN.postDelayed(refreshDisplay, remaining + 1);
        if (!registered) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(screen, filter);
            context.registerComponentCallbacks(configuration); registered = true;
            context.getSystemService(android.view.accessibility.AccessibilityManager.class).addAccessibilityStateChangeListener(accessibility);
            OverlayInteraction.addListener(this);
        }
        if (locked()) { removeWindows(); return; }
        boolean previewing = now < previewUntil;
        if (!permitted() || !previewing && (!enabled(context) || "off".equals(displayMode(context, appVisible)))) {
            removeWindows();
            JSONObject question = pending();
            if (question != null && !showingConversation() && clearanceDepth == 0 && !petHidden && !bubbleHidden && !busy) {
                String request = question.optString("requestId"), task = snapshot.optString("taskId");
                String key = task + ":" + request;
                if (!key.equals(lastAnnouncement) && ToolProgress.hasPending(task, request)) {
                    lastAnnouncement = key;
                    // The quiet service notification remains usable if Android declines this launch.
                    try { MainActivity.revealQuestion(context, task, request); }
                    catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
                        android.util.Log.w("Droy", "Question navigation unavailable");
                    }
                }
            }
            return;
        }
        if (!shouldDisplay()) {
            if (pet != null && "adaptive".equals(displayMode(context, appVisible)) && !petHidden && clearanceDepth == 0) leave();
            else removeWindows();
            return;
        }
        Activity host = activity();
        android.os.IBinder hostToken = host == null ? null : host.getWindow().getDecorView().getWindowToken();
        if (pet != null && (internalWindow != (host != null) || internalWindow && attachedToken != hostToken)) removeWindows();
        if (exiting) stopTransition(true);
        if (clearanceDepth > 0 && pet == null) return;
        if (pet == null) {
            internalWindow = host != null; attachedToken = hostToken; windows = internalWindow ? host.getWindowManager() : systemWindows;
            addPet(); enter(now - lastEntryAt > 3000); lastEntryAt = now;
        }
        pet.setContentDescription(name(context) + NativeLocale.text("，Droy 宠物。单击播放动作，双击查看任务，拖动调整位置；有待确认问题时单击处理", ", Droy pet. Tap to animate, double-tap to view the task, or drag to move. Tap to respond to a pending question"));
        pendingMarker.setVisibility(pending() != null ? View.VISIBLE : View.GONE);
        pendingMarker.setContentDescription(NativeLocale.text("有问题等你回应", "A question needs your response"));
        if (!interactionState.isEmpty()) {
            if (problem || PetInteractionActions.interrupted(interactionTaskActive, interactionTask, snapshot.optString("taskId"),
                    snapshot.optString("phase"), pending() != null)) cancelInteraction(true);
            else return;
        }
        if (busy || dragging || holding || clearanceDepth > 0 || petHidden || bubbleHidden) return;
        // Commit a buffered stage only when its window can be presented. Capture,
        // dragging, and a slow answer request must not consume an unseen message.
        String announcement = !manualBubble && !showingConversation()
            ? chatter.observe(snapshot.optBoolean("active") ? snapshot.optString("taskId") : "", stage, now) : "";
        MAIN.removeCallbacks(delayedAnnouncement);
        if (chatter.remaining(now) > 0) MAIN.postDelayed(delayedAnnouncement, chatter.remaining(now) + 1);
        if (temporarilyMoved && !snapshot.optBoolean("active")) {
            temporarilyMoved = false; onLeft = preferences.getBoolean("petLeft", false);
            applySavedPosition(); setDocked(docked); if (bubble != null) positionBubble();
        }
        JSONObject pending = pending();
        if (entering && pending == null && announcement.isEmpty() && !snapshot.optString("phase").equals("blocked")) return;
        if (pending == null && bubble != null && bubble.getTag() != null) collapse();
        String nextAppearance = NativeLocale.active() + ":" + name(context) + ":" + preferences.getString("appearanceMode", "system")
            + ":" + preferences.getString("themePalette", "purple") + ":" + preferences.getString("colorSource", "preset")
            + ":" + context.getResources().getConfiguration().uiMode;
        if (!appearance.equals(nextAppearance)) {
            appearance = nextAppearance;
            if (bubble != null) showBubble(manualBubble);
        }
        if (problemBubble && pending == null && !problem
                && !"slow".equals(snapshot.optString("summaryKind"))) collapse();
        if (bubble != null && manualBubble && !interactionMenu && !bubbleContent.equals(displayedSummary()) && (pending != null || stage.isEmpty())) showBubble(true);
        if (stage.isEmpty() && stageBubble && pending == null) {
            collapse();
        }
        if (pending != null && !showingConversation() && !pending.optString("requestId").equals(dismissedRequest)
                && (bubble == null || !pending.optString("requestId").equals(bubble.getTag()))) {
            proactiveUntil = 0;
            stopTransition(true); showBubble(false);
        } else if (!announcement.isEmpty() && !showingConversation() && !manualBubble) {
            proactiveUntil = 0; stopTransition(true); showBubble(false); stageBubble = true;
        } else if (problem && !showingConversation()) {
            String key = snapshot.optString("taskId") + ":problem:" + snapshot.optLong("startedAt") + ":" + summary();
            if (!key.equals(lastAnnouncement)) { lastAnnouncement = key; proactiveUntil = 0; stopTransition(true); showBubble(true); problemBubble = true; }
        } else if (stage.isEmpty() && "slow".equals(snapshot.optString("summaryKind")) && !showingConversation()) {
            String key = snapshot.optString("taskId") + ":slow:" + snapshot.optLong("startedAt");
            if (!key.equals(lastAnnouncement)) { lastAnnouncement = key; proactiveUntil = 0; showBubble(true); problemBubble = true; }
        }
    }

    void preview() {
        observedModes = modeKey();
        previewUntil = SystemClock.elapsedRealtime() + 8000;
        refreshNow();
        if (pet == null) throw new IllegalStateException(NativeLocale.text("请在 Droy 页面中预览，或允许在其他应用上显示", "Preview in Droy, or allow display over other apps"));
        displayedMessageKey = "";
        emerge(); bounce(); showBubble(true);
    }

    private void addPet() {
        updateScreen();
        pet = new FrameLayout(context); pet.setTag("droy-pet");
        sprite = new PetSpriteView(context); messages.usePersona(sprite.persona());
        pet.addView(sprite, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        pendingMarker = new TextView(context); pendingMarker.setText("?"); pendingMarker.setTextSize(21); pendingMarker.setTextColor(0xFF272A31);
        pendingMarker.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        pendingMarker.setRotation(13); pendingMarker.setElevation(dp(2));
        GradientDrawable markerShape = new GradientDrawable(); markerShape.setColor(0xFFFFE797);
        markerShape.setCornerRadii(new float[]{dp(10),dp(10),dp(10),dp(10),dp(10),dp(10),dp(2),dp(2)});
        markerShape.setStroke(dp(1), 0xFF3D3430); pendingMarker.setBackground(markerShape);
        pendingMarker.setGravity(Gravity.CENTER); pendingMarker.setContentDescription(NativeLocale.text("有问题等你回应", "A question needs your response"));
        FrameLayout.LayoutParams markerLayout = new FrameLayout.LayoutParams(dp(24), dp(28), Gravity.TOP | Gravity.RIGHT);
        pet.addView(pendingMarker, markerLayout); pendingMarker.setVisibility(View.GONE);
        pet.setClickable(true); pet.setFocusable(false);
        pet.setOnClickListener(view -> {
            stopTransition(true); emerge(); bounce();
            if (pending() != null) toggleBubble();
            else sprite.showVariant("tap", "tap", true);
        });
        androidx.core.view.ViewCompat.addAccessibilityAction(pet, NativeLocale.text("打开任务面板", "Open task panel"),
            (view, arguments) -> { toggleBubble(); return true; });
        final FrameLayout gesturePet = pet;
        final GestureDetector taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent event) { gesturePet.performClick(); return true; }
            @Override public boolean onDoubleTap(MotionEvent event) {
                if (pending() != null) { stopTransition(true); emerge(); showBubble(true); }
                else toggleBubble();
                return true;
            }
        });
        taps.setIsLongpressEnabled(false);
        pet.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY; int originX, originY;
            final Runnable longPress = () -> {
                if (pet != gesturePet || dragging) return;
                holding = true; removeBubble(); emerge();
                pet.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                sprite.showVariant("pet", "tap", true);
            };
            @Override public boolean onTouch(View view, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        cancelInteraction(true);
                        downX = event.getRawX(); downY = event.getRawY(); originX = petLayout.x; originY = petLayout.y;
                        dragging = holding = false; MAIN.removeCallbacks(dock); stopTransition(true); cancelMovement();
                        originX = petLayout.x; originY = petLayout.y;
                        MAIN.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                        taps.onTouchEvent(event); return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > ViewConfiguration.get(context).getScaledTouchSlop()) {
                            MAIN.removeCallbacks(longPress); holding = false;
                            dragging = true; temporarilyMoved = false; removeBubble(); setDocked(false); sprite.showState("working", false);
                        }
                        if (dragging) {
                            petLayout.x = PetPosition.clamp(originX + Math.round(dx), safe.left, safe.right - petLayout.width);
                            petLayout.y = PetPosition.clamp(originY + Math.round(dy), safe.top, usableBottom() - petLayout.height);
                            sprite.setFacingLeft(dx < 0); sprite.setRotation(Math.max(-8f, Math.min(8f, dx / dp(12)))); updatePet();
                        }
                        taps.onTouchEvent(event);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        MAIN.removeCallbacks(longPress);
                        sprite.animate().rotation(0).setDuration(160).start();
                        boolean movedOrHeld = dragging || holding;
                        if (dragging) {
                            // Android may resample MOVE beyond the final touch point.
                            // Persist the release coordinate so the cat stays under the finger.
                            if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                                petLayout.x = PetPosition.clamp(originX + Math.round(event.getRawX() - downX), safe.left, safe.right - petLayout.width);
                                petLayout.y = PetPosition.clamp(originY + Math.round(event.getRawY() - downY), safe.top, usableBottom() - petLayout.height);
                                updatePet();
                            }
                            dragging = false; onLeft = petLayout.x + petLayout.width / 2 < screenWidth / 2;
                            sprite.setFacingLeft(!onLeft); savePosition();
                            int edge = onLeft ? safe.left : safe.right - petLayout.width;
                            if (Math.abs(petLayout.x - edge) < dp(28)) moveTo(edge, petLayout.y, 180);
                            MAIN.postDelayed(dock, 8000);
                        } else if (!holding && event.getActionMasked() == android.view.MotionEvent.ACTION_UP) taps.onTouchEvent(event);
                        else MAIN.postDelayed(dock, 4000);
                        if (movedOrHeld || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                            MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL);
                            taps.onTouchEvent(cancel); cancel.recycle();
                        }
                        holding = false; MAIN.postDelayed(refreshDisplay, 1200); return true;
                    default: return false;
                }
            }
        });
        petLayout = layout(dp(72), dp(72), "Droy pet");
        // Entry/exit deliberately cross the display edge; ordinary layout clamps them on-screen.
        petLayout.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        temporarilyMoved = false;
        onLeft = preferences.getBoolean("petLeft", false); docked = true;
        applySavedPosition();
        windows.addView(pet, petLayout);
        petWindowId = windowId(pet); OverlayInteraction.registerWindow(petWindowId);
        pet.post(() -> { if (pet != null) { petWindowId = windowId(pet); OverlayInteraction.registerWindow(petWindowId); } });
        setDocked(true);
        pet.setOnApplyWindowInsetsListener((view, insets) -> {
            updateKeyboardInsets(view, insets); return insets;
        });
        pet.requestApplyInsets(); MAIN.postDelayed(blink, 11000); applyVisibility();
    }

    private WindowManager.LayoutParams layout(int width, int height, String title) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(width, height, internalWindow ? WindowManager.LayoutParams.TYPE_APPLICATION : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        // The Activity WindowManager supplies its application token for this top-level
        // window; a decor-view token would only be valid for a child panel.
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.windowAnimations = 0;
        // WindowManager's independent move animation keeps its old surface/input
        // transform briefly; the pet already owns its movement animation.
        if (Build.VERSION.SDK_INT >= 34) p.setCanPlayMoveAnimation(false);
        p.setTitle(title); p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        return p;
    }
    private void updateScreen() {
        Rect bounds = windows.getMaximumWindowMetrics().getBounds(); screenWidth = bounds.width(); screenHeight = bounds.height();
        Insets bars = windows.getMaximumWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        safe.set(Math.max(dp(8), bars.left), Math.max(dp(8), bars.top), screenWidth - Math.max(dp(8), bars.right), screenHeight - Math.max(dp(8), bars.bottom));
    }
    private int usableBottom() { return keyboard.isEmpty() ? safe.bottom : Math.min(safe.bottom, keyboard.top - dp(8)); }
    private void applySavedPosition() {
        if (petLayout == null) return;
        float x = preferences.getFloat("petX", onLeft ? 0f : 1f);
        petLayout.x = safe.left + Math.round(Math.max(0f, Math.min(1f, x)) * Math.max(0, safe.width() - petLayout.width));
        petLayout.y = PetPosition.clamp(PetPosition.restoreY(preferences.getFloat("petY", .62f), safe.top, safe.bottom, petLayout.height),
            safe.top, usableBottom() - petLayout.height);
        updatePet();
    }
    private void savePosition() {
        savePosition(petLayout.x, petLayout.y);
    }
    private void savePosition(int x, int y) {
        if (temporarilyMoved) return;
        preferences.edit().putBoolean("petLeft", onLeft)
            .putFloat("petX", Math.max(0f, Math.min(1f, (float)(x - safe.left) / Math.max(1, safe.width() - petLayout.width))))
            .putFloat("petY", PetPosition.fraction(y, safe.top, safe.bottom, petLayout.height)).apply();
    }
    private void updatePet() { if (pet != null && pet.isAttachedToWindow()) windows.updateViewLayout(pet, petLayout); }
    private void setDocked(boolean value) {
        docked = value; if (sprite == null) return;
        sprite.setFacingLeft(!onLeft);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) sprite.getLayoutParams();
        p.width = p.height = dp(value ? 36 : 56); p.gravity = value ? (Gravity.CENTER_VERTICAL | (onLeft ? Gravity.LEFT : Gravity.RIGHT)) : Gravity.CENTER;
        sprite.setLayoutParams(p);
        sprite.showState(value ? (onLeft ? "peekLeft" : "peekRight") : animation(), false);
    }
    private void emerge() {
        MAIN.removeCallbacks(dock); stopTransition(true); cancelMovement();
        setDocked(false);
    }
    private void dockNow() {
        onLeft = petLayout.x + petLayout.width / 2 < screenWidth / 2;
        moveTo(onLeft ? safe.left : safe.right - petLayout.width, petLayout.y, 320);
        setDocked(true);
    }
    private void enter(boolean full) {
        if (pet == null) return;
        if (!full || !ValueAnimator.areAnimatorsEnabled() || pending() != null) { setDocked(false); MAIN.postDelayed(dock, 4000); return; }
        playEntry(sprite.variant("enter", "working"));
    }
    private void playEntry(String state) {
        stopTransition(true); MAIN.removeCallbacks(dock);
        boolean peek = state.toLowerCase(java.util.Locale.ROOT).contains("peek");
        setDocked(peek);
        if (peek) sprite.showQuickState(onLeft ? "peekLeft" : "peekRight", 500); else sprite.showState(state, true);
        transitionTargetX = petLayout.x; transitionTargetY = petLayout.y;
        int outside = onLeft ? -petLayout.width : screenWidth;
        int peekEdge = onLeft ? safe.left : safe.right - petLayout.width;
        boolean jump = state.toLowerCase(java.util.Locale.ROOT).contains("jump");
        long duration = Math.max(450, Math.min(1400, sprite.duration(state))) + (peek ? 500 : 0);
        float introFraction = peek ? 500f / duration : 0;
        entering = true; long sequence = ++transitionSequence;
        transition = ValueAnimator.ofFloat(0, 1); transition.setDuration(duration);
        transition.setInterpolator(new android.view.animation.LinearInterpolator());
        transition.addUpdateListener(value -> {
            if (pet == null || transitionSequence != sequence) return;
            float t = (float)value.getAnimatedValue();
            if (peek && t >= introFraction && docked) { setDocked(false); sprite.showState(state, true); }
            if (peek && t < introFraction) {
                float exposure = Math.min(1f, t * duration / 150f);
                petLayout.x = outside + Math.round((peekEdge - outside) * exposure);
            } else if (peek) {
                float progress = (t - introFraction) / (1 - introFraction);
                petLayout.x = peekEdge + Math.round((transitionTargetX - peekEdge) * progress);
            } else petLayout.x = outside + Math.round((transitionTargetX - outside) * t);
            if (jump) sprite.setTranslationY(-dp(6) * (float)Math.sin(Math.PI * t));
            updatePet();
        });
        transition.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (cancelled || pet == null || sequence != transitionSequence) return;
                entering = false; transition = null; petLayout.x = transitionTargetX; petLayout.y = transitionTargetY;
                sprite.setTranslationY(0); updatePet(); refreshNow();
                if (bubble == null) MAIN.postDelayed(dock, 4000);
            }
        });
        transition.start();
    }
    private void leave() {
        if (exiting || pet == null) return;
        if (!ValueAnimator.areAnimatorsEnabled()) { removeWindows(); return; }
        playExit(sprite.variant("exit", "idle"));
    }
    private void playExit(String state) {
        cancelInteraction(false);
        stopTransition(true); removeBubble(); MAIN.removeCallbacks(dock);
        setDocked(false); sprite.setFacingLeft(onLeft); sprite.showState(state, true);
        transitionTargetX = petLayout.x; transitionTargetY = petLayout.y;
        int start = petLayout.x, outside = onLeft ? -petLayout.width : screenWidth;
        exiting = true; long sequence = ++transitionSequence;
        transition = ValueAnimator.ofFloat(0, 1); transition.setDuration(Math.max(350, Math.min(1000, sprite.duration(state))));
        transition.addUpdateListener(value -> {
            if (pet == null || sequence != transitionSequence) return;
            float t = (float)value.getAnimatedValue();
            petLayout.x = start + Math.round((outside - start) * t); updatePet();
        });
        transition.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (!cancelled && sequence == transitionSequence) { exiting = false; transition = null; removeWindows(); }
            }
        });
        transition.start();
    }
    private void stopTransition(boolean settle) {
        if (!entering && !exiting && transition == null) return;
        transitionSequence++;
        if (transition != null) { transition.cancel(); transition = null; }
        entering = exiting = false;
        if (pet != null && settle) { petLayout.x = transitionTargetX; petLayout.y = transitionTargetY; sprite.setTranslationY(0); updatePet(); }
    }
    private void moveTo(int x, int y, int duration) {
        cancelMovement();
        savePosition(x, y);
        if (!ValueAnimator.areAnimatorsEnabled()) { petLayout.x = x; petLayout.y = y; updatePet(); savePosition(); return; }
        int fromX = petLayout.x, fromY = petLayout.y;
        movement = ValueAnimator.ofFloat(0, 1); movement.setDuration(duration); movement.setInterpolator(new DecelerateInterpolator());
        movement.addUpdateListener(a -> { if (pet == null) return; float t = (float)a.getAnimatedValue();
            petLayout.x = fromX + Math.round((x - fromX) * t); petLayout.y = fromY + Math.round((y - fromY) * t); updatePet(); });
        movement.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) { if (!cancelled && pet != null) savePosition(); }
        });
        movement.start();
    }
    private void cancelMovement() { if (movement != null) { movement.cancel(); movement = null; } }
    private void bounce() {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        sprite.animate().cancel(); sprite.setScaleX(1.08f); sprite.setScaleY(.92f);
        sprite.animate().scaleX(1).scaleY(1).setDuration(220).start();
    }

    private void toggleBubble() {
        stopTransition(true); emerge(); bounce();
        if (bubble != null && (manualBubble || pending() != null)) {
            JSONObject question = pending();
            if (question != null) dismissedRequest = question.optString("requestId");
            collapse();
        } else { displayedMessageKey = ""; showBubble(true); }
    }

    private JSONObject pending() { return snapshot.optJSONObject("pending"); }
    private boolean waitingProgress() { return snapshot.optBoolean("active") && snapshot.optString("phase").equals("waiting"); }
    private String summary() {
        JSONObject p = pending();
        if (p != null) return p.optString("message", NativeLocale.text("有一个问题需要你处理", "A question needs your attention"));
        return snapshot.optString("summary");
    }
    private boolean workingSummary() { return snapshot.optBoolean("active") && "working".equals(snapshot.optString("summaryKind")); }
    private String displayedSummary() {
        if (pending() != null || "slow".equals(snapshot.optString("summaryKind")) && snapshot.optString("activityKind").isEmpty()) return summary();
        if (!proactiveText.isEmpty() && SystemClock.elapsedRealtime() < proactiveUntil) return proactiveText;
        boolean working = workingSummary() || "slow".equals(snapshot.optString("summaryKind")) && !snapshot.optString("activityKind").isEmpty();
        String stage = chatter.current();
        String raw = summary();
        if (!working && !raw.isBlank()) return raw;
        String key = NativeLocale.active() + ":" + name(context) + ":" + (working ? snapshot.optString("taskId") + ":working:" + stage : "idle");
        if (!key.equals(displayedMessageKey)) {
            displayedMessageKey = key;
            displayedMessage = working ? messages.activity(stage, name(context)) : messages.idle(name(context));
        }
        return displayedMessage;
    }
    private String animation() {
        return PetInteractionActions.restingState(snapshot.optString("phase"), pending() != null,
            state -> sprite != null && sprite.hasState(state));
    }
    private void showBubble(boolean manual) {
        if (pet == null) return;
        cancelInteraction(false);
        removeBubble(); emerge(); manualBubble = manual;
        JSONObject p = pending();
        bubbleColors = PetBubbleColors.from(context);
        boolean compact = !manual && p == null && !"slow".equals(snapshot.optString("summaryKind"));
        bubble = new LinearLayout(context); bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(compact ? 10 : 12), dp(compact ? 7 : 10), dp(compact ? 10 : 12), dp(compact ? 7 : 10));
        bubble.setTag(p == null ? null : p.optString("requestId"));
        if (manual || p != null || "slow".equals(snapshot.optString("summaryKind"))) {
            TextView title = text(name(context) + (p != null ? NativeLocale.text(" · 等你回应", " · Your response needed") : waitingProgress() ? NativeLocale.text(" · 等待中", " · Waiting") : snapshot.optBoolean("active") ? NativeLocale.text(" · 工作中", " · Working") : ""), 12, bubbleColors.onSurfaceVariant);
            title.setTypeface(null, android.graphics.Typeface.BOLD); title.setAccessibilityHeading(true);
            title.setPadding(0, 0, 0, dp(5)); bubble.addView(title);
        }
        String message = displayedSummary();
        bubbleContent = message;
        TextView content = text(message, compact ? 12 : 13, bubbleColors.onSurface);
        if (compact) { content.setMaxLines(2); content.setEllipsize(android.text.TextUtils.TruncateAt.END); }
        content.setLineSpacing(dp(2), 1f);
        JSONArray questions = p == null ? null : p.optJSONArray("questions");
        if (questions == null || questions.length() == 0) bubble.addView(content);
        String task = p == null && !"slow".equals(snapshot.optString("summaryKind")) && !proactiveTask.isEmpty() && SystemClock.elapsedRealtime() < proactiveUntil
            ? proactiveTask : snapshot.optString("taskId");
        if (p != null && questions != null && questions.length() > 0) {
            addQuestionForm(task, p, questions);
        } else if (p != null && p.optBoolean("inline")) {
            JSONArray options = p.optJSONArray("options");
            if (options != null) for (int i = 0; i < options.length(); i++) {
                JSONObject option = options.optJSONObject(i); if (option == null) continue;
                String requestId = p.optString("requestId"), optionId = option.optString("id");
                bubble.addView(button(option.optString("label"), () -> action(task, "answer", requestId, optionId)));
            }
        }
        if (manual || p != null || "slow".equals(snapshot.optString("summaryKind"))) {
            LinearLayout actions = new LinearLayout(context); actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            actions.setPadding(0, dp(4), 0, 0);
            Button open = button(p != null && !p.optBoolean("inline") ? NativeLocale.text("查看并处理", "View and respond") : NativeLocale.text("查看任务", "View task"), () -> openTask(task));
            styleButton(open, bubbleColors.primary, bubbleColors.onPrimary);
            actions.addView(open, new LinearLayout.LayoutParams(-2, dp(48)));
            if (!task.isEmpty() && snapshot.optBoolean("active")) {
                Button stop = button(NativeLocale.text("停止任务", "Stop task"), () -> action(task, "cancel", null, null));
                styleButton(stop, bubbleColors.errorContainer, bubbleColors.onErrorContainer);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(48)); lp.leftMargin = dp(4); actions.addView(stop, lp);
            }
            if (manual && p == null && !busy && !sprite.interactions().isEmpty()) {
                Button play = button(NativeLocale.text("互动动作", "Play actions"), this::showInteractions);
                styleButton(play, Color.TRANSPARENT, bubbleColors.primary);
                play.setContentDescription(NativeLocale.text("选择宠物互动动作", "Choose a companion play action"));
                if (!snapshot.optBoolean("active")) actions.addView(play, 0, new LinearLayout.LayoutParams(-2, dp(48)));
                else bubble.addView(play);
            }
            int available = Math.min(dp(p != null ? 244 : 220), safe.width()) - dp(30);
            int actionWidth = 0;
            for (int index = 0; index < actions.getChildCount(); index++) {
                View child = actions.getChildAt(index);
                child.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                actionWidth += child.getMeasuredWidth() + dp(4);
            }
            if (actionWidth > available) {
                actions.setOrientation(LinearLayout.VERTICAL);
                for (int index = 0; index < actions.getChildCount(); index++)
                    actions.getChildAt(index).setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
            }
            bubble.addView(actions);
        } else {
            bubble.setOnClickListener(v -> { bounce(); showBubble(true); }); bubble.setContentDescription(message + NativeLocale.text("，点击展开任务操作", ". Tap to expand task actions"));
        }
        bubbleWindow = new ScrollView(context); bubbleWindow.setFillViewport(false); bubbleWindow.addView(bubble);
        bubbleWindow.setClipToPadding(true); bubbleWindow.setVerticalScrollBarEnabled(false);
        bubbleBackground = new PetBubbleDrawable(context.getResources().getDisplayMetrics().density, bubbleColors.surface, bubbleColors.outline);
        bubbleMaxWidth = Math.min(dp(questions != null && questions.length() > 0 ? 288 : p != null ? 244 : compact ? 172 : 220), safe.width());
        bubbleLayout = layout(bubbleMaxWidth, WindowManager.LayoutParams.WRAP_CONTENT, "Droy pet message");
        bubbleWindow.setOnApplyWindowInsetsListener((view, insets) -> {
            updateKeyboardInsets(view, insets); return insets;
        });
        bubbleWindow.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { releaseAnswerFocus(); return true; }
            return false;
        });
        bubbleWindow.setFocusableInTouchMode(true);
        bubbleWindow.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        bubbleWindow.requestFocus();
        ScrollView answerWindow = bubbleWindow;
        answerWindow.getViewTreeObserver().addOnWindowFocusChangeListener(focused -> {
            if (focused && editingAnswer && bubbleWindow == answerWindow && answerWindow.findFocus() instanceof EditText input)
                showAnswerKeyboard(input);
        });
        bubbleWindow.setOnKeyListener((view, key, event) -> {
            if (key != KeyEvent.KEYCODE_BACK || event.getAction() != KeyEvent.ACTION_UP) return false;
            releaseAnswerFocus(); return true;
        });
        positionBubble();
        bubbleHost = new PetBubbleWindow(internalWindow ? activity() : context, bubbleWindow, bubbleBackground, bubbleLayout, this::releaseAnswerFocus);
        bubbleHost.show();
        bubbleWindowId = windowId(bubbleWindow); OverlayInteraction.registerWindow(bubbleWindowId);
        bubbleWindow.post(() -> { if (bubbleWindow != null) { bubbleWindowId = windowId(bubbleWindow); OverlayInteraction.registerWindow(bubbleWindowId); positionBubble(); } });
        if (manual) sprite.showVariant("tap", "tap", true);
        else sprite.showState(p != null || waitingProgress() ? "waiting" : snapshot.optString("phase").equals("completed") || snapshot.optString("phase").equals("interrupted") ? animation() : "speaking", true);
        applyVisibility();
        if (p == null && !snapshot.optString("phase").equals("blocked") && !waitingProgress() && !"slow".equals(snapshot.optString("summaryKind"))) MAIN.postDelayed(hideMessage, manual ? 8000 : 4000);
    }
    private void addQuestionForm(String task, JSONObject pending, JSONArray questions) {
        String draftKey = task + ":" + pending.optString("requestId");
        if (questionDraft == null || !draftKey.equals(questionDraft.key)) questionDraft = new PetQuestionDraft(draftKey, questions);
        PetQuestionDraft draft = questionDraft;
        for (int index = 0; index < draft.answers.size(); index++) {
            PetQuestionDraft.Answer answer = draft.answers.get(index);
            JSONObject question = answer.question;
            if (!question.optString("header").isEmpty()) {
                TextView heading = text(question.optString("header"), 12, bubbleColors.onSurfaceVariant);
                heading.setPadding(0, dp(index == 0 ? 0 : 12), 0, dp(4)); bubble.addView(heading);
            }
            TextView questionText = text(question.optString("question"), 14, bubbleColors.onSurface);
            questionText.setPadding(0, dp(index == 0 ? 0 : 8), 0, dp(6));
            questionText.setLineSpacing(dp(2), 1f); bubble.addView(questionText);
            if (!question.optString("detail").isEmpty()) {
                TextView detail = text(question.optString("detail"), 12, bubbleColors.onSurfaceVariant);
                detail.setTextIsSelectable(true); detail.setPadding(0, 0, 0, dp(6)); bubble.addView(detail);
            }
            JSONArray options = question.optJSONArray("options");
            java.util.List<CompoundButton> choices = new java.util.ArrayList<>();
            EditText custom = new EditText(context);
            custom.setTextSize(14); custom.setTextColor(bubbleColors.onSurface); custom.setHintTextColor(bubbleColors.onSurfaceVariant);
            custom.setHint(options != null && options.length() > 0 ? NativeLocale.text("填写其他回答", "Write another answer") : NativeLocale.text("填写你的回答", "Write your answer"));
            custom.setContentDescription(question.optString("question") + NativeLocale.text("，手动填写回答", ". Write an answer"));
            custom.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            custom.setMinLines(1); custom.setMaxLines(4); custom.setGravity(Gravity.TOP | Gravity.LEFT);
            custom.setPadding(dp(9), dp(10), dp(9), dp(10)); custom.setMinHeight(dp(48));
            custom.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bubbleColors.primary));
            custom.setText(answer.custom);
            boolean[] syncing = {false};
            Button[] skipControl = new Button[1];
            Runnable updateChoices = () -> {
                syncing[0] = true;
                for (CompoundButton choice : choices) choice.setChecked(answer.selected.contains((String)choice.getTag()));
                if (!custom.getText().toString().equals(answer.custom)) custom.setText(answer.custom);
                if (skipControl[0] != null) skipControl[0].setText(answer.skipped
                    ? NativeLocale.text("暂不回答，可重新填写", "Left unanswered. You can still answer") : NativeLocale.text("暂不回答", "Leave unanswered"));
                syncing[0] = false;
            };
            if (options != null) for (int optionIndex = 0; optionIndex < options.length(); optionIndex++) {
                JSONObject option = options.optJSONObject(optionIndex);
                if (option == null) continue;
                String label = option.optString("label"), description = option.optString("description");
                CompoundButton choice = answer.multiple() ? new CheckBox(context) : new RadioButton(context);
                choice.setTag(label); choice.setText(description.isEmpty() ? label : label + "\n" + description);
                choice.setTextSize(14); choice.setTextColor(bubbleColors.onSurface); choice.setGravity(Gravity.CENTER_VERTICAL);
                choice.setSingleLine(false); choice.setEllipsize(null);
                choice.setButtonTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{bubbleColors.primary, bubbleColors.onSurfaceVariant}));
                choice.setPadding(dp(2), dp(7), dp(4), dp(7)); choice.setMinHeight(dp(48));
                choice.setChecked(answer.selected.contains(label));
                choice.setOnClickListener(view -> { answer.choose(label, answer.multiple() ? choice.isChecked() : true); updateChoices.run(); });
                choices.add(choice); bubble.addView(choice, new LinearLayout.LayoutParams(-1, -2));
            }
            custom.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                    if (syncing[0]) return;
                    answer.type(value.toString()); updateChoices.run();
                    if (bubbleWindow != null) bubbleWindow.post(PetOverlay.this::positionBubble);
                }
                @Override public void afterTextChanged(android.text.Editable value) {}
            });
            custom.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) focusAnswer(custom);
                return false;
            });
            custom.setOnFocusChangeListener((view, focused) -> { if (focused) focusAnswer(custom); });
            if (question.optBoolean("allowCustom", true)) bubble.addView(custom, new LinearLayout.LayoutParams(-1, -2));
            // Generic skip labels can be mistaken for dismissible ads by accessibility helpers.
            Button skip = button(NativeLocale.text("暂不回答", "Leave unanswered"), () -> { answer.skip(); updateChoices.run(); });
            skipControl[0] = skip; updateChoices.run();
            styleButton(skip, Color.TRANSPARENT, bubbleColors.primary); bubble.addView(skip);
        }
        TextView error = text(NativeLocale.text("请回答或跳过每个问题", "Answer or skip each question"), 12, bubbleColors.error);
        error.setPadding(0, dp(7), 0, dp(3)); error.setVisibility(View.GONE); bubble.addView(error);
        Button submit = button(NativeLocale.text("发送回答", "Send answer"), () -> {
            if (!draft.complete()) {
                error.setVisibility(View.VISIBLE); positionBubble();
                error.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT); return;
            }
            try { action(task, "answer", pending.optString("requestId"), null, draft.encode()); }
            catch (org.json.JSONException invalid) { throw new IllegalStateException(invalid); }
        });
        styleButton(submit, bubbleColors.primary, bubbleColors.onPrimary);
        bubble.addView(submit);
    }
    private void focusAnswer(EditText input) {
        if (bubbleWindow == null || bubbleLayout == null) return;
        if (!editingAnswer) {
            editingAnswer = true;
            bubbleLayout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            bubbleLayout.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
            bubbleHost.update(bubbleLayout);
        }
        if (!input.hasFocus()) input.requestFocus();
        input.post(() -> showAnswerKeyboard(input));
    }
    private void showAnswerKeyboard(EditText input) {
        if (!editingAnswer || !input.isAttachedToWindow() || !input.hasWindowFocus()) return;
        context.getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }
    private void releaseAnswerFocus() {
        if (!editingAnswer) return;
        editingAnswer = false;
        if (bubbleWindow == null) return;
        context.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(bubbleWindow.getWindowToken(), 0);
        bubbleWindow.clearFocus();
        bubbleLayout.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        bubbleLayout.flags &= ~WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        if (bubbleHost != null) bubbleHost.update(bubbleLayout);
    }
    private TextView text(String value, int sp, int color) {
        TextView t = new TextView(context); t.setText(value); t.setTextSize(sp); t.setTextColor(color); t.setIncludeFontPadding(false); return t;
    }
    private Button button(String label, Runnable action) {
        Button button = new Button(context); button.setText(label); button.setAllCaps(false); button.setTextSize(12);
        styleButton(button, bubbleColors.secondaryContainer, bubbleColors.onSecondaryContainer);
        button.setStateListAnimator(null); button.setElevation(0);
        button.setPadding(dp(10), dp(4), dp(10), dp(4)); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setMinWidth(0); button.setMinimumWidth(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(4); button.setLayoutParams(lp);
        button.setOnClickListener(v -> action.run()); return button;
    }
    private void styleButton(Button button, int background, int foreground) {
        GradientDrawable shape = new GradientDrawable(); shape.setCornerRadius(dp(16)); shape.setColor(background);
        // The visible pill is compact; its transparent inset retains a 48dp touch target.
        android.graphics.drawable.InsetDrawable inset = new android.graphics.drawable.InsetDrawable(shape, 0, dp(8), 0, dp(8));
        int ripple = (foreground & 0x00FFFFFF) | 0x20000000;
        button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ripple), inset, null));
        button.setTextColor(foreground);
    }
    private void positionBubble() {
        if (bubbleWindow == null || petLayout == null) return;
        int tail = dp(6);
        int maxWidth = Math.min(bubbleMaxWidth, safe.width());
        bubble.measure(View.MeasureSpec.makeMeasureSpec(maxWidth - tail, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int width = manualBubble || pending() != null ? maxWidth : Math.min(maxWidth, bubble.getMeasuredWidth() + tail);
        PetBubblePlacement.Placement position = PetBubblePlacement.place(petLayout.x, petLayout.y, petLayout.width,
            petLayout.height, width, bubble.getMeasuredHeight(), safe.left, safe.top, safe.right, usableBottom());
        boolean horizontal = position.side() == PetBubblePlacement.Side.LEFT || position.side() == PetBubblePlacement.Side.RIGHT;
        bubbleWindow.setPadding(position.side() == PetBubblePlacement.Side.LEFT ? tail : 0,
            position.side() == PetBubblePlacement.Side.TOP ? tail : 0,
            position.side() == PetBubblePlacement.Side.RIGHT ? tail : 0,
            position.side() == PetBubblePlacement.Side.BOTTOM ? tail : 0);
        bubble.measure(View.MeasureSpec.makeMeasureSpec(width - (horizontal ? tail : 0), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        position = PetBubblePlacement.place(petLayout.x, petLayout.y, petLayout.width, petLayout.height, width,
            bubble.getMeasuredHeight() + (horizontal ? 0 : tail), safe.left, safe.top, safe.right, usableBottom());
        bubbleLayout.width = width; bubbleLayout.height = position.height();
        bubbleLayout.x = position.x(); bubbleLayout.y = position.y();
        bubbleBackground.pointTo(position.side(), position.tailOffset());
        if (bubbleHost != null) { bubbleHost.update(bubbleLayout); bubbleHost.surface().invalidateOutline(); }
    }
    private void action(String task, String action, String request, String option) {
        action(task, action, request, option, null);
    }
    private void action(String task, String action, String request, String option, JSONArray answers) {
        if (busy) return; busy = true; MAIN.removeCallbacks(hideMessage);
        if (bubble != null) setControlsEnabled(bubble, false);
        new Thread(() -> {
            String error = null;
            try { if (answers == null) PetActions.send(task, action, request, option); else PetActions.answer(task, request, answers); }
            catch (Exception failure) { error = failure.getMessage(); }
            String failure = error;
            MAIN.post(() -> {
                busy = false;
                if (failure != null) { Toast.makeText(context, failure, Toast.LENGTH_LONG).show(); if (pet != null) showBubble(true); }
                else {
                    JSONObject question = pending();
                    if (task.equals(snapshot.optString("taskId")) && (request == null || question == null
                            || request.equals(question.optString("requestId")))) {
                        dismissedRequest = request == null ? "" : request; collapse();
                    }
                    refreshNow();
                }
            });
        }, "droy-pet-action").start();
    }
    private void setControlsEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) setControlsEnabled(group.getChildAt(i), enabled);
    }
    private void openTask(String task) {
        JSONObject question = pending();
        if (question != null) MainActivity.revealQuestion(context, task, question.optString("requestId"));
        else MainActivity.revealTask(context, task);
        collapse();
    }
    private void showInteractions() {
        snapshot = ToolProgress.current(context);
        if (pet == null || sprite == null || busy || pending() != null || clearanceDepth > 0) { refreshNow(); return; }
        String packageId = sprite.packageId();
        showBubble(true);
        bubble.removeAllViews(); interactionMenu = true;
        previewUntil = Math.max(previewUntil, SystemClock.elapsedRealtime() + 30000);
        TextView heading = text(NativeLocale.text("Droy · 互动动作", "Droy · Play actions"), 14, bubbleColors.onSurface);
        heading.setPadding(0, 0, 0, dp(6)); bubble.addView(heading);
        for (String state : sprite.interactions()) {
            Button play = button(sprite.interactionLabel(state), () -> {
                if (sprite != null && packageId.equals(sprite.packageId())) playInteraction(state);
            });
            play.setTag("droy-pet-interaction:" + state);
            bubble.addView(play);
        }
        bubble.addView(button(NativeLocale.text("返回任务", "Back to task"), () -> showBubble(true)));
        String task = snapshot.optString("taskId");
        if (snapshot.optBoolean("active") && !task.isEmpty())
            bubble.addView(button(NativeLocale.text("停止任务", "Stop task"), () -> action(task, "cancel", null, null)));
        MAIN.removeCallbacks(hideMessage); positionBubble();
    }

    private void playInteraction(String state) {
        snapshot = ToolProgress.current(context);
        if (pet == null || sprite == null || busy || pending() != null || dragging || clearanceDepth > 0
                || !sprite.interactions().contains(state)) { refreshNow(); return; }
        cancelInteraction(false); removeBubble(); manualBubble = false; emerge();
        MAIN.removeCallbacks(blink);
        interactionState = state; interactionTask = snapshot.optString("taskId"); interactionTaskActive = snapshot.optBoolean("active");
        long sequence = ++interactionSequence;
        PetSpriteSpec.Effect effect = sprite.effect(state);
        long duration = PetInteractionActions.duration(sprite.duration(state), effect == null ? 0 : effect.durationMs(), ValueAnimator.areAnimatorsEnabled());
        previewUntil = Math.max(previewUntil, SystemClock.elapsedRealtime() + duration + 1500);
        sprite.showState(state, duration > 0);
        if (duration > 0 && effect != null) {
            try { showEffect(state); }
            catch (WindowManager.BadTokenException | SecurityException unavailable) {
                cancelInteraction(true);
                Toast.makeText(context, NativeLocale.text("无法显示水滴特效，请重新打开 Droy 后重试", "Unable to show the droplet effect. Reopen Droy and try again"), Toast.LENGTH_LONG).show();
                refreshNow(); return;
            }
        }
        finishInteraction = () -> {
            if (sequence != interactionSequence || pet == null || sprite == null) return;
            finishInteraction = null; interactionState = ""; interactionTask = ""; interactionTaskActive = false; removeEffect();
            snapshot = ToolProgress.current(context); sprite.showState(animation(), false); refreshNow();
            if (pet != null) {
                MAIN.postDelayed(blink, 11000);
                if (bubble == null) MAIN.postDelayed(dock, 4000);
            }
        };
        MAIN.postDelayed(finishInteraction, duration);
    }

    private void showEffect(String state) {
        int size = Math.min(dp(264), Math.min(safe.width(), usableBottom() - safe.top));
        if (size <= 0) return;
        effectWindow = sprite.effectView(state);
        WindowManager.LayoutParams params = layout(size, size, "Droy droplet effect");
        params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        // Android 12 limits pass-through touch beneath untrusted overlay windows.
        // Stay below the device limit instead of blocking another app's input.
        params.alpha = Build.VERSION.SDK_INT >= 31
            ? Math.min(.6f, context.getSystemService(android.hardware.input.InputManager.class).getMaximumObscuringOpacityForTouch() * .75f) : .6f;
        params.x = safe.left + (safe.width() - size) / 2;
        params.y = safe.top + (usableBottom() - safe.top - size) / 2;
        windows.addView(effectWindow, params);
        effectWindowId = windowId(effectWindow); OverlayInteraction.registerWindow(effectWindowId);
        effectWindow.post(() -> {
            if (effectWindow != null) { effectWindowId = windowId(effectWindow); OverlayInteraction.registerWindow(effectWindowId); }
        });
    }
    private void removeEffect() {
        OverlayInteraction.unregisterWindow(effectWindowId);
        if (effectWindow != null && effectWindow.getParent() != null) windows.removeViewImmediate(effectWindow);
        effectWindow = null; effectWindowId = -1;
    }
    private void cancelInteraction(boolean restoreState) {
        boolean wasPlaying = !interactionState.isEmpty();
        interactionSequence++; interactionState = ""; interactionTask = ""; interactionTaskActive = false;
        if (finishInteraction != null) { MAIN.removeCallbacks(finishInteraction); finishInteraction = null; }
        removeEffect();
        if (wasPlaying && sprite != null) {
            sprite.cancelPlayback();
            if (restoreState && pet != null) sprite.showState(docked ? (onLeft ? "peekLeft" : "peekRight") : animation(), false);
            MAIN.removeCallbacks(blink); MAIN.postDelayed(blink, 11000);
        }
    }

    private void collapse() { removeBubble(); manualBubble = false; if (pet != null) MAIN.postDelayed(dock, 1200); }
    private void removeBubble() {
        MAIN.removeCallbacks(hideMessage);
        stageBubble = false; problemBubble = false; interactionMenu = false;
        releaseAnswerFocus();
        if (bubbleWindow != null) { OverlayInteraction.unregisterWindow(bubbleWindowId);
            if (bubbleHost != null) bubbleHost.dismiss();
            bubbleHost = null; bubbleWindow = null; bubble = null; bubbleBackground = null; bubbleWindowId = -1; }
    }

    @Override public CompletableFuture<Void> prepare(Rect area, boolean capture) {
        cancelInteraction(true);
        stopTransition(true); cancelMovement(); MAIN.removeCallbacks(dock);
        restoreSequence++;
        clearanceDepth++;
        boolean changed = false, moved = false;
        if (pet != null && (capture || Rect.intersects(area, petBounds()))) {
            PetAvoidancePlacement.Position position = capture || Build.VERSION.SDK_INT < 34 || !pet.isHardwareAccelerated()
                ? null : PetAvoidancePlacement.choose(petLayout.x, petLayout.y,
                petLayout.width, petLayout.height, safe.left, safe.top, safe.right, usableBottom(),
                area.left, area.top, area.right, area.bottom, dp(8));
            if (position == null) petHidden = true;
            else {
                // InputDispatcher can retain the pre-move hit target for a synthetic gesture.
                // Keep the cat visible while removing it from input routing until dispatch settles.
                petTouchPaused = true;
                temporarilyMoved = true; petLayout.x = position.x(); petLayout.y = position.y();
                onLeft = petLayout.x + petLayout.width / 2 < screenWidth / 2;
                updatePet(); setDocked(docked); if (bubble != null) positionBubble();
                moved = true;
            }
            changed = true;
        }
        if (bubble != null && (capture || Rect.intersects(area, bubbleBounds()))) { bubbleHidden = true; changed = true; }
        if (!changed) return CompletableFuture.completedFuture(null);
        applyVisibility();
        CompletableFuture<Void> ready = new CompletableFuture<>();
        if (moved) {
            // A layout frame can precede the window's submitted surface/input transform.
            pet.getViewTreeObserver().registerFrameCommitCallback(() -> MAIN.post(() ->
                Choreographer.getInstance().postFrameCallback(frame -> ready.complete(null))));
            pet.invalidate();
        } else {
            // A hidden attached window can retain its last surface buffer past two
            // layout frames. Submit a transparent buffer before declaring capture safe.
            CompletableFuture.allOf(clearSurface(pet, petHidden), clearSurface(bubbleHost == null ? null : bubbleHost.surface(), bubbleHidden))
                .thenRun(() -> MAIN.post(() -> {
                    applyVisibility();
                    Choreographer.getInstance().postFrameCallback(frame -> ready.complete(null));
                }));
        }
        return ready;
    }
    private CompletableFuture<Void> clearSurface(View view, boolean hidden) {
        if (view == null || !hidden) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> submitted = new CompletableFuture<>();
        view.setAlpha(0f); view.setVisibility(View.VISIBLE);
        if (view.isHardwareAccelerated()) {
            view.getViewTreeObserver().registerFrameCommitCallback(() -> submitted.complete(null));
            view.invalidate();
        } else {
            view.invalidate();
            Choreographer.getInstance().postFrameCallback(first -> Choreographer.getInstance().postFrameCallback(second -> submitted.complete(null)));
        }
        return submitted;
    }
    @Override public void finish() {
        clearanceDepth = Math.max(0, clearanceDepth - 1);
        if (clearanceDepth == 0) {
            long sequence = ++restoreSequence;
            // Gesture completion precedes the target window consuming its final input frame.
            // Keep both relocated and hidden surfaces clear until that frame has passed.
            Choreographer.getInstance().postFrameCallback(first -> Choreographer.getInstance().postFrameCallback(second -> {
                if (sequence == restoreSequence && clearanceDepth == 0) restore.run();
            }));
        }
    }
    private void updateKeyboardInsets(View view, WindowInsets localInsets) {
        if (!localInsets.isVisible(WindowInsets.Type.ime())) {
            if (!io.droyagent.androiduse.DroyAccessibilityService.isConnected(context)) onKeyboardBounds(new Rect());
            return;
        }
        // Small overlay-window insets are local intersections. Ask the application
        // display-area metrics for full-screen geometry before translating a local fallback.
        WindowMetrics metrics = systemWindows.getCurrentWindowMetrics();
        Insets displayIme = metrics.getWindowInsets().getInsets(WindowInsets.Type.ime());
        Rect bounds = metrics.getBounds();
        if (displayIme.bottom > 0) {
            onKeyboardBounds(new Rect(bounds.left, bounds.bottom - displayIme.bottom, bounds.right, bounds.bottom));
        } else {
            Insets localIme = localInsets.getInsets(WindowInsets.Type.ime());
            if (localIme.bottom > 0) {
                int[] origin = new int[2]; view.getLocationOnScreen(origin);
                onKeyboardBounds(new Rect(0, origin[1] + view.getHeight() - localIme.bottom, screenWidth, screenHeight));
            }
        }
    }
    @Override public void onKeyboardBounds(Rect bounds) {
        if (keyboard.equals(bounds)) return;
        if (!bounds.isEmpty()) cancelInteraction(true);
        keyboard.set(bounds);
        if (pet != null && !dragging) { applySavedPosition(); if (bubble != null) positionBubble(); }
    }
    private void applyVisibility() {
        if (pet != null) { pet.setVisibility(petHidden ? View.INVISIBLE : View.VISIBLE);
            pet.setAlpha(petHidden ? 0f : 1f);
            if (petHidden || petTouchPaused) petLayout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else petLayout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            updatePet(); }
        if (bubbleWindow != null) { bubbleWindow.setVisibility(bubbleHidden ? View.INVISIBLE : View.VISIBLE);
            bubbleWindow.setAlpha(bubbleHidden ? 0f : 1f);
            if (bubbleHost != null) bubbleHost.setHidden(bubbleHidden);
            if (bubbleHidden) bubbleLayout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else bubbleLayout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            if (bubbleHost != null) bubbleHost.update(bubbleLayout); }
    }
    Rect petBounds() { return pet == null ? new Rect() : new Rect(petLayout.x, petLayout.y, petLayout.x + petLayout.width, petLayout.y + petLayout.height); }
    private Rect bubbleBounds() { return bubbleWindow == null ? new Rect() : new Rect(bubbleLayout.x, bubbleLayout.y, bubbleLayout.x + bubbleLayout.width, bubbleLayout.y + bubbleLayout.height); }
    private void removeWindows() {
        cancelInteraction(false);
        stopTransition(false); removeBubble(); cancelMovement(); MAIN.removeCallbacks(blink); MAIN.removeCallbacks(dock);
        holding = dragging = false;
        if (pet != null) { OverlayInteraction.unregisterWindow(petWindowId);
            // A registered ViewRoot is present even before the first attach traversal,
            // but absent after Android removes an Activity-owned window on destruction.
            if (pet.getParent() != null) windows.removeViewImmediate(pet);
            pet = null; sprite = null; petWindowId = -1; }
    }
    private int windowId(View view) {
        android.view.accessibility.AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        int id = node.getWindowId(); node.recycle(); return id;
    }
    private void registerWindowIds() {
        OverlayInteraction.unregisterWindow(petWindowId); OverlayInteraction.unregisterWindow(bubbleWindowId);
        OverlayInteraction.unregisterWindow(effectWindowId);
        petWindowId = pet == null ? -1 : windowId(pet);
        bubbleWindowId = bubbleWindow == null ? -1 : windowId(bubbleWindow);
        effectWindowId = effectWindow == null ? -1 : windowId(effectWindow);
        OverlayInteraction.registerWindow(petWindowId); OverlayInteraction.registerWindow(bubbleWindowId);
        OverlayInteraction.registerWindow(effectWindowId);
    }
    private void close() {
        removeWindows(); MAIN.removeCallbacks(restore); MAIN.removeCallbacks(delayedAnnouncement); MAIN.removeCallbacks(refreshDisplay);
        previewUntil = 0; displayPolicy.clear(); chatter.reset();
        proactiveText = proactiveTask = proactiveId = ""; proactiveUntil = 0;
        petHidden = bubbleHidden = petTouchPaused = false; clearanceDepth = 0; busy = false;
        restoreSequence++;
        lastAnnouncement = ""; displayedMessageKey = ""; temporarilyMoved = false;
        if (registered) { context.unregisterReceiver(screen); context.unregisterComponentCallbacks(configuration);
            context.getSystemService(android.view.accessibility.AccessibilityManager.class).removeAccessibilityStateChangeListener(accessibility);
            OverlayInteraction.removeListener(this); registered = false; }
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
