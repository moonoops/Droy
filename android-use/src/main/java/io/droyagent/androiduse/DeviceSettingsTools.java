package io.droyagent.androiduse;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.provider.Settings;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Deliberately exposes only named settings with readback and conflict-aware, explicit restoration. */
public final class DeviceSettingsTools {
    private static final int MAX_RECORDS = 20;
    private final Context context;
    private final SharedPreferences preferences;

    public DeviceSettingsTools(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("droy_setting_restore", Context.MODE_PRIVATE);
    }

    public synchronized JSONObject execute(String action, JSONObject args, Cancellation cancellation) throws Exception {
        cancellation.check();
        switch (action) {
            case "settings_get":
                if (args.has("name")) return read(name(args.opt("name")));
                return new JSONObject().put("settings", new JSONArray().put(read("media_volume"))
                        .put(read("brightness"))).put("restoreRecords", listRestoreRecords());
            case "settings_set": return set(args, cancellation);
            case "settings_restore": return restore(args, cancellation);
            default: throw new ActionException("unsupported_action", "Unsupported settings action");
        }
    }

    public synchronized JSONArray listRestoreRecords() throws Exception {
        return records();
    }

    private JSONObject read(String name) throws Exception {
        JSONObject result = new JSONObject().put("name", name).put("sampledAt", System.currentTimeMillis());
        if (name.equals("media_volume")) {
            AudioManager audio = audio();
            return result.put("available", true).put("value", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                    .put("min", audio.getStreamMinVolume(AudioManager.STREAM_MUSIC))
                    .put("max", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                    .put("writable", !audio.isVolumeFixed()).put("unit", "volume_step");
        }
        try {
            int value = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            int mode = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            return result.put("available", true).put("value", value).put("min", 0).put("max", 255)
                    .put("automatic", mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                    .put("writable", Settings.System.canWrite(context)
                            && mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    .put("unit", "brightness_0_255");
        } catch (Settings.SettingNotFoundException missing) {
            return result.put("available", false).put("writable", false).put("reason", "setting_unavailable");
        }
    }

    private JSONObject set(JSONObject args, Cancellation cancellation) throws Exception {
        String name = name(args.opt("name"));
        int value = ActionValidation.integer(args.opt("value"), "value");
        JSONObject before = read(name);
        requireWritable(before);
        validateRange(value, before.getInt("min"), before.getInt("max"));
        int previous = before.getInt("value");
        if (previous == value) return new JSONObject().put("status", "already_set").put("name", name)
                .put("before", previous).put("requested", value).put("after", previous)
                .put("verified", true).put("changed", false);
        cancellation.check();
        // Persist the original value before changing Android: a stop or process death must not lose it.
        JSONObject record = new JSONObject().put("recordId", UUID.randomUUID().toString()).put("name", name)
                .put("before", previous).put("written", value).put("createdAt", System.currentTimeMillis())
                .put("state", "pending");
        saveRecord(record);
        writeTracked(cancellation, () -> write(name, value), state -> {
            record.put("state", state);
            saveRecord(record);
        });
        JSONObject after = read(name);
        boolean verified = after.optBoolean("available") && after.getInt("value") == value;
        record.put("state", verified ? "available" : "unconfirmed")
                .put("observed", after.opt("value"));
        saveRecord(record);
        cancellation.check();
        return new JSONObject().put("status", verified ? "setting_verified" : "setting_unconfirmed")
                .put("name", name).put("before", previous).put("requested", value)
                .put("after", after.opt("value")).put("verified", verified)
                .put("changed", after.optInt("value", previous) != previous)
                .put("restoreRecordId", record.getString("recordId"));
    }

    private JSONObject restore(JSONObject args, Cancellation cancellation) throws Exception {
        Object id = args.opt("recordId");
        if (!(id instanceof String) || ((String) id).length() > 100)
            throw new ActionException("invalid_argument", "recordId is required");
        JSONArray records = records();
        JSONObject record = null;
        for (int i = 0; i < records.length(); i++) {
            if (records.getJSONObject(i).getString("recordId").equals(id)) record = records.getJSONObject(i);
        }
        if (record == null) throw new ActionException("restore_record_missing", "Restore record is no longer available");
        if (record.optString("state").equals("restored"))
            return new JSONObject().put("status", "already_restored").put("recordId", id);
        if (!record.optString("state").equals("available"))
            throw new ActionException("restore_not_confirmed", "The original setting write was not confirmed; no restoration was performed. Read the current setting before requesting a new change");
        String name = name(record.getString("name"));
        JSONObject current = read(name);
        requireWritable(current);
        if (!canRestore(record.getString("state"), current.getInt("value"), record.getInt("written")))
            throw new ActionException("restore_conflict", "Setting changed after Droy's write; current value was kept");
        int value = record.getInt("before");
        validateRange(value, current.getInt("min"), current.getInt("max"));
        cancellation.check();
        record.put("state", "restoring");
        saveRecord(record);
        JSONObject restoring = record;
        writeTracked(cancellation, () -> write(name, value), state -> {
            restoring.put("state", state.equals("not_applied") ? "available" : "restore_unconfirmed");
            saveRecord(restoring);
        });
        JSONObject after = read(name);
        boolean verified = after.optBoolean("available") && after.getInt("value") == value;
        if (verified) {
            record.put("state", "restored").put("restoredAt", System.currentTimeMillis());
        } else record.put("state", "restore_unconfirmed");
        saveRecord(record);
        cancellation.check();
        return new JSONObject().put("status", verified ? "restored" : "restore_unconfirmed")
                .put("recordId", id).put("name", name).put("after", after.opt("value"))
                .put("verified", verified);
    }

    private void write(String name, int value) throws Exception {
        if (name.equals("media_volume")) {
            audio().setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
        } else if (!Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value)) {
            throw new ActionException("setting_rejected", "Android rejected the brightness change");
        }
    }

    private AudioManager audio() throws ActionException {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) throw new ActionException("setting_unavailable", "Audio service is unavailable");
        return audio;
    }

    private static void requireWritable(JSONObject value) throws Exception {
        if (!value.optBoolean("available")) throw new ActionException("setting_unavailable", "Setting could not be read");
        if (!value.optBoolean("writable")) throw new ActionException("setting_not_writable",
                value.optBoolean("automatic") ? "Automatic brightness is active; select manual brightness before changing it"
                        : "Android has not permitted this setting change or the device fixes this value");
    }

    static String name(Object name) throws ActionException {
        if (!"media_volume".equals(name) && !"brightness".equals(name))
            throw new ActionException("invalid_argument", "name must be media_volume or brightness");
        return (String) name;
    }

    static void validateRange(int value, int min, int max) throws ActionException {
        if (value < min || value > max) throw new ActionException("invalid_argument", "value is outside the reported setting range");
    }

    static boolean canRestore(String state, int current, int written) {
        return "available".equals(state) && current == written;
    }

    /** A persisted intent is not evidence that a write ran, even if the value later happens to match. */
    static void writeTracked(Cancellation cancellation, SettingWrite write, StateRecorder record) throws Exception {
        try { cancellation.check(); }
        catch (InterruptedException stopped) {
            try { record.save("not_applied"); }
            catch (Exception failed) { stopped.addSuppressed(failed); }
            throw stopped;
        }
        try { write.run(); }
        catch (Exception failure) {
            try { record.save("unconfirmed"); }
            catch (Exception failed) { failure.addSuppressed(failed); }
            throw new ActionException("setting_unconfirmed", "The setting write did not finish normally; read the current value before requesting another change", failure);
        }
    }

    @FunctionalInterface interface SettingWrite { void run() throws Exception; }
    @FunctionalInterface interface StateRecorder { void save(String state) throws Exception; }

    private JSONArray records() throws Exception {
        return new JSONArray(preferences.getString("records", "[]"));
    }

    private void saveRecord(JSONObject record) throws Exception {
        JSONArray previous = records();
        JSONArray next = new JSONArray().put(record);
        for (int i = 0; i < previous.length() && next.length() < MAX_RECORDS; i++) {
            JSONObject item = previous.getJSONObject(i);
            if (!item.getString("recordId").equals(record.getString("recordId"))) next.put(item);
        }
        if (!preferences.edit().putString("records", next.toString()).commit())
            throw new ActionException("restore_record_failed", "Could not save the setting restore record");
    }
}
