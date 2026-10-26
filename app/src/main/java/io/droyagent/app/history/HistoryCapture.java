package io.droyagent.app.history;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.*;
import android.os.*;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Main-process source adapter. The host supplies readiness; it is never a setting. */
public final class HistoryCapture implements AutoCloseable {
    private static HistoryCapture current;
    private final Context context;
    private final BooleanSupplier executionPrivacyReady;
    private final SharedPreferences preferences;
    private final Object lock = new Object();
    private final HistoryEpoch epoch = new HistoryEpoch();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(256), runnable -> new Thread(runnable, "droy-history-spool"), new ThreadPoolExecutor.AbortPolicy()) {
        @Override protected void terminated() { if (spool != null) { spool.close(); spool = null; } }
    };
    private volatile HistoryPolicy policy;
    private volatile boolean running, closed, receiversRegistered, networkRegistered, locationServiceRunning, accessibilityConnected;
    private volatile String failure = "", locationState = "off", activityState = "off", lastGap = "";
    private volatile long dropped;
    private String pendingGap = "";
    private HistorySpool spool;
    private String activePackage = "", lastPageDigest = "";
    private long lastPageAt;
    private String lastDevice = "";
    private final Runnable retentionSweep = new Runnable() {
        @Override public void run() {
            if (closed) return;
            try { writer.execute(() -> {
                if (closed || !new File(context.getFilesDir(), "droy-history-spool/queue.db").isFile()) return;
                try { spool().expire(System.currentTimeMillis()); }
                catch (RuntimeException error) { synchronized (lock) { failure = "spool_failed"; lastGap = "retention_failed"; invalidate(); } refreshSources(); }
            }); } catch (RejectedExecutionException busy) { lastGap = "retention_deferred"; }
            main.postDelayed(this, TimeUnit.MINUTES.toMillis(30));
        }
    };
    private LocationListener locationListener;
    private long locationTicket=-1;
    private volatile long locationNotBeforeNanos=Long.MAX_VALUE;
    private volatile long activityNotBeforeUptime=Long.MAX_VALUE;
    private String locationProvider="";
    private int authorizationStamp=-1;
    private Set<String> browserPackages = Set.of(), inputPackages = Set.of();
    private volatile Runnable stateListener;

    public static synchronized HistoryCapture get(Context context, BooleanSupplier executionPrivacyReady) {
        if (current == null) current = new HistoryCapture(context.getApplicationContext(), executionPrivacyReady);
        return current;
    }
    static synchronized HistoryCapture current() { return current; }
    HistoryCapture(Context context, BooleanSupplier ready) {
        this.context = context; executionPrivacyReady = Objects.requireNonNull(ready);
        preferences = context.getSharedPreferences("history_capture", Context.MODE_PRIVATE);
        try { policy = HistoryPolicy.parse(new JSONObject(preferences.getString("configuration", "{}"))); }
        catch (Exception invalid) { policy = HistoryPolicy.defaults(); failure = "configuration_invalid"; }
        if(new File(context.getFilesDir(),"droy-history-spool/queue.db").isFile())pendingGap="collector_restarted";
    }

    public JSONObject configuration() throws Exception { return policy.toJson(); }
    public boolean requiresBackgroundRuntime() { return canCapture(); }
    public void setStateListener(Runnable listener) { stateListener = listener; }
    public JSONObject configure(JSONObject patch) throws Exception {
        synchronized (lock) {
            HistoryPolicy next = policy.patch(patch);
            if (next.expandsConsentFrom(policy) && !executionPrivacyReady.getAsBoolean()) throw new IllegalStateException("HISTORY_EXECUTION_NOT_READY");
            if (!preferences.edit().putString("configuration", next.toJson().toString()).commit()) throw new IllegalStateException("HISTORY_SETTINGS_WRITE_FAILED");
            boolean wasCapturing = canCapture();
            policy = next; failure = "";
            if (wasCapturing && !next.wantsCapture()) pendingGap = next.paused ? "user_paused" : "user_disabled";
            invalidate();
        }
        persistPendingGap();
        refreshSources(); return configuration();
    }
    public void start() {
        synchronized (lock) { if (closed) throw new IllegalStateException("HISTORY_CLOSED"); if(running)return; running = true; invalidate(); }
        // Deletion does not require capture consent. A disabled collector still
        // removes old delivery inputs while the app process is available.
        main.removeCallbacks(retentionSweep); main.post(retentionSweep);
        refreshSources();
    }
    public void stop(String reason) {
        synchronized (lock) {
            // The host may already have revoked readiness before asking us to
            // stop. The prior active epoch still establishes a real boundary.
            if (epoch.ticket()>=0 && (spool!=null||new File(context.getFilesDir(),"droy-history-spool/queue.db").isFile())) pendingGap = safeReason(reason);
            running = false; lastGap = safeReason(reason); invalidate();
        }
        persistPendingGap();
        refreshSources();
    }
    public void refreshAuthorization() {
        synchronized (lock) {
            int observed=authorizationState();
            if(observed!=authorizationStamp){
                if(epoch.ticket()>=0&&(spool!=null||new File(context.getFilesDir(),"droy-history-spool/queue.db").isFile()))pendingGap="authorization_changed";
                invalidate();
            }
        }
        persistPendingGap();
        refreshSources();
    }
    private int authorizationState(){
        return (executionPrivacyReady.getAsBoolean()?1:0)|(permission(Manifest.permission.ACCESS_FINE_LOCATION)?2:0)
            |(permission(Manifest.permission.ACCESS_COARSE_LOCATION)?4:0)|(permission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)?8:0);
    }
    private void invalidate() {
        authorizationStamp=authorizationState();
        epoch.replace(canCapture());
        locationNotBeforeNanos=canCapture()&&policy.locationEnabled&&locationPermission()?SystemClock.elapsedRealtimeNanos():Long.MAX_VALUE;
        activityNotBeforeUptime=canCapture()&&policy.enabled?SystemClock.uptimeMillis():Long.MAX_VALUE;
        activePackage = ""; lastPageDigest = ""; lastPageAt = 0; lastDevice = "";
    }
    private boolean canCapture() {
        return !closed && running && failure.isEmpty() && policy.wantsCapture() && executionPrivacyReady.getAsBoolean();
    }
    public JSONObject status() throws Exception {
        boolean ready = executionPrivacyReady.getAsBoolean();
        String state = !failure.isEmpty() ? "storage_limited" : policy.paused ? "paused" : !policy.wantsCapture() ? "off"
            : !ready ? "waiting_execution_privacy" : !running ? "service_interrupted" : "collecting";
        return new JSONObject().put("state", state).put("executionPrivacyReady", ready)
            .put("activityState", activityState).put("locationState", locationState).put("failure", failure)
            .put("droppedCallbacks", dropped).put("lastGap", lastGap).put("pendingCallbacks", writer.getQueue().size())
            .put("spoolBudgetBytes", HistoryPolicy.SPOOL_BYTES).put("pageByteLimit", HistoryPolicy.PAGE_BYTES)
            .put("locationPermission", locationPermission()).put("backgroundLocationPermission", permission(Manifest.permission.ACCESS_BACKGROUND_LOCATION));
    }

    public CompletableFuture<JSONObject> readBatch(int limit, int maxBytes) {
        return database(() -> {
            if (!executionPrivacyReady.getAsBoolean()) throw new IllegalStateException("HISTORY_EXECUTION_NOT_READY");
            if(spool==null&&!new File(context.getFilesDir(),"droy-history-spool/queue.db").isFile())
                return new JSONObject().put("batchId",JSONObject.NULL).put("items",new org.json.JSONArray()).put("bytes",0).put("hasMore",false).put("continuityId",JSONObject.NULL);
            return spool().readBatch(limit, maxBytes, System.currentTimeMillis());
        });
    }
    public CompletableFuture<JSONObject> acknowledge(String batchId) {
        return database(() -> {
            if (!executionPrivacyReady.getAsBoolean()) throw new IllegalStateException("HISTORY_EXECUTION_NOT_READY");
            JSONObject result = spool().acknowledge(batchId);
            synchronized (lock) { if ("storage_limited".equals(failure) || "spool_failed".equals(failure) || "callback_queue_full".equals(failure)) { failure = ""; invalidate(); } }
            refreshSources(); return result;
        });
    }
    public CompletableFuture<JSONObject> spoolStatus() { return database(() -> spool().status()); }
    private interface DatabaseCall { JSONObject run() throws Exception; }
    private CompletableFuture<JSONObject> database(DatabaseCall call) {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        try { writer.execute(() -> { try {
            if (closed) throw new IllegalStateException("HISTORY_CLOSED");
            synchronized (lock) { flushGap(); }
            result.complete(call.run());
        } catch (Exception error) { result.completeExceptionally(error); } }); }
        catch (RejectedExecutionException busy) { result.completeExceptionally(new IllegalStateException("HISTORY_BUSY")); }
        return result;
    }
    private HistorySpool spool() {
        if (spool == null) spool = new HistorySpool(new File(context.getFilesDir(), "droy-history-spool"), HistoryPolicy.SPOOL_BYTES);
        return spool;
    }

    public void onAccessibilityEvent(AccessibilityEvent event, AccessibilityService service, JSONObject actor) {
        long ticket = epoch.ticket(); if (ticket < 0 || !canCapture() || !policy.enabled) return;
        long eventTime=event.getEventTime();
        if(eventTime<=0||eventTime<activityNotBeforeUptime||eventTime>SystemClock.uptimeMillis())return;
        accessibilityConnected = true;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
            && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && type != AccessibilityEvent.TYPE_VIEW_SCROLLED) return;
        String name = event.getPackageName() == null ? "" : event.getPackageName().toString();
        boolean unlocked = unlocked();
        try {
            synchronized (lock) {
                if (!epoch.accepts(ticket) || !canCapture()) return;
                if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                    if (policy.activityEnabled && !activePackage.isEmpty() && (!unlocked || !activePackage.equals(name))) {
                        enqueue(ticket, "android_activity", new JSONObject().put("packageName", activePackage)
                            .put("transition", "leave").put("coverage", "observed_window_event"), actor, System.currentTimeMillis());
                    }
                    if (!unlocked || !policy.allowsPackage(name, context.getPackageName()) || inputPackages.contains(name)) { activePackage = ""; return; }
                    if (policy.activityEnabled && !name.equals(activePackage)) enqueue(ticket, "android_activity",
                        new JSONObject().put("packageName", name).put("transition", "foreground_candidate")
                            .put("coverage", "observed_window_event"), actor, System.currentTimeMillis());
                    activePackage = name;
                }
                long elapsed = SystemClock.elapsedRealtime();
                if (!policy.allowsPage(name, context.getPackageName(), unlocked, inputPackages.contains(name), isBrowser(name))
                    || elapsed - lastPageAt < HistoryPolicy.PAGE_INTERVAL_MS) return;
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (root == null) return;
                JSONObject projection;
                try {
                    if (root.getPackageName() == null || !name.contentEquals(root.getPackageName())) return;
                    projection = PageProjection.project(root);
                } finally { root.recycle(); }
                if (!epoch.accepts(ticket) || !canCapture() || projection.optString("text").trim().isEmpty()) return;
                String digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest((name + "\n" + projection.getString("text")).getBytes(StandardCharsets.UTF_8)));
                if (digest.equals(lastPageDigest)) return;
                lastPageAt = elapsed; lastPageDigest = digest;
                projection.put("packageName", name);
                enqueue(ticket, "android_page", projection, actor, System.currentTimeMillis());
            }
        } catch (Exception error) { recordFailure("projection_failed"); }
    }

    public void accessibilityConnectionChanged(boolean connected) {
        accessibilityConnected = connected;
        activityState = !policy.enabled || (!policy.activityEnabled && !policy.pageTextEnabled) ? "off" : connected ? "observing" : "waiting_permission";
        if (!connected) { synchronized (lock) { invalidate(); } refreshSources(); }
        if (policy.enabled && (policy.activityEnabled || policy.pageTextEnabled))
            coverage("accessibility", connected ? "observing" : "waiting_permission", connected ? "service_connected" : "service_disconnected");
    }
    private JSONObject actor(JSONObject value) throws Exception {
        JSONObject result = new JSONObject().put("kind", "unknown");
        if (value == null) return result;
        // The supplied value must come from host execution state, not page text.
        String kind = value.optString("kind");
        if (kind.equals("droy_activity") || kind.equals("user_explicit")) result.put("kind", kind);
        for (String field : List.of("taskId", "callId")) {
            String id = value.optString(field);
            if (id.matches("[A-Za-z0-9_.:-]{1,160}")) result.put(field, id);
        }
        return result;
    }
    private void enqueue(long ticket, String source, JSONObject payload, JSONObject actor, long occurredAt) throws Exception {
        long now = System.currentTimeMillis(), retention = source.equals("android_page") ? HistoryPolicy.PAGE_RETENTION_MS
            : source.equals("android_location") ? HistoryPolicy.LOCATION_RETENTION_MS : HistoryPolicy.ACTIVITY_RETENTION_MS;
        JSONObject event = new JSONObject().put("eventId", UUID.randomUUID().toString()).put("sourceType", source)
            .put("occurredAtMs", occurredAt).put("recordedAtMs", now).put("expiresAtMs", now + retention)
            .put("actor", actor(actor)).put("payload", payload);
        try {
            writer.execute(() -> {
                synchronized (lock) {
                    try { flushGap(); } catch (RuntimeException error) { failure = "spool_failed"; lastGap = failure; invalidate(); refreshSources(); return; }
                    if (!epoch.accepts(ticket) || !canCapture() || (source.equals("android_location") && !locationPermission())
                        || ((source.equals("android_activity") || source.equals("android_page")) && !accessibilityConnected)) return;
                    try {
                        if (!spool().append(event, source.equals("android_coverage"))) { failure = "storage_limited"; lastGap = failure; invalidate(); refreshSources(); }
                    } catch (Exception error) { failure = "spool_failed"; lastGap = failure; pendingGap=failure; invalidate(); refreshSources(); }
                }
            });
        } catch (RejectedExecutionException busy) {
            synchronized (lock) { dropped++; lastGap = "callback_queue_full"; pendingGap = lastGap; failure = lastGap; invalidate(); }
            refreshSources();
        }
    }
    private void coverage(String source, String state, String reason) {
        long ticket = epoch.ticket(); if (ticket < 0) return;
        try { enqueue(ticket, "android_coverage", new JSONObject().put("source", source).put("state", state).put("reason", reason), null, System.currentTimeMillis()); }
        catch (Exception error) { recordFailure("coverage_failed"); }
    }
    private void recordFailure(String code) { synchronized (lock) { lastGap = code; dropped++; pendingGap = code; } }
    private void flushGap() { if (!pendingGap.isEmpty()) { spool().noteGap(pendingGap); pendingGap = ""; } }
    private void persistPendingGap() {
        synchronized (lock) { if (pendingGap.isEmpty() || closed) return; }
        try { writer.execute(() -> { synchronized (lock) {
            try { flushGap(); } catch (RuntimeException error) { failure = "spool_failed"; lastGap = failure; }
        } }); } catch (RejectedExecutionException busy) { /* The next queued callback flushes the same bounded marker. */ }
    }
    private static String safeReason(String value) { return value != null && value.matches("[a-z_]{1,64}") ? value : "stopped"; }

    private void refreshSources() { main.post(() -> { applySources(); Runnable listener = stateListener; if (listener != null) listener.run(); }); }
    private void applySources() {
        if (closed || !canCapture()) {
            unregisterReceivers(); stopLocationUpdates();
            context.stopService(new Intent(context, HistoryLocationService.class));
            activityState = "off"; locationState = !policy.locationEnabled ? "off" : policy.paused ? "paused" : "waiting_capture";
            return;
        }
        resolveSensitivePackages();
        activityState = policy.enabled && (policy.activityEnabled || policy.pageTextEnabled) ? accessibilityConnected ? "observing" : "waiting_permission" : "off";
        boolean wantsDeviceEvents = policy.enabled && (policy.activityEnabled || policy.pageTextEnabled || policy.deviceStateEnabled);
        if (!wantsDeviceEvents) unregisterReceivers();
        if (wantsDeviceEvents && !receiversRegistered) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT); filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(deviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(deviceReceiver, filter);
            receiversRegistered = true;
            ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
            if (connectivity != null) { connectivity.registerDefaultNetworkCallback(networkCallback); networkRegistered = true; }
        }
        if (policy.locationEnabled) {
            if (!locationPermission()) { locationState = "waiting_permission"; stopLocationUpdates(); return; }
            if (!locationServiceRunning) {
                try { context.startForegroundService(new Intent(context, HistoryLocationService.class)); locationState = "starting"; }
                catch (RuntimeException restricted) { locationState = "system_restricted"; coverage("location", locationState, "foreground_start_restricted"); }
            } else locationServiceStarted();
        } else { stopLocationUpdates(); context.stopService(new Intent(context, HistoryLocationService.class)); locationState = "off"; }
    }
    private final BroadcastReceiver deviceReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                synchronized (lock) { activePackage = ""; lastPageDigest = ""; }
                if (policy.enabled && (policy.activityEnabled || policy.pageTextEnabled)) coverage("activity", "system_restricted", "screen_off");
            } else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction()) && policy.enabled && (policy.activityEnabled || policy.pageTextEnabled))
                coverage("activity", "observing", "screen_unlocked");
            captureDevice(intent);
        }
    };
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { captureDevice(null); }
        @Override public void onLost(Network network) { captureDevice(null); }
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { captureDevice(null); }
    };
    private void captureDevice(Intent battery) {
        long ticket = epoch.ticket(); if (ticket < 0 || !policy.enabled || !policy.deviceStateEnabled || !canCapture()) return;
        try {
            JSONObject payload = new JSONObject().put("screenInteractive", context.getSystemService(PowerManager.class).isInteractive());
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            NetworkCapabilities caps = manager == null ? null : manager.getNetworkCapabilities(manager.getActiveNetwork());
            payload.put("networkType", caps == null ? "none" : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cellular" : "other");
            BatteryManager power = context.getSystemService(BatteryManager.class);
            if (power != null) { int level = power.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY); if (level >= 0 && level <= 100) payload.put("batteryPercent", level); payload.put("charging", power.isCharging()); }
            synchronized (lock) {
                if (payload.toString().equals(lastDevice)) return;
                lastDevice = payload.toString(); enqueue(ticket, "android_device", payload, null, System.currentTimeMillis());
            }
        } catch (Exception error) { recordFailure("device_state_failed"); }
    }
    private void unregisterReceivers() {
        if (!receiversRegistered) return;
        context.unregisterReceiver(deviceReceiver);
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity != null && networkRegistered) connectivity.unregisterNetworkCallback(networkCallback);
        networkRegistered = false; receiversRegistered = false;
    }
    private void resolveSensitivePackages() {
        Set<String> browsers = new HashSet<>(), keyboards = new HashSet<>();
        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid")).addCategory(Intent.CATEGORY_BROWSABLE);
        for (var item : context.getPackageManager().queryIntentActivities(web, PackageManager.MATCH_ALL)) if (item.activityInfo != null) browsers.add(item.activityInfo.packageName);
        InputMethodManager input = context.getSystemService(InputMethodManager.class);
        if (input != null) for (var item : input.getInputMethodList()) keyboards.add(item.getPackageName());
        browserPackages = Set.copyOf(browsers); inputPackages = Set.copyOf(keyboards);
    }
    private boolean isBrowser(String name) {
        if (browserPackages.contains(name)) return true;
        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid"))
            .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(name);
        return !context.getPackageManager().queryIntentActivities(web, PackageManager.MATCH_ALL).isEmpty();
    }
    private boolean permission(String name) { return context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED; }
    boolean locationPermission() { return permission(Manifest.permission.ACCESS_COARSE_LOCATION) || permission(Manifest.permission.ACCESS_FINE_LOCATION); }
    private boolean unlocked() {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        PowerManager power = context.getSystemService(PowerManager.class);
        return power != null && power.isInteractive() && keyguard != null && !keyguard.isDeviceLocked();
    }
    boolean wantsLocation() { return canCapture() && policy.locationEnabled && locationPermission(); }
    void locationServiceStarted() {
        locationServiceRunning = true;
        if (!wantsLocation()) return;
        LocationManager manager = context.getSystemService(LocationManager.class);
        if (manager == null || !manager.isLocationEnabled()) { stopLocationUpdates();locationState = "system_restricted"; return; }
        long ticket = epoch.ticket();
        String provider = manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : LocationManager.PASSIVE_PROVIDER;
        if(locationListener!=null&&locationTicket==ticket&&provider.equals(locationProvider))return;
        stopLocationUpdates();
        locationNotBeforeNanos=SystemClock.elapsedRealtimeNanos();
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) { captureLocation(ticket, location); }
            @Override public void onProviderDisabled(String provider) { locationState = "system_restricted"; coverage("location", locationState, "provider_disabled"); }
            @Override public void onProviderEnabled(String provider) { locationState = "observing"; }
        };
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
            && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationState = "waiting_permission"; locationListener = null; return;
        }
        try { manager.requestLocationUpdates(provider, 300000L, 100f, locationListener, Looper.getMainLooper());locationTicket=ticket;locationProvider=provider; locationState = provider.equals(LocationManager.PASSIVE_PROVIDER) ? "passive_waiting" : "observing"; coverage("location", locationState, "provider_subscribed"); }
        catch (SecurityException denied) { locationState = "waiting_permission"; stopLocationUpdates(); }
        catch (IllegalArgumentException unavailable) { locationState = "system_restricted"; stopLocationUpdates(); }
    }
    private void captureLocation(long ticket, Location location) {
        if (!epoch.accepts(ticket) || !wantsLocation()) return;
        // Cached locations may be fresh but still predate consent or resume.
        // Keep the monotonic subscription boundary across unchanged refreshes.
        if(location.getElapsedRealtimeNanos()<locationNotBeforeNanos)return;
        long ageNs = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
        if (ageNs < 0 || ageNs > TimeUnit.MINUTES.toNanos(15) || !location.hasAccuracy() || !Float.isFinite(location.getAccuracy()) || location.getAccuracy() <= 0) {
            coverage("location", "system_restricted", "stale_or_invalid_sample"); return;
        }
        try {
            if (!Double.isFinite(location.getLatitude()) || !Double.isFinite(location.getLongitude()) || Math.abs(location.getLatitude()) > 90 || Math.abs(location.getLongitude()) > 180) return;
            JSONObject payload = new JSONObject().put("latitude", location.getLatitude()).put("longitude", location.getLongitude())
                .put("accuracyMeters", location.getAccuracy()).put("provider", location.getProvider())
                .put("elapsedRealtimeNanos", Long.toString(location.getElapsedRealtimeNanos()))
                .put("approximate", !permission(Manifest.permission.ACCESS_FINE_LOCATION)).put("stale", false)
                .put("isMock", Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider());
            enqueue(ticket, "android_location", payload, null, location.getTime()); locationState = "observing";
        } catch (Exception error) { recordFailure("location_failed"); }
    }
    private void stopLocationUpdates() {
        if (locationListener != null) {
            LocationManager manager = context.getSystemService(LocationManager.class);
            if (manager != null) manager.removeUpdates(locationListener);
            locationListener = null;
        }
        locationTicket=-1;locationProvider="";locationNotBeforeNanos=Long.MAX_VALUE;
    }
    void locationServiceStopped() { locationServiceRunning = false; stopLocationUpdates(); if (policy.locationEnabled) locationState = "service_interrupted"; }
    void locationServiceFailed() { locationServiceRunning = false; locationState = "system_restricted"; coverage("location", locationState, "foreground_start_restricted"); }

    @Override public void close() {
        synchronized (lock) { if (closed) return; closed = true; running = false; invalidate(); }
        main.removeCallbacks(retentionSweep);
        refreshSources();
        writer.shutdown();
        synchronized (HistoryCapture.class) { if (current == this) current = null; }
    }
}
