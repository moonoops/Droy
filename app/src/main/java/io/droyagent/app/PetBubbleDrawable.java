package io.droyagent.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Translucent tint and a soft edge sit above the Window's rounded background blur. */
final class PetBubbleDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final Path nub = new Path();
    private final float density;
    private final int surfaceColor, outlineColor;
    private PetBubblePlacement.Side side = PetBubblePlacement.Side.LEFT;
    private float offset;
    private int alpha = 255;
    private boolean blurAvailable;

    PetBubbleDrawable(float density, int surfaceColor, int outlineColor) {
        this.density = density;
        this.surfaceColor = surfaceColor; this.outlineColor = outlineColor;
    }

    void pointTo(PetBubblePlacement.Side side, int offset) {
        this.side = side; this.offset = offset; invalidateSelf();
    }

    void setBlurAvailable(boolean available) { blurAvailable = available; invalidateSelf(); }

    private RectF body() {
        RectF body = new RectF(getBounds());
        body.inset(.5f * density, .5f * density);
        float tip = 4 * density;
        switch (side) {
            case LEFT -> body.left += tip;
            case RIGHT -> body.right -= tip;
            case TOP -> body.top += tip;
            case BOTTOM -> body.bottom -= tip;
        }
        return body;
    }

    @Override public void getOutline(Outline outline) {
        RectF body = body();
        outline.setRoundRect(Math.round(body.left), Math.round(body.top), Math.round(body.right), Math.round(body.bottom), 20 * density);
        outline.setAlpha(1f);
    }

    @Override public void draw(Canvas canvas) {
        float radius = 20 * density;
        RectF body = body();
        shape.reset(); shape.addRoundRect(body, radius, radius, Path.Direction.CW);
        float tip = 4 * density, half = 3 * density;
        nub.reset();
        if (side == PetBubblePlacement.Side.LEFT || side == PetBubblePlacement.Side.RIGHT) {
            float y = Math.max(body.top + radius, Math.min(offset, body.bottom - radius));
            float x = side == PetBubblePlacement.Side.LEFT ? body.left : body.right;
            nub.moveTo(x, y - half); nub.lineTo(x + (side == PetBubblePlacement.Side.LEFT ? -tip : tip), y); nub.lineTo(x, y + half);
        } else {
            float x = Math.max(body.left + radius, Math.min(offset, body.right - radius));
            float y = side == PetBubblePlacement.Side.TOP ? body.top : body.bottom;
            nub.moveTo(x - half, y); nub.lineTo(x, y + (side == PetBubblePlacement.Side.TOP ? -tip : tip)); nub.lineTo(x + half, y);
        }
        nub.close(); shape.op(nub, Path.Op.UNION);
        paint.setStyle(Paint.Style.FILL); paint.setColor(surfaceColor);
        // Retain a readable tint when Android disables compositor blur (for example in power saving).
        paint.setAlpha(alpha * (blurAvailable ? 85 : 112) / 255); canvas.drawPath(shape, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density); paint.setColor(outlineColor);
        paint.setAlpha(alpha * 165 / 255); canvas.drawPath(shape, paint);
    }

    @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
