package io.droyagent.app;

import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PermissionSetupPlanTest {
    @Test public void minimumOnlyRequiresAccessibilityAndDoesNotAskForOptionalServices() {
        assertEquals(List.of("accessibility"), PermissionSetupPlan.forCapabilities(
            Set.of("allowObserve", "allowControl", "allowApps"), Set.of(), false));
    }

    @Test public void allPermissionsDeduplicatesRelatedCapabilitiesAndPlacesDialogsBeforeSettings() {
        List<String> steps = PermissionSetupPlan.forCapabilities(Set.of("allowObserve", "allowControl", "allowScreenshot",
            "allowCalendar", "allowContacts", "allowAudioMicrophone", "allowLocationRead", "allowWifiRead",
            "allowWifiControl", "allowCellularRead", "allowBluetoothRead", "allowBluetoothControl",
            "allowActivitySensors", "allowBodySensors", "allowNotifications", "allowUsage", "allowPrivacyUsage",
            "allowDiagnostics", "allowSettingsWrite", "allowRoot", "allowFiles", "allowFileExport"),
            Set.of("wifi", "bluetooth", "telephony", "stepCounter", "heartRate"), true);
        assertEquals(List.of("notifications", "calendar", "contacts", "microphone", "camera", "location", "bluetooth", "nearbyWifi",
            "phoneState", "activityRecognition", "bodySensors", "accessibility", "notificationAccess", "usage", "brightness", "petOverlay", "shizuku", "root"), steps);
        assertFalse(steps.contains("file")); assertFalse(steps.contains("directory"));
    }

    @Test public void unsupportedDevicesDoNotGenerateImpossibleHardwarePrompts() {
        assertEquals(List.of(), PermissionSetupPlan.forCapabilities(Set.of("allowBluetoothRead", "allowBluetoothControl",
            "allowWifiControl", "allowWifiRead", "allowActivitySensors", "allowBodySensors"), Set.of(), false));
        assertEquals(List.of(), PermissionSetupPlan.forCapabilities(Set.of("allowCellularRead"), Set.of(), false));
    }

    @Test public void onlyExplicitQuickConfigurationModesAreAccepted() {
        for (String mode : List.of("all", "assistant", "dialogue")) assertTrue(PermissionSetupPlan.validMode(mode));
        for (String mode : List.of("custom", "ALL", "", "root")) assertFalse(PermissionSetupPlan.validMode(mode));
    }

    @Test public void nativeOrdinaryBatchKeepsIndependentAuthorizationsOutEvenWhenRootWasAlreadyEnabled() {
        List<String> ordinary = PermissionSetupPlan.ordinaryForCapabilities(Set.of("allowObserve", "allowControl", "allowRoot",
            "allowCalendar", "allowContacts", "allowAudioMicrophone", "allowLocationRead", "allowBluetoothRead", "allowWifiControl",
            "allowCellularRead", "allowActivitySensors", "allowBodySensors", "allowNotifications", "allowUsage", "allowSettingsWrite"),
            Set.of("camera", "microphone", "location", "bluetooth", "wifi", "telephony", "stepCounter", "heartRate"), true);
        assertEquals(List.of("notifications", "calendar", "contacts", "microphone", "camera", "location", "bluetooth", "nearbyWifi",
            "phoneState", "activityRecognition", "bodySensors", "notificationAccess", "usage", "brightness", "petOverlay"), ordinary);
        for (String independent : List.of("accessibility", "root", "shizuku")) assertFalse(ordinary.contains(independent));
    }

    @Test public void ordinaryBatchFiltersAbsentCameraMicrophoneAndLocationHardware() {
        assertEquals(List.of("notifications", "petOverlay"), PermissionSetupPlan.ordinaryForCapabilities(
            Set.of("allowObserve", "allowControl", "allowRoot", "allowAudioMicrophone", "allowLocationRead"), Set.of(), true));
        assertEquals(List.of("location"), PermissionSetupPlan.ordinaryForCapabilities(Set.of("allowWifiRead"), Set.of("wifi"), false));
        assertEquals(List.of(), PermissionSetupPlan.ordinaryForCapabilities(Set.of("allowObserve", "allowRoot"), Set.of(), false));
    }
}
