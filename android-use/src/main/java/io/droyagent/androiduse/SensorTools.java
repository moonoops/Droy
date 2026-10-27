package io.droyagent.androiduse;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Finite, task-owned subscriptions; event times retain Android's monotonic time base. */
public final class SensorTools {
    private final Context context;
    private final SensorManager manager;
    private final ConcurrentHashMap<String, Set<Subscription>> active = new ConcurrentHashMap<>();

    public SensorTools(Context context) {
        this.context = context.getApplicationContext();
        manager = this.context.getSystemService(SensorManager.class);
    }

    public JSONObject capabilities() throws Exception {
        return new JSONObject().put("supported", manager != null)
            .put("sensorCount", manager == null ? 0 : manager.getSensorList(Sensor.TYPE_ALL).size())
            .put("activityRecognitionGranted", granted(Manifest.permission.ACTIVITY_RECOGNITION))
            .put("heartRateGranted", granted(heartRatePermission()));
    }

    public JSONObject execute(JSONObject args, String owner, Cancellation cancellation) throws Exception {
        cancellation.check();
        if (manager == null) throw HardwareSupport.error(context, "unsupported_hardware", R.string.hardware_sensor_unavailable);
        String operation = args.optString("operation", "list");
        if (operation.equals("list")) {
            JSONArray sensors = new JSONArray();
            for (Sensor sensor : manager.getSensorList(Sensor.TYPE_ALL)) {
                cancellation.check();
                sensors.put(describe(sensor));
            }
            return new JSONObject().put("status", "queried").put("source", "android_sensor_manager")
                .put("sensors", sensors).put("capturedAt", System.currentTimeMillis());
        }
        if (!Set.of("read", "sample").contains(operation))
            throw HardwareSupport.error(context, "invalid_argument", R.string.hardware_sensor_operation);
        int type = HardwareSupport.integer(args, "sensorType", -1, 1, Integer.MAX_VALUE);
        if (type < 1) throw HardwareSupport.error(context, "invalid_argument", R.string.hardware_sensor_type);
        Sensor sensor = select(type, args.optString("sensorName", ""));
        if (sensor == null) throw HardwareSupport.error(context, "unsupported_hardware", R.string.hardware_sensor_missing);
        requirePermission(sensor.getType());
        int duration = HardwareSupport.integer(args, "durationMs", operation.equals("read") ? 2000 : 1000, 100, 10000);
        int interval = HardwareSupport.integer(args, "intervalMs", 100, 10, 1000);
        int limit = HardwareSupport.integer(args, "limit", operation.equals("read") ? 1 : 100, 1, 1000);
        long deadline = SystemClock.elapsedRealtime() + Math.min(duration, cancellation.remainingMillis(duration));
        Subscription subscription = new Subscription(owner, sensor, limit, interval * 1000000L);
        active.computeIfAbsent(owner, key -> ConcurrentHashMap.newKeySet()).add(subscription);
        long started = System.currentTimeMillis();
        try {
            cancellation.check();
            if (subscription.stopped) throw new InterruptedException();
            boolean registered = sensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT
                ? manager.requestTriggerSensor(subscription.trigger, sensor)
                : manager.registerListener(subscription.listener, sensor, interval * 1000, new Handler(Looper.getMainLooper()));
            if (!registered) throw HardwareSupport.error(context, "sensor_unavailable", R.string.hardware_sensor_register_failed);
            while (SystemClock.elapsedRealtime() < deadline && subscription.data.count() < limit) {
                cancellation.check();
                requirePermission(type);
                if (subscription.stopped) throw new InterruptedException();
                Thread.sleep(Math.min(50L, Math.max(1L, deadline - SystemClock.elapsedRealtime())));
            }
            cancellation.check();
            if (subscription.stopped) throw new InterruptedException();
            requirePermission(type);
            subscription.close();
            JSONObject result = subscription.data.result();
            if (operation.equals("sample") && subscription.data.count() > 20) {
                JSONArray raw = result.getJSONArray("samples"), preview = new JSONArray();
                for (int i = 0; i < Math.min(20, raw.length()); i++) preview.put(raw.get(i));
                cancellation.check();
                JSONObject stored = new DeviceDataStore(context).put(owner,
                    raw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    new JSONObject().put("domain", "sensor").put("format", "application/json").put("sensor", describe(sensor)),
                    false, "sensor-samples.json");
                result.put("samples", preview).put("samplesPreview", true).put("data", stored);
            }
            return result.put("status", subscription.data.count() == 0 ? "no_reading" : "sampled")
                .put("source", "android_sensor_manager").put("sensor", describe(sensor))
                .put("startedAt", started).put("finishedAt", System.currentTimeMillis())
                .put("requestedIntervalMs", interval).put("requestedDurationMs", duration)
                .put("limitReached", subscription.data.count() >= limit)
                .put("timestampClock", "elapsed_realtime_nanoseconds");
        } finally {
            subscription.close();
            Set<Subscription> ownerActive = active.get(owner);
            if (ownerActive != null) { ownerActive.remove(subscription); if (ownerActive.isEmpty()) active.remove(owner, ownerActive); }
        }
    }

    private Sensor select(int type, String name) {
        if (name.isEmpty()) return manager.getDefaultSensor(type);
        for (Sensor sensor : manager.getSensorList(type)) if (sensor.getName().equals(name)) return sensor;
        return null;
    }

    private JSONObject describe(Sensor sensor) throws Exception {
        String required = permissionFor(sensor.getType());
        return new JSONObject().put("sensorType", sensor.getType()).put("sensorName", sensor.getName())
            .put("androidId", sensor.getId()).put("stringType", sensor.getStringType()).put("vendor", sensor.getVendor())
            .put("version", sensor.getVersion()).put("unit", unitFor(sensor.getType()))
            .put("axes", axesFor(sensor.getType())).put("maximumRange", sensor.getMaximumRange())
            .put("resolution", sensor.getResolution()).put("powerMilliAmps", sensor.getPower())
            .put("reportingMode", sensor.getReportingMode()).put("minimumDelayMicros", sensor.getMinDelay())
            .put("wakeUp", sensor.isWakeUpSensor()).put("requiredPermission", required.isEmpty() ? JSONObject.NULL : required)
            .put("systemPermissionGranted", required.isEmpty() || granted(required))
            .put("standardInterpretation", !unitFor(sensor.getType()).equals("unknown"));
    }

    private boolean granted(String permission) { return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private String permissionFor(int type) {
        if (type == Sensor.TYPE_STEP_COUNTER || type == Sensor.TYPE_STEP_DETECTOR) return Manifest.permission.ACTIVITY_RECOGNITION;
        if (type == Sensor.TYPE_HEART_RATE || type == Sensor.TYPE_HEART_BEAT) return heartRatePermission();
        return "";
    }
    private static String heartRatePermission() {
        return Build.VERSION.SDK_INT >= 36 ? "android.permission.health.READ_HEART_RATE" : Manifest.permission.BODY_SENSORS;
    }
    private void requirePermission(int type) throws ActionException {
        String required = permissionFor(type);
        if (!required.isEmpty() && !granted(required))
            throw HardwareSupport.error(context, "system_permission_required", R.string.hardware_sensor_permission);
    }

    static String unitFor(int type) {
        return switch (type) {
            case Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GRAVITY -> "m/s2";
            case Sensor.TYPE_GYROSCOPE -> "rad/s";
            case Sensor.TYPE_MAGNETIC_FIELD -> "microtesla";
            case Sensor.TYPE_LIGHT -> "lux";
            case Sensor.TYPE_PRESSURE -> "hPa";
            case Sensor.TYPE_PROXIMITY -> "cm_or_near_far";
            case Sensor.TYPE_AMBIENT_TEMPERATURE -> "celsius";
            case Sensor.TYPE_RELATIVE_HUMIDITY -> "percent";
            case Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "rotation_vector";
            case Sensor.TYPE_STEP_COUNTER -> "steps_since_reboot";
            case Sensor.TYPE_STEP_DETECTOR, Sensor.TYPE_SIGNIFICANT_MOTION, Sensor.TYPE_STATIONARY_DETECT, Sensor.TYPE_MOTION_DETECT -> "event";
            case Sensor.TYPE_HEART_RATE -> "beats_per_minute";
            case Sensor.TYPE_HEART_BEAT -> "confidence";
            default -> "unknown";
        };
    }
    static String axesFor(int type) {
        return switch (type) {
            case Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GRAVITY,
                Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD -> "device_x_right_y_top_z_out_of_screen_natural_orientation";
            case Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "x_sin_half_angle_y_sin_half_angle_z_sin_half_angle_optional_cos_half_angle_heading_accuracy_rad";
            default -> "sensor_defined";
        };
    }

    public void closeOwner(String owner) {
        Set<Subscription> subscriptions = active.remove(owner);
        if (subscriptions != null) for (Subscription subscription : subscriptions) subscription.close();
    }
    public void close() { for (String owner : active.keySet()) closeOwner(owner); }

    private final class Subscription {
        final Sensor sensor;
        final Samples data;
        volatile boolean stopped;
        final SensorEventListener listener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent event) { if (!stopped) data.add(event.timestamp, event.accuracy, event.values); }
            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
        };
        final TriggerEventListener trigger = new TriggerEventListener() {
            @Override public void onTrigger(TriggerEvent event) { if (!stopped) data.add(event.timestamp, -1, event.values); }
        };
        Subscription(String owner, Sensor sensor, int limit, long intervalNanos) {
            this.sensor = sensor; data = new Samples(limit, intervalNanos);
        }
        void close() {
            stopped = true;
            data.freeze();
            manager.unregisterListener(listener, sensor);
            manager.cancelTriggerSensor(trigger, sensor);
        }
    }

    static final class Samples {
        private final int limit;
        private final long intervalNanos;
        private final JSONArray rows = new JSONArray();
        private double[] min, max, sum;
        private long lastTimestamp = Long.MIN_VALUE, firstTimestamp;
        private int invalid, skipped;
        private boolean frozen;
        Samples(int limit, long intervalNanos) { this.limit = limit; this.intervalNanos = intervalNanos; }
        synchronized int count() { return rows.length(); }
        synchronized void freeze() { frozen = true; }
        synchronized void add(long timestamp, int accuracy, float[] values) {
            if (frozen) return;
            if (rows.length() >= limit) { skipped++; return; }
            if (timestamp <= 0 || values.length == 0 || min != null && values.length != min.length) { invalid++; return; }
            for (float value : values) if (!Float.isFinite(value)) { invalid++; return; }
            if (lastTimestamp != Long.MIN_VALUE && (timestamp <= lastTimestamp || timestamp - lastTimestamp < intervalNanos)) { skipped++; return; }
            try {
                if (min == null) { min = new double[values.length]; max = new double[values.length]; sum = new double[values.length]; Arrays.fill(min, Double.POSITIVE_INFINITY); Arrays.fill(max, Double.NEGATIVE_INFINITY); firstTimestamp = timestamp; }
                JSONArray vector = new JSONArray();
                for (int i = 0; i < values.length; i++) { vector.put(values[i]); min[i] = Math.min(min[i], values[i]); max[i] = Math.max(max[i], values[i]); sum[i] += values[i]; }
                rows.put(new JSONObject().put("elapsedRealtimeNanos", Long.toString(timestamp)).put("accuracy", accuracy).put("values", vector));
                lastTimestamp = timestamp;
            } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        }
        synchronized JSONObject result() throws Exception {
            JSONObject result = new JSONObject().put("samples", new JSONArray(rows.toString())).put("sampleCount", rows.length())
                .put("invalidSampleCount", invalid).put("skippedSampleCount", skipped);
            if (min != null) {
                JSONArray minima = new JSONArray(), maxima = new JSONArray(), means = new JSONArray();
                for (int i = 0; i < min.length; i++) { minima.put(min[i]); maxima.put(max[i]); means.put(sum[i] / rows.length()); }
                result.put("summary", new JSONObject().put("minimum", minima).put("maximum", maxima).put("mean", means)
                    .put("observedDurationMs", (lastTimestamp - firstTimestamp) / 1000000.0));
            }
            return result;
        }
    }
}
