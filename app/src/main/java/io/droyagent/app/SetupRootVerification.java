package io.droyagent.app;

import android.app.Activity;
import android.content.Context;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.RootAuthorizationProbe;
import org.json.JSONObject;

/** Keeps an explicit Root check alive while the user responds in their Root manager. */
final class SetupRootVerification {
    private static final Object LOCK = new Object();
    private static boolean running;
    private static boolean passed;
    private static String error = "";
    private static long checkedAt;

    private SetupRootVerification() {}

    static JSONObject status() throws Exception {
        synchronized (LOCK) {
            return new JSONObject().put("running", running).put("passed", passed).put("error", error).put("checkedAt", checkedAt);
        }
    }

    static JSONObject start(Activity owner) throws Exception {
        Context context = owner.getApplicationContext();
        checkPolicy(context);
        synchronized (LOCK) {
            if (running) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("Root 验证正在进行，请先在 Root 管理器中确认", "Root verification is in progress. Please confirm in your Root manager first"));
            running = true; passed = false; error = "";
        }
        try {
            SetupManager.recordRootVerification(context, false);
            Thread worker = new Thread(() -> run(context), "droy-setup-root");
            worker.start();
        } catch (Exception failure) {
            synchronized (LOCK) { running = false; error = io.droyagent.app.NativeLocale.text("无法开始 Root 验证，请重试", "Unable to start Root verification. Please retry"); }
            throw new IllegalStateException(io.droyagent.app.NativeLocale.text("无法开始 Root 验证，请重试", "Unable to start Root verification. Please retry"));
        }
        return status();
    }

    private static void checkPolicy(Context context) {
        if (ProductPermissions.stopped(context)) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请先恢复 Android Use 操作", "Please resume Android Use operations first"));
        if (!ProductPermissions.allowed(ProductPermissions.preferences(context), "allowRoot"))
            throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请先明确开启 Root 增强能力", "Please explicitly enable Root capabilities first"));
    }

    private static void run(Context context) {
        boolean authorized = false;
        String message = "";
        try {
            Cancellation cancellation = () -> {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Root verification cancelled");
                checkPolicy(context);
            };
            authorized = RootAuthorizationProbe.verify(cancellation);
            cancellation.check();
            if (!authorized) message = io.droyagent.app.NativeLocale.text("Root 管理器未授予 Droy Root 权限，请允许后重试", "Your Root manager has not granted Droy Root access. Please allow it and retry");
            SetupManager.recordRootVerification(context, authorized);
        } catch (Exception failure) {
            authorized = false;
            message = failure instanceof ActionException action && "root_timeout".equals(action.getCode())
                ? io.droyagent.app.NativeLocale.text("Root 授权等待超时，请在 Root 管理器确认后重试", "Root authorization timed out. Confirm in your Root manager, then retry")
                : failure instanceof java.io.IOException ? io.droyagent.app.NativeLocale.text("没有找到可用的 Root 服务，请检查 Root 环境", "No usable Root service was found. Please check the Root environment")
                : failure instanceof InterruptedException || failure instanceof IllegalStateException
                    ? io.droyagent.app.NativeLocale.text("Root 验证已停止，请检查能力开关和暂停状态后重试", "Root verification stopped. Check capability switches and pause state, then retry")
                    : io.droyagent.app.NativeLocale.text("Root 验证未完成，请检查授权后重试", "Root verification is incomplete. Please check authorization and retry");
            try { SetupManager.recordRootVerification(context, false); }
            catch (Exception persistenceFailure) { message = io.droyagent.app.NativeLocale.text("Root 验证结果未保存，请重新检查", "Root verification result was not saved. Please check again"); }
        } finally {
            synchronized (LOCK) {
                running = false; passed = authorized; error = message; checkedAt = System.currentTimeMillis();
            }
        }
    }
}
