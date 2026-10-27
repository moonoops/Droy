package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.AndroidUseExecutor;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.DroyAccessibilityService;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Replays a known-window event locally; never changes Android's accessibility cache. */
@RunWith(AndroidJUnit4.class)
public final class CoordinateRegionDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private UiAutomation automation;
    private AndroidUseExecutor executor;
    private TestPageActivity activity;
    private DroyAccessibilityService service;
    private int barWindowId;
    private Rect barBounds;
    private Rect contentBounds;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        // Instrumentation restarts the app process; perform the same recovery as normal app entry.
        io.droyagent.androiduse.AccessibilityRecovery.get(context).onAppOpened(false);
        executor = new AndroidUseExecutor(context);
        activity = (TestPageActivity) instrumentation.startActivitySync(new Intent(context, TestPageActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        DeviceUi.await("Accessibility did not reconnect", 15000, () -> DroyAccessibilityService.isConnected(context));
        service = (DroyAccessibilityService) invoke("current", new Class<?>[]{Context.class}, new Object[]{context});
        contentBounds = (Rect) invokeOnService("coordinateContentBounds");
        instrumentation.waitForIdleSync(); automation.waitForIdle(300, 5000);
        List<AccessibilityWindowInfo> windows = service.getWindows();
        barWindowId = -1;
        try {
            for (AccessibilityWindowInfo window : windows) {
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                if (window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM && !bounds.isEmpty()
                        && !Rect.intersects(contentBounds, bounds)) {
                    barWindowId = window.getId(); barBounds = bounds; break;
                }
            }
        } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
        assertTrue("Fixture requires a real fixed system-bar window", barWindowId >= 0);
    }

    @After public void tearDown() {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
    }

    @Test public void systemBarContentKeepsInteriorTapButStillInvalidatesTheFullFrame() throws Exception {
        JSONObject observation = observe();
        JSONObject tap = target(observation);
        assertTrue(contentBounds.contains(tap.getInt("x"), tap.getInt("y")));
        long full = generation("generation"), content = generation("contentGeneration");
        replay(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, barWindowId);
        assertTrue("Full screenshot generation must still change", generation("generation") > full);
        assertEquals("Bar-only content must preserve the interior epoch; " + barReceiptState(), content, generation("contentGeneration"));
        executor.execute("tap", tap, false, () -> {});
        DeviceUi.await("Coordinate action failed to reach the real fixture", 3000, () -> activity.confirmationCount() == 1);
    }

    @Test public void barTargetsAndCrossingSwipesStillRejectChangedBarContent() throws Exception {
        JSONObject observation = observe();
        replay(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, barWindowId);
        reject("tap", new JSONObject().put("snapshotId", observation.getString("snapshotId"))
            .put("x", barBounds.centerX()).put("y", barBounds.centerY()));
        observation = observe();
        replay(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, barWindowId);
        JSONObject swipe = target(observation);
        reject("swipe", new JSONObject().put("snapshotId", observation.getString("snapshotId"))
            .put("startX", swipe.getInt("x")).put("startY", swipe.getInt("y"))
            .put("endX", barBounds.centerX()).put("endY", barBounds.centerY()));
    }

    @Test public void unrelatedAppContentRevalidatesTheOriginalCoordinateButton() throws Exception {
        JSONObject observation = observe();
        AtomicInteger appWindowId = new AtomicInteger();
        instrumentation.runOnMainSync(() -> {
            AccessibilityNodeInfo node = activity.getWindow().getDecorView().createAccessibilityNodeInfo();
            try { appWindowId.set(node.getWindowId()); } finally { node.recycle(); }
        });
        replay(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, appWindowId.get());
        executor.execute("tap", target(observation), false, () -> {});
        DeviceUi.await("Revalidated coordinate did not activate the original button", 3000, () -> activity.confirmationCount() == 1);
    }

    @Test public void unchangedWindowGeometryKeepsTheBarException() throws Exception {
        observe();
        Method update = DroyAccessibilityService.class.getDeclaredMethod("updateWindowLayers", AccessibilityEvent.class);
        update.setAccessible(true);
        Method record = DroyAccessibilityService.class.getDeclaredMethod("recordUiChange", int.class, int.class);
        record.setAccessible(true);
        Method geometry = DroyAccessibilityService.class.getDeclaredMethod("coordinateContentBounds");
        geometry.setAccessible(true);
        instrumentation.runOnMainSync(() -> {
            AccessibilityEvent change = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED);
            try {
                update.invoke(service, change);
                geometry.invoke(service);
                service.onAccessibilityEvent(change);
                long content = generation("contentGeneration");
                record.invoke(service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, barWindowId);
                assertEquals("Unchanged geometry must preserve the known bar exception; " + barReceiptState(),
                        content, generation("contentGeneration"));
            } catch (Exception error) { throw new AssertionError(error); }
            finally { change.recycle(); }
        });
    }

    @Test public void changedWindowListRevokesOldGeometryBeforeTheEventCanBeIgnored() throws Exception {
        observe();
        java.lang.reflect.Field windows = DroyAccessibilityService.class.getDeclaredField("contentWindows");
        windows.setAccessible(true);
        Method update = DroyAccessibilityService.class.getDeclaredMethod("updateWindowLayers", AccessibilityEvent.class);
        update.setAccessible(true);
        Method record = DroyAccessibilityService.class.getDeclaredMethod("recordUiChange", int.class, int.class);
        record.setAccessible(true);
        instrumentation.runOnMainSync(() -> {
            AccessibilityEvent change = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED);
            try {
                // Exercise the update before onAccessibilityEvent's owned-overlay early return.
                windows.set(service, List.of());
                update.invoke(service, change);
                long content = generation("contentGeneration");
                record.invoke(service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, barWindowId);
                assertEquals("A replaced window list must revoke its previous region", content + 1, generation("contentGeneration"));
            } catch (Exception error) { throw new AssertionError(error); }
            finally { change.recycle(); }
        });
    }

    @Test public void windowPropertyEventWithoutAnIdentityChangeKeepsTheOriginalButton() throws Exception {
        JSONObject observation = observe();
        replay(AccessibilityEvent.TYPE_WINDOWS_CHANGED, barWindowId);
        executor.execute("tap", target(observation), false, () -> {});
        DeviceUi.await("Window property event prevented the original button action", 3000, () -> activity.confirmationCount() == 1);
    }

    @Test public void unanchoredCoordinatesAndSwipesStillRequireUnchangedContent() throws Exception {
        JSONObject observation = observe();
        replay(AccessibilityEvent.TYPE_WINDOWS_CHANGED, barWindowId);
        reject("tap", new JSONObject().put("snapshotId", observation.getString("snapshotId"))
            .put("x", contentBounds.left + 1).put("y", contentBounds.bottom - 1));
        observation = observe();
        replay(AccessibilityEvent.TYPE_WINDOWS_CHANGED, barWindowId);
        JSONObject tap = target(observation);
        reject("swipe", new JSONObject().put("snapshotId", observation.getString("snapshotId"))
            .put("startX", tap.getInt("x")).put("startY", tap.getInt("y"))
            .put("endX", tap.getInt("x")).put("endY", tap.getInt("y") + 40));
    }

    private JSONObject observe() throws Exception {
        instrumentation.waitForIdleSync(); automation.waitForIdle(150, 3000);
        return executor.execute("observe", new JSONObject(), false, () -> {});
    }
    private JSONObject target(JSONObject observation) throws Exception {
        JSONArray nodes = observation.getJSONArray("nodes");
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject node = nodes.getJSONObject(index);
            if (!node.optString("resourceId").endsWith(":id/test_confirm")) continue;
            JSONObject bounds = node.getJSONObject("bounds");
            return new JSONObject().put("snapshotId", observation.getString("snapshotId"))
                .put("x", (bounds.getInt("left") + bounds.getInt("right")) / 2)
                .put("y", (bounds.getInt("top") + bounds.getInt("bottom")) / 2);
        }
        throw new AssertionError("Missing fixture target");
    }
    private void reject(String action, JSONObject args) throws Exception {
        AtomicInteger dispatched = new AtomicInteger();
        Cancellation ticket = new Cancellation() {
            @Override public void check() {}
            @Override public void dispatched() { dispatched.incrementAndGet(); }
        };
        try { executor.execute(action, args, false, ticket); fail("Stale coordinates were dispatched"); }
        catch (ActionException expected) {
            assertEquals("stale_snapshot", expected.getCode()); assertEquals("window_changed", expected.getReason());
        }
        assertEquals(0, dispatched.get());
        assertEquals(0, activity.confirmationCount());
    }
    private void replay(int type, int windowId) throws Exception {
        Method record = DroyAccessibilityService.class.getDeclaredMethod("recordUiChange", int.class, int.class);
        record.setAccessible(true);
        instrumentation.runOnMainSync(() -> {
            try { record.invoke(service, type, windowId); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
    }
    private long generation(String method) throws Exception {
        return (Long) invoke(method, new Class<?>[0], new Object[0]);
    }
    private String barReceiptState() throws Exception {
        StringBuilder result = new StringBuilder("barWindow=").append(barWindowId);
        for (String name : new String[]{"windowGeometryGeneration", "eventContentRegion"}) {
            java.lang.reflect.Field field = DroyAccessibilityService.class.getDeclaredField(name);
            field.setAccessible(true); result.append(", ").append(name).append('=').append(field.get(service));
        }
        return result.toString();
    }
    private Object invokeOnService(String name) throws Exception {
        Method method = DroyAccessibilityService.class.getDeclaredMethod(name); method.setAccessible(true);
        return method.invoke(service);
    }
    private Object invoke(String name, Class<?>[] types, Object[] args) throws Exception {
        Method method = DroyAccessibilityService.class.getDeclaredMethod(name, types); method.setAccessible(true);
        return method.invoke(null, args);
    }
}
