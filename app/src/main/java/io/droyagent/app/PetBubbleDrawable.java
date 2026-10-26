package io.droyagent.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** A speech bubble whose tail follows the cat; answer forms obscure distracting app content. */
final class PetBubbleDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final Path tail = new Path();
    private final float density;
    private final int bodyAlpha;
    private PetBubblePlacement.Side side = PetBubblePlacement.Side.LEFT;
    private float offset;
    private int alpha = 255;

    PetBubbleDrawable(float density, boolean answerForm) {
        this.density = density;
        bodyAlpha = answerForm ? 247 : 199;
    }

    void pointTo(PetBubblePlacement.Side side, int offset) {
        this.side = side; this.offset = offset; invalidateSelf();
    }

    @Override public void draw(Canvas canvas) {
        float tip = 8 * density, radius = 13 * density, halfTail = 5 * density;
        RectF body = new RectF(getBounds());
        body.inset(.5f * density, .5f * density);
        switch (side) {
            case LEFT -> body.left += tip;
            case RIGHT -> body.right -= tip;
            case TOP -> body.top += tip;
            case BOTTOM -> body.bottom -= tip;
        }
        shape.reset(); shape.addRoundRect(body, radius, radius, Path.Direction.CW);
        tail.reset();
        if (side == PetBubblePlacement.Side.LEFT || side == PetBubblePlacement.Side.RIGHT) {
            float y = Math.max(body.top + radius, Math.min(offset, body.bottom - radius));
            float x = side == PetBubblePlacement.Side.LEFT ? body.left : body.right;
            tail.moveTo(x, y - halfTail);
            tail.lineTo(x + (side == PetBubblePlacement.Side.LEFT ? -tip : tip), y);
            tail.lineTo(x, y + halfTail);
        } else {
            float x = Math.max(body.left + radius, Math.min(offset, body.right - radius));
            float y = side == PetBubblePlacement.Side.TOP ? body.top : body.bottom;
            tail.moveTo(x - halfTail, y);
            tail.lineTo(x, y + (side == PetBubblePlacement.Side.TOP ? -tip : tip));
            tail.lineTo(x + halfTail, y);
        }
        tail.close(); shape.op(tail, Path.Op.UNION);
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xFF22252C); paint.setAlpha(bodyAlpha * alpha / 255); canvas.drawPath(shape, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(density); paint.setColor(0x38FFFFFF); paint.setAlpha(56 * alpha / 255); canvas.drawPath(shape, paint);
    }

    @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
