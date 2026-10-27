package io.droyagent.app;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProductPermissionsTest {
    private static final String[] CAPABILITY_KEYS = {
        "allowDeviceInfo", "allowApps", "allowObserve", "allowControl", "allowScreenshot", "allowRoot",
        "allowSettingsRead", "allowSettingsWrite", "allowNotifications", "allowCalendar", "allowContacts",
        "allowUsage", "allowFiles", "allowFileExport", "allowBrowser", "allowBrowserControl", "allowCompute",
        "allowDiagnostics", "allowPrivacyUsage"
    };

    @Test public void everyMissingCapabilityDefaultsToEnabledWithoutWritingPreferences() {
        Map<String, Boolean> saved = new HashMap<>();
        SharedPreferences preferences = preferences(saved);
        for (String key : CAPABILITY_KEYS) assertTrue(key, ProductPermissions.allowed(preferences, key));
        assertTrue("Reading defaults must not erase the distinction from explicit user choices", saved.isEmpty());
    }

    @Test public void explicitIndividualDenialsRemainDeniedWhileOtherDefaultsStayEnabled() {
        Map<String, Boolean> saved = new HashMap<>();
        SharedPreferences preferences = preferences(saved);
        for (String key : CAPABILITY_KEYS) {
            saved.clear(); saved.put(key, false);
            assertFalse(key, ProductPermissions.allowed(preferences, key));
            for (String other : CAPABILITY_KEYS) if (!other.equals(key))
                assertTrue("Disabling " + key + " changed " + other, ProductPermissions.allowed(preferences, other));
            assertEquals(Boolean.FALSE, saved.get(key));
            assertEquals(1, saved.size());
        }
    }

    @Test public void readingStoredTrueAndFalseDoesNotNormalizeOrOverwriteEitherChoice() {
        Map<String, Boolean> saved = new HashMap<>(Map.of("allowContacts", false, "allowCalendar", true));
        Map<String, Boolean> before = Map.copyOf(saved);
        SharedPreferences preferences = preferences(saved);
        assertFalse(ProductPermissions.allowed(preferences, "allowContacts"));
        assertTrue(ProductPermissions.allowed(preferences, "allowCalendar"));
        assertEquals(before, saved);
    }

    @Test public void migratedInstallationsDenyNewCapabilitiesAndKeepExplicitChoices() {
        Map<String, Boolean> saved = new HashMap<>(Map.of(ProductPermissions.DEFAULT_DENY, true,
            "allowContacts", false, "allowCalendar", true));
        SharedPreferences preferences = preferences(saved);
        assertFalse(ProductPermissions.allowed(preferences, "allowFutureCapability"));
        assertFalse(ProductPermissions.allowed(preferences, "allowContacts"));
        assertTrue(ProductPermissions.allowed(preferences, "allowCalendar"));
        assertEquals(3, saved.size());
    }

    @Test public void hardwareAdditionsNeedConsentEvenBeforeLegacyDefaultsAreMigrated() {
        Map<String, Boolean> saved = new HashMap<>();
        SharedPreferences preferences = preferences(saved);
        for (String key : new String[]{"allowSensorsRead", "allowLocationRead", "allowInfrared", "allowBluetoothRead",
                "allowBluetoothControl", "allowNfcRead", "allowNfcWrite", "allowNfcEmulation", "allowWifiRead",
                "allowWifiControl", "allowCellularRead", "allowNetworkProbe", "allowDeviceDataRead", "allowDeviceDataWrite",
                "allowActivitySensors", "allowBodySensors"}) {
            assertTrue(ProductPermissions.isHardwareCapability(key));
            assertFalse(key, ProductPermissions.allowed(preferences, key));
            saved.put(key, true);
            assertTrue(key, ProductPermissions.allowed(preferences, key));
            saved.put(key, false);
            assertFalse(key, ProductPermissions.allowed(preferences, key));
        }
        assertTrue(ProductPermissions.allowed(preferences, "allowContacts"));
    }

    private static SharedPreferences preferences(Map<String, Boolean> saved) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getBoolean")) return saved.getOrDefault((String) args[0], (Boolean) args[1]);
                    throw new AssertionError("A capability read should not call " + method.getName());
                });
    }
}
