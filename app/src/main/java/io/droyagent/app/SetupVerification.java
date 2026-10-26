package io.droyagent.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import io.droyagent.androiduse.DroyAccessibilityService;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs the ordinary authorized product actions against one nonce-bound local practice page. */
final class SetupVerification {
    private static final Object lock = new Object();
    private static String token;
    private static boolean running;
    private static String error = "";
    private static TestPageActivity page;
    private static boolean foreground;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private SetupVerification() {}

    static JSONObject status() throws Exception {
        synchronized (lock) { return new JSONObject().put("running", running).put("error", error); }
    }

    static JSONObject start(Activity owner) throws Exception {
        if (!RuntimeService.ready) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请等待运行环境就绪", "Please wait for the runtime to become ready"));
        if (ProductPermissions.stopped(owner)) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请先恢复 Android Use 操作", "Please resume Android Use operations first"));
        if (!DroyAccessibilityService.isConnected(owner)) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请先开启无障碍服务并等待实际连接", "Please enable accessibility and wait for the service to connect"));
        if (!ProductPermissions.allowed(ProductPermissions.preferences(owner), "allowObserve"))
            throw new IllegalStateException(io.droyagent.app.NativeLocale.text("练习验证需要页面观察权限", "Practice verification requires page observation permission"));
        final String challenge;
        synchronized (lock) {
            if (running) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("手机操作验证正在进行", "Phone operation verification is in progress"));
            token = challenge = UUID.randomUUID().toString(); running = true; error = "";
        }
        SetupManager.recordDeviceVerification(owner, new JSONObject().put("passed", false).put("channel", "none"));
        try {
            owner.startActivity(new Intent(owner, TestPageActivity.class).putExtra("setupVerification", challenge));
        } catch (RuntimeException failure) {
            synchronized (lock) { running = false; token = null; error = io.droyagent.app.NativeLocale.text("无法打开本地练习页，请重试", "Unable to open the local practice page. Please retry"); }
            throw failure;
        }
        new Thread(() -> run(owner.getApplicationContext(), challenge), "droy-setup-verify").start();
        return status();
    }

    static void opened(TestPageActivity activity, String challenge) {
        synchronized (lock) { if (running && challenge != null && challenge.equals(token)) { page = activity; foreground = true; } }
    }

    static void paused(TestPageActivity activity) {
        synchronized (lock) { if (page == activity) foreground = false; }
    }

    private static void check(String challenge) {
        synchronized (lock) {
            if (!running || !challenge.equals(token) || page == null || !foreground || page.isFinishing())
                throw new IllegalStateException(io.droyagent.app.NativeLocale.text("本地练习页已离开，手机操作验证已停止", "The local practice page was closed. Phone operation verification has stopped"));
        }
    }

    private static JSONObject observe(android.content.Context context, String challenge) throws Exception {
        check(challenge);
        JSONObject observed = RuntimeService.executeProductAction("observe", new JSONObject());
        check(challenge);
        if (!context.getPackageName().equals(observed.optString("packageName")))
            throw new IllegalStateException(io.droyagent.app.NativeLocale.text("当前页面不是 Droy 本地练习页，验证已停止", "This is not the Droy local practice page. Verification has stopped"));
        return observed;
    }

    private static JSONObject target(android.content.Context context, JSONObject observation, String id) throws Exception {
        JSONArray nodes = observation.getJSONArray("nodes");
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject node = nodes.getJSONObject(index);
            if (node.optString("resourceId").equals(context.getPackageName() + ":id/" + id)) return node;
        }
        throw new IllegalStateException(io.droyagent.app.NativeLocale.text("未找到本地练习控件，请重试", "Local practice controls were not found. Please retry"));
    }

    private static JSONObject arguments(android.content.Context context, JSONObject observation, String id) throws Exception {
        return new JSONObject().put("snapshotId", observation.getString("snapshotId"))
            .put("nodeId", target(context, observation, id).getString("nodeId"));
    }

    private static void run(android.content.Context context, String challenge) {
        boolean passed = false;
        try {
            long until = SystemClock.elapsedRealtime() + 8000;
            while (true) {
                synchronized (lock) { if (challenge.equals(token) && page != null && foreground && !MainActivity.isSensitiveUiVisible()) break; }
                if (SystemClock.elapsedRealtime() >= until) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("本地练习页未就绪，请重试", "The local practice page is not ready. Please retry"));
                Thread.sleep(80);
            }
            // The page starts with an empty input. An observed matching control
            // must exist before any product mutation can be issued.
            JSONObject before = observe(context, challenge);
            target(context, before, "test_input");
            String choices = SetupManager.status(context).getJSONObject("capabilities").toString();
            boolean control = ProductPermissions.allowed(ProductPermissions.preferences(context), "allowControl");
            if (control) {
                check(challenge);
                RuntimeService.executeProductAction("input_text", arguments(context, before, "test_input").put("text", "Droy 安装验证"));
                JSONObject entered = observe(context, challenge);
                check(challenge);
                RuntimeService.executeProductAction("tap", arguments(context, entered, "test_confirm"));
                JSONObject after = observe(context, challenge);
                if (!after.getString("text").contains("已确认：Droy 安装验证"))
                    throw new IllegalStateException(io.droyagent.app.NativeLocale.text("操作已返回，但练习结果未匹配，请核实后重试", "The operation returned, but the practice result did not match. Please verify and retry"));
            }
            if (!choices.equals(SetupManager.status(context).getJSONObject("capabilities").toString()))
                throw new IllegalStateException(io.droyagent.app.NativeLocale.text("验证期间能力选择发生变化，请按当前选择重新验证", "Capability selections changed during verification. Please verify the current selection again"));
            check(challenge);
            SetupManager.recordDeviceVerification(context, new JSONObject().put("passed", true).put("channel", "accessibility"));
            passed = true;
        } catch (Exception failure) {
            synchronized (lock) { error = failure instanceof IllegalStateException ? failure.getMessage() : io.droyagent.app.NativeLocale.text("手机操作验证未通过，请检查权限和连接后重试", "Phone operation verification failed. Please check permissions and connections, then retry"); }
        } finally {
            ToolProgress.endScreenOperation("product-user-action");
            ToolProgress.update("product-user-action", passed ? "completed" : "interrupted", false);
            RuntimeService.refreshExecutionProgress();
            final TestPageActivity closing;
            synchronized (lock) {
                closing = page; page = null; foreground = false; running = false; token = null;
                if (passed) error = "";
            }
            main.post(() -> { if (closing != null && !closing.isFinishing()) closing.finish(); });
        }
    }
}
