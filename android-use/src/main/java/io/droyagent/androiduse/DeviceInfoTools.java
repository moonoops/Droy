package io.droyagent.androiduse;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only device facts. Missing access or readings remain explicit, never zero-filled. */
public final class DeviceInfoTools {
    private static final Set<String> SECTIONS = Set.of("battery", "network", "memory", "storage", "device");
    private final Context context;

    public DeviceInfoTools(Context context) { this.context = context.getApplicationContext(); }

    public JSONObject query(JSONObject args, Cancellation cancellation) throws Exception {
        Set<String> requested = new LinkedHashSet<>();
        if (args.has("sections")) {
            Object value = args.get("sections");
            if (!(value instanceof JSONArray) || ((JSONArray) value).length() == 0) {
                throw new ActionException("invalid_argument", "sections must be a nonempty array of battery, network, memory, storage or device");
            }
            JSONArray sections = (JSONArray) value;
            for (int index = 0; index < sections.length(); index++) {
                Object section = sections.get(index);
                if (!(section instanceof String) || !SECTIONS.contains(section)) {
                    throw new ActionException("invalid_argument", "Unknown system information section");
                }
                requested.add((String) section);
            }
        } else {
            requested.addAll(java.util.List.of("battery", "network", "memory", "storage", "device"));
        }
        JSONObject result = new JSONObject().put("status", "queried")
                .put("capturedAt", System.currentTimeMillis()).put("source", "android_system");
        JSONObject sections = new JSONObject();
        for (String section : requested) {
            cancellation.check();
            try {
                JSONObject data;
                switch (section) {
                    case "battery": data = battery(); break;
                    case "network": data = network(); break;
                    case "memory": data = memory(); break;
                    case "storage": data = storage(); break;
                    case "device": data = device(); break;
                    default: throw new IllegalStateException(section);
                }
                sections.put(section, data);
            } catch (SecurityException denied) {
                sections.put(section, unavailable("permission_required", "Android did not permit this information query"));
            } catch (IllegalArgumentException unavailable) {
                sections.put(section, unavailable("reading_unavailable", "Android did not provide a usable reading"));
            }
        }
        cancellation.check();
        return result.put("sections", sections);
    }

    private JSONObject battery() throws Exception {
        Intent state = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (state == null) return unavailable("reading_unavailable", "Battery state is unavailable");
        int level = state.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = state.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = state.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        JSONObject data = available().put("percent", level >= 0 && scale > 0 && level <= scale ? (double) level * 100 / scale : JSONObject.NULL)
                .put("charging", status == -1 || status == BatteryManager.BATTERY_STATUS_UNKNOWN ? JSONObject.NULL : status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL)
                .put("plugged", state.hasExtra(BatteryManager.EXTRA_PLUGGED)
                        ? state.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) : JSONObject.NULL);
        PowerManager power = context.getSystemService(PowerManager.class);
        data.put("batteryOptimizationExempt", power == null ? JSONObject.NULL
                : power.isIgnoringBatteryOptimizations(context.getPackageName()));
        if (state.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
            data.put("temperatureCelsius", state.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE) / 10.0);
        } else data.put("temperatureCelsius", JSONObject.NULL);
        return data.put("missingValues", "null means Android did not expose this reading");
    }

    private JSONObject network() throws Exception {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) return unavailable("service_unavailable", "Connectivity service is unavailable");
        Network active = manager.getActiveNetwork();
        if (active == null) return available().put("connected", false).put("internetValidated", false)
                .put("transports", new JSONArray()).put("modelServiceReachable", "not_checked");
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(active);
        if (capabilities == null) return unavailable("reading_unavailable", "Active network changed while querying");
        JSONArray transports = new JSONArray();
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.put("wifi");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.put("cellular");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.put("ethernet");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports.put("vpn");
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports.put("bluetooth");
        return available().put("connected", true).put("transports", transports)
                .put("internetCapability", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                .put("internetValidated", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                .put("metered", manager.isActiveNetworkMetered()).put("modelServiceReachable", "not_checked");
    }

    private JSONObject memory() throws Exception {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return unavailable("service_unavailable", "Memory service is unavailable");
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        return available().put("totalBytes", info.totalMem > 0 ? info.totalMem : JSONObject.NULL)
                .put("availableBytes", info.availMem >= 0 && info.totalMem > 0 ? info.availMem : JSONObject.NULL)
                .put("lowMemory", info.lowMemory);
    }

    private JSONObject storage() throws Exception {
        StatFs stats = new StatFs(context.getDataDir().getAbsolutePath());
        if (stats.getTotalBytes() <= 0) return unavailable("reading_unavailable", "Storage capacity is unavailable");
        return available().put("volume", "app_data_volume")
                .put("totalBytes", stats.getTotalBytes()).put("availableBytes", stats.getAvailableBytes())
                .put("meaning", "Space available to applications on the volume containing Droy data");
    }

    private JSONObject device() throws Exception {
        return available().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("androidVersion", Build.VERSION.RELEASE).put("sdkInt", Build.VERSION.SDK_INT)
                .put("supportedAbis", new JSONArray(Build.SUPPORTED_ABIS))
                .put("uptimeMs", SystemClock.elapsedRealtime());
    }

    private static JSONObject available() throws Exception { return new JSONObject().put("status", "available"); }
    private static JSONObject unavailable(String reason, String message) throws Exception {
        return new JSONObject().put("status", "unavailable").put("reason", reason).put("message", message);
    }
}
