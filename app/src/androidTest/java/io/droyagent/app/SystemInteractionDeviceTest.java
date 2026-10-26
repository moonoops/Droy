package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Public Android actions on an offline fixture and this app's Settings entry. */
@RunWith(AndroidJUnit4.class)
public final class SystemInteractionDeviceTest {
    private static final Cancellation ACTIVE = () -> {};
    private Instrumentation instrumentation;
    private Context context;
    private TestPageActivity activity;
    private AndroidUseExecutor executor;
    private FrameLayout fixture;
    private ScrollView scroll;
    private final AtomicInteger longClicks = new AtomicInteger();

    @Before public void open() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        AccessibilityRecovery.get(context).onAppOpened(false);
        activity = (TestPageActivity) instrumentation.startActivitySync(new Intent(context, TestPageActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        DeviceUi.await("Accessibility must connect", 15000, () -> DroyAccessibilityService.isConnected(context));
        executor = new AndroidUseExecutor(context);
        instrumentation.runOnMainSync(() -> {
            LinearLayout root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(android.graphics.Color.WHITE);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(24, bars.top + 24, 24, bars.bottom + 24); return insets;
            });
            Button button = new Button(activity); button.setText("长按测试按钮");
            button.setContentDescription("System interaction long press fixture");
            button.setOnLongClickListener(view -> { longClicks.incrementAndGet(); return true; });
            root.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100));
            CheckBox checked = new CheckBox(activity); checked.setText("已选中的虚构设置"); checked.setChecked(true);
            root.addView(checked, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 90));
            scroll = new ScrollView(activity);
            LinearLayout rows = new LinearLayout(activity); rows.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < 45; i++) {
                TextView row = new TextView(activity); row.setText("虚构列表项目 " + i); row.setTextSize(18);
                row.setTextColor(android.graphics.Color.BLACK);
                rows.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100));
            }
            scroll.addView(rows);
            root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            fixture = new FrameLayout(activity); fixture.addView(root);
            activity.setContentView(fixture); root.requestApplyInsets();
        });
        settle();
    }

    @After public void close() {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
    }

    @Test public void nodeLongClickUsesAdvertisedActionAndConsumesOldSnapshot() throws Exception {
        JSONObject observed = observe();
        JSONObject button = node(observed, "System interaction long press fixture", false);
        assertTrue(button.getBoolean("longClickable"));
        assertTrue(contains(button.getJSONArray("supportedActions"), "long_click"));
        JSONObject checkbox = node(observed, "已选中的虚构设置", false);
        assertTrue(checkbox.getBoolean("checkable")); assertTrue(checkbox.getBoolean("checked"));
        capture("before-long-click");
        JSONObject args = nodeArgs(observed, button, "long_click");
        JSONObject receipt = executor.execute("node_action", args, false, ACTIVE);
        assertEquals("long_click", receipt.getString("operation"));
        assertEquals(1, longClicks.get());
        ActionException stale = assertThrows(ActionException.class, () -> executor.execute("node_action", args, false, ACTIVE));
        assertEquals("stale_snapshot", stale.getCode());
        assertEquals(1, longClicks.get());
        capture("after-long-click");
    }

    @Test public void scrollableContainerWithoutTextCanBeScrolledDirectly() throws Exception {
        JSONObject observed = observe();
        JSONObject container = node(observed, "", true);
        assertEquals("", container.optString("text"));
        assertTrue(contains(container.getJSONArray("supportedActions"), "scroll_forward"));
        int[] before = {0}; instrumentation.runOnMainSync(() -> before[0] = scroll.getScrollY());
        executor.execute("node_action", nodeArgs(observed, container, "scroll_forward"), false, ACTIVE);
        DeviceUi.await("The actual ScrollView did not move", 4000, () -> {
            int[] y = {0}; instrumentation.runOnMainSync(() -> y[0] = scroll.getScrollY()); return y[0] > before[0];
        });
        settle(); capture("after-semantic-scroll");
    }

    @Test public void scrollViewportCenterCannotBeOperatedThroughAModalOrCoveringControl() throws Exception {
        JSONObject before = observe(); JSONObject container = node(before, "", true);
        AtomicInteger dispatched = new AtomicInteger();
        Cancellation tracked = new Cancellation() {
            @Override public void check() {}
            @Override public void dispatched() { dispatched.incrementAndGet(); }
        };
        Dialog[] modal = {null};
        instrumentation.runOnMainSync(() -> {
            modal[0] = new Dialog(activity);
            Button content = new Button(activity); content.setText("虚构滚动拦截弹层");
            modal[0].setContentView(content); modal[0].show();
        });
        try {
            settle();
            ActionException rejected = assertThrows(ActionException.class, () -> executor.execute("node_action",
                    nodeArgs(before, container, "scroll_forward"), false, tracked));
            assertEquals("stale_snapshot", rejected.getCode());
            assertEquals(0, dispatched.get());
        } finally { instrumentation.runOnMainSync(() -> modal[0].dismiss()); }
        settle();

        Button[] cover = {null};
        instrumentation.runOnMainSync(() -> {
            Rect viewport = new Rect(); assertTrue(scroll.getGlobalVisibleRect(viewport));
            int[] origin = new int[2]; fixture.getLocationOnScreen(origin);
            cover[0] = new Button(activity); cover[0].setText("虚构滚动中心覆盖控件");
            cover[0].setOnClickListener(view -> {});
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(300, 180);
            position.leftMargin = viewport.centerX() - origin[0] - 150;
            position.topMargin = viewport.centerY() - origin[1] - 90;
            fixture.addView(cover[0], position);
        });
        try {
            settle();
            JSONObject covered = observe(); JSONObject coveredContainer = node(covered, "", true);
            capture("scroll-center-covered");
            ActionException rejected = assertThrows(ActionException.class, () -> executor.execute("node_action",
                    nodeArgs(covered, coveredContainer, "scroll_forward"), false, tracked));
            assertEquals("stale_snapshot", rejected.getCode());
            assertEquals("target_obscured", rejected.getReason());
            assertEquals(0, dispatched.get());
            instrumentation.runOnMainSync(() -> assertEquals(0, scroll.getScrollY()));
        } finally { instrumentation.runOnMainSync(() -> fixture.removeView(cover[0])); }
        settle();
        JSONObject clear = observe();
        executor.execute("node_action", nodeArgs(clear, node(clear, "", true), "scroll_forward"), false, ACTIVE);
        DeviceUi.await("Removing the cover did not restore semantic scrolling", 4000, () -> {
            int[] y = {0}; instrumentation.runOnMainSync(() -> y[0] = scroll.getScrollY()); return y[0] > 0;
        });
        settle(); capture("scroll-center-restored");
    }

    @Test public void unsupportedNodeActionAndDispatchCancellationHaveNoSideEffect() throws Exception {
        JSONObject observed = observe(); JSONObject button = node(observed, "System interaction long press fixture", false);
        ActionException unsupported = assertThrows(ActionException.class, () -> executor.execute("node_action",
                nodeArgs(observed, button, "scroll_forward"), false, ACTIVE));
        assertEquals("node_action_unavailable", unsupported.getCode());
        JSONObject fresh = observe(); JSONObject target = node(fresh, "System interaction long press fixture", false);
        Cancellation cancelledAtDispatch = new Cancellation() {
            @Override public void check() {}
            @Override public void dispatched() throws InterruptedException { throw new InterruptedException("fixture cancellation"); }
        };
        assertThrows(InterruptedException.class, () -> executor.execute("node_action",
                nodeArgs(fresh, target, "long_click"), false, cancelledAtDispatch));
        assertEquals(0, longClicks.get());
    }

    @Test public void homeUsesSystemActionAndAppDetailsUsesPublicSettingsEntry() throws Exception {
        JSONObject caps = executor.capabilities(false);
        assertTrue(caps.toString(), contains(caps.getJSONArray("globalActions"), "home"));
        executor.execute("global_action", new JSONObject().put("operation", "home").put("afterObserve", false), false, ACTIVE);
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        String homePackage = context.getPackageManager().resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).activityInfo.packageName;
        DeviceUi.await("Home was not shown", 5000, () -> homePackage.equals(observe().optString("packageName")));
        instrumentation.runOnMainSync(activity::finish);
        activity = (TestPageActivity) instrumentation.startActivitySync(new Intent(context, TestPageActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        settle();
        JSONObject opened = executor.execute("open_settings", new JSONObject().put("page", "app_details")
                .put("packageName", context.getPackageName()), false, ACTIVE);
        assertTrue(opened.getBoolean("dispatched"));
        assertFalse(opened.getBoolean("goalVerified"));
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", opened.getString("settingsAction"));
        String handler = opened.getString("handlerPackage");
        DeviceUi.await("App details did not become visible", 6000, () -> {
            JSONObject page = observe(); return handler.equals(page.optString("packageName"))
                    && (page.optString("text").contains("Droy") || page.optString("text").contains(context.getPackageName()));
        });
        capture("app-details-settings");
        executor.execute("back", new JSONObject().put("afterObserve", false), false, ACTIVE);
        settle();
    }

    @Test public void productStopBlocksNewNavigationAndNodeOperations() throws Exception {
        ProductBridge bridge = new ProductBridge(context, "fictional-system-interaction-stop");
        try {
            bridge.policy.stop();
            assertThrows(IllegalStateException.class, () -> bridge.executeTool("global_action",
                    new JSONObject().put("operation", "home"), "system-stop", "home"));
            assertThrows(IllegalStateException.class, () -> bridge.executeTool("open_settings",
                    new JSONObject().put("page", "privacy"), "system-stop", "settings"));
            assertThrows(IllegalStateException.class, () -> bridge.executeTool("node_action",
                    new JSONObject().put("operation", "long_click"), "system-stop", "node"));
        } finally { bridge.stop(); }
    }

    @Test public void advertisedSystemPanelsCanBeOpenedAndReturnedFrom() throws Exception {
        JSONArray supported = executor.capabilities(false).getJSONArray("globalActions");
        JSONArray evidence = new JSONArray();
        for (String operation : new String[]{"recents", "notifications", "quick_settings"}) {
            if (!contains(supported, operation)) {
                ActionException unavailable = assertThrows(ActionException.class, () -> executor.execute("global_action",
                        new JSONObject().put("operation", operation), false, ACTIVE));
                assertEquals("system_action_unavailable", unavailable.getCode());
                continue;
            }
            JSONObject before = windowIdentity();
            try {
                JSONObject opened = executor.execute("global_action", new JSONObject().put("operation", operation)
                        .put("afterObserve", false), false, ACTIVE);
                DeviceUi.await("The advertised system panel did not change the active window: " + operation, 5000, () -> {
                    JSONObject actual = windowIdentity();
                    return !actual.optBoolean("fixtureFocused") && actual.has("windowId")
                            && (actual.optInt("windowId") != before.optInt("windowId")
                                || !actual.optString("packageName").equals(before.optString("packageName")));
                });
                JSONObject panel = windowIdentity();
                JSONObject back = executor.execute("back", new JSONObject().put("afterObserve", false), false, ACTIVE);
                assertEquals("action_completed", back.getString("status"));
                assertFalse(back.getBoolean("goalVerified"));
                instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).waitForIdle(150, 3000);
                // Back's destination is system-owned (Recents may return Home).
                // Record its actual state, then explicitly restore our fixture.
                evidence.put(new JSONObject().put("operation", operation).put("before", before)
                        .put("openReceipt", opened).put("panel", panel).put("backReceipt", back)
                        .put("afterBack", windowIdentity()));
            } finally {
                if (operation.equals("notifications") || operation.equals("quick_settings")) {
                    // Quick Settings Back may only reduce to the notification
                    // shade. Collapse that test-owned surface before reopening
                    // the fixture; an Activity launch need not close the shade.
                    try (java.io.InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation(
                            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand("cmd statusbar collapse"))) {
                        byte[] buffer = new byte[1024];
                        while (output.read(buffer) != -1) { }
                    }
                }
                restoreFixture();
                writeEvidence("system-navigation.json", evidence.toString(2));
            }
        }
    }

    private JSONObject windowIdentity() throws Exception {
        JSONObject identity = new JSONObject();
        AccessibilityNodeInfo root = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow();
        if (root != null) {
            try { identity.put("windowId", root.getWindowId()).put("packageName", String.valueOf(root.getPackageName())); }
            finally { root.recycle(); }
        }
        boolean[] focused = {false}; instrumentation.runOnMainSync(() -> focused[0] = activity.hasWindowFocus());
        return identity.put("fixtureFocused", focused[0]);
    }

    private void restoreFixture() throws Exception {
        instrumentation.runOnMainSync(activity::finish);
        activity = (TestPageActivity) instrumentation.startActivitySync(new Intent(context, TestPageActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        DeviceUi.await("The explicitly restored fixture did not become active", 5000, () -> {
            JSONObject actual = windowIdentity();
            return actual.optBoolean("fixtureFocused") && context.getPackageName().equals(actual.optString("packageName"));
        });
    }

    private void writeEvidence(String name, String text) throws Exception {
        File directory = new File(context.getExternalFilesDir(null), "system-interaction-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private JSONObject observe() throws Exception { return executor.execute("observe", new JSONObject(), false, ACTIVE); }

    private static JSONObject node(JSONObject page, String label, boolean scrolling) throws Exception {
        JSONArray nodes = page.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject candidate = nodes.getJSONObject(i);
            if (scrolling ? candidate.optBoolean("scrollable")
                    : label.equals(candidate.optString("description")) || label.equals(candidate.optString("text"))) return candidate;
        }
        throw new AssertionError("Missing controlled fixture node " + label);
    }

    private static JSONObject nodeArgs(JSONObject observation, JSONObject node, String operation) throws Exception {
        return new JSONObject().put("snapshotId", observation.getString("snapshotId"))
                .put("nodeId", node.getString("nodeId")).put("operation", operation);
    }

    private static boolean contains(JSONArray values, String value) {
        for (int i = 0; i < values.length(); i++) if (value.equals(values.optString(i))) return true;
        return false;
    }

    private void settle() { SystemClock.sleep(350); instrumentation.waitForIdleSync(); }

    private void capture(String label) throws Exception {
        Bitmap bitmap = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
        assertNotNull(bitmap);
        File directory = new File(context.getExternalFilesDir(null), "system-interaction-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, label + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }
}
