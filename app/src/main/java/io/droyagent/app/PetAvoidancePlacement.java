package io.droyagent.app;

/** Finds the nearest visible resting position outside the pending input region. */
final class PetAvoidancePlacement {
    record Position(int x, int y) {}

    private PetAvoidancePlacement() {}

    static Position choose(int x, int y, int width, int height, int left, int top, int right, int bottom,
                           int avoidLeft, int avoidTop, int avoidRight, int avoidBottom, int gap) {
        if (right - left < width || bottom - top < height) return null;
        int[] xs = {left, right - width, PetPosition.clamp(x, left, right - width)};
        int[] ys = {PetPosition.clamp(y, top, bottom - height), top, bottom - height,
            PetPosition.clamp(avoidTop - height - gap, top, bottom - height),
            PetPosition.clamp(avoidBottom + gap, top, bottom - height)};
        Position best = null;
        long distance = Long.MAX_VALUE;
        for (int candidateX : xs) for (int candidateY : ys) {
            if (candidateX < avoidRight + gap && candidateX + width > avoidLeft - gap
                    && candidateY < avoidBottom + gap && candidateY + height > avoidTop - gap) continue;
            long dx = candidateX - x, dy = candidateY - y, score = dx * dx + dy * dy;
            if (score < distance) { distance = score; best = new Position(candidateX, candidateY); }
        }
        return best;
    }
}
