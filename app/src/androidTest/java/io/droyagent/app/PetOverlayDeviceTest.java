package io.droyagent.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real overlay windows and Android touch dispatch; no task is sent to a model. */
@RunWith(AndroidJUnit4.class)
public final class PetOverlayDeviceTest {
    private static final String NAME = "Droy";
    private static final String MESSAGE = "需要你选择接下来打开哪个页面。";
    private static final String[] PREF_KEYS = {"petEnabled", "petExternalDisplay", "petInternalDisplay", "petId", "petName", "petLeft", "petX", "petY", "themeColorScheme", BackgroundRuntime.ENABLED, BackgroundRuntime.STOPPED};
    private Instrumentation instrumentation;
    private UiAutomation automation;
    private Context context;
    private SharedPreferences preferences;
    private android.app.Activity activity;
    private DeviceUi ui;
    private int originalAccessibilityFlags;
    private boolean accessibilityFlagsChanged, startedRuntime;
    private final Map<String, Object> originalPreferences = new HashMap<>();
    private final Map<Field, Object> originalProgress = new HashMap<>();

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        assertTrue("Run pet tests with existing tasks and runtime stopped", RuntimeService.canManageHistory());
        assertTrue("Grant Droy the display-over-other-apps permission before this suite", Settings.canDrawOverlays(context));
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        Map<String, ?> values = preferences.getAll();
        for (String key : PREF_KEYS) if (values.containsKey(key)) originalPreferences.put(key, values.get(key));
        // The fixture's synthetic progress is process-local; restore it without adding a production reset API.
        synchronized (ToolProgress.class) {
            for (String name : new String[]{"currentTask", "currentPhase", "summary", "summaryKind", "activityKind", "pending", "active", "startedAt", "activeTasks"}) {
                Field field = ToolProgress.class.getDeclaredField(name); field.setAccessible(true);
                originalProgress.put(field, field.get(null));
            }
        }
        AccessibilityServiceInfo service = automation.getServiceInfo();
        originalAccessibilityFlags = service.flags;
        service.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(service); accessibilityFlagsChanged = true;
        assertTrue(preferences.edit().putBoolean("petEnabled", false).putString("petExternalDisplay", "off").putString("petInternalDisplay", "off").putString("petId", "default")
            .putBoolean(BackgroundRuntime.ENABLED, true).putBoolean(BackgroundRuntime.STOPPED, false).commit());
        ToolProgress.interruptAll();
        instrumentation.runOnMainSync(() -> {
            ToolProgress.update("pet-device-fixture-idle", "thinking", true);
            try {
                ToolProgress.updateFromEngine(new JSONObject().put("taskId", "pet-device-fixture-idle")
                    .put("phase", "completed").put("active", false).put("summary", "").put("pending", JSONObject.NULL));
            } catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
        });
        startedRuntime = true;
        activity = instrumentation.startActivitySync(new Intent(context, NativeAppActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, null);
        DeviceUi.await("The native container did not start its actual runtime", 30000,
            () -> RuntimeService.ready && NativeRuntimeHost.inspect(context).ready);
        assertTrue("Opening Droy did not start its actual runtime", RuntimeService.hasActiveSession());
        assertTrue(preferences.edit().putBoolean("petEnabled", true).putString("petExternalDisplay", "always").putString("petInternalDisplay", "always").putString("petName", NAME)
            .putBoolean("petLeft", false).putFloat("petX", 1f).putFloat("petY", .4f)
            .putString("themeColorScheme", "light").commit());
        goHome();
        PetOverlay.refresh(context);
        try { DeviceUi.await("The enabled pet did not appear over another app", 5000, () -> petVisible() && !(Boolean)overlayField("entering")); }
        catch (AssertionError failure) {
            reportSetupFailure(); capture("pet-setup-failure"); throw failure;
        }
    }

    private void reportSetupFailure() {
        AtomicReference<String> details = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            JSONObject progress = ToolProgress.current(context);
            String mode = PetOverlay.displayMode(context, false);
            long now = SystemClock.elapsedRealtime();
            PetDisplayPolicy policy = (PetDisplayPolicy)overlayFieldOnMain("displayPolicy");
            long previewUntil = (Long)overlayFieldOnMain("previewUntil");
            details.set("runtimeActive=" + RuntimeService.hasActiveSession()
                + " runtimeReady=" + RuntimeService.ready
                + " backgroundEnabled=" + BackgroundRuntime.enabled(context)
                + " backgroundStopped=" + BackgroundRuntime.explicitlyStopped(context)
                + " backgroundKeepRunning=" + BackgroundRuntime.keepRunning(context)
                + " overlayPermission=" + Settings.canDrawOverlays(context)
                + " mode=" + mode + " phase=" + progress.optString("phase")
                + " taskActive=" + progress.optBoolean("active") + " pending=" + (progress.optJSONObject("pending") != null)
                + " policyVisible=" + policy.visible(mode, progress.optBoolean("active"), progress.optJSONObject("pending") != null, now < previewUntil, now)
                + " appVisible=" + overlayFieldOnMain("appVisible")
                + " petBounds=" + PetOverlay.get(context).petBounds()
                + " entering=" + overlayFieldOnMain("entering") + " exiting=" + overlayFieldOnMain("exiting")
                + " petHidden=" + overlayFieldOnMain("petHidden") + " clearanceDepth=" + overlayFieldOnMain("clearanceDepth")
                + " locale=" + NativeLocale.active());
        });
        Bundle state = new Bundle(); state.putString("stream", "\nDROY_PET_SETUP_FAILURE " + details.get()
            + " windows=" + windowVisibilityDiagnostics() + "\n");
        instrumentation.sendStatus(2, state);
    }

    @After public void tearDown() throws Exception {
        try {
            if (startedRuntime) {
                if (activity != null && !activity.isDestroyed()) instrumentation.runOnMainSync(activity::finish);
                instrumentation.runOnMainSync(RuntimeService::requestStop);
                DeviceUi.await("Pet test left the runtime running", 15000, RuntimeService::canManageHistory);
                PetOverlay.runtimeStopped();
                instrumentation.waitForIdleSync();
            }
        } finally {
            if (preferences != null) {
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
            if (accessibilityFlagsChanged) {
                AccessibilityServiceInfo service = automation.getServiceInfo();
                service.flags = originalAccessibilityFlags; automation.setServiceInfo(service);
            }
        }
    }

    @Test public void actualTouchOpensTheBubbleDragsDocksAndRestoresThePosition() throws Exception {
        capture("01-idle-peek");
        tap(petBounds());
        DeviceUi.await("Tapping the cat did not open its message", 5000,
            () -> cachedMessage().startsWith(NAME) && nativeBounds(cachedMessage(), false) != null);
        String firstGreeting = cachedMessage();
        capture("02-tap-bubble");
        tap(petBounds());
        DeviceUi.await("Tapping the cat did not fold its greeting", 3000, () -> nativeBounds(firstGreeting, false) == null);
        tap(petBounds());
        DeviceUi.await("A new intentional greeting did not vary", 3000,
            () -> !firstGreeting.equals(cachedMessage()) && cachedMessage().startsWith(NAME)
                && nativeBounds(cachedMessage(), false) != null);
        tap(awaitText("查看任务"));
        DeviceUi.await("The bubble's task button did not return to Droy", 10000, this::activityFocused);
        assertFalse("Returning to Droy left the overlay message open", bubbleVisible());
        goHome();

        Rect screen = displayBounds(), safe = safeBounds(), from = petBounds();
        int targetLeft = screen.width() * 3 / 10;
        int targetTop = safe.top + safe.height() / 3;
        ui.swipe(from.centerX(), from.centerY(), targetLeft + from.width() / 2f,
            targetTop + from.height() / 2f, 360);
        try {
            DeviceUi.await("The pet did not follow the actual drag", 3000,
                () -> Math.abs(petBounds().left - targetLeft) <= 3 && Math.abs(petBounds().top - targetTop) <= 3);
        } catch (AssertionError failure) {
            capture("drag-failure");
            throw new AssertionError("Drag started at " + from + ", expected " + targetLeft + "," + targetTop + ", actual " + petBounds(), failure);
        }
        assertTrue("The drag did not save a new horizontal position", preferences.getFloat("petX", 1f) < .5f);
        assertTrue("The drag did not save the left docking side", preferences.getBoolean("petLeft", false));
        capture("03-free-drag");
        DeviceUi.await("An idle pet did not settle at the nearest screen edge", 11000,
            () -> Math.abs(petBounds().left - safe.left) <= 2);
        capture("04-left-peek");
        Rect docked = petBounds();

        setEnabled(false);
        DeviceUi.await("Disabling the pet did not remove its window", 3000, () -> petBounds().isEmpty() && !petVisible());
        assertFalse("Disabling the pet left a message window behind", bubbleVisible());
        capture("05-disabled");
        setEnabled(true);
        DeviceUi.await("Re-enabling the pet did not restore its saved edge", 5000,
            () -> petVisible() && Math.abs(petBounds().left - docked.left) <= 2 && Math.abs(petBounds().top - docked.top) <= 2);

        from = petBounds();
        int rightEdge = safe.right - from.width();
        ui.swipe(from.centerX(), from.centerY(), rightEdge - dp(12) + from.width() / 2f,
            from.centerY(), 360);
        DeviceUi.await("Releasing close to an edge did not snap the cat into place", 3000,
            () -> Math.abs(petBounds().left - rightEdge) <= 2);
        assertFalse("Dragging to the right did not update the docking side", preferences.getBoolean("petLeft", true));
        capture("06-right-edge");
    }

    @Test public void pendingMessagePersistsAndCaptureWaitsForHiddenWindowsThenRestoresThem() throws Exception {
        JSONObject pending = new JSONObject().put("requestId", "pet-device-fixture-request")
            .put("title", "选择页面").put("message", MESSAGE).put("inline", true)
            .put("options", new JSONArray().put(new JSONObject().put("id", "page-a").put("label", "打开示例页面"))
                .put(new JSONObject().put("id", "stay").put("label", "先停在这里")));
        JSONObject progress = new JSONObject().put("taskId", "pet-device-fixture-task")
            .put("phase", "waiting_input").put("active", true).put("summary", "等待你的选择").put("pending", pending);
        publish(progress);
        instrumentation.waitForIdleSync();
        DeviceUi.await("A real pending question did not open its answer surface", 500, this::bubbleVisible);
        awaitText(MESSAGE);
        assertNotNull("The simple answer was not rendered as an accessible button", awaitText("打开示例页面"));
        capture("07-pending-light");
        SystemClock.sleep(6500);
        assertNotNull("An unanswered question disappeared on the ordinary progress timer", nativeBounds(MESSAGE, false));
        tap(petBounds());
        DeviceUi.await("Tapping the cat did not let the user fold its question", 3000, () -> !bubbleVisible());
        PetOverlay.refresh(context);
        instrumentation.waitForIdleSync();
        assertFalse("An unchanged request reopened immediately after the user folded it", bubbleVisible());
        tap(petBounds());
        awaitText(MESSAGE);

        assertTrue(preferences.edit().putString("themeColorScheme", "dark").commit());
        instrumentation.runOnMainSync(() -> PetOverlay.get(context).preview());
        awaitText(MESSAGE);
        capture("08-pending-dark");
        AtomicReference<CompletableFuture<Void>> prepared = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> prepared.set(PetOverlay.get(context).prepare(new Rect(), true)));
        prepared.get().get(3, TimeUnit.SECONDS);
        DeviceUi.await("Capture preparation completed with pet pixels still accessible", 3000,
            () -> !petVisible() && !bubbleVisible());
        capture("09-capture-clearance");
        long finishedAt = SystemClock.elapsedRealtime();
        instrumentation.runOnMainSync(() -> PetOverlay.get(context).finish());
        DeviceUi.await("The pet and pending question did not promptly return after capture", 750,
            () -> petVisible() && nativeBounds(MESSAGE, false) != null);
        assertTrue("Capture recovery still waits through the old hide interval", SystemClock.elapsedRealtime() - finishedAt < 850);
        assertEquals("Capture recovery lost the pending request", "pet-device-fixture-request",
            ToolProgress.current(context).getJSONObject("pending").getString("requestId"));
        capture("10-pending-restored");
        setEnabled(false);
        DeviceUi.await("The off switch left the persistent confirmation visible", 3000,
            () -> petBounds().isEmpty() && !petVisible() && !bubbleVisible());
    }

    @Test public void structuredQuestionSelectsMultipleAnswersTypesOverAnotherAppAndPreservesItsDraft() throws Exception {
        Bundle accessibilityState = new Bundle();
        accessibilityState.putString("stream", "\nDROY_PET_IME_ACCESSIBILITY connected="
            + io.droyagent.androiduse.DroyAccessibilityService.isConnected(context) + "\n");
        instrumentation.sendStatus(2, accessibilityState);
        JSONObject question = new JSONObject().put("id", "destinations").put("question", "选择要打开的页面")
            .put("multiSelect", true).put("allowCustom", true).put("options", new JSONArray()
                .put(new JSONObject().put("label", "页面甲")).put(new JSONObject().put("label", "页面乙")));
        JSONObject pending = new JSONObject().put("requestId", "structured-question-fixture").put("title", "需要你的选择")
            .put("message", "选择要打开的页面").put("inline", true).put("options", new JSONArray())
            .put("questions", new JSONArray().put(question));
        publish(new JSONObject().put("taskId", "structured-question-task").put("phase", "waiting_input")
            .put("active", true).put("summary", "等待选择").put("pending", pending));
        awaitText("页面甲");
        assertFalse("A new question stole focus before the user chose to type", (Boolean)overlayField("editingAnswer"));
        assertTrue("A new question opened the keyboard without a touch", keyboardWindowBounds().isEmpty());
        tap(awaitText("页面甲")); tap(awaitText("页面乙"));
        PetQuestionDraft draft = (PetQuestionDraft)overlayField("questionDraft");
        assertEquals(2, draft.answers.get(0).selected.size());
        assertEquals("?", ((android.widget.TextView)overlayField("pendingMarker")).getText().toString());
        String description = "选择要打开的页面" + NativeLocale.text("，手动填写回答", ". Write an answer");
        String submitLabel = NativeLocale.text("发送回答", "Send answer");
        AtomicReference<Rect> field = new AtomicReference<>();
        DeviceUi.await("Custom-answer field is not visible", 3000, () -> {
            field.set(nativeBounds(description, true)); return field.get() != null;
        });
        tap(field.get());
        DeviceUi.await("Typing did not give the answer bubble input focus", 5000, () -> (Boolean)overlayField("editingAnswer"));
        DeviceUi.await("The actual IME did not appear over the other app", 5000, () -> !((Rect)overlayField("keyboard")).isEmpty());
        DeviceUi.await("Companion IME geometry does not match the actual keyboard", 5000, () -> {
            Rect actualIme = keyboardWindowBounds(), observed = (Rect)overlayField("keyboard");
            return !actualIme.isEmpty() && Math.abs(actualIme.top - observed.top) <= dp(2);
        });
        Rect actualIme = keyboardWindowBounds();
        assertTrue("Answer field overlaps the keyboard", nativeBounds(description, true).bottom <= actualIme.top);
        assertTrue("Answer bubble overlaps the keyboard", bubbleBounds().bottom <= actualIme.top);
        assertTrue("Accessible input could not edit the answer", setNativeText(description, "页面丙"));
        DeviceUi.await("Custom answer did not reach the shared draft", 3000, () -> draft.answers.get(0).custom.equals("页面丙"));
        assertEquals("Typing discarded selected multi-choice answers", 2, draft.answers.get(0).selected.size());
        for (int attempt = 0; attempt < 3 && nativeBounds(submitLabel, false) == null; attempt++) {
            Rect area = bubbleBounds();
            ui.swipe(area.centerX(), area.bottom - dp(20), area.centerX(), area.top + dp(20), 300);
        }
        assertTrue("Submit action cannot be reached above the keyboard", awaitText(submitLabel).bottom <= actualIme.top);
        capture("structured-question-keyboard");
        automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        DeviceUi.await("Back did not close the IME", 5000, () -> ((Rect)overlayField("keyboard")).isEmpty());
        tap(petBounds());
        DeviceUi.await("Question did not fold", 3000, () -> overlayField("bubbleWindow") == null);
        tap(petBounds());
        awaitText("页面丙");
        assertSame("Folding lost the answer draft", draft, overlayField("questionDraft"));
        assertEquals(2, draft.encode().getJSONObject(0).getJSONArray("selected").length());
        capture("structured-question-restored");
        pending.put("requestId", "replacement-question-fixture");
        publish(new JSONObject().put("taskId", "structured-question-task").put("phase", "waiting_input")
            .put("active", true).put("summary", "新问题").put("pending", pending));
        DeviceUi.await("A replacement question kept the previous draft", 3000, () -> overlayField("questionDraft") != draft);
        assertEquals("", ((PetQuestionDraft)overlayField("questionDraft")).answers.get(0).custom);
    }

    @Test public void stageMessagesCoalesceFastChangesAndQuestionsTakePriority() throws Exception {
        JSONObject progress = new JSONObject().put("taskId", "stage-question-fixture").put("phase", "thinking")
            .put("active", true).put("summary", "处理中").put("summaryKind", "working")
            .put("activityKind", "started").put("pending", JSONObject.NULL);
        publish(progress);
        DeviceUi.await("Task start did not announce the companion", 3000, () -> cachedMessage().startsWith(NAME));
        String started = cachedMessage(); awaitText(started);
        publish(progress.put("phase", "verifying").put("activityKind", "observing"));
        publish(progress.put("phase", "executing").put("activityKind", "controlling"));
        SystemClock.sleep(1000);
        assertEquals("Rapid stages replaced the start bubble too quickly", started, cachedMessage());
        AtomicReference<CompletableFuture<Void>> hidden = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> hidden.set(PetOverlay.get(context).prepare(new Rect(), true)));
        hidden.get().get(3, TimeUnit.SECONDS);
        SystemClock.sleep(3500);
        assertEquals("A hidden stage was marked as shown before its window could appear", "started",
            ((PetChatterPolicy)overlayField("chatter")).current());
        instrumentation.runOnMainSync(() -> PetOverlay.get(context).finish());
        DeviceUi.await("The latest stable stage was lost during capture", 5000, () -> !cachedMessage().equals(started));
        assertTrue(cachedMessage().startsWith(NAME)); capture("buffered-stage-message");
        JSONObject pending = new JSONObject().put("requestId", "priority-question-fixture").put("title", "选择")
            .put("message", "现在需要你的回答").put("inline", true).put("options", new JSONArray()
                .put(new JSONObject().put("id", "continue").put("label", "继续")));
        publish(progress.put("phase", "waiting_input").put("pending", pending));
        awaitText("现在需要你的回答"); awaitText("继续");
        capture("question-interrupts-stage");
    }

    private Rect keyboardWindowBounds() {
        Rect bounds = new Rect();
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            try { if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) window.getBoundsInScreen(bounds); }
            finally { window.recycle(); }
        }
        return bounds;
    }
    private Rect bubbleBounds() {
        WindowManager.LayoutParams layout = (WindowManager.LayoutParams)overlayField("bubbleLayout");
        return new Rect(layout.x, layout.y, layout.x + layout.width, layout.y + layout.height);
    }

    private boolean setNativeText(String description, String value) {
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            try {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) try { if (setNativeText(root, description, value)) return true; }
                finally { root.recycle(); }
            } finally { window.recycle(); }
        }
        return false;
    }
    private boolean setNativeText(AccessibilityNodeInfo node, String description, String value) {
        if (description.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) {
            Bundle text = new Bundle(); text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) try { if (setNativeText(child, description, value)) return true; } finally { child.recycle(); }
        }
        return false;
    }

    @Test public void pendingQuestionUsesItsOwnTaskInsteadOfAnEarlierProactiveNotice() throws Exception {
        publish(new JSONObject().put("taskId", "notice-task-a").put("phase", "executing")
            .put("active", true).put("summary", "任务甲处理中").put("pending", JSONObject.NULL));
        assertTrue(PetOverlay.notify(context, "notice-task-a", "earlier-notice", "任务甲的通知"));
        JSONObject question = new JSONObject().put("requestId", "request-b").put("title", "需要回应")
            .put("message", "任务乙的问题").put("inline", false).put("options", new JSONArray());
        publish(new JSONObject().put("taskId", "question-task-b").put("phase", "waiting_input")
            .put("active", true).put("summary", "任务乙的问题").put("pending", question));
        awaitText("任务乙的问题");
        tap(awaitText("查看并处理"));
        DeviceUi.await("The pending question was routed using the earlier notification's task", 3000, () -> {
            AtomicReference<String> target = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> target.set(activity.getIntent().getStringExtra("petTaskId")));
            return "question-task-b".equals(target.get());
        });
        AtomicReference<String> request = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> request.set(activity.getIntent().getStringExtra("petRequestId")));
        assertEquals("request-b", request.get());
    }

    @Test public void slowModelRequestShowsStopControlsOnceAndClearsWhenExecutionResumes() throws Exception {
        publish(new JSONObject().put("taskId", "earlier-slow-notice").put("phase", "executing")
            .put("active", true).put("summary", "先前任务的结果").put("pending", JSONObject.NULL));
        assertTrue(PetOverlay.notify(context, "earlier-slow-notice", "slow-overlap-notice", "先前任务的结果"));
        publish(new JSONObject().put("taskId", "earlier-slow-notice").put("phase", "completed")
            .put("active", false).put("summary", "先前任务的结果").put("pending", JSONObject.NULL));
        String message = "模型请求较慢，已等待超过10秒，可停止任务";
        JSONObject slow = new JSONObject().put("taskId", "pet-slow-fixture").put("phase", "thinking")
            .put("active", true).put("summary", message).put("summaryKind", "slow").put("pending", JSONObject.NULL);
        publish(slow);
        awaitText(message);
        assertNotNull("Slow model wait must keep a stop control", awaitText("停止任务"));
        capture("slow-model-stop");
        tap(petBounds());
        DeviceUi.await("The slow message did not fold", 3000, () -> !bubbleVisible());
        publish(slow); instrumentation.waitForIdleSync();
        assertFalse("The same slow request reopened after a duplicate event", bubbleVisible());
        publish(new JSONObject().put("taskId", "pet-slow-fixture").put("phase", "executing")
            .put("active", true).put("summary", "正在执行操作").put("pending", JSONObject.NULL));
        tap(petBounds()); awaitText("正在执行操作");
        assertNull("Execution retained the slow request copy", nativeBounds(message, false));
    }

    @Test public void ordinaryProgressIsCompactAndExpandsOnlyWhenTapped() throws Exception {
        String message = "页面有变化，正在重新确认";
        publish(new JSONObject().put("taskId", "pet-device-compact-task").put("phase", "thinking")
            .put("active", true).put("summary", message).put("pending", JSONObject.NULL));
        instrumentation.waitForIdleSync();
        assertFalse("Ordinary progress opened a notification by itself", bubbleVisible());
        assertTrue(PetOverlay.notify(context, "pet-device-compact-task", "compact-selected-notice", message));
        Rect text = awaitText(message);
        Rect compact = new Rect();
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            try {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) try { if (findNode(root, message, false) != null) window.getBoundsInScreen(compact); }
                finally { root.recycle(); }
            }
            finally { window.recycle(); }
        }
        assertFalse("The compact message window is missing", compact.isEmpty());
        assertTrue("Ordinary bubble is wider than its compact limit", compact.width() <= dp(196) + 1);
        assertTrue("Ordinary progress wastes vertical space", compact.height() <= dp(100));
        assertTrue("Bubble text escaped its window", compact.contains(text));
        assertNull("Ordinary progress should not expose large action controls", nativeBounds("停止任务", false));
        Rect cat = petBounds();
        assertTrue("Speech bubble is detached from the cat", Math.abs(compact.right - cat.left) <= 2
            || Math.abs(compact.left - cat.right) <= 2 || Math.abs(compact.bottom - cat.top) <= 2
            || Math.abs(compact.top - cat.bottom) <= 2);
        capture("11-compact-progress");
        tap(text);
        Rect stop = awaitText("停止任务");
        assertTrue("Expanded controls lost their touch target", stop.height() >= dp(48));
        capture("12-expanded-progress");
        tap(petBounds());
        DeviceUi.await("Expanded bubble did not fold", 3000, () -> !bubbleVisible());
        publish(new JSONObject().put("taskId", "pet-device-compact-task").put("phase", "executing")
            .put("active", true).put("summary", "正在查看当前页面").put("pending", JSONObject.NULL));
        instrumentation.waitForIdleSync();
        assertFalse("A subsequent progress update reopened the notification", bubbleVisible());
        tap(petBounds());
        awaitText("正在查看当前页面");
        DeviceUi.await("Recoverable progress stayed pinned like a blocker", 8000, () -> !bubbleVisible());
    }

    @Test public void newlyRegisteredWindowsCanBeClosedBeforeTheirFirstFrame() throws Exception {
        instrumentation.runOnMainSync(() -> {
            invokeOverlay("close", new Class<?>[]{});
            invokeOverlay("refreshNow", new Class<?>[]{});
            invokeOverlay("showBubble", new Class<?>[]{boolean.class}, true);
            android.view.View pet = (android.view.View)overlayFieldOnMain("pet");
            android.view.View bubble = (android.view.View)overlayFieldOnMain("bubbleWindow");
            assertNotNull(pet); assertNotNull(bubble);
            assertFalse("Fixture must run before the first attach traversal", pet.isAttachedToWindow());
            assertFalse(bubble.isAttachedToWindow());
            assertNotNull(pet.getParent()); assertNotNull(bubble.getParent());
            invokeOverlay("close", new Class<?>[]{});
            assertNull("Closing left a registered pet window", pet.getParent());
            assertNull("Closing left a registered bubble window", bubble.getParent());
        });
        PetOverlay.refresh(context);
        DeviceUi.await("The pet could not recover after immediate close", 5000, this::petVisible);
    }

    @Test public void alreadyDetachedWindowsCanBeClosedAndRecreated() throws Exception {
        tap(petBounds());
        DeviceUi.await("The message window did not appear", 3000, this::bubbleVisible);
        instrumentation.runOnMainSync(() -> {
            WindowManager manager = (WindowManager)overlayFieldOnMain("windows");
            manager.removeViewImmediate((android.view.View)overlayFieldOnMain("bubbleWindow"));
            manager.removeViewImmediate((android.view.View)overlayFieldOnMain("pet"));
        });
        PetOverlay.runtimeStopped();
        instrumentation.waitForIdleSync();
        assertTrue("Closing detached windows retained the pet", petBounds().isEmpty());
        assertFalse("Closing detached windows retained the message", bubbleVisible());
        PetOverlay.refresh(context);
        DeviceUi.await("The pet could not recover after its windows were removed", 5000, this::petVisible);
    }

    @Test public void announcedWaitStaysVisibleCanBeFoldedAndClearsOnResumeAndCancel() throws Exception {
        String message = "等待10:00开抢，到点后领取优惠券";
        JSONObject progress = new JSONObject().put("taskId", "pet-device-wait-task").put("phase", "waiting")
            .put("active", true).put("summary", message).put("pending", JSONObject.NULL);
        publish(progress);
        instrumentation.waitForIdleSync();
        assertFalse("An announced wait opened a bubble without selection", bubbleVisible());
        tap(petBounds());
        awaitText(message);
        capture("wait-01-visible");
        SystemClock.sleep(6500);
        assertNotNull("The explicit wait disappeared on the normal six-second timer", nativeBounds(message, false));
        awaitText(NAME + " · 等待中");
        awaitText("停止任务");
        capture("wait-02-expanded");
        tap(petBounds());
        DeviceUi.await("The user could not fold the waiting bubble", 3000, () -> !bubbleVisible());
        PetOverlay.refresh(context); instrumentation.waitForIdleSync();
        assertFalse("An unchanged wait reopened after being folded", bubbleVisible());
        tap(petBounds());
        awaitText(message);
        publish(progress.put("phase", "executing").put("summary", "已到时间，正在领取优惠券"));
        awaitText("已到时间，正在领取优惠券");
        assertNull("Resuming work left the waiting message on screen", nativeBounds(message, false));
        capture("wait-03-resumed");
        DeviceUi.await("Resumed work retained a persistent waiting bubble", 8000, () -> !bubbleVisible());
        publish(progress.put("phase", "waiting").put("summary", message));
        tap(petBounds());
        awaitText(message);
        publish(progress.put("phase", "interrupted").put("active", false).put("summary", "本轮执行已停止，点我查看任务"));
        awaitText("本轮执行已停止，点我查看任务");
        assertNull("Cancellation left stale waiting text", nativeBounds(message, false));
        capture("wait-04-cancelled");
    }

    @Test public void workingChatterStaysStableAcrossPhasesAndUnrelatedInputDoesNotHideThePet() throws Exception {
        JSONObject progress = new JSONObject().put("taskId", "pet-device-working-task").put("phase", "thinking")
            .put("active", true).put("summary", "正在努力执行").put("summaryKind", "working").put("pending", JSONObject.NULL);
        publish(progress);
        instrumentation.waitForIdleSync();
        assertFalse("Working status opened an unsolicited bubble", bubbleVisible());
        tap(petBounds());
        DeviceUi.await("Working chatter did not use the custom pet name", 5000,
            () -> cachedMessage().startsWith(NAME) && nativeBounds(cachedMessage(), false) != null);
        String message = cachedMessage();
        Object firstBubble = overlayField("bubble");
        for (String phase : new String[]{"executing", "verifying", "thinking"}) publish(progress.put("phase", phase));
        SystemClock.sleep(5100);
        assertEquals("Mechanical phases randomized the existing bubble", message, cachedMessage());
        assertSame("Mechanical phases rebuilt the existing working bubble", firstBubble, overlayField("bubble"));
        assertNotNull("Stable working chatter vanished before its normal timeout", nativeBounds(message, false));
        AtomicReference<CompletableFuture<Void>> prepared = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> prepared.set(PetOverlay.get(context).prepare(new Rect(0, 0, 1, 1), false)));
        prepared.get().get(3, TimeUnit.SECONDS);
        assertTrue("An unrelated input hid the cat", petVisible());
        assertSame("An unrelated input rebuilt the bubble", firstBubble, overlayField("bubble"));
        instrumentation.runOnMainSync(() -> PetOverlay.get(context).finish());
        assertTrue("Finishing an unrelated input hid the cat", petVisible());
        awaitText("停止任务");
        assertEquals("Expanding the current bubble randomized its message", message, cachedMessage());
        capture("13-stable-working-chatter");
        tap(petBounds());
        DeviceUi.await("The expanded working bubble did not fold", 3000, () -> nativeBounds(message, false) == null);
        tap(petBounds());
        DeviceUi.await("A new deliberate working greeting repeated the previous sentence", 3000,
            () -> !message.equals(cachedMessage()) && cachedMessage().startsWith(NAME)
                && nativeBounds(cachedMessage(), false) != null);
    }

    @Test public void adaptiveHasNoIdleWindowKeepsWorkAndDismissesOnlyTheFinalNotice() throws Exception {
        assertTrue(preferences.edit().putString("petExternalDisplay", "adaptive").putString("petInternalDisplay", "off").commit());
        PetOverlay.refresh(context);
        DeviceUi.await("Adaptive idle left a touchable pet window", 4000, () -> petBounds().isEmpty());
        publish(new JSONObject().put("taskId", "adaptive-fixture").put("phase", "executing").put("active", true)
            .put("summary", "正在核对示例内容").put("pending", JSONObject.NULL));
        DeviceUi.await("Adaptive task did not bring the pet in", 5000, () -> petVisible() && !(Boolean)overlayField("entering"));
        Object sprite = overlayField("sprite");
        AtomicReference<CompletableFuture<Void>> prepared = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> prepared.set(PetOverlay.get(context).prepare(new Rect(), true)));
        prepared.get().get(3, TimeUnit.SECONDS);
        assertFalse("Capture did not hide the adaptive pet", petVisible());
        instrumentation.runOnMainSync(() -> PetOverlay.get(context).finish());
        DeviceUi.await("Capture did not restore the active adaptive pet", 3000, this::petVisible);
        assertSame("Capture unnecessarily recreated the pet", sprite, overlayField("sprite"));
        assertFalse("Capture restoration replayed entry", (Boolean)overlayField("entering"));
        assertFalse("Execution state opened an unsolicited adaptive bubble", bubbleVisible());
        assertTrue(PetOverlay.notify(context, "adaptive-fixture", "adaptive-selected-result", "本轮执行已结束，点我查看结果"));
        publish(new JSONObject().put("taskId", "adaptive-fixture").put("phase", "completed").put("active", false)
            .put("summary", "本轮执行已结束，点我查看结果").put("pending", JSONObject.NULL));
        try { DeviceUi.await("Final notice did not appear", 3000, this::bubbleVisible); }
        catch (AssertionError failure) {
            capture("adaptive-final-notice-failure");
            throw new AssertionError("Final notice missing: progress=" + ToolProgress.current(context) + " snapshot=" + overlayField("snapshot")
                + " hidden=" + overlayField("petHidden") + "/" + overlayField("bubbleHidden") + " clearance=" + overlayField("clearanceDepth")
                + " appVisible=" + overlayField("appVisible") + " entering=" + overlayField("entering") + " last=" + overlayField("lastAnnouncement")
                + " bubble=" + overlayField("bubble") + " windows=" + windowVisibilityDiagnostics(), failure);
        }
        DeviceUi.await("Adaptive final notice never retired", 9000, () -> petBounds().isEmpty() && !bubbleVisible());
        PetOverlay.refresh(context); instrumentation.waitForIdleSync();
        assertTrue("An unchanged completion reopened after refresh", petBounds().isEmpty());
        capture("14-adaptive-idle-no-window");
    }

    @Test public void longPressPetsWithoutClickAndFollowingDragSetsTheInwardFacingDirection() throws Exception {
        Rect original = petBounds();
        ui.swipe(original.centerX(), original.centerY(), original.centerX(), original.centerY(), 800);
        assertFalse("Releasing a long press also triggered the click panel", bubbleVisible());
        AtomicReference<String> state = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> state.set(((PetSpriteView)overlayFieldOnMain("sprite")).state()));
        assertTrue("Long press did not use a petting variant", state.get().equals("pet") || state.get().equals("petShy"));
        capture("15-long-press-petting");
        Rect safe = safeBounds();
        ui.swipe(original.centerX(), original.centerY(), safe.left + original.width() / 2f, original.centerY(), 450);
        DeviceUi.await("Drag did not settle at the left edge", 3000, () -> Math.abs(petBounds().left - safe.left) <= 2);
        instrumentation.runOnMainSync(() -> {
            PetSpriteView sprite = (PetSpriteView)overlayFieldOnMain("sprite");
            try { Field facing = PetSpriteView.class.getDeclaredField("facingLeft"); facing.setAccessible(true);
                assertFalse("Left side pet is looking out of the screen", facing.getBoolean(sprite)); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        assertFalse("Dragging after petting opened a click panel", bubbleVisible());
        capture("16-left-body-inward-facing");
    }

    @Test public void disabledModesStillAllowAnActivityOwnedPreview() throws Exception {
        assertTrue(preferences.edit().putString("petExternalDisplay", "off").putString("petInternalDisplay", "off").commit());
        context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        DeviceUi.await("Droy did not resume for preview", 8000, this::activityFocused);
        PetOverlay.refresh(context);
        DeviceUi.await("Disabled internal mode still showed a daily pet", 3000, () -> petBounds().isEmpty());
        instrumentation.runOnMainSync(() -> PetOverlay.get(context).preview());
        DeviceUi.await("Preview did not bypass daily hiding", 3000, this::petVisible);
        WindowManager.LayoutParams layout = (WindowManager.LayoutParams)overlayField("petLayout");
        assertEquals("App preview must not need an overlay grant", WindowManager.LayoutParams.TYPE_APPLICATION, layout.type);
        assertNotNull("App preview is not attached to its Activity", layout.token);
        capture("17-internal-preview-with-modes-off");
        DeviceUi.await("Preview leaked a persistent window after its expiry", 10000, () -> petBounds().isEmpty());
    }

    @Test public void activityDisplayAndPreviewWorkWithOverlayPermissionActuallyRevoked() throws Exception {
        String current = shellOutput("appops get " + context.getPackageName() + " SYSTEM_ALERT_WINDOW");
        var match = java.util.regex.Pattern.compile("SYSTEM_ALERT_WINDOW: (allow|ignore|deny|default|foreground)").matcher(current);
        String originalMode = match.find() ? match.group(1) : "default";
        try {
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            DeviceUi.await("Droy did not resume for permission isolation", 8000, this::activityFocused);
            shellOutput("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW ignore");
            DeviceUi.await("Test did not actually revoke overlay permission", 3000, () -> !Settings.canDrawOverlays(context));
            assertTrue(preferences.edit().putString("petExternalDisplay", "always").putString("petInternalDisplay", "always").commit());
            PetOverlay.refresh(context);
            DeviceUi.await("Internal always display incorrectly requires an overlay grant", 3000, this::petVisible);
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION, ((WindowManager.LayoutParams)overlayField("petLayout")).type);
            capture("20-internal-always-with-overlay-permission-revoked");
            goHome();
            DeviceUi.await("External pet appeared without its required overlay permission", 3000, () -> petBounds().isEmpty() && !petVisible());
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            DeviceUi.await("Droy did not resume for isolated preview", 8000, this::activityFocused);
            assertTrue(preferences.edit().putString("petInternalDisplay", "off").commit());
            PetOverlay.refresh(context);
            DeviceUi.await("Daily internal display did not turn off", 3000, () -> petBounds().isEmpty());
            instrumentation.runOnMainSync(() -> PetOverlay.get(context).preview());
            DeviceUi.await("Preview incorrectly requires an overlay grant", 3000, this::petVisible);
            assertFalse(Settings.canDrawOverlays(context));
            capture("21-internal-preview-with-overlay-permission-revoked");
        } finally {
            shellOutput("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW " + originalMode);
            DeviceUi.await("Overlay grant was not restored after isolated test", 3000, () -> Settings.canDrawOverlays(context));
        }
    }
    private String shellOutput(String command) throws Exception {
        try (var input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test public void builtinSpecialActionsAreReachableFromTheVisiblePetAndRestoreTheTask() throws Exception {
        assertTrue("Enable system animations for the actual playback fixture", android.animation.ValueAnimator.areAnimatorsEnabled());
        String[][] entries = {
            {"default", "knead", "tailCurl", "sleep"},
            {"8dd37d0d-b6ed-47c2-9952-8f3be20fa6d0", "waveHands", "scream", "startle"},
            {"cb5c4126-8c3b-41a5-a1bd-3d3b95cdeee2", "neckStretch", "spitForward", "spitScreen"}
        };
        for (String[] entry : entries) {
            PetOverlay.select(context, entry[0]);
            DeviceUi.await("Built-in selection did not reach the overlay", 5000, () -> {
                Object view = overlayField("sprite");
                return view instanceof PetSpriteView value && entry[0].equals(value.packageId()) && !(Boolean)overlayField("entering");
            });
            for (int index = 1; index < entry.length; index++) {
                String state = entry[index];
                openPlayAction(state);
                DeviceUi.await("The selected action never played: " + state, 2000,
                    () -> state.equals(overlayField("interactionState")) && state.equals(((PetSpriteView)overlayField("sprite")).state()));
                if (state.equals("spitScreen")) {
                    android.view.View effect = (android.view.View)overlayField("effectWindow"); assertNotNull(effect);
                    WindowManager.LayoutParams params = (WindowManager.LayoutParams)effect.getLayoutParams();
                    assertTrue((params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
                    assertTrue((params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
                    assertTrue(params.alpha <= .6f);
                    Field drawn = PetScreenEffectView.class.getDeclaredField("lastDrawnFrame"); drawn.setAccessible(true);
                    int seen = 0; long deadline = SystemClock.uptimeMillis() + 2500;
                    while (SystemClock.uptimeMillis() < deadline && !(Boolean)onMain(() -> overlayFieldOnMain("interactionState").equals(""))) {
                        AtomicReference<Integer> frame = new AtomicReference<>();
                        instrumentation.runOnMainSync(() -> { try { frame.set(drawn.getInt(effect)); } catch (IllegalAccessException failure) { throw new AssertionError(failure); } });
                        if (frame.get() >= 0) seen |= 1 << frame.get();
                        SystemClock.sleep(10);
                    }
                    assertEquals("The real water layer did not draw all six authored frames", 0b111111, seen);
                }
                DeviceUi.await("Interaction did not restore its task state: " + state, 4000,
                    () -> "".equals(overlayField("interactionState")) && "completed".equals(((PetSpriteView)overlayField("sprite")).state()));
                assertNull("A finished interaction leaked a water window", overlayField("effectWindow"));
                if (state.equals("spitScreen")) {
                    // Evidence replay is separate so screenshot encoding cannot skip
                    // frames in the six-frame presentation assertion above.
                    openPlayAction(state);
                    Field drawn = PetScreenEffectView.class.getDeclaredField("lastDrawnFrame"); drawn.setAccessible(true);
                    DeviceUi.await("The evidence replay did not show its water splash", 2500, () -> {
                        android.view.View effect = (android.view.View)overlayField("effectWindow");
                        if (effect == null) return false;
                        int frame = onMain(() -> drawn.getInt(effect));
                        return frame == 2 || frame == 3;
                    });
                    capture("builtin-screen-spit-visible");
                    DeviceUi.await("The evidence replay leaked its water window", 4000,
                        () -> "".equals(overlayField("interactionState")) && overlayField("effectWindow") == null);
                }
            }
        }
    }

    @Test public void screenSpitClearsForCaptureDragSelectionAndPendingTaskControls() throws Exception {
        assertTrue(android.animation.ValueAnimator.areAnimatorsEnabled());
        String alpaca = "cb5c4126-8c3b-41a5-a1bd-3d3b95cdeee2";
        PetOverlay.select(context, alpaca);
        DeviceUi.await("Alpaca did not appear", 5000, () -> selectedSprite(alpaca));
        openPlayAction("spitScreen");
        CompletableFuture<Void> ready = onMain(() -> PetOverlay.get(context).prepare(new Rect(), true)); ready.get(3, TimeUnit.SECONDS);
        assertEquals("", overlayField("interactionState")); assertNull(overlayField("effectWindow"));
        instrumentation.runOnMainSync(() -> PetOverlay.get(context).finish());
        DeviceUi.await("Capture did not restore the visible alpaca", 3000, this::petVisible);

        openPlayAction("spitScreen");
        Rect from = petBounds(); ui.swipe(from.centerX(), from.centerY(), from.centerX() - dp(40), from.centerY(), 180);
        assertEquals("", overlayField("interactionState")); assertNull(overlayField("effectWindow"));

        openPlayAction("spitScreen"); PetOverlay.select(context, "default");
        DeviceUi.await("Selection did not cancel the old effect", 4000, () -> overlayField("effectWindow") == null && "".equals(overlayField("interactionState"))
            && selectedSprite("default"));

        PetOverlay.select(context, alpaca);
        DeviceUi.await("Alpaca did not reappear", 5000, () -> selectedSprite(alpaca));
        openPlayAction("spitScreen");
        JSONObject pending = new JSONObject().put("requestId", "pet-builtins-pending")
            .put("title", NativeLocale.text("需要你回应", "Your response is needed")).put("message", MESSAGE).put("inline", true)
            .put("options", new JSONArray().put(new JSONObject().put("id", "later").put("label", NativeLocale.text("稍后", "Later"))));
        instrumentation.runOnMainSync(() -> {
            ToolProgress.update("pet-builtins-question", "thinking", true);
            try { ToolProgress.updateFromEngine(new JSONObject().put("taskId", "pet-builtins-question").put("phase", "waiting")
                .put("active", true).put("summary", MESSAGE).put("pending", pending)); }
            catch (org.json.JSONException failure) { throw new AssertionError(failure); }
        });
        PetOverlay.refresh(context);
        DeviceUi.await("Question did not interrupt the play action", 3000, () -> "".equals(overlayField("interactionState")) && overlayField("effectWindow") == null && bubbleVisible());
        assertNull("Play menu displaced the live question", nativeBounds(NativeLocale.text("互动动作", "Play actions"), false));
        assertNotNull(nativeBounds(NativeLocale.text("稍后", "Later"), false));
        capture("builtin-pending-preempts-play");
    }

    private void openPlayAction(String state) throws Exception {
        tap(petBounds());
        if (state.equals("knead")) {
            assertTrue("The visible status lost the Droy identity", cachedMessage().startsWith("Droy"));
            capture("builtin-Droy-status");
        }
        tap(awaitText(NativeLocale.text("互动动作", "Play actions")));
        if (state.equals("knead")) capture("builtin-interaction-menu");
        AtomicReference<String> label = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> label.set(((PetSpriteView)overlayFieldOnMain("sprite")).interactionLabel(state)));
        tap(awaitText(label.get()));
    }
    private boolean selectedSprite(String id) {
        Object value = overlayField("sprite");
        return value instanceof PetSpriteView view && id.equals(view.packageId()) && !(Boolean)overlayField("entering");
    }

    private <T> T onMain(java.util.concurrent.Callable<T> callable) throws Exception {
        AtomicReference<T> result = new AtomicReference<>(); AtomicReference<Exception> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { result.set(callable.call()); } catch (Exception error) { failure.set(error); } });
        if (failure.get() != null) throw failure.get(); return result.get();
    }

    @Test public void customPackageSelectionLoadsVoiceAndRejectsBrokenCandidateWithoutReplacingIt() throws Exception {
        String id = java.util.UUID.randomUUID().toString(), brokenId = java.util.UUID.randomUUID().toString();
        File directory = new File(context.getFilesDir(), "dsh-home/pets/" + id);
        File broken = new File(context.getFilesDir(), "dsh-home/pets/" + brokenId);
        assertTrue(directory.mkdirs()); assertTrue(broken.mkdirs());
        try {
            JSONObject manifest;
            try (var input = context.getAssets().open("pet/pet.json")) { manifest = new JSONObject(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); }
            manifest.put("id", id).put("name", "安静测试角色");
            manifest.put("persona", new JSONObject().put("temperament", "calm").put("description", "使用内置猫图的虚构加载夹具，安静认真；不代表云端生成结果")
                .put("voice", new JSONObject().put("working", new JSONArray().put("{name}正在核对。").put("{name}还在认真检查。"))
                    .put("idle", new JSONArray().put("{name}：我在这里。").put("{name}：有事叫我。"))));
            JSONObject atlases = manifest.getJSONObject("atlases");
            for (var names = atlases.keys(); names.hasNext();) {
                String file = atlases.getJSONObject(names.next()).getString("file");
                try (var input = context.getAssets().open("pet/" + file); var output = new FileOutputStream(new File(directory, file))) { input.transferTo(output); }
            }
            java.nio.file.Files.write(new File(directory, "pet.json").toPath(), manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.write(new File(broken, "pet.json").toPath(), "{\"formatVersion\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            PetOverlay.select(context, id);
            DeviceUi.await("Selecting a custom package did not replace the actual sprite", 5000, () -> {
                Object sprite = overlayField("sprite"); return sprite instanceof PetSpriteView view && id.equals(view.packageId()) && !(Boolean)overlayField("entering");
            });
            assertEquals(id, preferences.getString("petId", ""));
            assertEquals(NAME, preferences.getString("petName", ""));
            tap(petBounds());
            DeviceUi.await("Selected character voice was not used by the pet", 3000,
                () -> cachedMessage().equals("Droy：我在这里。") || cachedMessage().equals("Droy：有事叫我。"));
            capture("18-custom-package-loading-fixture");
            assertThrows(Exception.class, () -> PetOverlay.select(context, brokenId));
            assertEquals("A broken candidate replaced the saved pet", id, preferences.getString("petId", ""));
            assertEquals("A broken candidate replaced the visible pet", id, ((PetSpriteView)overlayField("sprite")).packageId());
            assertEquals(NAME, preferences.getString("petName", ""));
        } finally {
            PetOverlay.select(context, "default");
            preferences.edit().putString("petName", NAME).commit();
            for (File created : new File[]{directory, broken}) {
                File[] files = created.listFiles(); if (files != null) for (File file : files) assertTrue("Could not remove fixture file", file.delete());
                assertTrue("Could not remove fixture folder", created.delete());
            }
        }
    }

    @Test public void everyEntryAndExitMovesTheActualWindowAcrossBothScreenEdges() throws Exception {
        for (boolean left : new boolean[]{false, true}) {
            for (String state : new String[]{"enterPeek", "enterWalk", "enterJump", "exitWalk", "exitHide"}) {
                boolean entry = state.startsWith("enter");
                instrumentation.runOnMainSync(() -> {
                    PetOverlay overlay = PetOverlay.get(context);
                    try {
                        invokeOverlay("stopTransition", new Class<?>[]{boolean.class}, true);
                        Field onLeft = PetOverlay.class.getDeclaredField("onLeft"); onLeft.setAccessible(true); onLeft.setBoolean(overlay, left);
                        preferences.edit().putBoolean("petLeft", left).putFloat("petX", left ? 0f : 1f).apply();
                        invokeOverlay("applySavedPosition", new Class<?>[]{});
                        invokeOverlay(entry ? "playEntry" : "playExit", new Class<?>[]{String.class}, state);
                        ((android.animation.ValueAnimator)overlayFieldOnMain("transition")).pause();
                    } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                });
                int first = sampleMotion(state, left, .03f, "early");
                int last = sampleMotion(state, left, .90f, "late");
                int direction = left == entry ? 1 : -1;
                assertTrue("The rendered window did not move in the expected direction: " + state + " left=" + left + " " + first + " -> " + last,
                    (last - first) * direction > dp(8));
                instrumentation.runOnMainSync(() -> invokeOverlay("stopTransition", new Class<?>[]{boolean.class}, true));
            }
        }
    }

    private int sampleMotion(String state, boolean left, float fraction, String stage) throws Exception {
        instrumentation.runOnMainSync(() -> ((android.animation.ValueAnimator)overlayFieldOnMain("transition")).setCurrentFraction(fraction));
        java.util.concurrent.CountDownLatch drawn = new java.util.concurrent.CountDownLatch(1);
        instrumentation.runOnMainSync(() -> android.view.Choreographer.getInstance().postFrameCallback(first ->
            android.view.Choreographer.getInstance().postFrameCallback(second -> drawn.countDown())));
        assertTrue("Motion frame was not submitted", drawn.await(3, TimeUnit.SECONDS));
        AtomicReference<int[]> actual = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            android.view.View view = (android.view.View)overlayFieldOnMain("pet");
            int[] point = new int[2]; view.getLocationOnScreen(point);
            WindowManager.LayoutParams layout = (WindowManager.LayoutParams)overlayFieldOnMain("petLayout");
            assertTrue("Entry/exit unexpectedly clamps the window to the display", (layout.flags & WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS) != 0);
            assertTrue("WMS position does not follow submitted motion: requested=" + layout.x + " actual=" + point[0], Math.abs(point[0] - layout.x) <= 3);
            actual.set(point);
        });
        capture("19-motion-" + state + "-" + (left ? "left" : "right") + "-" + stage);
        Bundle status = new Bundle(); status.putString("stream", "\nDROY_PET_MOTION " + state + " left=" + left + " fraction=" + fraction + " actualX=" + actual.get()[0] + "\n");
        instrumentation.sendStatus(2, status);
        return actual.get()[0];
    }
    private void invokeOverlay(String name, Class<?>[] types, Object... arguments) {
        try { var method = PetOverlay.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(PetOverlay.get(context), arguments); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private Object overlayFieldOnMain(String name) {
        try { Field field = PetOverlay.class.getDeclaredField(name); field.setAccessible(true); return field.get(PetOverlay.get(context)); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private String cachedMessage() { return (String) overlayField("displayedMessage"); }
    private Object overlayField(String name) {
        AtomicReference<Object> value = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                Field field = PetOverlay.class.getDeclaredField(name); field.setAccessible(true);
                value.set(field.get(PetOverlay.get(context)));
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        return value.get();
    }

    private void publish(JSONObject progress) {
        instrumentation.runOnMainSync(() -> {
            try { ToolProgress.updateFromEngine(progress); }
            catch (org.json.JSONException error) { throw new AssertionError(error); }
        });
        PetOverlay.refresh(context);
    }
    private void setEnabled(boolean enabled) {
        assertTrue(preferences.edit().putBoolean("petEnabled", enabled).putString("petExternalDisplay", enabled ? "always" : "off").putString("petInternalDisplay", enabled ? "always" : "off").commit());
        PetOverlay.refresh(context);
    }
    private void goHome() throws Exception {
        assertTrue("Android rejected the Home action", automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME));
        DeviceUi.await("Droy did not yield to the other app", 5000, () -> !activityFocused());
        automation.waitForIdle(300, 5000);
    }
    private boolean activityFocused() {
        AtomicReference<Boolean> focused = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> focused.set(activity.hasWindowFocus()));
        return focused.get();
    }
    private Rect petBounds() {
        AtomicReference<Rect> bounds = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> bounds.set(PetOverlay.get(context).petBounds()));
        return bounds.get();
    }
    private boolean petVisible() { return nativeBounds(NAME + NativeLocale.text("，Droy 宠物，点击查看任务，拖动调整位置", ", Droy pet. Tap to view the task; drag to move"), true) != null; }
    private String windowVisibilityDiagnostics() {
        StringBuilder result = new StringBuilder();
        String catDescription = NAME + NativeLocale.text("，Droy 宠物，点击查看任务，拖动调整位置", ", Droy pet. Tap to view the task; drag to move");
        result.append("retrieveInteractive=")
            .append((automation.getServiceInfo().flags & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0);
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            try {
                result.append("[id=").append(window.getId()).append(":type=").append(window.getType())
                    .append(":active=").append(window.isActive()).append(":focused=").append(window.isFocused())
                    .append(":root=").append(root != null).append(":visible=").append(root != null && root.isVisibleToUser());
                if (root != null) result.append(":rootClass=").append(root.getClassName())
                    .append(":catRootDescription=").append(catDescription.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription()))
                    .append(":catNode=").append(findNode(root, catDescription, true) != null);
                result.append("]");
            } finally { if (root != null) root.recycle(); window.recycle(); }
        }
        return result.toString();
    }
    private boolean bubbleVisible() {
        AtomicReference<String> body = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            android.widget.LinearLayout bubble = (android.widget.LinearLayout)overlayFieldOnMain("bubble");
            if (bubble == null) return;
            for (int i = 0; i < bubble.getChildCount(); i++) {
                android.view.View child = bubble.getChildAt(i);
                if (child instanceof android.widget.TextView text && !(child instanceof android.widget.Button))
                    body.set(text.getText().toString());
            }
        });
        // Android may return a null accessibility window title for a visible overlay.
        // Verify the actual rendered message node, not the optional window title.
        return body.get() != null && nativeBounds(body.get(), false) != null;
    }
    private Rect awaitText(String value) throws Exception {
        AtomicReference<Rect> found = new AtomicReference<>();
        DeviceUi.await("Pet control did not become visible: " + value, 5000, () -> {
            found.set(nativeBounds(value, false)); return found.get() != null;
        });
        return found.get();
    }
    private Rect nativeBounds(String value, boolean description) {
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            try {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) try {
                    Rect found = findNode(root, value, description); if (found != null) return found;
                } finally { root.recycle(); }
            } finally { window.recycle(); }
        }
        return null;
    }
    private Rect findNode(AccessibilityNodeInfo node, String value, boolean description) {
        CharSequence label = description ? node.getContentDescription() : node.getText();
        if (node.isVisibleToUser() && label != null && value.contentEquals(label)) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds); if (!bounds.isEmpty()) return bounds;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) try { Rect found = findNode(child, value, description); if (found != null) return found; }
            finally { child.recycle(); }
        }
        return null;
    }
    private void tap(Rect bounds) {
        assertNotNull("Missing touch target", bounds);
        assertFalse("Empty touch target", bounds.isEmpty());
        ui.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.centerY(), 48);
    }
    private Rect displayBounds() { return context.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds(); }
    private Rect safeBounds() {
        var metrics = context.getSystemService(WindowManager.class).getMaximumWindowMetrics();
        Rect bounds = metrics.getBounds();
        Insets bars = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        return new Rect(Math.max(dp(8), bars.left), Math.max(dp(8), bars.top),
            bounds.width() - Math.max(dp(8), bars.right), bounds.height() - Math.max(dp(8), bars.bottom));
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private void awaitOverlayFrameCommit() throws Exception {
        DeviceUi.await("Registered overlay did not attach for its screenshot", 3000, () -> {
            AtomicReference<Boolean> attached = new AtomicReference<>(true);
            instrumentation.runOnMainSync(() -> {
                for (String field : new String[]{"pet", "bubbleWindow", "effectWindow"}) {
                    android.view.View window = (android.view.View)overlayFieldOnMain(field);
                    if (window != null && window.getParent() != null && window.getVisibility() == android.view.View.VISIBLE
                            && window.getAlpha() > 0f && !window.isAttachedToWindow()) attached.set(false);
                }
            });
            return attached.get();
        });
        java.util.List<CompletableFuture<Void>> submitted = new java.util.ArrayList<>();
        instrumentation.runOnMainSync(() -> {
            for (String field : new String[]{"pet", "bubbleWindow", "effectWindow"}) {
                android.view.View window = (android.view.View)overlayFieldOnMain(field);
                if (window == null || !window.isAttachedToWindow() || !window.isShown() || window.getAlpha() == 0f) continue;
                CompletableFuture<Void> frame = new CompletableFuture<>(); submitted.add(frame);
                Runnable presented = () -> window.post(() -> android.view.Choreographer.getInstance().postFrameCallback(first ->
                    android.view.Choreographer.getInstance().postFrameCallback(second -> frame.complete(null))));
                if (window.isHardwareAccelerated()) window.getViewTreeObserver().registerFrameCommitCallback(presented);
                else window.postOnAnimation(presented);
                window.invalidate();
            }
        });
        CompletableFuture.allOf(submitted.toArray(new CompletableFuture<?>[0])).get(3, TimeUnit.SECONDS);
    }

    private void capture(String stage) throws Exception {
        instrumentation.waitForIdleSync();
        awaitOverlayFrameCommit();
        Bitmap image = automation.takeScreenshot();
        assertNotNull("Could not capture the pet interaction: " + stage, image);
        try {
            File external = context.getExternalFilesDir(null); assertNotNull(external);
            File directory = new File(external, "pet-ui-evidence");
            assertTrue("Could not create pet screenshot directory", directory.isDirectory() || directory.mkdirs());
            File output = new File(directory, stage + "-" + image.getWidth() + "x" + image.getHeight()
                + "-font" + Math.round(context.getResources().getConfiguration().fontScale * 100) + ".png");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                assertTrue("Could not save pet screenshot", image.compress(Bitmap.CompressFormat.PNG, 100, stream));
            }
            Bundle status = new Bundle(); status.putString("stream", "\nDROY_PET_EVIDENCE " + output.getAbsolutePath() + "\n");
            instrumentation.sendStatus(2, status);
        } finally { image.recycle(); }
    }
}
