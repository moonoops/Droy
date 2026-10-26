package io.droyagent.app;

/** Geometry uses physical screen pixels, including the tool gesture coordinates. */
final class PetPosition {
    private PetPosition() {}
    static int clamp(int value, int min, int max) { return Math.max(min, Math.min(value, Math.max(min, max))); }
    static int restoreY(float fraction, int top, int bottom, int height) {
        return top + Math.round(Math.max(0f, Math.min(1f, fraction)) * Math.max(0, bottom - top - height));
    }
    static float fraction(int y, int top, int bottom, int height) {
        return Math.max(0f, Math.min(1f, (float) (y - top) / Math.max(1, bottom - top - height)));
    }
    static int bubbleX(int petX, int petWidth, int width, int screenWidth, int margin) {
        int proposed = petX < screenWidth / 2 ? petX + petWidth : petX - width;
        return clamp(proposed, margin, screenWidth - width - margin);
    }
}
