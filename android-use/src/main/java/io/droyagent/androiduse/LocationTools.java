package io.droyagent.androiduse;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Task-bound location access independent of history collection and its consent. */
public final class LocationTools {
    private final Context context;
    private final LocationManager manager;
    private final ConcurrentHashMap<String, Set<Request>> active = new ConcurrentHashMap<>();

    public LocationTools(Context context) {
        this.context = context.getApplicationContext();
        manager = this.context.getSystemService(LocationManager.class);
    }

    public JSONObject capabilities() throws Exception {
        JSONArray providers = new JSONArray();
        if (manager != null) for (String provider : manager.getAllProviders())
            providers.put(new JSONObject().put("provider", provider).put("enabled", manager.isProviderEnabled(provider)));
        return new JSONObject().put("supported", manager != null).put("enabled", manager != null && manager.isLocationEnabled())
            .put("coarseGranted", granted(Manifest.permission.ACCESS_COARSE_LOCATION))
            .put("fineGranted", granted(Manifest.permission.ACCESS_FINE_LOCATION)).put("providers", providers);
    }

    public JSONObject execute(JSONObject args, String owner, Cancellation cancellation) throws Exception {
        cancellation.check();
        String operation = args.optString("operation", "status");
        if (operation.equals("status")) return capabilities();
        if (!Set.of("current", "sample").contains(operation))
            throw HardwareSupport.error(context, "invalid_argument", R.string.hardware_location_operation);
        if (manager == null) throw HardwareSupport.error(context, "unsupported_hardware", R.string.hardware_location_unavailable);
        String requested = args.optString("provider", "auto");
        if (!Set.of("auto", "gps", "network", "passive").contains(requested))
            throw HardwareSupport.error(context, "invalid_argument", R.string.hardware_location_provider);
        requirePermission(requested);
        String provider = provider(requested);
        int duration = HardwareSupport.integer(args, "durationMs", operation.equals("current") ? 10000 : 5000, 100, 10000);
        int interval = HardwareSupport.integer(args, "intervalMs", 1000, 250, 10000);
        int limit = HardwareSupport.integer(args, "limit", 20, 1, 40);
        int maxAge = HardwareSupport.integer(args, "maxAgeMs", 10000, 0, 300000);
        Request request = new Request();
        active.computeIfAbsent(owner, key -> ConcurrentHashMap.newKeySet()).add(request);
        long started = System.currentTimeMillis(), startNanos = SystemClock.elapsedRealtimeNanos();
        long deadline = SystemClock.elapsedRealtime() + Math.min(duration, cancellation.remainingMillis(duration));
        try {
            cancellation.check();
            if (request.stopped) throw new InterruptedException();
            if (operation.equals("current")) {
                CountDownLatch completed = new CountDownLatch(1);
                AtomicReference<Location> received = new AtomicReference<>();
                manager.getCurrentLocation(provider, request.signal, context.getMainExecutor(), location -> {
                    if (!request.stopped) received.set(location);
                    completed.countDown();
                });
                while (completed.getCount() > 0 && SystemClock.elapsedRealtime() < deadline) {
                    check(cancellation, request, provider);
                    completed.await(Math.min(50L, Math.max(1L, deadline - SystemClock.elapsedRealtime())), TimeUnit.MILLISECONDS);
                }
                check(cancellation, request, provider);
                Location location = received.get();
                if (location == null) return basis("no_fix", provider, started).put("reason", "no_location_before_deadline");
                long now = SystemClock.elapsedRealtimeNanos();
                if (maxAge == 0 && location.getElapsedRealtimeNanos() < startNanos
                    || !validReading(location.getLatitude(), location.getLongitude(), location.hasAccuracy() ? location.getAccuracy() : Float.NaN,
                        location.getElapsedRealtimeNanos(), now, maxAge == 0 ? duration : maxAge))
                    return basis("no_fix", provider, started).put("reason", "stale_or_invalid_location")
                        .put("sampleAgeMs", Math.max(0, (now - location.getElapsedRealtimeNanos()) / 1000000L));
                return basis("located", provider, started).put("location", reading(location));
            }
            JSONArray samples = new JSONArray();
            int[] rejected = {0};
            request.listener = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    if (request.stopped) return;
                    synchronized (samples) {
                        if (samples.length() >= limit) return;
                        if (location.getElapsedRealtimeNanos() < startNanos
                            || !validReading(location.getLatitude(), location.getLongitude(), location.hasAccuracy() ? location.getAccuracy() : Float.NaN,
                                location.getElapsedRealtimeNanos(), SystemClock.elapsedRealtimeNanos(), maxAge == 0 ? duration : maxAge)) { rejected[0]++; return; }
                        try { samples.put(reading(location)); }
                        catch (org.json.JSONException invalid) { rejected[0]++; }
                    }
                }
                @Override public void onProviderDisabled(String disabled) { request.providerDisabled = true; }
                @Override public void onStatusChanged(String p, int status, android.os.Bundle extras) { }
                @Override public void onProviderEnabled(String enabled) { }
            };
            manager.requestLocationUpdates(provider, interval, 0f, request.listener, Looper.getMainLooper());
            while (SystemClock.elapsedRealtime() < deadline) {
                check(cancellation, request, provider);
                synchronized (samples) { if (samples.length() >= limit) break; }
                if (request.providerDisabled) break;
                Thread.sleep(Math.min(50L, Math.max(1L, deadline - SystemClock.elapsedRealtime())));
            }
            check(cancellation, request, provider);
            synchronized (samples) {
                return basis(samples.length() == 0 ? "no_fix" : "sampled", provider, started)
                    .put("samples", new JSONArray(samples.toString())).put("sampleCount", samples.length())
                    .put("invalidSampleCount", rejected[0]).put("limitReached", samples.length() >= limit)
                    .put("providerDisabled", request.providerDisabled).put("requestedIntervalMs", interval);
            }
        } finally {
            request.close();
            Set<Request> ownerActive = active.get(owner);
            if (ownerActive != null) { ownerActive.remove(request); if (ownerActive.isEmpty()) active.remove(owner, ownerActive); }
        }
    }

    private JSONObject basis(String status, String provider, long started) throws Exception {
        return new JSONObject().put("status", status).put("source", "android_location_manager").put("provider", provider)
            .put("startedAt", started).put("finishedAt", System.currentTimeMillis()).put("coordinateReference", "WGS84")
            .put("collectionScope", "current_task");
    }
    private String provider(String requested) throws ActionException {
        if (!manager.isLocationEnabled()) throw HardwareSupport.error(context, "location_disabled", R.string.hardware_location_disabled);
        if (!requested.equals("auto")) {
            if (!manager.getAllProviders().contains(requested) || !manager.isProviderEnabled(requested))
                throw HardwareSupport.error(context, "provider_unavailable", R.string.hardware_location_disabled);
            return requested;
        }
        for (String provider : new String[]{"fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER}) {
            if (provider.equals(LocationManager.GPS_PROVIDER) && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) continue;
            if (manager.getAllProviders().contains(provider) && manager.isProviderEnabled(provider)) return provider;
        }
        throw HardwareSupport.error(context, "provider_unavailable", R.string.hardware_location_disabled);
    }
    private boolean granted(String permission) { return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private void requirePermission(String provider) throws ActionException {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION))
            throw HardwareSupport.error(context, "system_permission_required", R.string.hardware_location_permission);
        if (provider.equals("gps") && !granted(Manifest.permission.ACCESS_FINE_LOCATION))
            throw HardwareSupport.error(context, "precise_location_required", R.string.hardware_location_precise);
    }
    private void check(Cancellation cancellation, Request request, String provider) throws Exception {
        cancellation.check();
        if (request.stopped) throw new InterruptedException();
        if (request.preciseAtStart && !granted(Manifest.permission.ACCESS_FINE_LOCATION))
            throw HardwareSupport.error(context, "system_permission_required", R.string.hardware_location_permission);
        requirePermission(provider);
    }

    private JSONObject reading(Location location) throws org.json.JSONException {
        return new JSONObject().put("latitude", location.getLatitude()).put("longitude", location.getLongitude())
            .put("accuracyMeters", location.getAccuracy()).put("capturedAt", location.getTime())
            .put("elapsedRealtimeNanos", Long.toString(location.getElapsedRealtimeNanos()))
            .put("sampleAgeMs", Math.max(0, (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1000000L))
            .put("provider", location.getProvider()).put("approximate", !granted(Manifest.permission.ACCESS_FINE_LOCATION))
            .put("isMock", Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider())
            .put("coordinateReference", "WGS84")
            .put("ellipsoidAltitudeMeters", location.hasAltitude() ? finite(location.getAltitude()) : JSONObject.NULL)
            .put("altitudeReference", "WGS84_ellipsoid")
            .put("verticalAccuracyMeters", location.hasVerticalAccuracy() ? finite(location.getVerticalAccuracyMeters()) : JSONObject.NULL)
            .put("speedMetersPerSecond", location.hasSpeed() ? finite(location.getSpeed()) : JSONObject.NULL)
            .put("travelBearingDegrees", location.hasBearing() ? finite(location.getBearing()) : JSONObject.NULL)
            .put("bearingAccuracyDegrees", location.hasBearingAccuracy() ? finite(location.getBearingAccuracyDegrees()) : JSONObject.NULL);
    }
    private static Object finite(double value) { return Double.isFinite(value) ? value : JSONObject.NULL; }
    static boolean validReading(double latitude, double longitude, float accuracy, long elapsedNanos, long nowNanos, long maxAgeMs) {
        return Double.isFinite(latitude) && Double.isFinite(longitude) && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180
            && Float.isFinite(accuracy) && accuracy >= 0 && elapsedNanos > 0 && nowNanos >= elapsedNanos
            && nowNanos - elapsedNanos <= maxAgeMs * 1000000L;
    }
    public void closeOwner(String owner) {
        Set<Request> requests = active.remove(owner);
        if (requests != null) for (Request request : requests) request.close();
    }
    public void close() { for (String owner : active.keySet()) closeOwner(owner); }
    private final class Request {
        final CancellationSignal signal = new CancellationSignal();
        volatile boolean stopped, providerDisabled;
        volatile LocationListener listener;
        final boolean preciseAtStart = granted(Manifest.permission.ACCESS_FINE_LOCATION);
        void close() {
            stopped = true; signal.cancel();
            LocationListener current = listener;
            if (current != null) manager.removeUpdates(current);
        }
    }
}
