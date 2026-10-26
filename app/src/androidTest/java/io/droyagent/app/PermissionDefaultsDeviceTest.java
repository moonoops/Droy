package io.droyagent.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import io.droyagent.androiduse.AndroidUseExecutor;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Changes only capability choices and the saved stop flag; model settings/history remain untouched. */
@RunWith(AndroidJUnit4.class)
public final class PermissionDefaultsDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences settings;
    private MainActivity activity;
    private DeviceUi ui;
    private final Map<String, String> groups = new LinkedHashMap<>();
    private final Map<String, Boolean> saved = new HashMap<>();
    private boolean preferencesCaptured;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Stop the engine before capability preference tests", RuntimeService.canManageHistory());
        assertFalse(RuntimeService.ready);
        settings = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        JSONArray entries = new CapabilityCatalog(context).groups();
        assertEquals("Every product capability must be exercised", 24, entries.length());
        for (int index = 0; index < entries.length(); index++) {
            JSONObject group = entries.getJSONObject(index);
            groups.put(group.getString("preference"), group.getString("label"));
        }
        for (String key : groups.keySet()) if (settings.contains(key)) saved.put(key, settings.getBoolean(key, false));
        if (settings.contains(ProductPermissions.STOPPED))
            saved.put(ProductPermissions.STOPPED, settings.getBoolean(ProductPermissions.STOPPED, false));
        if (settings.contains(BackgroundRuntime.STOPPED))
            saved.put(BackgroundRuntime.STOPPED, settings.getBoolean(BackgroundRuntime.STOPPED, false));
        preferencesCaptured = true;
    }

    @After public void tearDown() throws Exception {
        try {
            if (activity != null) {
                MainActivity closing = activity;
                instrumentation.runOnMainSync(closing::finish);
                // NEW_TASK can otherwise target the finishing instance; startActivitySync
                // requires a new Activity and would wait for a launch that never happens.
                await("Capability fixture Activity was not destroyed after finish", () -> {
                    boolean[] destroyed = new boolean[1];
                    instrumentation.runOnMainSync(() -> destroyed[0] = closing.isDestroyed());
                    return destroyed[0];
                });
                instrumentation.waitForIdleSync();
            }
        } finally {
            if (activity != null) {
                instrumentation.runOnMainSync(RuntimeService::requestStop);
                DeviceUi.await("Capability test left its automatically started engine active", 15000, RuntimeService::canManageHistory);
            }
            if (preferencesCaptured) {
                SharedPreferences.Editor restore = settings.edit();
                for (String key : groups.keySet()) restoreChoice(restore, key);
                restoreChoice(restore, ProductPermissions.STOPPED);
                restoreChoice(restore, BackgroundRuntime.STOPPED);
                assertTrue("Could not restore the original capability choices", restore.commit());
                RuntimeService.applySavedOperationState();
            }
        }
    }

    @Test public void missingCapabilityPreferencesStayDisabledAndShowUncheckedSwitches() throws Exception {
        Map<String, ?> unrelated = unrelatedPreferences();
        clearOnlyCapabilityChoices();
        assertFalse("New product preferences should not require a per-run resume", ProductPermissions.stopped(context));
        assertFalse(ProductPermissions.allEnabled(context));
        for (String key : groups.keySet()) {
            assertFalse("Fresh default must not be confused with an explicit stored choice: " + key, settings.contains(key));
            assertFalse("A missing product capability must remain disabled: " + key, ProductPermissions.allowed(settings, key));
        }
        openCapabilities();
        assertFalse("Opening the app explicitly starts its runtime", BackgroundRuntime.explicitlyStopped(context));
        for (Map.Entry<String, String> group : groups.entrySet()) {
            assertTrue("Missing or unexpectedly enabled capability switch: " + group.getKey(), ui.truth(
                    "document.querySelector('[data-droy-preference=\"" + group.getKey() + "\"] [role=\"switch\"]')?.getAttribute('aria-checked') === 'false'"));
        }
        JSONArray states = new ProductBridge(context, "fictional-defaults-test-not-listening")
                .capabilityStatus().getJSONArray("groups");
        assertEquals(24, states.length());
        for (int index = 0; index < states.length(); index++) assertFalse(states.getJSONObject(index).getBoolean("allowed"));
        assertEquals("Capability defaults must preserve name, DNS and other product configuration", unrelated, unrelatedPreferences());
    }

    @Test public void individualUserDenialSurvivesActivityRecreation() throws Exception {
        clearOnlyCapabilityChoices();
        openCapabilities();
        ui.click("[data-droy-preference='allowContacts'] [role='switch']");
        DeviceUi.await("The permission switch did not save the user's choice", 5000,
                () -> settings.contains("allowContacts") && !ProductPermissions.allowed(settings, "allowContacts"));
        assertTrue("The user choice must be explicitly stored", settings.contains("allowContacts"));
        assertFalse(ProductPermissions.allowed(settings, "allowContacts"));
        MainActivity previous = activity;
        instrumentation.runOnMainSync(previous::recreate);
        await("Activity recreation did not produce a resumed replacement", () -> {
            AtomicReference<MainActivity> replacement = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                for (Activity candidate : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if (candidate instanceof MainActivity main && candidate != previous) replacement.set(main);
            });
            if (replacement.get() == null) return false;
            activity = replacement.get();
            return true;
        });
        instrumentation.waitForIdleSync();
        ui.activity = activity;
        ui.openPermissions();
        assertFalse("Activity recreation reopened the user's disabled capability",
                ui.truth("document.querySelector('[data-droy-preference=\"allowContacts\"] [role=\"switch\"]')?.getAttribute('aria-checked') === 'true'"));
        for (Map.Entry<String, String> group : groups.entrySet()) {
            if (group.getKey().equals("allowContacts")) continue;
            assertTrue("An unrelated default changed: " + group.getKey(), ui.truth(
                    "document.querySelector('[data-droy-preference=\"" + group.getKey() + "\"] [role=\"switch\"]')?.getAttribute('aria-checked') === 'true'"));
        }
        assertFalse(ProductPermissions.allEnabled(context));
    }

    @Test public void pcSetupProviderReportsSavedChoicesAndExplicitlyEnablesAll() {
        Map<String, ?> unrelated = unrelatedPreferences();
        clearOnlyCapabilityChoices();
        assertTrue(settings.edit().putBoolean("allowCalendar", false).putBoolean("allowRoot", false)
                .putBoolean(ProductPermissions.STOPPED, true).commit());
        Map<String, ?> choicesBeforeStatus = new HashMap<>(settings.getAll());
        Bundle before = providerCall("status");
        assertEquals(1, before.getInt("schemaVersion"));
        assertFalse("Status must report actual individual denials", before.getBoolean("enabled"));
        assertTrue("Status must report the saved pause", before.getBoolean("stopped"));
        assertEquals("Reading connection status must preserve capability choices and the saved pause",
                choicesBeforeStatus, settings.getAll());
        Bundle enabled = providerCall("enable-all");
        assertEquals(1, enabled.getInt("schemaVersion"));
        assertTrue("Explicit PC setup should reopen all capability groups", enabled.getBoolean("enabled"));
        assertFalse("Explicit PC setup should resume tools", enabled.getBoolean("stopped"));
        for (String key : groups.keySet()) {
            assertTrue("PC enable-all must save an explicit choice: " + key, settings.contains(key));
            assertTrue(settings.getBoolean(key, false));
        }
        Bundle after = providerCall("status");
        assertEquals(1, after.getInt("schemaVersion"));
        assertTrue(after.getBoolean("enabled"));
        assertFalse(after.getBoolean("stopped"));
        assertEquals("PC setup must not rewrite unrelated product preferences", unrelated, unrelatedPreferences());
    }

    @Test public void providerStatusDoesNotTreatSavedPauseAsAnIndividualDenial() {
        clearOnlyCapabilityChoices();
        assertTrue(settings.edit().putBoolean(ProductPermissions.STOPPED, true).commit());
        Map<String, ?> choicesBeforeStatus = new HashMap<>(settings.getAll());
        Bundle status = providerCall("status");
        assertEquals(1, status.getInt("schemaVersion"));
        assertTrue("All capabilities are chosen even while tool execution is paused", status.getBoolean("enabled"));
        assertTrue(status.getBoolean("stopped"));
        assertEquals("Status must leave implicit capability defaults and the saved pause unchanged",
                choicesBeforeStatus, settings.getAll());
        assertThrows(IllegalArgumentException.class, () -> providerCall("unsupported-fixture-method"));
    }

    private Bundle providerCall(String method) {
        Bundle result = context.getContentResolver().call(
                Uri.parse("content://" + context.getPackageName() + ".permissions"), method, null, null);
        assertNotNull("Permission setup provider did not reply", result);
        assertTrue("Setup must report live accessibility connection explicitly", result.containsKey("accessibilityConnected"));
        assertEquals("Setup and Android Use must agree about the actual accessibility connection",
                new AndroidUseExecutor(context).capabilities(false).optBoolean("accessibilityConnected"),
                result.getBoolean("accessibilityConnected"));
        return result;
    }

    private void clearOnlyCapabilityChoices() {
        SharedPreferences.Editor reset = settings.edit();
        for (String key : groups.keySet()) reset.remove(key);
        assertTrue(reset.remove(ProductPermissions.STOPPED).commit());
    }

    private void restoreChoice(SharedPreferences.Editor restore, String key) {
        if (saved.containsKey(key)) restore.putBoolean(key, saved.get(key)); else restore.remove(key);
    }

    private Map<String, ?> unrelatedPreferences() {
        Map<String, Object> values = new HashMap<>(settings.getAll());
        for (String key : groups.keySet()) values.remove(key);
        values.remove(ProductPermissions.STOPPED);
        // A user launch clears this operational stop marker; it is not the
        // independently saved background-runtime enablement preference.
        values.remove(BackgroundRuntime.STOPPED);
        // DSH is the theme authority; opening its UI may migrate the former
        // native switch and refresh these derived shell caches.
        for (String key : new String[]{"darkTheme", "themePreference", "themeColorScheme", "themeBackgroundColor", "resetTheme"}) values.remove(key);
        return values;
    }

    private void openCapabilities() throws Exception {
        activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        instrumentation.waitForIdleSync();
        ui = new DeviceUi(instrumentation, activity);
        ui.openPermissions();
    }

    private static void await(String failure, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        do {
            if (condition.check()) return;
            SystemClock.sleep(20);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(failure);
    }

    private interface Condition { boolean check() throws Exception; }
}
