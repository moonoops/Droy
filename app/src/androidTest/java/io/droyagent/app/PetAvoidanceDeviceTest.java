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
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.AndroidUseExecutor;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.DroyAccessibilityService;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises the executor's actual accessibility dispatch and screenshot with a real pet window. */
@RunWith(AndroidJUnit4.class)
public final class PetAvoidanceDeviceTest {
    private static final Cancellation ACTIVE = () -> {};
    private static final int BACKGROUND = 0xFFF4F8FA, TARGET = 0xFF4BCBD5;
    private static final String[] PREF_KEYS = {"petEnabled", "petExternalDisplay", "petInternalDisplay", "petId", "petName", "petLeft", "petX", "petY", "themeColorScheme"};
    private final Map<String, Object> originalPreferences = new HashMap<>();
    private final Map<Field, Object> originalProgress = new HashMap<>();
    private final AtomicInteger clicks = new AtomicInteger();
    private Instrumentation instrumentation;
    private UiAutomation automation;
    private Context context;
    private SharedPreferences preferences;
    private MainActivity mainActivity;
    private TestPageActivity fixture;
    private FrameLayout page;
    private TextView title;
    private View target;
    private AndroidUseExecutor executor;
    private boolean startedRuntime, savedPreferences;
    private int touchDeviceId = Integer.MIN_VALUE;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        // Keep this instance: asking Instrumentation for its default automation can suppress Droy's service.
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        assertTrue("Stop existing tasks and the runtime before this suite", RuntimeService.canManageHistory());
        assertTrue("Grant display-over-other-apps before this suite", Settings.canDrawOverlays(context));
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        Map<String, ?> values = preferences.getAll();
        for (String key : PREF_KEYS) if (values.containsKey(key)) originalPreferences.put(key, values.get(key));
        savedPreferences = true;
        synchronized (ToolProgress.class) {
            for (String name : new String[]{"currentTask", "currentPhase", "summary", "summaryKind", "pending", "active", "startedAt", "activeTasks"}) {
                Field field = ToolProgress.class.getDeclaredField(name); field.setAccessible(true);
                originalProgress.put(field, field.get(null));
            }
            ToolProgress.update("pet-avoidance-idle", "thinking", true);
            ToolProgress.updateFromEngine(new JSONObject().put("taskId", "pet-avoidance-idle")
                .put("phase", "completed").put("active", false).put("summary", "").put("pending", JSONObject.NULL));
        }
        assertTrue(preferences.edit().putBoolean("petEnabled", false).putString("petExternalDisplay", "off").putString("petInternalDisplay", "off").putString("petId", "default").commit());
        ToolProgress.interruptAll();
        startedRuntime = true;
        mainActivity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        new DeviceUi(instrumentation, mainActivity).awaitPage();
        DeviceUi.await("Droy accessibility did not connect", 15000, () -> DroyAccessibilityService.isConnected(context));
        executor = new AndroidUseExecutor(context);
        fixture = (TestPageActivity) instrumentation.startActivitySync(new Intent(context, TestPageActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(() -> {
            fixture.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            fixture.getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(fixture.getWindow().getDecorView().getWindowToken(), 0);
            page = new FrameLayout(fixture); page.setBackgroundColor(BACKGROUND);
            title = new TextView(fixture); title.setText("宠物避让原生验收\n青色区域是实际点击目标");
            title.setTextColor(Color.BLACK); title.setTextSize(18);
            FrameLayout.LayoutParams label = new FrameLayout.LayoutParams(-1, -2);
            label.leftMargin = label.topMargin = dp(20); page.addView(title, label);
            fixture.setContentView(page);
        });
        instrumentation.waitForIdleSync();
        assertTrue(preferences.edit().putBoolean("petEnabled", true).putString("petExternalDisplay", "always").putString("petInternalDisplay", "always").putString("petName", "Droy")
            .putBoolean("petLeft", false).putFloat("petX", .5f).putFloat("petY", .4f)
            .putString("themeColorScheme", "light").commit());
        PetOverlay.refresh(context);
        DeviceUi.await("The default pet did not appear over the fixture", 5000, () -> visible("pet") && !entryRunning());
        Rect pet = petBounds();
        instrumentation.runOnMainSync(() -> {
            int[] origin = new int[2]; page.getLocationOnScreen(origin);
            target = new View(fixture); target.setId(R.id.test_confirm);
            target.setContentDescription("宠物避让点击目标"); target.setBackgroundColor(TARGET);
            target.setOnClickListener(view -> clicks.incrementAndGet());
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(pet.width() + dp(16), pet.height() + dp(16));
            position.leftMargin = pet.left - origin[0] - dp(8);
            position.topMargin = pet.top - origin[1] - dp(8);
            page.addView(target, position);
        });
        instrumentation.waitForIdleSync();
        automation.waitForIdle(250, 3000);
        assertTrue("The fixture must cover the pet's entire window", viewBounds(target).contains(petBounds()));
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) { touchDeviceId = id; break; }
        }
    }

    @After public void tearDown() throws Exception {
        try {
            if (fixture != null && !fixture.isDestroyed()) instrumentation.runOnMainSync(fixture::finish);
            if (mainActivity != null && !mainActivity.isDestroyed()) instrumentation.runOnMainSync(mainActivity::finish);
            if (startedRuntime) {
                instrumentation.runOnMainSync(RuntimeService::requestStop);
                DeviceUi.await("Pet avoidance test left the runtime running", 15000, RuntimeService::canManageHistory);
                PetOverlay.runtimeStopped(); instrumentation.waitForIdleSync();
            }
        } finally {
            if (savedPreferences) {
                SharedPreferences.Editor restore = preferences.edit();
                for (String key : PREF_KEYS) {
                    Object value = originalPreferences.get(key);
                    if (value instanceof Boolean bool) restore.putBoolean(key, bool);
                    else if (value instanceof Float number) restore.putFloat(key, number);
                    else if (value instanceof String text) restore.putString(key, text);
                    else restore.remove(key);
                }
                assertTrue("Could not restore pet preferences", restore.commit());
            }
            synchronized (ToolProgress.class) {
                for (Map.Entry<Field, Object> entry : originalProgress.entrySet()) entry.getKey().set(null, entry.getValue());
            }
        }
    }

    @Test public void nativeActionsIgnorePetEventsClearTheTargetAndCaptureWithoutPetPixels() throws Exception {
        JSONObject coordinates = observeTarget();
        assertFalse("The pet leaked into the business observation", coordinates.toString().contains("宠物，点击查看任务"));
        // Open the real overlay after observing. Its window events must not revoke this coordinate snapshot.
        tapPet();
        DeviceUi.await("Touching the pet did not open its real message window", 3000, () -> visible("bubbleWindow"));
        automation.waitForIdle(250, 3000);
        captureUi("01-before-coordinate-tap");
        Rect covered = petBounds();
        AtomicInteger checkedDispatches = new AtomicInteger();
        Cancellation dispatchCheck = new Cancellation() {
            @Override public void check() {}
            @Override public void dispatched() {
                assertSame("Accessibility input must dispatch on main", Looper.getMainLooper(), Looper.myLooper());
                View pet = overlayView("pet"); assertNotNull(pet);
                assertNotEquals("The relocated cat must temporarily leave input routing", 0,
                    ((WindowManager.LayoutParams) pet.getLayoutParams()).flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                Rect input = checkedDispatches.get() == 0
                    ? new Rect(covered.centerX(), covered.centerY(), covered.centerX() + 1, covered.centerY() + 1)
                    : boundsOnMain(target);
                if (android.os.Build.VERSION.SDK_INT >= 34 && pet.isHardwareAccelerated()) {
                    assertEquals("A normal overlapping input made the cat disappear", View.VISIBLE, pet.getVisibility());
                    assertFalse("The visible cat still covers the input target", Rect.intersects(PetOverlay.get(context).petBounds(), input));
                    assertFalse("The rendered cat window has not moved clear of the input target", Rect.intersects(boundsOnMain(pet), input));
                } else assertEquals("Older Android must clear the animated window instead of moving it", View.INVISIBLE, pet.getVisibility());
                View bubble = overlayView("bubbleWindow");
                if (bubble != null && bubble.getVisibility() == View.VISIBLE)
                    assertFalse("The rendered bubble still covers the input target", Rect.intersects(boundsOnMain(bubble), input));
                checkedDispatches.incrementAndGet();
            }
        };
        executor.execute("tap", new JSONObject().put("snapshotId", coordinates.getString("snapshotId"))
            .put("x", covered.centerX()).put("y", covered.centerY()).put("afterObserve", false), false, dispatchCheck);
        DeviceUi.await("The coordinate tap hit the overlay instead of the fixture", 3000, () -> clicks.get() == 1);
        awaitRestored();

        JSONObject nodes = observeTarget();
        // Removing the bubble is another pet-only window change, including its queued removal event.
        tapPet();
        DeviceUi.await("Touching the pet did not close its message window", 3000, () -> !visible("bubbleWindow"));
        automation.waitForIdle(250, 3000);
        executor.execute("tap", new JSONObject().put("snapshotId", nodes.getString("snapshotId"))
            .put("nodeId", targetNode(nodes).getString("nodeId")).put("afterObserve", false), false, dispatchCheck);
        DeviceUi.await("The node tap did not reach the underlying fixture", 3000, () -> clicks.get() == 2);
        assertEquals("Both native dispatches must happen with the visible pet outside the target", 2, checkedDispatches.get());
        awaitRestored();

        tapPet();
        verifyCapture();
    }

    @Test public void screenshotClearsPetAndBubbleAndRestoresThem() throws Exception {
        tapPet();
        verifyCapture();
    }

    private void verifyCapture() throws Exception {
        try {
            DeviceUi.await("The screenshot setup needs both real overlay windows", 3000, () -> visible("bubbleWindow"));
        } catch (AssertionError failure) {
            captureUi("screenshot-setup-failure");
            throw new AssertionError("After pet tap: underlying clicks=" + clicks.get() + ", pet=" + petBounds() + ", actual=" + overlayBounds("pet"), failure);
        }
        automation.waitForIdle(250, 3000);
        Rect catRegion = petBounds(), bubbleRegion = overlayBounds("bubbleWindow");
        Bitmap before = automation.takeScreenshot(); assertNotNull(before);
        try {
            assertTrue("The pre-capture screenshot did not contain the black cat", darkPixels(before, catRegion) > 20);
            save(before, "02-before-executor-screenshot");
        } finally { before.recycle(); }

        Set<Integer> petWindowIds = overlayWindowIds();
        Set<Integer> systemWindows = new HashSet<>(), changedSystemWindows = ConcurrentHashMap.newKeySet();
        // The existing generation is read only by this regression; no production diagnostic API is needed.
        Field generationField = DroyAccessibilityService.class.getDeclaredField("generation");
        generationField.setAccessible(true);
        AtomicLong generation = (AtomicLong) generationField.get(null);
        android.accessibilityservice.AccessibilityServiceInfo diagnosticService = automation.getServiceInfo();
        int originalFlags = diagnosticService.flags;
        diagnosticService.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(diagnosticService);
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            try {
                if (window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM && !petWindowIds.contains(window.getId()))
                    systemWindows.add(window.getId());
            } finally { window.recycle(); }
        }
        long beforeGeneration = generation.get();
        automation.setOnAccessibilityEventListener(event -> {
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && systemWindows.contains(event.getWindowId()))
                changedSystemWindows.add(event.getWindowId());
        });
        JSONObject captured;
        try { captured = executor.execute("screenshot", new JSONObject(), false, ACTIVE); }
        finally {
            automation.setOnAccessibilityEventListener(null);
            diagnosticService.flags = originalFlags; automation.setServiceInfo(diagnosticService);
        }
        long afterGeneration = generation.get();
        String basisDiagnostic = "status=" + captured.optString("coordinateBasisStatus")
            + ",reason=" + captured.optString("coordinateBasisReason", "none")
            + ",generation=" + beforeGeneration + "->" + afterGeneration + ",externalSystemContentWindowIds=" + changedSystemWindows;
        Bundle diagnostic = new Bundle(); diagnostic.putString("stream", "\nDROY_PET_CAPTURE_BASIS " + basisDiagnostic + "\n");
        instrumentation.sendStatus(2, diagnostic);
        assertEquals("captured", captured.getString("status"));
        assertEquals("accessibility", captured.getString("channel"));
        byte[] png = Base64.decode(captured.getString("base64"), Base64.DEFAULT);
        Bitmap image = BitmapFactory.decodeByteArray(png, 0, png.length); assertNotNull(image);
        try {
            save(image, "03-executor-screenshot-no-pet");
            assertFixturePixels(image, catRegion);
            assertFixturePixels(image, bubbleRegion);
        } finally { image.recycle(); }
        awaitRestored();
        assertTrue("The existing message did not return after screenshot", visible("bubbleWindow"));
        captureUi("04-restored-after-executor-screenshot");
        if (!"available".equals(captured.optString("coordinateBasisStatus"))) {
            assertEquals(basisDiagnostic, "unavailable", captured.optString("coordinateBasisStatus"));
            assertEquals(basisDiagnostic, "screen_changed_during_capture", captured.optString("coordinateBasisReason"));
            assertFalse("A stable screen must retain its screenshot basis: " + basisDiagnostic, changedSystemWindows.isEmpty());
            assertNotEquals("The external content event did not invalidate the generation: " + basisDiagnostic,
                beforeGeneration, afterGeneration);
        }
    }

    private JSONObject observeTarget() throws Exception {
        JSONObject observation = executor.execute("observe", new JSONObject(), false, ACTIVE);
        assertNotNull("The underlying target is absent from the real observation", targetNode(observation));
        return observation;
    }
    private JSONObject targetNode(JSONObject observation) throws Exception {
        JSONArray nodes = observation.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if (node.optString("resourceId").endsWith(":id/test_confirm")) return node;
        }
        return null;
    }
    private void awaitRestored() throws Exception {
        AtomicReference<Boolean> ready = new AtomicReference<>(false);
        DeviceUi.await("The executor did not restore the pet's input routing after its operation", 4000, () -> {
            instrumentation.runOnMainSync(() -> {
                View pet = overlayView("pet"), bubble = overlayView("bubbleWindow");
                ready.set(touchReady(pet) && (bubble == null || touchReady(bubble)));
            });
            return ready.get();
        });
        instrumentation.runOnMainSync(() -> {
            View pet = overlayView("pet");
            assertEquals("Restored pet still rejects touches", 0,
                ((WindowManager.LayoutParams) pet.getLayoutParams()).flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            View bubble = overlayView("bubbleWindow");
            if (bubble != null) assertEquals("Restored bubble still rejects touches", 0,
                ((WindowManager.LayoutParams) bubble.getLayoutParams()).flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        });
        // View flags change before WindowManager publishes the restored input
        // surface. Inject only after the same frames a user would actually see.
        java.util.concurrent.CountDownLatch painted = new java.util.concurrent.CountDownLatch(1);
        instrumentation.runOnMainSync(() -> android.view.Choreographer.getInstance().postFrameCallback(first ->
            android.view.Choreographer.getInstance().postFrameCallback(second -> painted.countDown())));
        assertTrue("The restored pet did not reach a rendered frame", painted.await(3, java.util.concurrent.TimeUnit.SECONDS));
        automation.waitForIdle(100, 3000);
    }
    private boolean touchReady(View view) {
        return view != null && view.isAttachedToWindow() && view.getVisibility() == View.VISIBLE
            && (((WindowManager.LayoutParams) view.getLayoutParams()).flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0;
    }
    private View overlayView(String name) {
        try {
            Field field = PetOverlay.class.getDeclaredField(name); field.setAccessible(true);
            return (View) field.get(PetOverlay.get(context));
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private boolean visible(String field) {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> {
            View view = overlayView(field); result.set(view != null && view.isAttachedToWindow() && view.getVisibility() == View.VISIBLE);
        });
        return result.get();
    }
    private boolean entryRunning() {
        AtomicReference<Boolean> value = new AtomicReference<>(true);
        instrumentation.runOnMainSync(() -> {
            try { Field field = PetOverlay.class.getDeclaredField("entering"); field.setAccessible(true); value.set(field.getBoolean(PetOverlay.get(context))); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        return value.get();
    }
    private Rect petBounds() {
        AtomicReference<Rect> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(PetOverlay.get(context).petBounds())); return result.get();
    }
    private Rect overlayBounds(String field) {
        AtomicReference<Rect> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(boundsOnMain(overlayView(field)))); return result.get();
    }
    private Set<Integer> overlayWindowIds() {
        Set<Integer> ids = new HashSet<>();
        instrumentation.runOnMainSync(() -> {
            for (String field : new String[]{"pet", "bubbleWindow"}) {
                AccessibilityNodeInfo node = overlayView(field).createAccessibilityNodeInfo();
                try { assertTrue("Overlay needs a registered window ID", node.getWindowId() >= 0); ids.add(node.getWindowId()); }
                finally { node.recycle(); }
            }
        });
        return ids;
    }
    private Rect viewBounds(View view) {
        AtomicReference<Rect> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(boundsOnMain(view))); return result.get();
    }
    private Rect boundsOnMain(View view) {
        assertNotNull(view); int[] position = new int[2]; view.getLocationOnScreen(position);
        return new Rect(position[0], position[1], position[0] + view.getWidth(), position[1] + view.getHeight());
    }
    private void tapPet() {
        Rect bounds = petBounds(); long down = SystemClock.uptimeMillis();
        inject(down, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY());
        try { SystemClock.sleep(48); }
        finally { inject(down, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY()); }
    }
    private void inject(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 1, 1, 0, 1, 1, touchDeviceId, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue("Android rejected the pet touch", automation.injectInputEvent(event, true)); }
        finally { event.recycle(); }
    }
    private int darkPixels(Bitmap image, Rect region) {
        int count = 0;
        for (int y = region.top; y < region.bottom; y += 2) for (int x = region.left; x < region.right; x += 2) {
            int color = image.getPixel(x, y);
            if (Color.red(color) < 50 && Color.green(color) < 50 && Color.blue(color) < 50) count++;
        }
        return count;
    }
    private void assertFixturePixels(Bitmap image, Rect overlayRegion) {
        Rect region = new Rect(overlayRegion), targetBounds = viewBounds(target), titleBounds = viewBounds(title);
        region.inset(dp(4), dp(4)); assertTrue(region.intersect(viewBounds(page)));
        int checked = 0;
        for (int y = region.top; y < region.bottom; y += 3) for (int x = region.left; x < region.right; x += 3) {
            if (titleBounds.contains(x, y)) continue;
            int expected = targetBounds.contains(x, y) ? TARGET : BACKGROUND, actual = image.getPixel(x, y);
            assertTrue("Overlay pixels remain in executor screenshot at " + x + "," + y,
                Math.abs(Color.red(expected) - Color.red(actual)) <= 8
                    && Math.abs(Color.green(expected) - Color.green(actual)) <= 8
                    && Math.abs(Color.blue(expected) - Color.blue(actual)) <= 8);
            checked++;
        }
        assertTrue("Too little fixture area was checked for overlay pixels", checked > 30);
    }
    private void captureUi(String stage) throws Exception {
        Bitmap image = automation.takeScreenshot(); assertNotNull(image);
        try { save(image, stage); } finally { image.recycle(); }
    }
    private void save(Bitmap image, String stage) throws Exception {
        File external = context.getExternalFilesDir(null); assertNotNull(external);
        File directory = new File(external, "pet-avoidance-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File output = new File(directory, stage + ".png");
        try (FileOutputStream stream = new FileOutputStream(output)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
        Bundle status = new Bundle(); status.putString("stream", "\nDROY_PET_AVOIDANCE_EVIDENCE " + output.getAbsolutePath() + "\n");
        instrumentation.sendStatus(2, status);
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
