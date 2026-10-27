package io.droyagent.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Product choices persist independently from Android's actual system permissions. */
final class ProductPermissions {
    static final String STOPPED = "toolsStopped";
    static final String DEFAULT_DENY = "capabilitiesDefaultDeny";
    private static final java.util.Set<String> HARDWARE_CAPABILITIES = java.util.Set.of(
        "allowSensorsRead", "allowLocationRead", "allowInfrared", "allowBluetoothRead", "allowBluetoothControl",
        "allowNfcRead", "allowNfcWrite", "allowNfcEmulation", "allowWifiRead", "allowWifiControl",
        "allowCellularRead", "allowNetworkProbe", "allowDeviceDataRead", "allowDeviceDataWrite",
        "allowActivitySensors", "allowBodySensors");
    private ProductPermissions() {}

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("product", Context.MODE_PRIVATE);
    }

    static boolean allowed(SharedPreferences preferences, String key) {
        return preferences.getBoolean(key, !isHardwareCapability(key) && !preferences.getBoolean(DEFAULT_DENY, false));
    }

    static boolean isHardwareCapability(String key) { return HARDWARE_CAPABILITIES.contains(key); }

    static boolean stopped(Context context) {
        return preferences(context).getBoolean(STOPPED, false);
    }

    static void setStopped(Context context, boolean stopped) {
        if (!preferences(context).edit().putBoolean(STOPPED, stopped).commit())
            throw new IllegalStateException(io.droyagent.app.NativeLocale.text("工具运行设置保存失败", "Tool runtime setting could not be saved"));
    }

    static void enableAll(Context context) {
        CapabilityCatalog catalog = new CapabilityCatalog(context);
        SharedPreferences.Editor editor = preferences(context).edit();
        for (int i = 0; i < catalog.groups().length(); i++)
            editor.putBoolean(catalog.groups().optJSONObject(i).optString("preference"), true);
        if (!editor.putBoolean(STOPPED, false).commit()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("能力设置保存失败", "Capability setting could not be saved"));
        RuntimeService.applySavedOperationState();
    }

    static boolean allEnabled(Context context) {
        CapabilityCatalog catalog = new CapabilityCatalog(context);
        SharedPreferences preferences = preferences(context);
        for (int i = 0; i < catalog.groups().length(); i++) {
            JSONObject group = catalog.groups().optJSONObject(i);
            if (!allowed(preferences, group.optString("preference"))) return false;
        }
        return true;
    }
}
