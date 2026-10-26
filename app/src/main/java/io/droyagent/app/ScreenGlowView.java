package io.droyagent.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.os.Build;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.RoundedCorner;
import android.view.View;
import android.view.WindowInsets;

/** Draws only the display perimeter; the underlying app keeps its original pixels. */
final class ScreenGlowView extends View {
    private static final long CYCLE_MS = 6000;
    private static final int[] COLORS = { 0xFF89C7F4, 0xFFBAACED, 0xFFE7B4CB, 0xFFEBD0B4, 0xFF89C7F4 };
    private static final float[] STROKES_DP = { 20, 16, 12, 8, 5, 1.75f };
    private static final int[] STROKE_ALPHA = { 5, 8, 12, 18, 24, 230 };
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private final Matrix gradientTransform = new Matrix();
    private final float[] radii = new float[8];
    private final float density;
    private final long cycleStarted = SystemClock.uptimeMillis();
    private SweepGradient gradient;
    private boolean captureSuppressed, targetVisible, framePosted, disposed;
    private float level, fadeFrom, fadeTo, appearance = 1f;
    private long fadeStarted, fadeDuration;
    private Runnable afterHidden;

    ScreenGlowView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setTag("droy-automation-screen-glow");
        setFocusable(false); setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        updateAppearance();
    }

    void show() {
        if (disposed) return;
        afterHidden = null;
        if (!targetVisible) { targetVisible = true; transition(1f, 400); }
        else { updateAppearance(); invalidate(); scheduleFrame(); }
    }

    void hide(Runnable completed) {
        if (disposed) { completed.run(); return; }
        afterHidden = completed;
        if (captureSuppressed || !isAttachedToWindow()) {
            targetVisible = false; level = fadeTo = 0; fadeDuration = 0;
            cancelFrame(); finishHidden(); return;
        }
        if (!targetVisible && fadeTo == 0f) return;
        targetVisible = false; transition(0f, 300);
    }

    void setCaptureSuppressed(boolean value) {
        if (captureSuppressed == value || disposed) return;
        captureSuppressed = value;
        if (value) cancelFrame();
        else scheduleFrame();
        // The overlay submits this with alpha zero before making the surface
        // invisible, then restores visibility and alpha before resuming motion.
        invalidate();
    }

    void configurationChanged() { updateAppearance(); updateOutline(getRootWindowInsets()); invalidate(); }

    void dispose() {
        disposed = true; afterHidden = null; cancelFrame();
    }

    private void updateAppearance() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
        appearance = dark ? .72f : 1f;
    }

    private void transition(float destination, long duration) {
        updateLevel(SystemClock.uptimeMillis());
        fadeFrom = level; fadeTo = destination; fadeStarted = SystemClock.uptimeMillis();
        fadeDuration = ValueAnimator.areAnimatorsEnabled() ? duration : 0;
        if (fadeDuration == 0) level = destination;
        invalidate();
        if (!targetVisible && fadeDuration == 0) finishHidden();
        else scheduleFrame();
    }

    private void updateLevel(long now) {
        if (fadeDuration == 0) return;
        float fraction = Math.min(1f, Math.max(0f, (float) (now - fadeStarted) / fadeDuration));
        float eased = fraction * fraction * (3f - 2f * fraction);
        level = fadeFrom + (fadeTo - fadeFrom) * eased;
        if (fraction == 1f) { level = fadeTo; fadeDuration = 0; }
    }

    private final Choreographer.FrameCallback frame = timestamp -> {
        framePosted = false;
        if (disposed || captureSuppressed || !isAttachedToWindow()) return;
        if (!ValueAnimator.areAnimatorsEnabled()) { level = fadeTo; fadeDuration = 0; }
        else updateLevel(SystemClock.uptimeMillis());
        invalidate();
        if (!targetVisible && fadeDuration == 0) finishHidden();
        else scheduleFrame();
    };

    private void scheduleFrame() {
        if (!framePosted && !disposed && !captureSuppressed && isAttachedToWindow()
                && ValueAnimator.areAnimatorsEnabled() && (targetVisible || fadeDuration > 0)) {
            framePosted = true;
            Choreographer.getInstance().postFrameCallback(frame);
        }
    }

    private void cancelFrame() {
        if (framePosted) Choreographer.getInstance().removeFrameCallback(frame);
        framePosted = false;
    }

    private void finishHidden() {
        Runnable finished = afterHidden; afterHidden = null;
        if (finished != null) post(finished);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestApplyInsets(); scheduleFrame();
    }

    @Override protected void onDetachedFromWindow() { cancelFrame(); super.onDetachedFromWindow(); }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        gradient = new SweepGradient(width / 2f, height / 2f, COLORS, null);
        paint.setShader(gradient); updateOutline(getRootWindowInsets());
    }

    @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        updateOutline(insets); return insets;
    }

    private void updateOutline(WindowInsets insets) {
        if (getWidth() == 0 || getHeight() == 0) return;
        float inset = 1.1f * density;
        for (int i = 0; i < 4; i++) {
            float radius = 0;
            if (Build.VERSION.SDK_INT >= 31 && insets != null) {
                RoundedCorner corner = insets.getRoundedCorner(i);
                if (corner != null) radius = Math.max(0f, corner.getRadius() - inset);
            }
            // RoundedCorner positions and Path radii both run clockwise from top-left.
            radii[i * 2] = radii[i * 2 + 1] = radius;
        }
        outline.reset();
        outline.addRoundRect(new RectF(inset, inset, getWidth() - inset, getHeight() - inset), radii, Path.Direction.CW);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (disposed || captureSuppressed || gradient == null || level <= 0f) return;
        float turn = ValueAnimator.areAnimatorsEnabled()
            ? (SystemClock.uptimeMillis() - cycleStarted) % CYCLE_MS * 360f / CYCLE_MS : 0f;
        gradientTransform.setRotate(turn - 140f, getWidth() / 2f, getHeight() / 2f);
        gradient.setLocalMatrix(gradientTransform);
        // Narrow translucent strokes feather inward without a full-screen software blur.
        for (int i = 0; i < STROKES_DP.length; i++) {
            paint.setStrokeWidth(STROKES_DP[i] * density);
            paint.setAlpha(Math.round(STROKE_ALPHA[i] * level * appearance));
            canvas.drawPath(outline, paint);
        }
    }
}
