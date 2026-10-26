package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.AndroidUseExecutor;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.DroyAccessibilityService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Deterministic dynamic-page and delayed-dispatch regressions; never calls a model. */
@RunWith(AndroidJUnit4.class)
public final class ExecutionReliabilityDeviceTest {
    private static final Cancellation ACTIVE = () -> {};
    private Instrumentation instrumentation;
    private Context context;
    private AndroidUseExecutor executor;
    private TestPageActivity activity;
    private TextView placeholder;
    private AtomicInteger clicks;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        io.droyagent.androiduse.AccessibilityRecovery.get(context).onAppOpened(false);
        executor = new AndroidUseExecutor(context);
        activity = (TestPageActivity) instrumentation.startActivitySync(new Intent(context, TestPageActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        DeviceUi.await("The enabled accessibility service did not reconnect", 15000,
                () -> DroyAccessibilityService.isConnected(context));
        instrumentation.runOnMainSync(() -> {
            activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            InputMethodManager input = activity.getSystemService(InputMethodManager.class);
            input.hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0);
        });
        instrumentation.waitForIdleSync();
    }

    @After public void tearDown() {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
    }

    @Test public void rotatingTextDoesNotRevokeAnExplicitSearchRegion() throws Exception {
        searchFixture();
        JSONObject observation = observed("test_confirm");
        assertEquals("search_entry", node(observation, "test_confirm").getString("role"));
        Rect originalBounds = observationBounds(node(observation, "test_confirm"));
        instrumentation.runOnMainSync(() -> placeholder.setText("推荐词二"));
        instrumentation.waitForIdleSync();
        awaitLayout("test_confirm");
        assertEquals("Rotating text must not move the search fixture", originalBounds, viewBounds("test_confirm"));
        JSONObject result = executor.execute("tap", args(observation, "test_confirm"), false, ACTIVE);
        assertEquals(1, clicks.get());
        assertEquals("observed", result.getJSONObject("afterObservation").getString("status"));
        assertNotEquals(observation.getString("snapshotId"), result.getJSONObject("afterObservation").getString("snapshotId"));
    }

    @Test public void changedReusableBusinessItemStillRejectsItsOldTarget() throws Exception {
        instrumentation.runOnMainSync(() -> activity.showReusableItem(false));
        instrumentation.waitForIdleSync();
        JSONObject observation = observed("test_confirm");
        instrumentation.runOnMainSync(activity::rebindItemTitle);
        instrumentation.waitForIdleSync();
        expectTargetChanged(() -> executor.execute("tap", args(observation, "test_confirm"), false, ACTIVE));
        assertEquals("尚未确认", resultText());
    }

    @Test public void hintChangesDoNotOverrideRealInputProtection() throws Exception {
        JSONObject observation = observed("test_input");
        JSONObject input = node(observation, "test_input");
        assertEquals("", input.getString("text"));
        assertFalse(input.getString("hint").isEmpty());
        instrumentation.runOnMainSync(() -> ((EditText) activity.findViewById(R.id.test_input)).setHint("新的提示"));
        instrumentation.waitForIdleSync();
        JSONObject written = executor.execute("input_text", args(observation, "test_input").put("text", "诊断文字"), false, ACTIVE);
        assertTrue(written.getBoolean("inputTextVerified"));
        JSONObject next = written.getJSONObject("afterObservation");
        instrumentation.runOnMainSync(() -> ((EditText) activity.findViewById(R.id.test_input)).setText("用户刚刚输入"));
        instrumentation.waitForIdleSync();
        expectTargetChanged(() -> executor.execute("input_text", args(next, "test_input").put("text", "不能覆盖"), false, ACTIVE));
    }

    @Test public void longModelPauseRevalidatesTheSameNode() throws Exception {
        JSONObject observation = observed("test_confirm");
        SystemClock.sleep(15500);
        executor.execute("tap", args(observation, "test_confirm"), false, ACTIVE);
        assertEquals("已确认：", resultText());
    }

    @Test public void expiredCoordinatesReturnNewEvidenceWithoutDispatching() throws Exception {
        JSONObject observation = observed("test_confirm");
        AtomicInteger dispatches = new AtomicInteger();
        Cancellation tracked = new Cancellation() {
            @Override public void check() {}
            @Override public void dispatched() { dispatches.incrementAndGet(); }
        };
        SystemClock.sleep(15500);
        JSONObject coordinate = new JSONObject().put("snapshotId", observation.getString("snapshotId"));
        JSONObject bounds = node(observation, "test_confirm").getJSONObject("bounds");
        coordinate.put("x", (bounds.getInt("left") + bounds.getInt("right")) / 2)
                .put("y", (bounds.getInt("top") + bounds.getInt("bottom")) / 2);
        try { executor.execute("tap", coordinate, false, tracked); fail("Expired coordinates were accepted"); }
        catch (ActionException expected) {
            assertEquals("stale_snapshot", expected.getCode());
            assertEquals("snapshot_expired", expected.getReason());
            assertEquals(0, dispatches.get());
            assertNotEquals(observation.getString("snapshotId"), expected.getDetails().getJSONObject("currentObservation").getString("snapshotId"));
        }
        assertEquals("尚未确认", resultText());
    }

    @Test public void conditionsReturnTheBusinessBlockerAndTimeoutKeepsLastObservation() throws Exception {
        instrumentation.runOnMainSync(() -> ((TextView) activity.findViewById(R.id.test_result)).setText("请完成安全验证"));
        instrumentation.waitForIdleSync();
        JSONObject matched = executor.execute("wait_for", new JSONObject().put("conditions", new JSONArray()
                .put(new JSONObject().put("text", "商品结果"))
                .put(new JSONObject().put("packageName", context.getPackageName()).put("text", "安全验证")))
                .put("match", "any").put("timeoutMs", 1000), false, ACTIVE);
        assertEquals("matched", matched.getString("status"));
        assertEquals(1, matched.getInt("matchedConditionIndex"));
        assertTrue(matched.getJSONObject("observation").getString("text").contains("安全验证"));
        JSONObject timed = executor.execute("wait_for", new JSONObject().put("condition", new JSONObject()
                .put("packageName", "example.other.app").put("text", "不存在").put("state", "absent"))
                .put("timeoutMs", 100), false, ACTIVE);
        assertEquals("timed_out", timed.getString("status"));
        assertEquals(context.getPackageName(), timed.getJSONObject("observation").getString("packageName"));
    }

    @Test public void queuedActionPastDeadlineNeverDispatchesLater() throws Exception {
        JSONObject observation = observed("test_confirm");
        CountDownLatch blocked = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> { blocked.countDown(); SystemClock.sleep(350); });
        assertTrue(blocked.await(2, TimeUnit.SECONDS));
        long deadline = SystemClock.elapsedRealtime() + 80;
        AtomicInteger dispatched = new AtomicInteger();
        Cancellation budget = new Cancellation() {
            @Override public void check() throws InterruptedException {
                if (SystemClock.elapsedRealtime() >= deadline) throw new InterruptedException("test deadline");
            }
            @Override public long remainingMillis(long maximumMs) throws Exception {
                check(); return Math.min(maximumMs, Math.max(1, deadline - SystemClock.elapsedRealtime()));
            }
            @Override public void dispatched() throws InterruptedException { check(); dispatched.incrementAndGet(); }
        };
        try { executor.execute("tap", args(observation, "test_confirm"), false, budget); fail("Expired action returned success"); }
        catch (InterruptedException expected) { }
        SystemClock.sleep(450);
        assertEquals(0, dispatched.get());
        assertEquals("尚未确认", resultText());
    }

    @Test public void compactObservationRetainsLateSemanticControls() throws Exception {
        instrumentation.runOnMainSync(() -> {
            LinearLayout root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL);
            LinearLayout current = root;
            for (int i = 0; i < 70; i++) {
                LinearLayout next = new LinearLayout(activity); next.setOrientation(LinearLayout.VERTICAL);
                // Sibling layout noise used to consume the first 60 output slots.
                root.addView(next, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
            TextView button = new TextView(activity); button.setId(R.id.test_confirm); button.setText("最后的操作入口");
            button.setOnClickListener(v -> { }); current.addView(button);
            installFixture(root);
        });
        JSONObject observation = observed("test_confirm");
        assertNotNull(node(observation, "test_confirm"));
        assertTrue(observation.getJSONArray("nodes").length() < 20);
    }

    private void searchFixture() {
        clicks = new AtomicInteger();
        instrumentation.runOnMainSync(() -> {
            LinearLayout root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(32, 100, 32, 32);
            root.setFocusableInTouchMode(true);
            LinearLayout search = new LinearLayout(activity); search.setId(R.id.test_confirm);
            search.setContentDescription("搜索栏"); search.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            placeholder = new TextView(activity); placeholder.setText("推荐词一"); placeholder.setTextSize(24);
            placeholder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            search.addView(placeholder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150));
            search.setOnClickListener(v -> clicks.incrementAndGet()); root.addView(search);
            installFixture(root);
            root.requestFocus();
        });
        instrumentation.waitForIdleSync();
    }

    private JSONObject observed(String suffix) throws Exception {
        awaitLayout(suffix);
        long end = SystemClock.elapsedRealtime() + 5000;
        do {
            JSONObject result = executor.execute("observe", new JSONObject(), false, ACTIVE);
            JSONObject candidate = node(result, suffix);
            if (candidate != null && viewBounds(suffix).equals(observationBounds(candidate))) return result;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < end);
        throw new AssertionError("Fixture observation did not match settled native bounds: " + suffix);
    }

    private void installFixture(LinearLayout root) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(32, bars.top + 32, 32, bars.bottom + 32);
            return insets;
        });
        activity.setContentView(root);
        root.requestApplyInsets();
    }

    private void awaitLayout(String suffix) throws Exception {
        AtomicReference<Rect> previous = new AtomicReference<>();
        AtomicInteger unchanged = new AtomicInteger();
        DeviceUi.await("Fixture did not finish layout: " + suffix, 5000, () -> {
            AtomicReference<Rect> current = new AtomicReference<>();
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            instrumentation.runOnMainSync(() -> {
                View view = activity.findViewById(context.getResources().getIdentifier(suffix, "id", context.getPackageName()));
                View decor = activity.getWindow().getDecorView();
                WindowInsets insets = decor.getRootWindowInsets();
                if (view != null && view.isLaidOut() && view.isAttachedToWindow() && activity.hasWindowFocus()
                        && !view.isLayoutRequested() && !decor.isLayoutRequested()
                        && insets != null && !insets.isVisible(WindowInsets.Type.ime())) {
                    int[] origin = new int[2]; view.getLocationOnScreen(origin);
                    Rect bounds = new Rect(origin[0], origin[1], origin[0] + view.getWidth(), origin[1] + view.getHeight());
                    current.set(bounds); ready.set(!bounds.isEmpty());
                }
            });
            if (!ready.get()) { unchanged.set(0); return false; }
            boolean same = current.get().equals(previous.get());
            previous.set(current.get());
            if (!same) { unchanged.set(0); return false; }
            return unchanged.incrementAndGet() >= 2;
        });
    }

    private Rect viewBounds(String suffix) {
        AtomicReference<Rect> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            View view = activity.findViewById(context.getResources().getIdentifier(suffix, "id", context.getPackageName()));
            int[] origin = new int[2]; view.getLocationOnScreen(origin);
            result.set(new Rect(origin[0], origin[1], origin[0] + view.getWidth(), origin[1] + view.getHeight()));
        });
        return result.get();
    }

    private Rect observationBounds(JSONObject node) throws Exception {
        JSONObject bounds = node.getJSONObject("bounds");
        return new Rect(bounds.getInt("left"), bounds.getInt("top"), bounds.getInt("right"), bounds.getInt("bottom"));
    }

    private JSONObject node(JSONObject observation, String suffix) throws Exception {
        JSONArray nodes = observation.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if (node.optString("resourceId").endsWith(":id/" + suffix)) return node;
        }
        return null;
    }

    private JSONObject args(JSONObject observation, String suffix) throws Exception {
        JSONObject target = node(observation, suffix); assertNotNull(target);
        return new JSONObject().put("snapshotId", observation.getString("snapshotId")).put("nodeId", target.getString("nodeId"));
    }

    private String resultText() {
        java.util.concurrent.atomic.AtomicReference<String> value = new java.util.concurrent.atomic.AtomicReference<>();
        instrumentation.runOnMainSync(() -> value.set(((TextView) activity.findViewById(R.id.test_result)).getText().toString()));
        return value.get();
    }

    private void expectTargetChanged(Operation operation) throws Exception {
        try { operation.run(); fail("Changed target was accepted"); }
        catch (ActionException expected) { assertEquals("stale_snapshot", expected.getCode()); assertEquals("target_changed", expected.getReason()); }
    }

    private interface Operation { void run() throws Exception; }
}
