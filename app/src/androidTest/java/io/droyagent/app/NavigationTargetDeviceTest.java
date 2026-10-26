package io.droyagent.app;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.AccessibilityRecovery;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.AndroidUseExecutor;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.DroyAccessibilityService;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Navigation fixtures never purchase, send messages, or use a network account. */
@RunWith(AndroidJUnit4.class)
public final class NavigationTargetDeviceTest {
    private static final Cancellation ACTIVE = () -> {};
    private Instrumentation instrumentation;
    private Context context;
    private TestPageActivity activity;
    private AndroidUseExecutor executor;
    private LinearLayout root;
    private FrameLayout region;
    private TextView heading;
    private View card;
    private AlertDialog dialog;
    private final AtomicInteger clicks = new AtomicInteger(), wrongClicks = new AtomicInteger();

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
            root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(32, 110, 32, 32);
            heading = new TextView(activity); heading.setId(android.R.id.title); heading.setText("练习商品列表");
            root.addView(heading, new LinearLayout.LayoutParams(-1, 90));
            region = new FrameLayout(activity); root.addView(region, new LinearLayout.LayoutParams(-1, 800));
            card = product("练习面包 54.30元 1000+人付款", clicks);
            region.addView(card, new FrameLayout.LayoutParams(-1, 700));
            activity.setContentView(root);
        });
        settle();
    }

    @After public void tearDown() {
        instrumentation.runOnMainSync(() -> { if (dialog != null) dialog.dismiss(); if (activity != null) activity.finish(); });
    }

    @Test public void replacedVirtualObjectIsRelocatedInTheSameCall() throws Exception {
        JSONObject basis = observe();
        replaceCard("练习面包 54.30元 1000+人付款");
        navigate(args(basis, true));
        assertEquals(1, clicks.get()); assertEquals(0, wrongClicks.get());
    }

    @Test public void movedProductAndUpdatedPriceStillOpenTheSameProduct() throws Exception {
        JSONObject basis = observe();
        instrumentation.runOnMainSync(() -> { card.setTranslationY(40); card.setContentDescription("练习面包 59.90元 2000+人付款"); });
        settle();
        navigate(args(basis, true));
        assertEquals(1, clicks.get());
    }

    @Test public void normalTapDoesNotRebindAReplacedConfirmationNode() throws Exception {
        instrumentation.runOnMainSync(() -> card.setContentDescription("确认")); settle();
        JSONObject basis = observe();
        replaceCard("确认");
        assertRejected(args(basis, false));
    }

    @Test public void duplicateVisualNodesAndUnrelatedEventsAllowCoordinateNavigation() throws Exception {
        instrumentation.runOnMainSync(() -> {
            TextView duplicate = product("练习面包 54.30元 1000+人付款", clicks);
            region.addView(duplicate, new FrameLayout.LayoutParams(-1, 700));
        }); settle();
        JSONObject basis = observe();
        instrumentation.runOnMainSync(() -> root.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)); settle();
        navigate(pointArgs(basis));
        assertEquals(1, clicks.get()); assertEquals(0, wrongClicks.get());
    }

    @Test public void cardCornerButtonDoesNotBlockItsSemanticNavigation() throws Exception {
        instrumentation.runOnMainSync(() -> {
            TextView more = product("更多", wrongClicks);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(100, 90, Gravity.BOTTOM | Gravity.RIGHT);
            params.bottomMargin = 120; region.addView(more, params);
        }); settle();
        navigate(args(observe(), true));
        assertEquals(1, clicks.get()); assertEquals(0, wrongClicks.get());
    }

    @Test public void reconstructedCardInsideDuplicateContainerKeepsTheSemanticAction() throws Exception {
        FrameLayout[] wrapper = new FrameLayout[1];
        instrumentation.runOnMainSync(() -> {
            region.removeView(card); wrapper[0] = new FrameLayout(activity);
            wrapper[0].setId(R.id.test_confirm); wrapper[0].setOnClickListener(v -> wrongClicks.incrementAndGet());
            wrapper[0].setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            card = product("练习面包 54.30元 1000+人付款", clicks);
            wrapper[0].addView(card, new FrameLayout.LayoutParams(-1, 700));
            region.addView(wrapper[0], new FrameLayout.LayoutParams(-1, 700));
        }); settle();
        JSONObject basis = observe();
        instrumentation.runOnMainSync(() -> {
            wrapper[0].removeView(card); card = product("练习面包 59.90元 2000+人付款", clicks);
            wrapper[0].addView(card, new FrameLayout.LayoutParams(-1, 700));
            FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(100, 90, Gravity.BOTTOM | Gravity.RIGHT);
            wrapper[0].addView(product("更多", wrongClicks), corner);
        }); settle();
        navigate(args(basis, true));
        assertEquals(1, clicks.get()); assertEquals(0, wrongClicks.get());
    }

    @Test public void explicitCoordinateDoesNotFollowMovedProduct() throws Exception {
        JSONObject basis = observe(); JSONObject point = pointArgs(basis);
        instrumentation.runOnMainSync(() -> card.setTranslationY(500)); settle();
        assertRejected(point);
    }

    @Test public void twoDistinctObjectsAtSameBoundsAreRejected() throws Exception {
        JSONObject basis = observe();
        instrumentation.runOnMainSync(() -> region.addView(product("进入另一家店", wrongClicks), new FrameLayout.LayoutParams(-1, 700)));
        settle(); assertRejected(args(basis, true));
    }

    @Test public void sameNamedObjectsAtDifferentBoundsAreRejected() throws Exception {
        JSONObject basis = observe();
        replaceCard("练习面包 54.30元 1000+人付款");
        instrumentation.runOnMainSync(() -> {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(200, 100); params.topMargin = 710;
            region.addView(product("练习面包 54.30元 1000+人付款", wrongClicks), params);
        }); settle(); assertRejected(args(basis, true));
    }

    @Test public void actualModalAndSameWindowPageChangeAreRejected() throws Exception {
        JSONObject basis = observe();
        instrumentation.runOnMainSync(() -> { dialog = new AlertDialog.Builder(activity).setTitle("另一个操作")
                .setMessage("请先处理这个弹窗").setPositiveButton("关闭", (d, w) -> {}).create(); dialog.show(); });
        settle(); assertRejected(args(basis, true));
        instrumentation.runOnMainSync(dialog::dismiss); settle();
        basis = observe();
        instrumentation.runOnMainSync(() -> heading.setText("另一个页面")); settle();
        assertRejected(args(basis, true));
    }

    @Test public void explicitTouchPointCoveredByIndependentButtonIsRejected() throws Exception {
        JSONObject basis = observe(); JSONObject point = pointArgs(basis);
        instrumentation.runOnMainSync(() -> {
            TextView cover = product("新按钮", wrongClicks);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(200, 150, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            params.topMargin = 300; region.addView(cover, params);
        }); settle(); assertRejected(point);
    }

    @Test public void cancelledNavigationDoesNotDispatch() throws Exception {
        JSONObject basis = observe();
        try { executor.execute("tap", args(basis, true), false, () -> { throw new InterruptedException("cancelled"); }); fail(); }
        catch (InterruptedException expected) { }
        assertEquals(0, clicks.get());
    }

    @Test public void newUnnamedFullCardOverlayIsNotADuplicateContainer() throws Exception {
        JSONObject basis = observe();
        instrumentation.runOnMainSync(() -> region.addView(product("", wrongClicks), new FrameLayout.LayoutParams(-1, 700)));
        settle(); assertRejected(args(basis, true));
    }

    @Test public void pageTitleWithoutResourceIdStillPreventsCrossPageRebinding() throws Exception {
        instrumentation.runOnMainSync(() -> heading.setId(View.NO_ID)); settle();
        JSONObject basis = observe();
        replaceCard("练习面包 54.30元 1000+人付款");
        instrumentation.runOnMainSync(() -> heading.setText("另一页商品")); settle();
        assertRejected(args(basis, true));
    }

    @Test public void genericDescriptionDoesNotMatchADifferentVisibleProduct() throws Exception {
        instrumentation.runOnMainSync(() -> { card.setContentDescription("查看详情"); ((TextView) card).setText("商品甲"); }); settle();
        JSONObject basis = observe();
        replaceCard("查看详情");
        instrumentation.runOnMainSync(() -> ((TextView) card).setText("商品乙")); settle();
        assertRejected(args(basis, true));
    }

    @Test public void independentChildButtonAtExplicitPointIsRejected() throws Exception {
        instrumentation.runOnMainSync(() -> {
            region.removeView(card);
            FrameLayout container = new FrameLayout(activity);
            container.setContentDescription("练习面包 54.30元 1000+人付款");
            container.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            container.setOnClickListener(v -> clicks.incrementAndGet()); card = container;
            region.addView(container, new FrameLayout.LayoutParams(-1, 700));
        }); settle();
        JSONObject basis = observe(); JSONObject point = pointArgs(basis);
        instrumentation.runOnMainSync(() -> {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(200, 150, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            params.topMargin = 300; ((FrameLayout) card).addView(product("进店", wrongClicks), params);
        }); settle(); assertRejected(point);
    }

    @Test public void reusedResourceIdContainerDoesNotKeepItsPreviousProductIdentity() throws Exception {
        final TextView[] title = new TextView[1];
        instrumentation.runOnMainSync(() -> {
            region.removeView(card);
            FrameLayout container = new FrameLayout(activity); container.setId(R.id.test_confirm);
            container.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            container.setOnClickListener(v -> clicks.incrementAndGet()); card = container;
            TextView badge = new TextView(activity); badge.setText("包邮");
            badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            container.addView(badge, new FrameLayout.LayoutParams(-1, 80));
            title[0] = new TextView(activity); title[0].setText("商品甲");
            title[0].setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            FrameLayout.LayoutParams titleLayout = new FrameLayout.LayoutParams(-1, 100); titleLayout.topMargin = 100;
            container.addView(title[0], titleLayout);
            region.addView(container, new FrameLayout.LayoutParams(-1, 700));
        }); settle();
        JSONObject basis = observe(); JSONObject node = null;
        for (int i = 0; i < basis.getJSONArray("nodes").length(); i++) {
            JSONObject candidate = basis.getJSONArray("nodes").getJSONObject(i);
            if (candidate.optString("resourceId").endsWith("/test_confirm")) node = candidate;
        }
        assertNotNull(node);
        JSONObject args = new JSONObject().put("snapshotId", basis.getString("snapshotId"))
                .put("nodeId", node.getString("nodeId")).put("navigation", true);
        instrumentation.runOnMainSync(() -> title[0].setText("商品乙")); settle();
        assertRejected(args);
    }

    private TextView product(String description, AtomicInteger counter) {
        TextView view = new TextView(activity); view.setContentDescription(description); view.setBackgroundColor(Color.LTGRAY);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); view.setOnClickListener(v -> counter.incrementAndGet());
        return view;
    }
    private void replaceCard(String description) {
        instrumentation.runOnMainSync(() -> { region.removeView(card); card = product(description, clicks); region.addView(card, new FrameLayout.LayoutParams(-1, 700)); });
        settle();
    }
    private JSONObject observe() throws Exception { return executor.execute("observe", new JSONObject(), false, ACTIVE); }
    private JSONObject target(JSONObject observation) throws Exception {
        JSONArray nodes = observation.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if (node.optString("description").startsWith("练习面包") || node.optString("description").equals("确认")
                    || node.optString("description").equals("查看详情")) return node;
        }
        throw new AssertionError("Missing fixture target");
    }
    private JSONObject args(JSONObject observation, boolean navigation) throws Exception {
        return new JSONObject().put("snapshotId", observation.getString("snapshotId"))
                .put("nodeId", target(observation).getString("nodeId")).put("navigation", navigation);
    }
    private JSONObject pointArgs(JSONObject observation) throws Exception {
        JSONObject bounds = target(observation).getJSONObject("bounds");
        return new JSONObject().put("snapshotId", observation.getString("snapshotId")).put("navigation", true)
                .put("x", (bounds.getInt("left") + bounds.getInt("right")) / 2)
                .put("y", (bounds.getInt("top") + bounds.getInt("bottom")) / 2);
    }
    private void navigate(JSONObject args) throws Exception {
        long began = SystemClock.elapsedRealtime();
        AtomicInteger dispatched = new AtomicInteger();
        executor.execute("tap", args, false, new Cancellation() {
            @Override public void check() {}
            @Override public void dispatched() {
                dispatched.incrementAndGet();
                android.util.Log.i("DroyNavigationTest", "preDispatchMs=" + (SystemClock.elapsedRealtime() - began));
            }
        });
        assertEquals("Navigation dispatches at most once", 1, dispatched.get());
    }
    private void assertRejected(JSONObject args) throws Exception {
        AtomicInteger dispatches = new AtomicInteger();
        try {
            executor.execute("tap", args, false, new Cancellation() {
                @Override public void check() {}
                @Override public void dispatched() { dispatches.incrementAndGet(); }
            }); fail("Changed or ambiguous object dispatched");
        } catch (ActionException expected) {
            assertEquals("stale_snapshot", expected.getCode());
            assertNotNull("Failure must include a current observation", expected.getDetails());
            assertTrue(expected.getDetails().has("currentObservation"));
        }
        assertEquals(0, dispatches.get()); assertEquals(0, clicks.get()); assertEquals(0, wrongClicks.get());
    }
    private void settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(350); instrumentation.waitForIdleSync(); }
}
