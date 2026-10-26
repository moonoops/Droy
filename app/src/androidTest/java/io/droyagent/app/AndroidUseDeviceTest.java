package io.droyagent.app;

import android.app.Instrumentation;
import android.app.Activity;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.AndroidUseExecutor;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.DroyAccessibilityService;
import java.lang.reflect.Method;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

/** Device tests of the native/HTTP boundary; self UI checks use DSH without model calls. */
@RunWith(AndroidJUnit4.class)
public class AndroidUseDeviceTest {
    private static final Cancellation ACTIVE = () -> {};
    private Instrumentation instrumentation;
    private Context context;
    private AndroidUseExecutor executor;
    private TestPageActivity activity;
    private MainActivity productActivity;
    private boolean ownsProductRuntime;

    @Before public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        // Instrumentation's normal UiAutomation connection would suppress our service.
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        executor = new AndroidUseExecutor(context);
    }

    @After public void tearDown() throws Exception {
        if (productActivity != null) instrumentation.runOnMainSync(productActivity::finish);
        if (ownsProductRuntime) {
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("Protected UI test left its automatic engine startup active", 15000, RuntimeService::canManageHistory);
        }
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
    }

    @Test public void observeChineseInputTapAndVerifyOnRealAccessibilityTree() throws Exception {
        openPractice();
        String text = "安卓独立运行，中文验证 你好🌙";
        JSONObject input = nodeAction("input_text", "test_input", new JSONObject().put("text", text));
        assertEquals("action_completed", input.getString("status"));
        assertFalse("An action receipt is not task verification", input.getBoolean("goalVerified"));
        assertTrue("Exact input must be read back separately from action acceptance", input.getBoolean("inputTextVerified"));
        assertFalse("Writing text must not imply an explicit search/form submit action", input.getBoolean("submitActionRequested"));
        JSONObject typed = observeUntil("test_input", text);
        assertEquals(text, findNode(typed, "test_input").getString("text"));
        nodeAction("tap", "test_confirm", new JSONObject());
        JSONObject confirmed = observeUntil("test_result", "已确认：" + text);
        assertEquals("已确认：" + text, findNode(confirmed, "test_result").getString("text"));
        assertEquals("已确认：" + text, resultText());
    }

    @Test public void changedNodeCannotBeClickedUsingAnOlderSnapshot() throws Exception {
        openPractice();
        JSONObject old = observeUntil("test_confirm", "确认");
        JSONObject target = findNode(old, "test_confirm");
        instrumentation.runOnMainSync(() -> ((Button) activity.findViewById(R.id.test_confirm)).setText("确认已更新"));
        expectAction("stale_snapshot", () -> executor.execute("tap", new JSONObject()
                .put("snapshotId", old.getString("snapshotId")).put("nodeId", target.getString("nodeId")), false, ACTIVE));
        assertEquals("尚未确认", resultText());
    }

    @Test public void delayedSameWindowEventDoesNotInvalidateAnUnchangedNode() throws Exception {
        openPractice();
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        instrumentation.runOnMainSync(() -> activity.getWindow().setTitle("Droy regression window A"));
        automation.waitForIdle(300, 5000);
        JSONObject old = settledObservation("test_confirm", "确认");
        int windowId = activeWindowId(automation);
        AccessibilityEvent titleEvent = automation.executeAndWaitForEvent(() -> instrumentation.runOnMainSync(() ->
                activity.getWindow().setTitle("Droy regression window B")),
                event -> event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED && event.getWindowId() == windowId
                        && (event.getWindowChanges() & AccessibilityEvent.WINDOWS_CHANGE_TITLE) != 0, 3000);
        titleEvent.recycle();
        // Replay delayed callbacks into the service, not fake window-manager
        // events into Android's global cache (which changes system metadata).
        Method current = DroyAccessibilityService.class.getDeclaredMethod("current", Context.class);
        current.setAccessible(true);
        DroyAccessibilityService service = (DroyAccessibilityService) current.invoke(null, context);
        assertNotNull(service);
        instrumentation.runOnMainSync(() -> {
            for (int eventType : new int[]{AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED}) {
                AccessibilityEvent delayed = AccessibilityEvent.obtain(eventType);
                try { service.onAccessibilityEvent(delayed); }
                finally { delayed.recycle(); }
            }
        });
        automation.waitForIdle(150, 3000);
        JSONObject result = executor.execute("tap", nodeArguments(old, "test_confirm"), false, ACTIVE);
        assertEquals("action_completed", result.getString("status"));
        assertEquals("已确认：", resultText());
    }

    @Test public void switchingToAnotherWindowAndBackRevokesTheOldNodeBasis() throws Exception {
        openPractice();
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        automation.waitForIdle(300, 5000);
        JSONObject old = executor.execute("observe", new JSONObject(), false, ACTIVE);
        int originalWindowId = activeWindowId(automation);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(TestPageActivity.class.getName(), null, false);
        Activity other = null;
        try {
            // NEW_TASK can route this identical component to the existing task
            // instead of creating the second window required by the regression.
            instrumentation.runOnMainSync(() -> activity.startActivity(new Intent(activity, TestPageActivity.class)));
            other = monitor.waitForActivityWithTimeout(5000);
            assertNotNull("The fixture must create another Activity instance", other);
            assertNotSame(activity, other);
            awaitActivityWindow(other, automation, originalWindowId, true);
            automation.waitForIdle(150, 3000);
            assertNotEquals("The fixture must actually change the active window", originalWindowId, activeWindowId(automation));
        } finally {
            instrumentation.removeMonitor(monitor);
            if (other != null) {
                Activity closing = other;
                instrumentation.runOnMainSync(closing::finish);
            }
        }
        awaitActivityWindow(activity, automation, originalWindowId, false);
        automation.waitForIdle(150, 3000);
        assertEquals(originalWindowId, activeWindowId(automation));
        try {
            executor.execute("tap", nodeArguments(old, "test_confirm"), false, ACTIVE);
            fail("A real window switch must revoke the previous basis");
        } catch (ActionException expected) {
            assertEquals("stale_snapshot", expected.getCode());
            assertEquals("The window epoch must revoke the basis, independently of its age", "window_changed", expected.getReason());
        }
        assertEquals("尚未确认", resultText());
    }

    private void awaitActivityWindow(Activity target, UiAutomation automation, int previousWindowId, boolean different) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 7000;
        do {
            AtomicReference<Boolean> focused = new AtomicReference<>(false);
            instrumentation.runOnMainSync(() -> focused.set(target.hasWindowFocus()
                    && target.getWindow().getDecorView().isAttachedToWindow()));
            if (focused.get()) {
                AccessibilityNodeInfo root = automation.getRootInActiveWindow();
                if (root != null) {
                    try {
                        boolean expectedWindow = different ? root.getWindowId() != previousWindowId : root.getWindowId() == previousWindowId;
                        if (expectedWindow && root.getPackageName() != null && context.getPackageName().contentEquals(root.getPackageName())) return;
                    } finally { root.recycle(); }
                }
            }
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("The expected Activity window did not become attached, focused and active");
    }

    private static int activeWindowId(UiAutomation automation) {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        assertNotNull("The fixture needs an exposed active window", root);
        try { return root.getWindowId(); }
        finally { root.recycle(); }
    }

    @Test public void unrelatedDynamicUpdatesAllowOneObservationAndOneNodeAction() throws Exception {
        openPractice();
        // Activity startup can deliver delayed focus/IME window events after
        // main-thread idle. The regression concerns later unrelated content,
        // so settle those genuine window transitions before the one-shot basis.
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).waitForIdle(300, 5000);
        instrumentation.runOnMainSync(() -> {
            activity.makePageClickable();
            activity.startUnrelatedUpdates();
        });
        instrumentation.waitForIdleSync();
        JSONObject beforeInput = settledObservation("test_input", "");
        int beforeRefresh = refreshCount();
        awaitUnrelatedRefresh(beforeRefresh);
        String chinese = "m6 macmini 的价格，中文输入🌙";
        JSONObject input = executor.execute("input_text", nodeArguments(beforeInput, "test_input").put("text", chinese), false, ACTIVE);
        assertTrue(input.toString(), input.getBoolean("inputTextVerified"));
        assertEquals(chinese, inputText());

        JSONObject beforeClick = settledObservation("test_confirm", "确认");
        beforeRefresh = refreshCount();
        awaitUnrelatedRefresh(beforeRefresh);
        JSONObject clicked = executor.execute("tap", nodeArguments(beforeClick, "test_confirm"), false, ACTIVE);
        assertEquals("action_completed", clicked.getString("status"));
        assertEquals("已确认：" + chinese, resultText());
        AtomicInteger count = new AtomicInteger();
        instrumentation.runOnMainSync(() -> count.set(activity.confirmationCount()));
        assertEquals("The confirm action must be dispatched exactly once", 1, count.get());
        expectAction("stale_snapshot", () -> executor.execute("tap", nodeArguments(beforeClick, "test_confirm"), false, ACTIVE));
    }

    @Test public void userEditedTextCannotBeOverwrittenUsingAnOlderSnapshot() throws Exception {
        openPractice();
        JSONObject old = observeUntil("test_input", null);
        instrumentation.runOnMainSync(() -> ((EditText) activity.findViewById(R.id.test_input)).setText("用户刚刚输入"));
        expectAction("stale_snapshot", () -> executor.execute("input_text", nodeArguments(old, "test_input")
                .put("text", "不应覆盖"), false, ACTIVE));
        assertEquals("用户刚刚输入", inputText());
    }

    @Test public void movedNodeCannotBeClickedUsingAnOlderSnapshot() throws Exception {
        openPractice();
        JSONObject old = observeUntil("test_confirm", "确认");
        instrumentation.runOnMainSync(() -> activity.findViewById(R.id.test_confirm).setTranslationY(20));
        expectAction("stale_snapshot", () -> executor.execute("tap", nodeArguments(old, "test_confirm"), false, ACTIVE));
        assertEquals("尚未确认", resultText());
    }

    @Test public void replacementWithSameLabelCannotReuseTheOriginalNode() throws Exception {
        openPractice();
        JSONObject old = observeUntil("test_confirm", "确认");
        instrumentation.runOnMainSync(() -> {
            Button original = activity.findViewById(R.id.test_confirm);
            ViewGroup parent = (ViewGroup) original.getParent();
            int index = parent.indexOfChild(original);
            Button replacement = new Button(activity);
            replacement.setId(R.id.test_confirm);
            replacement.setText("确认");
            replacement.setOnClickListener(v -> ((TextView) activity.findViewById(R.id.test_result)).setText("误点替换按钮"));
            parent.removeViewAt(index);
            parent.addView(replacement, index);
        });
        instrumentation.waitForIdleSync();
        expectAction("stale_snapshot", () -> executor.execute("tap", nodeArguments(old, "test_confirm"), false, ACTIVE));
        assertEquals("尚未确认", resultText());
    }

    @Test public void sameWindowOverlayCannotBeBypassedWithANodeAction() throws Exception {
        openPractice();
        JSONObject old = observeUntil("test_confirm", "确认");
        instrumentation.runOnMainSync(() -> activity.coverControl(R.id.test_confirm));
        instrumentation.waitForIdleSync();
        expectAction("stale_snapshot", () -> executor.execute("tap", nodeArguments(old, "test_confirm"), false, ACTIVE));
        assertEquals("尚未确认", resultText());
    }

    @Test public void changedDescendantCannotRebindClickableContainer() throws Exception {
        assertReboundItemRejected(false);
    }

    @Test public void changedCollectionItemCannotRebindGenericChildButton() throws Exception {
        assertReboundItemRejected(true);
    }

    private void assertReboundItemRejected(boolean childButton) throws Exception {
        openPractice();
        instrumentation.runOnMainSync(() -> activity.showReusableItem(childButton));
        instrumentation.waitForIdleSync();
        JSONObject old = observeUntil("test_confirm", null);
        AtomicReference<View> original = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            original.set(activity.findViewById(R.id.test_confirm));
            activity.rebindItemTitle();
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> assertSame("The fixture must reuse the same action View", original.get(), activity.findViewById(R.id.test_confirm)));
        expectAction("stale_snapshot", () -> executor.execute("tap", nodeArguments(old, "test_confirm"), false, ACTIVE));
        assertEquals("尚未确认", resultText());
    }

    @Test public void stoppedTaskCannotExecuteAnAlreadyQueuedAction() throws Exception {
        openPractice();
        JSONObject observation = observeUntil("test_confirm", "确认");
        JSONObject args = new JSONObject().put("snapshotId", observation.getString("snapshotId"))
                .put("nodeId", findNode(observation, "test_confirm").getString("nodeId"));
        ControlPolicy policy = new ControlPolicy();
        policy.enable();
        ControlPolicy.Ticket ticket = policy.begin("queued-stop-test", "tap");
        CountDownLatch mainBlocked = new CountDownLatch(1), releaseMain = new CountDownLatch(1);
        CountDownLatch actionQueued = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        new Handler(Looper.getMainLooper()).post(() -> {
            mainBlocked.countDown();
            try {
                releaseMain.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        Thread worker = new Thread(() -> {
            try {
                result.complete(executor.execute("tap", args, false, () -> {
                    ticket.check();
                    if (checks.incrementAndGet() >= 3) actionQueued.countDown();
                }));
            } catch (Exception error) {
                result.completeExceptionally(error);
            }
        }, "droy-stop-device-test");
        try {
            assertTrue("Main-thread barrier was not reached", mainBlocked.await(1, TimeUnit.SECONDS));
            worker.start();
            assertTrue("Action was not queued", actionQueued.await(1, TimeUnit.SECONDS));
            policy.stop();
            try {
                result.get(1, TimeUnit.SECONDS);
                fail("Stopped task returned a success receipt");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof InterruptedException);
            }
        } finally {
            policy.stop();
            releaseMain.countDown();
            worker.join(2000);
            policy.finish(ticket);
        }
        instrumentation.waitForIdleSync();
        assertEquals("尚未确认", resultText());
    }

    @Test public void revokedProductPermissionBlocksFurtherHttpActions() throws Exception {
        openPractice();
        SharedPreferences settings = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        String[] keys = {"allowObserve", "allowControl", "allowScreenshot", "allowRoot"};
        Map<String, Boolean> previous = new HashMap<>();
        for (String key : keys) if (settings.contains(key)) previous.put(key, settings.getBoolean(key, false));
        String token = "device-test-" + UUID.randomUUID();
        ProductBridge bridge = new ProductBridge(context, token);
        try {
            assertTrue(settings.edit().putBoolean("allowObserve", true).putBoolean("allowControl", true)
                    .putBoolean("allowScreenshot", false).putBoolean("allowRoot", false).commit());
            bridge.start(5000, false);
            bridge.policy.enable();
            String chinese = "HTTP 中文 UTF-8 回环验证 你好🌙";
            JSONObject observed = post(bridge, token, "observe", "observe-before-input", new JSONObject());
            assertTrue(observed.toString(), observed.getBoolean("ok"));
            JSONObject snapshot = observed.getJSONObject("result");
            JSONObject write = post(bridge, token, "input_text", "allowed-chinese-input", nodeArguments(snapshot, "test_input").put("text", chinese));
            assertTrue("Authorized HTTP Chinese input failed: " + write, write.getBoolean("ok"));
            assertTrue(write.getJSONObject("result").getBoolean("inputTextVerified"));
            assertEquals("HTTP request decoding must preserve Chinese and supplementary Unicode", chinese, inputText());
            JSONObject confirmed = post(bridge, token, "observe", "observe-chinese-roundtrip", new JSONObject());
            assertTrue(confirmed.toString(), confirmed.getBoolean("ok"));
            snapshot = confirmed.getJSONObject("result");
            assertEquals("HTTP response must preserve the exact UI text", chinese, findNode(snapshot, "test_input").getString("text"));
            JSONObject args = new JSONObject().put("snapshotId", snapshot.getString("snapshotId"))
                    .put("nodeId", findNode(snapshot, "test_input").getString("nodeId")).put("text", "不应写入");
            assertTrue(settings.edit().putBoolean("allowControl", false).commit());
            assertFailure("PERMISSION_DENIED", post(bridge, token, "input_text", "input-after-revoke", args));
            assertEquals(chinese, inputText());
            assertFailure("PERMISSION_DENIED", post(bridge, token, "screenshot", "screenshot-without-grant", new JSONObject()));
            assertFailure("PERMISSION_DENIED", post(bridge, token, "back", "root-without-grant", new JSONObject().put("channel", "root")));
            assertTrue(settings.edit().putBoolean("allowObserve", false).commit());
            assertFailure("PERMISSION_DENIED", post(bridge, token, "observe", "observe-after-revoke", new JSONObject()));
            assertEquals("尚未确认", resultText());
        } finally {
            bridge.policy.stop();
            bridge.stop();
            SharedPreferences.Editor restore = settings.edit();
            for (String key : keys) {
                if (previous.containsKey(key)) restore.putBoolean(key, previous.get(key));
                else restore.remove(key);
            }
            assertTrue("Could not restore product test preferences", restore.commit());
        }
    }

    @Test public void screenshotIsSrgbPngAcceptedByThePackagedMobileDecoder() throws Exception {
        openPractice();
        JSONObject capture = executor.execute("screenshot", new JSONObject(), false, ACTIVE);
        assertEquals("captured", capture.getString("status"));
        assertEquals("image/png", capture.getString("mimeType"));
        assertEquals("sRGB", capture.getString("colorSpace"));
        byte[] png = Base64.decode(capture.getString("base64"), Base64.DEFAULT);
        assertArrayEquals(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}, java.util.Arrays.copyOf(png, 8));
        assertEquals(8, png[24]);
        assertTrue("Mobile decoder requires RGB or RGBA", png[25] == 2 || png[25] == 6);
        assertEquals(0, png[28]);
        Bitmap decoded = BitmapFactory.decodeByteArray(png, 0, png.length);
        assertNotNull(decoded);
        try {
            assertEquals(capture.getInt("width"), decoded.getWidth());
            assertEquals(capture.getInt("height"), decoded.getHeight());
            assertNotNull(decoded.getColorSpace());
            assertTrue("PNG was not encoded in sRGB", decoded.getColorSpace().isSrgb());
        } finally {
            decoded.recycle();
        }
        JSONObject mobile = runPackagedDecoder(png);
        assertEquals(capture.getInt("width"), mobile.getInt("width"));
        assertEquals(capture.getInt("height"), mobile.getInt("height"));
        assertTrue(mobile.getInt("channels") == 3 || mobile.getInt("channels") == 4);
    }

    @Test public void productPagesAllowAuthorizedReadingScreenshotsAndControl() throws Exception {
        openPractice();
        SharedPreferences settings = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        String[] keys = {"allowObserve", "allowControl", "allowScreenshot", "allowRoot", "allowApps"};
        Map<String, Boolean> previous = new HashMap<>();
        for (String key : keys) if (settings.contains(key)) previous.put(key, settings.getBoolean(key, false));
        String token = "self-ui-test-" + UUID.randomUUID();
        ProductBridge bridge = new ProductBridge(context, token);
        try {
            assertTrue(settings.edit().putBoolean("allowObserve", true).putBoolean("allowControl", true)
                    .putBoolean("allowScreenshot", true).putBoolean("allowRoot", false).putBoolean("allowApps", true).commit());
            bridge.start(5000, false);
            bridge.policy.enable();
            assertTrue("Stop the existing engine before the self UI test", RuntimeService.canManageHistory());
            ownsProductRuntime = true;
            productActivity = (MainActivity) instrumentation.startActivitySync(
                    new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            instrumentation.waitForIdleSync();
            DeviceUi ui = new DeviceUi(instrumentation, productActivity);
            ui.awaitPage();
            for (String page : new String[]{"conversation", "permissions", "diagnostics"}) {
                if (page.equals("conversation")) ui.invoke("showChat");
                else if (page.equals("permissions")) ui.openPermissions();
                else ui.invoke("showDiagnostics");
                instrumentation.waitForIdleSync();
                assertTrue("The product page is not visible", MainActivity.isSensitiveUiVisible());
                assertFalse(bridge.capabilityStatus().getJSONObject("product").getBoolean("productUiProtected"));
                JSONObject observed = post(bridge, token, "observe", page + "-observe", new JSONObject());
                assertTrue("Authorized self UI observation failed", observed.getBoolean("ok"));
                assertEquals(context.getPackageName(), observed.getJSONObject("result").getString("packageName"));
                JSONObject captured = post(bridge, token, "screenshot", page + "-screenshot", new JSONObject());
                assertTrue("Authorized self UI screenshot failed", captured.getBoolean("ok"));
                assertEquals("captured", captured.getJSONObject("result").getString("status"));
                if (!page.equals("conversation")) {
                    File evidence = new File(context.getExternalFilesDir(null), "self-ui-evidence");
                    assertTrue(evidence.isDirectory() || evidence.mkdirs());
                    Files.write(new File(evidence, page + ".png").toPath(),
                        Base64.decode(captured.getJSONObject("result").getString("base64"), Base64.DEFAULT));
                }
            }

            // Exercise a benign, real Droy control rather than changing a grant.
            JSONObject observed = post(bridge, token, "observe", "diagnostics-control-basis", new JSONObject()).getJSONObject("result");
            JSONObject back = null;
            JSONArray nodes = observed.getJSONArray("nodes");
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.getJSONObject(i);
                if (node.optBoolean("clickable") && "返回".equals(node.optString("text"))) { back = node; break; }
            }
            assertNotNull("The real diagnostics Back button was not observed", back);
            JSONObject tapped = post(bridge, token, "tap", "diagnostics-back", new JSONObject()
                .put("snapshotId", observed.getString("snapshotId")).put("nodeId", back.getString("nodeId")));
            assertTrue("Authorized self UI click failed", tapped.getBoolean("ok"));
            DeviceUi.await("The Droy Back button did not return to the conversation", 5000,
                () -> ui.nativeNavigationState().getBoolean("chatVisible"));

            assertTrue(settings.edit().putBoolean("allowObserve", false).commit());
            assertFailure("PERMISSION_DENIED", post(bridge, token, "observe", "self-ui-read-revoked", new JSONObject()));
            assertTrue(settings.edit().putBoolean("allowObserve", true).putBoolean("allowControl", false).commit());
            assertFailure("PERMISSION_DENIED", post(bridge, token, "back", "self-ui-control-revoked", new JSONObject()));
            assertFalse("The self UI test must not grant Root", settings.getBoolean("allowRoot", false));
        } finally {
            bridge.policy.stop();
            bridge.stop();
            SharedPreferences.Editor restore = settings.edit();
            for (String key : keys) {
                if (previous.containsKey(key)) restore.putBoolean(key, previous.get(key));
                else restore.remove(key);
            }
            assertTrue("Could not restore self UI test product preferences", restore.commit());
        }
    }

    /** Run this method separately after disabling the service in system settings. */
    @Test public void disabledSystemAccessibilityRejectsObservationAndControl() throws Exception {
        assumeFalse("Disable the DroyAgent system accessibility service before running this test",
                executor.capabilities(false).getBoolean("accessibilityConnected"));
        expectAction("accessibility_unavailable", () -> executor.execute("observe", new JSONObject(), false, ACTIVE));
        expectAction("accessibility_unavailable", () -> executor.execute("back", new JSONObject(), false, ACTIVE));
        expectAction("accessibility_unavailable", () -> executor.execute("screenshot", new JSONObject(), false, ACTIVE));
    }

    private void openPractice() throws Exception {
        // Some Android vendors freeze a background instrumentation process while it
        // waits for a service. Bring our own fixture forward before any polling.
        Intent intent = new Intent(context, TestPageActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (TestPageActivity) instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
        io.droyagent.androiduse.AccessibilityRecovery.get(context).onAppOpened(false);
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (!executor.capabilities(false).getBoolean("accessibilityConnected") && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100);
        }
        assertTrue("Enable the DroyAgent accessibility service before these device tests",
                executor.capabilities(false).getBoolean("accessibilityConnected"));
        observeUntil("test_input", null);
    }

    private JSONObject settledObservation(String resource, String expectedText) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        Rect previous = null;
        int stable = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            AtomicReference<Rect> actual = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                int id = context.getResources().getIdentifier(resource, "id", context.getPackageName());
                View view = activity.findViewById(id);
                if (view == null || !view.isLaidOut() || view.isLayoutRequested() || !view.hasWindowFocus()) return;
                int[] point = new int[2]; view.getLocationOnScreen(point);
                actual.set(new Rect(point[0], point[1], point[0] + view.getWidth(), point[1] + view.getHeight()));
            });
            Rect bounds = actual.get();
            stable = bounds != null && bounds.equals(previous) ? stable + 1 : 0;
            previous = bounds;
            if (stable >= 2) {
                JSONObject observed = observeUntil(resource, expectedText);
                JSONObject node = findNode(observed, resource).getJSONObject("bounds");
                Rect captured = new Rect(node.getInt("left"), node.getInt("top"), node.getInt("right"), node.getInt("bottom"));
                if (bounds.equals(captured)) return observed;
            }
            SystemClock.sleep(80);
        }
        throw new AssertionError("The observation did not match the settled native target: " + resource);
    }

    private JSONObject observeUntil(String resource, String expectedText) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        JSONObject last = null;
        do {
            try {
                last = executor.execute("observe", new JSONObject(), false, ACTIVE);
                JSONObject node = findNodeOrNull(last, resource);
                if (context.getPackageName().equals(last.optString("packageName")) && node != null
                        && (expectedText == null || expectedText.equals(node.optString("text")))) return last;
            } catch (ActionException unavailable) {
                if (!unavailable.getCode().equals("window_unavailable")) throw unavailable;
            }
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Practice node did not reach the expected state: " + resource
                + " (last foreground=" + (last == null ? "none" : last.optString("packageName")) + ")");
    }

    private JSONObject nodeAction(String action, String resource, JSONObject additional) throws Exception {
        JSONObject observed = observeUntil(resource, null);
        JSONObject args = new JSONObject(additional.toString()).put("snapshotId", observed.getString("snapshotId"))
                .put("nodeId", findNode(observed, resource).getString("nodeId"));
        return executor.execute(action, args, false, ACTIVE);
    }

    private static JSONObject nodeArguments(JSONObject observed, String resource) throws Exception {
        return new JSONObject().put("snapshotId", observed.getString("snapshotId"))
                .put("nodeId", findNode(observed, resource).getString("nodeId"));
    }

    private int refreshCount() {
        AtomicInteger count = new AtomicInteger();
        instrumentation.runOnMainSync(() -> count.set(activity.refreshCount()));
        return count.get();
    }

    private void awaitUnrelatedRefresh(int previous) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 2000;
        while (refreshCount() <= previous + 1 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50);
        assertTrue("The dynamic fixture must change after the snapshot", refreshCount() > previous + 1);
        instrumentation.waitForIdleSync();
    }

    private static JSONObject findNodeOrNull(JSONObject observed, String resource) throws Exception {
        JSONArray nodes = observed.getJSONArray("nodes");
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject node = nodes.getJSONObject(index);
            if (node.optString("resourceId").endsWith(":id/" + resource)) return node;
        }
        return null;
    }

    private static JSONObject findNode(JSONObject observed, String resource) throws Exception {
        JSONObject node = findNodeOrNull(observed, resource);
        assertNotNull("Missing practice node: " + resource, node);
        return node;
    }

    private String resultText() {
        AtomicReference<String> value = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> value.set(((TextView) activity.findViewById(R.id.test_result)).getText().toString()));
        return value.get();
    }

    private String inputText() {
        AtomicReference<String> value = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> value.set(((EditText) activity.findViewById(R.id.test_input)).getText().toString()));
        return value.get();
    }

    private static void expectAction(String code, Checked operation) throws Exception {
        try {
            operation.run();
            fail("Expected " + code);
        } catch (ActionException error) {
            assertEquals(code, error.getCode());
        }
    }

    private static void assertFailure(String code, JSONObject response) throws Exception {
        assertFalse(response.toString(), response.getBoolean("ok"));
        assertEquals(code, response.getJSONObject("error").getString("code"));
    }

    private static JSONObject post(ProductBridge bridge, String token, String action, String call, JSONObject args) throws Exception {
        byte[] body = new JSONObject().put("taskId", "permission-device-test").put("callId", call)
                .put("action", action).put("args", args).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + bridge.getListeningPort() + "/tool").openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setFixedLengthStreamingMode(body.length);
        connection.setDoOutput(true);
        try {
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            assertEquals("Product bridge returned an HTTP failure", 200, status);
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                RuntimeInstaller.copy(input, output);
                return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    private JSONObject runPackagedDecoder(byte[] png) throws Exception {
        File directory = Files.createTempDirectory(context.getCacheDir().toPath(), "png-device-test-").toFile();
        File screenshot = new File(directory, "screen.png"), decoder = new File(directory, "png.mjs");
        Process process = null;
        try {
            Files.write(screenshot.toPath(), png);
            try (ZipInputStream archive = new ZipInputStream(context.getAssets().open("dsh-runtime.zip"))) {
                ZipEntry entry;
                while ((entry = archive.getNextEntry()) != null) {
                    if (entry.getName().equals("mobile/png.mjs")) {
                        try (FileOutputStream out = new FileOutputStream(decoder)) { RuntimeInstaller.copy(archive, out); }
                        break;
                    }
                }
            }
            assertTrue("APK does not contain mobile/png.mjs", decoder.isFile());
            String script = "import {readFileSync} from 'node:fs';import {pathToFileURL} from 'node:url';"
                    + "const {decodePng}=await import(pathToFileURL(process.env.DROY_TEST_DECODER).href);"
                    + "const {width,height,channels}=decodePng(readFileSync(process.env.DROY_TEST_PNG));"
                    + "console.log(JSON.stringify({width,height,channels}));";
            String nativeDir = context.getApplicationInfo().nativeLibraryDir;
            ProcessBuilder builder = new ProcessBuilder(nativeDir + "/libdroy_node.so", "--input-type=module", "-e", script);
            builder.redirectErrorStream(true);
            builder.environment().put("LD_LIBRARY_PATH", nativeDir);
            builder.environment().put("DROY_TEST_DECODER", decoder.getAbsolutePath());
            builder.environment().put("DROY_TEST_PNG", screenshot.getAbsolutePath());
            process = builder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Process running = process;
            AtomicReference<Exception> readFailure = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try (InputStream input = running.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    int size;
                    while ((size = input.read(buffer)) >= 0) {
                        if (output.size() + size > 16384) throw new java.io.IOException("Decoder output exceeded 16 KiB");
                        output.write(buffer, 0, size);
                    }
                } catch (Exception error) {
                    readFailure.set(error);
                    running.destroyForcibly();
                }
            }, "droy-png-device-test-output");
            reader.start();
            assertTrue("Packaged mobile PNG decoder timed out", process.waitFor(15, TimeUnit.SECONDS));
            reader.join(2000);
            assertFalse("Decoder reader did not finish", reader.isAlive());
            if (readFailure.get() != null) throw readFailure.get();
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
            assertEquals(text, 0, process.exitValue());
            return new JSONObject(text.trim());
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(screenshot.toPath());
            Files.deleteIfExists(decoder.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }

    private interface Checked { void run() throws Exception; }
}
