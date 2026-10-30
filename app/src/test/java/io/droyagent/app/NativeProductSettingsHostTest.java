package io.droyagent.app;

import android.content.SharedPreferences;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class NativeProductSettingsHostTest {
    private SharedPreferences notificationPreferences(AtomicReference<SharedPreferences.OnSharedPreferenceChangeListener> listener,
            AtomicInteger unregistered) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                if (method.getName().equals("registerOnSharedPreferenceChangeListener")) {
                    assertNull(listener.getAndSet((SharedPreferences.OnSharedPreferenceChangeListener) args[0]));
                    return null;
                }
                if (method.getName().equals("unregisterOnSharedPreferenceChangeListener")) {
                    assertSame(listener.get(), args[0]);
                    unregistered.incrementAndGet();
                    return null;
                }
                throw new AssertionError("Unexpected preference access: " + method.getName());
            });
    }
    @Test public void notificationObservationOnlyPublishesRelevantStateChanges() throws Exception {
        AtomicReference<SharedPreferences.OnSharedPreferenceChangeListener> listener = new AtomicReference<>();
        AtomicInteger changes = new AtomicInteger(), unregistered = new AtomicInteger();
        SharedPreferences preferences = notificationPreferences(listener, unregistered);
        try (AutoCloseable ignored = NativeProductSettingsHost.observeNotificationState(preferences, changes::incrementAndGet)) {
            for (String key : new String[]{"notificationManagementData", "notificationManagementEnabled", "allowNotifications",
                    "allowRoot", "capabilitiesDefaultDeny", "toolsStopped", "backgroundRuntimeEnabled",
                    "backgroundRuntimeStopped", "languagePreference", null})
                listener.get().onSharedPreferenceChanged(preferences, key);
            assertEquals(10, changes.get());
            for (String key : new String[]{"petId", "assistantName", "browserEncryptedDns", "apiKey", "allowLocationRead"})
                listener.get().onSharedPreferenceChanged(preferences, key);
            assertEquals(10, changes.get());
        }
        assertEquals(1, unregistered.get());
    }
    @Test public void notificationObservationStopsAfterIdempotentCloseIncludingQueuedCallbacks() throws Exception {
        AtomicReference<SharedPreferences.OnSharedPreferenceChangeListener> listener = new AtomicReference<>();
        AtomicInteger changes = new AtomicInteger(), unregistered = new AtomicInteger();
        SharedPreferences preferences = notificationPreferences(listener, unregistered);
        AutoCloseable subscription = NativeProductSettingsHost.observeNotificationState(preferences, changes::incrementAndGet);
        listener.get().onSharedPreferenceChanged(preferences, "notificationManagementData");
        subscription.close();
        subscription.close();
        listener.get().onSharedPreferenceChanged(preferences, "notificationManagementData");
        listener.get().onSharedPreferenceChanged(preferences, null);
        assertEquals(1, changes.get());
        assertEquals(1, unregistered.get());
    }
    @Test public void displayWriterCannotMutatePermissionsCredentialsOrMaintenance() {
        for (String key : new String[]{"allowControl", "backgroundRuntimeEnabled", "notificationManagementEnabled", "apiKey", "resetTheme", "clearHistory", "petId"})
            assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.checkedDisplay(key, true));
    }
    @Test public void displayModesAndBooleanValuesAreNotCoerced() throws Exception {
        assertEquals("adaptive", NativeProductSettingsHost.checkedDisplay("petExternalDisplay", "adaptive"));
        assertEquals(false, NativeProductSettingsHost.checkedDisplay("automationScreenDisplay", false));
        assertEquals(true, NativeProductSettingsHost.checkedDisplay("browserEncryptedDns", true));
        assertEquals(false, NativeProductSettingsHost.checkedDisplay("browserEncryptedDns", false));
        assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.checkedDisplay("petInternalDisplay", "sometimes"));
        assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.checkedDisplay("browserEncryptedDns", "false"));
    }
    @Test public void retiredBrandAndPetNamePreferencesCannotBeChanged() {
        for (String key : new String[]{"assistantName", "petName", "brandWelcomeTemplate", "brandHeroBadge", "brandSidebarLabel"})
            assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.checkedDisplay(key, "Custom"));
    }
    @Test public void operationBoundaryRejectsExtraFields() throws Exception {
        NativeProductSettingsHost.fields(new JSONObject().put("localEnabled", false), Set.of("localEnabled"));
        assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.fields(
            new JSONObject().put("localEnabled", false).put("operation", "send"), Set.of("localEnabled")));
    }

    @Test public void notificationConfigurationPreservesRulesAndExplicitSafetyChoices() throws Exception {
        JSONObject request = new JSONObject().put("operation", "configure").put("enabled", true)
            .put("ruleText", "Keep delivery updates\n保留配送进展").put("reminderMinutes", 10)
            .put("silentTakeoverEnabled", false);
        String original = request.toString();
        JSONObject checked = NativeProductSettingsHost.checkedNotificationRequest(request);
        assertEquals(request.getString("ruleText"), checked.getString("ruleText"));
        assertEquals(10, checked.getInt("reminderMinutes"));
        assertFalse(checked.getBoolean("silentTakeoverEnabled"));
        assertTrue(checked.getBoolean("enabled"));
        assertEquals(original, request.toString());
        checked.put("silentTakeoverEnabled", true);
        assertFalse(request.getBoolean("silentTakeoverEnabled"));
    }
    @Test public void notificationConfigurationRejectsInvalidDelaysAndCoercedSwitches() throws Exception {
        for (Object minutes : new Object[]{0, 6, 10.5, "5", JSONObject.NULL})
            assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.checkedNotificationRequest(
                new JSONObject().put("operation", "configure").put("reminderMinutes", minutes)));
        for (String key : new String[]{"enabled", "resetRule", "silentTakeoverEnabled"})
            assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.checkedNotificationRequest(
                new JSONObject().put("operation", "configure").put(key, "false")));
        assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.checkedNotificationRequest(
            new JSONObject().put("operation", "configure").put("ruleText", "Keep deliveries").put("resetRule", true)));
    }
    @Test public void notificationSettingsRejectRetiredAndInternalOperations() throws Exception {
        for (String operation : new String[]{"upsertRule", "deleteRule", "classificationContext", "send", "get"})
            assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.checkedNotificationRequest(
                new JSONObject().put("operation", operation)));
        for (String field : new String[]{"cloudAnalysisEnabled", "exceptions", "packages", "allowRoot", "apiKey"})
            assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.checkedNotificationRequest(
                new JSONObject().put("operation", "configure").put(field, true)));
    }
    @Test public void appSelectionOnlyAcceptsActualPackageIdentifiersAndSupportedModes() throws Exception {
        JSONObject selected = new JSONObject().put("operation", "setApps").put("enabled", true)
            .put("packageNames", new org.json.JSONArray().put("example.shipping").put("example.other"));
        JSONObject checked = NativeProductSettingsHost.checkedNotificationRequest(selected);
        assertEquals(selected.toString(), checked.toString());
        assertEquals("allow", NativeProductSettingsHost.checkedNotificationRequest(new JSONObject()
            .put("operation", "setAppMode").put("packageName", "example.shipping").put("mode", "allow")).getString("mode"));
        assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.checkedNotificationRequest(
            new JSONObject().put("operation", "setAppMode").put("packageName", "example.shipping").put("mode", "block")));
        assertThrows(IllegalArgumentException.class, () -> NativeProductSettingsHost.checkedNotificationRequest(
            new JSONObject().put("operation", "setApps").put("enabled", false)
                .put("packageNames", new org.json.JSONArray().put("not an app"))));
    }

    @Test public void diagnosticsOnlyReturnsStructuredFieldsAndKnownCodeCounts() throws Exception {
        JSONObject versions = new JSONObject().put("droyVersion", "0.0.3").put("dshVersion", "1.2.3-beta.1")
            .put("mobileVersion", "2026.10.29").put("apiKey", "fictional-version-key");
        JSONObject progress = new JSONObject().put("phase", "verifying").put("active", true)
            .put("taskId", "private-task").put("summary", "private-user-input").put("pending", "private-question");
        String raw = "EACCES /private/user/file fictional-api-key\nunknown private-response\nEACCES token=fictional-token\nERR_MODULE_NOT_FOUND /private/module\n";
        JSONObject result = NativeProductSettingsHost.diagnosticSnapshot(versions, "16", 36, true, true, false, progress, raw);
        assertEquals("verifying", result.getString("phase")); assertTrue(result.getBoolean("phaseActive"));
        assertEquals("16", result.getString("androidRelease")); assertEquals(36, result.getInt("androidApi"));
        assertEquals(2, result.getJSONArray("notices").length());
        assertEquals("EACCES", result.getJSONArray("notices").getJSONObject(0).getString("code"));
        assertEquals(2, result.getJSONArray("notices").getJSONObject(0).getInt("count"));
        for (String forbidden : new String[]{"private", "fictional", "apiKey", "summary", "taskId", "pending", "raw", "unknown"})
            assertFalse(result.toString().contains(forbidden));
    }
    @Test public void diagnosticsHasNoRawFallbackForUnknownOrMalformedSources() throws Exception {
        JSONObject result = NativeProductSettingsHost.diagnosticSnapshot(new JSONObject().put("droyVersion", "/private/version")
            .put("dshVersion", "Bearer fictional-secret"), "/private/android", 36, false, false, true,
            new JSONObject().put("phase", "user-controlled-phase").put("active", "true"), "arbitrary exception message: secret=fictional-secret");
        assertFalse(result.has("phase")); assertFalse(result.has("phaseActive"));
        assertFalse(result.has("droyVersion")); assertFalse(result.has("dshVersion")); assertFalse(result.has("androidRelease"));
        assertEquals(0, result.getJSONArray("notices").length()); assertFalse(result.toString().contains("fictional"));
        JSONObject bounded = NativeProductSettingsHost.diagnosticSnapshot(new JSONObject(), "16", 36, false, false, false,
            new JSONObject(), "ENOSPC /private/source\n".repeat(1000));
        assertTrue(bounded.getJSONArray("notices").getJSONObject(0).getInt("count") <= 256);
    }
}
