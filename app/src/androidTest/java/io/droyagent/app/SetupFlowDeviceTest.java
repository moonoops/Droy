package io.droyagent.app;

import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Fresh-install checks in an emulator or an explicitly selected development-app fixture. */
@RunWith(AndroidJUnit4.class)
public final class SetupFlowDeviceTest {
    private static final String ROOT = "[data-droy-setup]";
    private static final String NEXT = "[data-droy-setup-next]";
    private static final String MODEL_TEST = "[data-droy-setup-model-test]";
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences product, installation, wake;
    private Map<String, ?> originalProduct, originalInstallation, originalWake;
    private MainActivity activity;
    private DeviceUi ui;
    private File home, preserved, preservedPreferences;
    private Map<String, String> originalFiles;
    private boolean owned, hadHome, rotated, passed, notificationShadeMayBeVisible, maiBotDialogueFixture;
    private String completedCompanionTaskId,completedCompanionTaskSummary;

    @Before public void prepare() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        boolean emulator = Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone");
        boolean developmentPhone = context.getPackageName().equals("io.droyagent.app.dev")
            && (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            && "true".equals(InstrumentationRegistry.getArguments().getString("setupDevelopmentPhoneFixture"));
        assertTrue("Installation fixtures require an emulator or an explicitly selected development app", emulator || developmentPhone);
        assertTrue("Stop tasks and the engine before installation tests", RuntimeService.canManageHistory());
        assertTrue("Do not interrupt audio capture", AudioCapture.get(context).uiStatus().isNull("active"));
        product = ProductPermissions.preferences(context);
        installation = context.getSharedPreferences("installation", Context.MODE_PRIVATE);
        wake = context.getSharedPreferences("droy-voice-wake", Context.MODE_PRIVATE);
        originalProduct = new HashMap<>(product.getAll());
        originalInstallation = new HashMap<>(installation.getAll());
        originalWake = new HashMap<>(wake.getAll());
        home = new File(context.getFilesDir(), "dsh-home");
        preserved = new File(context.getFilesDir(), "setup-flow-home-backup-" + UUID.randomUUID());
        preservedPreferences=new File(context.getFilesDir(),preserved.getName()+"-preferences");
        assertTrue("Could not preserve the original preference files",preservedPreferences.mkdirs());
        File preferenceDirectory=new File(context.getApplicationInfo().dataDir,"shared_prefs");
        for(String name:new String[]{"product","installation","droy-voice-wake"}) {
            File source=new File(preferenceDirectory,name+".xml");
            if(source.isFile())Files.copy(source.toPath(),new File(preservedPreferences,name+".xml").toPath());
            else Files.write(new File(preservedPreferences,name+".absent").toPath(),new byte[0]);
        }
        Files.write(new File(preservedPreferences,"complete").toPath(),new byte[0]);
        report("DROY_SETUP_PREFERENCES_BACKUP "+preservedPreferences.getName());
        originalFiles = snapshot(home.toPath());
        hadHome = home.exists();
        if (hadHome) assertTrue("Could not preserve the original DSH home", home.renameTo(preserved));
        owned = true;
        assertTrue(product.edit().clear().commit());
        assertTrue(installation.edit().clear().commit());
        SetupManager.initialize(context);
        JSONObject fresh = SetupManager.status(context);
        assertTrue("An empty installation must enter the wizard", fresh.getBoolean("required"));
        assertEquals(1, fresh.getInt("step"));
        assertFalse("Fresh installation already selected a permission profile", fresh.getBoolean("modeSelected"));
        for (String key : SetupManager.capabilityKeys(context))
            assertFalse("Fresh setup enabled " + key, ProductPermissions.allowed(product, key));
        Bundle args = InstrumentationRegistry.getArguments();
        String theme = args.getString("layoutTheme", "light");
        assertTrue("Unsupported theme fixture", theme.equals("light") || theme.equals("dark"));
        assertTrue(product.edit().putBoolean("petEnabled", false).putString("petExternalDisplay", "off")
            .putString("petInternalDisplay", "off").putBoolean("backgroundRuntimeEnabled", false)
            .putBoolean(BackgroundRuntime.STOPPED, false).putBoolean("darkTheme", theme.equals("dark")).commit());
        assertTrue(wake.edit().putBoolean("enabled", false).commit());
        File profile = new File(home, "profiles/mobile");
        assertTrue(profile.mkdirs());
        File provider = new File(home, "setup-fixture.mjs");
        Files.write(provider.toPath(), SetupFixtureProvider.source().getBytes(StandardCharsets.UTF_8));
        JSONArray patch = new JSONArray().put(new JSONObject().put("id", "agent-default-model")
            .put("config", new JSONObject().put("provider", SetupFixtureProvider.PROVIDER).put("model", SetupFixtureProvider.MODEL)))
            .put(new JSONObject().put("insert", new JSONArray().put(new JSONObject().put("id", "setup-local-fixture")
                .put("name", provider.getAbsolutePath()))));
        String locale = args.getString("setupLocale");
        if (locale != null) {
            assertTrue("Unsupported installation language fixture", locale.equals("zh") || locale.equals("en"));
            assertTrue(product.edit().putString("languagePreference", locale).commit());
            patch.put(new JSONObject().put("id", "locale").put("config", new JSONObject().put("preference", locale)));
        }
        Files.write(new File(profile, "cordis.patch.yml").toPath(), patch.toString().getBytes(StandardCharsets.UTF_8));
        boolean configured="true".equals(args.getString("configuredCompanionFixture"));
        if(configured)assertTrue(installation.edit().putInt("schemaVersion",1).putBoolean("completed",true).putInt("step",6)
            .putString("mode","dialogue").putBoolean("modeSelected",true).commit());
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0));
        rotated = true;
        launch();
        if(configured) {ui.awaitPage();prepareVisibleCompanionList();} else awaitStep(1);
        DeviceUi.await("Local installation model fixture did not initialize", 20000,
            () -> new File(home, "setup-fixture-ready.json").isFile());
        assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0));
        DeviceUi.await("The portrait installation orientation did not apply", 5000,
            () -> ui.truth("innerHeight>innerWidth"));
        configureMaximumTextFont();
    }

    @After public void restore() throws Exception {
        if (!owned) return;
        try {
            if (!passed && !notificationShadeMayBeVisible && ui != null && activity != null && !activity.isDestroyed()) {
                try { capture("failure"); }
                catch (Exception | AssertionError failure) { report("Could not capture failed installation state: " + failure.getClass().getSimpleName()); }
            }
        } finally {
            try {
                if (activity != null && !activity.isDestroyed()) instrumentation.runOnMainSync(activity::finish);
                instrumentation.runOnMainSync(() -> RuntimeService.requestStop(context));
                DeviceUi.await("Installation fixture cleanup left the engine running", 25000, RuntimeService::canManageHistory);
                if(maiBotDialogueFixture)saveDialogueFixtureEvidence();
            } finally {
                boolean homeRestored=false,preferencesRestored=false;
                try {
                    try {
                        if (rotated) instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                            .setRotation(UiAutomation.ROTATION_UNFREEZE);
                    } finally {
                        assertTrue("Keep the original home backup until the engine stops", RuntimeService.canManageHistory());
                        try {
                            HistoryData.deleteTree(home.toPath());
                            if (hadHome) assertTrue("Could not restore the original DSH home", preserved.renameTo(home));
                            assertEquals("Installation tests changed the original DSH home", originalFiles, snapshot(home.toPath()));
                            homeRestored=true;
                        } finally {
                            try {
                                restorePreferences(product,originalProduct);assertEquals(originalProduct,product.getAll());
                            } finally {
                                try {
                                    restorePreferences(installation,originalInstallation);assertEquals(originalInstallation,installation.getAll());
                                } finally {
                                    restorePreferences(wake,originalWake);assertEquals(originalWake,wake.getAll());
                                }
                            }
                            preferencesRestored=true;
                        }
                    }
                } finally {
                    if(homeRestored&&preferencesRestored) {
                        HistoryData.deleteTree(preservedPreferences.toPath());
                        report("DROY_SETUP_RESTORE "+new JSONObject().put("homeFileCount",originalFiles.size()).put("homeUnchanged",true)
                            .put("preferencesRestored",true).put("originalSetupComplete",Boolean.TRUE.equals(originalInstallation.get("completed"))));
                        owned=false;
                    }
                }
            }
        }
    }

    @Test public void portraitSetupFieldsRemainEditableAndBackKeepsDraft() throws Exception {
        assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.getRequestedOrientation());
        assertEquals(android.content.res.Configuration.ORIENTATION_PORTRAIT, activity.getResources().getConfiguration().orientation);
        captureAndCheck("portrait-welcome");
        selectMode("dialogue");
        inspectEmbeddedModelSettings();
        testModel();
        next(3);
        ui.tap("[data-droy-setup-shizuku=skip]");
        next(4);
        type("#droy-setup-address", "Portrait fixture");
        capture("portrait-personalization-keyboard");
        hideKeyboardAndSettle();
        assertTrue("Closing the keyboard lost the portrait form draft", ui.truth(
            "document.querySelector('#droy-setup-address').value==='Portrait fixture'"));
        captureAndCheck("portrait-personalization-after-back");
        next(5);
        assertEquals("Portrait fixture", product.getString("userAddress", ""));
        passed = true;
    }

    @Test public void permissionQuickConfigurationConfirmsSavesAndPersistsInWizardAndSettings() throws Exception {
        SetupManager.update(context, new JSONObject().put("mode", "dialogue").put("step", 5));
        ui.evaluate("window.DroyMobile.refresh()"); awaitStep(5);
        captureAndCheck("permission-wizard-quick-actions");
        assertFalse("Removed permission-page actions are still visible", ui.truth(
            "[...document.querySelector('[data-droy-permissions]').querySelectorAll('[data-droy-action]')].some(e=>['resumeOperations','pauseOperations','refresh'].includes(e.dataset.droyAction))"));
        ui.tap("[data-droy-permission-preset=all]");
        DeviceUi.await("The all-permissions explanation did not open", 5000,
            () -> ui.truth("!!document.querySelector('[data-droy-permission-confirm]')"));
        capture("permission-all-confirmation");
        for (String key : SetupManager.capabilityKeys(context)) assertFalse(ProductPermissions.allowed(product, key));
        ui.tap("[data-droy-permission-cancel]");
        DeviceUi.await("Canceling the confirmation left it open", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-permission-confirm]')"));
        assertFalse(permissionQueueState().getBoolean("active"));
        ui.tap("[data-droy-permission-preset=assistant]");
        capture("permission-minimum-confirmation");
        ui.tap("[data-droy-permission-confirm]");
        returnFromPermissionSettings();
        Set<String> minimum = Set.of("allowObserve", "allowControl", "allowApps");
        for (String key : SetupManager.capabilityKeys(context))
            assertEquals("Minimum permissions changed " + key, minimum.contains(key), ProductPermissions.allowed(product, key));
        assertFalse(ProductPermissions.stopped(context));
        captureAndCheck("permission-minimum-saved");
        ui.tap("[data-droy-preference=allowControl] [role=switch]");
        DeviceUi.await("An individual capability was not saved immediately", 5000,
            () -> !ProductPermissions.allowed(product, "allowControl"));
        confirmPermissionPreset("dialogue");
        DeviceUi.await("Disabling all permissions did not pause phone capabilities", 5000,
            () -> ProductPermissions.stopped(context));
        for (String key : SetupManager.capabilityKeys(context)) assertFalse(ProductPermissions.allowed(product, key));
        ui.tap("[data-droy-preference=allowApps] [role=switch]");
        DeviceUi.await("Enabling one capability left the saved global stop active", 5000,
            () -> ProductPermissions.allowed(product, "allowApps") && !ProductPermissions.stopped(context));
        restart(); awaitStep(5);
        assertTrue(ProductPermissions.allowed(product, "allowApps"));
        assertFalse(ProductPermissions.stopped(context));
        assertTrue("The saved switch did not survive restart", ui.truth(
            "document.querySelector('[data-droy-preference=allowApps] [role=switch]').getAttribute('aria-checked')==='true'"));
        assertTrue(installation.edit().putBoolean("completed", true).putInt("step", 6).commit());
        ui.evaluate("window.DroyMobile.refresh()");
        DeviceUi.await("The fixture wizard did not leave", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-setup]')"));
        ui.evaluate("window.DroyMobile.openPermissions()");
        DeviceUi.await("The settings permission page did not open", 10000,
            () -> ui.truth("!!document.querySelector('[data-droy-settings-panel] [data-droy-permissions]')"));
        capture("permission-settings-quick-actions");
        ui.tap("[data-droy-permission-preset=dialogue]");
        capture("permission-settings-disable-confirmation");
        assertTrue("Opening a confirmation changed the capability", ProductPermissions.allowed(product, "allowApps"));
        ui.tap("[data-droy-permission-cancel]");
        assertTrue(ProductPermissions.allowed(product, "allowApps"));
        confirmPermissionPreset("dialogue");
        DeviceUi.await("Settings did not save the disabled state", 5000, () -> ProductPermissions.stopped(context));
        capture("permission-settings-disabled");
        report("DROY_PERMISSION_QUICK_CONFIGURATION confirmations_cancel_save_single_toggle_restart_wizard_settings");
        passed = true;
    }

    @Test public void allPermissionDialogsContinueAfterGrantDenialAndSettingsReturn() throws Exception {
        assertTrue("Run permission grant fixtures only in the emulator", Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone"));
        assertEquals("Back up and externally restore the emulator's grants and flags before this fixture", "true",
            InstrumentationRegistry.getArguments().getString("permissionSetupFixture"));
        SetupManager.update(context, new JSONObject().put("mode", "dialogue").put("step", 5));
        ui.evaluate("window.DroyMobile.refresh()"); awaitStep(5);
        confirmPermissionPreset("all");
        Set<String> visited = new LinkedHashSet<>();
        boolean sawGrant = false, sawDenial = false, sawSettings = false, recreated = false;
        long deadline = SystemClock.elapsedRealtime() + 90000;
        while (permissionQueueState().getBoolean("active") && SystemClock.elapsedRealtime() < deadline) {
            JSONObject state = permissionQueueState(); String current = state.getString("current");
            if (current.isEmpty() || visited.contains(current)) { SystemClock.sleep(50); continue; }
            visited.add(current);
            report("DROY_PERMISSION_QUEUE_STEP " + state);
            assertFalse("A permission launch failed: " + state, !state.optString("error").isEmpty());
            if (Set.of("notifications", "calendar", "contacts", "microphone", "camera", "location", "bluetooth",
                    "nearbyWifi", "phoneState", "activityRecognition", "bodySensors").contains(current)) {
                boolean deny = current.equals("contacts");
                boolean clicked = tapSystemPermissionButton(deny, current);
                sawGrant |= clicked && !deny; sawDenial |= clicked && deny;
            } else {
                assertFalse("The emulator unexpectedly offered Root authorization", current.equals("root"));
                DeviceUi.await("A special-permission settings page did not become visible", 5000,
                    () -> systemForegroundPackage().equals("com.android.settings"));
                sawSettings = true;
                if (!recreated) {
                    MainActivity previous = activity;
                    instrumentation.runOnMainSync(previous::recreate);
                    AtomicReference<MainActivity> replacement = new AtomicReference<>();
                    DeviceUi.await("The permission owner did not recreate", 10000, () -> {
                        instrumentation.runOnMainSync(() -> {
                            for (Stage stage : List.of(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED))
                                for (android.app.Activity candidate : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage))
                                    if (candidate instanceof MainActivity main && main != previous && !main.isDestroyed()) replacement.set(main);
                        });
                        return replacement.get() != null;
                    });
                    activity = replacement.get(); ui = new DeviceUi(instrumentation, activity);
                    assertEquals("Recreating the owner lost the in-flight authorization", current, permissionQueueState().getString("current"));
                    recreated = true;
                }
                ui.pressBack();
            }
            DeviceUi.await("The permission queue did not advance after " + current, 10000,
                () -> !permissionQueueState().optString("current").equals(current));
        }
        assertFalse("The authorization queue remained active", permissionQueueState().getBoolean("active"));
        assertTrue("No Android grant dialog was exercised", sawGrant);
        assertTrue("The contacts denial was not exercised; start the fixture with contacts ungranted", sawDenial);
        assertTrue("No special-permission settings return was exercised", sawSettings);
        assertTrue("The authorization owner was not recreated", recreated);
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS));
        assertTrue("Denying the Android permission rewrote the saved product choice", ProductPermissions.allowed(product, "allowContacts"));
        android.content.pm.PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS);
        for (String permission : packageInfo.requestedPermissions)
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
                assertFalse("A grant retained a marker that would misclassify a later system revocation: " + permission,
                    product.getBoolean("permissionRequested:" + permission, false));
        awaitChatReady(); awaitStep(5);
        captureAndCheck("permission-all-queue-finished-with-denial");
        report("DROY_PERMISSION_QUEUE_STEPS " + new JSONArray(visited));
        passed = true;
    }

    private void confirmPermissionPreset(String mode) throws Exception {
        ui.tap("[data-droy-permission-preset=" + mode + "]");
        DeviceUi.await("Permission confirmation did not open", 5000,
            () -> ui.truth("!!document.querySelector('[data-droy-permission-confirm]')"));
        ui.tap("[data-droy-permission-confirm]");
        DeviceUi.await("Permission configuration was not saved", 5000,
            () -> SetupManager.status(context).getString("mode").equals(mode));
    }

    private JSONObject permissionQueueState() {
        AtomicReference<JSONObject> value = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                java.lang.reflect.Field field = MainActivity.class.getDeclaredField("permissionSetup"); field.setAccessible(true);
                value.set(((PermissionSetup) field.get(activity)).state());
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        return value.get();
    }

    private void returnFromPermissionSettings() throws Exception {
        DeviceUi.await("The permission configuration did not save", 5000,
            () -> SetupManager.status(context).getString("mode").equals("assistant"));
        if (permissionQueueState().getBoolean("active")) {
            DeviceUi.await("The accessibility settings did not open", 5000,
                () -> systemForegroundPackage().equals("com.android.settings"));
            ui.pressBack();
        }
        DeviceUi.await("The permission queue did not finish after returning", 10000,
            () -> !permissionQueueState().getBoolean("active"));
        DeviceUi.await("The permission confirmation did not close", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-permission-confirm]')"));
    }

    private String systemForegroundPackage() {
        AccessibilityNodeInfo root = ui.automation().getRootInActiveWindow();
        if (root == null) return "";
        try { return String.valueOf(root.getPackageName()); } finally { root.recycle(); }
    }

    private boolean tapSystemPermissionButton(boolean deny, String step) throws Exception {
        // Use Android's accessible click for system dialogs: their tree can be
        // ready before the opening window transition accepts touchscreen input.
        ui.automation().waitForIdle(500, 5000);
        boolean[] clicked = {false};
        DeviceUi.await("Android did not show the expected permission prompt for " + step, 8000, () -> {
            // Android can grant another permission from the same group without
            // showing a second dialog. Its real callback still advances the queue.
            if (!permissionQueueState().optString("current").equals(step)) return true;
            AccessibilityNodeInfo root = ui.automation().getRootInActiveWindow();
            if (root == null) return false;
            try {
                List<String> ids = deny ? List.of("permission_deny_button", "permission_deny_and_dont_ask_again_button")
                    : List.of("permission_allow_foreground_only_button", "permission_allow_button", "permission_allow_always_button");
                for (String id : ids) for (String provider : List.of("com.android.permissioncontroller", "com.google.android.permissioncontroller")) {
                    for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(provider + ":id/" + id)) {
                        try { if (node.isVisibleToUser() && node.isEnabled()) return clicked[0] = node.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
                        finally { node.recycle(); }
                    }
                }
                return false;
            } finally { root.recycle(); }
        });
        return clicked[0];
    }

    @Test public void dialogueCompletesSixPagesAndResumesWithoutGrantingPhoneCapabilities() throws Exception {
        captureAndCheck("welcome");
        selectMode("dialogue");
        inspectEmbeddedModelSettings();
        testModel();
        next(3);
        captureAndCheck("environment");
        ui.tap("[data-droy-setup-shizuku=skip]");
        next(4);
        captureAndCheck("personalization");
        assertFalse("Setup must not redefine a partner through the old pet-name field",
            ui.truth("!!document.querySelector('#droy-setup-name,#droy-setup-personality')"));
        assertTrue("Setup did not introduce both independent companions",ui.truth("(()=>{const t=document.querySelector('[data-droy-setup]').textContent;return /咔咔|Kaka/.test(t)&&/麦麦|Maimai/.test(t)})()"));
        assertTrue("The optional pet choice disappeared",ui.truth("[...document.querySelectorAll('[data-droy-setup] details summary')].some(e=>/宠物|pet/i.test(e.textContent))"));
        Object petName=product.getAll().get("petName"),personality=product.getAll().get("companionPersonality");
        type("#droy-setup-address", "Friend");
        capture("personalization-keyboard");
        hideKeyboardAndSettle();
        next(5);
        assertEquals("Friend", product.getString("userAddress", ""));
        assertEquals("The welcome address changed the optional pet's name",petName,product.getAll().get("petName"));
        assertEquals("The welcome address changed partner personality",personality,product.getAll().get("companionPersonality"));
        restart();
        awaitStep(5);
        assertEquals("Interruption restarted model checks", 2, modelEvents().size());
        verifySelectedCapabilities();
        assertTrue("Dialogue must complete without Android Use permissions", SetupManager.status(context).getBoolean("canComplete"));
        for (String key : SetupManager.capabilityKeys(context))
            assertFalse("Dialogue setup enabled " + key, ProductPermissions.allowed(product, key));
        captureAndCheck("permissions-dialogue");
        next(6);
        captureAndCheck("welcome-use");
        finishWizard();
        assertModelIsolation();
        Map<String, ?> configured = new HashMap<>(product.getAll());
        SetupManager.initialize(context);
        assertEquals("Reinitialization overwrote saved choices", configured, product.getAll());
        restart();
        awaitChatReady();
        assertTrue(SetupManager.status(context).getBoolean("completed"));
        assertFalse("A completed installation reopened the wizard", ui.truth("!!document.querySelector('" + ROOT + "')"));
        assertEquals("Restart sent another model request", 2, modelEvents().size());
        exerciseCompanions();
        report("DROY_COMPANION_CORE_PASS six_page_two_companions_work_readonly_and_synthetic_history_anchor");
        if(extendedCompanionFlows())exerciseCompanionSettingsAndMemory();
        assertModelIsolation();
        passed = true;
    }

    @Test public void configuredLocalCompanionsExerciseRemainingPhoneFlows() throws Exception {
        assertEquals("This case requires its explicit configured local fixture","true",InstrumentationRegistry.getArguments().getString("configuredCompanionFixture"));
        assertTrue("Use the extended companion flow for this bounded phone case",extendedCompanionFlows());
        assertFalse("A configured companion fixture must not reopen setup",ui.truth("!!document.querySelector('[data-droy-setup]')"));
        capture("configured-local-companion-list");
        exerciseCompanions();
        report("DROY_COMPANION_CORE_PASS configured_fixture_two_companions_work_readonly_and_synthetic_history_anchor");
        exerciseCompanionSettingsAndMemory();
        assertEquals("A configured fixture must not repeat setup model checks",0,modelEvents().size());
        passed=true;
    }

    @Test public void configuredCompanionEntryAppearanceAndNotifications() throws Exception {
        assertEquals("This case requires its explicit configured local fixture","true",InstrumentationRegistry.getArguments().getString("configuredCompanionFixture"));
        capture("configured-entry-list");
        exerciseEntryAppearanceAndNotifications();
        assertEquals("Entry settings must not repeat any model calls",0,modelEvents().size());
        assertEquals("Unsent appearance drafts must not call a companion model",0,fixtureEvents("setup-companion-events.jsonl").size());
        passed=true;
    }

    @Test public void configuredCompanionNotificationOnly() throws Exception {
        assertEquals("This case requires its explicit configured local fixture","true",InstrumentationRegistry.getArguments().getString("configuredCompanionFixture"));
        awaitList();capture("configured-notification-list");
        notificationShadeMayBeVisible=true;
        CompanionNotificationChecks.run(instrumentation,context,activity,ui);
        notificationShadeMayBeVisible=false;
        assertEquals("Notification navigation must not call the setup model",0,modelEvents().size());
        assertEquals("Notification navigation must not call a companion model",0,fixtureEvents("setup-companion-events.jsonl").size());
        passed=true;
    }

    @Test public void chatExperiencePerformance() throws Exception {
        assertEquals("Use the explicitly isolated configured companion fixture", "true",
            InstrumentationRegistry.getArguments().getString("configuredCompanionFixture"));
        new ChatExperienceChecks(instrumentation, context, activity, ui).performance();
        passed = true;
    }

    @Test public void chatExperienceUiChecks() throws Exception {
        assertEquals("Use the explicitly isolated configured companion fixture", "true",
            InstrumentationRegistry.getArguments().getString("configuredCompanionFixture"));
        new ChatExperienceChecks(instrumentation, context, activity, ui).uiChecks();
        passed = true;
    }

    @Test public void navigationMessagesUiChecks() throws Exception {
        assertEquals("Use the explicitly isolated configured companion fixture", "true",
            InstrumentationRegistry.getArguments().getString("configuredCompanionFixture"));
        new NavigationMessagesDeviceTest(instrumentation, context, activity, ui).run();
        passed = true;
    }

    @Test public void navigationSettingsRenderingControl() throws Exception {
        assertEquals("Use the explicitly isolated configured companion fixture", "true",
            InstrumentationRegistry.getArguments().getString("configuredCompanionFixture"));
        new NavigationMessagesDeviceTest(instrumentation, context, activity, ui).settingsRenderingControl();
        passed = true;
    }

    @Test public void chatExperienceRenderingControl() throws Exception {
        assertEquals("Use the explicitly isolated configured companion fixture", "true",
            InstrumentationRegistry.getArguments().getString("configuredCompanionFixture"));
        new ChatExperienceChecks(instrumentation, context, activity, ui).renderingControl();
        passed = true;
    }

    @Test public void configuredCompanionMaiBotDialogueOnly() throws Exception {
        maiBotDialogueFixture=true;
        assertEquals("Use the explicitly isolated configured companion fixture","true",
            InstrumentationRegistry.getArguments().getString("configuredCompanionFixture"));
        Map<String,Map<String,String>> personas=new HashMap<>();
        for(String language:new String[]{"zh","en"}) {
            selectDialogueLanguage(language);
            Map<String,String> localized=new HashMap<>();personas.put(language,localized);
            for(String id:new String[]{"kaka","maimai"}) {
                localized.put(id,companionProfile(id).getString("personality"));
                openCompanion(id);
                String greeting=id.equals("kaka")?SetupFixtureProvider.KAKA_GREETING:SetupFixtureProvider.MAIMAI_GREETING;
                Set<String> priorTasks=new HashSet<>();
                for(JSONObject row:companionMessages(id))if(row.optString("type").equals("task"))priorTasks.add(row.getString("id"));
                int before=assistantTextCount(id);
                sendCompanion(greeting);
                awaitCompanionTexts(id,before+1);
                awaitCompanionIdle(id,"fixture:"+id+":greeting",language);
                String userId=null;
                for(JSONObject row:companionMessages(id))if(row.optString("role").equals("user")&&row.optString("text").equals(greeting))userId=row.getString("id");
                assertNotNull("The actual greeting was not persisted",userId);
                boolean quoted=false,sticker=false;
                for(JSONObject row:companionMessages(id)) {
                    if(row.optString("role").equals("assistant")&&row.optString("replyTo").equals(userId))quoted=true;
                    if(row.optString("type").equals("sticker")&&row.optString("role").equals("assistant"))sticker=true;
                    assertFalse("A simple greeting created a work conversation",row.optString("type").equals("task")&&!priorTasks.contains(row.getString("id")));
                }
                assertTrue("The reply lost its internal message target",quoted);
                assertFalse("An ordinary greeting should not create a visible quote",ui.truth("!!document.querySelector('.droy-im-message-quote')"));
                assertEquals("The ordinary text reply must remain one bubble",before+1,assistantTextCount(id));
                if(id.equals("kaka")) {
                    assertTrue("The independent emoji tool did not persist its sticker",sticker);
                    DeviceUi.await("The independent sticker was not visibly decoded",10000,()->ui.truth("[...document.querySelectorAll('.droy-im-sticker-message')].some(e=>e.complete&&e.naturalWidth>0&&e.getClientRects().length)"));
                }
                capture("maibot-"+language+"-"+id+"-reply");ensureList();
            }
            if(language.equals("zh")) {
                openCompanion("kaka");sendCompanion(SetupFixtureProvider.WORK_REQUEST);
                DeviceUi.await("The adapted Planner did not return the real completed work",30000,()->{
                    boolean complete=false,spoken=false;
                    for(JSONObject row:companionMessages("kaka")) {
                        JSONObject task=row.optJSONObject("task");
                        if(task!=null&&task.optString("state").equals("completed")&&!task.optString("summary").isBlank())complete=true;
                        if(row.optString("role").equals("assistant")&&row.optString("text").contains("DROY_WORK_RESULT"))spoken=true;
                    }
                    return complete&&spoken;
                });
                awaitCompanionIdle("kaka","fixture:work-completed",language);capture("maibot-completed-work-reply");
                exerciseNeutralWork();ensureList();
            }
        }
        assertMaiBotDialogueEvents(personas);
        assertEquals("The conversation-only case repeated installation model checks",0,modelEvents().size());
        for(String key:SetupManager.capabilityKeys(context))assertFalse("Chat enabled a phone capability: "+key,ProductPermissions.allowed(product,key));
        report("DROY_MAIBOT_DIALOGUE_PASS bilingual_two_companions_original_templates_new_schema_reply_then_emoji_quote_work_and_neutral_work");
        passed=true;
    }

    private int assistantTextCount(String id) throws Exception {
        int count=0;for(JSONObject row:companionMessages(id))if(row.optString("role").equals("assistant")&&row.optString("type").equals("text"))count++;
        return count;
    }

    private void awaitCompanionIdle(String id,String reference,String language) throws Exception {
        DeviceUi.await("The Planner did not stop after its visible actions",20000,()->{
            JSONObject status=companionGet("/"+id+"/messages").getJSONObject("status");
            assertFalse("The adapted dialogue failed: "+status,status.has("error")&&!status.isNull("error"));
            if(!status.optString("phase").equals("idle"))return false;
            for(JSONObject event:fixtureEvents("setup-companion-idle.jsonl"))if(event.optString("companion").equals(id)
                &&event.optString("locale").equals(language)&&event.optString("reference").equals(reference))return true;
            return false;
        });ui.awaitPaint();
    }

    private void assertMaiBotDialogueEvents(Map<String,Map<String,String>> personas) throws Exception {
        Set<String> planners=new HashSet<>(),replyers=new HashSet<>(),emojiAfterReply=new HashSet<>(),emojiSelectors=new HashSet<>();
        boolean work=false;
        for(JSONObject event:fixtureEvents("setup-companion-events.jsonl")) {
            String kind=event.getString("kind"),id=event.getString("companion"),language=event.getString("locale");
            String key=language+":"+id,serialized=event.toString();
            assertFalse("Private chat crossed companion ownership",serialized.contains(id.equals("kaka")?SetupFixtureProvider.MAIMAI_GREETING:SetupFixtureProvider.KAKA_GREETING));
            if(kind.equals("planner")||kind.equals("replyer")) {
                String persona=personas.get(language).get(id);
                assertTrue("The original/localized persona was not sent to the model",!persona.isBlank()&&event.getString("system").contains(persona));
                assertTrue("The model did not receive the actual MaiBot template",event.getBoolean("templateVerified"));
                assertFalse("The model received unrendered MaiBot template fields",event.getString("system").matches("(?s).*\\{(?:identity|bot_name|personality|reply_style|group_chat_attention_block)\\}.*"));
            }
            if(kind.equals("planner")) {
                planners.add(key);JSONObject schema=event.getJSONObject("replySchema");
                assertTrue(schema.getJSONArray("required").toString().contains("reply_reference"));
                assertEquals(new JSONArray().put("简短表达").put("正常回复").put("长回复").toString(),
                    schema.getJSONObject("properties").getJSONObject("reply_style").getJSONArray("enum").toString());
                assertTrue(schema.getJSONObject("properties").getJSONObject("expression_intent").getJSONObject("properties").has("reply_act"));
                assertFalse("The model-facing reply schema retained legacy intent",schema.getJSONObject("properties").has("intent"));
                assertFalse("The model-facing reply schema retained the old attached-sticker path",schema.getJSONObject("properties").has("stickerId"));
                JSONArray calls=event.getJSONArray("turnCalls");
                if(calls.toString().contains("companion_reply")&&calls.toString().contains("companion_stickers")&&calls.toString().contains("companion_emoji"))emojiAfterReply.add(key);
            } else if(kind.equals("replyer")) {
                replyers.add(key);
                assertTrue("Replyer did not receive actual user/assistant history",event.getBoolean("realHistoryVerified"));
                assertTrue("Replyer did not receive a separate reply-reference message",event.getBoolean("referenceVerified"));
                assertTrue("Reply style was not reflected in the Replyer request",event.getBoolean("styleVerified"));
                if(language.equals("en"))assertTrue("Continuing the chat lost the actual assistant-role history",event.getBoolean("assistantHistoryPresent"));
            } else if(kind.equals("emoji-selector")) {
                assertTrue("The independent emoji selector did not use its original template",event.getBoolean("templateVerified"));
                assertTrue("The emoji model did not receive the three described local test images",event.getBoolean("emojiCandidatesVerified"));
                emojiSelectors.add(key);
            } else if(kind.equals("work"))work=true;
        }
        for(String language:new String[]{"zh","en"})for(String id:new String[]{"kaka","maimai"}) {
            String key=language+":"+id;
            assertTrue("Missing adapted Planner: "+key,planners.contains(key));
            assertTrue("Missing original Replyer: "+key,replyers.contains(key));
            if(id.equals("kaka")) {
                assertTrue("Reply incorrectly concluded before the separate sticker actions: "+key,emojiAfterReply.contains(key));
                assertTrue("The independent emoji selection model was not called: "+key,emojiSelectors.contains(key));
            }
        }
        assertTrue("The adapted Planner never executed its local work",work);
    }

    private void saveDialogueFixtureEvidence() throws Exception {
        String label=InstrumentationRegistry.getArguments().getString("evidenceLabel","setup").replaceAll("[^A-Za-z0-9_-]","_");
        File directory=new File(context.getExternalFilesDir(null),"setup-model-evidence/"+label);
        assertTrue(directory.isDirectory()||directory.mkdirs());
        for(String name:new String[]{"setup-companion-events.jsonl","setup-companion-idle.jsonl","setup-neutral-work-events.jsonl"}) {
            File source=new File(home,name);
            if(source.isFile())try(InputStream input=Files.newInputStream(source.toPath());FileOutputStream output=new FileOutputStream(new File(directory,name))) {
                RuntimeInstaller.copy(input,output);
            }
        }
    }

    private void selectDialogueLanguage(String language) throws Exception {
        openGeneral();selectUiLanguage(language);tapSettled("[data-droy-settings-back]");awaitList();
        DeviceUi.await("The companion locale did not follow General settings",10000,()->
            (language.equals("zh")?"咔咔":"Kaka").equals(companionProfile("kaka").getString("name")));
    }

    private void exerciseCompanions() throws Exception {
        JSONObject nativeMemory=MemoryRuntime.get(context).status();
        report("DROY_COMPANION_ROOT_FACTS "+new JSONObject().put("authorized",nativeMemory.getJSONObject("root").getBoolean("authorized"))
            .put("productAllowed",nativeMemory.getJSONObject("root").getBoolean("productAllowed"))
            .put("executionReady",nativeMemory.getJSONObject("execution").getBoolean("ready")));
        assertFalse("This fixture must chat and work without an authorized Root channel",
            MemoryRuntime.get(context).status().getJSONObject("root").getBoolean("authorized"));
        JSONObject directory=companionGet("");
        assertEquals("The fresh installation must have two companions",2,directory.getJSONArray("companions").length());
        for(int index=0;index<2;index++) {
            Object model=directory.getJSONArray("companions").getJSONObject(index).opt("model");
            if(model instanceof JSONObject selected)
                assertEquals("The fixture must never send chat to a paid model",SetupFixtureProvider.PROVIDER,selected.getString("provider"));
        }
        openCompanion("kaka");sendCompanion(SetupFixtureProvider.KAKA_GREETING);
        awaitCompanionTexts("kaka",1);
        DeviceUi.await("Kaka did not send its scripted sticker",10000,()->{
            for(JSONObject row:companionMessages("kaka"))if(row.optString("type").equals("sticker")&&row.optString("role").equals("assistant"))return true;
            return false;
        });
        DeviceUi.await("The actual sticker image was not displayed",10000,()->ui.truth("[...document.querySelectorAll('.droy-im-sticker-message')].some(e=>e.complete&&e.naturalWidth>0&&e.getClientRects().length)"));
        capture("companion-kaka-conversation");
        ui.tap("[data-droy-im-screen=chat] .droy-im-header>.droy-im-icon:first-child");
        openCompanion("maimai");sendCompanion(SetupFixtureProvider.MAIMAI_GREETING);
        awaitCompanionTexts("maimai",1);capture("companion-maimai-conversation");
        ui.tap("[data-droy-im-screen=chat] .droy-im-header>.droy-im-icon:first-child");
        openCompanion("kaka");sendCompanion(SetupFixtureProvider.WORK_REQUEST);
        JSONObject[] task={null};
        DeviceUi.await("The offline companion work did not complete",30000,()->{
            for(JSONObject row:companionMessages("kaka")) {
                JSONObject value=row.optJSONObject("task");
                if(value!=null&&value.optString("state").equals("completed")&&!value.optString("summary").isBlank()) {task[0]=value;return true;}
            }
            return false;
        });
        awaitCompanionTexts("kaka",3);
        DeviceUi.await("The finished result did not return to the companion chat",10000,()->{
            for(JSONObject row:companionMessages("kaka"))if(row.optString("role").equals("assistant")
                &&(row.optString("text").contains("time to unwind")||row.optString("text").contains("留点时间放空")))return true;
            return false;
        });
        DeviceUi.await("The real IM task card was not rendered",10000,()->ui.truth("!!document.querySelector('.droy-im-task-card')"));
        DeviceUi.await("The IM did not display the completed task reply",10000,()->ui.truth("[...document.querySelectorAll('.droy-im-message')].some(e=>/time to unwind|留点时间放空/.test(e.textContent))"));
        capture("companion-completed-work");
        completedCompanionTaskId=task[0].getString("id");completedCompanionTaskSummary=task[0].getString("summary");
        if(extendedCompanionFlows()) {
            exerciseProfileModels();
            exerciseNeutralWork();
            openCompanion("kaka");
        }
        String sessionId=task[0].getString("sessionId");
        assertFalse("The work must have its own DSH conversation",sessionId.isBlank());
        for(JSONObject profile:rows(companionGet("").getJSONArray("companions")))if(profile.getString("id").equals("kaka"))
            assertNotEquals("The IM planner session was used as its work history",profile.optString("chatSessionId"),sessionId);
        ui.tap("[data-droy-im-screen=chat] .droy-im-header>.droy-im-icon:first-child");
        DeviceUi.await("Chat did not return to the IM list",5000,()->ui.truth("!!document.querySelector('[data-droy-im-screen=list]')"));
        ui.awaitPaint();ui.tap(".droy-im-mode-trigger");
        DeviceUi.await("The workbench did not open",5000,()->ui.truth("!document.querySelector('[data-droy-im]')&&!!document.querySelector('.droy-work-mode-switch')"));
        ui.awaitPaint();ui.tap("[data-droy-menu]");
        String row="[data-droy-session-row='"+sessionId+"']";
        DeviceUi.await("The partner work group did not appear in the drawer",10000,()->ui.truth(
            "!!document.querySelector("+JSONObject.quote(row)+")||[...document.querySelectorAll('[role=treeitem][aria-expanded]')].some(e=>/咔咔|Kaka/.test(e.textContent))"));
        if(!ui.truth("!!document.querySelector("+JSONObject.quote(row)+")")) {
            assertTrue(ui.truth("(()=>{const e=[...document.querySelectorAll('[role=treeitem][aria-expanded]')].find(e=>/咔咔|Kaka/.test(e.textContent));if(!e)return false;e.setAttribute('data-setup-companion-work-group','');return true})()"));
            ui.awaitPaint();ui.tap("[data-setup-companion-work-group]");
        }
        DeviceUi.await("The companion's real history did not appear in its workspace",10000,()->ui.truth("!!document.querySelector("+JSONObject.quote(row)+")"));
        ui.awaitPaint();ui.tap(row);
        DeviceUi.await("The actual partner work session did not become visible and read-only",10000,()->ui.truth(
            "!!document.querySelector('[data-conversation-session=\""+sessionId+"\"]')&&!!document.querySelector('.droy-workspace-readonly')"));
        assertFalse("Partner work history exposed an editable work composer",ui.truth("[...document.querySelectorAll('[data-conversation-session=\""+sessionId+"\"] [data-composer-input]')].some(e=>e.getClientRects().length&&e.isContentEditable)"));
        checkWorkHeaderGeometry();
        capture("companion-readonly-work-history");
        List<JSONObject> events=fixtureEvents("setup-companion-events.jsonl");
        boolean kaka=false,maimai=false,work=false;
        for(JSONObject event:events) {
            String serialized=event.toString(),peer=event.getString("companion");
            if(peer.equals("kaka")) {kaka=true;assertFalse("Kaka received Maimai's private message",serialized.contains(SetupFixtureProvider.MAIMAI_GREETING));}
            if(peer.equals("maimai")) {maimai=true;assertFalse("Maimai received Kaka's private message",serialized.contains(SetupFixtureProvider.KAKA_GREETING));}
            if(event.optString("input").equals("DROY_FIXTURE_WEEKEND_PLAN"))work=true;
        }
        assertTrue("Both companions and the separate work planner must use the local adapter",kaka&&maimai&&work);
        for(String key:SetupManager.capabilityKeys(context))assertFalse("Ordinary companion conversation enabled "+key,ProductPermissions.allowed(product,key));
        exerciseLongHistory();
    }

    private boolean extendedCompanionFlows() {
        return "true".equals(InstrumentationRegistry.getArguments().getString("companionExtendedFlows"));
    }

    private void exerciseProfileModels() throws Exception {
        for(String id:new String[]{"kaka","maimai"}) {
            openProfile(id);
            String name=companionProfile(id).getString("name");
            String model=id.equals("kaka")?SetupFixtureProvider.ALTERNATE_MODEL:SetupFixtureProvider.MODEL;
            tapSettled("[data-droy-im-action=model-picker]");
            tapSettled("[data-droy-im-model='"+SetupFixtureProvider.PROVIDER+":"+model+"']");
            tapSettled(".droy-im-profile-form > button[type=submit]");
            DeviceUi.await("The companion model was not independently saved",10000,()->model.equals(companionProfile(id).getJSONObject("model").optString("model")));
            DeviceUi.await("The saved profile did not acknowledge its form",5000,()->ui.truth("/已保存|Saved/.test(document.querySelector('.droy-im-profile-form > button[type=submit]')?.textContent)"));
            assertEquals("Saving a model changed the unedited companion name",name,companionProfile(id).getString("name"));
            capture("profile-"+id+"-model-saved");
            ensureList();
        }
        assertIndependentModels();
    }

    private void exerciseNeutralWork() throws Exception {
        ensureList();tapSettled(".droy-im-mode-trigger");
        DeviceUi.await("The ordinary work entry did not open",10000,()->ui.truth("!!document.querySelector('.droy-work-mode-switch')&&!document.querySelector('[data-droy-im]')"));
        tapSettled("[data-droy-menu]");
        DeviceUi.await("The ordinary New Session action was not visible",10000,()->ui.truth("(()=>{const e=[...document.querySelectorAll('[data-droy-sidebar] button')].find(e=>[e.textContent.trim(),e.getAttribute('aria-label')||''].some(t=>/^(新会话|New Session)$/i.test(t)));if(!e)return false;e.setAttribute('data-setup-neutral-new','');return true})()"));
        tapSettled("[data-setup-neutral-new]");ui.awaitPage();
        String editor="[data-content-phase=hero] [data-composer-input][contenteditable=true]";
        DeviceUi.await("A normal new work session did not expose its editor",10000,()->ui.truth("!!document.querySelector("+JSONObject.quote(editor)+")&&!document.querySelector('[data-droy-drawer-open=true]')"));
        assertFalse("Normal work entered a managed buddy workspace",ui.truth("/^(Kaka|咔咔|Maimai|麦麦)$/.test(document.querySelector('[data-droy-workspace-trigger]')?.textContent.trim()||'')"));
        ui.showKeyboard(editor);
        int length=((String)ui.evaluate("document.querySelector("+JSONObject.quote(editor)+").textContent")).length();
        ui.replaceFocusedText(SetupFixtureProvider.NEUTRAL_WORK_REQUEST,length);
        DeviceUi.await("The ordinary work editor did not receive native input",5000,()->ui.truth("document.querySelector("+JSONObject.quote(editor)+")?.textContent.includes("+JSONObject.quote(SetupFixtureProvider.NEUTRAL_WORK_REQUEST)+")"));
        tapSettled("button[aria-label='发送消息'],button[aria-label='Send message']");
        DeviceUi.await("The normal work did not render the local reply",30000,()->ui.truth("[...document.querySelectorAll('[data-conversation-session]')].some(e=>e.textContent.includes("+JSONObject.quote(SetupFixtureProvider.NEUTRAL_WORK_REPLY)+"))"));
        // The hero editor is replaced by the active editor after submission;
        // its normal autofocus must finish before dismissing the phone IME.
        ui.awaitPaint();hideKeyboardAndSettle();
        List<JSONObject> calls=fixtureEvents("setup-neutral-work-events.jsonl");
        assertEquals("The bounded ordinary work request should call its local adapter once",1,calls.size());
        JSONObject call=calls.get(0);StringBuilder contextText=new StringBuilder(call.optString("system"));
        for(JSONObject message:rows(call.getJSONArray("messages")))for(JSONObject part:rows(message.getJSONArray("content")))
            if(part.optString("type").equals("text"))contextText.append('\n').append(part.optString("text"));
        String prompt=contextText.toString();
        assertEquals(SetupFixtureProvider.PROVIDER,call.getString("provider"));
        assertEquals("Buddy profile changes altered the ordinary work model",SetupFixtureProvider.MODEL,call.getString("model"));
        assertFalse("Ordinary work received Kaka's private chat",prompt.contains(SetupFixtureProvider.KAKA_GREETING));
        assertFalse("Ordinary work received Maimai's private chat",prompt.contains(SetupFixtureProvider.MAIMAI_GREETING));
        for(String id:new String[]{"kaka","maimai"}) {
            String personality=companionProfile(id).getString("personality");
            assertFalse("Ordinary work received a companion persona",!personality.isBlank()&&prompt.contains(personality));
        }
        for(int index=0;index<call.getJSONArray("tools").length();index++)assertFalse("Ordinary work received a companion-only tool",
            call.getJSONArray("tools").getString(index).startsWith("companion_"));
        capture("ordinary-default-work-reply");
        report("DROY_ORDINARY_WORK_PASS local_model_independent_and_no_companion_context");
    }

    private void exerciseCompanionSettingsAndMemory() throws Exception {
        final String initial="Fictional preference: I like mint tea.",corrected="Fictional preference: I prefer jasmine tea.";
        openProfile("kaka");
        assertFalse("The retired memory manager remains visible",ui.truth("!!document.querySelector('.droy-im-memory-entry')"));
        capture("memory-manager-hidden");
        assertEquals("The isolated companion memory fixture was not empty",0,companionGet("/kaka/memories").getJSONArray("records").length());
        companionRequest("/kaka/memories","POST",new JSONObject().put("text",initial));awaitMemory("kaka",initial);
        JSONObject record=companionGet("/kaka/memories").getJSONArray("records").getJSONObject(0);
        companionRequest("/kaka/memories","PATCH",new JSONObject().put("recordId",record.getString("recordId"))
            .put("expectedRevisionId",record.getString("revisionId")).put("text",corrected));awaitMemory("kaka",corrected);
        reportCompletedWorkAfterMemory("corrected");
        assertFalse("Memory correction left the old preference active",memoryTexts("kaka").contains(initial));
        assertMemoryEmpty("maimai");
        restart();awaitList();assertIndependentModels();
        awaitMemory("kaka",corrected);
        record=companionGet("/kaka/memories").getJSONArray("records").getJSONObject(0);
        companionRequest("/kaka/memories","DELETE",new JSONObject().put("recordId",record.getString("recordId"))
            .put("expectedRevisionId",record.getString("revisionId")));
        DeviceUi.await("The forgotten memory remained active",10000,()->companionGet("/kaka/memories").getJSONArray("records").length()==0);
        assertMemoryEmpty("kaka");
        reportCompletedWorkAfterMemory("forgotten");
        restart();awaitList();assertIndependentModels();
        assertMemoryEmpty("kaka");assertMemoryEmpty("maimai");
        report("DROY_COMPANION_MEMORY_PASS manager_hidden_api_add_correct_forget_isolated_and_restart_persistent");
        JSONObject completed=findCompletedWork();
        assertEquals("Correcting an unrelated manual memory changed a completed task", "completed", completed.getString("state"));
        assertEquals("Correcting an unrelated manual memory erased completed work", completedCompanionTaskSummary, completed.getString("summary"));
        exerciseEntryAppearanceAndNotifications();
    }

    private void exerciseEntryAppearanceAndNotifications() throws Exception {
        setDefaultMode("work");restart();
        report("DROY_DEFAULT_ENTRY_SAVED_AFTER_RESTART "+companionGet("").getJSONObject("settings").getString("defaultMode"));
        try {
            DeviceUi.await("The saved default Work entry was not followed on restart",15000,()->ui.truth("!document.querySelector('[data-droy-im]')&&!!document.querySelector('.droy-work-mode-switch')"));
        } catch(AssertionError failure) {
            report("DROY_DEFAULT_ENTRY_DIAGNOSTIC "+new JSONObject().put("savedDefaultMode",companionGet("").getJSONObject("settings").getString("defaultMode"))
                .put("ui",ui.evaluate("(()=>{const visible=e=>!!e&&e.getClientRects().length>0&&!e.closest('[inert]');return {im:visible(document.querySelector('[data-droy-im]')),list:visible(document.querySelector('[data-droy-im-screen=list]')),work:visible(document.querySelector('.droy-work-mode-switch')),imActive:document.body.hasAttribute('data-droy-im-active'),ready:document.documentElement.dataset.droyUiReady}})()")));
            throw failure;
        }
        capture("default-entry-work-after-restart");
        if("true".equals(InstrumentationRegistry.getArguments().getString("entryDiagnosticOnly")))return;
        setDefaultMode("messages");restart();awaitList();capture("default-entry-messages-after-restart");
        exerciseAppearance("zh","dark");exerciseAppearance("en","light");
        notificationShadeMayBeVisible=true;
        CompanionNotificationChecks.run(instrumentation,context,activity,ui);
        notificationShadeMayBeVisible=false;
    }

    private JSONObject findCompletedWork() throws Exception {
        String path="/kaka/messages";
        for(int page=0;page<3;page++) {
            JSONObject response=companionGet(path);List<JSONObject> messages=rows(response.getJSONArray("messages"));
            for(JSONObject message:messages) {
                JSONObject task=message.optJSONObject("task");
                if(task!=null&&completedCompanionTaskId.equals(task.optString("id")))return task;
            }
            if(!response.optBoolean("hasMore")||messages.isEmpty())break;
            path="/kaka/messages?before="+java.net.URLEncoder.encode(messages.get(0).getString("id"),"UTF-8");
        }
        throw new AssertionError("The earlier completed work disappeared from IM history");
    }

    private void reportCompletedWorkAfterMemory(String stage) throws Exception {
        JSONObject task=findCompletedWork();
        report("DROY_MEMORY_UNRELATED_WORK "+new JSONObject().put("stage",stage).put("state",task.getString("state"))
            .put("summaryUnchanged",completedCompanionTaskSummary.equals(task.optString("summary"))));
    }

    private JSONObject companionProfile(String id) throws Exception {
        for(JSONObject profile:rows(companionGet("").getJSONArray("companions")))if(profile.getString("id").equals(id))return profile;
        throw new AssertionError("The expected companion profile disappeared");
    }

    private void assertIndependentModels() throws Exception {
        assertEquals(SetupFixtureProvider.ALTERNATE_MODEL,companionProfile("kaka").getJSONObject("model").getString("model"));
        assertEquals(SetupFixtureProvider.MODEL,companionProfile("maimai").getJSONObject("model").getString("model"));
    }

    private void openProfile(String id) throws Exception {
        ensureList();openCompanion(id);tapSettled(".droy-im-chat-title");
        DeviceUi.await("The buddy profile did not open",10000,()->ui.truth("!!document.querySelector('[data-droy-im-screen=profile]')"));
        DeviceUi.await("The buddy avatar did not decode",10000,()->ui.truth("(()=>{const e=document.querySelector('.droy-im-profile-identity img');return !!e&&e.complete&&e.naturalWidth>0})()"));
    }

    private List<String> memoryTexts(String id) throws Exception {
        List<String> values=new ArrayList<>();
        for(JSONObject row:rows(companionGet("/"+id+"/memories").getJSONArray("records")))values.add(row.optJSONObject("payload")==null?row.optString("text"):row.getJSONObject("payload").optString("text"));
        return values;
    }

    private void awaitMemory(String id,String text) throws Exception {
        DeviceUi.await("The companion memory did not persist its public fixture text",10000,()->memoryTexts(id).equals(List.of(text)));
    }

    private void assertMemoryEmpty(String id) throws Exception {
        assertEquals("The other/deleted memory escaped its companion scope",0,memoryTexts(id).size());
    }

    private void setDefaultMode(String mode) throws Exception {
        openGeneral();selectNativeOption(".droy-im-default-mode select",mode);
        DeviceUi.await("The default entry was not persisted",10000,()->mode.equals(companionGet("").getJSONObject("settings").getString("defaultMode")));
        tapSettled("[data-droy-settings-back]");awaitList();
    }

    private void openGeneral() throws Exception {
        ensureList();tapSettled("[data-droy-im-screen=list] button[aria-label='设置'],[data-droy-im-screen=list] button[aria-label='Settings']");
        DeviceUi.await("The visible General settings entry did not open",10000,()->ui.truth("!!document.querySelector('[data-droy-theme-choice=light]')"));
    }

    private void exerciseAppearance(String language,String theme) throws Exception {
        openGeneral();
        selectUiLanguage(language);
        tapSettled("[data-droy-theme-choice="+theme+"]");
        DeviceUi.await("The requested theme did not apply",10000,()->ui.truth("document.documentElement.getAttribute('data-ds-theme-source')==="+JSONObject.quote(theme)));
        capture(language+"-"+theme+"-general");tapSettled("[data-droy-settings-back]");awaitList();
        DeviceUi.await("Both visible companion avatars did not decode",10000,()->ui.truth("document.querySelectorAll('[data-droy-companion] img').length===2&&[...document.querySelectorAll('[data-droy-companion] img')].every(e=>e.complete&&e.naturalWidth>0)"));
        capture(language+"-"+theme+"-list");
        openCompanion("kaka");String draft="Unsent public fixture draft";type(".droy-im-input",draft);
        assertTrue("The phone IM composer is covered by its keyboard",ui.truth("(()=>{const r=document.querySelector('.droy-im-composer').getBoundingClientRect(),v=visualViewport;return r.bottom<=v.offsetTop+v.height+1&&r.left>=0&&r.right<=innerWidth+1})()"));
        capture(language+"-"+theme+"-keyboard");hideKeyboardAndSettle();
        assertTrue("Back from the keyboard left the companion chat",ui.truth("!!document.querySelector('[data-droy-im-screen=chat]')"));
        assertEquals(draft,ui.evaluate("document.querySelector('.droy-im-input').value"));
        type(".droy-im-input","");hideKeyboardAndSettle();
        tapSettled(".droy-im-composer button[aria-label='表情'],.droy-im-composer button[aria-label='Stickers']");
        DeviceUi.await("The sticker panel did not open",5000,()->ui.truth("document.querySelector('[data-droy-im-input-mode]')?.dataset.droyImInputMode==='stickers'"));
        capture(language+"-"+theme+"-stickers");ui.pressBack();
        DeviceUi.await("Back did not close only the sticker panel",5000,()->ui.truth("!!document.querySelector('[data-droy-im-screen=chat]')&&document.querySelector('[data-droy-im-input-mode]')?.dataset.droyImInputMode==='keyboard'"));
        ui.awaitPaint();tapSettled(".droy-im-chat-title");
        DeviceUi.await("The server profile did not follow the current locale",10000,()->(language.equals("zh")?"咔咔":"Kaka").equals(companionProfile("kaka").getString("name")));
        String personality=companionProfile("kaka").getString("personality");
        tapSettled("[data-droy-im-action=personality]");
        DeviceUi.await("The phone profile kept a stale-language persona",10000,()->ui.truth("document.querySelector('.droy-im-personality-editor')?.value.trim()==="+JSONObject.quote(personality.trim())));
        tapSettled("[data-droy-im-dialog=personality] [data-droy-im-action=cancel]");
        capture(language+"-"+theme+"-profile");ensureList();
        report("DROY_COMPANION_APPEARANCE_PASS "+language+" "+theme);
    }

    private void selectUiLanguage(String language) throws Exception {
        assertTrue(ui.truth("(()=>{const e=[...document.querySelectorAll('[data-droy-settings-panel] button[aria-haspopup=menu]')].find(e=>['中文','English'].includes(e.textContent.trim()));if(!e)return false;e.setAttribute('data-setup-language','');return true})()"));
        tapSettled("[data-setup-language]");String label=language.equals("zh")?"中文":"English";
        DeviceUi.await("The language menu did not expose the requested language",5000,()->ui.truth("(()=>{const e=[...document.querySelectorAll('[role=menuitem],[role=menuitemradio]')].find(e=>e.getClientRects().length&&e.textContent.trim()==="+JSONObject.quote(label)+");if(!e)return false;e.setAttribute('data-setup-language-option','');return true})()"));
        tapSettled("[data-setup-language-option]");
        DeviceUi.await("The requested UI language did not apply",10000,()->ui.truth("document.documentElement.lang.startsWith("+JSONObject.quote(language)+")"));
    }

    private void awaitList() throws Exception {
        DeviceUi.await("The IM list did not become the visible destination",15000,this::visibleCompanionList);ui.awaitPaint();
    }

    private boolean visibleCompanionList() throws Exception {
        return ui.truth("(()=>{const visible=e=>{if(!e||!e.getClientRects().length||e.closest('[inert],[aria-hidden=true]'))return false;const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility==='visible'&&s.display!=='none'&&s.opacity!=='0'};return visible(document.querySelector('[data-droy-im-screen=list]'))&&![...document.querySelectorAll('[data-droy-settings-panel]')].some(visible)})()");
    }

    private void prepareVisibleCompanionList() throws Exception {
        android.webkit.WebView view=ui.web();assertNotNull(view);
        AtomicReference<Map<String,Boolean>> facts=new AtomicReference<>();
        DeviceUi.await("The test-owned resumed Activity did not expose a visible foreground WebView",5000,()->{
            instrumentation.runOnMainSync(()->{
                Rect bounds=new Rect();Map<String,Boolean> state=new HashMap<>();
                state.put("resumed",ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).contains(activity));
                state.put("windowFocus",activity.hasWindowFocus()||view.hasWindowFocus());
                state.put("attached",view.isAttachedToWindow());state.put("shown",view.isShown());
                state.put("visibleBounds",view.getGlobalVisibleRect(bounds)&&!bounds.isEmpty());
                facts.set(state);
            });
            return !facts.get().containsValue(false);
        });
        report("DROY_SETUP_FOREGROUND_WEBVIEW "+new JSONObject(facts.get()));
        boolean settingsBlocked=ui.truth("(()=>{const panel=document.querySelector('[data-droy-settings-panel]');if(!panel||!panel.getClientRects().length||panel.closest('[inert]'))return false;const r=panel.getBoundingClientRect(),s=getComputedStyle(panel),hit=document.elementFromPoint(Math.max(0,Math.min(innerWidth-1,r.left+r.width/2)),Math.max(0,Math.min(innerHeight-1,r.top+r.height/2)));return r.width>0&&r.height>0&&s.visibility==='visible'&&s.display!=='none'&&!!hit&&panel.contains(hit)})()");
        if(settingsBlocked) {
            report("DROY_SETUP_RESTORED_SETTINGS_BLOCKED_IM one_actual_back");
            ui.pressBack();
        }
        awaitList();
    }

    private void ensureList() throws Exception {
        hideKeyboardAndSettle();
        if(!ui.truth("!!document.querySelector('[data-droy-im]')")) {
            tapSettled(".droy-work-mode-switch .droy-im-mode-trigger");
        }
        for(int step=0;step<3&&!ui.truth("!!document.querySelector('[data-droy-im-screen=list]')");step++) {
            ui.pressBack();ui.awaitPaint();
        }
        awaitList();
    }

    private void hideKeyboardAndSettle() throws Exception {
        hideKeyboardAndSettle(true);
    }

    private void hideKeyboardAndSettle(boolean explicitBack) throws Exception {
        // Accessibility can retain an obsolete IME window after a send button
        // takes focus. Only live app insets may authorize a navigation Back.
        if(explicitBack&&ui.nativeNavigationState().getBoolean("liveIme"))ui.pressBack();else ui.hideKeyboard();
        for(int frame=0;frame<2;frame++) {
            DeviceUi.await("The closed keyboard still owns the next touch",5000,()->!ui.keyboardVisible()&&!visibleInputWindow()
                &&ui.truth("document.documentElement.getAttribute('data-droy-ime-visible')!=='true'&&parseFloat(document.documentElement.style.getPropertyValue('--droy-ime-bottom')||'0')===0"));
            ui.awaitPaint();
        }
        report("DROY_IME_CLOSED "+new JSONObject().put("windows",inputWindowMetadata()).put("native",ui.nativeNavigationState()));
    }

    private boolean visibleInputWindow() throws Exception {
        for(JSONObject window:rows(inputWindowMetadata()))if(window.getBoolean("rootVisible")&&!window.getBoolean("emptyBounds"))return true;
        return false;
    }

    private JSONArray inputWindowMetadata() throws Exception {
        JSONArray result=new JSONArray();
        for(android.view.accessibility.AccessibilityWindowInfo window:ui.automation().getWindows()) {
            if(window.getType()!=android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD)continue;
            Rect bounds=new Rect();window.getBoundsInScreen(bounds);AccessibilityNodeInfo root=window.getRoot();
            result.put(new JSONObject().put("type",window.getType()).put("rootVisible",root!=null&&root.isVisibleToUser())
                .put("emptyBounds",bounds.isEmpty()).put("bounds",bounds.toShortString()).put("focused",window.isFocused()));
        }
        return result;
    }

    private void tapSettled(String selector) throws Exception {
        ui.visibleWebBounds(selector);ui.awaitPaint();ui.tap(selector);
    }

    private void selectNativeOption(String selector,String value) throws Exception {
        String label=(String)ui.evaluate("(()=>{const e=document.querySelector("+JSONObject.quote(selector)+");return [...e.options].find(o=>o.value==="+JSONObject.quote(value)+")?.textContent||null})()");
        assertNotNull("The expected local option was not in the real selector",label);
        tapSettled(selector);
        DeviceUi.await("The real native selector did not show its exact option",5000,()->{
            AccessibilityNodeInfo root=ui.automation().getRootInActiveWindow();if(root==null)return false;
            for(AccessibilityNodeInfo option:root.findAccessibilityNodeInfosByText(label)) {
                if(!label.contentEquals(option.getText()==null?"":option.getText())||!option.isVisibleToUser())continue;
                Rect bounds=new Rect();option.getBoundsInScreen(bounds);if(bounds.isEmpty())continue;
                ui.tapAt(bounds.centerX(),bounds.centerY());return true;
            }
            return false;
        });
        DeviceUi.await("The touched native option did not update its form",5000,()->ui.truth("document.querySelector("+JSONObject.quote(selector)+")?.value==="+JSONObject.quote(value)));ui.awaitPaint();
    }

    private void acceptNativeConfirm() throws Exception {
        DeviceUi.await("The memory action did not show a real native confirmation",5000,()->{
            AccessibilityNodeInfo root=ui.automation().getRootInActiveWindow();if(root==null)return false;
            for(AccessibilityNodeInfo button:root.findAccessibilityNodeInfosByViewId("android:id/button1")) {
                if(!button.isVisibleToUser()||!button.isEnabled())continue;
                Rect bounds=new Rect();button.getBoundsInScreen(bounds);if(bounds.isEmpty())continue;
                ui.tapAt(bounds.centerX(),bounds.centerY());return true;
            }
            return false;
        });ui.awaitPaint();
    }

    private void checkWorkHeaderGeometry() throws Exception {
        JSONObject geometry=(JSONObject)ui.evaluate("(()=>{const mode=document.querySelector('.droy-im-mode-trigger'),title=[...document.querySelectorAll('[class*=\"_crumbCurrent\"]')].find(e=>e.getClientRects().length);const describe=e=>{if(!e)return null;const r=e.getBoundingClientRect(),s=getComputedStyle(e);return {tag:e.tagName,className:String(e.className),rect:{left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height},paddingInlineStart:s.paddingInlineStart,leadingClearance:s.getPropertyValue('--dsh-frame-leading-clearance').trim(),display:s.display,overflow:s.overflow,position:s.position}};const a=mode?.getBoundingClientRect(),b=title?.getBoundingClientRect();return {mode:describe(mode),title:describe(title),ancestors:title?[title.parentElement,title.parentElement?.parentElement,title.parentElement?.parentElement?.parentElement].map(describe):[],leading:describe(document.querySelector('[data-shell-leading]')),center:describe(document.querySelector('[data-droy-center]')),overlap:!!a&&!!b&&a.left<b.right&&b.left<a.right&&a.top<b.bottom&&b.top<a.bottom}})()");
        report("DROY_COMPANION_HEADER_GEOMETRY "+geometry);
        assertFalse("The work mode selector overlaps the session title: "+geometry,geometry.getBoolean("overlap"));
    }

    private void exerciseLongHistory() throws Exception {
        new ChatExperienceChecks(instrumentation,context,activity,ui).appendSyntheticHistory();
        openCompanion("kaka");
        ui.evaluate("window.__setupScrollReloadMark=true");
        android.webkit.WebView reloading=ui.web();
        instrumentation.runOnMainSync(reloading::reload);
        DeviceUi.await("The WebView did not reload its message cache",10000,()->ui.truth("window.__setupScrollReloadMark===undefined&&typeof window.DroyMobile?.handleBack==='function'"));
        awaitChatReady();openCompanion("kaka");
        DeviceUi.await("The actual history did not expose its first 50-message page",10000,()->ui.truth("document.querySelectorAll('.droy-im-message').length===50&&!!document.querySelector('.droy-im-load-earlier')"));
        capture("companion-long-history-latest-page");
        for(int count=0;count<12&&!ui.truth("document.querySelector('.droy-im-messages').scrollTop<=1");count++) {
            Rect bounds=ui.webBounds(".droy-im-messages");
            ui.swipe(bounds.centerX(),bounds.top+60,bounds.centerX(),bounds.bottom-60,220);
            ui.awaitPaint();
        }
        DeviceUi.await("Trusted touch scrolling did not reach earlier history",5000,()->ui.truth("document.querySelector('.droy-im-messages').scrollTop<=1"));
        JSONObject anchor=visibleMessageAnchor();
        ui.tap(".droy-im-load-earlier");
        DeviceUi.await("The older history page was not prepended",10000,()->ui.truth("document.querySelectorAll('.droy-im-message').length>50"));
        ui.awaitPaint();
        assertEquals("Loading older history moved the visible message anchor",anchor.getDouble("offset"),messageOffset(anchor.getString("id")),3d);
        capture("companion-long-history-older-page");
        JSONObject saved=visibleMessageAnchor();
        ui.tap("[data-droy-im-screen=chat] .droy-im-header>.droy-im-icon:first-child");
        DeviceUi.await("Long history did not return to the list",5000,()->ui.truth("!!document.querySelector('[data-droy-im-screen=list]')"));
        openCompanion("kaka");
        assertEquals("Returning to the companion lost the read position",saved.getDouble("offset"),messageOffset(saved.getString("id")),3d);
        capture("companion-long-history-returned-anchor");
    }

    private JSONObject visibleMessageAnchor() throws Exception {
        Object value=ui.evaluate("(()=>{const l=document.querySelector('.droy-im-messages'),b=l.getBoundingClientRect(),e=[...l.querySelectorAll('.droy-im-message')].find(e=>{const r=e.getBoundingClientRect();return r.bottom>b.top+2&&r.top<b.bottom-2});return e?{id:e.dataset.messageId,offset:e.getBoundingClientRect().top-b.top}:null})()");
        assertTrue("No visible message was available as an anchor",value instanceof JSONObject);
        return (JSONObject)value;
    }

    private double messageOffset(String id) throws Exception {
        Object value=ui.evaluate("(()=>{const l=document.querySelector('.droy-im-messages'),e=[...l.querySelectorAll('.droy-im-message')].find(e=>e.dataset.messageId==="+JSONObject.quote(id)+");return e?e.getBoundingClientRect().top-l.getBoundingClientRect().top:null})()");
        assertTrue("The visible message anchor disappeared",value instanceof Number);
        return ((Number)value).doubleValue();
    }

    private void openCompanion(String id) throws Exception {
        if(!ui.truth("!!document.querySelector('[data-droy-im]')")) {
            ui.tap(".droy-work-mode-switch .droy-im-mode-trigger");
        }
        DeviceUi.await("The IM list was not ready",10000,()->ui.truth("!!document.querySelector('[data-droy-companion=\""+id+"\"]')"));
        ui.awaitPaint();ui.tap("[data-droy-companion='"+id+"']");
        DeviceUi.await("The companion composer did not appear",10000,()->ui.truth("!!document.querySelector('.droy-im-input')"));
        ui.awaitPaint();
    }

    private void sendCompanion(String value) throws Exception {
        type(".droy-im-input",value);ui.awaitPaint();ui.tap(".droy-im-send");
        DeviceUi.await("The message send was not acknowledged",10000,()->ui.truth("document.querySelector('.droy-im-input')?.value===''"));
        hideKeyboardAndSettle(false);
    }

    private void awaitCompanionTexts(String id,int minimum) throws Exception {
        DeviceUi.await("The local companion did not send its visible reply",30000,()->{
            int count=0;for(JSONObject row:companionMessages(id))if(row.optString("role").equals("assistant")&&row.optString("type").equals("text")&&!row.optString("text").isBlank())count++;
            return count>=minimum;
        });
        DeviceUi.await("The IM UI did not render the local reply",10000,()->ui.truth("document.querySelectorAll('.droy-im-message.is-assistant').length>0"));
        ui.awaitPaint();
    }

    private List<JSONObject> companionMessages(String id) throws Exception {return rows(companionGet("/"+id+"/messages").getJSONArray("messages"));}

    private static List<JSONObject> rows(JSONArray source) throws Exception {
        List<JSONObject> result=new ArrayList<>();for(int index=0;index<source.length();index++)result.add(source.getJSONObject(index));return result;
    }

    private JSONObject companionGet(String path) throws Exception {
        return companionRequest(path,"GET",null);
    }

    private JSONObject companionRequest(String path,String method,JSONObject body) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+RuntimeService.webPort+"/droy/companions"+path).openConnection();
        connection.setConnectTimeout(1000);connection.setReadTimeout(3000);
        connection.setRequestProperty("Cookie","droy_session="+RuntimeService.webToken);
        connection.setRequestMethod(method);
        if(body!=null) {
            connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");
            try(var output=connection.getOutputStream()){output.write(body.toString().getBytes(StandardCharsets.UTF_8));}
        }
        try {
            assertEquals("The real companion API did not accept its application session",200,connection.getResponseCode());
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                byte[] chunk=new byte[8192];int count;
                while((count=input.read(chunk))>=0) {assertTrue("Companion fixture response was unexpectedly large",bytes.size()+count<=1024*1024);bytes.write(chunk,0,count);}
                return new JSONObject(bytes.toString("UTF-8"));
            }
        } finally {connection.disconnect();}
    }

    @Test public void assistantWithoutAccessibilityCannotCompleteTheWizard() throws Exception {
        assertFalse("Run this case with system accessibility disabled before instrumentation",
            io.droyagent.androiduse.DroyAccessibilityService.isConnected(context));
        selectMode("assistant");
        testModel();
        next(3);
        ui.tap("[data-droy-setup-shizuku=skip]");
        next(4);
        next(5);
        verifySelectedCapabilities();
        JSONObject state = SetupManager.status(context);
        assertFalse("Missing accessibility was reported as installation success", state.getBoolean("canComplete"));
        if (!ui.truth("document.querySelector('" + NEXT + "').disabled")) {
            ui.tap(NEXT);
            DeviceUi.await("The missing permission did not produce actionable feedback", 10000,
                () -> ui.truth("!!document.querySelector('[data-droy-setup-error]')"));
        }
        awaitStep(5);
        assertThrows(IllegalStateException.class, () -> SetupManager.complete(context));
        assertFalse(SetupManager.status(context).getBoolean("completed"));
        captureAndCheck("permissions-missing-accessibility");
        assertModelIsolation();
        passed = true;
    }

    @Test public void assistantPracticeChecksRealObservationInputClickAndReadback() throws Exception {
        assertTrue("Run this case with system accessibility enabled before instrumentation",
            io.droyagent.androiduse.DroyAccessibilityService.isConnected(context));
        selectMode("assistant");
        testModel();
        next(3);
        ui.tap("[data-droy-setup-shizuku=skip]");
        next(4);
        next(5);
        assertFalse("Granting accessibility cannot substitute for the operation test", SetupManager.status(context).getBoolean("canComplete"));
        ui.tap("[data-droy-setup-device-test]");
        DeviceUi.await("The actual operation practice did not pass", 25000,
            () -> SetupManager.status(context).getJSONObject("deviceVerification").optBoolean("passed"));
        awaitStep(5);
        verifySelectedCapabilities();
        JSONObject state = SetupManager.status(context);
        assertTrue("Selected capabilities did not become ready", state.getBoolean("canComplete"));
        assertEquals("accessibility", state.getJSONObject("deviceVerification").getString("channel"));
        DeviceUi.await("Practice left an active execution indicator", 5000,
            () -> !ToolProgress.current(context).optBoolean("active"));
        captureAndCheck("permissions-operation-verified");
        next(6);
        finishWizard();
        assertModelIsolation();
        passed = true;
    }

    private void launch() throws Exception {
        PowerManager power=context.getSystemService(PowerManager.class);
        KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
        boolean interactive=power!=null&&power.isInteractive();
        boolean keyguardLocked=keyguard==null||keyguard.isKeyguardLocked();
        boolean deviceLocked=keyguard==null||keyguard.isDeviceLocked();
        report("DROY_SETUP_LAUNCH_ENVIRONMENT "+new JSONObject().put("interactive",interactive)
            .put("keyguardLocked",keyguardLocked).put("deviceLocked",deviceLocked));
        assertTrue("Fixture environment: wake the device before launching the visible UI test",interactive);
        assertFalse("Fixture environment: unlock the device before launching the visible UI test",keyguardLocked||deviceLocked);
        String command="am start -W -n "+context.getPackageName()+"/"+MainActivity.class.getName()+" -f 0x10008000";
        try(InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation
                .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command));
                ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            RuntimeInstaller.copy(input,output);
            assertTrue("The explicit development Activity launch failed",output.toString("UTF-8").contains("Status: ok"));
        }
        AtomicReference<MainActivity> resumed=new AtomicReference<>();
        DeviceUi.await("The installation Activity did not resume",45000,()->{
            instrumentation.runOnMainSync(()->{
                for(android.app.Activity candidate:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if(candidate instanceof MainActivity main)resumed.set(main);
            });
            return resumed.get()!=null;
        });
        activity=resumed.get();
        // Keep this test-owned window awake during slow turns; the existing
        // restart/After finish paths release the window and its temporary flag.
        instrumentation.runOnMainSync(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        ui = new DeviceUi(instrumentation, activity);
        awaitChatReady();
    }

    private void awaitChatReady() throws Exception {
        try {
            DeviceUi.await("The DSH installation page did not become ready: " + RuntimeService.status, 120000,
                () -> RuntimeService.ready && ui.web() != null && ui.nativeUiReady() && !ui.loadingOverlayShown()
                    && ui.truth("typeof window.DroyMobile?.handleBack==='function'&&document.documentElement.dataset.droyUiReady==='true'"));
        } catch (AssertionError failure) {
            String status=RuntimeService.status,diagnostics=RuntimeService.diagnosticsSnapshot();
            String token=RuntimeService.webToken;
            if(!token.isEmpty()){status=status.replace(token,"[redacted]");diagnostics=diagnostics.replace(token,"[redacted]");}
            JSONObject state=new JSONObject().put("status",status).put("runtimeReady",RuntimeService.ready)
                .put("runtimeDiagnostics",diagnostics);
            try {
                state.put("webViewPresent",ui.web()!=null).put("nativeUiReady",ui.nativeUiReady())
                    .put("loadingOverlayShown",ui.loadingOverlayShown()).put("nativeNavigation",ui.nativeNavigationState());
                if(ui.web()!=null)state.put("document",ui.evaluate("({path:location.pathname,uiReady:document.documentElement.dataset.droyUiReady||null,insetsReady:document.documentElement.dataset.droyInsetsReady||null,layoutReady:getComputedStyle(document.documentElement).getPropertyValue('--droy-mobile-layout-ready').trim(),frame:!!document.querySelector('[data-droy-frame]'),drawerOpen:!!document.querySelector('[data-droy-drawer-open]'),workHeader:!!document.querySelector('.droy-work-header'),composer:!!document.querySelector('[data-composer-input]'),bridge:typeof window.DroyMobile?.handleBack==='function',root:!!document.getElementById('root'),slotErrors:document.querySelectorAll('[data-slot-error]').length,setup:!!document.querySelector('[data-droy-setup]'),messages:!!document.querySelector('[data-droy-im]')})"));
            } catch(Exception|AssertionError diagnosticFailure) {failure.addSuppressed(diagnosticFailure);}
            report("DROY_SETUP_STARTUP_FAILURE "+state);
            throw new AssertionError(failure.getMessage()+"; final startup state="+state,failure);
        }
    }

    private void restart() throws Exception {
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.runOnMainSync(() -> RuntimeService.requestStop(context));
        DeviceUi.await("The fixture engine did not stop for restart", 25000, RuntimeService::canManageHistory);
        launch();
    }

    private void awaitStep(int step) throws Exception {
        try {
            DeviceUi.await("Installation did not reach step " + step, 15000,
                () -> ui.truth("document.querySelector('" + ROOT + "')?.getAttribute('data-droy-setup-step')==='" + step + "'"));
        } catch (AssertionError failure) {
            JSONObject state = SetupManager.status(context);
            report("DROY_SETUP_TRANSITION " + new JSONObject().put("expectedStep", step).put("nativeStep", state.getInt("step"))
                .put("completed", state.getBoolean("completed")).put("source", state.getString("source"))
                .put("clicks", ui.evaluate("window.__setupClicks||[]")).put("completeRequests", ui.evaluate("window.__setupCompleteRequests||0")));
            throw failure;
        }
    }

    private void selectMode(String mode) throws Exception {
        ui.evaluate("window.__setupClicks=[];window.__setupCompleteRequests=0;const original=window.fetch;window.fetch=function(...args){if(String(args[0]).endsWith('/droy/setup/complete'))window.__setupCompleteRequests++;return original.apply(this,args)};document.addEventListener('click',e=>{const b=e.target.closest?.('[data-droy-setup-next],[data-droy-setup-finish]');if(b)window.__setupClicks.push({step:document.querySelector('[data-droy-setup]')?.getAttribute('data-droy-setup-step'),action:b.hasAttribute('data-droy-setup-finish')?'finish':'next',trusted:e.isTrusted,detail:e.detail,time:e.timeStamp})},true)");
        ui.tap("[data-droy-setup-mode=" + mode + "]");
        next(2);
        assertEquals(mode, SetupManager.status(context).getString("mode"));
        assertTrue(SetupManager.status(context).getBoolean("modeSelected"));
    }

    private void next(int expected) throws Exception {
        DeviceUi.await("The installation next action remained disabled", 15000,
            () -> ui.truth("!!document.querySelector('" + NEXT + "')&&!document.querySelector('" + NEXT + "').disabled"));
        tapSettled(NEXT);
        awaitStep(expected);
    }

    private void inspectEmbeddedModelSettings() throws Exception {
        String panel = "[data-droy-setup-model-settings]";
        DeviceUi.await("The shared DSH model provider directory did not load", 15000,
            () -> ui.truth("Array.from(document.querySelectorAll('" + panel + " button')).some(b=>!b.disabled&&['Add model provider','添加模型提供商'].includes(b.textContent.trim()))"));
        assertFalse("Embedding models must not open a second settings shell", ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        capture("existing-model-settings");
        ui.evaluate("Array.from(document.querySelectorAll('" + panel + " button')).find(b=>!b.disabled&&['Add model provider','添加模型提供商'].includes(b.textContent.trim())).setAttribute('data-droy-test-add-provider','true')");
        ui.tap("[data-droy-test-add-provider]");
        DeviceUi.await("DSH did not open a model provider configuration form", 10000,
            () -> ui.truth("!!document.querySelector('" + panel + " select[aria-label]')&&!!document.querySelector('" + panel + " input')"));
        capture("existing-model-provider-form");
        ui.evaluate("Array.from(document.querySelectorAll('" + panel + " button')).find(b=>!b.disabled&&['Cancel','取消'].includes(b.textContent.trim())).setAttribute('data-droy-test-cancel-provider','true')");
        ui.tap("[data-droy-test-cancel-provider]");
        awaitStep(2);
    }

    private void testModel() throws Exception {
        DeviceUi.await("The local model did not become selectable", 15000,
            () -> ui.truth("!!document.querySelector('" + MODEL_TEST + "')&&!document.querySelector('" + MODEL_TEST + "').disabled"));
        captureAndCheck("models");
        ui.tap(MODEL_TEST);
        DeviceUi.await("The local model connection and tool protocol did not verify", 20000, () -> {
            JSONObject model = SetupManager.status(context).getJSONObject("modelVerification");
            return model.optBoolean("passed") && model.optBoolean("toolsPassed");
        });
        DeviceUi.await("The model test UI did not settle", 5000,
            () -> ui.truth("!!document.querySelector('" + NEXT + "')&&!document.querySelector('" + NEXT + "').disabled"));
        assertEquals("One model test should make exactly two short local requests", 2, modelEvents().size());
        captureAndCheck("models-verified");
    }

    private void verifySelectedCapabilities() throws Exception {
        ui.tap("[data-droy-setup-verify]");
        DeviceUi.await("Installation verification UI did not settle", 15000,
            () -> ui.truth("!!document.querySelector('[data-droy-setup-verify]')&&!document.querySelector('[data-droy-setup-verify]').disabled"));
        DeviceUi.await("Installation checks were not rendered", 5000,
            () -> ui.truth("document.querySelectorAll('[data-droy-setup-check]').length>0"));
    }

    private void finishWizard() throws Exception {
        ui.tap("[data-droy-setup-finish]");
        DeviceUi.await("Installation did not complete", 15000,
            () -> SetupManager.status(context).getBoolean("completed") && !ui.truth("!!document.querySelector('" + ROOT + "')"));
    }

    private void type(String selector, String value) throws Exception {
        ui.awaitPaint();
        try { ui.showKeyboard(selector); }
        catch (AssertionError failure) {
            report("DROY_SETUP_INPUT_GEOMETRY " + ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
                + "),s=e&&getComputedStyle(e),r=document.documentElement;return {ime:r.getAttribute('data-droy-ime-visible'),imeBottom:r.style.getPropertyValue('--droy-ime-bottom'),safeTop:r.style.getPropertyValue('--droy-safe-top'),viewport:visualViewport&&{height:visualViewport.height,offsetTop:visualViewport.offsetTop,scale:visualViewport.scale},rootHeight:document.querySelector('[data-droy-setup]')?.getBoundingClientRect().height,style:s&&{height:s.height,minHeight:s.minHeight,margin:s.scrollMarginBlock,zoom:s.zoom},portrait:matchMedia('(orientation:portrait)').matches}})()"));
            throw failure;
        }
        DeviceUi.await("The installation field did not receive touch focus", 5000,
            () -> ui.truth("document.activeElement===document.querySelector(" + JSONObject.quote(selector) + ")"));
        int existing = ((String) ui.evaluate("document.querySelector(" + JSONObject.quote(selector) + ").value")).length();
        ui.replaceFocusedText(value, existing);
        DeviceUi.await("Installation input did not accept the Android editor commit", 5000,
            () -> value.equals(ui.evaluate("document.querySelector(" + JSONObject.quote(selector) + ").value")));
    }

    private List<JSONObject> modelEvents() throws Exception {
        return fixtureEvents("setup-model-events.jsonl");
    }

    private List<JSONObject> fixtureEvents(String name) throws Exception {
        File path = new File(home, name);
        List<JSONObject> rows = new ArrayList<>();
        if (path.isFile()) for (String line : Files.readAllLines(path.toPath()))
            if (!line.isBlank()) rows.add(new JSONObject(line));
        return rows;
    }

    private void assertModelIsolation() throws Exception {
        List<JSONObject> calls = modelEvents();
        assertEquals("The installation made unexpected model requests", 2, calls.size());
        for (JSONObject call : calls) {
            assertFalse("Installation included Agent prompt context", call.toString().contains(SetupFixtureProvider.CONTEXT_CANARY));
            JSONArray messages = call.getJSONArray("messages");
            assertEquals("Installation included conversation history", 1, messages.length());
            assertEquals("user", messages.getJSONObject(0).getString("role"));
        }
        String greeting = calls.get(0).getJSONArray("messages").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0).getString("text");
        assertTrue("The greeting must remain fixed public test content", Set.of("你好", "Hello").contains(greeting));
        assertEquals(0, calls.get(0).getJSONArray("tools").length());
        assertEquals("The protocol check exposed real phone tools", "droy_setup_echo",
            calls.get(1).getJSONArray("tools").getString(0));
        assertEquals(1, calls.get(1).getJSONArray("tools").length());
    }

    private void configureMaximumTextFont() throws Exception {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("layoutMaxTextFont"))) return;
        ui.evaluate("window.DroyMobile.openPermissions()");
        DeviceUi.await("Layout settings did not open", 10000,
            () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        ui.tap("[data-droy-settings-section=general]");
        ui.tap("[data-droy-font-expander]");
        String increase = "[data-droy-font-row=size][data-droy-font-role=text] [data-droy-font-step=increase]";
        for (int n = 0; n < 60 && !ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"); n++) ui.tap(increase);
        assertTrue("The DSH font control did not reach its maximum", ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"));
        ui.pressBack();
        DeviceUi.await("Layout settings did not close", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        awaitStep(1);
    }

    private void captureAndCheck(String stage) throws Exception {
        JSONObject geometry = (JSONObject) ui.evaluate("(()=>{const root=document.querySelector('" + ROOT + "'),r=root.getBoundingClientRect();"
            + "let fits=true;for(const e of root.querySelectorAll('p,h1,h2,h3,h4,label,summary,button')){if(!e.getClientRects().length)continue;"
            + "const w=document.createTreeWalker(e,NodeFilter.SHOW_TEXT);while(w.nextNode()){if(!w.currentNode.textContent.trim())continue;"
            + "const x=document.createRange();x.selectNodeContents(w.currentNode);for(const b of x.getClientRects())if(b.width>.1&&(b.left<r.left-1||b.right>r.right+1))fits=false;}}"
            + "const small=[];for(const e of root.querySelectorAll('button,select,input:not([type=checkbox]):not([type=radio]),textarea')){"
            + "if(!e.getClientRects().length)continue;const t=e.getAttribute('role')==='switch'?e.closest('label')||e:e,b=t.getBoundingClientRect();"
            + "if(b.width<47||b.height<47)small.push({tag:t.tagName,label:t.getAttribute('aria-label'),width:b.width,height:b.height});}"
            + "return {fits,small,overflow:root.scrollWidth>root.clientWidth+2||document.documentElement.scrollWidth>innerWidth+1}})()");
        assertTrue("Installation text is clipped: " + geometry, geometry.getBoolean("fits"));
        assertEquals("Installation controls are smaller than 48 CSS pixels: " + geometry, 0, geometry.getJSONArray("small").length());
        assertFalse("Installation has horizontal overflow: " + geometry, geometry.getBoolean("overflow"));
        capture(stage);
    }

    private void capture(String stage) throws Exception {
        try {ui.awaitPaint(".droy-setup-progress");}
        catch(AssertionError failure) {
            report("DROY_SETUP_ANIMATIONS "+ui.evaluate("document.getAnimations().filter(a=>['running','pending'].includes(a.playState)).map(a=>{const e=a.effect?.target,t=a.effect?.getComputedTiming(),r=e?.getBoundingClientRect();return {name:a.animationName,playState:a.playState,tag:e?.tagName,className:String(e?.className),text:e?.textContent?.slice(0,400),wizard:!!e?.closest('[data-droy-setup]'),inert:!!e?.closest('[inert]'),rect:r?{width:r.width,height:r.height,top:r.top,bottom:r.bottom}:null,duration:t?.duration,iterations:String(t?.iterations),endTime:String(t?.endTime),currentTime:a.currentTime}})"));
            captureFrame(stage+"-animation-timeout");
            throw failure;
        }
        captureFrame(stage);
    }

    private void captureFrame(String stage) throws Exception {
        Bitmap image = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
        assertNotNull(image);
        try {
            File directory = new File(context.getExternalFilesDir(null), "setup-ui-evidence");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            String label = InstrumentationRegistry.getArguments().getString("evidenceLabel", "setup").replaceAll("[^A-Za-z0-9_-]", "_");
            File path = File.createTempFile(label + "-" + stage + "-", ".png", directory);
            try (FileOutputStream output = new FileOutputStream(path)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); }
            JSONObject conditions = (JSONObject) ui.evaluate("({width:innerWidth,height:innerHeight,textFont:getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim(),"
                + "theme:document.documentElement.getAttribute('data-ds-theme-source'),step:document.querySelector('[data-droy-setup]')?.getAttribute('data-droy-setup-step')||null})");
            conditions.put("systemFontScale", activity.getResources().getConfiguration().fontScale);
            conditions.put("requestedLocale", InstrumentationRegistry.getArguments().getString("setupLocale", "system"));
            conditions.put("cloudModel", false).put("source", "local_scripted_dsh_adapter");
            Files.write(new File(directory, path.getName() + ".json").toPath(), conditions.toString(2).getBytes(StandardCharsets.UTF_8));
            report("DROY_SETUP_EVIDENCE " + path.getAbsolutePath() + "\nDROY_SETUP_CONDITIONS " + conditions);
        } finally { image.recycle(); }
    }

    private void report(String message) {
        Bundle status = new Bundle(); status.putString("stream", "\n" + message + "\n"); instrumentation.sendStatus(2, status);
    }

    private static void restorePreferences(SharedPreferences preferences, Map<String, ?> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof Boolean flag) editor.putBoolean(key, flag);
            else if (value instanceof String text) editor.putString(key, text);
            else if (value instanceof Integer number) editor.putInt(key, number);
            else if (value instanceof Long number) editor.putLong(key, number);
            else if (value instanceof Float number) editor.putFloat(key, number);
            else if (value instanceof Set<?> set) {
                Set<String> copy = new HashSet<>(); for (Object item : set) copy.add((String) item); editor.putStringSet(key, copy);
            } else throw new AssertionError("Unsupported preference type: " + key);
        }
        assertTrue("Could not restore installation fixture preferences", editor.commit());
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> rows = new TreeMap<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return rows;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                String name = root.relativize(path).toString();
                if (Files.isSymbolicLink(path)) rows.put(name, "link:" + Files.readSymbolicLink(path));
                else if (Files.isDirectory(path)) rows.put(name, "directory");
                else rows.put(name, RuntimeInstaller.hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            }
        }
        return rows;
    }
}
