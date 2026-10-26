package io.droyagent.app;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.AccessibilityRecovery;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.AndroidUseExecutor;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.DroyAccessibilityService;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Synthetic local controls verify execution without opening an account or sending messages. */
@RunWith(AndroidJUnit4.class)
public final class TaskExecutionOptimizationDeviceTest {
    private static final Cancellation ACTIVE = () -> {};
    private Instrumentation instrumentation;
    private Context context;
    private TestPageActivity activity;
    private AndroidUseExecutor executor;
    private FrameLayout root;
    private TextView button, status;
    private AlertDialog dialog;
    private final AtomicInteger clicks = new AtomicInteger();

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        AccessibilityRecovery.get(context).onAppOpened(false);
        activity = (TestPageActivity) instrumentation.startActivitySync(new Intent(context, TestPageActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        DeviceUi.await("Accessibility unavailable", 15000, () -> DroyAccessibilityService.isConnected(context));
        executor = new AndroidUseExecutor(context);
        instrumentation.runOnMainSync(() -> {
            activity.getWindow().setDecorFitsSystemWindows(false);
            root = new FrameLayout(activity);
            status = label("尚未操作"); status.setId(R.id.test_result);
            FrameLayout.LayoutParams statusLayout = new FrameLayout.LayoutParams(-1, 100); statusLayout.topMargin = 180;
            root.addView(status, statusLayout);
            button = label("练习发送"); button.setId(R.id.test_confirm);
            button.setOnClickListener(v -> { clicks.incrementAndGet(); status.setText("已执行一次"); });
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(360, 140); params.leftMargin = 40; params.topMargin = 500;
            root.addView(button, params);
            activity.setContentView(root);
        });
        settle();
    }

    @After public void tearDown() {
        instrumentation.runOnMainSync(() -> { if (dialog != null) dialog.dismiss(); if (activity != null) activity.finish(); });
    }

    @Test public void aButtonOverlappingTheSystemBottomEdgeStillAcceptsANodeClick() throws Exception {
        placeAtBottomEdge();
        JSONObject basis = observe(), target = target(basis, "练习发送");
        long start = SystemClock.elapsedRealtime();
        JSONObject result = executor.execute("tap", nodeArgs(basis, target).put("afterCondition", condition("已执行一次")), false, ACTIVE);
        assertEquals("matched", result.getString("afterConditionStatus"));
        assertEquals(1, clicks.get());
        evidence("bottom-node", basis, result, SystemClock.elapsedRealtime() - start);
    }

    @Test public void anInteriorCoordinateDoesNotInheritItsLargeParentsBottomOcclusion() throws Exception {
        instrumentation.runOnMainSync(() -> {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) button.getLayoutParams();
            params.width = -1; params.height = -1; params.leftMargin = 0; params.topMargin = 0;
            button.setLayoutParams(params); button.setGravity(Gravity.TOP | Gravity.LEFT);
            button.setPadding(50, 500, 0, 0);
            root.removeView(status);
        }); settle();
        JSONObject basis = observe(), bounds = target(basis, "练习发送").getJSONObject("bounds");
        JSONObject args = new JSONObject().put("snapshotId", basis.getString("snapshotId"))
                .put("x", bounds.getInt("left") + 100).put("y", bounds.getInt("top") + 600);
        JSONObject result = executor.execute("tap", args, false, ACTIVE);
        DeviceUi.await("Coordinate click not delivered", 3000, () -> clicks.get() == 1);
        evidence("interior-coordinate", basis, result, 0);
    }

    @Test public void aRealModalNeverAllowsAnOldButtonToDispatch() throws Exception {
        JSONObject basis = observe();
        instrumentation.runOnMainSync(() -> {
            dialog = new AlertDialog.Builder(activity).setTitle("新的本地弹窗").setMessage("需要先处理弹窗")
                    .setPositiveButton("关闭", (d, which) -> {}).create(); dialog.show();
        }); settle();
        try { executor.execute("tap", nodeArgs(basis, target(basis, "练习发送")), false, ACTIVE); fail("Modal was bypassed"); }
        catch (ActionException expected) { assertEquals("stale_snapshot", expected.getCode()); }
        assertEquals(0, clicks.get());
    }

    @Test public void aNormalNodeClickIgnoresAnIndependentCornerButton() throws Exception {
        AtomicInteger cornerClicks = new AtomicInteger();
        addSiblingButton(false, cornerClicks);
        JSONObject basis = observe();
        JSONObject result = executor.execute("tap", nodeArgs(basis, target(basis, "练习发送"))
                .put("afterCondition", condition("已执行一次")), false, ACTIVE);
        assertEquals("matched", result.getString("afterConditionStatus"));
        assertEquals(1, clicks.get()); assertEquals(0, cornerClicks.get());
        evidence("normal-node-corner", basis, result, result.getLong("afterConditionElapsedMs"));
    }

    @Test public void aNormalNodeClickRejectsAnIndependentControlCoveringItsCenter() throws Exception {
        AtomicInteger coveringClicks = new AtomicInteger();
        addSiblingButton(true, coveringClicks);
        JSONObject basis = observe(); AtomicInteger dispatches = new AtomicInteger();
        try {
            executor.execute("tap", nodeArgs(basis, target(basis, "练习发送")), false, new Cancellation() {
                @Override public void check() {}
                @Override public void dispatched() { dispatches.incrementAndGet(); }
            });
            fail("A separate control covering the target center was bypassed");
        } catch (ActionException expected) {
            assertEquals("stale_snapshot", expected.getCode());
            assertEquals("target_obscured", expected.getReason());
            assertEquals("sibling_control", expected.getDetails().getString("occlusionKind"));
        }
        assertEquals(0, dispatches.get()); assertEquals(0, clicks.get()); assertEquals(0, coveringClicks.get());
    }

    @Test public void aSemanticLongClickIgnoresAnIndependentCornerButton() throws Exception {
        AtomicInteger cornerClicks = new AtomicInteger(), longClicks = new AtomicInteger();
        addSiblingButton(false, cornerClicks);
        instrumentation.runOnMainSync(() -> button.setOnLongClickListener(v -> {
            longClicks.incrementAndGet(); status.setText("已长按一次"); return true;
        })); settle();
        JSONObject basis = observe();
        JSONObject result = executor.execute("node_action", nodeArgs(basis, target(basis, "练习发送"))
                .put("operation", "long_click").put("afterCondition", condition("已长按一次")), false, ACTIVE);
        assertEquals("matched", result.getString("afterConditionStatus"));
        assertEquals(1, longClicks.get()); assertEquals(0, clicks.get()); assertEquals(0, cornerClicks.get());
        evidence("long-node-corner", basis, result, result.getLong("afterConditionElapsedMs"));
    }

    @Test public void aSemanticLongClickRejectsAnIndependentControlCoveringItsCenter() throws Exception {
        AtomicInteger coveringClicks = new AtomicInteger(), longClicks = new AtomicInteger();
        addSiblingButton(true, coveringClicks);
        instrumentation.runOnMainSync(() -> button.setOnLongClickListener(v -> { longClicks.incrementAndGet(); return true; })); settle();
        JSONObject basis = observe(); AtomicInteger dispatches = new AtomicInteger();
        try {
            executor.execute("node_action", nodeArgs(basis, target(basis, "练习发送"))
                    .put("operation", "long_click"), false, new Cancellation() {
                @Override public void check() {}
                @Override public void dispatched() { dispatches.incrementAndGet(); }
            }); fail("Long click bypassed an independent center control");
        } catch (ActionException expected) {
            assertEquals("stale_snapshot", expected.getCode()); assertEquals("target_obscured", expected.getReason());
        }
        assertEquals(0, dispatches.get()); assertEquals(0, longClicks.get()); assertEquals(0, coveringClicks.get());
    }

    @Test public void coordinateHoldUsesOneFingerAndProducesOneLongClick() throws Exception {
        AtomicInteger longClicks = new AtomicInteger(), pointers = new AtomicInteger(), lifts = new AtomicInteger();
        instrumentation.runOnMainSync(() -> {
            button.setOnLongClickListener(v -> { longClicks.incrementAndGet(); status.setText("已长按一次"); return true; });
            button.setOnTouchListener((v, event) -> {
                pointers.accumulateAndGet(event.getPointerCount(), Math::max);
                if (event.getActionMasked() == MotionEvent.ACTION_UP) lifts.incrementAndGet();
                return false;
            });
        }); settle();
        JSONObject basis = observe();
        JSONObject result = executor.execute("tap", pointArgs(basis).put("holdMs", 1000)
                .put("afterCondition", condition("已长按一次")), false, ACTIVE);
        assertEquals(1000, result.getInt("holdMs")); assertEquals(1, result.getInt("pointerCount"));
        assertEquals("matched", result.getString("afterConditionStatus"));
        assertEquals(1, pointers.get()); assertEquals(1, longClicks.get()); assertEquals(0, clicks.get());
        DeviceUi.await("Long hold did not release its pointer", 1000, () -> lifts.get() == 1);
        evidence("coordinate-hold", basis, result, result.getLong("afterConditionElapsedMs"));
    }

    @Test public void coordinateHoldNeverDegradesIntoANodeOrRootTap() throws Exception {
        JSONObject basis = observe(); AtomicInteger dispatches = new AtomicInteger();
        JSONObject[] rejected = {
                nodeArgs(basis, target(basis, "练习发送")).put("holdMs", 800),
                pointArgs(basis).put("holdMs", 800).put("navigation", true),
                pointArgs(basis).put("holdMs", 800).put("channel", "root"),
                pointArgs(basis).put("holdMs", 800).put("channel", "shizuku"),
                pointArgs(basis).put("holdMs", 70)
        };
        for (JSONObject args : rejected) {
            try {
                executor.execute("tap", args, true, new Cancellation() {
                    @Override public void check() {}
                    @Override public void dispatched() { dispatches.incrementAndGet(); }
                }); fail("Invalid hold was dispatched through another action");
            } catch (ActionException expected) { assertEquals("invalid_argument", expected.getCode()); }
        }
        assertEquals(0, dispatches.get()); assertEquals(0, clicks.get());
    }

    @Test public void cancellationDrainsTheAlreadySubmittedHoldWithoutAnotherTouch() throws Exception {
        AtomicInteger lifts = new AtomicInteger(), pointers = new AtomicInteger(), dispatches = new AtomicInteger();
        AtomicLong dispatchedAt = new AtomicLong();
        instrumentation.runOnMainSync(() -> {
            button.setOnLongClickListener(v -> true);
            button.setOnTouchListener((v, event) -> {
                pointers.accumulateAndGet(event.getPointerCount(), Math::max);
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                    lifts.incrementAndGet();
                return false;
            });
        }); settle();
        JSONObject basis = observe();
        try {
            executor.execute("tap", pointArgs(basis).put("holdMs", 1000), false, new Cancellation() {
                @Override public void check() throws InterruptedException {
                    if (dispatchedAt.get() != 0 && SystemClock.elapsedRealtime() - dispatchedAt.get() >= 100)
                        throw new InterruptedException("fixture cancellation");
                }
                @Override public void dispatched() {
                    dispatches.incrementAndGet(); dispatchedAt.set(SystemClock.elapsedRealtime());
                }
            }); fail("Cancelled hold returned success");
        } catch (InterruptedException expected) { }
        DeviceUi.await("Cancelled hold did not finish its existing pointer", 1000, () -> lifts.get() > 0);
        assertEquals(1, dispatches.get()); assertEquals(1, pointers.get()); assertEquals(0, clicks.get());
    }

    @Test public void missingHoldCompletionRemainsAnUnknownOutcome() throws Exception {
        try {
            invokeExecutor("finishSubmittedHold", new Class<?>[]{CompletableFuture.class, boolean[].class, long[].class},
                    new Object[]{new CompletableFuture<Boolean>(), new boolean[]{true}, new long[]{SystemClock.elapsedRealtime()}});
            fail("A missing pointer completion was reported as released");
        } catch (ActionException expected) {
            assertEquals("gesture_release_failed", expected.getCode());
            assertTrue(expected.getDetails().getBoolean("outcomeUnknown"));
            assertTrue(expected.getDetails().getBoolean("dispatched"));
        }
        assertEquals(0, clicks.get());
    }

    @Test public void screenshotBasisKeepsOnlyAppContentAfterABarEventDuringCapture() throws Exception {
        JSONObject before = observe(); Object stamp = captureStamp();
        replayContentEvent(systemBarWindowId());
        JSONObject image = attachBasis(stamp, 0);
        assertEquals("available", image.getString("coordinateBasisStatus"));
        assertEquals("app_content", image.getString("coordinateBasisScope"));
        assertTrue(image.getJSONObject("coordinateBasisChanges").getBoolean("fullGeneration"));
        assertFalse(image.getJSONObject("coordinateBasisChanges").getBoolean("contentGeneration"));
        JSONObject bounds = target(before, "练习发送").getJSONObject("bounds");
        executor.execute("tap", new JSONObject().put("snapshotId", image.getString("snapshotId"))
                .put("x", (bounds.getInt("left") + bounds.getInt("right")) / 2)
                .put("y", (bounds.getInt("top") + bounds.getInt("bottom")) / 2), false, ACTIVE);
        DeviceUi.await("App-region screenshot coordinate was not delivered", 3000, () -> clicks.get() == 1);
        evidence("screenshot-app-region", before, image, 0);
    }

    @Test public void screenshotAndNodeObservationShareOneActionableBasis() throws Exception {
        Object stamp = preparedStamp(); replayContentEvent(systemBarWindowId());
        JSONObject image = attachBasis(stamp, 0), observation = image.getJSONObject("afterObservation");
        assertEquals("available", image.getString("coordinateBasisStatus"));
        assertEquals(field(stamp, "observationId"), image.getString("snapshotId"));
        assertEquals(image.getString("snapshotId"), observation.getString("snapshotId"));
        assertEquals(image.getString("coordinateBasisScope"), observation.getString("coordinateBasisScope"));
        assertEquals(image.getJSONObject("coordinateBounds").toString(), observation.getJSONObject("coordinateBounds").toString());
        JSONObject result = executor.execute("tap", nodeArgs(observation, target(observation, "练习发送"))
                .put("afterCondition", condition("已执行一次")), false, ACTIVE);
        assertEquals("matched", result.getString("afterConditionStatus")); assertEquals(1, clicks.get());
        evidence("screenshot-node-shared-basis", observation, result, 0);
    }

    @Test public void contentChangesNeverExposeAnAssociatedActionableObservation() throws Exception {
        Object stamp = preparedStamp();
        instrumentation.runOnMainSync(() -> status.setText("捕获期间页面内容改变")); settle();
        JSONObject image = attachBasis(stamp, 0);
        assertEquals("unavailable", image.getString("coordinateBasisStatus"));
        assertTrue(image.getJSONObject("coordinateBasisChanges").getBoolean("contentGeneration"));
        assertFalse(image.has("snapshotId")); assertFalse(image.has("afterObservation"));
    }

    @Test public void replacedScreenshotObservationIsNotReboundToTheNewNodes() throws Exception {
        Object stamp = preparedStamp(); JSONObject newer = observe();
        assertNotEquals(field(stamp, "observationId"), newer.getString("snapshotId"));
        JSONObject image = attachBasis(stamp, 0);
        assertEquals("unavailable", image.getString("coordinateBasisStatus"));
        assertTrue(image.getJSONObject("coordinateBasisChanges").getBoolean("observationBasis"));
        assertFalse(image.has("snapshotId")); assertFalse(image.has("afterObservation"));
        executor.execute("tap", nodeArgs(newer, target(newer, "练习发送")), false, ACTIVE);
        assertEquals(1, clicks.get());
    }

    @Test public void attachingScreenshotNodesNeverRenewsTheirCoordinateClock() throws Exception {
        Object stamp = preparedStamp();
        Object active = field(executor, "snapshot"); long created = (Long) field(active, "createdMs");
        long captureStarted = (Long) field(stamp, "createdMs");
        assertTrue(created <= captureStarted);
        SystemClock.sleep(250);
        JSONObject image = attachBasis(stamp, 0);
        assertEquals("available", image.getString("coordinateBasisStatus"));
        assertEquals(field(stamp, "observationId"), image.getString("snapshotId"));
        assertSame(active, field(executor, "snapshot"));
        assertEquals(created, ((Long) field(field(executor, "snapshot"), "createdMs")).longValue());
    }

    @Test public void anAppOnlyScreenshotBasisRejectsSystemBarCoordinates() throws Exception {
        Object stamp = captureStamp(); int bar = systemBarWindowId();
        replayContentEvent(bar); JSONObject image = attachBasis(stamp, 0);
        assertEquals("app_content", image.getString("coordinateBasisScope"));
        Rect barBounds = systemBarBounds(bar);
        rejectScreenshotPath("tap", new JSONObject().put("snapshotId", image.getString("snapshotId"))
                .put("x", barBounds.centerX()).put("y", barBounds.centerY()));
    }

    @Test public void anAppOnlyScreenshotBasisRejectsAGestureCrossingItsBounds() throws Exception {
        JSONObject before = observe(); Object stamp = captureStamp(); int bar = systemBarWindowId();
        replayContentEvent(bar); JSONObject image = attachBasis(stamp, 0);
        JSONObject target = target(before, "练习发送").getJSONObject("bounds"); Rect barBounds = systemBarBounds(bar);
        rejectScreenshotPath("swipe", new JSONObject().put("snapshotId", image.getString("snapshotId"))
                .put("startX", (target.getInt("left") + target.getInt("right")) / 2)
                .put("startY", (target.getInt("top") + target.getInt("bottom")) / 2)
                .put("endX", barBounds.centerX()).put("endY", barBounds.centerY()));
    }

    @Test public void actualAppContentChangesInvalidateTheScreenshotBasis() throws Exception {
        Object stamp = captureStamp(); AtomicInteger window = new AtomicInteger();
        instrumentation.runOnMainSync(() -> {
            AccessibilityNodeInfo node = button.createAccessibilityNodeInfo();
            try { window.set(node.getWindowId()); } finally { node.recycle(); }
        });
        replayContentEvent(window.get()); JSONObject image = attachBasis(stamp, 0);
        assertEquals("unavailable", image.getString("coordinateBasisStatus"));
        assertEquals("screen_changed_during_capture", image.getString("coordinateBasisReason"));
        assertTrue(image.getJSONObject("coordinateBasisChanges").getBoolean("contentGeneration"));
        assertFalse(image.has("snapshotId"));
    }

    @Test public void aModalOpeningDuringCaptureInvalidatesItsWindowBasis() throws Exception {
        Object stamp = captureStamp();
        instrumentation.runOnMainSync(() -> {
            dialog = new AlertDialog.Builder(activity).setTitle("捕获期间出现的弹窗")
                    .setPositiveButton("关闭", (d, which) -> {}).create(); dialog.show();
        }); settle();
        JSONObject image = attachBasis(stamp, 0);
        assertEquals("unavailable", image.getString("coordinateBasisStatus"));
        JSONObject changes = image.getJSONObject("coordinateBasisChanges");
        assertTrue(changes.getBoolean("window") || changes.getBoolean("hardGeneration"));
        assertFalse(image.has("snapshotId"));
    }

    @Test public void mismatchedScreenshotGeometryProvidesABranchDiagnostic() throws Exception {
        Object stamp = captureStamp(); JSONObject image = attachBasis(stamp, -1);
        assertEquals("unavailable", image.getString("coordinateBasisStatus"));
        assertEquals("display_geometry_mismatch", image.getString("coordinateBasisReason"));
        assertTrue(image.getJSONObject("coordinateBasisChanges").getBoolean("displayGeometry"));
    }

    @Test public void anAsynchronousSelectionReturnsItsFinalStateWithoutASecondClick() throws Exception {
        instrumentation.runOnMainSync(() -> button.setOnClickListener(v -> {
            clicks.incrementAndGet();
            new Handler(Looper.getMainLooper()).postDelayed(() -> { button.setSelected(true); status.setText("照片已选中"); }, 300);
        })); settle();
        JSONObject basis = observe(); long start = SystemClock.elapsedRealtime();
        JSONObject result = executor.execute("tap", nodeArgs(basis, target(basis, "练习发送"))
                .put("afterCondition", condition("照片已选中")).put("afterTimeoutMs", 3000), false, ACTIVE);
        assertEquals("matched", result.getString("afterConditionStatus"));
        assertTrue(target(result.getJSONObject("afterObservation"), "练习发送").getBoolean("selected"));
        assertEquals(1, clicks.get()); assertFalse(result.getBoolean("actionRepeated"));
        evidence("delayed-selection", basis, result, SystemClock.elapsedRealtime() - start);
    }

    @Test public void delayedNavigationReturnsTheNewPageInTheSameCall() throws Exception {
        instrumentation.runOnMainSync(() -> button.setOnClickListener(v -> {
            clicks.incrementAndGet();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                root.removeAllViews(); root.addView(label("已进入照片选择页"), new FrameLayout.LayoutParams(-1, 500));
            }, 300);
        })); settle();
        JSONObject basis = observe();
        JSONObject result = executor.execute("tap", nodeArgs(basis, target(basis, "练习发送"))
                .put("afterCondition", condition("已进入照片选择页")).put("afterTimeoutMs", 3000), false, ACTIVE);
        assertEquals("matched", result.getString("afterConditionStatus"));
        target(result.getJSONObject("afterObservation"), "已进入照片选择页");
        assertEquals(1, clicks.get());
        evidence("delayed-navigation", basis, result, result.getLong("afterConditionElapsedMs"));
    }

    @Test public void timeoutKeepsTheActionReceiptAndNeverRepeatsIt() throws Exception {
        JSONObject basis = observe();
        JSONObject result = executor.execute("tap", nodeArgs(basis, target(basis, "练习发送"))
                .put("afterCondition", condition("永远不会出现的目标")).put("afterTimeoutMs", 100), false, ACTIVE);
        assertEquals("action_completed", result.getString("status"));
        assertEquals("timed_out", result.getString("afterConditionStatus"));
        assertTrue(result.has("afterObservation")); assertFalse(result.getBoolean("actionRepeated")); assertEquals(1, clicks.get());
    }

    @Test public void invalidCompletionConditionIsRejectedBeforeDispatch() throws Exception {
        JSONObject basis = observe();
        try {
            executor.execute("tap", nodeArgs(basis, target(basis, "练习发送"))
                    .put("afterCondition", new JSONObject().put("text", 7)), false, ACTIVE);
            fail("Invalid completion condition dispatched");
        } catch (ActionException expected) { assertEquals("invalid_argument", expected.getCode()); }
        assertEquals(0, clicks.get());
    }

    @Test public void sequenceBasisDoesNotReplaceOrConsumeTheObservedReferences() throws Exception {
        JSONObject basis = observe(), internal = executor.sequenceBasis(basis.getString("snapshotId"), ACTIVE);
        assertEquals(basis.getString("snapshotId"), internal.getString("snapshotId"));
        assertTrue(target(internal, "练习发送").has("sequenceIdentity"));
        assertFalse(target(basis, "练习发送").has("sequenceIdentity"));
        executor.execute("tap", nodeArgs(basis, target(basis, "练习发送")), false, ACTIVE);
        assertEquals(1, clicks.get());
    }

    private DroyAccessibilityService service() throws Exception {
        Method method = DroyAccessibilityService.class.getDeclaredMethod("current", Context.class); method.setAccessible(true);
        return (DroyAccessibilityService) method.invoke(null, context);
    }
    private Object preparedStamp() throws Exception {
        AtomicReference<Object> result = new AtomicReference<>(); AtomicReference<Exception> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(invokeExecutor("prepareScreenshotStamp", new Class<?>[]{String.class, Cancellation.class}, new Object[]{null, ACTIVE})); }
            catch (Exception error) { failure.set(error); }
        });
        if (failure.get() != null) throw failure.get();
        return result.get();
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private Object captureStamp() throws Exception {
        AtomicReference<Object> result = new AtomicReference<>(); AtomicReference<Exception> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(invokeExecutor("captureStamp", new Class<?>[]{String.class}, new Object[]{null})); }
            catch (Exception error) { failure.set(error); }
        });
        if (failure.get() != null) throw failure.get();
        return result.get();
    }
    private JSONObject attachBasis(Object stamp, int widthDelta) throws Exception {
        Rect screen = activity.getWindowManager().getMaximumWindowMetrics().getBounds();
        return (JSONObject) invokeExecutor("attachScreenshotBasis",
                new Class<?>[]{JSONObject.class, int.class, int.class, stamp.getClass(), Cancellation.class},
                new Object[]{new JSONObject().put("status", "captured").put("channel", "accessibility"),
                        screen.width() + widthDelta, screen.height(), stamp, ACTIVE});
    }
    private Object invokeExecutor(String name, Class<?>[] types, Object[] arguments) throws Exception {
        Method method = AndroidUseExecutor.class.getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(executor, arguments); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw error;
        }
    }
    private void replayContentEvent(int windowId) throws Exception {
        DroyAccessibilityService service = service();
        Method record = DroyAccessibilityService.class.getDeclaredMethod("recordUiChange", int.class, int.class); record.setAccessible(true);
        instrumentation.runOnMainSync(() -> {
            try { record.invoke(service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, windowId); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
    }
    private int systemBarWindowId() throws Exception {
        DroyAccessibilityService service = service();
        Method method = DroyAccessibilityService.class.getDeclaredMethod("coordinateContentBounds"); method.setAccessible(true);
        Rect content = (Rect) method.invoke(service);
        List<AccessibilityWindowInfo> windows = service.getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                if (window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM && !bounds.isEmpty() && !Rect.intersects(bounds, content))
                    return window.getId();
            }
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
        throw new AssertionError("Fixture requires a real system-bar window");
    }
    private Rect systemBarBounds(int id) throws Exception {
        List<AccessibilityWindowInfo> windows = service().getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) if (window.getId() == id) {
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds); return bounds;
            }
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
        throw new AssertionError("System bar disappeared");
    }
    private void rejectScreenshotPath(String action, JSONObject args) throws Exception {
        AtomicInteger dispatches = new AtomicInteger();
        try {
            executor.execute(action, args, false, new Cancellation() {
                @Override public void check() {}
                @Override public void dispatched() { dispatches.incrementAndGet(); }
            }); fail("App-region screenshot authorized an outside path");
        } catch (ActionException expected) { assertEquals("stale_snapshot", expected.getCode()); }
        assertEquals(0, dispatches.get()); assertEquals(0, clicks.get());
    }

    private TextView label(String text) {
        TextView view = new TextView(activity); view.setText(text); view.setTextSize(20);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        view.setBackgroundColor(0xffeeeeee); return view;
    }
    private void addSiblingButton(boolean coverCenter, AtomicInteger counter) {
        instrumentation.runOnMainSync(() -> {
            FrameLayout.LayoutParams main = (FrameLayout.LayoutParams) button.getLayoutParams();
            main.width = 700; main.height = 800; button.setLayoutParams(main);
            TextView corner = label(coverCenter ? "覆盖主体的操作" : "右下角独立操作");
            corner.setOnClickListener(v -> counter.incrementAndGet());
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(90, 90);
            params.leftMargin = main.leftMargin + (coverCenter ? main.width / 2 - 45 : main.width - 90);
            params.topMargin = main.topMargin + (coverCenter ? main.height / 2 - 45 : main.height - 90);
            root.addView(corner, params);
        }); settle();
    }
    private void placeAtBottomEdge() {
        instrumentation.runOnMainSync(() -> {
            var metrics = activity.getWindowManager().getMaximumWindowMetrics();
            Insets bars = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int[] offset = new int[2]; root.getLocationOnScreen(offset);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) button.getLayoutParams();
            params.topMargin = metrics.getBounds().bottom - bars.bottom - offset[1] - params.height + 7;
            button.setLayoutParams(params);
        }); settle();
    }
    private JSONObject observe() throws Exception { return executor.execute("observe", new JSONObject(), false, ACTIVE); }
    private static JSONObject condition(String text) throws Exception { return new JSONObject().put("text", text); }
    private static JSONObject pointArgs(JSONObject basis) throws Exception {
        JSONObject bounds = target(basis, "练习发送").getJSONObject("bounds");
        return new JSONObject().put("snapshotId", basis.getString("snapshotId"))
                .put("x", (bounds.getInt("left") + bounds.getInt("right")) / 2)
                .put("y", (bounds.getInt("top") + bounds.getInt("bottom")) / 2);
    }
    private static JSONObject nodeArgs(JSONObject basis, JSONObject node) throws Exception {
        return new JSONObject().put("snapshotId", basis.getString("snapshotId")).put("nodeId", node.getString("nodeId"));
    }
    private static JSONObject target(JSONObject basis, String text) throws Exception {
        JSONArray nodes = basis.getJSONArray("nodes");
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject node = nodes.getJSONObject(index);
            if (text.equals(node.optString("text"))) return node;
        }
        throw new AssertionError("Fixture target missing: " + text);
    }
    private void evidence(String name, JSONObject before, JSONObject result, long elapsed) throws Exception {
        File directory = new File(context.getExternalFilesDir(null), "task-execution-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        JSONObject metrics = new JSONObject().put("elapsedMs", elapsed).put("beforeObservationMs", before.optLong("observationMs"))
                .put("clicks", clicks.get()).put("result", result);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".json"))) {
            output.write(metrics.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        Bitmap screen = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
        if (screen != null) try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(screen.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { if (screen != null) screen.recycle(); }
    }
    private void settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(350); instrumentation.waitForIdleSync(); }
}
