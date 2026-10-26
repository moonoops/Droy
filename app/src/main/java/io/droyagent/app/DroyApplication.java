package io.droyagent.app;

import android.app.Application;
import android.app.Activity;
import android.app.NotificationManager;
import android.os.Bundle;
import android.os.Build;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import io.droyagent.androiduse.AccessibilityRecovery;

public final class DroyApplication extends Application {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // The compute service has an isolated UID and cannot read app files.
        if (android.os.Process.myUid() == getApplicationInfo().uid) NativeLocale.initialize(this);
    }

    @Override public void onCreate() {
        super.onCreate();
        // Also initialized when Android binds accessibility before opening the UI.
        if (getPackageName().equals(Application.getProcessName())) {
            SetupManager.initialize(this);
            AccessibilityRecovery.get(this);
            MemoryRuntime memory = MemoryRuntime.get(this);
            IntentFilter packages = new IntentFilter();
            packages.addAction(Intent.ACTION_PACKAGE_ADDED);
            packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
            packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
            packages.addDataScheme("package");
            BroadcastReceiver notificationApps = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    NotificationManagement.synchronizeInstalledApps(context);
                }
            };
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(notificationApps, packages, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(notificationApps, packages);
            IntentFilter notificationState = new IntentFilter(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED);
            notificationState.addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(notificationApps, notificationState, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(notificationApps, notificationState);
            registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity activity) {
                    PetOverlay.foreground(activity, true); AutomationScreenOverlay.foreground(activity, true);
                    memory.activityResumed(activity);
                }
                @Override public void onActivityPaused(Activity activity) {
                    PetOverlay.foreground(activity, false); AutomationScreenOverlay.foreground(activity, false);
                    memory.activityPaused(activity);
                }
                @Override public void onActivityCreated(Activity activity, Bundle state) {}
                @Override public void onActivityStarted(Activity activity) {
                    if (activity instanceof MainActivity || activity instanceof TestPageActivity
                            && activity.getIntent().hasExtra("setupVerification")) BackgroundRuntime.activityStarted(activity);
                }
                @Override public void onActivityStopped(Activity activity) {
                    if (activity instanceof MainActivity || activity instanceof TestPageActivity
                            && activity.getIntent().hasExtra("setupVerification")) BackgroundRuntime.activityStopped();
                }
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
                @Override public void onActivityDestroyed(Activity activity) {}
            });
        }
    }
}
