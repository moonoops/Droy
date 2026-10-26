package io.droyagent.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;

/** Stores operation metadata, never arguments, screen text, or personal query results. */
final class ToolProgress {
    private static final Set<String> PHASES = Set.of("thinking", "executing", "verifying", "waiting", "waiting_input", "blocked", "completed", "interrupted");
    private static final Set<String> WORKING_PHASES = Set.of("thinking", "executing", "verifying");
    private static Map<String, TaskState> activeTasks = Map.of();
    private static String currentTask = "", currentPhase = "";
    private static String summary = "", summaryKind = "", activityKind = "";
    private static JSONObject pending;
    private static boolean active;
    private static long startedAt;
    private final SharedPreferences preferences;
    ToolProgress(Context context) { preferences = context.getSharedPreferences("tool-progress", Context.MODE_PRIVATE); }
    synchronized void record(String action, String state, String outcome) {
        try {
            JSONArray old = history(), next = new JSONArray();
            for (int i = Math.max(0, old.length() - 19); i < old.length(); i++) next.put(old.getJSONObject(i));
            next.put(new JSONObject().put("action", action).put("state", state).put("outcome", outcome)
                .put("recordedAt", System.currentTimeMillis()));
            // Diagnostic history is not the action acknowledgement. Keep its disk
            // flush off the dispatch path; SharedPreferences updates memory first.
            preferences.edit().putString("history", next.toString()).apply();
        } catch (org.json.JSONException error) { throw new IllegalStateException(io.droyagent.app.NativeLocale.text("操作状态无效", "Invalid operation state"), error); }
    }
    static synchronized void update(String task, String phase, boolean isActive) {
        validatePhase(task, phase);
        TaskState previous = activeTasks.get(task);
        // Native wait_for dispatch/receipt stages must not replace the engine's
        // announced wait. A later engine event explicitly resumes execution.
        if (isActive && previous != null && previous.phase.equals("waiting")) phase = "waiting";
        JSONObject question = isActive && previous != null ? previous.pending : null;
        String text = isActive ? previous == null ? "" : previous.summary : terminalSummary(phase);
        String kind = isActive && previous != null ? previous.summaryKind : "";
        if (kind.equals("slow") && !phase.equals("thinking")) { kind = ""; text = ""; }
        applyTask(task, phase, isActive, text, kind, isActive && previous != null ? previous.activityKind : "", question);
    }
    static synchronized boolean hasPending(String task, String request) {
        TaskState state = activeTasks.get(task);
        return state != null && state.pending != null && request.equals(state.pending.optString("requestId"));
    }
    private static void validatePhase(String task, String phase) {
        if (task == null || task.isBlank() || task.length() > 256 || !PHASES.contains(phase))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("执行进度无效", "Invalid execution progress"));
    }
    static synchronized boolean updateFromEngine(JSONObject request) throws org.json.JSONException {
        String task = request.getString("taskId"), phase = request.getString("phase");
        validatePhase(task, phase);
        Object rawActive = request.get("active");
        if (!(rawActive instanceof Boolean)) throw new IllegalArgumentException("active must be a boolean");
        String text = request.has("summary") ? boundedText(request, "summary", 120, true) : "";
        String kind = request.has("summaryKind") ? boundedText(request, "summaryKind", 16, true) : "";
        if (!kind.isEmpty() && !kind.equals("working") && !kind.equals("slow")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物消息类型无效", "Invalid companion message type"));
        String activity = request.has("activityKind") ? boundedText(request, "activityKind", 16, true) : "";
        if (!Set.of("", "started", "thinking", "observing", "controlling", "working").contains(activity))
            throw new IllegalArgumentException(NativeLocale.text("伙伴进度类型无效", "Invalid companion activity type"));
        JSONObject nextPending = null;
        if (!request.isNull("pending")) {
            JSONObject source = request.getJSONObject("pending");
            JSONArray options = source.getJSONArray("options"), normalized = new JSONArray();
            if (options.length() > 3 || !(source.get("inline") instanceof Boolean)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物选项无效", "Invalid companion options"));
            for (int i = 0; i < options.length(); i++) {
                JSONObject option = options.getJSONObject(i);
                normalized.put(new JSONObject().put("id", boundedText(option, "id", 80, false))
                    .put("label", boundedText(option, "label", 24, false)));
            }
            nextPending = new JSONObject().put("requestId", boundedText(source, "requestId", 160, false))
                .put("title", boundedText(source, "title", 32, false)).put("message", boundedText(source, "message", 120, false))
                .put("inline", source.getBoolean("inline")).put("options", normalized);
            if (source.has("questions")) nextPending.put("questions", normalizeQuestions(source.getJSONArray("questions")));
        }
        boolean completedNow = activeTasks.containsKey(task) && !(Boolean) rawActive && phase.equals("completed");
        applyTask(task, phase, (Boolean) rawActive, text, kind, activity, nextPending);
        return completedNow;
    }

    private static JSONArray normalizeQuestions(JSONArray source) throws org.json.JSONException {
        if (source.length() < 1 || source.length() > 16 || source.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 100000)
            throw new IllegalArgumentException(NativeLocale.text("伙伴问题表单无效", "Invalid companion question form"));
        JSONArray questions = new JSONArray();
        Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject raw = source.getJSONObject(i);
            String id = boundedText(raw, "id", 160, false);
            if (!ids.add(id) || !(raw.get("multiSelect") instanceof Boolean)
                || !(raw.get("allowCustom") instanceof Boolean)) throw new IllegalArgumentException(NativeLocale.text("伙伴问题无效", "Invalid companion question"));
            JSONObject question = new JSONObject().put("id", id).put("question", boundedText(raw, "question", 4000, false))
                .put("multiSelect", raw.getBoolean("multiSelect")).put("allowCustom", raw.getBoolean("allowCustom"));
            if (raw.has("header")) question.put("header", boundedText(raw, "header", 512, true));
            if (raw.has("detail")) question.put("detail", boundedText(raw, "detail", 16000, true));
            JSONArray options = raw.getJSONArray("options"), normalized = new JSONArray();
            if (options.length() > 32) throw new IllegalArgumentException(NativeLocale.text("伙伴问题选项无效", "Invalid companion question options"));
            for (int j = 0; j < options.length(); j++) {
                JSONObject option = options.getJSONObject(j);
                JSONObject next = new JSONObject().put("label", boundedText(option, "label", 512, false));
                if (option.has("description")) next.put("description", boundedText(option, "description", 4000, true));
                normalized.put(next);
            }
            questions.put(question.put("options", normalized));
        }
        return questions;
    }

    private static void applyTask(String task, String phase, boolean isActive, String text, String kind, String activity, JSONObject question) {
        TaskState previous = activeTasks.get(task);
        // A stale terminal event must neither revive a task nor replace another task's presentation.
        if (!isActive && previous == null && !currentTask.isEmpty() && !currentTask.equals(task)) return;
        question = isActive ? question : null;
        String effectivePhase = question == null ? phase : "waiting_input";
        long began = previous != null && previous.phase.equals(effectivePhase) ? previous.startedAt
            : !isActive && currentTask.equals(task) && currentPhase.equals(effectivePhase) ? startedAt : System.currentTimeMillis();
        TaskState next = new TaskState(task, effectivePhase, text,
            isActive && question == null && WORKING_PHASES.contains(effectivePhase) ? kind : "",
            isActive && question == null && (kind.equals("working") || kind.equals("slow")) ? activity : "", question, began,
            isActive && previous != null && previous.phoneOperation);
        Map<String, TaskState> updated = new LinkedHashMap<>(activeTasks);
        if (isActive) updated.put(task, next); else updated.remove(task);
        activeTasks = Collections.unmodifiableMap(updated);

        TaskState selected = activeTasks.get(currentTask);
        if (selected == null || selected.pending == null) {
            TaskState waiting = activeTasks.values().stream().filter(row -> row.pending != null).findFirst().orElse(null);
            if (waiting != null) selected = waiting;
        }
        if (selected == null) selected = isActive ? next : activeTasks.values().stream().findFirst().orElse(next);
        show(selected, !activeTasks.isEmpty());
    }

    private static void show(TaskState state, boolean isActive) {
        currentTask = state.task; currentPhase = state.phase; summary = state.summary;
        summaryKind = state.summaryKind; activityKind = state.activityKind; pending = state.pending; startedAt = state.startedAt; active = isActive;
    }

    private static String terminalSummary(String phase) {
        return phase.equals("completed") ? io.droyagent.app.NativeLocale.text("本轮执行已结束，点我查看结果", "This turn has ended. Tap me to view the result") : io.droyagent.app.NativeLocale.text("本轮执行已停止，点我查看任务", "This turn has stopped. Tap me to view the task");
    }

    /** Engine shutdown invalidates all pending questions and running phases together. */
    static synchronized void interruptAll() {
        activeTasks = Map.of();
        if (active) show(new TaskState(currentTask, "interrupted", terminalSummary("interrupted"), "", "", null,
            System.currentTimeMillis()), false);
    }

    /** Only an authorized foreground tool starts a phone-operation session; chat progress cannot start one. */
    static synchronized void phoneOperation(String task) { setPhoneOperation(task, true); }
    static synchronized void endScreenOperation(String task) { setPhoneOperation(task, false); }
    private static void setPhoneOperation(String task, boolean value) {
        TaskState previous = activeTasks.get(task);
        if (previous == null || previous.phoneOperation == value) return;
        Map<String, TaskState> updated = new LinkedHashMap<>(activeTasks);
        updated.put(task, new TaskState(previous.task, previous.phase, previous.summary, previous.summaryKind, previous.activityKind,
            previous.pending, previous.startedAt, value));
        activeTasks = Collections.unmodifiableMap(updated);
    }
    static synchronized void clearScreenOperations() {
        for (String task : activeTasks.keySet()) setPhoneOperation(task, false);
    }
    static synchronized boolean screenOperationActive() {
        return activeTasks.values().stream().anyMatch(task -> task.phoneOperation && task.pending == null
            && WORKING_PHASES.contains(task.phase));
    }

    private static final class TaskState {
        final String task, phase, summary, summaryKind, activityKind;
        final JSONObject pending;
        final long startedAt;
        final boolean phoneOperation;
        TaskState(String task, String phase, String summary, String summaryKind, String activityKind, JSONObject pending, long startedAt) {
            this(task, phase, summary, summaryKind, activityKind, pending, startedAt, false);
        }
        TaskState(String task, String phase, String summary, String summaryKind, String activityKind, JSONObject pending, long startedAt, boolean phoneOperation) {
            this.task = task; this.phase = phase; this.summary = summary; this.summaryKind = summaryKind; this.activityKind = activityKind;
            this.pending = pending; this.startedAt = startedAt; this.phoneOperation = phoneOperation;
        }
    }
    private static String boundedText(JSONObject source, String key, int max, boolean empty) throws org.json.JSONException {
        Object raw = source.get(key);
        if (!(raw instanceof String text) || (!empty && text.isBlank()) || text.codePointCount(0, text.length()) > max)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物消息无效：", "Invalid companion message: ") + key);
        return text;
    }
    static synchronized JSONObject current(Context context) {
        try {
            return new JSONObject().put("taskId", currentTask).put("phase", currentPhase)
                .put("active", active).put("startedAt", startedAt).put("summary", summary).put("summaryKind", summaryKind)
                .put("activityKind", activityKind).put("pending", pending == null ? JSONObject.NULL : new JSONObject(pending.toString()));
        } catch (org.json.JSONException impossible) { throw new AssertionError(impossible); }
    }
    synchronized JSONArray history() {
        try { return new JSONArray(preferences.getString("history", "[]")); }
        catch (org.json.JSONException error) { throw new IllegalStateException(io.droyagent.app.NativeLocale.text("操作状态记录无法读取", "Unable to read operation state record"), error); }
    }
    synchronized void recoverInterrupted() {
        interruptAll();
        JSONArray history = history();
        if (history.length() == 0) return;
        JSONObject last = history.optJSONObject(history.length() - 1);
        if (last != null && last.optString("state").equals("executing"))
            record(last.optString("action"), "interrupted", io.droyagent.app.NativeLocale.text("结果尚未确认；不会自动重放，请先核实当前状态", "The result is unconfirmed and will not be replayed automatically. Please check the current state"));
    }
    static String describe(Context context) {
        JSONArray history = new ToolProgress(context).history();
        if (history.length() == 0) return io.droyagent.app.NativeLocale.text("尚无手机工具执行记录", "No phone tool execution records yet");
        StringBuilder text = new StringBuilder();
        for (int i = Math.max(0, history.length() - 5); i < history.length(); i++) {
            JSONObject row = history.optJSONObject(i);
            if (row != null) text.append(title(row.optString("action"))).append(NativeLocale.text("：", ": ")).append(row.optString("outcome")).append('\n');
        }
        return text.toString().trim();
    }

    private static String title(String action) {
        return switch (action) {
            case "system_info" -> io.droyagent.app.NativeLocale.text("查询手机状态", "Query phone status");
            case "find_apps" -> io.droyagent.app.NativeLocale.text("查找应用", "Find apps");
            case "app_info" -> io.droyagent.app.NativeLocale.text("查询应用信息与权限", "Query app information and permissions");
            case "app_exit_info" -> io.droyagent.app.NativeLocale.text("查询应用退出原因", "Query app exit reasons");
            case "network_usage" -> io.droyagent.app.NativeLocale.text("查询应用网络流量", "Query app network usage");
            case "privacy_usage" -> io.droyagent.app.NativeLocale.text("查询麦克风与摄像头访问", "Query microphone and camera access");
            case "battery_usage" -> io.droyagent.app.NativeLocale.text("查询应用耗电", "Query app battery usage");
            case "process_memory" -> io.droyagent.app.NativeLocale.text("查看应用进程数据", "Inspect app process data");
            case "memory_scan" -> io.droyagent.app.NativeLocale.text("定位应用数据", "Locate app data");
            case "memory_read" -> io.droyagent.app.NativeLocale.text("读取应用数据", "Read app data");
            case "memory_write" -> io.droyagent.app.NativeLocale.text("单次修改应用数据", "Modify app data once");
            case "open_app" -> io.droyagent.app.NativeLocale.text("打开应用", "Open app");
            case "observe" -> io.droyagent.app.NativeLocale.text("读取页面", "Read page");
            case "screenshot" -> io.droyagent.app.NativeLocale.text("截取屏幕", "Capture screen");
            case "tap" -> io.droyagent.app.NativeLocale.text("点击页面", "Tap page");
            case "swipe" -> io.droyagent.app.NativeLocale.text("滑动页面", "Swipe page");
            case "input_text" -> io.droyagent.app.NativeLocale.text("填写文字", "Enter text");
            case "back" -> io.droyagent.app.NativeLocale.text("返回上一页", "Go back");
            case "node_action" -> io.droyagent.app.NativeLocale.text("操作页面控件", "Operate page controls");
            case "global_action" -> io.droyagent.app.NativeLocale.text("系统导航", "System navigation");
            case "open_settings" -> io.droyagent.app.NativeLocale.text("打开系统设置", "Open system settings");
            case "wait_for" -> io.droyagent.app.NativeLocale.text("等待页面变化", "Wait for page changes");
            case "settings_get" -> io.droyagent.app.NativeLocale.text("查询音量与亮度", "Query volume and brightness");
            case "settings_set" -> io.droyagent.app.NativeLocale.text("调整音量或亮度", "Adjust volume or brightness");
            case "settings_restore" -> io.droyagent.app.NativeLocale.text("恢复设置", "Restore settings");
            case "notifications_query" -> io.droyagent.app.NativeLocale.text("查询当前通知", "Query current notifications");
            case "calendar_query" -> io.droyagent.app.NativeLocale.text("查询日历", "Query calendar");
            case "contacts_query" -> io.droyagent.app.NativeLocale.text("查找联系人", "Find contacts");
            case "usage_query" -> io.droyagent.app.NativeLocale.text("查询应用使用情况", "Query app usage");
            case "file_grants" -> io.droyagent.app.NativeLocale.text("查看文件授权", "View file permissions");
            case "file_list" -> io.droyagent.app.NativeLocale.text("查看选定目录", "View selected directory");
            case "file_read" -> io.droyagent.app.NativeLocale.text("读取选定文件", "Read selected file");
            case "file_export" -> io.droyagent.app.NativeLocale.text("导出新文件", "Export new file");
            case "browser_status" -> io.droyagent.app.NativeLocale.text("查看浏览器状态", "View browser status");
            case "browser_open" -> io.droyagent.app.NativeLocale.text("打开网页", "Open web page");
            case "browser_observe" -> io.droyagent.app.NativeLocale.text("读取网页", "Read web page");
            case "browser_click" -> io.droyagent.app.NativeLocale.text("点击网页", "Click web page");
            case "browser_input" -> io.droyagent.app.NativeLocale.text("填写网页", "Enter web page text");
            case "browser_wait" -> io.droyagent.app.NativeLocale.text("等待网页", "Wait for web page");
            case "browser_back" -> io.droyagent.app.NativeLocale.text("返回上个网页", "Go to previous web page");
            case "compute_run" -> io.droyagent.app.NativeLocale.text("执行数据计算", "Run data computation");
            case "compute_jobs" -> io.droyagent.app.NativeLocale.text("查看计算记录", "View computation records");
            case "compute_artifact" -> io.droyagent.app.NativeLocale.text("读取计算产物", "Read computation artifacts");
            default -> io.droyagent.app.NativeLocale.text("工具操作", "Tool operation");
        };
    }
}
