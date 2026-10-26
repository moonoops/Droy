package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.DroyAccessibilityService;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Reuses an explicitly selected existing conversation without submitting a model turn.
 * Existing event bytes must remain intact; DSH may append only its empty session/end-seed recovery marker.
 */
@RunWith(AndroidJUnit4.class)
public final class PetCompletionDeviceTest {
    private static final String[] PREF_KEYS = {"petEnabled", "petExternalDisplay", "petInternalDisplay", "returnToDroyOnCompletion"};
    private final Map<String, Object> originalPreferences = new HashMap<>();
    private final Map<Field, Object> originalProgress = new HashMap<>();
    private Instrumentation instrumentation;
    private UiAutomation automation;
    private Context context;
    private SharedPreferences preferences;
    private MainActivity activity;
    private DeviceUi ui;
    private String taskId;
    private boolean preferencesOwned, startedRuntime;
    private Path sessionDirectory;
    private Map<String, byte[]> originalHistory;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        taskId = InstrumentationRegistry.getArguments().getString("completionTaskId", "").trim();
        assertTrue("Supply an existing session with -e completionTaskId; this test never creates one",
            !taskId.isEmpty() && taskId.length() <= 256);
        assertTrue("Stop existing tasks and the runtime before completion tests", RuntimeService.canManageHistory());
        sessionDirectory = context.getFilesDir().toPath().resolve("dsh-home/sessions");
        originalHistory = historySnapshot();
        assertFalse("An existing conversation history is required", originalHistory.isEmpty());
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        Map<String, ?> saved = preferences.getAll();
        for (String key : PREF_KEYS) if (saved.containsKey(key)) originalPreferences.put(key, saved.get(key));
        preferencesOwned = true;
        synchronized (ToolProgress.class) {
            for (String name : new String[]{"currentTask", "currentPhase", "summary", "summaryKind", "pending", "active", "startedAt", "activeTasks"}) {
                Field field = ToolProgress.class.getDeclaredField(name); field.setAccessible(true);
                originalProgress.put(field, field.get(null));
            }
        }
        ToolProgress.interruptAll();
        assertTrue(preferences.edit().putBoolean("petEnabled", false).putString("petExternalDisplay", "off").putString("petInternalDisplay", "off").remove("returnToDroyOnCompletion").commit());
        startedRuntime = true;
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
        ui.hideKeyboard();
        DeviceUi.await("Droy accessibility must be connected for the real background return", 15000,
            () -> DroyAccessibilityService.isConnected(context));
        // The production navigator validates that this exact session exists and is not archived.
        ui.evaluate("window.__droyCompletionReady='pending';window.DroyMobile.revealPetTask({taskId:"
            + JSONObject.quote(taskId) + "}).then(()=>window.__droyCompletionReady='ready',()=>window.__droyCompletionReady='missing')");
        DeviceUi.await("The selected existing conversation did not resolve", 10000,
            () -> !"pending".equals(ui.evaluate("window.__droyCompletionReady")));
        assertEquals("completionTaskId must identify an existing, unarchived conversation", "ready",
            ui.evaluate("window.__droyCompletionReady"));
        ui.evaluate("delete window.__droyCompletionReady");
        DeviceUi.await("The selected conversation did not render", 10000, this::selectedTaskVisible);
    }

    @After public void tearDown() throws Exception {
        try {
            if (startedRuntime) {
                if (activity != null && !activity.isDestroyed()) instrumentation.runOnMainSync(activity::finish);
                instrumentation.runOnMainSync(RuntimeService::requestStop);
                DeviceUi.await("Completion test left its runtime running", 15000, RuntimeService::canManageHistory);
                PetOverlay.runtimeStopped();
                instrumentation.waitForIdleSync();
            }
        } finally {
            if (preferencesOwned) {
                SharedPreferences.Editor restore = preferences.edit();
                for (String key : PREF_KEYS) {
                    Object saved = originalPreferences.get(key);
                    if (saved instanceof Boolean value) restore.putBoolean(key, value);
                    else if (saved instanceof String value) restore.putString(key, value);
                    else restore.remove(key);
                }
                assertTrue("Could not restore completion preferences", restore.commit());
            }
            synchronized (ToolProgress.class) {
                for (Map.Entry<Field, Object> entry : originalProgress.entrySet()) entry.getKey().set(null, entry.getValue());
            }
            if (originalHistory != null) assertHistoryPreserved(historySnapshot());
        }
    }

    @Test public void existingTaskReturnsWithPetOffAfterBackgroundGracePeriodAndRespectsSwitch() throws Exception {
        WebView originalWeb = ui.web();
        assertFalse(preferences.getBoolean("petEnabled", true));
        assertFalse("Missing preference must leave completion in the background", preferences.getBoolean("returnToDroyOnCompletion", false));
        goHomeBeyondGracePeriod();
        postProgress("thinking", true);
        postProgress("completed", false);
        assertRemainsOutsideDroy("The default completion return interrupted the foreground app");
        assertTrue(preferences.edit().putBoolean("returnToDroyOnCompletion", true).commit());
        goHomeBeyondGracePeriod();
        postProgress("thinking", true);
        postProgress("completed", false);
        DeviceUi.await("Completion did not bring the real Droy Activity forward", 10000, this::activityFocused);
        DeviceUi.await("Completion returned without revealing the requested conversation", 10000, this::selectedTaskVisible);
        AtomicReference<String> returnedTask = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> returnedTask.set(activity.getIntent().getStringExtra("petTaskId")));
        assertEquals("Completion returned to another task", taskId, returnedTask.get());
        assertSame("Returning recreated the existing conversation WebView", originalWeb, ui.web());
        assertFalse("Completion enabled the pet", preferences.getBoolean("petEnabled", true));

        goHomeBeyondGracePeriod();
        postProgress("completed", false);
        RuntimeService.refreshExecutionProgress();
        assertRemainsOutsideDroy("Duplicate completion or refresh brought Droy forward again");
        assertTrue(preferences.edit().putBoolean("returnToDroyOnCompletion", false).commit());
        postProgress("thinking", true);
        postProgress("completed", false);
        assertRemainsOutsideDroy("Disabled completion return brought Droy forward");
        assertTrue(preferences.edit().putBoolean("returnToDroyOnCompletion", true).commit());
        postProgress("completed", false);
        assertRemainsOutsideDroy("Enabling return replayed an old completion");
        postProgress("thinking", true);
        postProgress("interrupted", false);
        assertRemainsOutsideDroy("An interrupted task triggered completion return");
    }

    @Test public void pendingQuestionReturnsWithPetOffAndDoesNotReplayAfterCancellation() throws Exception {
        goHomeBeyondGracePeriod();
        JSONObject question = new JSONObject().put("requestId", "question-return-fixture")
            .put("title", "需要回应").put("message", "请回到任务选择下一步").put("inline", false)
            .put("options", new org.json.JSONArray());
        ToolProgress.updateFromEngine(new JSONObject().put("taskId", taskId).put("phase", "waiting_input")
            .put("active", true).put("summary", "请回到任务选择下一步").put("pending", question));
        RuntimeService.refreshExecutionProgress();
        DeviceUi.await("Pending question did not bring Droy forward with both pet displays disabled", 5000, this::activityFocused);
        DeviceUi.await("Question return did not reach the original conversation", 5000, this::selectedTaskVisible);
        Field request = MainActivity.class.getDeclaredField("pendingPetRequest"); request.setAccessible(true);
        DeviceUi.await("Question return did not settle its actual page receipt", 5000, () -> {
            AtomicReference<Object> value = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> { try { value.set(request.get(activity)); } catch (Exception error) { throw new AssertionError(error); } });
            return value.get() == null;
        });
        ToolProgress.updateFromEngine(new JSONObject().put("taskId", taskId).put("phase", "interrupted")
            .put("active", false).put("summary", "任务已停止").put("pending", JSONObject.NULL));
        RuntimeService.refreshExecutionProgress();
        goHomeBeyondGracePeriod();
        instrumentation.runOnMainSync(() -> MainActivity.revealQuestion(context, taskId, "question-return-fixture"));
        RuntimeService.refreshExecutionProgress();
        assertRemainsOutsideDroy("An answered or cancelled question replayed a late return");
    }

    @Test public void aNewQuestionInTheSameTaskReplacesAnOlderInFlightNavigation() throws Exception {
        ui.evaluate("window.__droyOriginalReveal=window.DroyMobile.revealPetTask;window.__droyQuestionReturns=[];"
            + "window.DroyMobile.revealPetTask=args=>{window.__droyQuestionReturns.push(args.requestId);"
            + "if(args.requestId==='older-question')return new Promise(resolve=>window.__droyFinishOldReveal=resolve);"
            + "return window.__droyOriginalReveal(args)}");
        try {
            deliverQuestionIntent("older-question");
            DeviceUi.await("The older question never started its delayed navigation", 5000,
                () -> ui.truth("window.__droyQuestionReturns.includes('older-question')"));
            deliverQuestionIntent("newer-question");
            DeviceUi.await("The older in-flight task key suppressed the newer question", 5000,
                () -> ui.truth("window.__droyQuestionReturns.includes('newer-question')"));
            DeviceUi.await("The replacement question did not reach its conversation", 5000, this::selectedTaskVisible);
            ui.evaluate("window.__droyFinishOldReveal({status:'opened'})");
            assertTrue("The old navigation response changed the current question", ToolProgress.hasPending(taskId, "newer-question"));
        } finally {
            ui.evaluate("window.DroyMobile.revealPetTask=window.__droyOriginalReveal;"
                + "window.__droyFinishOldReveal?.({status:'opened'});delete window.__droyOriginalReveal;"
                + "delete window.__droyFinishOldReveal;delete window.__droyQuestionReturns");
        }
    }

    private void deliverQuestionIntent(String requestId) {
        // Replace the question and deliver its Intent in the same main-thread turn,
        // before a separately posted overlay refresh can clear the older navigation.
        instrumentation.runOnMainSync(() -> {
            try {
                JSONObject question = new JSONObject().put("requestId", requestId).put("title", "需要回应")
                    .put("message", "请选择下一步").put("inline", false).put("options", new org.json.JSONArray());
                ToolProgress.updateFromEngine(new JSONObject().put("taskId", taskId).put("phase", "waiting_input")
                    .put("active", true).put("summary", "请选择下一步").put("pending", question));
                activity.onNewIntent(new Intent(context, MainActivity.class).putExtra("petTaskId", taskId).putExtra("petRequestId", requestId));
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
    }

    private boolean activityFocused() {
        java.util.concurrent.atomic.AtomicBoolean focused = new java.util.concurrent.atomic.AtomicBoolean();
        instrumentation.runOnMainSync(() -> focused.set(activity.hasWindowFocus()));
        return focused.get();
    }

    private boolean selectedTaskVisible() throws Exception {
        return ui.truth("[...document.querySelectorAll('[data-droy-session-row][aria-selected=true]')].some(e=>e.getAttribute('data-droy-session-row')==="
            + JSONObject.quote(taskId) + ")&&!!document.querySelector('[data-composer-input][contenteditable=true]')"
            + "&&!document.querySelector('[data-droy-drawer-open=true]')&&!document.querySelector('[data-droy-settings-panel]')");
    }

    private void goHomeBeyondGracePeriod() throws Exception {
        context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        DeviceUi.await("The test did not leave Droy", 5000, () -> !activityFocused());
        automation.waitForIdle(300, 5000);
        long until = SystemClock.elapsedRealtime() + 6500;
        do {
            assertFalse("Droy unexpectedly became foreground during the background grace wait", activityFocused());
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < until);
    }

    private void assertRemainsOutsideDroy(String message) {
        long until = SystemClock.elapsedRealtime() + 1500;
        do {
            assertFalse(message, activityFocused());
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < until);
    }

    private void postProgress(String phase, boolean active) throws Exception {
        Field bridgeField = RuntimeService.class.getDeclaredField("bridge"); bridgeField.setAccessible(true);
        ProductBridge bridge = (ProductBridge) bridgeField.get(RuntimeService.instance);
        Field tokenField = ProductBridge.class.getDeclaredField("token"); tokenField.setAccessible(true);
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + bridge.getListeningPort() + "/progress").openConnection(Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + tokenField.get(bridge));
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] body = new JSONObject().put("taskId", taskId).put("phase", phase).put("active", active)
                .put("summary", "").put("pending", JSONObject.NULL).toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            assertEquals("The progress endpoint rejected the test event", 200, connection.getResponseCode());
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream response = new ByteArrayOutputStream(); RuntimeInstaller.copy(input, response);
                assertTrue(new JSONObject(response.toString(StandardCharsets.UTF_8.name())).getBoolean("ok"));
            }
        } finally { connection.disconnect(); }
    }

    private void assertHistoryPreserved(Map<String, byte[]> after) throws Exception {
        assertTrue("Completion test created or removed session files", originalHistory.keySet().equals(after.keySet()));
        for (Map.Entry<String, byte[]> entry : originalHistory.entrySet()) {
            byte[] before = entry.getValue(), current = after.get(entry.getKey());
            assertTrue("Completion test truncated session history", current.length >= before.length);
            for (int index = 0; index < before.length; index++)
                assertTrue("Completion test rewrote an existing history byte", before[index] == current[index]);
            if (before.length == current.length) continue;
            assertTrue("Only an existing JSONL event log may receive recovery metadata", entry.getKey().endsWith(".jsonl"));
            assertTrue("Recovery metadata must follow a complete original JSONL record", before.length > 0 && before[before.length - 1] == '\n');
            assertTrue("Recovery metadata must end with a complete JSONL record", current[current.length - 1] == '\n');
            String previous = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(before)).toString().stripTrailing();
            String appended = StandardCharsets.UTF_8.newDecoder().decode(
                java.nio.ByteBuffer.wrap(current, before.length, current.length - before.length)).toString();
            try {
                JSONObject last = new JSONObject(previous.substring(previous.lastIndexOf('\n') + 1));
                long sequence = last.getLong("seq");
                String[] lines = appended.split("\n", -1);
                for (int index = 0; index < lines.length - 1; index++) {
                    JSONObject marker = new JSONObject(lines[index]);
                    assertTrue("Recovery marker contains unexpected fields",
                        marker.length() == 4 && marker.has("type") && marker.has("seq") && marker.has("time") && marker.has("data"));
                    assertTrue("Completion test appended a non-recovery event", "session/end-seed".equals(marker.getString("type")));
                    assertTrue("Recovery marker must contain an empty data object",
                        marker.optJSONObject("data") != null && marker.getJSONObject("data").length() == 0);
                    assertTrue("Recovery sequence must be an integer", marker.get("seq") instanceof Integer || marker.get("seq") instanceof Long);
                    assertEquals("Recovery sequence must continue the existing log", ++sequence, marker.getLong("seq"));
                    assertTrue("Recovery time must be an integer", marker.get("time") instanceof Integer || marker.get("time") instanceof Long);
                    assertTrue("Recovery time must be nonnegative", marker.getLong("time") >= 0);
                }
            } catch (org.json.JSONException malformed) {
                // Do not include existing private event contents in test diagnostics.
                throw new AssertionError("Session recovery metadata is not valid JSONL");
            }
        }
    }

    private Map<String, byte[]> historySnapshot() throws Exception {
        Map<String, byte[]> result = new TreeMap<>();
        if (!Files.isDirectory(sessionDirectory, LinkOption.NOFOLLOW_LINKS)) return result;
        try (java.util.stream.Stream<Path> paths = Files.walk(sessionDirectory)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                assertFalse("Session history contains an unexpected link", Files.isSymbolicLink(path));
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    result.put(sessionDirectory.relativize(path).toString(), Files.readAllBytes(path));
            }
        }
        return result;
    }
}
