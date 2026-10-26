package io.droyagent.androiduse;

import java.util.Arrays;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class GesturePlanTest {
    @Test public void oldSwipeKeepsItsStraightPathAndDefaultDuration() throws Exception {
        GesturePlan plan = swipe(300, 800, 300, 200, null, false, 0);
        assertEquals("precise", plan.motion);
        assertEquals(300, plan.durationMs);
        assertArrayEquals(new int[]{300}, plan.durations);
        assertArrayEquals(new float[]{300, 800}, plan.paths[0][0], 0);
        assertArrayEquals(new float[]{300, 200}, plan.paths[0][1], 0);
    }

    @Test public void naturalPathsVaryWithoutMovingEndpointsOrExitingBounds() throws Exception {
        boolean curved = false, variedDuration = false;
        int previous = 0;
        for (int seed = 0; seed < 100; seed++) {
            GesturePlan plan = swipe(300, 800, 300, 200, null, true, seed);
            assertArrayEquals(new float[]{300, 800}, plan.paths[0][0], 0);
            assertArrayEquals(new float[]{300, 200}, plan.paths[0][plan.paths[0].length - 1], 0);
            assertEquals(plan.durationMs, Arrays.stream(plan.durations).sum());
            for (int duration : plan.durations) assertTrue(duration > 0 && duration <= 100);
            for (float[] point : plan.paths[0]) {
                assertTrue(point[0] >= 0 && point[0] <= 599 && point[1] >= 0 && point[1] <= 999);
                assertTrue(point[0] >= plan.left && point[0] < plan.right && point[1] >= plan.top && point[1] < plan.bottom);
                curved |= Math.abs(point[0] - 300) > .1;
            }
            variedDuration |= previous != 0 && previous != plan.durationMs;
            previous = plan.durationMs;
        }
        assertTrue(curved); assertTrue(variedDuration);
    }

    @Test public void naturalMovementAcceleratesAndDeceleratesWhileHonoringExplicitDuration() throws Exception {
        GesturePlan plan = swipe(300, 800, 300, 200, 500, true, 17);
        assertEquals(500, plan.durationMs);
        int step = plan.samplesPerSegment();
        float firstDistance = Math.abs(plan.paths[0][step][1] - plan.paths[0][0][1]);
        float middleDistance = Math.abs(plan.paths[0][3 * step][1] - plan.paths[0][2 * step][1]);
        float finalDistance = Math.abs(plan.paths[0][5 * step][1] - plan.paths[0][4 * step][1]);
        assertTrue(middleDistance > firstDistance * 2);
        assertEquals(firstDistance, finalDistance, .001);
        for (int duration : new int[]{50, 51, 1999, 2000}) {
            GesturePlan limit = swipe(300, 800, 300, 200, duration, true, 5);
            assertEquals(duration, Arrays.stream(limit.durations).sum());
            assertTrue(Arrays.stream(limit.durations).min().getAsInt() > 0);
        }
    }

    @Test public void edgesAndZeroLengthHaveExplicitBehavior() throws Exception {
        for (int x : new int[]{0, 599}) {
            GesturePlan plan = swipe(x, 800, x, 200, null, true, 17);
            for (float[] point : plan.paths[0]) assertTrue(point[0] >= 0 && point[0] <= 599);
        }
        expect(() -> swipe(3, 3, 3, 3, null, true, 0));
        assertEquals(2, swipe(3, 3, 3, 3, null, false, 0).paths[0].length);
    }

    @Test public void multiPointerKeyframesAreSynchronizedAndCopied() throws Exception {
        int[][][] input = {{{200, 500}, {100, 500}}, {{400, 500}, {500, 500}}};
        GesturePlan plan = GesturePlan.multiple(input, 401, 600, 1000);
        assertEquals(2, plan.paths.length);
        assertEquals(401, Arrays.stream(plan.durations).sum());
        assertTrue(Arrays.stream(plan.durations).max().getAsInt() <= 100);
        for (int i = 0; i < plan.paths[0].length; i++) assertEquals(600, plan.paths[0][i][0] + plan.paths[1][i][0], .001);
        input[0][0][0] = 0;
        assertEquals(200, plan.paths[0][0][0], 0);
        int[][][] five = new int[5][16][2];
        for (int p = 0; p < 5; p++) for (int i = 0; i < 16; i++) five[p][i] = new int[]{p * 10 + 50, i + 50};
        GesturePlan fastest = GesturePlan.multiple(five, 50, 600, 1000);
        assertEquals(50, Arrays.stream(fastest.durations).sum());
        assertTrue(Arrays.stream(fastest.durations).min().getAsInt() >= 3);
    }

    @Test public void malformedMultiPointerInputFailsBeforeAnyDispatch() throws Exception {
        expect(() -> GesturePlan.multiple(new int[1][2][2], 300, 600, 1000));
        expect(() -> GesturePlan.multiple(new int[6][2][2], 300, 600, 1000));
        expect(() -> GesturePlan.multiple(new int[2][17][2], 300, 600, 1000));
        expect(() -> GesturePlan.multiple(new int[][][]{{{1, 1}, {2, 2}}, {{3, 3}}}, 300, 600, 1000));
        expect(() -> GesturePlan.multiple(new int[][][]{{{1, 1}, {2, 2}}, {{1, 1}, {4, 4}}}, 300, 600, 1000));
        expect(() -> GesturePlan.multiple(new int[][][]{{{1, 1}, {2, 2}}, {{600, 1}, {4, 4}}}, 300, 600, 1000));
        expect(() -> GesturePlan.multiple(new int[][][]{{{100, 100}, {200, 100}}, {{200, 100}, {100, 100}}}, 300, 600, 1000));
        expect(() -> GesturePlan.multiple(new int[][][]{{{1, 1}, {2, 2}}, {{3, 3}, {4, 4}}}, 2001, 600, 1000));
    }

    private GesturePlan swipe(int x, int y, int endX, int endY, Integer duration, boolean natural, long seed) throws Exception {
        return GesturePlan.swipe(x, y, endX, endY, duration, natural, 1, 0, 0, 600, 1000, new Random(seed));
    }
    private interface Throwing { void run() throws Exception; }
    private void expect(Throwing operation) throws Exception {
        try { operation.run(); fail("Expected invalid_argument"); }
        catch (ActionException error) { assertEquals("invalid_argument", error.getCode()); }
    }
}
