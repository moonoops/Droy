package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.AccessibilityRecovery;
import io.droyagent.androiduse.AndroidUseExecutor;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.DroyAccessibilityService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Captures real MotionEvents; does not contact apps, accounts or a model. */
@RunWith(AndroidJUnit4.class)
public final class AdvancedGestureDeviceTest {
    private static final Cancellation ACTIVE = () -> {};
    private Instrumentation instrumentation;
    private Context context;
    private AndroidUseExecutor executor;
    private TestPageActivity activity;
    private TouchSurface surface;
    private Rect area;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        executor = new AndroidUseExecutor(context);
        activity = (TestPageActivity) instrumentation.startActivitySync(new Intent(context, TestPageActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AccessibilityRecovery.get(context).onAppOpened(false);
        DeviceUi.await("Accessibility did not connect", 15000, () -> DroyAccessibilityService.isConnected(context));
        instrumentation.runOnMainSync(() -> {
            surface = new TouchSurface(activity);
            activity.setContentView(surface);
        });
        instrumentation.waitForIdleSync();
        DeviceUi.await("Touch surface not laid out", 5000, () -> surface.getWidth() > 100 && surface.getHeight() > 100);
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).waitForIdle(200, 5000);
        int[] position = new int[2];
        instrumentation.runOnMainSync(() -> surface.getLocationOnScreen(position));
        area = new Rect(position[0], position[1], position[0] + surface.getWidth(), position[1] + surface.getHeight());
    }

    @After public void tearDown() {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
    }

    @Test public void naturalSwipeHasContinuousTouchVariableSpeedAndExactEndpoints() throws Exception {
        int x = area.centerX(), start = area.top + area.height() * 4 / 5, end = area.top + area.height() / 5;
        JSONObject args = swipe(x, start, x, end).put("motion", "natural").put("durationMs", 700);
        JSONObject result = executor.execute("swipe", args, false, ACTIVE);
        assertEquals("natural", result.getString("motion"));
        assertEquals(700, result.getInt("plannedDurationMs"));
        assertFalse(result.getBoolean("goalVerified"));
        assertNotEquals(args.getString("snapshotId"), result.getJSONObject("afterObservation").getString("snapshotId"));
        List<Sample> samples = samples();
        assertLifecycle(samples, 1);
        Sample first = samples.get(0), last = samples.get(samples.size() - 1);
        assertEquals(x, first.x[0], 2); assertEquals(start, first.y[0], 2);
        assertEquals(x, last.x[0], 2); assertEquals(end, last.y[0], 2);
        for (Sample sample : samples) assertTrue(area.contains(Math.round(sample.x[0]), Math.round(sample.y[0])));
        double early = speed(samples, start, end, .02, .18);
        double middle = speed(samples, start, end, .4, .6);
        double late = speed(samples, start, end, .82, .98);
        assertTrue("Middle speed must exceed initial speed: " + middle + " / " + early, middle > early * 1.15);
        assertTrue("Middle speed must exceed final speed: " + middle + " / " + late, middle > late * 1.15);
        evidence("natural-swipe", samples);
    }

    @Test public void twoPointersPinchTogetherWithoutLiftingBetweenSegments() throws Exception {
        int y = area.centerY();
        int leftStart = area.left + area.width() * 2 / 5, rightStart = area.left + area.width() * 3 / 5;
        int leftEnd = area.left + area.width() / 5, rightEnd = area.left + area.width() * 4 / 5;
        JSONObject result = executor.execute("gesture", new JSONObject().put("snapshotId", observe().getString("snapshotId"))
                .put("pointers", new JSONArray().put(pointer(leftStart, y, leftEnd, y)).put(pointer(rightStart, y, rightEnd, y)))
                .put("durationMs", 500), false, ACTIVE);
        assertEquals(2, result.getInt("pointerCount"));
        List<Sample> samples = samples();
        assertLifecycle(samples, 2);
        double firstSpan = -1, lastSpan = -1;
        for (Sample sample : samples) if (sample.x.length == 2) {
            double span = Math.abs(sample.x[1] - sample.x[0]);
            if (firstSpan < 0) firstSpan = span;
            lastSpan = span;
        }
        assertEquals(rightStart - leftStart, firstSpan, 3);
        assertEquals(rightEnd - leftEnd, lastSpan, 3);
        evidence("two-pointer-pinch", samples);
    }

    @Test public void fivePointersRemainSimultaneousAndReleaseExactlyOnce() throws Exception {
        JSONArray pointers = new JSONArray();
        for (int p = 0; p < 5; p++) {
            int x = area.left + area.width() * (2 + p * 2) / 12;
            pointers.put(pointer(x, area.top + area.height() * 3 / 5, x, area.top + area.height() * 2 / 5));
        }
        JSONObject result = executor.execute("gesture", new JSONObject().put("snapshotId", observe().getString("snapshotId"))
                .put("pointers", pointers).put("durationMs", 400), false, ACTIVE);
        assertEquals(5, result.getInt("pointerCount"));
        List<Sample> samples = samples();
        assertLifecycle(samples, 5);
        assertTrue(samples.stream().anyMatch(sample -> sample.x.length == 5));
        evidence("five-pointer-drag", samples);
    }

    @Test public void twoPointersRotateThroughSynchronizedKeyframes() throws Exception {
        int radius = area.width() / 4;
        JSONArray pointers = new JSONArray();
        for (int p = 0; p < 2; p++) {
            JSONArray points = new JSONArray();
            for (int i = 0; i < 5; i++) {
                double angle = Math.PI * p + Math.PI / 2 * i / 4;
                points.put(new JSONObject().put("x", area.centerX() + (int) Math.round(radius * Math.cos(angle)))
                        .put("y", area.centerY() + (int) Math.round(radius * Math.sin(angle))));
            }
            pointers.put(new JSONObject().put("points", points));
        }
        executor.execute("gesture", new JSONObject().put("snapshotId", observe().getString("snapshotId"))
                .put("pointers", pointers).put("durationMs", 600), false, ACTIVE);
        List<Sample> samples = samples(); assertLifecycle(samples, 2);
        Sample lastTwo = null;
        for (Sample sample : samples) if (sample.x.length == 2) {
            lastTwo = sample;
            assertEquals(2 * radius, Math.hypot(sample.x[1] - sample.x[0], sample.y[1] - sample.y[0]), radius * .05 + 3);
        }
        assertNotNull(lastTwo);
        assertEquals(area.centerX(), lastTwo.x[0], 3);
        assertEquals(area.centerX(), lastTwo.x[1], 3);
        assertEquals(2 * radius, Math.abs(lastTwo.y[1] - lastTwo.y[0]), 3);
        evidence("two-pointer-rotation", samples);
    }

    @Test public void cancelledNaturalSwipeReleasesWithoutReachingTheTargetOrReplaying() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        surface.cancelAfterMove = cancelled;
        int x = area.centerX(), start = area.top + area.height() * 4 / 5, end = area.top + area.height() / 5;
        try {
            executor.execute("swipe", swipe(x, start, x, end).put("motion", "natural").put("durationMs", 2000), false,
                    () -> { if (cancelled.get()) throw new InterruptedException("fixture cancellation"); });
            fail("Expected cancellation");
        } catch (InterruptedException expected) { }
        instrumentation.waitForIdleSync();
        List<Sample> samples = samples();
        assertLifecycle(samples, 1);
        assertTrue("Cancellation must not jump to the target", samples.get(samples.size() - 1).y[0] > end + (start - end) / 2);
        int count = samples.size();
        SystemClock.sleep(200);
        assertEquals(count, samples().size());
        surface.cancelAfterMove = null;
        surface.events.clear();
        executor.execute("swipe", swipe(x, start, x, end).put("durationMs", 200), false, ACTIVE);
        assertLifecycle(samples(), 1);
    }

    @Test public void rootPreferenceUsesAvailableNaturalGestureAndInvalidPointersAreRejected() throws Exception {
        JSONObject result = executor.execute("swipe", swipe(area.centerX(), area.centerY(), area.centerX(), area.centerY() - 100)
                .put("motion", "natural").put("channel", "root"), false, ACTIVE);
        assertEquals("accessibility", result.getString("channel"));
        assertLifecycle(samples(), 1);
        surface.events.clear();
        try {
            executor.execute("gesture", new JSONObject().put("snapshotId", observe().getString("snapshotId"))
                    .put("pointers", new JSONArray().put(pointer(10, 10, 20, 20))), false, ACTIVE);
            fail("Expected pointer validation");
        } catch (ActionException expected) { assertEquals("invalid_argument", expected.getCode()); }
        assertTrue(samples().isEmpty());
    }

    @Test public void cancellationWhileMainIsDispatchingStillReleasesRegisteredPointers() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch workerObserved = new CountDownLatch(1);
        Cancellation duringDispatch = new Cancellation() {
            @Override public void check() throws InterruptedException {
                if (cancelled.get() && Looper.myLooper() != Looper.getMainLooper()) {
                    workerObserved.countDown();
                    throw new InterruptedException("fixture cancelled while dispatch callback is running");
                }
            }
            @Override public void dispatched() throws InterruptedException {
                // Keep the already-running main callback occupied until its caller
                // stops waiting. Registration occurs after this callback resumes.
                cancelled.set(true);
                if (!workerObserved.await(3, TimeUnit.SECONDS)) throw new AssertionError("Worker did not observe cancellation");
                SystemClock.sleep(100);
            }
        };
        int x = area.centerX(), start = area.top + area.height() * 4 / 5, end = area.top + area.height() / 5;
        try {
            executor.execute("swipe", swipe(x, start, x, end).put("motion", "natural").put("durationMs", 2000), false, duringDispatch);
            fail("Expected cancellation");
        } catch (InterruptedException expected) { }
        instrumentation.waitForIdleSync();
        assertLifecycle(samples(), 1);
        assertTrue(samples().get(samples().size() - 1).y[0] > end + (start - end) / 2);
    }

    private void evidence(String name, List<Sample> samples) throws Exception {
        File directory = new File(context.getExternalFilesDir(null), "android-use-interaction-evidence");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot create fixture evidence directory");
        Bitmap bitmap = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
        assertNotNull(bitmap);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
        JSONArray events = new JSONArray();
        for (Sample sample : samples) {
            JSONArray points = new JSONArray();
            for (int p = 0; p < sample.x.length; p++) points.put(new JSONObject().put("x", sample.x[p]).put("y", sample.y[p]));
            events.put(new JSONObject().put("action", sample.action).put("timeMs", sample.time - samples.get(0).time).put("points", points));
        }
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".json"))) {
            output.write(events.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private JSONObject observe() throws Exception { return executor.execute("observe", new JSONObject(), false, ACTIVE); }
    private JSONObject swipe(int x, int y, int endX, int endY) throws Exception {
        return new JSONObject().put("snapshotId", observe().getString("snapshotId"))
                .put("startX", x).put("startY", y).put("endX", endX).put("endY", endY);
    }
    private JSONObject pointer(int x, int y, int endX, int endY) throws Exception {
        return new JSONObject().put("points", new JSONArray().put(new JSONObject().put("x", x).put("y", y))
                .put(new JSONObject().put("x", endX).put("y", endY)));
    }
    private List<Sample> samples() { synchronized (surface.events) { return new ArrayList<>(surface.events); } }
    private void assertLifecycle(List<Sample> samples, int pointers) {
        assertFalse(samples.isEmpty());
        int down = 0, up = 0, pointerDown = 0, pointerUp = 0;
        for (Sample sample : samples) {
            if (sample.action == MotionEvent.ACTION_DOWN) down++;
            if (sample.action == MotionEvent.ACTION_UP) up++;
            if (sample.action == MotionEvent.ACTION_POINTER_DOWN) pointerDown++;
            if (sample.action == MotionEvent.ACTION_POINTER_UP) pointerUp++;
            assertNotEquals("Unexpected cancellation", MotionEvent.ACTION_CANCEL, sample.action);
            assertTrue(sample.x.length <= pointers);
        }
        assertEquals(1, down); assertEquals(1, up);
        assertEquals(pointers - 1, pointerDown); assertEquals(pointers - 1, pointerUp);
    }
    private double speed(List<Sample> samples, int start, int end, double min, double max) {
        Sample first = null, last = null;
        for (Sample sample : samples) {
            double progress = (start - sample.y[0]) / (start - end);
            if (progress >= min && progress <= max) { if (first == null) first = sample; last = sample; }
        }
        assertNotNull("No motion samples in speed band", first);
        assertNotNull(last); assertTrue(last.time > first.time);
        return Math.abs(last.y[0] - first.y[0]) / (last.time - first.time);
    }

    private static final class Sample {
        final int action;
        final long time;
        final float[] x, y;
        Sample(MotionEvent event, int[] origin) {
            action = event.getActionMasked(); time = event.getEventTime();
            x = new float[event.getPointerCount()]; y = new float[x.length];
            for (int p = 0; p < x.length; p++) { x[p] = event.getX(p) + origin[0]; y[p] = event.getY(p) + origin[1]; }
        }
    }
    private static final class TouchSurface extends View {
        final List<Sample> events = Collections.synchronizedList(new ArrayList<>());
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        volatile AtomicBoolean cancelAfterMove;
        TouchSurface(Context context) { super(context); setContentDescription("手势测试区域"); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.WHITE); paint.setColor(Color.DKGRAY); paint.setTextSize(40);
            canvas.drawText("手势测试：轨迹与多指触摸", 24, 60, paint);
            paint.setColor(Color.BLUE); paint.setStrokeWidth(5);
            synchronized (events) { for (Sample sample : events) for (int p = 0; p < sample.x.length; p++) {
                int[] origin = new int[2]; getLocationOnScreen(origin);
                canvas.drawCircle(sample.x[p] - origin[0], sample.y[p] - origin[1], 3, paint);
            } }
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            int[] origin = new int[2]; getLocationOnScreen(origin);
            events.add(new Sample(event, origin)); invalidate();
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && cancelAfterMove != null) cancelAfterMove.set(true);
            return true;
        }
    }
}
