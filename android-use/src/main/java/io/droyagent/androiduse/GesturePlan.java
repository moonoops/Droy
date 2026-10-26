package io.droyagent.androiduse;

import java.util.Random;

/** One immutable path plan is shared by validation, overlay clearance and dispatch. */
final class GesturePlan {
    static final int MAX_POINTERS = 5;
    static final int MAX_POINTS = 16;
    final float[][][] paths;
    final int[] durations;
    final int durationMs;
    final String motion;
    final int left, top, right, bottom;

    private GesturePlan(float[][][] paths, int[] durations, int durationMs, String motion) {
        this.paths = paths;
        this.durations = durations;
        this.durationMs = durationMs;
        this.motion = motion;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = 0, maxY = 0;
        for (float[][] pointer : paths) for (float[] point : pointer) {
            minX = Math.min(minX, point[0]); minY = Math.min(minY, point[1]);
            maxX = Math.max(maxX, point[0]); maxY = Math.max(maxY, point[1]);
        }
        left = (int) Math.floor(minX); top = (int) Math.floor(minY);
        right = (int) Math.ceil(maxX) + 1; bottom = (int) Math.ceil(maxY) + 1;
    }

    static GesturePlan swipe(int x, int y, int endX, int endY, Integer requestedDuration,
                             boolean natural, float density, int left, int top, int right, int bottom,
                             Random random) throws ActionException {
        double distance = Math.hypot(endX - x, endY - y);
        if (natural && distance == 0) throw new ActionException("invalid_argument", "Natural swipe needs different endpoints");
        int duration = requestedDuration != null ? ActionValidation.duration(requestedDuration) : natural
                ? (int) Math.round(Math.max(220, Math.min(650, 180 + distance / density * .55)) * (.9 + random.nextDouble() * .2))
                : 300;
        if (!natural) return new GesturePlan(new float[][][]{{{x, y}, {endX, endY}}}, new int[]{duration}, duration, "precise");
        int segments = Math.max(5, (duration + 99) / 100);
        float[][][] paths = new float[1][segments * 4 + 1][2];
        double bend = Math.min(12 * density, distance * .025) * (random.nextDouble() * 2 - 1);
        double secondary = bend * (random.nextDouble() - .5) * .3;
        for (int i = 0; i < paths[0].length; i++) {
            double time = (double) i / (paths[0].length - 1);
            double progress = time * time * (3 - 2 * time);
            double offset = Math.sin(Math.PI * progress) * bend + Math.sin(2 * Math.PI * progress) * secondary;
            paths[0][i][0] = (float) Math.max(left, Math.min(right - 1, x + (endX - x) * progress - (endY - y) / distance * offset));
            paths[0][i][1] = (float) Math.max(top, Math.min(bottom - 1, y + (endY - y) * progress + (endX - x) / distance * offset));
        }
        paths[0][0] = new float[]{x, y}; paths[0][paths[0].length - 1] = new float[]{endX, endY};
        return new GesturePlan(paths, durations(duration, segments), duration, "natural");
    }

    static GesturePlan multiple(int[][][] pointers, int duration, int width, int height) throws ActionException {
        ActionValidation.duration(duration);
        if (pointers == null || pointers.length < 2 || pointers.length > MAX_POINTERS)
            throw new ActionException("invalid_argument", "gesture requires 2 to 5 pointers");
        int count = pointers[0] == null ? 0 : pointers[0].length;
        if (count < 2 || count > MAX_POINTS) throw new ActionException("invalid_argument", "Each pointer needs 2 to 16 points");
        for (int[][] pointer : pointers) {
            if (pointer == null || pointer.length != count) throw new ActionException("invalid_argument", "Pointers need the same number of synchronized points");
            for (int[] point : pointer) {
                if (point == null || point.length != 2) throw new ActionException("invalid_argument", "Each point needs x and y");
                ActionValidation.coordinate(point[0], "x", width); ActionValidation.coordinate(point[1], "y", height);
            }
        }
        for (int i = 0; i < count; i++) for (int a = 0; a < pointers.length; a++) for (int b = a + 1; b < pointers.length; b++) {
            if (pointers[a][i][0] == pointers[b][i][0] && pointers[a][i][1] == pointers[b][i][1])
                throw new ActionException("invalid_argument", "Simultaneous pointers must have distinct positions");
        }
        // Android may merge pointers at the same position. Check between keyframes
        // as well, so a crossing path cannot silently become a one-finger gesture.
        for (int i = 0; i < count - 1; i++) for (int a = 0; a < pointers.length; a++) for (int b = a + 1; b < pointers.length; b++) {
            double x = pointers[a][i][0] - pointers[b][i][0], y = pointers[a][i][1] - pointers[b][i][1];
            double dx = pointers[a][i + 1][0] - pointers[b][i + 1][0] - x;
            double dy = pointers[a][i + 1][1] - pointers[b][i + 1][1] - y;
            double squared = dx * dx + dy * dy;
            double fraction = squared == 0 ? 0 : Math.max(0, Math.min(1, -(x * dx + y * dy) / squared));
            if (Math.hypot(x + fraction * dx, y + fraction * dy) < 1)
                throw new ActionException("invalid_argument", "Pointer paths collide between synchronized points");
        }
        int subdivisions = Math.max(1, (duration + (count - 1) * 100 - 1) / ((count - 1) * 100));
        int segments = (count - 1) * subdivisions;
        float[][][] paths = new float[pointers.length][segments + 1][2];
        for (int p = 0; p < pointers.length; p++) for (int i = 0; i <= segments; i++) {
            int from = Math.min(count - 2, i / subdivisions);
            double fraction = (double) (i - from * subdivisions) / subdivisions;
            for (int axis = 0; axis < 2; axis++) paths[p][i][axis] = (float)
                    (pointers[p][from][axis] + fraction * (pointers[p][from + 1][axis] - pointers[p][from][axis]));
        }
        return new GesturePlan(paths, durations(duration, segments), duration, "precise");
    }

    int samplesPerSegment() { return (paths[0].length - 1) / durations.length; }

    private static int[] durations(int total, int count) {
        int[] result = new int[count];
        for (int i = 0; i < count; i++) result[i] = total / count + (i < total % count ? 1 : 0);
        return result;
    }
}
