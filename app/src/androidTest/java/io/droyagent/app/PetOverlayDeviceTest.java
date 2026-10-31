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
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final String[] PREF_KEYS = {"petEnabled", "petExternalDisplay", "petInternalDisplay", "petId", "petName", "petLeft", "petX", "petY", "themeColorScheme", "appearanceMode", "colorSource", "languagePreference", BackgroundRuntime.ENABLED, BackgroundRuntime.STOPPED};
    private Instrumentation instrumentation;
    private UiAutomation automation;
    private Context context;
    private SharedPreferences preferences;
    private android.app.Activity activity;
    private TestPageActivity darkBackdrop;
    private DeviceUi ui;
    private int originalAccessibilityFlags;
    private boolean accessibilityFlagsChanged, startedRuntime;
    private Field localeMirror;
    private Object originalLocaleMirror;
    private String originalEffectiveLanguage;
    private final Map<String, Object> originalPreferences = new HashMap<>();
    private final Map<Field, Object> originalProgress = new HashMap<>();
    private final Map<String, Long> originalControlRevisions = new HashMap<>();

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        assertTrue("Run pet tests with existing tasks and runtime stopped", RuntimeService.canManageHistory());
        assertTrue("Grant Droy the display-over-other-apps permission before this suite", Settings.canDrawOverlays(context));
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        Map<String, ?> values = preferences.getAll();
        for (String key : PREF_KEYS) if (values.containsKey(key)) originalPreferences.put(key, values.get(key));
        localeMirror = NativeLocale.class.getDeclaredField("processLanguage"); localeMirror.setAccessible(true);
        originalLocaleMirror = localeMirror.get(null);
        originalEffectiveLanguage = NativeLocale.active();
        // The fixture's synthetic progress is process-local; restore it without adding a production reset API.
        synchronized (ToolProgress.class) {
            for (String name : new String[]{"currentTask", "currentControlTask", "currentPhase", "summary", "summaryKind", "activityKind", "pending", "active", "startedAt", "activeTasks", "controlRevision"}) {
                Field field = ToolProgress.class.getDeclaredField(name); field.setAccessible(true);
                originalProgress.put(field, field.get(null));
            }
            Field revisions = ToolProgress.class.getDeclaredField("controlRevisions"); revisions.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String, Long> current = (Map<String, Long>)revisions.get(null);
            originalControlRevisions.putAll(current);
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
        startInForeground(() -> activity = instrumentation.startActivitySync(new Intent(context, NativeAppActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)));
        ui = new DeviceUi(instrumentation, null);
        DeviceUi.await("The native container did not start its actual runtime", 30000,
            () -> RuntimeService.ready && NativeRuntimeHost.inspect(context).ready);
        assertTrue("Opening Droy did not start its actual runtime", RuntimeService.hasActiveSession());
        assertTrue(preferences.edit().putBoolean("petEnabled", true).putString("petExternalDisplay", "always").putString("petInternalDisplay", "always").putString("petName", NAME)
            .putBoolean("petLeft", false).putFloat("petX", 1f).putFloat("petY", .4f)
            .putString("themeColorScheme", "light").putString("appearanceMode", "light").putString("colorSource", "preset").commit());
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
            try { closeDarkBackdrop(); }
            finally {
                if (startedRuntime) {
                    if (activity != null && !activity.isDestroyed()) instrumentation.runOnMainSync(activity::finish);
                    instrumentation.runOnMainSync(RuntimeService::requestStop);
                    DeviceUi.await("Pet test left the runtime running", 15000, RuntimeService::canManageHistory);
                    PetOverlay.runtimeStopped();
                    instrumentation.waitForIdleSync();
                }
            }
        } finally {
            try {
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
            } finally {
                if (localeMirror != null) instrumentation.runOnMainSync(() -> {
                    try { localeMirror.set(null, originalLocaleMirror); }
                    catch (IllegalAccessException failure) { throw new AssertionError(failure); }
                });
                synchronized (ToolProgress.class) {
                    for (Map.Entry<Field, Object> entry : originalProgress.entrySet()) entry.getKey().set(null, entry.getValue());
                    Field revisions = ToolProgress.class.getDeclaredField("controlRevisions"); revisions.setAccessible(true);
                    @SuppressWarnings("unchecked") Map<String, Long> current = (Map<String, Long>)revisions.get(null);
                    current.clear(); current.putAll(originalControlRevisions);
                }
                if (accessibilityFlagsChanged) {
                    AccessibilityServiceInfo service = automation.getServiceInfo();
                    service.flags = originalAccessibilityFlags; automation.setServiceInfo(service);
                }
                if (originalEffectiveLanguage != null) {
                    assertEquals("Pet tests changed the effective saved language", originalEffectiveLanguage, NativeLocale.active());
                    Bundle locale = new Bundle(); locale.putString("stream", "\nDROY_PET_LOCALE original=" + originalEffectiveLanguage
                        + " restored=" + NativeLocale.active() + " process_override=restored\n");
                    instrumentation.sendStatus(2, locale);
                }
            }
        }
    }

    @Test public void actualTouchOpensTheBubbleDragsDocksAndRestoresThePosition() throws Exception {
        assertTrue("Enable system animations for actual idle-action playback", android.animation.ValueAnimator.areAnimatorsEnabled());
        capture("01-idle-peek");
        tap(petBounds());
        DeviceUi.await("The confirmed single tap did not play an idle interaction", 2000,
            () -> !((String)overlayField("interactionState")).isEmpty());
        assertFalse("An ordinary single tap opened a task panel", bubbleVisible());
        assertFalse("A single tap did not bring the cat out of its resting pose", (Boolean)overlayField("docked"));
        String tapState = onMain(() -> ((PetSpriteView)overlayFieldOnMain("sprite")).state());
        assertTrue("Single tap did not play an available interaction: " + tapState,
            onMain(() -> ((PetSpriteView)overlayFieldOnMain("sprite")).interactions().contains(tapState)));
        capture("01-single-tap-animation-no-panel");
        singlePetTap();
        DeviceUi.await("A second deliberate idle tap did not play a different interaction", 2000,
            () -> !((String)overlayField("interactionState")).isEmpty() && !tapState.equals(overlayField("interactionState")));
        assertFalse("A deliberate idle single tap opened its status panel", bubbleVisible());
        doubleTap(petBounds());
        awaitText(overlayText(R.string.pet_overlay_no_work));
        Rect open = awaitIcon("droy-pet-open-app", R.string.pet_overlay_open_app);
        assertIconTags("droy-pet-open-app");
        Object firstBubble = overlayField("bubbleHost");
        ui.tapAt(petBounds().centerX(), petBounds().centerY());
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout() + 64L);
        assertSame("A third tap in one burst replaced or folded the status window", firstBubble, overlayField("bubbleHost"));
        assertEquals("A multi-tap burst also played a delayed single action", "", overlayField("interactionState"));
        capture("02-tap-bubble");
        tap(open);
        DeviceUi.await("The idle bubble's Open Droy icon did not return to the app", 10000, this::activityFocused);
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
        assertFalse("Dragging the pet also opened its task panel", bubbleVisible());
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
            .put("title", "选择页面").put("message", MESSAGE).put("inline", true).put("kind", "approval")
            .put("options", new JSONArray().put(new JSONObject().put("id", "page-a").put("label", "打开示例页面"))
                .put(new JSONObject().put("id", "stay").put("label", "先停在这里")));
        JSONObject progress = new JSONObject().put("taskId", "pet-device-fixture-task")
            .put("phase", "waiting_input").put("active", true).put("summary", "等待你的选择").put("pending", pending);
        publish(progress);
        instrumentation.waitForIdleSync();
        DeviceUi.await("A real pending question did not open its answer surface", 500, this::bubbleVisible);
        awaitText(MESSAGE);
        assertNotNull("The simple answer was not rendered as an accessible button", awaitText("打开示例页面"));
        assertIconTags();
        assertNull("An explicit approval was replaced with a generic Skip action", nativeBounds(overlayText(R.string.pet_overlay_leave_unanswered_description), true));
        assertNull("An explicit approval exposed a generic answer submission", nativeBounds(overlayText(R.string.pet_overlay_send_answer), false));
        instrumentation.runOnMainSync(() -> {
            android.widget.TextView reason = bubbleTextOnMain(MESSAGE);
            assertNotNull("The approval reason was lost", reason);
            assertNull("The approval reason may not be ellipsized", reason.getEllipsize());
            assertEquals("An explicit approval was converted into an ordinary question form", null, overlayFieldOnMain("questionFrame"));
        });
        capture("07-pending-light");
        SystemClock.sleep(6500);
        assertNotNull("An unanswered question disappeared on the ordinary progress timer", nativeBounds(MESSAGE, false));
        singlePetTap();
        DeviceUi.await("Tapping the cat did not let the user fold its question", 3000, () -> !bubbleVisible());
        PetOverlay.refresh(context);
        instrumentation.waitForIdleSync();
        assertFalse("An unchanged request reopened immediately after the user folded it", bubbleVisible());
        singlePetTap();
        awaitText(MESSAGE);

        assertTrue(preferences.edit().putString("appearanceMode", "dark").commit());
        instrumentation.runOnMainSync(() -> PetOverlay.get(context).preview());
        awaitText(MESSAGE);
        capture("08-pending-dark");
        AtomicReference<CompletableFuture<Void>> prepared = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> prepared.set(PetOverlay.get(context).prepare(new Rect(), true)));
        prepared.get().get(3, TimeUnit.SECONDS);
        DeviceUi.await("Capture preparation completed with pet pixels still accessible", 3000,
            () -> !petVisible() && !bubbleVisible());
        PetBubbleWindow hiddenHost = (PetBubbleWindow)overlayField("bubbleHost");
        assertNotNull("Capture discarded the pending form's Window host", hiddenHost);
        instrumentation.runOnMainSync(() -> {
            assertFalse("Capture left the actual dialog decor visible", hiddenHost.surface().isShown());
            assertEquals("Capture left an opaque decor surface", 0f, hiddenHost.surface().getAlpha(), 0f);
        });
        capture("09-capture-clearance");
        long finishedAt = SystemClock.elapsedRealtime();
        instrumentation.runOnMainSync(() -> PetOverlay.get(context).finish());
        DeviceUi.await("The pet and pending question did not promptly return after capture", 750,
            () -> petVisible() && nativeBounds(MESSAGE, false) != null);
        assertSame("Capture unnecessarily recreated the pending answer Window", hiddenHost, overlayField("bubbleHost"));
        assertTrue("Capture recovery still waits through the old hide interval", SystemClock.elapsedRealtime() - finishedAt < 850);
        assertEquals("Capture recovery lost the pending request", "pet-device-fixture-request",
            ToolProgress.current(context).getJSONObject("pending").getString("requestId"));
        capture("10-pending-restored");
        setEnabled(false);
        DeviceUi.await("The off switch left the persistent confirmation visible", 3000,
            () -> petBounds().isEmpty() && !petVisible() && !bubbleVisible());
    }

    @Test public void structuredQuestionSelectsMultipleAnswersTypesOverAnotherAppAndPreservesItsDraft() throws Exception {
        String alpaca = "cb5c4126-8c3b-41a5-a1bd-3d3b95cdeee2";
        PetOverlay.select(context, alpaca);
        DeviceUi.await("The question fixture alpaca did not finish appearing", 5000, () -> selectedSprite(alpaca));
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
        Rect firstChoice = awaitText("页面甲"), secondChoice = awaitText("页面乙");
        assertTrue("Choice rows lost their 48dp target", firstChoice.height() >= dp(48) && secondChoice.height() >= dp(48));
        assertTrue("Question choices have oversized external gaps", secondChoice.top - firstChoice.bottom <= dp(4));
        Rect available = (Rect)overlayField("safe");
        assertTrue("Structured question did not use the available phone width", bubbleBounds().width() >= available.width() - dp(16) - 1);
        assertQuestionFooter();
        assertFalse("A new question stole focus before the user chose to type", (Boolean)overlayField("editingAnswer"));
        assertTrue("A new question opened the keyboard without a touch", keyboardWindowBounds().isEmpty());
        tap(awaitText("页面甲"));
        PetQuestionDraft draft = (PetQuestionDraft)overlayField("questionDraft");
        assertStructuredSelection("after-first-option", draft, 1, null);
        tap(awaitText("页面乙"));
        assertStructuredSelection("after-both-options", draft, 2, null);
        assertEquals("?", ((android.widget.TextView)overlayField("pendingMarker")).getText().toString());
        String description = "选择要打开的页面" + NativeLocale.text("，手动填写回答", ". Write an answer");
        String submitLabel = NativeLocale.text("发送回答", "Send answer");
        AtomicReference<Rect> field = new AtomicReference<>();
        DeviceUi.await("Custom-answer field is not visible", 3000, () -> {
            field.set(nativeBounds(description, true)); return field.get() != null;
        });
        assertStructuredSelection("before-input-tap", draft, 2, field.get());
        tap(field.get());
        assertStructuredSelection("after-input-tap", draft, 2, field.get());
        DeviceUi.await("Typing did not give the answer bubble input focus", 5000, () -> (Boolean)overlayField("editingAnswer"));
        assertStructuredSelection("after-input-focus", draft, 2, field.get());
        Rect[] geometry = awaitQuestionImeGeometry(description);
        Rect actualIme = geometry[0], visibleInput = geometry[1];
        field.set(visibleInput);
        assertStructuredSelection("after-ime-visible", draft, 2, visibleInput);
        assertTrue("The actual IME did not expand beyond its transient inset", actualIme.height() >= dp(48));
        assertFalse("The focused answer input is not visible", visibleInput.isEmpty());
        assertTrue("Answer field overlaps the keyboard", visibleInput.bottom <= actualIme.top);
        assertTrue("Answer bubble overlaps the keyboard", geometry[2].bottom <= actualIme.top);
        assertQuestionFooter();
        assertStructuredSelection("before-set-native-text", draft, 2, field.get());
        boolean edited = setNativeText(description, "页面丙");
        assertStructuredSelection("after-set-native-text", draft, 2, field.get());
        assertTrue("Accessible input could not edit the answer", edited);
        DeviceUi.await("Custom answer did not reach the shared draft", 3000, () -> draft.answers.get(0).custom.equals("页面丙"));
        assertStructuredSelection("after-custom-text-observed", draft, 2, field.get());
        for (int attempt = 0; attempt < 3 && nativeBounds(submitLabel, false) == null; attempt++) {
            Rect area = bubbleBounds();
            ui.swipe(area.centerX(), area.bottom - dp(20), area.centerX(), area.top + dp(20), 300);
        }
        assertTrue("Submit action cannot be reached above the keyboard", awaitText(submitLabel).bottom <= actualIme.top);
        assertQuestionFooter();
        capture("structured-question-keyboard");
        automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        DeviceUi.await("Back did not close the IME", 5000, () -> ((Rect)overlayField("keyboard")).isEmpty());
        assertEquals("Cancelling answer entry lost the manual draft", "页面丙", draft.answers.get(0).custom);
        assertQuestionFooter();
        singlePetTap();
        DeviceUi.await("Question did not fold", 3000, () -> overlayField("bubbleWindow") == null);
        singlePetTap();
        awaitText("页面丙");
        assertSame("Folding lost the answer draft", draft, overlayField("questionDraft"));
        assertEquals(2, draft.encode().getJSONObject(0).getJSONArray("selected").length());
        assertQuestionFooter();
        capture("structured-question-restored");
        String beforeCapture = draft.encode().toString();
        PetBubbleWindow originalHost = (PetBubbleWindow)overlayField("bubbleHost");
        CompletableFuture<Void> clear = onMain(() -> PetOverlay.get(context).prepare(new Rect(), true));
        clear.get(3, TimeUnit.SECONDS);
        assertFalse("Capture left the structured form visible", bubbleVisible());
        instrumentation.runOnMainSync(() -> assertEquals("Capture retained opaque question pixels", 0f, originalHost.surface().getAlpha(), 0f));
        instrumentation.runOnMainSync(() -> PetOverlay.get(context).finish());
        DeviceUi.await("Capture did not restore the structured question", 1000, this::bubbleVisible);
        assertSame("Capture replaced the structured draft", draft, overlayField("questionDraft"));
        assertSame("Capture recreated the structured window", originalHost, overlayField("bubbleHost"));
        assertEquals("Capture changed selected or manual answers", beforeCapture, draft.encode().toString());
        assertQuestionFooter();
        pending.put("requestId", "replacement-question-fixture");
        publish(new JSONObject().put("taskId", "structured-question-task").put("phase", "waiting_input")
            .put("active", true).put("summary", "新问题").put("pending", pending));
        DeviceUi.await("A replacement question kept the previous draft", 3000, () -> overlayField("questionDraft") != draft);
        assertEquals("", ((PetQuestionDraft)overlayField("questionDraft")).answers.get(0).custom);
    }

    private Rect[] awaitQuestionImeGeometry(String description) throws Exception {
        AtomicReference<Rect[]> settled = new AtomicReference<>();
        AtomicReference<String> last = new AtomicReference<>("IME geometry not sampled");
        Rect[] previousIme = new Rect[1];
        long[] stableSince = {0};
        try {
            DeviceUi.await("The real IME, focused input and answer window did not settle above the keyboard", 10_000, () -> {
                Rect actual = keyboardWindowBounds(), observed = (Rect)overlayField("keyboard");
                Rect input = nativeBounds(description, true);
                Rect bubble = onMain(() -> {
                    WindowManager.LayoutParams layout = (WindowManager.LayoutParams)overlayFieldOnMain("bubbleLayout");
                    return layout == null ? new Rect() : new Rect(layout.x, layout.y, layout.x + layout.width, layout.y + layout.height);
                });
                boolean focused = onMain(() -> {
                    android.widget.EditText view = findQuestionInput((android.view.View)overlayFieldOnMain("bubble"));
                    return view != null && view.hasFocus() && view.isShown() && view.getGlobalVisibleRect(new Rect());
                });
                last.set("actual=" + actual.toShortString() + " observed=" + observed.toShortString()
                    + " input=" + (input == null ? "none" : input.toShortString()) + " bubble=" + bubble.toShortString() + " focused=" + focused);
                boolean ready = actual.height() >= dp(48) && observed.height() >= dp(48)
                    && Math.abs(actual.top - observed.top) <= dp(2) && Math.abs(actual.bottom - observed.bottom) <= dp(2)
                    && input != null && !input.isEmpty() && focused && bubble.contains(input)
                    && input.bottom <= actual.top && bubble.bottom <= actual.top;
                if (!ready) { previousIme[0] = null; stableSince[0] = 0; return false; }
                long now = SystemClock.uptimeMillis();
                if (!actual.equals(previousIme[0])) { previousIme[0] = new Rect(actual); stableSince[0] = now; return false; }
                if (now - stableSince[0] < 120) return false;
                settled.set(new Rect[]{new Rect(actual), new Rect(input), new Rect(bubble)});
                return true;
            });
        } catch (AssertionError failure) {
            try { capture("structured-question-ime-geometry-timeout"); }
            catch (Exception | AssertionError diagnostic) { failure.addSuppressed(diagnostic); }
            throw new AssertionError("IME/input geometry did not settle: " + last.get(), failure);
        }
        return settled.get();
    }

    private void assertStructuredSelection(String step, PetQuestionDraft expected, int count, Rect touchBounds) throws Exception {
        JSONObject state = onMain(() -> {
            PetQuestionDraft current = (PetQuestionDraft)overlayFieldOnMain("questionDraft");
            PetQuestionDraft.Answer answer = current == null || current.answers.isEmpty() ? null : current.answers.get(0);
            android.view.View bubble = (android.view.View)overlayFieldOnMain("bubble");
            android.view.View focused = bubble == null ? null : bubble.findFocus();
            android.view.View root = bubbleRootOnMain();
            android.view.View skip = root == null ? null : root.findViewWithTag("droy-pet-leave-unanswered");
            int[] choices = questionChoiceCounts(bubble);
            WindowManager.LayoutParams layout = (WindowManager.LayoutParams)overlayFieldOnMain("bubbleLayout");
            String bubbleArea = layout == null ? "none" : new Rect(layout.x, layout.y, layout.x + layout.width, layout.y + layout.height).toShortString();
            return new JSONObject().put("step", step).put("sameDraft", current == expected)
                .put("selectedCount", answer == null ? -1 : answer.selected.size())
                .put("multiple", answer != null && answer.multiple()).put("skipped", answer != null && answer.skipped)
                .put("customCharacters", answer == null ? -1 : answer.custom.length())
                .put("choiceViews", choices[0]).put("checkedViews", choices[1])
                .put("editingAnswer", overlayFieldOnMain("editingAnswer"))
                .put("focusClass", focused == null ? "none" : focused.getClass().getSimpleName())
                .put("inputBounds", questionViewBounds(findQuestionInput(bubble)))
                .put("skipBounds", questionViewBounds(skip)).put("bubbleBounds", bubbleArea)
                .put("observedIme", ((Rect)overlayFieldOnMain("keyboard")).toShortString())
                .put("requestedTouchBounds", touchBounds == null ? "none" : touchBounds.toShortString());
        });
        Bundle diagnostic = new Bundle(); diagnostic.putString("stream", "\nDROY_PET_QUESTION_STEP " + state + "\n");
        instrumentation.sendStatus(2, diagnostic);
        try {
            assertTrue("Question draft changed at " + step + ": " + state, state.getBoolean("sameDraft"));
            assertTrue("Multi-choice mode changed at " + step + ": " + state, state.getBoolean("multiple"));
            assertFalse("The question was unexpectedly skipped at " + step + ": " + state, state.getBoolean("skipped"));
            assertEquals("Typing discarded selected multi-choice answers at " + step + ": " + state,
                count, state.getInt("selectedCount"));
        } catch (AssertionError failure) {
            try { capture("structured-question-failure-" + step); }
            catch (Exception | AssertionError evidenceFailure) { failure.addSuppressed(evidenceFailure); }
            throw failure;
        }
    }
    private int[] questionChoiceCounts(android.view.View view) {
        int[] counts = {0, 0};
        if (view instanceof android.widget.CompoundButton choice) { counts[0]++; if (choice.isChecked()) counts[1]++; }
        if (view instanceof android.view.ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) {
            int[] child = questionChoiceCounts(group.getChildAt(index)); counts[0] += child[0]; counts[1] += child[1];
        }
        return counts;
    }
    private android.widget.EditText findQuestionInput(android.view.View view) {
        if (view instanceof android.widget.EditText input) return input;
        if (view instanceof android.view.ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) {
            android.widget.EditText input = findQuestionInput(group.getChildAt(index)); if (input != null) return input;
        }
        return null;
    }
    private String questionViewBounds(android.view.View view) {
        if (view == null) return "none";
        int[] location = new int[2]; view.getLocationOnScreen(location);
        return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight()).toShortString();
    }

    @Test public void stageMessagesCoalesceFastChangesAndQuestionsTakePriority() throws Exception {
        JSONObject progress = new JSONObject().put("taskId", "stage-question-fixture").put("phase", "thinking")
            .put("active", true).put("summary", "处理中").put("summaryKind", "working")
            .put("activityKind", "started").put("pending", JSONObject.NULL);
        long startedAt = SystemClock.elapsedRealtime();
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
        // Let the real 30-second ordinary-message interval expire while both
        // windows are hidden. Capture must not consume that buffered stage.
        SystemClock.sleep(Math.max(0, startedAt + 31_500 - SystemClock.elapsedRealtime()));
        assertEquals("A hidden stage was marked as shown before its window could appear", "started",
            ((PetChatterPolicy)overlayField("chatter")).current());
        instrumentation.runOnMainSync(() -> PetOverlay.get(context).finish());
        DeviceUi.await("The latest stable stage was lost during capture", 5000,
            () -> ((PetChatterPolicy)overlayField("chatter")).current().equals("controlling") && !cachedMessage().equals(started));
        assertTrue(cachedMessage().startsWith(NAME)); capture("buffered-stage-message");
        JSONObject pending = new JSONObject().put("requestId", "priority-question-fixture").put("title", "选择")
            .put("message", "现在需要你的回答").put("inline", true).put("options", new JSONArray()
                .put(new JSONObject().put("id", "continue").put("label", "继续")));
        long questionAt = SystemClock.elapsedRealtime();
        publish(progress.put("phase", "waiting_input").put("pending", pending));
        DeviceUi.await("A question waited for the ordinary chatter interval", 1000, () -> nativeBounds("现在需要你的回答", false) != null);
        assertTrue("Question priority was not immediate", SystemClock.elapsedRealtime() - questionAt < 1500);
        awaitText("现在需要你的回答"); awaitText("继续");
        capture("question-interrupts-stage");
    }

    @Test public void typedErrorsBypassChatterBudgetDeduplicateAndYieldToPendingQuestions() throws Exception {
        String task = "pet-typed-error-fixture";
        JSONObject progress = new JSONObject().put("taskId", task).put("phase", "thinking")
            .put("active", true).put("summary", NativeLocale.text("正在处理虚构任务", "Working on the fictional task."))
            .put("summaryKind", "working").put("activityKind", "started").put("pending", JSONObject.NULL);
        publish(progress);
        DeviceUi.await("The ordinary task start did not appear", 3000,
            () -> ((PetChatterPolicy)overlayField("chatter")).current().equals("started") && bubbleVisible());
        instrumentation.runOnMainSync(() -> {
            // Populate the ordinary quota using past policy observations. The real
            // 30-second window/visibility timing is covered by the stage test above.
            PetChatterPolicy policy = (PetChatterPolicy)overlayFieldOnMain("chatter");
            long now = SystemClock.elapsedRealtime(); policy.reset();
            assertEquals("started", policy.observe(task, "started", now - 65_000));
            assertEquals("", policy.observe(task, "observing", now - 64_000));
            assertEquals("observing", policy.observe(task, "observing", now - 34_000));
            assertEquals("", policy.observe(task, "controlling", now - 33_000));
            assertEquals("controlling", policy.observe(task, "controlling", now - 3_000));
        });

        String alpaca = "cb5c4126-8c3b-41a5-a1bd-3d3b95cdeee2";
        PetOverlay.select(context, alpaca);
        DeviceUi.await("Alpaca did not appear", 5000, () -> selectedSprite(alpaca));
        openPlayAction("spitScreen");
        assertEquals("The play action must be active before publishing its interrupting error", "spitScreen", overlayField("interactionState"));
        assertNotNull("The play action must have a live effect to verify cleanup", overlayField("effectWindow"));
        String firstError = NativeLocale.text("模型请求失败，正在等待重试。", "The model request failed. Waiting to retry.");
        long errorAt = SystemClock.elapsedRealtime();
        publish(progress.put("summary", firstError).put("summaryKind", "error").put("activityKind", ""));
        DeviceUi.await("A typed model error waited behind ordinary chatter", 1000, () -> nativeBounds(firstError, false) != null);
        assertTrue("The typed error was not presented promptly", SystemClock.elapsedRealtime() - errorAt < 1500);
        assertEquals("A model error did not interrupt the play action", "", overlayField("interactionState"));
        assertNull("A model error left a play effect over the task controls", overlayField("effectWindow"));
        assertEquals("An error consumed or reset the ordinary message quota", "controlling",
            ((PetChatterPolicy)overlayField("chatter")).current());
        capture("typed-error-visible-" + NativeLocale.active());
        collapseBubble();
        publish(progress);
        instrumentation.waitForIdleSync(); SystemClock.sleep(1200);
        assertFalse("An identical model error reopened after being folded", bubbleVisible());

        String replacement = NativeLocale.text("重试仍然失败，请检查模型连接。", "The retry failed. Check the model connection.");
        errorAt = SystemClock.elapsedRealtime();
        publish(progress.put("summary", replacement));
        DeviceUi.await("A changed error was suppressed as a duplicate", 1000, () -> nativeBounds(replacement, false) != null);
        assertTrue("The changed error was not presented promptly", SystemClock.elapsedRealtime() - errorAt < 1500);
        assertNull("The replacement error retained stale text", nativeBounds(firstError, false));
        publish(progress.put("summary", NativeLocale.text("已恢复处理虚构任务", "Resumed the fictional task."))
            .put("summaryKind", "working").put("activityKind", "controlling"));
        DeviceUi.await("Resuming ordinary work left the error bubble pinned", 1000, () -> !bubbleVisible());
        assertEquals("Recovering from an error renewed the ordinary quota", "controlling",
            ((PetChatterPolicy)overlayField("chatter")).current());

        String questionText = NativeLocale.text("模型连接出错后，是否继续等待？", "Keep waiting for the model connection?");
        String option = NativeLocale.text("继续等待", "Keep waiting");
        JSONObject question = new JSONObject().put("requestId", "typed-error-priority-question")
            .put("title", NativeLocale.text("需要你的选择", "Your choice is needed"))
            .put("message", questionText).put("inline", true).put("options", new JSONArray()
                .put(new JSONObject().put("id", "wait").put("label", option)));
        publish(progress.put("phase", "waiting_input").put("summary", replacement).put("summaryKind", "error")
            .put("activityKind", "").put("pending", question));
        DeviceUi.await("The pending question did not take priority over the error", 1000, () -> nativeBounds(questionText, false) != null);
        publish(new JSONObject().put("taskId", "another-typed-error-fixture").put("phase", "thinking")
            .put("active", true).put("summary", firstError).put("summaryKind", "error").put("pending", JSONObject.NULL));
        awaitText(questionText); awaitText(option);
        assertNull("A separate task's error displaced the pending question", nativeBounds(firstError, false));
        assertEquals("typed-error-priority-question", ToolProgress.current(context).getJSONObject("pending").getString("requestId"));
        capture("typed-error-question-priority-" + NativeLocale.active());
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
        assertIconTags("droy-pet-open-full-task");
        tap(awaitIcon("droy-pet-open-full-task", R.string.pet_overlay_open_full_task));
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
        assertIconTags();
        singlePetTap();
        assertTaskControls(message, false, true, true);
        capture("slow-model-stop");
        collapseBubble();
        publish(slow); instrumentation.waitForIdleSync();
        assertFalse("The same slow request reopened after a duplicate event", bubbleVisible());
        publish(new JSONObject().put("taskId", "pet-slow-fixture").put("phase", "executing")
            .put("active", true).put("summary", "正在执行操作").put("pending", JSONObject.NULL));
        singlePetTap(); awaitText("正在执行操作");
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
        assertTrue("Ordinary bubble is wider than its compact limit", compact.width() <= dp(172) + 1);
        assertTrue("Ordinary progress wastes vertical space", compact.height() <= dp(100));
        assertTrue("Bubble text escaped its window", compact.contains(text));
        assertIconTags();
        Rect cat = petBounds();
        assertTrue("Speech bubble is detached from the cat", Math.abs(compact.right - cat.left) <= 2
            || Math.abs(compact.left - cat.right) <= 2 || Math.abs(compact.bottom - cat.top) <= 2
            || Math.abs(compact.top - cat.bottom) <= 2);
        capture("11-compact-progress");
        tap(text);
        assertTaskControls(message, false, true, true);
        capture("12-expanded-progress");
        collapseBubble();
        publish(new JSONObject().put("taskId", "pet-device-compact-task").put("phase", "executing")
            .put("active", true).put("summary", "正在查看当前页面").put("pending", JSONObject.NULL));
        instrumentation.waitForIdleSync();
        assertFalse("A subsequent progress update reopened the notification", bubbleVisible());
        singlePetTap();
        awaitText("正在查看当前页面");
        DeviceUi.await("Recoverable progress stayed pinned like a blocker", 8000, () -> !bubbleVisible());
    }

    @Test public void englishCompactGlassAndPanelActionsFitWithoutShrinkingTheirTouchTargets() throws Exception {
        instrumentation.runOnMainSync(() -> NativeLocale.mirrorProcessLanguage("en"));
        assertEquals("en", NativeLocale.active());
        String message = "Checking the fictional page and keeping this task's progress concise while the work continues.";
        publish(new JSONObject().put("taskId", "english-glass-fixture").put("phase", "executing")
            .put("active", true).put("summary", message).put("pending", JSONObject.NULL));
        assertTrue(PetOverlay.notify(context, "english-glass-fixture", "english-glass-notice", message));
        awaitText(message);
        assertTrue("The compact English bubble exceeded 172dp", bubbleBounds().width() <= dp(172) + 1);
        instrumentation.runOnMainSync(() -> {
            android.widget.TextView content = bubbleTextOnMain(message);
            assertNotNull(content);
            assertEquals(2, content.getMaxLines());
            assertTextSize(content, 12);
        });
        assertIconTags();
        capture("fog-compact-english");
        singlePetTap();
        assertTaskControls(message, false, true, true);
        awaitOverlayFrameCommit();
        instrumentation.runOnMainSync(() -> assertTextSize(bubbleTextOnMain(message), 14));
        assertFogTint();
        PetBubbleColors light = (PetBubbleColors)overlayField("bubbleColors");
        assertEquals("The fog surface is not neutral white", 0xFFFFFFFF, light.surface);
        capture("fog-panel-english-light");
        assertTrue(preferences.edit().putString("appearanceMode", "dark").commit());
        PetOverlay.refresh(context);
        assertTaskControls(message, false, true, true);
        PetBubbleColors dark = PetBubbleColors.from(context);
        assertEquals("Saved dark mode changed the overlay tint", light.surface, dark.surface);
        assertEquals("Saved dark mode changed the overlay text", light.onSurface, dark.onSurface);
        assertEquals("Saved dark mode changed the overlay secondary text", light.onSurfaceVariant, dark.onSurfaceVariant);
        assertEquals("Saved dark mode changed the overlay outline", light.outline, dark.outline);
        assertEquals("Saved dark mode changed the overlay task icons", light.primary, dark.primary);
        assertEquals("Saved dark mode changed the overlay stop icon", light.error, dark.error);
        assertFogTint();
        capture("fog-panel-english-dark");
    }

    @Test public void taskControlIconsTrackPausedAndTransitionSnapshotsWithoutClaimingEngineExecution() throws Exception {
        String locale = InstrumentationRegistry.getArguments().getString("petFixtureLocale", "en");
        assertTrue("Pet fixture locale must be en or zh", Set.of("en", "zh").contains(locale));
        instrumentation.runOnMainSync(() -> NativeLocale.mirrorProcessLanguage(locale));
        String alpaca = "cb5c4126-8c3b-41a5-a1bd-3d3b95cdeee2";
        PetOverlay.select(context, alpaca);
        DeviceUi.await("The selected alpaca did not finish appearing", 5000, () ->
            alpaca.equals(((PetSpriteView)overlayField("sprite")).packageId()) && !(Boolean)overlayField("entering"));
        String work = NativeLocale.text("正在对比商品信息", "Checking a fictional page");
        JSONObject progress = new JSONObject().put("taskId", "pet-control-presentation-fixture")
            .put("controlTaskId", "pet-control-owner-fixture")
            .put("phase", "executing").put("active", true).put("summary", work)
            .put("controlRevision", 40).put("pending", JSONObject.NULL);
        publish(progress);
        assertTrue(PetOverlay.notify(context, "pet-control-presentation-fixture", "pet-control-presentation-hint-" + locale, work));
        awaitText(work);
        capture("fog-control-auto-hint-" + locale);
        singlePetTap();
        assertTaskControls(work, false, true, true);
        capture("fog-control-working");
        String[] phases = {"pausing", "paused", "resuming", "stopping"};
        int[] descriptions = {R.string.pet_overlay_pausing, R.string.pet_overlay_paused, R.string.pet_overlay_resuming, R.string.pet_overlay_stopping};
        for (int i = 0; i < phases.length; i++) {
            publish(progress.put("phase", phases[i]).put("active", true).put("controlRevision", 41 + i));
            String summary = overlayText(descriptions[i]);
            awaitText(summary);
            boolean resume = phases[i].equals("paused") || phases[i].equals("resuming");
            assertTaskControls(summary, resume, phases[i].equals("paused"), !phases[i].equals("stopping"));
            if (phases[i].equals("paused")) {
                DeviceUi.await("The paused message kept covering the foreground app", 9000, () -> !bubbleVisible());
                JSONObject retained = ToolProgress.current(context);
                assertTrue("Hiding the paused bubble ended the logical task", retained.getBoolean("active"));
                assertEquals("Hiding the paused bubble resumed the task", "paused", retained.getString("phase"));
                assertEquals("Hiding the paused bubble changed its controller revision", 42, retained.getLong("controlRevision"));
                singlePetTap();
                assertTaskControls(summary, true, true, true);
            }
            capture("fog-control-" + phases[i]);
        }
        String failure = NativeLocale.text("尚未确认操作已停止，仍可结束当前任务。",
            "Could not confirm cancellation. You can still stop this task.");
        publish(progress.put("phase", "pausing").put("summaryKind", "error").put("summary", failure).put("controlRevision", 45));
        assertTaskControls(failure, false, false, true);
        publish(progress.put("phase", "stopped").put("active", false).put("summaryKind", "").put("controlRevision", 46));
        awaitText(overlayText(R.string.pet_overlay_no_work));
        awaitIcon("droy-pet-open-app", R.string.pet_overlay_open_app);
        assertIconTags("droy-pet-open-app");
        assertEquals("The last presentation snapshot lost its control revision", 46, ToolProgress.current(context).getLong("controlRevision"));
        assertEquals("Task controls lost their distinct controller identity", "pet-control-owner-fixture", ToolProgress.current(context).getString("controlTaskId"));
        assertFalse("Presentation snapshots must not start a phone-operation session", ToolProgress.screenOperationActive());
        capture("fog-control-idle-" + locale);
    }

    private void assertFogTint() throws Exception {
        instrumentation.runOnMainSync(() -> {
            PetBubbleDrawable drawable = (PetBubbleDrawable)overlayFieldOnMain("bubbleBackground");
            assertNotNull("The actual bubble has no fog drawable", drawable);
            PetBubbleWindow host = (PetBubbleWindow)overlayFieldOnMain("bubbleHost");
            WindowManager.LayoutParams attributes = (WindowManager.LayoutParams)host.surface().getLayoutParams();
            assertTrue("The fog overlay dimmed the entire background app", (attributes.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) == 0 || attributes.dimAmount == 0f);
            Rect bounds = drawable.getBounds();
            assertFalse("The actual fog drawable has no measured bounds", bounds.isEmpty());
            Bitmap sample = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888);
            try {
                drawable.draw(new android.graphics.Canvas(sample));
                Field blur = PetBubbleDrawable.class.getDeclaredField("blurAvailable"); blur.setAccessible(true);
                Field fade = PetBubbleDrawable.class.getDeclaredField("alpha"); fade.setAccessible(true);
                Field radius = PetBubbleWindow.class.getDeclaredField("blurRadius"); radius.setAccessible(true);
                assertEquals("The actual blur host lost its 8dp local blur radius", Math.min(100, dp(8)), radius.getInt(host));
                int pixel = sample.getPixel(sample.getWidth() / 2, sample.getHeight() / 2);
                int expected = fade.getInt(drawable) * (blur.getBoolean(drawable) ? 85 : 112) / 255;
                assertEquals("The real bubble tint lost its high transmission", expected, android.graphics.Color.alpha(pixel), 1);
                assertEquals("The real bubble tint is not neutral", 255, android.graphics.Color.red(pixel));
                assertEquals(255, android.graphics.Color.green(pixel));
                assertEquals(255, android.graphics.Color.blue(pixel));
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            finally { sample.recycle(); }
        });
    }

    private android.widget.TextView bubbleTextOnMain(String text) {
        return findTextView((android.view.View)overlayFieldOnMain("bubble"), text);
    }
    private android.widget.TextView findTextView(android.view.View view, String text) {
        if (view instanceof android.widget.TextView label && text.contentEquals(label.getText())) return label;
        if (view instanceof android.view.ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) {
            android.widget.TextView found = findTextView(group.getChildAt(index), text);
            if (found != null) return found;
        }
        return null;
    }
    private void assertTextSize(android.widget.TextView view, int sp) {
        assertNotNull("The expected text view is missing", view);
        float expected = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sp, context.getResources().getDisplayMetrics());
        assertEquals("Text size does not match the compact overlay hierarchy", expected, view.getTextSize(), .1f);
    }

    @Test public void newlyRegisteredWindowsCanBeClosedBeforeTheirFirstFrame() throws Exception {
        instrumentation.runOnMainSync(() -> {
            invokeOverlay("close", new Class<?>[]{});
            invokeOverlay("refreshNow", new Class<?>[]{});
            invokeOverlay("showBubble", new Class<?>[]{boolean.class}, true);
            android.view.View pet = (android.view.View)overlayFieldOnMain("pet");
            android.view.View bubble = ((PetBubbleWindow)overlayFieldOnMain("bubbleHost")).surface();
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
        doubleTap(petBounds());
        DeviceUi.await("The message window did not appear", 3000, this::bubbleVisible);
        instrumentation.runOnMainSync(() -> {
            WindowManager manager = (WindowManager)overlayFieldOnMain("windows");
            manager.removeViewImmediate(((PetBubbleWindow)overlayFieldOnMain("bubbleHost")).surface());
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
        doubleTap(petBounds());
        awaitText(message);
        capture("wait-01-visible");
        SystemClock.sleep(6500);
        assertNotNull("The explicit wait disappeared on the normal six-second timer", nativeBounds(message, false));
        assertTaskControls(message, false, true, true);
        capture("wait-02-expanded");
        collapseBubble();
        PetOverlay.refresh(context); instrumentation.waitForIdleSync();
        assertFalse("An unchanged wait reopened after being folded", bubbleVisible());
        singlePetTap();
        awaitText(message);
        publish(progress.put("phase", "executing").put("summary", "已到时间，正在领取优惠券"));
        awaitText("已到时间，正在领取优惠券");
        assertNull("Resuming work left the waiting message on screen", nativeBounds(message, false));
        capture("wait-03-resumed");
        DeviceUi.await("Resumed work retained a persistent waiting bubble", 8000, () -> !bubbleVisible());
        publish(progress.put("phase", "waiting").put("summary", message));
        singlePetTap();
        awaitText(message);
        publish(progress.put("phase", "interrupted").put("active", false).put("summary", "本轮执行已停止，点我查看任务"));
        awaitText(overlayText(R.string.pet_overlay_no_work));
        awaitIcon("droy-pet-open-app", R.string.pet_overlay_open_app);
        assertIconTags("droy-pet-open-app");
        assertNull("Cancellation left stale waiting text", nativeBounds(message, false));
        capture("wait-04-cancelled");
    }

    @Test public void workingSummaryStaysStableAcrossPhasesAndUnrelatedInputDoesNotHideThePet() throws Exception {
        String message = "正在核对虚构页面";
        JSONObject progress = new JSONObject().put("taskId", "pet-device-working-task").put("phase", "thinking")
            .put("active", true).put("summary", message).put("summaryKind", "working").put("pending", JSONObject.NULL);
        publish(progress);
        instrumentation.waitForIdleSync();
        assertFalse("Working status opened an unsolicited bubble", bubbleVisible());
        singlePetTap();
        awaitText(message);
        assertTaskControls(message, false, true, true);
        Object firstBubble = overlayField("bubble");
        for (String phase : new String[]{"executing", "verifying", "thinking"}) publish(progress.put("phase", phase));
        SystemClock.sleep(5100);
        assertEquals("Mechanical phases changed the concrete work summary", message, cachedMessage());
        assertSame("Mechanical phases rebuilt the existing working bubble", firstBubble, overlayField("bubble"));
        assertNotNull("Stable working summary vanished before its normal timeout", nativeBounds(message, false));
        AtomicReference<CompletableFuture<Void>> prepared = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> prepared.set(PetOverlay.get(context).prepare(new Rect(0, 0, 1, 1), false)));
        prepared.get().get(3, TimeUnit.SECONDS);
        assertTrue("An unrelated input hid the cat", petVisible());
        assertSame("An unrelated input rebuilt the bubble", firstBubble, overlayField("bubble"));
        instrumentation.runOnMainSync(() -> PetOverlay.get(context).finish());
        assertTrue("Finishing an unrelated input hid the cat", petVisible());
        assertTaskControls(message, false, true, true);
        assertEquals("Task controls changed the current summary", message, cachedMessage());
        capture("13-stable-working-summary");
        collapseBubble();
        singlePetTap();
        awaitText(message);
        assertEquals("Reopening invented a new task summary", message, cachedMessage());
        assertTaskControls(message, false, true, true);
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
        resumeNativeActivity("Droy did not resume for preview");
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
            resumeNativeActivity("Droy did not resume for permission isolation");
            shellOutput("appops set " + context.getPackageName() + " SYSTEM_ALERT_WINDOW ignore");
            DeviceUi.await("Test did not actually revoke overlay permission", 3000, () -> !Settings.canDrawOverlays(context));
            assertTrue(preferences.edit().putString("petExternalDisplay", "always").putString("petInternalDisplay", "always").commit());
            PetOverlay.refresh(context);
            DeviceUi.await("Internal always display incorrectly requires an overlay grant", 3000, this::petVisible);
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION, ((WindowManager.LayoutParams)overlayField("petLayout")).type);
            capture("20-internal-always-with-overlay-permission-revoked");
            goHome();
            DeviceUi.await("External pet appeared without its required overlay permission", 3000, () -> petBounds().isEmpty() && !petVisible());
            resumeNativeActivity("Droy did not resume for isolated preview");
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

    @Test public void builtinSpecialActionsDrawTheirActualFramesAndRestoreTheTask() throws Exception {
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
        // Select a deterministic animation for actual window/frame cleanup checks.
        // The public idle-tap gesture and its non-repetition are exercised separately.
        instrumentation.runOnMainSync(() -> invokeOverlay("playInteraction", new Class<?>[]{String.class}, state));
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
        instrumentation.runOnMainSync(() -> NativeLocale.mirrorProcessLanguage("zh"));
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
            publish(new JSONObject().put("taskId", "custom-persona-voice-fixture").put("phase", "executing")
                .put("active", true).put("summary", "正在检查虚构内容").put("summaryKind", "working")
                .put("activityKind", "working").put("pending", JSONObject.NULL));
            DeviceUi.await("Selected character voice was not used by the pet", 3000,
                () -> cachedMessage().equals("Droy正在核对。") || cachedMessage().equals("Droy还在认真检查。"));
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

    private String cachedMessage() { return (String) overlayField("bubbleContent"); }
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
            try {
                if (!progress.has("controlRevision")) progress.put("controlRevision", 0);
                ToolProgress.updateFromEngine(progress);
            }
            catch (org.json.JSONException error) { throw new AssertionError(error); }
        });
        PetOverlay.refresh(context);
    }
    private void setEnabled(boolean enabled) {
        assertTrue(preferences.edit().putBoolean("petEnabled", enabled).putString("petExternalDisplay", enabled ? "always" : "off").putString("petInternalDisplay", enabled ? "always" : "off").commit());
        PetOverlay.refresh(context);
    }
    private void startInForeground(Runnable launch) {
        // HyperOS can reject a fixture launch while another app is focused.
        // Scope this identity to the actual launch call, including later previews.
        automation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND");
        try { launch.run(); }
        finally {
            automation.dropShellPermissionIdentity();
            Bundle status = new Bundle(); status.putString("stream", "\nDROY_PET_LAUNCH temporary_background_launch_identity=dropped\n");
            instrumentation.sendStatus(2, status);
        }
    }
    private void resumeNativeActivity(String failureMessage) throws Exception {
        startInForeground(() -> context.startActivity(new Intent(context, NativeAppActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
        DeviceUi.await(failureMessage, 8000, () -> {
            AtomicReference<Boolean> resumed = new AtomicReference<>(false);
            instrumentation.runOnMainSync(() -> {
                java.lang.ref.WeakReference<?> foreground = (java.lang.ref.WeakReference<?>)overlayFieldOnMain("foregroundActivity");
                resumed.set(activity.hasWindowFocus() && (Boolean)overlayFieldOnMain("appVisible") && foreground.get() == activity);
            });
            return resumed.get();
        });
    }
    private void goHome() throws Exception {
        assertTrue("Android rejected the Home action", automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME));
        DeviceUi.await("Droy did not yield to the other app", 5000, () -> !activityFocused());
        automation.waitForIdle(300, 5000);
        if ("true".equals(InstrumentationRegistry.getArguments().getString("petDarkBackdrop"))) showDarkBackdrop();
    }
    private void showDarkBackdrop() throws Exception {
        closeDarkBackdrop();
        startInForeground(() -> darkBackdrop = (TestPageActivity)instrumentation.startActivitySync(new Intent(context, TestPageActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)));
        instrumentation.runOnMainSync(() -> {
            android.view.View background = new android.view.View(darkBackdrop);
            background.setBackgroundColor(0xFF121212);
            darkBackdrop.setContentView(background);
            darkBackdrop.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF121212));
            darkBackdrop.getWindow().setStatusBarColor(0xFF121212);
            darkBackdrop.getWindow().setNavigationBarColor(0xFF121212);
            darkBackdrop.getWindow().getDecorView().setSystemUiVisibility(0);
            // TestPage also receives the app's lifecycle callback. Exclude this
            // owned backdrop from internal hosting so QA still uses real overlays.
            PetOverlay.foreground(darkBackdrop, false);
        });
        DeviceUi.await("The dark backdrop did not retain the external pet window", 5000, () -> onMain(() -> {
            WindowManager.LayoutParams pet = (WindowManager.LayoutParams)overlayFieldOnMain("petLayout");
            return darkBackdrop.hasWindowFocus() && !(Boolean)overlayFieldOnMain("appVisible")
                && !(Boolean)overlayFieldOnMain("internalWindow") && pet != null
                && pet.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }));
        automation.waitForIdle(300, 5000);
    }
    private void closeDarkBackdrop() {
        if (darkBackdrop != null && !darkBackdrop.isDestroyed()) instrumentation.runOnMainSync(darkBackdrop::finishAndRemoveTask);
        darkBackdrop = null;
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
    private String petDescription() {
        JSONObject progress = ToolProgress.current(context);
        return overlayText(progress.optJSONObject("pending") != null ? R.string.pet_overlay_question_description
            : progress.optBoolean("active") || Set.of("pausing", "paused", "resuming", "stopping").contains(progress.optString("phase"))
                ? R.string.pet_overlay_working_description : R.string.pet_overlay_idle_description);
    }
    private boolean petVisible() { return nativeBounds(petDescription(), true) != null; }
    private String windowVisibilityDiagnostics() {
        StringBuilder result = new StringBuilder();
        String catDescription = petDescription();
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
        AtomicReference<Boolean> visible = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> {
            PetBubbleWindow host = (PetBubbleWindow)overlayFieldOnMain("bubbleHost");
            android.view.View surface = host == null ? null : host.surface();
            visible.set(surface != null && surface.isAttachedToWindow() && surface.isShown() && surface.getAlpha() > 0f
                && surface.getGlobalVisibleRect(new Rect()));
        });
        return visible.get();
    }

    private String overlayText(int resource) {
        android.content.res.Configuration configuration = new android.content.res.Configuration(context.getResources().getConfiguration());
        configuration.setLocale(java.util.Locale.forLanguageTag(NativeLocale.active()));
        return context.createConfigurationContext(configuration).getString(resource);
    }
    private android.view.View bubbleRootOnMain() {
        android.view.View question = (android.view.View)overlayFieldOnMain("questionFrame");
        return question == null ? (android.view.View)overlayFieldOnMain("bubble") : question;
    }
    private Rect awaitIcon(String tag, int description) throws Exception {
        String label = overlayText(description);
        AtomicReference<Rect> result = new AtomicReference<>();
        DeviceUi.await("The accessible icon did not become visible: " + label, 5000, () -> {
            result.set(nativeBounds(label, true)); return result.get() != null;
        });
        Rect bounds = result.get();
        assertTrue("The icon target is narrower than 48dp: " + label, bounds.width() >= dp(48));
        assertTrue("The icon target is shorter than 48dp: " + label, bounds.height() >= dp(48));
        assertTrue("The icon escaped its actual window: " + label, bubbleBounds().contains(bounds));
        instrumentation.runOnMainSync(() -> {
            android.view.View root = bubbleRootOnMain();
            android.view.View view = root == null ? null : root.findViewWithTag(tag);
            assertTrue("Task control is not an ImageButton", view instanceof android.widget.ImageButton);
            assertEquals(label, view.getContentDescription());
            assertTrue("Task icon is not accessible as a click target", view.isClickable());
            android.widget.ImageButton icon = (android.widget.ImageButton)view;
            assertEquals("Icon glyph width is not 24dp", dp(24), icon.getWidth() - icon.getPaddingLeft() - icon.getPaddingRight());
            assertEquals("Icon glyph height is not 24dp", dp(24), icon.getHeight() - icon.getPaddingTop() - icon.getPaddingBottom());
        });
        return bounds;
    }
    private void assertIconTags(String... expected) {
        instrumentation.runOnMainSync(() -> {
            List<String> actual = new ArrayList<>();
            collectIconTags(bubbleRootOnMain(), actual);
            assertEquals("The overlay exposed an extra or missing icon action", Set.of(expected), Set.copyOf(actual));
            assertEquals("The overlay duplicated an icon action", expected.length, actual.size());
        });
    }
    private void assertTaskControls(String message, boolean paused, boolean primaryEnabled, boolean stopEnabled) throws Exception {
        String primaryTag = paused ? "droy-pet-resume" : "droy-pet-pause";
        Rect primary = awaitIcon(primaryTag, paused ? R.string.pet_overlay_resume : R.string.pet_overlay_pause);
        Rect stop = awaitIcon("droy-pet-stop", R.string.pet_overlay_stop);
        Rect content = awaitText(message);
        assertTrue("Task icons were placed beside the summary instead of below it", primary.top >= content.bottom && stop.top >= content.bottom);
        assertEquals("Task icons escaped their shared action row", primary.top, stop.top, dp(1));
        assertTrue("Task icon touch targets overlap", primary.right <= stop.left);
        assertTrue("Working message exceeded its 250dp limit", bubbleBounds().width() <= dp(250) + 1);
        assertIconTags(primaryTag, "droy-pet-stop");
        instrumentation.runOnMainSync(() -> {
            android.view.View root = bubbleRootOnMain();
            assertEquals("Primary task-control enabled state is stale", primaryEnabled, root.findViewWithTag(primaryTag).isEnabled());
            assertEquals("Stop enabled state is stale", stopEnabled, root.findViewWithTag("droy-pet-stop").isEnabled());
        });
    }
    private void assertQuestionFooter() throws Exception {
        AtomicReference<Rect[]> settled = new AtomicReference<>();
        AtomicReference<String> last = new AtomicReference<>("Question footer geometry not sampled");
        try {
            DeviceUi.await("The question footer did not settle within its actual window", 3000, () -> {
                Rect[] nodes = new Rect[2];
                List<AccessibilityWindowInfo> windows = automation.getWindows();
                try {
                    for (AccessibilityWindowInfo window : windows) {
                        AccessibilityNodeInfo root = window.getRoot();
                        if (root == null) continue;
                        try {
                            Rect skip = findNode(root, overlayText(R.string.pet_overlay_leave_unanswered_description), true);
                            Rect send = findNode(root, overlayText(R.string.pet_overlay_send_answer), false);
                            if (skip != null || send != null) { nodes[0] = skip; nodes[1] = send; }
                            if (skip != null && send != null) break;
                        } finally { root.recycle(); }
                    }
                } finally { for (AccessibilityWindowInfo window : windows) window.recycle(); }
                Rect[] frame = onMain(() -> {
                    WindowManager.LayoutParams layout = (WindowManager.LayoutParams)overlayFieldOnMain("bubbleLayout");
                    Rect requested = layout == null ? new Rect() : new Rect(layout.x, layout.y, layout.x + layout.width, layout.y + layout.height);
                    PetBubbleWindow host = (PetBubbleWindow)overlayFieldOnMain("bubbleHost");
                    android.view.View surface = host == null ? null : host.surface();
                    Rect actual = new Rect();
                    if (surface != null && surface.isAttachedToWindow() && surface.isShown() && !surface.isLayoutRequested()) {
                        int[] location = new int[2]; surface.getLocationOnScreen(location);
                        actual.set(location[0], location[1], location[0] + surface.getWidth(), location[1] + surface.getHeight());
                    }
                    return new Rect[]{requested, actual};
                });
                last.set("skip=" + (nodes[0] == null ? "none" : nodes[0].toShortString())
                    + " send=" + (nodes[1] == null ? "none" : nodes[1].toShortString())
                    + " requested=" + frame[0].toShortString() + " actual=" + frame[1].toShortString());
                if (nodes[0] == null || nodes[1] == null || frame[1].isEmpty() || !frame[0].equals(frame[1])
                        || Math.abs(nodes[0].top - nodes[1].top) > dp(1)
                        || !frame[1].contains(nodes[0]) || !frame[1].contains(nodes[1])) return false;
                settled.set(new Rect[]{new Rect(nodes[0]), new Rect(nodes[1]), new Rect(frame[1])});
                return true;
            });
        } catch (AssertionError failure) {
            try { capture("structured-question-footer-geometry-timeout"); }
            catch (Exception | AssertionError diagnostic) { failure.addSuppressed(diagnostic); }
            throw new AssertionError("Question footer geometry did not settle: " + last.get(), failure);
        }
        Rect skip = settled.get()[0], send = settled.get()[1], window = settled.get()[2];
        assertNotNull("The specific unanswered action is not accessible", skip);
        assertNotNull("The send-answer action is not accessible", send);
        assertTrue("Skip lost its 48dp target", skip.width() >= dp(48) && skip.height() >= dp(48));
        assertTrue("Send lost its 48dp target", send.width() >= dp(48) && send.height() >= dp(48));
        assertEquals("Question footer actions are not in one compact row", skip.top, send.top, dp(1));
        assertTrue("Question footer actions overlap", skip.right <= send.left);
        assertTrue("Skip escaped its actual question window", window.contains(skip));
        assertTrue("Send escaped its actual question window", window.contains(send));
        assertIconTags();
        assertNull("A question exposed an unrelated task menu", nativeBounds(NativeLocale.text("互动动作", "Play actions"), false));
        assertNull("A question exposed an unrelated Open Droy action", nativeBounds(overlayText(R.string.pet_overlay_open_app), true));
        assertNull("A question exposed a Stop action", nativeBounds(overlayText(R.string.pet_overlay_stop), true));
        instrumentation.runOnMainSync(() -> {
            android.view.View root = bubbleRootOnMain();
            assertNotNull("Structured questions lost their fixed frame", overlayFieldOnMain("questionFrame"));
            android.widget.LinearLayout actions = (android.widget.LinearLayout)overlayFieldOnMain("questionActions");
            assertEquals(android.widget.LinearLayout.HORIZONTAL, actions.getOrientation());
            assertSame("The answer footer must be outside the scrolling body", root, actions.getParent());
            assertSame(root, ((android.view.View)overlayFieldOnMain("bubbleWindow")).getParent());
        });
    }
    private void collectIconTags(android.view.View view, List<String> tags) {
        if (view instanceof android.widget.ImageButton) tags.add(String.valueOf(view.getTag()));
        if (view instanceof android.view.ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) collectIconTags(group.getChildAt(i), tags);
    }
    private void collapseBubble() throws Exception {
        instrumentation.runOnMainSync(() -> invokeOverlay("collapse", new Class<?>[]{}));
        DeviceUi.await("The message window did not collapse", 3000, () -> !bubbleVisible());
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
    private void doubleTap(Rect bounds) {
        assertNotNull("Missing double-tap target", bounds);
        assertFalse("Empty double-tap target", bounds.isEmpty());
        // Separate intentional gestures, then inject two trusted taps at the
        // same screen coordinate inside Android's standard double-tap interval.
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout() + 32L);
        ui.tapAt(bounds.centerX(), bounds.centerY());
        SystemClock.sleep(90);
        ui.tapAt(bounds.centerX(), bounds.centerY());
    }
    private void singlePetTap() {
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout() + 32L);
        tap(petBounds());
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
                    android.view.View window = overlaySurfaceOnMain(field);
                    if (window != null && window.getParent() != null && window.getVisibility() == android.view.View.VISIBLE
                            && window.getAlpha() > 0f && !window.isAttachedToWindow()) attached.set(false);
                }
            });
            return attached.get();
        });
        java.util.List<CompletableFuture<Void>> submitted = new java.util.ArrayList<>();
        instrumentation.runOnMainSync(() -> {
            for (String field : new String[]{"pet", "bubbleWindow", "effectWindow"}) {
                android.view.View window = overlaySurfaceOnMain(field);
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

    private android.view.View overlaySurfaceOnMain(String field) {
        if (field.equals("bubbleWindow")) {
            PetBubbleWindow host = (PetBubbleWindow)overlayFieldOnMain("bubbleHost");
            return host == null ? null : host.surface();
        }
        return (android.view.View)overlayFieldOnMain(field);
    }

    private void capture(String stage) throws Exception {
        instrumentation.waitForIdleSync();
        awaitOverlayFrameCommit();
        if (darkBackdrop != null) instrumentation.runOnMainSync(() -> {
            assertFalse("The dark QA backdrop switched the pet to internal hosting", (Boolean)overlayFieldOnMain("internalWindow"));
            for (String field : new String[]{"petLayout", "bubbleLayout"}) {
                WindowManager.LayoutParams layout = (WindowManager.LayoutParams)overlayFieldOnMain(field);
                if (layout != null) assertEquals("The dark QA backdrop must use real external windows", WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, layout.type);
            }
        });
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
