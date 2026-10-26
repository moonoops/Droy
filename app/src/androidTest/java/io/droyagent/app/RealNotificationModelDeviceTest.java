package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Explicit opt-in real-model evaluation. The only allowed external message is the fixed self-test below. */
@RunWith(AndroidJUnit4.class)
public final class RealNotificationModelDeviceTest {
    private static final String WECHAT = "com.tencent.mm";
    private static final String RECIPIENT = "文件传输助手";
    private static final String MESSAGE = "Droy 通知功能测试，可忽略。";
    private static final String[] KEYS = {"notifyLocalEnabled", "notifyAppsEnabled", "notifyAllowedApps"};
    private final Map<String, Object> saved = new HashMap<>();
    private Instrumentation instrumentation;
    private UiAutomation automation;
    private Context context;
    private MainActivity activity;
    private DeviceUi ui;
    private SharedPreferences preferences;
    private Boolean savedPlugin;
    private boolean recoveryMode;
    private String originalWechatSession = "";
    private long lastObscuredSeq = -1;
    private boolean preferencesOwned;
    private Path profilePath;
    private byte[] savedProfile;
    private final JSONArray cases = new JSONArray();

    @Test public void existingModelChoosesAndExecutesApprovedNotifications() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        assumeTrue("Explicit model-use approval required", "true".equals(args.getString("realNotificationModel")));
        instrumentation = InstrumentationRegistry.getInstrumentation();
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        assertFalse("Use the configured real phone, not an unconfigured emulator", NotificationTestState.emulator());
        assertTrue("Stop the runtime before the real model evaluation", RuntimeService.canManageHistory());
        profilePath = context.getFilesDir().toPath().resolve("dsh-home/profiles/mobile/cordis.patch.yml");
        if (Files.isRegularFile(profilePath)) savedProfile = Files.readAllBytes(profilePath);
        preferences = ProductPermissions.preferences(context);
        Map<String, ?> all = preferences.getAll();
        for (String key : KEYS) if (all.containsKey(key)) saved.put(key, all.get(key));
        preferencesOwned = true;
        Throwable failure = null;
        try {
            activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            ui = new DeviceUi(instrumentation, activity); ui.awaitPage();
            savedPlugin = plugin(null).getBoolean("enabled");
            if (!savedPlugin) plugin(true);
            ProactiveNotifications.get(context).request(new JSONObject().put("operation", "configure").put("localEnabled", true));
            DeviceUi.await("Notification plugin was not activated", 15000,
                () -> ProactiveNotifications.get(context).status().optBoolean("pluginActive"));
            if ("true".equals(args.getString("realNotificationRecovery"))) {
                recoveryMode = true;
                assertPreviousSendWasNotDispatched();
                captureWindowGeometry("before-recovery");
                configureWechat();
                evaluate("wechat_self_test_recovery", "Droy 通知验收恢复：上次测试已在输入派发前因其他窗口遮挡被拒绝，没有发出任何消息。本次只继续完成原来批准的一条消息。请先观察手机并识别遮挡，必要时用返回收起键盘后重新观察、定位微信文件传输助手的输入框；不要放宽快照校验。请使用通知插件准备App通知，由当前任务通过Android Use向我自己的“文件传输助手”仅发送一条“"
                    + MESSAGE + "”。不要给其他目标发消息；一旦输入或发送结果不确定就核实或结束为未知，不能重复发送。发送后观察正确会话中的消息并用complete记录结果。", false, true);
                return;
            }
            evaluate("simple_answer", "Droy 通知验收 A：请只回答 1+1 等于多少。", false, false);
            evaluate("single_system", "Droy 通知验收 B：请在手机系统通知栏静默提醒我“Droy 本机通知测试”。这是普通测试，只用系统通知这一种方式。", true, false);
            if ("true".equals(args.getString("realNotificationSendWechat"))) {
                configureWechat();
                evaluate("wechat_self_test", "Droy 通知验收 C：用户已明确允许这一次真实测试。请通过微信向我自己的“文件传输助手”主动通知，且仅发送一条消息，内容必须完整且仅为：“"
                    + MESSAGE + "”。请使用 Droy 通知插件准备 App 通知，由当前任务使用 Android Use 完成发送，发送后观察目标会话并记录实际结果。不要向任何其他联系人发送，不要添加说明或第二条消息；若结果不确定，请核实或结束为未知，不得重复点击发送。", false, true);
            }
        } catch (Exception | AssertionError error) { failure = error; throw error; }
        finally {
            writeResult(failure);
            try { if (savedPlugin != null && RuntimeService.ready && plugin(null).getBoolean("enabled") != savedPlugin) plugin(savedPlugin); }
            finally {
                if (preferencesOwned) {
                    SharedPreferences.Editor editor = preferences.edit();
                    for (String key : KEYS) {
                        Object value = saved.get(key);
                        if (value instanceof Boolean bool) editor.putBoolean(key, bool);
                        else if (value instanceof String text) editor.putString(key, text);
                        else editor.remove(key);
                    }
                    assertTrue("Could not restore notification test preferences", editor.commit());
                }
                if (activity != null) instrumentation.runOnMainSync(activity::finish);
                instrumentation.runOnMainSync(RuntimeService::requestStop);
                DeviceUi.await("The evaluation runtime did not stop", 15000, RuntimeService::canManageHistory);
                if (savedProfile != null) Files.write(profilePath, savedProfile);
            }
        }
    }

    private void evaluate(String name, String prompt, boolean expectLocal, boolean expectApp) throws Exception {
        String session = newConversation();
        JSONObject outcome = new JSONObject().put("case", name).put("sessionId", session).put("status", "started");
        cases.put(outcome);
        assertTrue("The real DSH input facade was not available", ui.truth("(()=>{const e=document.querySelector('[data-composer-input]');"
            + "const key=Object.keys(e||{}).find(k=>k.startsWith('__reactFiber$'));for(let f=e?.[key];f;f=f.return){const p=f.memoizedProps;"
            + "if(p&&typeof p.addFiles==='function'&&p.keyboard&&p.sessionId===" + JSONObject.quote(session)
            + "){window.__droyNotificationInput=p.keyboard;return true}}return false})()"));
        assertTrue("The evaluation must start from an empty new draft", ui.truth("window.__droyNotificationInput.snapshot.draft.trim()===''") );
        ui.evaluate("window.__droyNotificationInput.setDraft(" + JSONObject.quote(prompt) + ");true");
        DeviceUi.await("The test draft did not enter the normal composer", 5000,
            () -> ui.truth("document.querySelector('[data-composer-input]').textContent.includes(" + JSONObject.quote(prompt.substring(0, 12)) + ")"));
        if (expectApp) {
            File marker = new File(context.getExternalFilesDir(null), "real-notification-model-evidence/" + (recoveryMode ? "wechat-recovery-attempt.json" : "wechat-attempt.json"));
            assertFalse("A previous real WeChat attempt exists; inspect it rather than sending another message", marker.exists());
            assertTrue(marker.getParentFile().isDirectory() || marker.getParentFile().mkdirs());
            Files.write(marker.toPath(), new JSONObject().put("sessionId", session).put("message", MESSAGE)
                .put("target", RECIPIENT).put("previousSessionId", originalWechatSession).put("state", "prompt_submission_started").toString(2).getBytes(StandardCharsets.UTF_8));
        }
        ui.evaluate("window.__droyNotificationInput.submit('queue','click');delete window.__droyNotificationInput;true");
        DeviceUi.await("The configured model did not start the test turn", 30000, () -> turnStarted(session));
        DeviceUi.await("The real model did not finish the test turn", expectApp ? 240000 : 90000, () -> turnEnded(session));
        JSONArray events = events(session), tools = new JSONArray();
        int notifications = 0;
        boolean prepared = false, completed = false;
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            if (!java.util.Set.of("tool/call", "tool/ptc-dispatch-start").contains(event.optString("type"))) continue;
            JSONObject data = event.getJSONObject("data");
            String tool = data.optString("name");
            tools.put(new JSONObject().put("name", tool).put("seq", event.opt("seq")));
            if ("droy_notify".equals(tool)) {
                JSONObject arguments = object(data.opt("arguments"));
                if ("send".equals(arguments.optString("operation"))) notifications++;
                if ("complete".equals(arguments.optString("operation"))) completed = true;
            }
        }
        JSONArray records = records(session);
        int actualLocal = 0, actualApp = 0;
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            if ("app".equals(record.optString("channel"))) {
                actualApp++; prepared = true;
                assertEquals("Only the approved app may be used", WECHAT, record.optString("packageName"));
                assertEquals("Only the approved self recipient may be used", RECIPIENT, record.optString("target"));
                assertEquals("The message must match the one approved by the user", MESSAGE, record.optString("text"));
            } else actualLocal++;
        }
        outcome.put("tools", tools).put("notificationCalls", notifications).put("records", safeRecords(records))
            .put("status", "finished").put("appPrepared", prepared).put("appCompletionCalled", completed);
        if (!expectLocal && !expectApp) assertEquals("A simple answer should not produce an extra notification", 0, notifications);
        if (expectLocal) {
            assertEquals("Ordinary local test should select a single channel", 1, actualLocal);
            assertEquals("Ordinary local test must not send via an App", 0, actualApp);
            assertEquals("The local notice was not published", "published", records.getJSONObject(0).optString("status"));
        }
        if (expectApp) {
            assertEquals("Only one App notification may be prepared", 1, actualApp);
            assertEquals("The specified App-only test should not also alert locally", 0, actualLocal);
            assertTrue("The model must prepare through droy_notify", prepared);
            assertTrue("The current Agent must settle the result with droy_notify complete", completed);
            assertEquals("The app send was not observed", "sent_in_app", records.getJSONObject(0).optString("status"));
        }
    }

    private String newConversation() throws Exception {
        if (!ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')")) ui.click("[data-droy-menu]");
        ui.click("button[aria-label='新建会话']");
        DeviceUi.await("The new test conversation did not open", 15000,
            () -> ui.truth("!!document.querySelector('[data-composer-input][contenteditable=true]')&&!document.querySelector('[data-droy-drawer-open=true]')"));
        String session = String.valueOf(ui.evaluate("document.querySelector('[data-conversation-session]')?.getAttribute('data-conversation-session')||''"));
        assertFalse("A real session identity is required", session.isBlank());
        return session;
    }

    private JSONArray events(String session) throws Exception {
        Path root = context.getFilesDir().toPath().resolve("dsh-home/sessions");
        if (!Files.isDirectory(root)) return new JSONArray();
        JSONArray rows = new JSONArray();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".jsonl")
                    || !path.getParent().getFileName().toString().equals(session)) continue;
                String log = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                int committed = log.lastIndexOf('\n');
                if (committed < 0) continue;
                for (String line : log.substring(0, committed).split("\n")) {
                    if (line.isBlank()) continue;
                    JSONObject value = new JSONObject(line);
                    if (!"session".equals(value.optString("type"))) rows.put(value);
                }
            }
        }
        return rows;
    }
    private boolean turnStarted(String session) throws Exception { return hasEvent(session, "turn/start"); }
    private boolean turnEnded(String session) throws Exception { return hasEvent(session, "turn/end"); }
    private boolean hasEvent(String session, String type) throws Exception {
        JSONArray rows = events(session);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (recoveryMode && "tool/result".equals(row.optString("type")) && row.optLong("seq", -1) > lastObscuredSeq
                && row.optJSONObject("data") != null && row.getJSONObject("data").toString().contains("target_obscured")) {
                lastObscuredSeq = row.getLong("seq");
                captureWindowGeometry("obscured-" + lastObscuredSeq);
            }
            if (type.equals(row.optString("type"))) return true;
        }
        return false;
    }
    private JSONArray records(String session) throws Exception {
        JSONArray all = new JSONArray(preferences.getString("proactiveNotificationRecords", "[]")), selected = new JSONArray();
        for (int i = 0; i < all.length(); i++) if (session.equals(all.getJSONObject(i).optString("taskId"))) selected.put(all.getJSONObject(i));
        return selected;
    }
    private JSONArray safeRecords(JSONArray records) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < records.length(); i++) {
            JSONObject row = records.getJSONObject(i), safe = new JSONObject();
            for (String key : new String[]{"noticeId", "channel", "status", "target", "text"}) if (row.has(key)) safe.put(key, row.get(key));
            result.put(safe);
        }
        return result;
    }
    private static JSONObject object(Object value) throws Exception { return value instanceof JSONObject obj ? obj : new JSONObject(String.valueOf(value)); }
    private JSONObject plugin(Boolean enabled) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + RuntimeService.webPort + "/droy/notify/plugin").openConnection(Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(5000); connection.setReadTimeout(15000);
            connection.setRequestProperty("Cookie", "droy_session=" + RuntimeService.webToken);
            if (enabled != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] data = new JSONObject().put("enabled", enabled).toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(data.length);
                try (var output = connection.getOutputStream()) { output.write(data); }
            }
            assertEquals("The real notification plugin control failed", 200, connection.getResponseCode());
            try (InputStream input = connection.getInputStream()) { return new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8)); }
        } finally { connection.disconnect(); }
    }


    @Test public void inspectNotificationWindowGeometry() throws Exception {
        assumeTrue("Explicit geometry probe flag required", "true".equals(InstrumentationRegistry.getArguments().getString("realNotificationGeometry")));
        instrumentation = InstrumentationRegistry.getInstrumentation();
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        android.accessibilityservice.AccessibilityServiceInfo probeInfo = automation.getServiceInfo();
        probeInfo.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(probeInfo);
        captureWindowGeometry("live-probe");
        java.lang.reflect.Field serviceField = io.droyagent.androiduse.DroyAccessibilityService.class.getDeclaredField("connected");
        serviceField.setAccessible(true);
        io.droyagent.androiduse.DroyAccessibilityService service = (io.droyagent.androiduse.DroyAccessibilityService) serviceField.get(null);
        android.view.WindowManager manager = (service != null ? service : context).getSystemService(android.view.WindowManager.class);
        android.view.WindowMetrics metrics = manager.getMaximumWindowMetrics();
        android.view.WindowInsets insets = metrics.getWindowInsets();
        JSONObject insetReport = new JSONObject().put("context", service != null ? "accessibility_service" : "target_application")
            .put("displayBounds", metrics.getBounds().flattenToString());
        String[] insetNames = {"systemBars", "navigationBars", "systemGestures", "mandatorySystemGestures", "tappableElement", "displayCutout"};
        int[] insetTypes = {android.view.WindowInsets.Type.systemBars(), android.view.WindowInsets.Type.navigationBars(),
            android.view.WindowInsets.Type.systemGestures(), android.view.WindowInsets.Type.mandatorySystemGestures(),
            android.view.WindowInsets.Type.tappableElement(), android.view.WindowInsets.Type.displayCutout()};
        for (int i = 0; i < insetTypes.length; i++) {
            android.graphics.Insets visible = insets.getInsets(insetTypes[i]), ignoring = insets.getInsetsIgnoringVisibility(insetTypes[i]);
            insetReport.put(insetNames[i], new JSONObject().put("visible", insetValue(visible)).put("ignoringVisibility", insetValue(ignoring)));
        }
        File metricDirectory = new File(context.getExternalFilesDir(null), "real-notification-model-evidence");
        Files.write(new File(metricDirectory, "insets-live-probe.json").toPath(), insetReport.toString(2).getBytes(StandardCharsets.UTF_8));
        android.view.accessibility.AccessibilityNodeInfo root = service != null ? service.getRootInActiveWindow() : automation.getRootInActiveWindow();
        assertNotNull("No current app root", root);
        JSONArray editable = new JSONArray();
        java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            android.view.accessibility.AccessibilityNodeInfo node = queue.removeFirst();
            try {
                if (node.isEditable()) {
                    android.graphics.Rect bounds = new android.graphics.Rect(); node.getBoundsInScreen(bounds);
                    editable.put(new JSONObject().put("windowId", node.getWindowId()).put("className", String.valueOf(node.getClassName()))
                        .put("resourceId", node.getViewIdResourceName()).put("visible", node.isVisibleToUser()).put("enabled", node.isEnabled())
                        .put("bounds", new JSONObject().put("left", bounds.left).put("top", bounds.top).put("right", bounds.right).put("bottom", bounds.bottom)));
                }
                for (int i = 0; i < node.getChildCount(); i++) { var child = node.getChild(i); if (child != null) queue.add(child); }
            } finally { node.recycle(); }
        }
        File directory = new File(context.getExternalFilesDir(null), "real-notification-model-evidence");
        Files.write(new File(directory, "editable-live-probe.json").toPath(), editable.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject insetValue(android.graphics.Insets value) throws Exception {
        return new JSONObject().put("left", value.left).put("top", value.top).put("right", value.right).put("bottom", value.bottom);
    }

    private void configureWechat() throws Exception {
        context.getPackageManager().getApplicationInfo(WECHAT, 0);
        assertFalse("Respect the saved Android Use pause", ProductPermissions.stopped(context));
        DeviceUi.await("The authorized accessibility service did not reconnect", 15000,
            () -> io.droyagent.androiduse.DroyAccessibilityService.isConnected(context));
        JSONArray allowed = new JSONArray(preferences.getString("notifyAllowedApps", "[]"));
        boolean found = false;
        for (int i = 0; i < allowed.length(); i++) if (WECHAT.equals(allowed.getJSONObject(i).optString("packageName"))) {
            allowed.getJSONObject(i).put("target", RECIPIENT); found = true;
        }
        if (!found) allowed.put(new JSONObject().put("packageName", WECHAT).put("target", RECIPIENT));
        ProactiveNotifications.get(context).request(new JSONObject().put("operation", "configure")
            .put("appsEnabled", true).put("allowedApps", allowed));
    }

    private void assertPreviousSendWasNotDispatched() throws Exception {
        File directory = new File(context.getExternalFilesDir(null), "real-notification-model-evidence");
        JSONObject prior = new JSONObject(new String(Files.readAllBytes(new File(directory, "result.json").toPath()), StandardCharsets.UTF_8));
        JSONArray priorCases = prior.getJSONArray("cases");
        for (int i = 0; i < priorCases.length(); i++) if ("wechat_self_test".equals(priorCases.getJSONObject(i).optString("case"))) {
            JSONObject row = priorCases.getJSONObject(i);
            originalWechatSession = row.getString("sessionId");
            JSONArray receipts = row.getJSONArray("records");
            assertEquals(1, receipts.length());
            assertEquals("failed", receipts.getJSONObject(0).getString("status"));
            assertEquals(MESSAGE, receipts.getJSONObject(0).getString("text"));
        }
        assertFalse("A previous failed self-test is required", originalWechatSession.isBlank());
        JSONArray rows = events(originalWechatSession);
        java.util.Set<String> inputs = new java.util.HashSet<>();
        long firstInput = Long.MAX_VALUE;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (!"tool/call".equals(row.optString("type"))) continue;
            JSONObject data = row.getJSONObject("data");
            if ("android_input_text".equals(data.optString("name")) && MESSAGE.equals(object(data.opt("arguments")).optString("text"))) {
                inputs.add(data.getString("callId")); firstInput = Math.min(firstInput, row.getLong("seq"));
            }
        }
        assertFalse("There must be rejected message-input attempts to recover", inputs.isEmpty());
        int rejected = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if ("tool/call".equals(row.optString("type")) && row.getLong("seq") > firstInput) {
                String name = row.getJSONObject("data").optString("name");
                assertFalse("A send gesture after the message input makes recovery unsafe", java.util.Set.of("android_tap", "android_gesture").contains(name));
            }
            if (!"tool/result".equals(row.optString("type"))) continue;
            JSONObject message = row.getJSONObject("data").getJSONObject("message");
            if (!inputs.contains(message.optString("toolCallId"))) continue;
            assertTrue("An earlier message input might have dispatched", message.optBoolean("isError"));
            String content = message.getJSONArray("content").getJSONObject(0).getString("text");
            assertTrue(content.contains("target_obscured") && content.contains("\"dispatched\":false") && content.contains("\"outcomeUnknown\":false"));
            rejected++;
        }
        assertEquals(inputs.size(), rejected);
        File backup = new File(directory, "result-first-attempt.json");
        if (!backup.exists()) Files.copy(new File(directory, "result.json").toPath(), backup.toPath());
    }

    private void captureWindowGeometry(String label) throws Exception {
        JSONArray rows = new JSONArray();
        java.lang.reflect.Field serviceField = io.droyagent.androiduse.DroyAccessibilityService.class.getDeclaredField("connected");
        serviceField.setAccessible(true);
        io.droyagent.androiduse.DroyAccessibilityService service = (io.droyagent.androiduse.DroyAccessibilityService) serviceField.get(null);
        java.util.List<android.graphics.Rect> editableAreas = new java.util.ArrayList<>();
        android.view.accessibility.AccessibilityNodeInfo activeRoot = service != null ? service.getRootInActiveWindow() : automation.getRootInActiveWindow();
        if (activeRoot != null) {
            java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> pendingNodes = new java.util.ArrayDeque<>();
            pendingNodes.add(activeRoot);
            while (!pendingNodes.isEmpty()) {
                android.view.accessibility.AccessibilityNodeInfo node = pendingNodes.removeFirst();
                try {
                    if (node.isEditable()) { android.graphics.Rect area = new android.graphics.Rect(); node.getBoundsInScreen(area); editableAreas.add(area); }
                    for (int i = 0; i < node.getChildCount(); i++) { var child = node.getChild(i); if (child != null) pendingNodes.add(child); }
                } finally { node.recycle(); }
            }
        }
        java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = service != null ? service.getWindows() : automation.getWindows();
        try {
            for (android.view.accessibility.AccessibilityWindowInfo window : windows) {
                android.graphics.Rect bounds = new android.graphics.Rect(); window.getBoundsInScreen(bounds);
                JSONObject row = new JSONObject().put("type", window.getType()).put("layer", window.getLayer())
                    .put("focused", window.isFocused()).put("active", window.isActive())
                    .put("bounds", new JSONObject().put("left", bounds.left).put("top", bounds.top).put("right", bounds.right).put("bottom", bounds.bottom));
                android.view.accessibility.AccessibilityNodeInfo root = window.getRoot();
                if (root != null) { try { row.put("packageName", String.valueOf(root.getPackageName())); } finally { root.recycle(); } }
                if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) row.put("systemTitle", String.valueOf(window.getTitle()));
                android.graphics.Region region = new android.graphics.Region(); window.getRegionInScreen(region);
                JSONArray intersections = new JSONArray();
                for (android.graphics.Rect area : editableAreas) {
                    android.graphics.Region intersection = new android.graphics.Region(region);
                    intersection.op(area, android.graphics.Region.Op.INTERSECT);
                    intersections.put(new JSONObject().put("editableBounds", area.flattenToString())
                        .put("isEmpty", intersection.isEmpty()).put("region", intersection.toString()));
                }
                row.put("regionInScreen", region.toString()).put("editableIntersections", intersections);
                rows.put(row);
            }
        } finally { for (android.view.accessibility.AccessibilityWindowInfo window : windows) window.recycle(); }
        File directory = new File(context.getExternalFilesDir(null), "real-notification-model-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Files.write(new File(directory, "windows-" + label + ".json").toPath(), rows.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private void writeResult(Throwable failure) throws Exception {
        if (context == null) return;
        File directory = new File(context.getExternalFilesDir(null), "real-notification-model-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        JSONObject result = new JSONObject().put("cases", cases).put("passed", failure == null)
            .put("device", android.os.Build.MODEL).put("api", android.os.Build.VERSION.SDK_INT);
        if (failure != null) result.put("failure", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        Files.write(new File(directory, recoveryMode ? "result-recovery.json" : "result.json").toPath(), result.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
