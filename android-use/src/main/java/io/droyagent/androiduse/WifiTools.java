package io.droyagent.androiduse;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Public Android Wi-Fi observations and task-owned connection requests. */
public final class WifiTools {
    private final Context context;
    private final WifiManager wifi;
    private final ConnectivityManager connectivity;
    private final Map<String, Connection> requests = new ConcurrentHashMap<>();

    public WifiTools(Context context) {
        this.context = context.getApplicationContext();
        wifi = this.context.getSystemService(WifiManager.class);
        connectivity = this.context.getSystemService(ConnectivityManager.class);
    }

    public JSONObject capabilities() throws Exception {
        return new JSONObject().put("supported", wifi != null && connectivity != null)
                .put("statePermission", granted(Manifest.permission.ACCESS_WIFI_STATE))
                .put("scanPermission", granted(Manifest.permission.ACCESS_FINE_LOCATION))
                .put("nearbyPermission", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.NEARBY_WIFI_DEVICES))
                .put("locationEnabled", locationEnabled()).put("silentRadioToggle", false)
                .put("connectionMode", "task_owned_network_specifier");
    }

    public JSONObject execute(JSONObject args, String owner, Cancellation cancellation) throws Exception {
        cancellation.check();
        if (args.has("passphrase")) throw error("invalid_argument", R.string.wifi_credential_local_only);
        String operation = args.optString("operation", "status");
        if (operation.equals("credentials")) return new JSONObject().put("status", "queried").put("credentials", new WifiCredentialStore(context).list());
        if (operation.equals("credential_add")) {
            cancellation.dispatched();
            context.startActivity(new Intent().setClassName(context, "io.droyagent.app.WifiCredentialActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return new JSONObject().put("status", "requested").put("localInputRequired", true);
        }
        if (operation.equals("credential_delete")) {
            Object ref = args.opt("credentialRef");
            if (!(ref instanceof String) || ((String) ref).isBlank()) throw error("invalid_argument", R.string.network_invalid_argument);
            cancellation.dispatched(); new WifiCredentialStore(context).delete((String) ref);
            return new JSONObject().put("status", "deleted").put("credentialRef", ref);
        }
        if (wifi == null || connectivity == null) throw error("unsupported", R.string.network_hardware_unavailable);
        if (operation.equals("disconnect")) return disconnect(args, owner);
        require(Manifest.permission.ACCESS_WIFI_STATE);
        require(Manifest.permission.ACCESS_NETWORK_STATE);
        return switch (operation) {
            case "status" -> status(owner);
            case "scan" -> scan(args, cancellation);
            case "sample" -> sample(args, owner, cancellation);
            case "connect" -> connect(args, owner, cancellation);
            default -> throw error("invalid_argument", R.string.network_invalid_operation);
        };
    }

    private JSONObject status(String owner) throws Exception {
        JSONArray networks = new JSONArray();
        Network active = connectivity.getActiveNetwork();
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
            JSONObject item = new JSONObject().put("networkId", network.toString()).put("isDefault", network.equals(active))
                    .put("internetValidated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            LinkProperties links = connectivity.getLinkProperties(network);
            if (links != null) {
                JSONArray addresses = new JSONArray(), dns = new JSONArray(), routes = new JSONArray();
                for (android.net.LinkAddress address : links.getLinkAddresses()) addresses.put(address.toString());
                for (java.net.InetAddress address : links.getDnsServers()) dns.put(address.getHostAddress());
                for (android.net.RouteInfo route : links.getRoutes()) routes.put(new JSONObject().put("destination", route.getDestination().toString())
                        .put("gateway", route.getGateway() == null ? JSONObject.NULL : route.getGateway().getHostAddress()));
                item.put("interface", links.getInterfaceName()).put("addresses", addresses).put("dns", dns).put("routes", routes);
            }
            if (caps.getTransportInfo() instanceof WifiInfo info) item.put("wifi", info(info));
            networks.put(item);
        }
        // This describes the radio association, which may exist without an active internet network.
        WifiInfo association = wifi.getConnectionInfo();
        JSONArray owned = new JSONArray();
        for (Map.Entry<String, Connection> entry : requests.entrySet()) {
            Connection connection = entry.getValue();
            if (NetworkToolValues.ownedBy(connection.owner, owner)) owned.put(new JSONObject().put("sessionId", entry.getKey())
                    .put("state", connection.state).put("networkId", connection.network == null ? JSONObject.NULL : connection.network.toString()));
        }
        return new JSONObject().put("status", "queried").put("source", "android_wifi")
                .put("capturedAt", System.currentTimeMillis()).put("enabled", wifi.isWifiEnabled())
                .put("association", association == null ? JSONObject.NULL : info(association))
                .put("networks", networks).put("requests", owned)
                .put("redactedFields", !granted(Manifest.permission.ACCESS_FINE_LOCATION));
    }

    private static JSONObject info(WifiInfo info) throws Exception {
        String ssid = info.getSSID(), bssid = info.getBSSID();
        return new JSONObject().put("ssid", ssid == null || ssid.equals(WifiManager.UNKNOWN_SSID) ? JSONObject.NULL : ssid)
                .put("bssid", bssid == null || bssid.equals("02:00:00:00:00:00") ? JSONObject.NULL : bssid)
                .put("rssiDbm", info.getRssi() == -127 ? JSONObject.NULL : info.getRssi())
                .put("frequencyMhz", info.getFrequency() > 0 ? info.getFrequency() : JSONObject.NULL)
                .put("rxLinkSpeedMbps", info.getRxLinkSpeedMbps() >= 0 ? info.getRxLinkSpeedMbps() : JSONObject.NULL)
                .put("txLinkSpeedMbps", info.getTxLinkSpeedMbps() >= 0 ? info.getTxLinkSpeedMbps() : JSONObject.NULL)
                .put("wifiStandard", info.getWifiStandard())
                .put("supplicantState", info.getSupplicantState().name());
    }

    private JSONObject scan(JSONObject args, Cancellation cancellation) throws Exception {
        require(Manifest.permission.ACCESS_FINE_LOCATION);
        require(Manifest.permission.CHANGE_WIFI_STATE);
        if (!locationEnabled()) throw error("location_disabled", R.string.network_location_off);
        int timeout = HardwareSupport.integer(args, "timeoutMs", 4000, 100, 10000);
        int limit = HardwareSupport.integer(args, "limit", 40, 1, 100);
        CountDownLatch signal = new CountDownLatch(1);
        boolean[] updated = {false};
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                    updated[0] = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
                    signal.countDown();
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        boolean requested;
        try {
            cancellation.dispatched();
            requested = wifi.startScan();
            long deadline = SystemClock.elapsedRealtime() + Math.min(timeout, cancellation.remainingMillis(timeout));
            while (requested && signal.getCount() > 0 && SystemClock.elapsedRealtime() < deadline) {
                cancellation.check();
                signal.await(50, TimeUnit.MILLISECONDS);
            }
            cancellation.check();
            require(Manifest.permission.ACCESS_FINE_LOCATION);
            List<ScanResult> scans = wifi.getScanResults();
            JSONArray results = new JSONArray();
            for (ScanResult scan : scans) {
                if (results.length() >= limit) break;
                JSONArray elements = new JSONArray();
                for (ScanResult.InformationElement element : scan.getInformationElements()) {
                    ByteBuffer bytes = element.getBytes();
                    byte[] value = new byte[bytes.remaining()]; bytes.get(value);
                    elements.put(new JSONObject().put("id", element.getId()).put("idExt", element.getIdExt()).put("hex", hex(value)));
                }
                results.put(new JSONObject().put("ssid", scan.SSID).put("bssid", scan.BSSID)
                        .put("rssiDbm", scan.level).put("frequencyMhz", scan.frequency).put("channelWidth", scan.channelWidth)
                        .put("centerFreq0Mhz", scan.centerFreq0).put("centerFreq1Mhz", scan.centerFreq1)
                        .put("capabilities", scan.capabilities).put("wifiStandard", scan.getWifiStandard())
                        .put("observedElapsedMicros", scan.timestamp)
                        .put("ageMs", Math.max(0, SystemClock.elapsedRealtime() - scan.timestamp / 1000))
                        .put("informationElements", elements));
            }
            return new JSONObject().put("status", "queried").put("source", "android_wifi_scan")
                    .put("capturedAt", System.currentTimeMillis()).put("scanRequested", requested)
                    .put("resultsUpdated", updated[0]).put("cachedResultsPossible", !updated[0])
                    .put("results", results).put("truncated", scans.size() > results.length());
        } finally { context.unregisterReceiver(receiver); }
    }

    private JSONObject sample(JSONObject args, String owner, Cancellation cancellation) throws Exception {
        int duration = HardwareSupport.integer(args, "durationMs", 3000, 100, 10000);
        int interval = HardwareSupport.integer(args, "intervalMs", 500, 100, 5000);
        JSONArray samples = new JSONArray();
        long deadline = SystemClock.elapsedRealtime() + Math.min(duration, cancellation.remainingMillis(duration));
        do {
            cancellation.check(); require(Manifest.permission.ACCESS_WIFI_STATE);
            samples.put(status(owner));
            long next = Math.min(deadline, SystemClock.elapsedRealtime() + interval);
            while (SystemClock.elapsedRealtime() < next) { cancellation.check(); Thread.sleep(Math.max(1, Math.min(50, next - SystemClock.elapsedRealtime()))); }
        } while (SystemClock.elapsedRealtime() < deadline);
        return new JSONObject().put("status", "sampled").put("samples", samples).put("count", samples.length());
    }

    private synchronized JSONObject connect(JSONObject args, String owner, Cancellation cancellation) throws Exception {
        if (owner == null || owner.isBlank()) throw error("invalid_argument", R.string.network_invalid_argument);
        require(Manifest.permission.CHANGE_NETWORK_STATE);
        if (Build.VERSION.SDK_INT >= 33) require(Manifest.permission.NEARBY_WIFI_DEVICES);
        else require(Manifest.permission.ACCESS_FINE_LOCATION);
        for (Connection existing : requests.values()) if (existing.owner.equals(owner))
            throw error("request_limit", R.string.network_wifi_request_limit);
        Object ssid = args.opt("ssid");
        if (!(ssid instanceof String) || ((String) ssid).isBlank() || ((String) ssid).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32)
            throw error("invalid_argument", R.string.network_invalid_argument);
        WifiNetworkSpecifier.Builder spec = new WifiNetworkSpecifier.Builder().setSsid((String) ssid);
        if (args.has("bssid")) {
            try { spec.setBssid(MacAddress.fromString(args.getString("bssid"))); }
            catch (IllegalArgumentException failure) { throw error("invalid_argument", R.string.network_invalid_argument); }
        }
        String security = args.optString("security", "open");
        if (!security.equals("open") && !(args.opt("credentialRef") instanceof String)) throw error("invalid_argument", R.string.network_invalid_argument);
        try {
            if (security.equals("wpa2")) spec.setWpa2Passphrase(new WifiCredentialStore(context).read(args.getString("credentialRef")));
            else if (security.equals("wpa3")) spec.setWpa3Passphrase(new WifiCredentialStore(context).read(args.getString("credentialRef")));
            else if (!security.equals("open")) throw error("invalid_argument", R.string.network_invalid_argument);
        } catch (IllegalArgumentException invalid) { throw error("invalid_argument", R.string.network_invalid_argument); }
        NetworkRequest request = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec.build()).build();
        String id = UUID.randomUUID().toString();
        Connection connection = new Connection(owner);
        connection.callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { connection.network = network; connection.state = "available"; }
            @Override public void onUnavailable() { connection.state = "unavailable"; }
            @Override public void onLost(Network network) { connection.network = null; connection.state = "lost"; }
        };
        cancellation.dispatched();
        connectivity.requestNetwork(request, connection.callback, 30000);
        requests.put(id, connection);
        try { cancellation.check(); }
        catch (InterruptedException stopped) { requests.remove(id); release(connection); throw stopped; }
        return new JSONObject().put("status", "requested").put("sessionId", id).put("state", connection.state)
                .put("userConfirmationMayBeRequired", true).put("networkScope", "droy_request_not_global_default");
    }

    private JSONObject disconnect(JSONObject args, String owner) throws Exception {
        Connection connection = requests.get(args.optString("sessionId"));
        if (connection == null || !NetworkToolValues.ownedBy(connection.owner, owner)) throw error("session_unavailable", R.string.network_session_unavailable);
        requests.remove(args.getString("sessionId"), connection); release(connection);
        return new JSONObject().put("status", "released").put("scope", "droy_request_only");
    }

    public synchronized void closeOwner(String owner) {
        for (Map.Entry<String, Connection> entry : requests.entrySet()) if (entry.getValue().owner.equals(owner)
                && requests.remove(entry.getKey(), entry.getValue())) release(entry.getValue());
    }
    public boolean hasOwnerSessions(String owner) {
        for (Connection connection : requests.values()) {
            if (NetworkToolValues.ownedBy(connection.owner, owner)
                    && !java.util.Set.of("unavailable", "lost", "released").contains(connection.state)) return true;
        }
        return false;
    }
    public synchronized void close() {
        for (Connection connection : requests.values()) release(connection);
        requests.clear();
    }
    private void release(Connection connection) {
        connection.state = "released"; connection.network = null;
        try { connectivity.unregisterNetworkCallback(connection.callback); }
        catch (IllegalArgumentException alreadyReleased) { /* Android already removed a timed-out request. */ }
    }
    private boolean locationEnabled() { LocationManager manager = context.getSystemService(LocationManager.class); return manager != null && manager.isLocationEnabled(); }
    private boolean granted(String permission) { return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private void require(String permission) throws ActionException { if (!granted(permission)) throw error("permission_required", R.string.network_permission_required); }
    private ActionException error(String code, int message) { return HardwareSupport.error(context, code, message); }
    private static String hex(byte[] bytes) { StringBuilder text = new StringBuilder(bytes.length * 2); for (byte value : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); return text.toString(); }
    private static final class Connection {
        final String owner; volatile String state = "awaiting_system"; volatile Network network; ConnectivityManager.NetworkCallback callback;
        Connection(String owner) { this.owner = owner; }
    }
}
