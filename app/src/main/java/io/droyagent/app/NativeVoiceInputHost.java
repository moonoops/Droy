package io.droyagent.app;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import io.droyagent.app.media.MediaInputStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Product-owned persistent recording. Presentation receives identities and facts, never paths. */
public final class NativeVoiceInputHost {
    private static final String OWNER = "droy-native-input";
    private static final String KEY = "operation";
    public static final int MAX_AUDIO_BYTES = 20 * 1024 * 1024;
    private static String wakeOwner;
    private NativeVoiceInputHost() {}

    public static final class Failure extends Exception {
        public final String code;
        private Failure(String code) { super(code); this.code = code; }
    }
    private static Failure fail(String code) { return new Failure(code); }
    private static SharedPreferences preferences(Context context) { return context.getSharedPreferences("native_voice_capture", Context.MODE_PRIVATE); }
    private static JSONObject operation(Context context) throws Exception {
        String raw = preferences(context).getString(KEY, null);
        if (raw == null) return null;
        try {
            JSONObject value = new JSONObject(raw);
            uuid(value.getString("id")); validateTarget(value.getJSONObject("target"));
            if (value.getInt("schema") != 1) throw fail("native_voice_storage");
            return value;
        } catch (org.json.JSONException | IllegalArgumentException invalid) { throw fail("native_voice_storage"); }
    }
    private static JSONObject require(Context context, String id) throws Exception {
        uuid(id); JSONObject value = operation(context);
        if (value == null || !id.equals(value.getString("id"))) throw fail("native_voice_owner");
        return value;
    }
    private static void save(Context context, JSONObject value) throws Failure {
        if (!preferences(context).edit().putString(KEY, value.toString()).commit()) throw fail("native_voice_storage");
    }

    public static JSONObject permissions(Context context) throws Exception {
        SharedPreferences product = ProductPermissions.preferences(context);
        return new JSONObject().put("microphone", context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            .put("microphoneAllowed", ProductPermissions.allowed(product, "allowAudioMicrophone"))
            .put("transcriptionAllowed", ProductPermissions.allowed(product, "allowAudioTranscription"))
            .put("paused", ProductPermissions.stopped(context))
            .put("runtimeReady", NativeRuntimeHost.inspect(context).ready);
    }
    private static void checkStart(Context context) throws Exception {
        JSONObject facts = permissions(context);
        if (facts.getBoolean("paused")) throw fail("native_voice_paused");
        if (!facts.getBoolean("microphone") || !facts.getBoolean("microphoneAllowed"))
            throw fail("native_voice_permission");
        if (!facts.getBoolean("runtimeReady") || BackgroundRuntime.explicitlyStopped(context)) throw fail("runtime_unavailable");
        if (!BackgroundRuntime.visible()) throw fail("native_voice_foreground");
    }

    public static synchronized JSONObject prepare(Context context, String id, JSONObject target, long durationMs) throws Exception {
        uuid(id); validateTarget(target); checkStart(context);
        long bytesDuration = (MAX_AUDIO_BYTES - 44L) * 1000L / (AudioCapture.SAMPLE_RATE * 2L);
        if (durationMs < 1 || durationMs > Math.min(bytesDuration, 60_000L)) throw fail("native_voice_limit");
        JSONObject previous = operation(context);
        if (previous != null) {
            if (!previous.getString("id").equals(id)) throw fail("native_voice_pending");
            if (!previous.getJSONObject("target").toString().equals(target.toString())) throw fail("native_voice_owner");
            return snapshot(context, id, 0);
        }
        save(context, new JSONObject().put("schema", 1).put("id", id).put("target", new JSONObject(target.toString()))
            .put("durationMs", durationMs).put("startRequested", false).put("cancelRequested", false).put("createdAt", System.currentTimeMillis()));
        return snapshot(context, id, 0);
    }

    public static synchronized JSONObject start(Context context, String id) throws Exception {
        JSONObject value = require(context, id);
        if (value.getBoolean("cancelRequested")) throw fail("native_voice_cancelled");
        if (value.getBoolean("startRequested")) return snapshot(context, id, 0);
        checkStart(context);
        value.put("startRequested", true); save(context, value);
        try {
            VoiceWake.get(context).claimForVoice(); wakeOwner = id;
            VoiceRuntime.request(context, new JSONObject().put("operation", "stop_speaking"));
            JSONObject capture = AudioCapture.get(context).start(new JSONObject().put("source", "microphone").put("saveFile", true)
                .put("durationMs", value.getLong("durationMs")), OWNER, id);
            value.put("captureId", capture.getString("captureId")); save(context, value);
            return snapshot(context, id, 0);
        } catch (Exception failure) {
            AudioCapture.get(context).cancelStart(OWNER, id);
            throw failure instanceof Failure ? failure : fail(failure instanceof SecurityException ? "native_voice_permission" : "native_voice_start_failed");
        }
    }

    public static synchronized JSONObject snapshot(Context context, String expectedId, long cursor) throws Exception {
        if (cursor < 0) throw fail("native_voice_owner");
        JSONObject value = operation(context);
        if (value == null) {
            if (expectedId != null) throw fail("native_voice_owner");
            return new JSONObject().put("operation", JSONObject.NULL).put("permissions", permissions(context));
        }
        String id = value.getString("id");
        if (expectedId != null && !id.equals(expectedId)) throw fail("native_voice_owner");
        JSONObject capture = findCapture(context, value);
        String state = capture == null ? value.optBoolean("startRequested") ? "interrupted" : "armed" : capture.getString("state");
        boolean quiescent = !Set.of("awaiting_user", "starting", "recording", "finalizing").contains(state);
        boolean fileReady = capture != null && capture.getBoolean("fileReady");
        double level = 0; long nextCursor = cursor;
        JSONObject permissions = permissions(context);
        if (state.equals("recording") && !permissions.getBoolean("paused") && permissions.getBoolean("microphoneAllowed")) {
            AudioCapture.FrameRead frames = AudioCapture.get(context).readFrames(capture.getString("captureId"), OWNER, cursor, 32768);
            level = Math.min(1, VoicePcm.level(frames.data) * 8); nextCursor = frames.metadata.getLong("nextCursor");
        }
        if (quiescent && id.equals(wakeOwner)) { wakeOwner = null; VoiceWake.get(context).releaseVoice(); }
        JSONObject safe = new JSONObject().put("id", id).put("target", value.getJSONObject("target"))
            .put("captureId", capture == null ? JSONObject.NULL : capture.getString("captureId"))
            .put("state", state).put("quiescent", quiescent).put("fileReady", fileReady)
            .put("cancelRequested", value.getBoolean("cancelRequested"))
            .put("bytes", capture == null ? 0 : capture.getLong("fileBytes"))
            .put("durationMs", capture == null ? 0 : capture.getLong("durationMs"))
            .put("level", level).put("nextCursor", nextCursor)
            .put("reason", capture == null ? "" : machineReason(capture.optString("reason")));
        return new JSONObject().put("operation", safe).put("permissions", permissions);
    }

    public static synchronized JSONObject stop(Context context, String id, boolean cancel) throws Exception {
        JSONObject value = require(context, id);
        Failure storage = null;
        if (cancel) {
            value.put("cancelRequested", true);
            try { save(context, value); } catch (Failure failure) { storage = failure; }
        }
        JSONObject capture = findCapture(context, value);
        if (capture != null) AudioCapture.get(context).stop(capture.getString("captureId"), OWNER);
        else AudioCapture.get(context).cancelStart(OWNER, id);
        if (storage != null) throw storage;
        return snapshot(context, id, 0);
    }

    public static synchronized boolean transcriptionQuiescent(Context context) {
        return !AudioTranscription.get(context).hasActiveJobs();
    }

    public static synchronized JSONObject admitWave(Context context, String id) throws Exception {
        JSONObject value = require(context, id), state = snapshot(context, id, 0).getJSONObject("operation");
        if (!state.getBoolean("quiescent") || !state.getBoolean("fileReady") || value.getBoolean("cancelRequested")) throw fail("native_voice_not_settled");
        File file = AudioCapture.get(context).file(state.getString("captureId"), OWNER);
        JSONObject imported = MediaInputStore.nativeDrafts(context).importCompletedWave(file, id);
        JSONObject item = new JSONObject(imported.getJSONArray("items").getJSONObject(0).toString());
        return item.put("importId", id);
    }

    public static synchronized void discard(Context context, String id) throws Exception {
        JSONObject value = require(context, id), safe = snapshot(context, id, 0).getJSONObject("operation");
        if (!safe.getBoolean("quiescent")) throw fail("native_voice_not_settled");
        JSONObject capture = findCapture(context, value);
        if (capture != null) AudioCapture.get(context).deleteFromUi(capture.getString("captureId"));
        MediaInputStore store = MediaInputStore.nativeDrafts(context);
        JSONArray rows = store.list();
        for (int index = 0; index < rows.length(); index++) if (id.equals(rows.getJSONObject(index).getString("id"))) { store.complete(id, "work"); break; }
        if (!preferences(context).edit().remove(KEY).commit()) throw fail("native_voice_storage");
    }

    private static JSONObject findCapture(Context context, JSONObject value) throws Exception {
        AudioCapture capture = AudioCapture.get(context);
        if (value.has("captureId")) {
            try { return capture.status(value.getString("captureId"), OWNER); }
            catch (IllegalArgumentException missing) { /* A deliberately removed recording may no longer have metadata. */ }
        }
        JSONObject all = capture.uiStatus(), active = all.optJSONObject("active");
        if (matches(active, value.getString("id"))) return active;
        JSONArray recent = all.getJSONArray("recent");
        for (int index = 0; index < recent.length(); index++) if (matches(recent.getJSONObject(index), value.getString("id"))) return recent.getJSONObject(index);
        return null;
    }
    private static boolean matches(JSONObject value, String id) { return value != null && OWNER.equals(value.optString("owner")) && id.equals(value.optString("requestId")); }
    private static String machineReason(String value) { return value.matches("[a-z_]{1,64}") ? value : "unknown"; }
    private static void uuid(String value) throws Failure {
        try { if (!UUID.fromString(value).toString().equals(value)) throw fail("native_voice_owner"); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw fail("native_voice_owner"); }
    }
    private static void validateTarget(JSONObject value) throws Exception {
        if (value == null) throw fail("native_voice_owner");
        Set<String> fields = new LinkedHashSet<>(); value.keys().forEachRemaining(fields::add);
        String kind = value.optString("kind");
        if (kind.equals("companion")) {
            if (!fields.equals(Set.of("kind", "companionId"))) throw fail("native_voice_owner");
            identifier(value.get("companionId"));
        } else if (kind.equals("workbench")) {
            if (!fields.equals(Set.of("kind", "draftId", "sessionId", "workspaceId"))) throw fail("native_voice_owner");
            identifier(value.get("draftId"));
            if (!value.isNull("sessionId")) identifier(value.get("sessionId"));
            if (!value.isNull("workspaceId")) identifier(value.get("workspaceId"));
        } else throw fail("native_voice_owner");
    }
    private static void identifier(Object raw) throws Failure {
        if (!(raw instanceof String value) || value.isBlank() || value.length() > 1024 || !value.equals(value.trim()) || value.codePoints().anyMatch(Character::isISOControl))
            throw fail("native_voice_owner");
    }
}
