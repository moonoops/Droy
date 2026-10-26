package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.AndroidUseExecutor;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.DroyAccessibilityService;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** A real editor extends under the actual navigation bar; no text leaves this local fixture. */
@RunWith(AndroidJUnit4.class)
public final class InputSystemBarDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private TestPageActivity activity;
    private AndroidUseExecutor executor;
    private EditText editor;
    private FrameLayout page;
    private Rect content, navigation;
    private String geometry = "pending";

    @Before public void setup() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        assertTrue("Stop existing tasks and the runtime before this fixture", RuntimeService.canManageHistory());
        io.droyagent.androiduse.AccessibilityRecovery.get(context).onAppOpened(false);
        activity = (TestPageActivity) instrumentation.startActivitySync(new Intent(context, TestPageActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        DeviceUi.await("Accessibility did not reconnect", 15000, () -> DroyAccessibilityService.isConnected(context));
        Method current = DroyAccessibilityService.class.getDeclaredMethod("current", Context.class);
        current.setAccessible(true);
        DroyAccessibilityService service = (DroyAccessibilityService) current.invoke(null, context);
        Method contentBounds = DroyAccessibilityService.class.getDeclaredMethod("coordinateContentBounds");
        contentBounds.setAccessible(true);
        instrumentation.runOnMainSync(() -> {
            activity.getWindow().setDecorFitsSystemWindows(false);
            activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            page = new FrameLayout(activity);
            editor = new EditText(activity); editor.setId(R.id.test_input);
            editor.setHint("本地边缘输入测试"); editor.setShowSoftInputOnFocus(false);
            page.addView(editor, new FrameLayout.LayoutParams(300, 100));
            activity.setContentView(page);
        });
        DeviceUi.await("Fixture root did not lay out", 5000, () -> page.isLaidOut() && page.getHeight() > 0);
        DeviceUi.await("Fixture app did not become the actual active content window", 5000, () -> {
            AtomicInteger editorWindow = new AtomicInteger(-1), focused = new AtomicInteger();
            instrumentation.runOnMainSync(() -> {
                focused.set(activity.hasWindowFocus() ? 1 : 0);
                AccessibilityNodeInfo node = editor.createAccessibilityNodeInfo();
                try { editorWindow.set(node.getWindowId()); } finally { node.recycle(); }
            });
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            try { return focused.get() == 1 && root != null && editorWindow.get() >= 0 && root.getWindowId() == editorWindow.get(); }
            finally { if (root != null) root.recycle(); }
        });
        instrumentation.waitForIdleSync();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).waitForIdle(250, 3000);
        try {
            DeviceUi.await("Fixture requires a passive bottom system bar", 5000, () -> {
                content = (Rect) contentBounds.invoke(service);
                Rect screen = context.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
                List<AccessibilityWindowInfo> windows = service.getWindows();
                StringBuilder details = new StringBuilder("screen=").append(screen).append(" content=").append(content);
                navigation = null;
                try {
                    for (AccessibilityWindowInfo window : windows) {
                        Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                        details.append(" window=").append(window.getType()).append('/').append(window.isFocused())
                            .append('/').append(window.isActive()).append('/').append(bounds);
                        if (window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM && !window.isFocused() && !window.isActive()
                                && !bounds.isEmpty() && screen.contains(bounds) && content.bottom < screen.bottom
                                && bounds.left == screen.left && bounds.right == screen.right && bounds.bottom == screen.bottom
                                && bounds.top <= content.bottom) navigation = bounds;
                    }
                } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
                geometry = details.toString();
                return navigation != null;
            });
        } catch (AssertionError failure) { throw new AssertionError(failure.getMessage() + "; " + geometry, failure); }
        instrumentation.runOnMainSync(() -> {
            int[] origin = new int[2]; page.getLocationOnScreen(origin);
            int height = Math.max(80, Math.round(64 * context.getResources().getDisplayMetrics().density));
            int overlap = Math.max(1, Math.min(navigation.height() / 3, height / 4));
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(Math.min(content.width() / 2, 600), height);
            position.leftMargin = content.left + content.width() / 4 - origin[0];
            position.topMargin = navigation.top + overlap - height - origin[1];
            editor.setLayoutParams(position);
        });
        DeviceUi.await("Editor did not actually cross the navigation edge with its center still visible", 5000, () -> {
            Rect bounds = editorBounds();
            return Rect.intersects(bounds, navigation) && content.contains(bounds.centerX(), bounds.centerY());
        });
        instrumentation.waitForIdleSync();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).waitForIdle(250, 3000);
        executor = new AndroidUseExecutor(context);
    }

    @After public void teardown() { if (activity != null) instrumentation.runOnMainSync(activity::finish); }

    @Test public void textSettingUsesTheVisibleEditorBodyDespiteItsNavigationCoveredEdge() throws Exception {
        String message = "仅本地边缘输入，中文🌙";
        AtomicInteger dispatched = new AtomicInteger();
        JSONObject result = executor.execute("input_text", arguments().put("text", message), false, ticket(dispatched));
        assertEquals("action_completed", result.getString("status"));
        assertTrue(result.toString(), result.getBoolean("inputTextVerified"));
        assertEquals(1, dispatched.get());
        assertEquals(message, editorText());
    }

    @Test public void tappingTheSamePartiallyCoveredNodeStillRequiresTheWholeTargetUnobscured() throws Exception {
        reject("tap", arguments());
        assertEquals("", editorText());
    }

    @Test public void anApplicationControlOverTheEditorStillBlocksTextSetting() throws Exception {
        instrumentation.runOnMainSync(() -> {
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams((FrameLayout.LayoutParams) editor.getLayoutParams());
            Button cover = new Button(activity); cover.setText("本地遮挡控件");
            page.addView(cover, position);
        });
        instrumentation.waitForIdleSync();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).waitForIdle(250, 3000);
        reject("input_text", arguments().put("text", "不能覆盖"));
        assertEquals("", editorText());
    }

    private JSONObject arguments() throws Exception {
        JSONObject observed = executor.execute("observe", new JSONObject(), false, () -> {});
        JSONArray nodes = observed.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if (!node.optString("resourceId").endsWith(":id/test_input")) continue;
            JSONObject raw = node.getJSONObject("bounds");
            Rect actual = new Rect(raw.getInt("left"), raw.getInt("top"), raw.getInt("right"), raw.getInt("bottom"));
            assertTrue("Accessibility clipped away the overlapping edge", Rect.intersects(actual, navigation));
            assertTrue(node.getBoolean("editable"));
            return new JSONObject().put("snapshotId", observed.getString("snapshotId")).put("nodeId", node.getString("nodeId"));
        }
        throw new AssertionError("The edge editor is missing from the actual accessibility tree");
    }
    private void reject(String action, JSONObject args) throws Exception {
        AtomicInteger dispatched = new AtomicInteger();
        try { executor.execute(action, args, false, ticket(dispatched)); fail("Covered node was dispatched"); }
        catch (ActionException expected) { assertEquals("stale_snapshot", expected.getCode()); assertEquals("target_obscured", expected.getReason()); }
        assertEquals(0, dispatched.get());
    }
    private Cancellation ticket(AtomicInteger dispatched) {
        return new Cancellation() { public void check() {} public void dispatched() { dispatched.incrementAndGet(); } };
    }
    private Rect editorBounds() {
        AtomicReference<Rect> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { int[] origin = new int[2]; editor.getLocationOnScreen(origin);
            result.set(new Rect(origin[0], origin[1], origin[0] + editor.getWidth(), origin[1] + editor.getHeight())); });
        return result.get();
    }
    private String editorText() {
        AtomicReference<String> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(editor.getText().toString()));
        return result.get();
    }
}
