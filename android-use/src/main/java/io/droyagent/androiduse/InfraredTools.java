package io.droyagent.androiduse;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.ConsumerIrManager;
import android.os.SystemClock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Emits supplied waveforms without inventing remote protocols or claiming appliance state. */
public final class InfraredTools {
    private final Context context;
    private final ConsumerIrManager manager;
    private final ConcurrentHashMap<String, Set<AtomicBoolean>> active = new ConcurrentHashMap<>();

    public InfraredTools(Context context) {
        this.context = context.getApplicationContext();
        manager = this.context.getSystemService(ConsumerIrManager.class);
    }
    public JSONObject capabilities() throws Exception {
        boolean available = manager != null && manager.hasIrEmitter();
        JSONArray ranges = new JSONArray();
        ConsumerIrManager.CarrierFrequencyRange[] reported = available && granted() ? manager.getCarrierFrequencies() : null;
        if (reported != null) for (ConsumerIrManager.CarrierFrequencyRange range : reported)
            ranges.put(new JSONObject().put("minHz", range.getMinFrequency()).put("maxHz", range.getMaxFrequency()));
        return new JSONObject().put("supported", available).put("transmitPermissionGranted", granted())
            .put("carrierRanges", ranges).put("carrierRangesKnown", reported != null)
            .put("maxWaveformDurationMicrosExclusive", 2000000).put("maxSequenceDurationMs", 10000);
    }
    public JSONObject execute(JSONObject args, String owner, Cancellation cancellation) throws Exception {
        cancellation.check();
        String operation = args.optString("operation", "status");
        if (operation.equals("status")) return capabilities();
        if (!operation.equals("transmit")) throw HardwareSupport.error(context, "invalid_argument", R.string.hardware_infrared_operation);
        if (manager == null || !manager.hasIrEmitter()) throw HardwareSupport.error(context, "unsupported_hardware", R.string.hardware_infrared_unavailable);
        if (!granted()) throw HardwareSupport.error(context, "system_permission_required", R.string.hardware_infrared_permission);
        if (!args.has("carrierHz")) throw HardwareSupport.error(context, "invalid_argument", R.string.hardware_infrared_carrier_required);
        int carrier = HardwareSupport.integer(args, "carrierHz", -1, 1000, 1000000);
        if (carrier < 1000) throw HardwareSupport.error(context, "invalid_argument", R.string.hardware_infrared_carrier_required);
        int repeat = HardwareSupport.integer(args, "repeat", 1, 1, 5);
        int interval = HardwareSupport.integer(args, "intervalMs", 100, 0, 2000);
        int[] waveform;
        Object supplied = args.opt("patternMicros");
        if (supplied == null && args.has("dataRef"))
            supplied = new JSONArray(new String(new DeviceDataStore(context).readPayload(args, owner, 65536), java.nio.charset.StandardCharsets.UTF_8));
        try { waveform = waveform(supplied); }
        catch (ActionException invalid) { throw HardwareSupport.error(context, "invalid_argument", R.string.hardware_infrared_waveform); }
        long durationMicros = totalMicros(waveform);
        if (sequenceMillis(durationMicros, repeat, interval) > 10000)
            throw HardwareSupport.error(context, "invalid_argument", R.string.hardware_infrared_sequence);
        ConsumerIrManager.CarrierFrequencyRange[] ranges = manager.getCarrierFrequencies();
        if (ranges != null) {
            boolean found = false;
            for (ConsumerIrManager.CarrierFrequencyRange range : ranges)
                if (carrier >= range.getMinFrequency() && carrier <= range.getMaxFrequency()) { found = true; break; }
            if (!found) throw HardwareSupport.error(context, "invalid_argument", R.string.hardware_infrared_carrier);
        }
        AtomicBoolean stopped = new AtomicBoolean();
        active.computeIfAbsent(owner, key -> ConcurrentHashMap.newKeySet()).add(stopped);
        int sent = 0, attempted = 0;
        try {
            for (int index = 0; index < repeat; index++) {
                cancellation.check();
                if (stopped.get()) throw new InterruptedException();
                if (!granted()) throw HardwareSupport.error(context, "system_permission_required", R.string.hardware_infrared_permission);
                if (cancellation.remainingMillis((durationMicros + 999) / 1000) < (durationMicros + 999) / 1000)
                    throw new InterruptedException();
                cancellation.dispatched();
                attempted++;
                manager.transmit(carrier, waveform);
                sent++;
                if (index < repeat - 1) {
                    long until = SystemClock.elapsedRealtime() + interval;
                    while (SystemClock.elapsedRealtime() < until) {
                        cancellation.check(); if (stopped.get()) throw new InterruptedException();
                        Thread.sleep(Math.min(50L, Math.max(1L, until - SystemClock.elapsedRealtime())));
                    }
                }
            }
            return new JSONObject().put("status", "transmitted").put("source", "android_consumer_ir")
                .put("carrierHz", carrier).put("patternDurationMicros", durationMicros).put("transmittedCount", sent)
                .put("deviceResponse", "not_observed").put("completedAt", System.currentTimeMillis());
        } catch (InterruptedException cancelled) {
            throw new ActionException("cancelled", HardwareSupport.text(context, R.string.hardware_infrared_stopped), cancelled)
                .withDetails(new JSONObject().put("transmittedCount", sent).put("requestedCount", repeat)
                    .put("attemptedCount", attempted)
                    .put("deviceResponse", "not_observed"));
        } catch (ActionException failure) {
            throw failure.withDetails(new JSONObject().put("transmittedCount", sent).put("requestedCount", repeat)
                .put("attemptedCount", attempted).put("deviceResponse", "not_observed"));
        } catch (RuntimeException failure) {
            throw new ActionException("infrared_unconfirmed", HardwareSupport.text(context, R.string.hardware_infrared_failed), failure)
                .withDetails(new JSONObject().put("transmittedCount", sent).put("requestedCount", repeat)
                    .put("attemptedCount", attempted).put("cause", failure.getClass().getSimpleName())
                    .put("deviceResponse", "not_observed"));
        } finally {
            Set<AtomicBoolean> ownerActive = active.get(owner);
            if (ownerActive != null) { ownerActive.remove(stopped); if (ownerActive.isEmpty()) active.remove(owner, ownerActive); }
        }
    }
    private boolean granted() { return context.checkSelfPermission(Manifest.permission.TRANSMIT_IR) == PackageManager.PERMISSION_GRANTED; }
    public static int[] waveform(Object value) throws ActionException {
        if (!(value instanceof JSONArray) || ((JSONArray) value).length() < 1 || ((JSONArray) value).length() > 4096)
            throw new ActionException("invalid_argument", "invalid_waveform");
        JSONArray values = (JSONArray) value;
        int[] pattern = new int[values.length()];
        for (int index = 0; index < pattern.length; index++) {
            pattern[index] = ActionValidation.integer(values.opt(index), "patternMicros");
            if (pattern[index] <= 0) throw new ActionException("invalid_argument", "invalid_waveform");
        }
        if (totalMicros(pattern) >= 2000000) throw new ActionException("invalid_argument", "invalid_waveform");
        return pattern;
    }
    static long totalMicros(int[] pattern) { long sum = 0; for (int duration : pattern) sum += duration; return sum; }
    static long sequenceMillis(long micros, int repeat, int intervalMs) { return (micros * repeat + 999) / 1000 + (long) intervalMs * (repeat - 1); }
    public void closeOwner(String owner) {
        Set<AtomicBoolean> flags = active.remove(owner);
        if (flags != null) for (AtomicBoolean flag : flags) flag.set(true);
    }
    public void close() { for (String owner : active.keySet()) closeOwner(owner); }
}
