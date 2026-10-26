package io.droyagent.app;

/** The tail touches the pet's window; the sprite's own inset leaves a small visual gap. */
final class PetBubblePlacement {
    enum Side { LEFT, RIGHT, TOP, BOTTOM }
    record Placement(int x, int y, int height, Side side, int tailOffset) {}

    private PetBubblePlacement() {}

    static Placement place(int petX, int petY, int petWidth, int petHeight, int width, int height,
                           int left, int top, int right, int bottom) {
        int leftRoom = petX - left, rightRoom = right - petX - petWidth;
        int headY = petY + petHeight * 2 / 5;
        if (Math.max(leftRoom, rightRoom) >= width) {
            boolean toRight = rightRoom >= leftRoom;
            int visibleHeight = Math.min(height, bottom - top);
            int x = toRight ? petX + petWidth : petX - width;
            int y = PetPosition.clamp(headY - visibleHeight / 2, top, bottom - visibleHeight);
            return new Placement(x, y, visibleHeight, toRight ? Side.LEFT : Side.RIGHT, headY - y);
        }
        int above = petY - top, below = bottom - petY - petHeight;
        boolean toTop = above >= below;
        int visibleHeight = Math.min(height, Math.max(above, below));
        int x = PetPosition.clamp(petX + petWidth / 2 - width / 2, left, right - width);
        int y = toTop ? petY - visibleHeight : petY + petHeight;
        return new Placement(x, y, visibleHeight, toTop ? Side.BOTTOM : Side.TOP, petX + petWidth / 2 - x);
    }
}
