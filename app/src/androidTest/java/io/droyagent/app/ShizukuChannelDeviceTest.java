package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.AndroidUseExecutor;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.DroyAccessibilityService;
import io.droyagent.androiduse.ShizukuAccess;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Requires an authorized, shell-UID Shizuku on a dedicated device. */
@RunWith(AndroidJUnit4.class)
public class ShizukuChannelDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private TestPageActivity activity;
    private AndroidUseExecutor executor;
    private int accessibilityEnabled;
    private final Cancellation active = () -> {};

    @Before public void open() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        accessibilityEnabled = android.provider.Settings.Secure.getInt(context.getContentResolver(), "accessibility_enabled", 0);
        assertTrue("Start Shizuku and authorize Droy before running this class", ShizukuAccess.get(context).available());
        assertEquals("This class validates the non-root shell identity", 2000, ShizukuAccess.get(context).uid());
        activity = (TestPageActivity) instrumentation.startActivitySync(new Intent(context, TestPageActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        executor = new AndroidUseExecutor(context);
        instrumentation.waitForIdleSync();
    }

    @After public void close() {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.provider.Settings.Secure.putInt(context.getContentResolver(), "accessibility_enabled", accessibilityEnabled);
        io.droyagent.androiduse.AccessibilityRecovery.get(context).onAppOpened(false);
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
    }

    @Test public void authorizedShellCapturesDespiteProductRootBeingDisabled() throws Exception {
        JSONObject image = screenshot();
        assertEquals("shizuku", image.getString("channel"));
        assertEquals("image/png", image.getString("mimeType"));
        assertTrue(image.getString("base64").length() > 100);
    }

    @Test public void shellTapAndBackReachTheLocalApp() throws Exception {
        JSONObject image = screenshot();
        Rect target = button();
        JSONObject result = executor.execute("tap", new JSONObject().put("channel", "shizuku")
                .put("snapshotId", image.getString("snapshotId")).put("x", target.centerX()).put("y", target.centerY()), false, active);
        assertEquals("shizuku", result.getString("channel"));
        awaitConfirmed();
        executor.execute("back", new JSONObject().put("channel", "shizuku"), false, active);
        DeviceUi.await("Shell back must finish the practice activity", 3000, activity::isFinishing);
    }

    @Test public void shellScreenshotAndTapWorkWithoutAccessibility() throws Exception {
        try {
            // UiAutomation temporarily suppresses accessibility without altering its saved setting.
            instrumentation.getUiAutomation();
            DeviceUi.await("Accessibility should be suppressed for this case", 5000, () -> !DroyAccessibilityService.isConnected(context));
            JSONObject image = screenshot();
            assertEquals("shizuku_foreground_geometry_age", image.getString("coordinateBasis"));
            Rect target = button();
            JSONObject result = executor.execute("tap", new JSONObject().put("snapshotId", image.getString("snapshotId"))
                    .put("x", target.centerX()).put("y", target.centerY()), false, active);
            assertEquals("shizuku", result.getString("channel"));
            awaitConfirmed();
        } finally { instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES); }
    }

    @Test public void cancelledSwipeReleasesTheHelperBeforeTheNextOperation() throws Exception {
        JSONObject image = screenshot();
        java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.CountDownLatch dispatched = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CompletableFuture<Exception> finished = new java.util.concurrent.CompletableFuture<>();
        Thread operation = new Thread(() -> {
            try {
                executor.execute("swipe", new JSONObject().put("channel", "shizuku").put("snapshotId", image.getString("snapshotId"))
                        .put("startX", 500).put("startY", 800).put("endX", 500).put("endY", 850).put("durationMs", 2000), false,
                        new Cancellation() {
                            @Override public void check() throws InterruptedException { if (stopped.get()) throw new InterruptedException("test stop"); }
                            @Override public void dispatched() { dispatched.countDown(); }
                        });
                finished.complete(null);
            } catch (Exception failure) { finished.complete(failure); }
        });
        operation.start();
        try {
            assertTrue(dispatched.await(5, java.util.concurrent.TimeUnit.SECONDS));
            Thread.sleep(150);
            stopped.set(true);
            assertTrue(finished.get(3, java.util.concurrent.TimeUnit.SECONDS) instanceof InterruptedException);
            JSONObject fresh = screenshot();
            assertEquals("shizuku", fresh.getString("channel"));
            Rect target = button();
            executor.execute("tap", new JSONObject().put("channel", "shizuku").put("snapshotId", fresh.getString("snapshotId"))
                    .put("x", target.centerX()).put("y", target.centerY()), false, active);
            awaitConfirmed();
        } finally { stopped.set(true); operation.join(4000); }
    }

    private JSONObject screenshot() throws Exception {
        JSONObject last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            last = executor.execute("screenshot", new JSONObject().put("channel", "shizuku"), false, active);
            if (last.has("snapshotId")) return last;
            Thread.sleep(200);
        }
        throw new AssertionError("Screenshot did not establish a basis: " + last);
    }

    private Rect button() {
        AtomicReference<Rect> bounds = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            android.view.View view = activity.findViewById(R.id.test_confirm);
            int[] position = new int[2]; view.getLocationOnScreen(position);
            bounds.set(new Rect(position[0], position[1], position[0] + view.getWidth(), position[1] + view.getHeight()));
        });
        return bounds.get();
    }

    private void awaitConfirmed() throws Exception {
        DeviceUi.await("Shell tap did not confirm the practice form", 3000, () -> {
            AtomicReference<String> text = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> text.set(((TextView) activity.findViewById(R.id.test_result)).getText().toString()));
            return text.get().startsWith("已确认：");
        });
    }
}
