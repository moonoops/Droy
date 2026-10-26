package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.AndroidUseExecutor;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.DroyAccessibilityService;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Requires real su approval for the target app UID. No root test is skipped. */
@RunWith(AndroidJUnit4.class)
public class RootChannelDeviceTest {
    private static final Cancellation ACTIVE = () -> {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Root device test interrupted");
    };
    private Instrumentation instrumentation;
    private UiAutomation automation;
    private Context context;
    private AndroidUseExecutor executor;
    private TestPageActivity activity;

    @Before public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        executor = new AndroidUseExecutor(context);
    }

    @After public void tearDown() {
        if (activity != null) instrumentation.runOnMainSync(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) activity.finish();
        });
    }

    /** Run alone to check su approval without injecting any touch or key event. */
    @Test public void rootAuthorizationProbeReturnsRealScreenshot() throws Exception {
        openPractice();
        JSONObject result = rootScreenshot();
        assertEquals("last_command_succeeded", executor.capabilities(true).getString("rootAuthorization"));
        assertRootPng(result);
    }

    @Test public void rootCoordinateTapConfirmsTextInOwnPracticeActivity() throws Exception {
        openPractice();
        String text = "Root 点击真实验证 你好";
        instrumentation.runOnMainSync(() -> {
            EditText input = activity.findViewById(R.id.test_input);
            input.setText(text);
            input.clearFocus();
            hideKeyboard();
        });
        instrumentation.waitForIdleSync();
        Rect button = viewBounds(R.id.test_confirm);
        assertOwnForeground();
        JSONObject basis = coordinateBasis();
        JSONObject receipt = rootAction("tap", new JSONObject().put("snapshotId", basis.getString("snapshotId"))
                .put("x", button.centerX()).put("y", button.centerY()));
        assertEquals("action_completed", receipt.getString("status"));
        assertFalse(receipt.getBoolean("goalVerified"));
        awaitMain("Root tap did not update the practice result", () ->
                ("已确认：" + text).equals(((TextView) activity.findViewById(R.id.test_result)).getText().toString()));
    }

    @Test public void rootCancellationBeforeProcessStartDoesNotTouchTheApp() throws Exception {
        openPractice();
        Rect button = viewBounds(R.id.test_confirm);
        assertOwnForeground();
        JSONObject basis = coordinateBasis();
        AtomicBoolean launchCheckReached = new AtomicBoolean();
        try {
            executor.execute("tap", new JSONObject().put("channel", "root").put("snapshotId", basis.getString("snapshotId"))
                    .put("x", button.centerX()).put("y", button.centerY()), true, () -> {
                        // Cancel at CommandRunner's pre-ProcessBuilder check. This
                        // follows the lifecycle boundary rather than counting the
                        // growing number of snapshot and authorization checks.
                        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                            if (frame.getClassName().equals("io.droyagent.androiduse.CommandRunner")
                                    && frame.getMethodName().equals("run")) {
                                launchCheckReached.set(true);
                                throw new InterruptedException("cancelled at command launch");
                            }
                        }
                    });
            fail("Expected cancellation before the root command starts");
        } catch (InterruptedException expected) {
            assertTrue("Cancellation did not reach the pre-process boundary", launchCheckReached.get());
        }
        instrumentation.runOnMainSync(() ->
                assertEquals("尚未确认", ((TextView) activity.findViewById(R.id.test_result)).getText().toString()));
    }

    @Test public void rootSwipeScrollsOwnFixtureAndBackClosesIt() throws Exception {
        openPractice();
        AtomicReference<ScrollView> fixture = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            hideKeyboard();
            ScrollView scroll = new ScrollView(activity);
            LinearLayout rows = new LinearLayout(activity);
            rows.setOrientation(LinearLayout.VERTICAL);
            int rowHeight = Math.round(72 * activity.getResources().getDisplayMetrics().density);
            for (int index = 0; index < 60; index++) {
                TextView row = new TextView(activity);
                row.setText("DroyAgent root 滚动测试行 " + index);
                row.setTextSize(20);
                rows.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight));
            }
            scroll.addView(rows);
            // Only this test fixture is changed; production activity code remains unchanged.
            activity.setContentView(scroll);
            fixture.set(scroll);
        });
        instrumentation.waitForIdleSync();
        AtomicReference<Rect> bounds = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            ScrollView scroll = fixture.get();
            Rect visible = new Rect();
            assertTrue(scroll.getGlobalVisibleRect(visible));
            int[] location = new int[2];
            scroll.getLocationOnScreen(location);
            visible.set(location[0], location[1], location[0] + scroll.getWidth(), location[1] + scroll.getHeight());
            assertTrue("Scroll fixture is too small", visible.height() > 300);
            assertEquals(0, fixture.get().getScrollY());
            bounds.set(visible);
        });
        Rect area = bounds.get();
        assertOwnForeground();
        JSONObject basis = coordinateBasis();
        JSONObject swipe = rootAction("swipe", new JSONObject().put("snapshotId", basis.getString("snapshotId"))
                .put("startX", area.centerX()).put("startY", area.top + area.height() * 3 / 4)
                .put("endX", area.centerX()).put("endY", area.top + area.height() / 4).put("durationMs", 500));
        assertEquals("action_completed", swipe.getString("status"));
        awaitMain("Root swipe did not scroll the app's test fixture", () -> fixture.get().getScrollY() > 0);
        assertOwnForeground();
        JSONObject back = rootAction("back", new JSONObject());
        assertEquals("action_completed", back.getString("status"));
        awaitMain("Root back did not close TestPageActivity", () -> activity.isFinishing() || activity.isDestroyed());
    }

    @Test public void revokedRootGrantUsesAccessibilityAndControlRevocationStillStopsActions() throws Exception {
        openPractice();
        SharedPreferences settings = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        String[] keys = {"allowObserve", "allowControl", "allowScreenshot", "allowRoot"};
        Map<String, Boolean> previous = new HashMap<>();
        for (String key : keys) if (settings.contains(key)) previous.put(key, settings.getBoolean(key, false));
        String token = "root-device-test-" + UUID.randomUUID();
        ProductBridge bridge = new ProductBridge(context, token);
        try {
            assertTrue(settings.edit().putBoolean("allowObserve", true).putBoolean("allowControl", true)
                    .putBoolean("allowScreenshot", true).putBoolean("allowRoot", true).commit());
            bridge.start(5000, false);
            bridge.policy.enable();
            assertOwnForeground();
            JSONObject basis = null;
            String basisReason = "not_captured";
            for (int attempt = 0; attempt < 3; attempt++) {
                JSONObject before = post(bridge, token, "screenshot", "root-before-revocation-" + attempt, new JSONObject());
                assertTrue("Real root authorization is required; bridge failure: " + errorCode(before), before.getBoolean("ok"));
                JSONObject captured = before.getJSONObject("result");
                assertRootPng(captured);
                if ("available".equals(captured.optString("coordinateBasisStatus")) && captured.has("snapshotId")) {
                    basis = captured;
                    break;
                }
                basisReason = captured.optString("coordinateBasisReason", "snapshot_missing");
                // Only repeat the read-only capture; no input has been sent.
                SystemClock.sleep(150);
            }
            assertNotNull("Bridge screenshot did not establish a coordinate basis: " + basisReason, basis);
            Rect button = viewBounds(R.id.test_confirm);
            assertTrue(settings.edit().putBoolean("allowRoot", false).commit());
            assertOwnForeground();
            JSONObject adapted = post(bridge, token, "tap", "tap-after-root-revocation",
                    new JSONObject().put("snapshotId", basis.getString("snapshotId"))
                            .put("x", button.centerX()).put("y", button.centerY()));
            assertTrue(adapted.toString(), adapted.getBoolean("ok"));
            assertEquals("accessibility", adapted.getJSONObject("result").getString("channel"));
            DeviceUi.await("Adaptive tap must reach the practice app", 3000, () -> {
                java.util.concurrent.atomic.AtomicBoolean confirmed = new java.util.concurrent.atomic.AtomicBoolean();
                instrumentation.runOnMainSync(() -> confirmed.set(((TextView) activity.findViewById(R.id.test_result)).getText().toString().startsWith("已确认：")));
                return confirmed.get();
            });
            assertTrue(settings.edit().putBoolean("allowControl", false).putBoolean("allowScreenshot", false).commit());
            assertPermissionDenied(post(bridge, token, "back", "root-back-after-revocation", new JSONObject()));
            assertPermissionDenied(post(bridge, token, "screenshot", "root-screen-after-revocation", new JSONObject()));
            instrumentation.runOnMainSync(() -> {
                assertFalse(activity.isFinishing());
                assertTrue(((TextView) activity.findViewById(R.id.test_result)).getText().toString().startsWith("已确认："));
            });
        } finally {
            bridge.policy.stop();
            bridge.stop();
            SharedPreferences.Editor restore = settings.edit();
            for (String key : keys) {
                if (previous.containsKey(key)) restore.putBoolean(key, previous.get(key));
                else restore.remove(key);
            }
            assertTrue("Could not restore root test product preferences", restore.commit());
        }
    }

    private void openPractice() {
        Intent intent = new Intent(context, TestPageActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (TestPageActivity) instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
        awaitMain("Practice activity did not receive focus; unlock the phone and close overlays", activity::hasWindowFocus);
        assertOwnForeground();
    }

    private JSONObject rootScreenshot() throws Exception {
        assertOwnForeground();
        return rootAction("screenshot", new JSONObject());
    }

    private JSONObject coordinateBasis() throws Exception {
        JSONObject last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            last = rootScreenshot();
            assertRootPng(last);
            if ("available".equals(last.optString("coordinateBasisStatus")) && last.has("snapshotId")) return last;
            // Capturing again is safe; no modifying action has been dispatched.
            SystemClock.sleep(150);
        }
        throw new AssertionError("Root screenshot did not establish a stable coordinate basis: "
                + (last == null ? "none" : last.optString("coordinateBasisReason")));
    }

    private JSONObject rootAction(String action, JSONObject args) throws Exception {
        try {
            return executor.execute(action, args.put("channel", "root"), true, ACTIVE);
        } catch (ActionException failure) {
            throw new AssertionError("Real root channel failed (" + failure.getCode()
                    + "). Grant su to the DroyAgent app UID before running root device tests.", failure);
        }
    }

    private static void assertRootPng(JSONObject capture) throws Exception {
        assertEquals("captured", capture.getString("status"));
        assertEquals("root", capture.getString("channel"));
        assertEquals("image/png", capture.getString("mimeType"));
        assertEquals("sRGB", capture.getString("colorSpace"));
        byte[] png = Base64.decode(capture.getString("base64"), Base64.DEFAULT);
        assertTrue("Root did not return a PNG image", png.length > 29);
        assertArrayEquals(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}, java.util.Arrays.copyOf(png, 8));
        assertEquals(8, png[24]);
        assertTrue(png[25] == 2 || png[25] == 6);
        assertEquals(0, png[28]);
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
        assertNotNull("Root PNG cannot be decoded", bitmap);
        try {
            assertEquals(capture.getInt("width"), bitmap.getWidth());
            assertEquals(capture.getInt("height"), bitmap.getHeight());
            assertTrue(bitmap.getWidth() > 0 && bitmap.getHeight() > 0);
            assertNotNull(bitmap.getColorSpace());
            assertTrue(bitmap.getColorSpace().isSrgb());
        } finally {
            bitmap.recycle();
        }
    }

    private void assertOwnForeground() {
        assertFalse("Foreground checks must run on the instrumentation thread", Looper.myLooper() == Looper.getMainLooper());
        instrumentation.runOnMainSync(() -> {
            assertFalse("Practice activity is finishing", activity.isFinishing());
            assertFalse("Practice activity was destroyed", activity.isDestroyed());
            assertTrue("Another window has focus; refusing root input", activity.hasWindowFocus());
        });
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        assertNotNull("Cannot verify the foreground app; refusing root input", root);
        try {
            assertEquals("Another app is in the foreground; refusing root input",
                    context.getPackageName(), String.valueOf(root.getPackageName()));
        } finally {
            root.recycle();
        }
    }

    private Rect viewBounds(int id) {
        AtomicReference<Rect> value = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            View view = activity.findViewById(id);
            assertNotNull(view);
            assertTrue(view.isShown() && view.isEnabled());
            Rect bounds = new Rect();
            assertTrue(view.getGlobalVisibleRect(bounds));
            assertTrue("Root target has no visible bounds", bounds.width() > 0 && bounds.height() > 0);
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            bounds.set(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
            value.set(bounds);
        });
        return value.get();
    }

    private void hideKeyboard() {
        InputMethodManager input = activity.getSystemService(InputMethodManager.class);
        if (input != null) input.hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0);
    }

    private void awaitMain(String failure, BooleanSupplier condition) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        AtomicReference<Boolean> satisfied = new AtomicReference<>(false);
        do {
            instrumentation.runOnMainSync(() -> satisfied.set(condition.getAsBoolean()));
            if (satisfied.get()) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(failure);
    }

    private static JSONObject post(ProductBridge bridge, String token, String action, String call, JSONObject args) throws Exception {
        byte[] body = new JSONObject().put("taskId", "root-permission-device-test").put("callId", call)
                .put("action", action).put("args", args.put("channel", "root")).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + bridge.getListeningPort() + "/tool").openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(20000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setFixedLengthStreamingMode(body.length);
        connection.setDoOutput(true);
        try {
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
            assertEquals(200, connection.getResponseCode());
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    assertTrue("Root screenshot bridge response exceeded its limit", (long) bytes.size() + count <= 24 * 1024 * 1024);
                    bytes.write(buffer, 0, count);
                }
                return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String errorCode(JSONObject response) {
        JSONObject error = response.optJSONObject("error");
        return error == null ? "none" : error.optString("code");
    }

    private static void assertPermissionDenied(JSONObject response) throws Exception {
        assertFalse("Revoked root action was accepted", response.getBoolean("ok"));
        assertEquals("PERMISSION_DENIED", errorCode(response));
    }
}
