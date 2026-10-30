package io.droyagent.app;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/** Artwork-only transient layer: it never receives input or reads screen content. */
final class PetScreenEffectView extends View {
    private final Bitmap bitmap;
    private final PetSpriteSpec.Effect effect;
    private final Paint paint = new Paint();
    private final long started = SystemClock.uptimeMillis();
    private final Rect source = new Rect();
    private final RectF destination = new RectF();
    private int lastDrawnFrame = -1;
    private final Runnable advance = () -> { if (isAttachedToWindow()) invalidate(); };

    PetScreenEffectView(Context context, PetPackage assets, PetSpriteSpec.Effect effect) {
        super(context); this.effect = effect; this.bitmap = assets.bitmaps.get(effect.animation.atlasKey);
        paint.setFilterBitmap(!assets.spec.pixelArt); paint.setAntiAlias(!assets.spec.pixelArt);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); setClickable(false); setFocusable(false);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        long elapsed = SystemClock.uptimeMillis() - started - effect.delayMs;
        if (elapsed < 0) { postDelayed(advance, -elapsed); return; }
        if (elapsed >= effect.animation.durationMs) return;
        int frameIndex = effect.animation.frameAt(elapsed);
        PetSpriteSpec.Frame frame = effect.animation.frames[frameIndex];
        float scale = effect.animation.scaleToFit(getWidth(), getHeight());
        float width = frame.width * scale, height = frame.height * scale;
        source.set(frame.x, frame.y, frame.x + frame.width, frame.y + frame.height);
        destination.set((getWidth() - width) / 2, (getHeight() - height) / 2,
            (getWidth() + width) / 2, (getHeight() + height) / 2);
        canvas.drawBitmap(bitmap, source, destination, paint);
        lastDrawnFrame = frameIndex;
        long next = effect.animation.nextFrameDelay(elapsed);
        postDelayed(advance, next > 0 ? next : effect.animation.durationMs - elapsed);
    }
    @Override protected void onDetachedFromWindow() { removeCallbacks(advance); super.onDetachedFromWindow(); }
}
