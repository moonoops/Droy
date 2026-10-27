package io.droyagent.androiduse;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.DnsResolver;
import android.net.InetAddresses;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.CancellationSignal;
import android.os.SystemClock;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Active measurements explicitly bound to the selected Android Network. */
public final class NetworkProbeTools {
    private final Context context;
    private final ConnectivityManager connectivity;
    private final java.util.Set<NetworkDownload> downloads = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public NetworkProbeTools(Context context) {
        this.context = context.getApplicationContext();
        connectivity = this.context.getSystemService(ConnectivityManager.class);
    }

    public JSONObject capabilities() throws Exception {
        return new JSONObject().put("supported", connectivity != null)
                .put("internetPermission", granted(Manifest.permission.INTERNET))
                .put("networkPermission", granted(Manifest.permission.ACCESS_NETWORK_STATE));
    }

    public JSONObject execute(JSONObject args, String owner, Cancellation cancellation) throws Exception {
        cancellation.check();
        if (connectivity == null) throw error("unsupported", R.string.network_hardware_unavailable);
        if (!granted(Manifest.permission.INTERNET) || !granted(Manifest.permission.ACCESS_NETWORK_STATE))
            throw error("permission_required", R.string.network_permission_required);
        Network network = select(args);
        JSONObject selectedNetwork = networkResult(network);
        String operation = args.optString("operation", "probe");
        if (operation.equals("download")) return download(network, selectedNetwork, args, owner, cancellation);
        if (!operation.equals("probe")) throw error("invalid_argument", R.string.network_invalid_operation);
        String host;
        try { host = NetworkToolValues.host(args.getString("host")); }
        catch (IllegalArgumentException | org.json.JSONException invalid) { throw error("invalid_argument", R.string.network_invalid_argument); }
        int port = HardwareSupport.integer(args, "port", 443, 1, 65535);
        int count = HardwareSupport.integer(args, "count", 3, 1, 5);
        int timeout = HardwareSupport.integer(args, "timeoutMs", 2000, 100, 5000);
        int index = HardwareSupport.integer(args, "addressIndex", 0, 0, 15);
        JSONArray attempts = new JSONArray();
        for (int attempt = 0; attempt < count; attempt++) {
            cancellation.check();
            JSONObject item = new JSONObject().put("attempt", attempt + 1).put("capturedAt", System.currentTimeMillis());
            long started = SystemClock.elapsedRealtime();
            List<InetAddress> addresses;
            try {
                addresses = resolve(network, host, Math.min(timeout, cancellation.remainingMillis(timeout)), cancellation);
                JSONArray resolved = new JSONArray(); for (InetAddress address : addresses) resolved.put(address.getHostAddress());
                item.put("dnsMs", SystemClock.elapsedRealtime() - started).put("addresses", resolved);
            } catch (ProbeFailure failed) {
                attempts.put(item.put("status", "failed").put("stage", "dns").put("errorType", failed.type));
                continue;
            }
            if (addresses.isEmpty() || index >= addresses.size()) {
                attempts.put(item.put("status", "failed").put("stage", "selection").put("errorType", "address_unavailable"));
                continue;
            }
            InetAddress address = addresses.get(index);
            cancellation.dispatched();
            started = SystemClock.elapsedRealtime();
            try {
                tcp(network, address, port, Math.min(timeout, cancellation.remainingMillis(timeout)), cancellation);
                item.put("status", "connected").put("address", address.getHostAddress()).put("tcpConnectMs", SystemClock.elapsedRealtime() - started);
            } catch (ProbeFailure failed) {
                item.put("status", "failed").put("stage", "tcp").put("address", address.getHostAddress()).put("errorType", failed.type);
            }
            attempts.put(item);
        }
        return selectedNetwork.put("networkStillAvailable", connectivity.getNetworkCapabilities(network) != null)
                .put("status", "measured").put("host", host).put("port", port).put("attempts", attempts)
                .put("measurement", "dns_and_tcp_handshake").put("tcpApplicationPayloadBytesSent", 0);
    }

    private Network select(JSONObject args) throws Exception {
        String type = args.optString("network", "default"), id = args.optString("networkId", "");
        if (!java.util.Set.of("default", "wifi", "cellular").contains(type)) throw error("invalid_argument", R.string.network_invalid_argument);
        if (type.equals("default") && id.isEmpty()) {
            Network active = connectivity.getActiveNetwork();
            if (active != null) return active;
        } else {
            for (Network network : connectivity.getAllNetworks()) {
                if (!id.isEmpty() && !network.toString().equals(id)) continue;
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
                if (caps == null) continue;
                if (NetworkToolValues.matchesTransport(type, caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR), caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))) return network;
            }
        }
        throw error("network_unavailable", R.string.network_not_available);
    }

    private List<InetAddress> resolve(Network network, String host, long timeout, Cancellation cancellation) throws Exception {
        if (InetAddresses.isNumericAddress(host)) return List.of(InetAddresses.parseNumericAddress(host));
        CompletableFuture<List<InetAddress>> result = new CompletableFuture<>();
        CancellationSignal signal = new CancellationSignal();
        try {
            cancellation.dispatched();
            DnsResolver.getInstance().query(network, host, DnsResolver.FLAG_EMPTY, context.getMainExecutor(), signal,
                    new DnsResolver.Callback<List<InetAddress>>() {
                        @Override public void onAnswer(List<InetAddress> answer, int rcode) {
                            if (rcode == 0) result.complete(answer);
                            else result.completeExceptionally(new ProbeFailure("dns_rcode_" + rcode));
                        }
                        @Override public void onError(DnsResolver.DnsException error) { result.completeExceptionally(new ProbeFailure("dns_error_" + error.code)); }
                    });
            return await(result, timeout, cancellation);
        } finally { signal.cancel(); }
    }

    private void tcp(Network network, InetAddress address, int port, long timeout, Cancellation cancellation) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Socket socket = network.getSocketFactory().createSocket()) {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            executor.execute(() -> {
                try { socket.connect(new InetSocketAddress(address, port), (int) Math.max(1, timeout)); result.complete(true); }
                catch (java.io.IOException failure) { result.completeExceptionally(new ProbeFailure(failure.getClass().getSimpleName())); }
            });
            await(result, timeout, cancellation);
        } finally { executor.shutdownNow(); }
    }

    private <T> T await(CompletableFuture<T> future, long timeout, Cancellation cancellation) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            cancellation.check();
            try { return future.get(Math.max(1, Math.min(50, deadline - SystemClock.elapsedRealtime())), TimeUnit.MILLISECONDS); }
            catch (TimeoutException waiting) { }
            catch (java.util.concurrent.ExecutionException failed) {
                if (failed.getCause() instanceof ProbeFailure reason) throw reason;
                throw failed;
            }
        }
        cancellation.check();
        throw new ProbeFailure("timeout");
    }

    private JSONObject download(Network network, JSONObject selectedNetwork, JSONObject args, String owner, Cancellation cancellation) throws Exception {
        URL url;
        try {
            url = new URL(args.getString("url"));
            if (!java.util.Set.of("http", "https").contains(url.getProtocol()) || url.getUserInfo() != null
                    || url.getHost().isBlank()) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | java.net.MalformedURLException | org.json.JSONException invalid) {
            throw error("invalid_argument", R.string.network_invalid_argument);
        }
        int maximum = HardwareSupport.integer(args, "maxBytes", 65536, 1, 1048576);
        int timeout = HardwareSupport.integer(args, "timeoutMs", 5000, 100, 10000);
        NetworkDownload operation = new NetworkDownload(owner);
        downloads.add(operation);
        NetworkDownload.Result result;
        try {
            cancellation.dispatched();
            result = operation.measure(target -> (HttpURLConnection) network.openConnection(target), url, maximum,
                    Math.max(1, Math.min(timeout, cancellation.remainingMillis(timeout))), cancellation);
        } finally { downloads.remove(operation); operation.close(); }
        cancellation.check();
        return selectedNetwork.put("networkStillAvailable", connectivity.getNetworkCapabilities(network) != null)
                .put("status", "measured").put("measurement", "bounded_http_download")
                .put("host", url.getHost()).put("httpStatus", result.responseCode < 0 ? JSONObject.NULL : result.responseCode)
                .put("completion", result.completion).put("errorType", result.errorType == null ? JSONObject.NULL : result.errorType)
                .put("bytesReceived", result.bytes).put("elapsedMs", result.elapsedMs).put("maxBytes", maximum)
                .put("effectiveMbps", result.bytes > 0 && result.elapsedMs > 0 ? result.bytes * 8.0 / (result.elapsedMs * 1000) : JSONObject.NULL)
                .put("includesConnectionOverhead", true);
    }

    private JSONObject networkResult(Network network) throws Exception {
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
        JSONArray transports = new JSONArray();
        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.put("wifi");
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.put("cellular");
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports.put("vpn");
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.put("ethernet");
        }
        return new JSONObject().put("networkId", network.toString()).put("transports", transports)
                .put("networkStillAvailable", caps != null).put("capturedAt", System.currentTimeMillis());
    }
    public void closeOwner(String owner) {
        for (NetworkDownload operation : downloads) if (NetworkToolValues.ownedBy(operation.owner, owner)) operation.close();
    }
    public void close() { for (NetworkDownload operation : downloads) operation.close(); }
    private boolean granted(String permission) { return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private ActionException error(String code, int message) { return HardwareSupport.error(context, code, message); }
    private static final class ProbeFailure extends Exception { final String type; ProbeFailure(String type) { this.type = type; } }
}
