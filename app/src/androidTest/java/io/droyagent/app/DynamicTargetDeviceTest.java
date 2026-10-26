package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.AndroidUseExecutor;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.AccessibilityRecovery;
import io.droyagent.androiduse.DroyAccessibilityService;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Dynamic coupon controls on an offline fixture: no account, purchase or model call. */
@RunWith(AndroidJUnit4.class)
public final class DynamicTargetDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private TestPageActivity activity;
    private AndroidUseExecutor executor;
    private LinearLayout button, root;
    private TextView timer, animation;
    private final AtomicInteger clicks = new AtomicInteger();
    private static final Cancellation ACTIVE = () -> {};

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        AccessibilityRecovery.get(context).onAppOpened(false);
        activity = (TestPageActivity) instrumentation.startActivitySync(new Intent(context, TestPageActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        DeviceUi.await("Accessibility did not reconnect", 15000, () -> DroyAccessibilityService.isConnected(context));
        executor = new AndroidUseExecutor(context);
        instrumentation.runOnMainSync(() -> {
            root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL);
            root.setFocusableInTouchMode(true);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(32, bars.top + 32, 32, bars.bottom + 32); return insets;
            });
            animation = new TextView(activity); animation.setText("视频字幕：第一帧");
            root.addView(animation, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 250));
            button = new LinearLayout(activity); button.setId(R.id.test_confirm);
            button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            button.setOnClickListener(view -> clicks.incrementAndGet());
            timer = new TextView(activity); timer.setTextSize(17);
            timer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            timer.setText("抢补贴再减300元｜00:18:47.6后开抢");
            button.addView(timer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 120));
            root.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150));
            activity.setContentView(root); root.requestApplyInsets(); root.requestFocus();
        });
        settle();
    }

    @After public void tearDown() {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
    }

    @Test public void countdownTickKeepsOriginalButton() throws Exception {
        JSONObject before = observe();
        changeTimer("抢补贴再减300元｜00:18:39.0后开抢");
        executor.execute("tap", nodeArgs(before), false, ACTIVE);
        assertEquals(1, clicks.get());
    }

    @Test public void unrelatedAnimationKeepsCoordinateTarget() throws Exception {
        JSONObject before = observe();
        instrumentation.runOnMainSync(() -> {
            animation.setBackgroundColor(Color.BLUE);
            animation.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }); settle();
        executor.execute("tap", coordinateArgs(before), false, ACTIVE);
        assertEquals(1, clicks.get());
    }

    @Test public void countdownTickKeepsCoordinateTarget() throws Exception {
        JSONObject before = observe();
        changeTimer("抢补贴再减300元｜00:11:11.1后开抢");
        executor.execute("tap", coordinateArgs(before), false, ACTIVE);
        assertEquals(1, clicks.get());
    }

    @Test public void openingStateRequiresNewObservationThenCanClick() throws Exception {
        JSONObject before = observe();
        changeTimer("抢券再减300元｜抢券进行中");
        reject(nodeArgs(before));
        executor.execute("tap", nodeArgs(observe()), false, ACTIVE);
        assertEquals(1, clicks.get());
    }

    @Test public void changedAmountStillRejectsCoordinates() throws Exception {
        JSONObject before = observe();
        changeTimer("抢补贴再减500元｜00:18:39.0后开抢");
        reject(coordinateArgs(before));
    }

    @Test public void amountOutsideTheUnchangedButtonStillRejectsCoordinates() throws Exception {
        instrumentation.runOnMainSync(() -> animation.setText("实付 ¥4999")); settle();
        JSONObject before = observe();
        instrumentation.runOnMainSync(() -> animation.setText("实付 ¥5999")); settle();
        reject(coordinateArgs(before));
    }

    @Test public void newInteractiveChildInsideSearchContainerRejectsOldCoordinates() throws Exception {
        instrumentation.runOnMainSync(() -> button.setContentDescription("搜索栏")); settle();
        JSONObject before = observe();
        instrumentation.runOnMainSync(() -> {
            timer.setText("清除"); timer.setOnClickListener(view -> clicks.addAndGet(100));
        }); settle();
        reject(coordinateArgs(before));
    }

    @Test public void movedTargetStillRejectsCoordinates() throws Exception {
        JSONObject before = observe();
        instrumentation.runOnMainSync(() -> button.setTranslationY(40)); settle();
        reject(coordinateArgs(before));
    }

    @Test public void disabledTargetStillRejectsCoordinates() throws Exception {
        JSONObject before = observe();
        instrumentation.runOnMainSync(() -> button.setEnabled(false)); settle();
        reject(coordinateArgs(before));
    }

    @Test public void coveringControlStillRejectsCoordinates() throws Exception {
        JSONObject before = observe();
        instrumentation.runOnMainSync(() -> {
            TextView cover = new TextView(activity); cover.setText("新出现的覆盖按钮");
            cover.setOnClickListener(view -> clicks.addAndGet(100));
            root.addView(cover, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150));
            cover.setTranslationY(-150);
        });
        settle();
        reject(coordinateArgs(before));
    }

    @Test public void cancelledCoordinateActionNeverDispatches() throws Exception {
        JSONObject before = observe();
        AtomicInteger dispatched = new AtomicInteger();
        try {
            executor.execute("tap", coordinateArgs(before), false, new Cancellation() {
                @Override public void check() throws InterruptedException { throw new InterruptedException("cancelled fixture"); }
                @Override public void dispatched() { dispatched.incrementAndGet(); }
            });
            fail("Cancelled action ran");
        } catch (InterruptedException expected) { }
        assertEquals(0, dispatched.get()); assertEquals(0, clicks.get());
    }

    private void settle() {
        instrumentation.waitForIdleSync(); SystemClock.sleep(350); instrumentation.waitForIdleSync();
    }
    private void changeTimer(String text) { instrumentation.runOnMainSync(() -> timer.setText(text)); settle(); }
    private JSONObject observe() throws Exception { return executor.execute("observe", new JSONObject(), false, ACTIVE); }
    private JSONObject target(JSONObject observation) throws Exception {
        JSONArray nodes = observation.getJSONArray("nodes");
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject node = nodes.getJSONObject(index);
            if (node.optString("resourceId").endsWith(":id/test_confirm")) return node;
        }
        throw new AssertionError("Missing coupon fixture");
    }
    private JSONObject nodeArgs(JSONObject observation) throws Exception {
        return new JSONObject().put("snapshotId", observation.getString("snapshotId"))
                .put("nodeId", target(observation).getString("nodeId"));
    }
    private JSONObject coordinateArgs(JSONObject observation) throws Exception {
        JSONObject bounds = target(observation).getJSONObject("bounds");
        return new JSONObject().put("snapshotId", observation.getString("snapshotId"))
                .put("x", (bounds.getInt("left") + bounds.getInt("right")) / 2)
                .put("y", (bounds.getInt("top") + bounds.getInt("bottom")) / 2);
    }
    private void reject(JSONObject args) throws Exception {
        AtomicInteger dispatched = new AtomicInteger();
        try {
            executor.execute("tap", args, false, new Cancellation() {
                @Override public void check() {}
                @Override public void dispatched() { dispatched.incrementAndGet(); }
            });
            fail("Changed target was accepted");
        } catch (ActionException expected) { assertEquals("stale_snapshot", expected.getCode()); }
        assertEquals(0, dispatched.get()); assertEquals(0, clicks.get());
    }
}
