package io.droyagent.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Product preference and foreground-service lifecycle share one stop decision. */
final class BackgroundRuntime {
    static final String ENABLED = "backgroundRuntimeEnabled";
    static final String STOPPED = "backgroundRuntimeStopped";
    private static int visibleActivities;

    private BackgroundRuntime() {}

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("product", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context) { return preferences(context).getBoolean(ENABLED, false); }
    static boolean explicitlyStopped(Context context) { return preferences(context).getBoolean(STOPPED, false); }

    static boolean keepRunning(Context context) {
        SharedPreferences preferences = preferences(context);
        return shouldKeepRunning(preferences.getBoolean(ENABLED, false),
            NotificationManagement.enabled(context), preferences.getBoolean(STOPPED, false));
    }

    static boolean shouldKeepRunning(boolean enabled, boolean notificationManagement, boolean stopped) {
        return !stopped && (enabled || notificationManagement);
    }

    static JSONObject state(Context context) throws org.json.JSONException {
        return new JSONObject().put(ENABLED, enabled(context))
            .put("backgroundRuntimeRunning", RuntimeService.ready && keepRunning(context));
    }

    static void setEnabled(Context context, boolean enabled) {
        SharedPreferences.Editor editor = preferences(context).edit().putBoolean(ENABLED, enabled);
        if (enabled) editor.putBoolean(STOPPED, false);
        if (!editor.commit()) throw new IllegalStateException(NativeLocale.text(
            "后台运行设置未保存，请重试", "Background runtime settings were not saved. Please try again"));
        settingsChanged(context);
    }

    static void settingsChanged(Context context) {
        if (keepRunning(context)) {
            context.startForegroundService(new Intent(context, RuntimeService.class).setAction(RuntimeService.REFRESH));
        } else RuntimeService.refreshBackgroundPolicy();
    }

    static void startedByUser(Context context) { preferences(context).edit().putBoolean(STOPPED, false).apply(); }
    static void stoppedByUser(Context context) { preferences(context).edit().putBoolean(STOPPED, true).apply(); }

    static synchronized boolean visible() { return visibleActivities > 0; }

    static synchronized void activityStarted(Context context) { activityStarted(context, true); }

    static synchronized void activityStarted(Context context, boolean startIfAllowed) {
        visibleActivities++;
        VoiceWake.visibilityChangedIfCreated();
        if (startIfAllowed && !explicitlyStopped(context) && RuntimeService.canStart())
            context.startForegroundService(new Intent(context, RuntimeService.class).setAction(RuntimeService.REFRESH));
        RuntimeService.refreshBackgroundPolicy();
    }

    static synchronized void activityStopped() {
        visibleActivities--;
        VoiceWake.visibilityChangedIfCreated();
        RuntimeService.refreshBackgroundPolicy();
    }
}
