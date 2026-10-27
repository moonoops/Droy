package io.droyagent.app;

import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real service and bundled DSH process tests. No model requests or user-data reads. */
@RunWith(AndroidJUnit4.class)
public class RuntimeLifecycleDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;
    private boolean ownsRuntime;
    private SharedPreferences fixturePreferences;
    private String originalLanguage;
    private Boolean originalBackgroundStop;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Stop the existing engine before running lifecycle tests", RuntimeService.canManageHistory());
        assertFalse("The engine should initially be stopped", RuntimeService.ready);
        fixturePreferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        originalLanguage = fixturePreferences.getString("languagePreference", null);
        originalBackgroundStop = fixturePreferences.contains(BackgroundRuntime.STOPPED)
            ? fixturePreferences.getBoolean(BackgroundRuntime.STOPPED, false) : null;
        assertTrue(fixturePreferences.edit().putString("languagePreference", "zh").commit());
        activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        instrumentation.waitForIdleSync();
        await("Activity did not receive foreground window focus", 10000, () -> {
            boolean[] focused = new boolean[1];
            instrumentation.runOnMainSync(() -> focused[0] = activity.hasWindowFocus());
            return focused[0];
        });
        // Opening the app now starts DSH. Isolate each lifecycle scenario from
        // that startup before exercising its own explicit start/stop boundary.
        ownsRuntime = true;
        await("Opening the app did not create its automatic runtime service", 10000, () -> RuntimeService.instance != null);
        instrumentation.runOnMainSync(RuntimeService::requestStop);
        await("Automatic startup did not stop before the lifecycle scenario", 15000, RuntimeService::canManageHistory);
        assertStoppedPageRecoverable();
    }

    @After public void tearDown() throws Exception {
        try {
            if (ownsRuntime) {
                instrumentation.runOnMainSync(RuntimeService::requestStop);
                await("Test cleanup left an engine process or service running", 15000,
                        RuntimeService::canManageHistory);
            }
        } finally {
            try {
                if (activity != null) {
                    MainActivity closing = activity;
                    instrumentation.runOnMainSync(closing::finish);
                    await("Lifecycle fixture Activity was not destroyed after finish", 10000, () -> {
                        boolean[] destroyed = new boolean[1];
                        instrumentation.runOnMainSync(() -> destroyed[0] = closing.isDestroyed());
                        return destroyed[0];
                    });
                    instrumentation.waitForIdleSync();
                }
            } finally {
                if (fixturePreferences != null) {
                    SharedPreferences.Editor restore = fixturePreferences.edit();
                    if (originalLanguage == null) restore.remove("languagePreference");
                    else restore.putString("languagePreference", originalLanguage);
                    if (originalBackgroundStop == null) restore.remove(BackgroundRuntime.STOPPED);
                    else restore.putBoolean(BackgroundRuntime.STOPPED, originalBackgroundStop);
                    assertTrue("Could not restore lifecycle fixture settings", restore.commit());
                }
            }
        }
    }

    @Test public void notificationPausesToolsAndDiagnosticsStopsRealDsh() throws Exception {
        assertTrue("Grant notification permission before running this notification fixture",
            android.os.Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED);
        SharedPreferences settings = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        boolean hadPause = settings.contains(ProductPermissions.STOPPED);
        boolean previousPause = settings.getBoolean(ProductPermissions.STOPPED, false);
        try {
            RuntimeService.resumeOperations(context);
            RuntimeService service = startReady();
            Process child = child(service);
            ProductBridge bridge = bridge(service);
            int bridgePort = bridge.getListeningPort();
            int webPort = RuntimeService.webPort;
            assertTrue("Ready DSH must have a live child", child.isAlive());
            assertListening(bridgePort);
            assertListening(webPort);

            await("Ready engine did not show its standby state", 5000, () -> {
                Notification posted = runtimeNotificationForTest();
                return posted != null && isStandbyTitle(posted.extras.getCharSequence(Notification.EXTRA_TITLE));
            });
            Notification idle = runtimeNotificationForTest();
            assertNotNull("Ready engine did not publish its standby notification", idle);
            assertTrue("Standby notification should not have action buttons", idle.actions == null || idle.actions.length == 0);
            assertTrue(isStandbyTitle(idle.extras.getCharSequence(Notification.EXTRA_TITLE)));
            assertNull("Standby notification should not carry an empty expanded layout", idle.extras.getString(Notification.EXTRA_TEMPLATE));
            assertNull("Standby notification should not carry an empty detail line", idle.extras.getCharSequence(Notification.EXTRA_TEXT));
            ToolProgress.update("notification-pause-fixture", "executing", true);
            RuntimeService.refreshExecutionProgress();
            await("Active work did not publish its task action", 5000, () -> {
                Notification posted = runtimeNotificationForTest();
                return posted != null && posted.actions != null && posted.actions.length > 0;
            });

            Notification.Action pause = null;
            for (StatusBarNotification posted : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
                if (posted.getId() != 1 || !context.getPackageName().equals(posted.getPackageName())) continue;
                Notification.Action[] actions = posted.getNotification().actions;
                if (actions == null) continue;
                for (Notification.Action action : actions) {
                    assertFalse("Engine shutdown belongs in About & diagnostics", "停止任务和引擎".contentEquals(action.title));
                    if ("暂停手机操作".contentEquals(action.title)) pause = action;
                }
            }
            assertNotNull("The running service did not publish its pause action", pause);
            assertNotNull("Pause action has no PendingIntent", pause.actionIntent);
            assertEquals("Notification action must belong to this app", context.getPackageName(), pause.actionIntent.getCreatorPackage());
            pause.actionIntent.send();
            await("Notification did not pause tool operations", 5000, () -> ProductPermissions.stopped(context));
            // The preference is published before the main-thread notification
            // handler finishes stopping the policy. Observe the completed action.
            instrumentation.runOnMainSync(() -> {});
            assertOperationsStopped(service);
            assertTrue("Notification pause must preserve the conversation engine", RuntimeService.ready && child.isAlive());
            assertListening(webPort);

            DeviceUi ui = new DeviceUi(instrumentation, activity);
            ui.invoke("showDiagnostics");
            ui.nativeClick("停止任务和引擎");
            assertFullyStopped(service, child, bridgePort, webPort);
            assertNoRuntimeNotification();
        } finally {
            ToolProgress.interruptAll();
            RuntimeService.refreshExecutionProgress();
            SharedPreferences.Editor restore = settings.edit();
            if (hadPause) restore.putBoolean(ProductPermissions.STOPPED, previousPause); else restore.remove(ProductPermissions.STOPPED);
            assertTrue("Could not restore the original tool pause", restore.commit());
            RuntimeService.applySavedOperationState();
        }
    }

    @Test public void immediateStopDuringStartupCannotLeaveOrRelaunchChildOrBridge() throws Exception {
        requestStart();
        await("Service never entered startup", 10000, () -> RuntimeService.instance != null
                && booleanField(RuntimeService.instance, "starting"));
        RuntimeService service = RuntimeService.instance;
        assertNotNull(service);
        instrumentation.runOnMainSync(() -> {
            assertFalse("Startup finished before the cancellation window was exercised", RuntimeService.ready);
            assertTrue("Startup flag was not set", booleanField(service, "starting"));
            RuntimeService.requestStop();
        });
        await("Startup cancellation did not destroy the service", 15000, RuntimeService::canManageHistory);
        assertStoppedPageRecoverable();
        ExecutorService worker = worker(service);
        assertTrue("Startup worker did not terminate after cancellation", worker.awaitTermination(5, TimeUnit.SECONDS));
        Process child = child(service);
        ProductBridge bridge = bridge(service);
        if (child != null) assertFalse("Cancelled startup left a child process alive", child.isAlive());
        if (bridge != null) assertFalse("Cancelled startup left the bridge listening", bridge.isAlive());

        // A dead service alone is insufficient: a still-running installer worker
        // could otherwise launch a new child after onDestroy has returned.
        long deadline = SystemClock.elapsedRealtime() + 1000;
        do {
            assertTrue("Startup automatically resumed after stop", RuntimeService.canManageHistory());
            assertNull("A stopped service was automatically recreated", RuntimeService.instance);
            assertFalse("Stopped startup became ready", RuntimeService.ready);
            Process later = child(service);
            if (later != null) assertFalse("Late startup child escaped cancellation", later.isAlive());
            ProductBridge laterBridge = bridge(service);
            if (laterBridge != null) assertFalse("Late bridge escaped cancellation", laterBridge.isAlive());
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
    }

    @Test public void unexpectedChildExitIsVisibleAndManualRestartSucceeds() throws Exception {
        RuntimeService service = startReady();
        Process first = child(service);
        int firstBridgePort = bridge(service).getListeningPort();
        int firstWebPort = RuntimeService.webPort;
        first.destroyForcibly();
        assertFullyStopped(service, first, firstBridgePort, firstWebPort);
        assertTrue("Unexpected exit must have an explicit restartable status",
                RuntimeService.status.startsWith("引擎已退出（") && RuntimeService.status.endsWith("可重新启动"));
        assertStoppedPageRecoverable();
        assertNoRuntimeNotification();
        assertTrue("Unexpected exit retained the history maintenance lock", RuntimeService.beginHistoryChange());
        RuntimeService.endHistoryChange();

        RuntimeService restarted = startReady();
        Process second = child(restarted);
        assertNotSame("Restart reused the terminated child", first, second);
        assertTrue("Manual restart did not create a live DSH child", second.isAlive());
        int secondBridgePort = bridge(restarted).getListeningPort();
        int secondWebPort = RuntimeService.webPort;
        instrumentation.runOnMainSync(RuntimeService::requestStop);
        assertFullyStopped(restarted, second, secondBridgePort, secondWebPort);
    }

    @Test public void readyRunsAndNormalRestartsUseSavedChoicesWithoutPerRunPermission() throws Exception {
        SharedPreferences settings = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        Map<String, Boolean> saved = savedPermissionChoices(settings);
        try {
            assertTrue(settings.edit().remove(ProductPermissions.STOPPED).putBoolean("allowDeviceInfo", true)
                    .putBoolean("allowApps", false).commit());
            RuntimeService first = startReady();
            assertOperationsAvailable(first);
            assertSystemQueryWorks();
            assertAppSearchBlocked(first);
            Process firstChild = child(first);
            int firstBridgePort = bridge(first).getListeningPort();
            int firstWebPort = RuntimeService.webPort;
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            assertFullyStopped(first, firstChild, firstBridgePort, firstWebPort);
            assertFalse("Stopping the engine must not persist a user pause", ProductPermissions.stopped(context));
            assertOperationsStopped(first);

            RuntimeService restarted = startReady();
            assertNotSame("Restart must create a fresh service", first, restarted);
            assertOperationsAvailable(restarted);
            assertSystemQueryWorks();
            assertFalse("Restart reopened a capability the user disabled", ProductPermissions.allowed(settings, "allowApps"));
            assertAppSearchBlocked(restarted);
        } finally {
            stopAndRestorePermissionChoices(settings, saved);
        }
    }

    @Test public void explicitPauseCancelsQueuedWorkAndPersistsUntilManualResume() throws Exception {
        SharedPreferences settings = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        Map<String, Boolean> saved = savedPermissionChoices(settings);
        ExecutorService queued = Executors.newSingleThreadExecutor();
        CountDownLatch registered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            assertTrue(settings.edit().remove(ProductPermissions.STOPPED).putBoolean("allowDeviceInfo", true)
                    .putBoolean("allowApps", false).commit());
            RuntimeService first = startReady();
            assertOperationsAvailable(first);
            ControlPolicy.Ticket oldTicket = bridge(first).policy.begin("pause-lifecycle", "queued-before-pause");
            AtomicBoolean executed = new AtomicBoolean();
            Future<?> pending = queued.submit(() -> {
                registered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Queued fixture was not released");
                    oldTicket.check();
                    executed.set(true);
                } catch (InterruptedException stopped) { throw new java.util.concurrent.CompletionException(stopped); }
            });
            assertTrue("Queued action was not registered", registered.await(5, TimeUnit.SECONDS));
            DeviceUi ui = new DeviceUi(instrumentation, activity);
            ui.openPermissions();
            ui.click("[data-droy-action='pauseOperations']");
            await("User pause was not saved", 5000, () -> ProductPermissions.stopped(context));
            assertTrue("User pause must be saved", ProductPermissions.stopped(context));
            assertOperationsStopped(first);
            release.countDown();
            try { pending.get(5, TimeUnit.SECONDS); fail("A queued pre-pause action was executed"); }
            catch (ExecutionException cancelled) {
                assertTrue("Queued action failed for a reason other than cancellation",
                        cancelled.getCause() instanceof java.util.concurrent.CompletionException
                        && cancelled.getCause().getCause() instanceof InterruptedException);
            }
            assertFalse("Stopped queued action crossed the execution boundary", executed.get());

            Process firstChild = child(first);
            int firstBridgePort = bridge(first).getListeningPort();
            int firstWebPort = RuntimeService.webPort;
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            assertFullyStopped(first, firstChild, firstBridgePort, firstWebPort);
            first.allowOperations();
            assertTrue("A terminated service must not clear the saved pause", ProductPermissions.stopped(context));

            RuntimeService restarted = startReady();
            assertOperationsStopped(restarted);
            assertThrows(IllegalStateException.class, () -> RuntimeService.executeProductAction("system_info", new JSONObject()));
            ui.openPermissions();
            ui.click("[data-droy-action='resumeOperations']");
            await("Manual resume was not saved", 5000, () -> !ProductPermissions.stopped(context));
            assertFalse("Manual resume did not clear the saved pause", ProductPermissions.stopped(context));
            assertOperationsAvailable(restarted);
            assertThrows("Resume must not revive an old queued ticket", InterruptedException.class, oldTicket::check);
            assertFalse("Resume reopened a capability the user disabled", ProductPermissions.allowed(settings, "allowApps"));
            assertAppSearchBlocked(restarted);
            assertSystemQueryWorks();
        } finally {
            release.countDown();
            queued.shutdownNow();
            try { assertTrue("Queued fixture worker remained active", queued.awaitTermination(5, TimeUnit.SECONDS)); }
            finally { stopAndRestorePermissionChoices(settings, saved); }
        }
    }

    private static Map<String, Boolean> savedPermissionChoices(SharedPreferences settings) {
        Map<String, Boolean> saved = new HashMap<>();
        for (String key : new String[]{"allowDeviceInfo", "allowApps", ProductPermissions.STOPPED})
            if (settings.contains(key)) saved.put(key, settings.getBoolean(key, false));
        return saved;
    }

    private void stopAndRestorePermissionChoices(SharedPreferences settings, Map<String, Boolean> saved) throws Exception {
        instrumentation.runOnMainSync(RuntimeService::requestStop);
        try { await("Permission test left the runtime running", 15000, RuntimeService::canManageHistory); }
        finally {
            SharedPreferences.Editor restore = settings.edit();
            for (String key : new String[]{"allowDeviceInfo", "allowApps", ProductPermissions.STOPPED}) {
                if (saved.containsKey(key)) restore.putBoolean(key, saved.get(key)); else restore.remove(key);
            }
            assertTrue("Could not restore product test preferences", restore.commit());
        }
    }

    private static void assertOperationsAvailable(RuntimeService service) {
        assertFalse("Ready runtime should use saved choices without a per-run allow action", bridge(service).policy.isStopped());
    }

    private static void assertSystemQueryWorks() throws Exception {
        JSONObject result = RuntimeService.executeProductAction("system_info", new JSONObject()
                .put("sections", new JSONArray().put("battery")));
        assertEquals("queried", result.getString("status"));
        assertTrue("Ready engine did not actually dispatch a native query", result.getJSONObject("sections").has("battery"));
    }

    private static void assertAppSearchBlocked(RuntimeService service) {
        try {
            assertThrows("A saved individual denial must still be enforced", SecurityException.class,
                    () -> bridge(service).executeTool("find_apps", new JSONObject().put("query", "fixture"),
                            "denied-lifecycle", java.util.UUID.randomUUID().toString()));
        } finally { bridge(service).policy.end("denied-lifecycle"); }
    }

    @Test public void bootstrapFailureReleasesServiceAndPreservesReasonAfterDestroy() throws Exception {
        File runtime = RuntimeInstaller.install(context);
        Path bootstrap = new File(runtime, "mobile/bootstrap.mjs").toPath();
        Path backup = new File(runtime, "mobile/bootstrap.lifecycle-test-" + java.util.UUID.randomUUID()).toPath();
        assertTrue("Bundled bootstrap is missing before fault injection", Files.isRegularFile(bootstrap));
        // Only the bundled executable fixture is moved; session and user files
        // remain untouched. The original bootstrap is restored before restart.
        Files.move(bootstrap, backup);
        try {
            requestStart();
            await("Failing service was not created", 10000, () -> RuntimeService.instance != null);
            RuntimeService failed = RuntimeService.instance;
            await("Bootstrap failure was not surfaced", 30000,
                    () -> RuntimeService.status.startsWith("启动失败：运行配置初始化失败（"));
            await("Failed startup retained its service or process", 15000, RuntimeService::canManageHistory);
            assertNull("Failed startup retained its service instance", RuntimeService.instance);
            assertFalse("Failed startup became ready", RuntimeService.ready);
            assertStoppedPageRecoverable();
            assertNotNull("Fault injection did not launch the real bootstrap process", child(failed));
            assertFalse("Failed bootstrap process remained alive", child(failed).isAlive());
            assertFalse("Failed startup retained its native bridge", bridge(failed).isAlive());
            assertTrue("Failed startup worker remained active", worker(failed).awaitTermination(5, TimeUnit.SECONDS));
            assertTrue("Service destruction hid the bootstrap failure",
                    RuntimeService.status.startsWith("启动失败：运行配置初始化失败（"));
            assertTrue("Service destruction discarded failure diagnostics",
                    RuntimeService.diagnosticsSnapshot().contains("运行配置初始化失败（"));
            assertNoRuntimeNotification();
            assertTrue("Failed startup retained the history maintenance lock", RuntimeService.beginHistoryChange());
            RuntimeService.endHistoryChange();
        } finally {
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            try {
                await("Failed-start cleanup left runtime active", 15000, RuntimeService::canManageHistory);
            } finally {
                Files.move(backup, bootstrap);
            }
        }
        RuntimeService recovered = startReady();
        assertEquals("Bootstrap recovery must honor the saved user pause", ProductPermissions.stopped(context),
                bridge(recovered).policy.isStopped());
    }

    @Test public void continuousModeSurvivesTaskRemovalAndExplicitStopStaysStopped() throws Exception {
        SharedPreferences preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        Map<String, Boolean> saved = backgroundPreferences(preferences);
        try {
            preferences.edit().putBoolean(BackgroundRuntime.ENABLED, true)
                .putBoolean("notificationManagementEnabled", false).apply();
            RuntimeService service = startReady();
            Process child = child(service);
            int removedTaskId = activity.getTaskId();
            instrumentation.runOnMainSync(() -> {
                assertEquals(android.app.Service.START_STICKY, service.onStartCommand(null, 0, 99));
                activity.finishAndRemoveTask();
            });
            await("App did not leave the foreground", 5000, () -> !BackgroundRuntime.visible());
            await("Android retained the app task after removal", 5000,
                () -> context.getSystemService(android.app.ActivityManager.class).getAppTasks().stream()
                    .noneMatch(task -> task.getTaskInfo().taskId == removedTaskId));
            SystemClock.sleep(1400);
            assertSame(service, RuntimeService.instance);
            assertTrue("Task removal stopped the background engine", RuntimeService.ready && child.isAlive());
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            await("Explicit stop did not finish", 15000, RuntimeService::canManageHistory);
            BackgroundRuntime.settingsChanged(context);
            SystemClock.sleep(1400);
            assertNull("Background configuration overrode explicit stop", RuntimeService.instance);
            assertTrue(BackgroundRuntime.explicitlyStopped(context));
        } finally { restoreBackgroundPreferences(preferences, saved); }
    }

    @Test public void disablingContinuousModeFinishesActiveWorkBeforeLeavingBackground() throws Exception {
        SharedPreferences preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        Map<String, Boolean> saved = backgroundPreferences(preferences);
        try {
            preferences.edit().putBoolean(BackgroundRuntime.ENABLED, false)
                .putBoolean("notificationManagementEnabled", false).apply();
            RuntimeService service = startReady();
            ToolProgress.update("background-fixture", "executing", true);
            instrumentation.runOnMainSync(() -> {
                activity.moveTaskToBack(true);
                RuntimeService.refreshExecutionProgress();
            });
            await("App did not leave the foreground", 5000, () -> !BackgroundRuntime.visible());
            SystemClock.sleep(1400);
            assertSame("Disabling standby interrupted active work", service, RuntimeService.instance);
            assertTrue(RuntimeService.ready);
            ToolProgress.update("background-fixture", "completed", false);
            RuntimeService.refreshExecutionProgress();
            await("Completed background work retained idle runtime", 15000, RuntimeService::canManageHistory);
            assertFalse("Idle release must not become a user stop", BackgroundRuntime.explicitlyStopped(context));
        } finally {
            ToolProgress.interruptAll();
            restoreBackgroundPreferences(preferences, saved);
        }
    }

    private static Map<String, Boolean> backgroundPreferences(SharedPreferences preferences) {
        Map<String, Boolean> saved = new HashMap<>();
        for (String key : new String[]{BackgroundRuntime.ENABLED, BackgroundRuntime.STOPPED, "notificationManagementEnabled"})
            if (preferences.contains(key)) saved.put(key, preferences.getBoolean(key, false));
        return saved;
    }

    private static void restoreBackgroundPreferences(SharedPreferences preferences, Map<String, Boolean> saved) {
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : new String[]{BackgroundRuntime.ENABLED, BackgroundRuntime.STOPPED, "notificationManagementEnabled"}) {
            if (saved.containsKey(key)) editor.putBoolean(key, saved.get(key)); else editor.remove(key);
        }
        editor.apply();
    }

    private void requestStart() {
        ownsRuntime = true;
        instrumentation.runOnMainSync(() -> {
            assertTrue("Engine must be started from our visible activity", activity.hasWindowFocus());
            context.startForegroundService(new Intent(context, RuntimeService.class));
        });
    }

    private RuntimeService startReady() throws Exception {
        requestStart();
        await("Requested service was not created", 10000, () -> RuntimeService.instance != null);
        RuntimeService service = RuntimeService.instance;
        await("Real bundled DSH did not become ready", 90000, () -> RuntimeService.ready
                || field(service, "terminalStatus") != null);
        assertTrue("Real DSH startup failed: " + RuntimeService.status, RuntimeService.ready);
        assertSame("Ready state belongs to a different runtime", service, RuntimeService.instance);
        assertNotNull("Ready service disappeared", service);
        assertNotNull("Ready service has no DSH process", child(service));
        assertNotNull("Ready service has no native bridge", bridge(service));
        await("Ready runtime did not apply the saved user stop state", 5000,
                () -> bridge(service).policy.isStopped() == ProductPermissions.stopped(context));
        return service;
    }

    private void assertFullyStopped(RuntimeService service, Process child, int bridgePort, int webPort) throws Exception {
        await("Stop did not finish service and child shutdown", 15000, RuntimeService::canManageHistory);
        assertNull("RuntimeService remained active", RuntimeService.instance);
        assertFalse("Runtime stayed ready after stop", RuntimeService.ready);
        assertFalse("DSH child remained alive after stop", child.isAlive());
        assertTrue("Stopped worker remained active", worker(service).awaitTermination(5, TimeUnit.SECONDS));
        assertFalse("Native bridge remained active after stop", bridge(service).isAlive());
        assertClosed(bridgePort);
        assertClosed(webPort);
    }

    private Notification runtimeNotificationForTest() {
        for (StatusBarNotification posted : context.getSystemService(NotificationManager.class).getActiveNotifications())
            if (posted.getId() == 1 && context.getPackageName().equals(posted.getPackageName())) return posted.getNotification();
        return null;
    }

    private static boolean isStandbyTitle(CharSequence title) {
        return title != null && java.util.Set.of("Droy · 待命", "Droy · 正在无所事事", "Droy · 随时听你安排")
            .contains(title.toString());
    }

    private void assertNoRuntimeNotification() throws Exception {
        await("Foreground runtime notification remained after termination", 5000, () -> {
            for (StatusBarNotification posted : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
                if (posted.getId() == 1) return false;
            }
            return true;
        });
    }

    private void assertStoppedPageRecoverable() throws Exception {
        DeviceUi ui = new DeviceUi(instrumentation, activity);
        await("A stopped or failed engine left its loading cover over recovery", 5000, () -> {
            if (ui.loadingOverlayShown()) return false;
            AtomicBoolean actionable = new AtomicBoolean();
            instrumentation.runOnMainSync(() -> {
                android.widget.Button restart = DeviceUi.findButton(activity.getWindow().getDecorView(), "启动并进入对话");
                actionable.set(restart != null && restart.isShown() && restart.isEnabled());
            });
            return actionable.get();
        });
    }

    private static void assertOperationsStopped(RuntimeService service) {
        ControlPolicy policy = bridge(service).policy;
        assertTrue("Stopped tools must reject new operations", policy.isStopped());
        try {
            policy.begin("lifecycle-test", "must-stay-blocked");
            fail("Stopped policy admitted a phone action");
        } catch (IllegalStateException expected) {
            assertEquals("Wrong policy rejection", "Android Use 操作已暂停，请在权限设置中恢复", expected.getMessage());
        }
    }

    private static void assertListening(int port) throws Exception {
        assertTrue("Expected a bound local service port", port > 0);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
        }
    }

    private static void assertClosed(int port) {
        assertTrue("Expected a previously bound service port", port > 0);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            fail("Stopped service port still accepts connections");
        } catch (java.io.IOException expected) {
            // A connection refusal proves the listener is gone without reading data.
        }
    }

    private static Process child(RuntimeService service) { return (Process) field(service, "process"); }
    private static ProductBridge bridge(RuntimeService service) { return (ProductBridge) field(service, "bridge"); }
    private static ExecutorService worker(RuntimeService service) { return (ExecutorService) field(service, "worker"); }
    private static boolean booleanField(RuntimeService service, String name) { return (Boolean) field(service, name); }

    private static Object field(RuntimeService service, String name) {
        try {
            Field field = RuntimeService.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(service);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Runtime lifecycle test contract changed: " + name, error);
        }
    }

    private static void await(String failure, long timeout, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do {
            if (condition.check()) return;
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(failure);
    }

    private interface Condition { boolean check() throws Exception; }
}
