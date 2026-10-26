package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.AndroidUseExecutor;
import io.droyagent.androiduse.DroyAccessibilityService;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual native perimeter, touch dispatch, screenshot clearance and task-state transitions. */
@RunWith(AndroidJUnit4.class)
public final class AutomationScreenOverlayDeviceTest {
    private static final String TASK = "screen-glow-device-fixture";
    private final boolean darkPage = "true".equals(InstrumentationRegistry.getArguments().getString("glowDarkPage"));
    private final int background = darkPage ? 0xFF151618 : 0xFFF2F4F7;
    private static final String[] PREF_KEYS = {"automationScreenDisplay", "petEnabled", "petExternalDisplay", "petInternalDisplay", "toolsStopped", "allowDeviceInfo", "allowObserve", "petId", "petName", "petLeft", "petX", "petY"};
    private final Map<String, Object> originalPreferences = new HashMap<>();
    private final Map<Field, Object> originalProgress = new HashMap<>();
    private final AtomicInteger centerTouches = new AtomicInteger(), edgeTouches = new AtomicInteger(), swipeMoves = new AtomicInteger();
    private Instrumentation instrumentation;
    private UiAutomation automation;
    private Context context;
    private SharedPreferences preferences;
    private SharedPreferences progressPreferences;
    private String originalHistory, originalAnimationScale;
    private MainActivity mainActivity;
    private TestPageActivity fixture;
    private FrameLayout page;
    private View centerTarget, edgeTarget;
    private AndroidUseExecutor executor;
    private boolean ownsPreferences, startedRuntime, historyOwned, animationScaleOwned;
    private int touchDeviceId = Integer.MIN_VALUE;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        assertTrue("Stop tasks and runtime before screen-glow tests", RuntimeService.canManageHistory());
        progressPreferences = context.getSharedPreferences("tool-progress", Context.MODE_PRIVATE);
        originalHistory = progressPreferences.contains("history") ? progressPreferences.getString("history", "[]") : null;
        historyOwned = true;
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        for (String key : PREF_KEYS) if (preferences.contains(key)) originalPreferences.put(key, preferences.getAll().get(key));
        ownsPreferences = true;
        synchronized (ToolProgress.class) {
            for (Field field : ToolProgress.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
                field.setAccessible(true); originalProgress.put(field, field.get(null));
            }
            ToolProgress.interruptAll();
        }
        assertTrue(preferences.edit().remove("automationScreenDisplay").putBoolean("petEnabled", false)
            .putString("petExternalDisplay", "off").putString("petInternalDisplay", "off").putBoolean("toolsStopped", false)
            .putBoolean("allowDeviceInfo", true).putBoolean("allowObserve", true).commit());
        startedRuntime = true;
        mainActivity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        new DeviceUi(instrumentation, mainActivity).awaitPage();
        DeviceUi.await("Droy accessibility did not connect", 15000, () -> DroyAccessibilityService.isConnected(context));
        executor = new AndroidUseExecutor(context);
        fixture = (TestPageActivity) instrumentation.startActivitySync(new Intent(context, TestPageActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(() -> {
            fixture.getWindow().setDecorFitsSystemWindows(false);
            fixture.getWindow().setStatusBarColor(Color.TRANSPARENT);
            fixture.getWindow().setNavigationBarColor(Color.TRANSPARENT);
            page = new FrameLayout(fixture); page.setBackgroundColor(background);
            page.setOnTouchListener((view, event) -> { if (event.getActionMasked() == MotionEvent.ACTION_MOVE) swipeMoves.incrementAndGet(); return true; });
            TextView title = new TextView(fixture); title.setText("Droy 屏幕光效验收\n实际触摸与截图检查");
            title.setTextColor(darkPage ? Color.WHITE : Color.BLACK); title.setTextSize(18);
            FrameLayout.LayoutParams titlePosition = new FrameLayout.LayoutParams(-2, -2);
            titlePosition.leftMargin = dp(28); titlePosition.topMargin = dp(80); page.addView(title, titlePosition);
            fixture.setContentView(page);
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            centerTarget = target("光效中心触摸目标", centerTouches);
            edgeTarget = target("光效边缘触摸目标", edgeTouches);
            centerTarget.setId(R.id.test_confirm);
            FrameLayout.LayoutParams center = new FrameLayout.LayoutParams(dp(110), dp(64));
            center.leftMargin = (page.getWidth() - center.width) / 2; center.topMargin = page.getHeight() / 2;
            page.addView(centerTarget, center);
            FrameLayout.LayoutParams edge = new FrameLayout.LayoutParams(dp(60), dp(64));
            edge.leftMargin = 0; edge.topMargin = page.getHeight() / 2;
            page.addView(edgeTarget, edge);
        });
        instrumentation.waitForIdleSync();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) { touchDeviceId = id; break; }
        }
        assertNotEquals("No actual touchscreen source was advertised", Integer.MIN_VALUE, touchDeviceId);
        enginePhase("thinking", true);
        assertFalse("Ordinary chat unexpectedly displayed the perimeter", glowVisible());
    }

    @After public void tearDown() throws Exception {
        try {
            ToolProgress.interruptAll(); AutomationScreenOverlay.runtimeStopped();
            if (fixture != null && !fixture.isDestroyed()) instrumentation.runOnMainSync(fixture::finish);
            if (mainActivity != null && !mainActivity.isDestroyed()) instrumentation.runOnMainSync(mainActivity::finish);
            if (startedRuntime) {
                instrumentation.runOnMainSync(RuntimeService::requestStop);
                DeviceUi.await("Screen-glow test left runtime active", 15000, RuntimeService::canManageHistory);
                PetOverlay.runtimeStopped(); AutomationScreenOverlay.runtimeStopped(); instrumentation.waitForIdleSync();
            }
        } finally {
            try {
                if (animationScaleOwned) {
                    // Deleting an absent scale does not reset Android's cached animator value.
                    if (originalAnimationScale == null) {
                        writeAnimationScale("1");
                        DeviceUi.await("The default animator scale did not become effective", 5000,
                            android.animation.ValueAnimator::areAnimatorsEnabled);
                    }
                    writeAnimationScale(originalAnimationScale);
                    boolean originallyEnabled = originalAnimationScale == null || Float.parseFloat(originalAnimationScale) != 0f;
                    DeviceUi.await("The test did not restore the original animator duration scale", 5000,
                        () -> java.util.Objects.equals(originalAnimationScale, Settings.Global.getString(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE))
                            && android.animation.ValueAnimator.areAnimatorsEnabled() == originallyEnabled);
                }
            } finally {
                try {
                    if (ownsPreferences) {
                        SharedPreferences.Editor restore = preferences.edit();
                        for (String key : PREF_KEYS) {
                            Object value = originalPreferences.get(key);
                            if (value instanceof Boolean bool) restore.putBoolean(key, bool);
                            else if (value instanceof Float number) restore.putFloat(key, number);
                            else if (value instanceof String text) restore.putString(key, text);
                            else restore.remove(key);
                        }
                        assertTrue("Could not restore screen-glow preferences", restore.commit());
                    }
                    if (historyOwned) {
                        SharedPreferences.Editor restoreHistory = progressPreferences.edit();
                        if (originalHistory == null) restoreHistory.remove("history"); else restoreHistory.putString("history", originalHistory);
                        assertTrue("Could not restore native tool-history metadata", restoreHistory.commit());
                    }
                } finally {
                    synchronized (ToolProgress.class) {
                        for (Map.Entry<Field, Object> entry : originalProgress.entrySet()) entry.getKey().set(null, entry.getValue());
                    }
                }
            }
        }
    }

    @Test public void perimeterPassesTouchesAndExecutorCapturesOnlyTheUnderlyingPage() throws Exception {
        boolean staticRequested = "true".equals(InstrumentationRegistry.getArguments().getString("glowStaticAnimation"));
        if (staticRequested) disableAnimationsOnDedicatedEmulator();
        beginPhoneOperation();
        DeviceUi.await("The native perimeter did not appear with the pet off", 5000, this::glowVisible);
        awaitFrames();
        instrumentation.runOnMainSync(() -> {
            View glow = glowOnMain(); assertNotNull(glow);
            WindowManager.LayoutParams layout = (WindowManager.LayoutParams) glow.getLayoutParams();
            assertNotEquals(0, layout.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            assertNotEquals(0, layout.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            assertEquals("Connected accessibility must use a trusted overlay window", WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, layout.type);
            Rect bounds = boundsOnMain(glow);
            Rect display = fixture.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
            assertTrue("The perimeter does not cover the full visible display: " + bounds + " / " + display,
                Math.abs(bounds.left - display.left) <= 1 && Math.abs(bounds.top - display.top) <= 1
                    && Math.abs(bounds.right - display.right) <= 1 && Math.abs(bounds.bottom - display.bottom) <= 1);
        });
        captureUi("01-active-with-pet-off");
        if ("true".equals(InstrumentationRegistry.getArguments().getString("glowScreenLifecycle"))) {
            assertTrue("Screen lifecycle changes require a dedicated emulator", Build.MODEL.contains("sdk_gphone"));
            shellInput("input keyevent KEYCODE_SLEEP");
            DeviceUi.await("Screen off left the perimeter attached", 5000, () -> !glowVisible());
            shellInput("input keyevent KEYCODE_WAKEUP");
            shellInput("wm dismiss-keyguard");
            DeviceUi.await("Unlock did not restore the active phone operation", 5000, this::glowVisible);
            awaitFrames(); captureUi("01d-resumed-after-screen-off");
        }
        Bitmap before = automation.takeScreenshot(); assertNotNull(before);
        try {
            assertTrue("No visible edge glow was rendered", coloredEdgePixels(before) > 25);
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                SystemClock.sleep(1100);
                Bitmap later = automation.takeScreenshot(); assertNotNull(later);
                try {
                    assertTrue("The enabled perimeter animation did not change its visible edge colors", changedEdgePixels(before, later) > 15);
                    save(later, "01b-active-animation-later");
                } finally { later.recycle(); }
            } else if (staticRequested) {
                View staticGlow = currentGlow();
                assertFalse("Disabled animations still scheduled a perimeter frame", framePosted(staticGlow));
                SystemClock.sleep(1100);
                Bitmap later = automation.takeScreenshot(); assertNotNull(later);
                try {
                    assertEquals("The animation-disabled perimeter still changed colors", 0, changedEdgePixels(before, later));
                    save(later, "01c-static-animation-disabled");
                } finally { later.recycle(); }
                assertFalse("The static perimeter resumed frame callbacks", framePosted(staticGlow));
            }
        }
        finally { before.recycle(); }

        Rect center = bounds(centerTarget), edge = bounds(edgeTarget);
        touch(center.centerX(), center.centerY());
        DeviceUi.await("Center touch was swallowed by the full-screen window", 3000, () -> centerTouches.get() == 1);
        touch(edge.left + dp(6), edge.centerY());
        DeviceUi.await("Edge touch was swallowed by the full-screen window", 3000, () -> edgeTouches.get() == 1);
        JSONObject observed = executor.execute("observe", new JSONObject(), false, () -> {});
        executor.execute("tap", new JSONObject().put("snapshotId", observed.getString("snapshotId"))
            .put("x", center.centerX()).put("y", center.centerY()).put("afterObserve", false), false, () -> {});
        DeviceUi.await("Accessibility input could not reach the center through the perimeter", 3000, () -> centerTouches.get() == 2);
        DeviceUi.await("The perimeter disappeared after a normal input", 3000, this::glowVisible);

        Rect content = bounds(page);
        JSONObject swipeBasis = executor.execute("observe", new JSONObject(), false, () -> {});
        executor.execute("swipe", new JSONObject().put("snapshotId", swipeBasis.getString("snapshotId"))
            .put("startX", content.left + content.width() * 3 / 4).put("startY", content.top + content.height() * 4 / 5)
            .put("endX", content.left + content.width() * 3 / 4).put("endY", content.top + content.height() * 2 / 3)
            .put("durationMs", 250).put("afterObserve", false), false, () -> {});
        DeviceUi.await("Accessibility swipe did not reach the underlying page", 3000, () -> swipeMoves.get() > 0);
        JSONObject result = executor.execute("screenshot", new JSONObject(), false, () -> {});
        assertEquals("captured", result.getString("status"));
        byte[] png = Base64.decode(result.getString("base64"), Base64.DEFAULT);
        Bitmap captured = BitmapFactory.decodeByteArray(png, 0, png.length); assertNotNull(captured);
        try {
            save(captured, "02-executor-screenshot-no-glow");
            assertTrue("The actual executor screenshot still contains colored perimeter pixels", coloredEdgePixels(captured) <= 4);
            Rect centerPatch = new Rect(captured.getWidth() / 2 - dp(20), captured.getHeight() / 3,
                captured.getWidth() / 2 + dp(20), captured.getHeight() / 3 + dp(20));
            assertBackground(captured, centerPatch);
        } finally { captured.recycle(); }
        DeviceUi.await("Screenshot did not restore the running perimeter", 5000, () -> glowVisible() && !captureSuppressed());
        awaitFrames(); captureUi("03-restored-after-screenshot");
        if (staticRequested) {
            View staticGlow = currentGlow(); assertFalse(framePosted(staticGlow));
            enginePhase("completed", false);
            DeviceUi.await("Static perimeter remained after completion", 5000, () -> !glowVisible());
            assertFalse("Static perimeter cleanup left a scheduled frame", framePosted(staticGlow));
            captureUi("03b-static-stopped");
        }
    }

    @Test public void actualProductBridgeDistinguishesBackgroundQueryFromPhoneObservation() throws Exception {
        assertFalse(ToolProgress.screenOperationActive());
        try {
            JSONObject information = RuntimeService.executeProductAction("system_info", new JSONObject().put("sections", new JSONArray().put("battery")));
            assertEquals("queried", information.getString("status"));
            assertTrue(information.getJSONObject("sections").has("battery"));
            awaitFrames();
            assertFalse("A real background query incorrectly started a phone-operation session", ToolProgress.screenOperationActive());
            assertFalse("A real background query incorrectly displayed the perimeter", glowVisible());
            JSONObject observation = RuntimeService.executeProductAction("observe", new JSONObject());
            assertEquals("The real bridge did not observe the fixture", context.getPackageName(), observation.getString("packageName"));
            assertTrue("The real bridge did not start a phone-operation session", ToolProgress.screenOperationActive());
            DeviceUi.await("Real phone observation did not display the perimeter", 5000, this::glowVisible);
            awaitFrames(); captureUi("05-real-product-bridge-observe");
        } finally {
            ToolProgress.update("product-user-action", "completed", false);
            RuntimeService.refreshExecutionProgress();
        }
        DeviceUi.await("Ending the actual product action left the perimeter displayed", 5000, () -> !glowVisible());
        assertFalse("An ordinary thinking task revived the completed device action", ToolProgress.screenOperationActive());
    }

    @Test public void screenshotClearsBothLivePetAndPerimeterThenRestoresBothListeners() throws Exception {
        beginPhoneOperation();
        assertTrue(preferences.edit().putBoolean("petEnabled", true).putString("petExternalDisplay", "always")
            .putString("petInternalDisplay", "always").putString("petId", "default").putString("petName", "Droy")
            .putBoolean("petLeft", false).putFloat("petX", .85f).putFloat("petY", .78f).commit());
        PetOverlay.refresh(context);
        DeviceUi.await("The real pet and perimeter did not appear together", 5000,
            () -> glowVisible() && petVisibleAndSettled());
        awaitFrames();
        Rect beforePet = petBounds();
        Bitmap before = automation.takeScreenshot(); assertNotNull(before);
        try {
            assertTrue("The coexistence setup has no actual black cat pixels", darkPixels(before, beforePet) > 20);
            assertTrue("The coexistence setup has no actual luminous perimeter", coloredEdgePixels(before) > 25);
            save(before, "06-pet-and-perimeter-before-screenshot");
        } finally { before.recycle(); }

        JSONObject result = executor.execute("screenshot", new JSONObject(), false, () -> {});
        assertEquals("captured", result.getString("status"));
        byte[] png = Base64.decode(result.getString("base64"), Base64.DEFAULT);
        Bitmap captured = BitmapFactory.decodeByteArray(png, 0, png.length); assertNotNull(captured);
        try {
            assertTrue("The model screenshot still contains the luminous perimeter", coloredEdgePixels(captured) <= 4);
            assertTrue("The model screenshot still contains the pet's black pixels", darkPixels(captured, beforePet) <= 3);
            save(captured, "07-executor-screenshot-without-pet-or-perimeter");
        } finally { captured.recycle(); }

        DeviceUi.await("The shared screenshot cleanup failed to restore both live listeners", 5000,
            () -> glowVisible() && !captureSuppressed() && petVisibleAndSettled());
        awaitFrames();
        Bitmap restored = automation.takeScreenshot(); assertNotNull(restored);
        try {
            assertTrue("The restored perimeter has no visible edge light", coloredEdgePixels(restored) > 25);
            assertTrue("The pet window returned without its actual cat pixels", darkPixels(restored, petBounds()) > 20);
            save(restored, "08-pet-and-perimeter-restored");
        } finally { restored.recycle(); }
    }

    @Test public void waitingPreferenceAndTaskEndRemoveThePerimeterWithoutRevivingOldWork() throws Exception {
        beginPhoneOperation(); DeviceUi.await("Perimeter did not appear", 5000, this::glowVisible);
        enginePhase("thinking", true);
        DeviceUi.await("The inter-tool thinking phase interrupted the perimeter", 2000, this::glowVisible);
        for (String phase : new String[]{"waiting", "waiting_input", "blocked", "interrupted"}) {
            enginePhase(phase, true);
            DeviceUi.await("Perimeter remained during " + phase, 5000, () -> !glowVisible());
            beginPhoneOperation(); DeviceUi.await("Perimeter did not resume after " + phase, 5000, this::glowVisible);
        }
        assertTrue(preferences.edit().putBoolean("automationScreenDisplay", false).commit());
        AutomationScreenOverlay.refresh(context);
        DeviceUi.await("Disabling the preference left the perimeter attached", 5000, () -> !glowVisible());
        assertEquals("off", preferences.getString("petInternalDisplay", ""));
        assertEquals("off", preferences.getString("petExternalDisplay", ""));
        assertTrue(preferences.edit().putBoolean("automationScreenDisplay", true).commit());
        AutomationScreenOverlay.refresh(context);
        DeviceUi.await("Re-enabling during operation did not restore the perimeter", 5000, this::glowVisible);
        enginePhase("completed", false);
        DeviceUi.await("Task end left the perimeter attached", 5000, () -> !glowVisible());
        AutomationScreenOverlay.refresh(context); awaitFrames();
        assertFalse("Refreshing old completed work revived the perimeter", glowVisible());
        beginPhoneOperation(); DeviceUi.await("Perimeter did not reappear for new work", 5000, this::glowVisible);
        ToolProgress.interruptAll(); AutomationScreenOverlay.runtimeStopped();
        DeviceUi.await("Runtime cleanup left the perimeter attached", 5000, () -> !glowVisible());
        captureUi("04-stopped-no-glow");
    }

    private View target(String description, AtomicInteger touches) {
        View target = new View(fixture); target.setBackgroundColor(0xFFB5CDD9);
        target.setContentDescription(description); target.setOnClickListener(view -> touches.incrementAndGet()); return target;
    }
    private void beginPhoneOperation() throws Exception {
        enginePhase("executing", true); ToolProgress.phoneOperation(TASK); AutomationScreenOverlay.refresh(context);
    }
    private void enginePhase(String phase, boolean active) throws Exception {
        ToolProgress.updateFromEngine(new JSONObject().put("taskId", TASK).put("phase", phase).put("active", active)
            .put("summary", "").put("pending", JSONObject.NULL));
        AutomationScreenOverlay.refresh(context);
    }
    private Object overlayOnMain() {
        try { Field field = AutomationScreenOverlay.class.getDeclaredField("instance"); field.setAccessible(true); return field.get(null); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private View glowOnMain() {
        Object overlay = overlayOnMain(); if (overlay == null) return null;
        try { Field field = AutomationScreenOverlay.class.getDeclaredField("glow"); field.setAccessible(true); return (View) field.get(overlay); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private View currentGlow() {
        AtomicReference<View> result = new AtomicReference<>(); instrumentation.runOnMainSync(() -> result.set(glowOnMain())); return result.get();
    }
    private boolean framePosted(View glow) {
        assertNotNull("No actual native perimeter was available", glow);
        AtomicReference<Boolean> result = new AtomicReference<>(true);
        instrumentation.runOnMainSync(() -> {
            try { Field field = ScreenGlowView.class.getDeclaredField("framePosted"); field.setAccessible(true); result.set(field.getBoolean(glow)); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }); return result.get();
    }
    private boolean petVisibleAndSettled() {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> {
            try {
                PetOverlay pet = PetOverlay.get(context);
                Field viewField = PetOverlay.class.getDeclaredField("pet"), enteringField = PetOverlay.class.getDeclaredField("entering");
                viewField.setAccessible(true); enteringField.setAccessible(true);
                View view = (View) viewField.get(pet);
                result.set(view != null && view.isAttachedToWindow() && view.getVisibility() == View.VISIBLE
                    && view.getAlpha() > .01f && !enteringField.getBoolean(pet));
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }); return result.get();
    }
    private Rect petBounds() {
        AtomicReference<Rect> result = new AtomicReference<>(); instrumentation.runOnMainSync(() -> result.set(PetOverlay.get(context).petBounds())); return result.get();
    }
    private boolean glowVisible() {
        AtomicReference<Boolean> visible = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> { View glow = glowOnMain(); visible.set(glow != null && glow.isAttachedToWindow() && glow.getAlpha() > .01f); });
        return visible.get();
    }
    private boolean captureSuppressed() {
        AtomicReference<Boolean> suppressed = new AtomicReference<>(true);
        instrumentation.runOnMainSync(() -> {
            View glow = glowOnMain(); if (glow == null) return;
            try { Field field = ScreenGlowView.class.getDeclaredField("captureSuppressed"); field.setAccessible(true); suppressed.set(field.getBoolean(glow)); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }); return suppressed.get();
    }
    private Rect bounds(View view) {
        AtomicReference<Rect> result = new AtomicReference<>(); instrumentation.runOnMainSync(() -> result.set(boundsOnMain(view))); return result.get();
    }
    private Rect boundsOnMain(View view) { int[] point = new int[2]; view.getLocationOnScreen(point); return new Rect(point[0], point[1], point[0] + view.getWidth(), point[1] + view.getHeight()); }
    private void awaitFrames() throws Exception {
        java.util.concurrent.CountDownLatch frames = new java.util.concurrent.CountDownLatch(1);
        instrumentation.runOnMainSync(() -> android.view.Choreographer.getInstance().postFrameCallback(first ->
            android.view.Choreographer.getInstance().postFrameCallback(second -> frames.countDown())));
        assertTrue("No rendered frame arrived", frames.await(3, java.util.concurrent.TimeUnit.SECONDS));
        SystemClock.sleep(450);
    }
    private void touch(float x, float y) {
        long down = SystemClock.uptimeMillis(); inject(down, MotionEvent.ACTION_DOWN, x, y);
        try { SystemClock.sleep(48); } finally { inject(down, MotionEvent.ACTION_UP, x, y); }
    }
    private void inject(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 1, 1, 0, 1, 1, touchDeviceId, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue("Android rejected a real touch event", automation.injectInputEvent(event, true)); } finally { event.recycle(); }
    }
    private int coloredEdgePixels(Bitmap image) {
        int count = 0, band = Math.min(dp(12), image.getWidth() / 8);
        for (int y = image.getHeight() / 4; y < image.getHeight() * 2 / 5; y += 3) {
            for (int offset = 1; offset < band; offset += 2) {
                if (!nearBackground(image.getPixel(offset, y), 6)) count++;
                if (!nearBackground(image.getPixel(image.getWidth() - 1 - offset, y), 6)) count++;
            }
        } return count;
    }
    private int changedEdgePixels(Bitmap first, Bitmap second) {
        assertEquals(first.getWidth(), second.getWidth()); assertEquals(first.getHeight(), second.getHeight());
        int changed = 0, band = Math.min(dp(12), first.getWidth() / 8);
        for (int y = first.getHeight() / 4; y < first.getHeight() * 2 / 5; y += 3) for (int offset = 1; offset < band; offset += 2) {
            for (int x : new int[]{offset, first.getWidth() - 1 - offset}) {
                int a = first.getPixel(x, y), b = second.getPixel(x, y);
                if (Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b)) > 8) changed++;
            }
        } return changed;
    }
    private int darkPixels(Bitmap image, Rect area) {
        Rect sampled = new Rect(area); assertTrue("The cat's rendered region is outside the screenshot", sampled.intersect(0, 0, image.getWidth(), image.getHeight()));
        int count = 0;
        for (int y = sampled.top; y < sampled.bottom; y += 2) for (int x = sampled.left; x < sampled.right; x += 2) {
            int color = image.getPixel(x, y);
            if (Color.red(color) < 50 && Color.green(color) < 50 && Color.blue(color) < 50) count++;
        } return count;
    }
    private boolean nearBackground(int color, int tolerance) {
        return Math.abs(Color.red(color) - Color.red(background)) <= tolerance
            && Math.abs(Color.green(color) - Color.green(background)) <= tolerance
            && Math.abs(Color.blue(color) - Color.blue(background)) <= tolerance;
    }
    private void assertBackground(Bitmap image, Rect area) {
        for (int y = area.top; y < area.bottom; y += 4) for (int x = area.left; x < area.right; x += 4)
            assertTrue("The center of the screenshot was tinted at " + x + "," + y, nearBackground(image.getPixel(x, y), 4));
    }
    private void captureUi(String stage) throws Exception {
        Bitmap image = automation.takeScreenshot(); assertNotNull(image);
        try { save(image, stage); } finally { image.recycle(); }
    }
    private void save(Bitmap image, String stage) throws Exception {
        File directory = new File(context.getExternalFilesDir(null), "screen-glow-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        String label = InstrumentationRegistry.getArguments().getString("glowEvidenceLabel", "default").replaceAll("[^A-Za-z0-9_-]", "_");
        File output = new File(directory, label + "-" + stage + ".png");
        try (FileOutputStream stream = new FileOutputStream(output)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
        Bundle status = new Bundle(); status.putString("stream", "\nDROY_SCREEN_GLOW_EVIDENCE " + output.getAbsolutePath() + "\n"); instrumentation.sendStatus(2, status);
    }
    private void disableAnimationsOnDedicatedEmulator() throws Exception {
        assertTrue("glowStaticAnimation may change the system animator scale only on a dedicated emulator",
            Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")
                || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk_gphone"));
        originalAnimationScale = Settings.Global.getString(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE);
        if (originalAnimationScale != null) assertTrue("Unsupported original animator scale", originalAnimationScale.matches("[0-9]+(?:\\.[0-9]+)?"));
        animationScaleOwned = true;
        writeAnimationScale("0");
        DeviceUi.await("The system did not disable animator scheduling", 5000,
            () -> !android.animation.ValueAnimator.areAnimatorsEnabled()
                && Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, -1) == 0f);
    }
    private void writeAnimationScale(String value) throws Exception {
        String command = value == null ? "settings delete global animator_duration_scale"
            : "settings put global animator_duration_scale " + value;
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int size;
            while ((size = input.read(buffer)) != -1) { output.write(buffer, 0, size); assertTrue("Animator settings command returned excessive output", output.size() <= 4096); }
            String message = output.toString("UTF-8").trim();
            assertTrue("Could not update the dedicated emulator animator scale", message.isEmpty() || value == null && message.matches("Deleted [0-9]+ rows"));
        }
    }
    private void shellInput(String command) throws Exception {
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))) {
            byte[] buffer = new byte[1024]; while (input.read(buffer) != -1) { }
        }
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
