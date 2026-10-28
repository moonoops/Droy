package io.droyagent.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Maps product choices to distinct Android authorization steps. */
final class PermissionSetupPlan {
    private PermissionSetupPlan() {}

    static boolean validMode(String mode) { return Set.of("all", "assistant", "dialogue").contains(mode); }

    static List<String> forCapabilities(Set<String> enabled, Set<String> hardware, boolean allSystemPermissions) {
        LinkedHashSet<String> steps = new LinkedHashSet<>();
        if (allSystemPermissions) { steps.add("notifications"); steps.add("camera"); steps.add("petOverlay"); }
        for (String key : enabled) {
            if (key.equals("allowCellularRead") && hardware.contains("telephony")) steps.add("phoneState");
            String name = forPreference(key);
            if (name == null) continue;
            if (name.equals("bluetooth") && !hardware.contains("bluetooth")
                    || name.equals("nearbyWifi") && !hardware.contains("wifi")
                    || name.equals("phoneState") && !hardware.contains("telephony")
                    || name.equals("activityRecognition") && !hardware.contains("stepCounter")
                    || name.equals("bodySensors") && !hardware.contains("heartRate")) continue;
            if (name.equals("location") && key.equals("allowWifiRead") && !hardware.contains("wifi")) continue;
            if (name.equals("location") && key.equals("allowCellularRead") && !hardware.contains("telephony")) continue;
            steps.add(name);
        }
        if (enabled.contains("allowRoot") && (enabled.contains("allowControl") || enabled.contains("allowObserve") || enabled.contains("allowScreenshot"))) steps.add("shizuku");
        // Runtime dialogs precede settings pages, so Android can present them without interruption.
        List<String> ordered = new ArrayList<>();
        for (String name : List.of("notifications", "calendar", "contacts", "microphone", "camera", "location", "bluetooth",
                "nearbyWifi", "phoneState", "activityRecognition", "bodySensors", "accessibility",
                "notificationAccess", "usage", "brightness", "petOverlay", "shizuku", "root"))
            if (steps.contains(name)) ordered.add(name);
        return ordered;
    }

    static String forPreference(String key) {
        return switch (key) {
            case "allowObserve", "allowControl", "allowScreenshot" -> "accessibility";
            case "allowCalendar" -> "calendar";
            case "allowContacts" -> "contacts";
            case "allowAudioMicrophone" -> "microphone";
            case "allowLocationRead", "allowWifiRead", "allowCellularRead" -> "location";
            case "allowBluetoothRead", "allowBluetoothControl" -> "bluetooth";
            case "allowWifiControl" -> "nearbyWifi";
            case "allowActivitySensors" -> "activityRecognition";
            case "allowBodySensors" -> "bodySensors";
            case "allowNotifications" -> "notificationAccess";
            case "allowUsage", "allowPrivacyUsage", "allowDiagnostics" -> "usage";
            case "allowSettingsWrite" -> "brightness";
            case "allowRoot" -> "root";
            default -> null;
        };
    }
}
