package io.droyagent.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.lifecycle.ViewModelProvider;
import io.droyagent.app.nativeui.setup.NativeOnboardingViewModel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Requires launcherIconFixture=true and changes only the development app's launcher state. */
@RunWith(AndroidJUnit4.class)
public final class LauncherIconRotationDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private LauncherIconRotation.AndroidComponents components;
    private SharedPreferences production, fixture;
    private String fixtureName;
    private int[] originalComponents;
    private Map<String, ?> originalPreferences;
    private final AtomicInteger gatewayCreated = new AtomicInteger(), gatewayDestroyed = new AtomicInteger();
    private final Application.ActivityLifecycleCallbacks gatewayObserver = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityCreated(Activity activity, Bundle state) {
            if (activity instanceof LauncherEntryActivity) gatewayCreated.incrementAndGet();
        }
        @Override public void onActivityDestroyed(Activity activity) {
            if (activity instanceof LauncherEntryActivity) gatewayDestroyed.incrementAndGet();
        }
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityResumed(Activity activity) {}
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    };
    private boolean observingGateways;

    @Before public void preserveLauncherState() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assumeTrue("Launcher rotation tests require the independent development package",
            context.getPackageName().equals("io.droyagent.app.dev"));
        assumeTrue("Explicit launcherIconFixture=true is required",
            "true".equals(InstrumentationRegistry.getArguments().getString("launcherIconFixture")));
        assumeTrue("Custom branding intentionally disables random launcher changes", BuildConfig.DROY_RANDOM_LAUNCHER_ICONS);
        components = new LauncherIconRotation.AndroidComponents(context);
        originalComponents = components.states();
        production = context.getSharedPreferences(LauncherIconRotation.PREFERENCES, Context.MODE_PRIVATE);
        originalPreferences = new HashMap<>(production.getAll());
        // Real alias opens must not make unseeded probability draws during this deterministic fixture.
        new LauncherIconRotation.PreferenceStore(production).complete(System.currentTimeMillis());
        fixtureName = "launcher-icons-fixture-" + UUID.randomUUID();
        fixture = context.getSharedPreferences(fixtureName, Context.MODE_PRIVATE);
        ((Application) context.getApplicationContext()).registerActivityLifecycleCallbacks(gatewayObserver);
        observingGateways = true;
    }

    @After public void restoreLauncherState() {
        if (observingGateways) ((Application) context.getApplicationContext()).unregisterActivityLifecycleCallbacks(gatewayObserver);
        if (originalComponents != null) components.applyStates(originalComponents);
        if (originalPreferences != null) {
            SharedPreferences.Editor editor = production.edit().clear();
            for (Map.Entry<String, ?> value : originalPreferences.entrySet()) {
                if (value.getValue() instanceof Long number) editor.putLong(value.getKey(), number);
                else if (value.getValue() instanceof Integer number) editor.putInt(value.getKey(), number);
                else throw new AssertionError("Unexpected launcher state type: " + value.getKey());
            }
            assertTrue("Could not restore launcher preferences", editor.commit());
        }
        if (fixtureName != null) context.deleteSharedPreferences(fixtureName);
    }

    @Test public void allNineAliasesKeepOneLauncherAndTheStableUiTaskThroughEverySwitch() {
        components.select(0);
        NativeAppActivity activity = launchAlias(0);
        int task = activity.getTaskId(), pid = Process.myPid();
        assertStableTask(activity, task);
        LauncherIconRotation.PreferenceStore store = new LauncherIconRotation.PreferenceStore(fixture);
        for (int step = 1; step <= 9; step++) {
            int current = LauncherIconRotation.uniqueIcon(components.enabledMask());
            int target = step % 9;
            int alternative = target < current ? target : target - 1;
            long now = 1_800_000_000_000L + step * LauncherIconPolicy.MIN_SWITCH_INTERVAL_MILLIS;
            assertTrue("The forced probability hit did not switch icons", LauncherIconRotation.maybeSwitch(
                components, store, now, bound -> bound == 100 ? 0 : alternative));
            assertOnlyLauncher(target);
            assertEquals(pid, Process.myPid());
            assertStableTask(activity, task);

            // Each icon is opened through its real manifest alias, including the just-disabled old alias's task.
            NativeAppActivity reopened = launchAlias(target);
            assertSame("Icon changes must not recreate or finish the open app", activity, reopened);
            assertStableTask(reopened, task);
            LauncherIconRotation.PreferenceStore reopenedStore = new LauncherIconRotation.PreferenceStore(
                context.getSharedPreferences(fixtureName, Context.MODE_PRIVATE));
            assertEquals(now, reopenedStore.lastSwitchMillis());
            assertFalse("Persisted cooldown allowed another switch before one hour", LauncherIconRotation.maybeSwitch(
                components, reopenedStore, now + LauncherIconPolicy.MIN_SWITCH_INTERVAL_MILLIS - 1,
                bound -> { throw new AssertionError("Cooldown must not draw random values"); }));
            assertOnlyLauncher(target);
        }
    }

    @Test public void recreationHistoryAndInternalIntentsDoNotCountAsDesktopOpens() {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        assertTrue(LauncherEntryActivity.isFreshLauncherOpen(launcher, false));
        assertFalse(LauncherEntryActivity.isFreshLauncherOpen(launcher, true));
        assertFalse(LauncherEntryActivity.isFreshLauncherOpen(new Intent(launcher)
            .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY), false));
        assertFalse(LauncherEntryActivity.isFreshLauncherOpen(new Intent(context, NativeAppActivity.class), false));
        assertFalse(LauncherEntryActivity.isFreshLauncherOpen(new Intent(Intent.ACTION_MAIN), false));
    }

    @Test public void android12RootBackBackgroundsAndReopensTheSameNativeTask() {
        assumeTrue("Android 11 retains its original Back behavior", Build.VERSION.SDK_INT >= 31);
        components.select(0);
        NativeAppActivity activity = launchAlias(0);
        AtomicReference<NativeOnboardingViewModel> model = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> model.set(new ViewModelProvider(activity).get(NativeOnboardingViewModel.class)));
        SharedPreferences installation = context.getSharedPreferences("installation", Context.MODE_PRIVATE);
        boolean hadCompleted = installation.contains("completed"), wasCompleted = installation.getBoolean("completed", false);
        try {
            // The explicit development fixture changes only this local routing flag, then restores it.
            // Production setup verification and the higher-priority onboarding BackHandler stay intact.
            assertTrue(installation.edit().putBoolean("completed", true).commit());
            refreshSetup(model.get());
            awaitSetup(model.get(), false);
            int task = activity.getTaskId();
            assertTrue("Fixture requires the task's root screen", activity.isTaskRoot());
            instrumentation.runOnMainSync(() -> activity.getOnBackPressedDispatcher().onBackPressed());
            long deadline = SystemClock.uptimeMillis() + 10_000;
            AtomicReference<Stage> stage = new AtomicReference<>();
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync(() -> stage.set(ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity)));
                if (stage.get() == Stage.STOPPED) break;
                SystemClock.sleep(50);
            }
            assertEquals("Root Back must background the app", Stage.STOPPED, stage.get());
            assertStableTask(activity, task);
            assertSame(activity, launchAlias(0));
            assertStableTask(activity, task);
        } finally {
            SharedPreferences.Editor restore = installation.edit();
            if (hadCompleted) restore.putBoolean("completed", wasCompleted); else restore.remove("completed");
            assertTrue("Could not restore setup routing", restore.commit());
            refreshSetup(model.get());
            awaitSetup(model.get(), !wasCompleted);
        }
    }

    private void refreshSetup(NativeOnboardingViewModel model) {
        instrumentation.runOnMainSync(() -> { model.stopObserving(); model.startObserving(); });
    }

    private void awaitSetup(NativeOnboardingViewModel model, boolean required) {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (Boolean.valueOf(required).equals(model.getState().getValue().getRequiresSetup())) {
                SystemClock.sleep(150);
                instrumentation.waitForIdleSync();
                return;
            }
            SystemClock.sleep(50);
        }
        fail("Setup routing did not reach fixture state: required=" + required);
    }

    private NativeAppActivity launchAlias(int index) {
        int createdBefore = gatewayCreated.get(), destroyedBefore = gatewayDestroyed.get();
        Intent launch = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(components.name(index)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        instrumentation.runOnMainSync(() -> context.startActivity(launch));
        instrumentation.waitForIdleSync();
        long deadline = SystemClock.uptimeMillis() + 15_000;
        AtomicReference<NativeAppActivity> found = new AtomicReference<>();
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync(() -> {
                for (Activity candidate : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                    if (candidate instanceof NativeAppActivity nativeApp && !nativeApp.isFinishing()) found.set(nativeApp);
                }
            });
            if (found.get() != null && gatewayCreated.get() > createdBefore && gatewayDestroyed.get() > destroyedBefore)
                return found.get();
            SystemClock.sleep(50);
        }
        throw new AssertionError("Launcher alias did not open the native app: " + components.name(index));
    }

    private void assertOnlyLauncher(int expected) {
        assertEquals(1 << expected, components.enabledMask());
        PackageManager packages = context.getPackageManager();
        List<ResolveInfo> launchers = packages.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.getPackageName()), 0);
        assertEquals("Exactly one launcher entry must be visible", 1, launchers.size());
        assertEquals(LauncherIconRotation.ALIAS_NAMES[expected], launchers.get(0).activityInfo.name);
        assertNotNull(launchers.get(0).loadIcon(packages));
        assertEquals(components.name(expected), packages.getLaunchIntentForPackage(context.getPackageName()).getComponent());
    }

    private void assertStableTask(NativeAppActivity activity, int expectedTask) {
        assertFalse("Changing the launcher alias finished the app", activity.isFinishing());
        assertFalse(activity.isDestroyed());
        assertEquals(expectedTask, activity.getTaskId());
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        for (ActivityManager.AppTask task : manager.getAppTasks()) {
            ActivityManager.RecentTaskInfo info = task.getTaskInfo();
            if (info.taskId == expectedTask) {
                assertEquals(new ComponentName(context, NativeAppActivity.class), info.baseIntent.getComponent());
                return;
            }
        }
        fail("The stable native app task disappeared after switching icons");
    }
}
