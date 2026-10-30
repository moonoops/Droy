package io.droyagent.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Local one-shot sprite playback; the host owns placement and gesture semantics. */
final class PetSpriteView extends View {
    private final PetPackage assets;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private final RectF destination = new RectF();
    private final Map<String, String> previousVariants;
    private final float rimWidth;
    private PetSpriteSpec.Animation animation;
    private String state = "idle";
    private int frame;
    private long startedAt;
    private boolean animateOnAttach, facingLeft = true, playing;
    private Runnable completion;

    private final Runnable advance = new Runnable() {
        @Override public void run() {
            if (!isAttachedToWindow() || !isShown() || !playing) return;
            long elapsed = SystemClock.uptimeMillis() - startedAt;
            if (!ValueAnimator.areAnimatorsEnabled()) elapsed = animation.durationMs;
            frame = animation.frameAt(elapsed); invalidate();
            if (elapsed >= animation.durationMs) {
                playing = false;
                Runnable done = completion; completion = null;
                if (done != null) done.run();
                return;
            }
            long delay = animation.nextFrameDelay(elapsed);
            postDelayed(this, delay > 0 ? delay : animation.durationMs - elapsed);
        }
    };

    PetSpriteView(Context context) {
        super(context);
        assets = PetPackage.selected(context.getApplicationContext()); previousVariants = assets.previousVariants;
        rimWidth = assets.spec.pixelArt ? 0f : .65f * getResources().getDisplayMetrics().density;
        bitmapPaint.setFilterBitmap(!assets.spec.pixelArt); bitmapPaint.setAntiAlias(!assets.spec.pixelArt);
        rimPaint.setColorFilter(new PorterDuffColorFilter(Color.argb(105, 255, 255, 255), PorterDuff.Mode.SRC_IN));
        animation = assets.spec.animations.get("idle");
    }
    PetPersona persona() { return assets.spec.persona; }
    String packageId() { return assets.id; }
    boolean hasState(String name) { return assets.spec.animations.containsKey(name); }
    String state() { return state; }
    long duration(String name) { return assets.spec.animations.get(name).durationMs; }
    java.util.List<String> interactions() { return PetInteractionActions.available(this::hasState); }
    String interactionLabel(String name) {
        PetSpriteSpec.Label label = assets.spec.labels.get(name);
        return label == null ? PetInteractionActions.fallbackLabel(name) : label.localized();
    }
    PetSpriteSpec.Effect effect(String name) { return assets.spec.effects.get(name); }
    View effectView(String name) { return new PetScreenEffectView(getContext(), assets, assets.spec.effects.get(name)); }
    void cancelPlayback() {
        removeCallbacks(advance); playing = animateOnAttach = false; completion = null;
    }
    void setFacingLeft(boolean value) {
        if (facingLeft == value) return;
        facingLeft = value; animation = directional(state); frame = Math.min(frame, animation.frames.length - 1); invalidate();
    }
    private PetSpriteSpec.Animation directional(String name) {
        PetSpriteSpec.Animation base = assets.spec.animations.get(name);
        return assets.spec.animations.get(facingLeft ? base.leftState : base.rightState);
    }
    String variant(String group, String fallback) {
        String[] candidates = assets.spec.variants.get(group);
        if (candidates == null || candidates.length == 0) return fallback;
        String previous = previousVariants.get(group);
        int total = 0;
        for (String candidate : candidates) if (candidates.length == 1 || !candidate.equals(previous)) total += persona().weight(candidate);
        int selection = ThreadLocalRandom.current().nextInt(total);
        for (String candidate : candidates) {
            if (candidates.length > 1 && candidate.equals(previous)) continue;
            selection -= persona().weight(candidate);
            if (selection < 0) { previousVariants.put(group, candidate); return candidate; }
        }
        throw new AssertionError("Empty pet variant selection");
    }
    void showVariant(String group, String fallback, boolean animate) { showState(variant(group, fallback), animate); }
    void showState(String name, boolean animate) { showState(name, animate, null); }
    void showState(String name, boolean animate, Runnable done) {
        if (!hasState(name)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("未知宠物动作：", "Unknown companion action: ") + name);
        removeCallbacks(advance);
        state = name; animation = directional(name); frame = 0; completion = done;
        animateOnAttach = animate && ValueAnimator.areAnimatorsEnabled(); playing = false;
        invalidate();
        if (animateOnAttach && isAttachedToWindow()) startAnimation();
        else if (!animateOnAttach && done != null) { completion = null; post(done); }
    }
    void showQuickState(String name, int totalMs) {
        showState(name, true); animation = animation.withTotalDuration(totalMs);
    }
    private void startAnimation() {
        animateOnAttach = false; playing = true; startedAt = SystemClock.uptimeMillis(); post(advance);
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animateOnAttach && ValueAnimator.areAnimatorsEnabled()) startAnimation();
    }
    @Override protected void onDetachedFromWindow() {
        removeCallbacks(advance); animateOnAttach = playing = false; completion = null; super.onDetachedFromWindow();
    }
    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE) removeCallbacks(advance);
        else if (playing) post(advance);
    }
    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Math.round(56 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(resolveSize(size + getPaddingLeft() + getPaddingRight(), widthMeasureSpec),
            resolveSize(size + getPaddingTop() + getPaddingBottom(), heightMeasureSpec));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth() - getPaddingLeft() - getPaddingRight() - 2 * rimWidth;
        float height = getHeight() - getPaddingTop() - getPaddingBottom() - 2 * rimWidth;
        if (width <= 0 || height <= 0) return;
        PetSpriteSpec.Frame current = animation.frames[frame];
        float scale = animation.scaleToFit(width, height);
        float drawnWidth = current.width * scale, drawnHeight = current.height * scale;
        float left = getPaddingLeft() + rimWidth + (width - drawnWidth) / 2;
        float bottom = getHeight() - getPaddingBottom() - rimWidth;
        destination.set(left, bottom - drawnHeight, left + drawnWidth, bottom);
        source.set(current.x, current.y, current.x + current.width, current.y + current.height);
        Bitmap bitmap = assets.bitmaps.get(animation.atlasKey);
        canvas.save();
        boolean mirror = animation.mirrored(facingLeft);
        if (mirror) canvas.scale(-1, 1, getWidth() / 2f, getHeight() / 2f);
        if (rimWidth > 0) {
            drawRim(canvas, bitmap, -rimWidth, 0); drawRim(canvas, bitmap, rimWidth, 0);
            drawRim(canvas, bitmap, 0, -rimWidth); drawRim(canvas, bitmap, 0, rimWidth);
        }
        canvas.drawBitmap(bitmap, source, destination, bitmapPaint); canvas.restore();
    }
    private void drawRim(Canvas canvas, Bitmap bitmap, float x, float y) {
        canvas.save(); canvas.translate(x, y); canvas.drawBitmap(bitmap, source, destination, rimPaint); canvas.restore();
    }
}
