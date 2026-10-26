package io.droyagent.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Set;
import java.util.UUID;

/** Native lifetime for the Voice plugin; it reuses the existing capture and ASR services. */
final class VoiceMedia {
    static final String OWNER = "droy-voice";
    private static VoiceMedia instance;
    static synchronized VoiceMedia get(Context context) {
        if (instance == null) instance = new VoiceMedia(context.getApplicationContext());
        return instance;
    }
    static void stopIfCreated(String reason) {
        VoiceMedia current;
        synchronized (VoiceMedia.class) { current = instance; }
        if (current != null) current.stopForPolicy(reason);
    }
    static boolean hasActiveWork(Context context) {
        VoiceMedia current;
        synchronized (VoiceMedia.class) { current = instance; }
        return current != null && current.active();
    }

    private final Context context;
    private final AudioCapture capture;
    private final AudioTranscription transcription;
    private final VoiceSpeechOutput output;
    private final SharedPreferences preferences;
    private String captureId = "", transcriptId = "", sessionId = "", mode = "standard", state = "idle", error = "";
    private boolean muted, resumePending;
    private String language = "auto", polledCaptureId = "";
    private long lastSpeechMs;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesChanged;

    private VoiceMedia(Context context) {
        this.context = context;
        capture = AudioCapture.get(context); transcription = AudioTranscription.get(context);
        output = new VoiceSpeechOutput(context); preferences = ProductPermissions.preferences(context);
        preferencesChanged = (prefs, key) -> {
            if (ProductPermissions.stopped(context) || BackgroundRuntime.explicitlyStopped(context)
                || (!ProductPermissions.allowed(prefs, "allowAudioMicrophone") && active()))
                stopForPolicy("permission_revoked");
        };
        preferences.registerOnSharedPreferenceChangeListener(preferencesChanged);
    }

    synchronized JSONObject request(JSONObject args) throws Exception {
        String operation = args.getString("operation");
        return switch (operation) {
            case "start" -> start(args);
            case "status" -> status();
            case "poll" -> poll(args);
            case "stop" -> stop("user_stop");
            case "dispose" -> dispose();
            case "mute" -> {
                requireActive();
                if (!(args.opt("muted") instanceof Boolean)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("静音设置无效", "Invalid mute setting"));
                yield setMuted(args.getBoolean("muted"));
            }
            case "voices" -> output.voices();
            case "tts.models", "tts.download", "tts.cancel_download" -> output.modelRequest(args);
            case "speak" -> { checkOutputAllowed(); yield output.speak(args); }
            case "stop_speaking" -> { output.stop(); yield status(); }
            case "clear_playback" -> {
                output.clear(args.optString("responseId")); yield status();
            }
            case "play" -> {
                checkOutputAllowed();
                String encoded = args.getString("audio");
                if (encoded.length() > 256000) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("语音块过大", "Speech chunk is too large"));
                byte[] bytes;
                try { bytes = Base64.decode(encoded, Base64.DEFAULT); }
                catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("语音数据不是有效的 Base64", "Speech data is not valid Base64")); }
                output.play(bytes, args.getInt("sampleRate"), args.optString("responseId"));
                yield new JSONObject().put("state", "queued").put("bytes", bytes.length);
            }
            case "preparation", "prepare", "cancel_preparation" -> transcription.request(args, OWNER);
            default -> throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("未知语音操作：", "Unknown speech operation: ") + operation);
        };
    }

    private JSONObject start(JSONObject args) throws Exception {
        checkOutputAllowed();
        String nextMode = args.optString("mode", "standard"), nextSession = args.optString("sessionId");
        if (!nextMode.equals("standard")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("本版仅开放普通语音对话", "Only standard voice chat is available in this version"));
        if (nextSession.isBlank() || nextSession.length() > 256) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("请先打开一个对话", "Please open a conversation first"));
        if (active()) {
            if (sessionId.equals(nextSession) && mode.equals(nextMode)) return status();
            throw new IllegalStateException(io.droyagent.app.NativeLocale.text("已有语音对话进行中，请先结束", "A voice chat is already active. Please end it first"));
        }
        if (nextMode.equals("standard")) {
            JSONObject preparation = transcription.request(new JSONObject().put("operation", "preparation"), OWNER);
            if (!preparation.optBoolean("ready")) throw new IllegalStateException(preparation.optString("state").equals("failed")
                ? io.droyagent.app.NativeLocale.text("语音初始化失败，请重新打开 Droy", "Speech initialization failed. Please reopen Droy") : io.droyagent.app.NativeLocale.text("语音正在初始化，请稍后再试", "Speech is initializing. Please try again shortly"));
            if (transcription.hasActiveJobs()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("语音识别正在使用，请稍后再试", "Speech recognition is busy. Please try again shortly"));
        }
        language = args.optString("language", "auto");
        if (!Set.of("auto", "zh", "en", "ja", "ko", "yue").contains(language))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("不支持的识别语言", "Unsupported recognition language"));
        JSONObject started = startCapture();
        captureId = started.getString("captureId"); transcriptId = ""; sessionId = nextSession; mode = nextMode;
        state = started.getString("state"); muted = false; resumePending = false; polledCaptureId = ""; lastSpeechMs = 0; error = "";
        try { output.beginCommunication(); ensureTranscription(); }
        catch (Exception failure) {
            error = failure.getMessage() == null ? io.droyagent.app.NativeLocale.text("语音对话未能开始", "Voice chat could not start") : failure.getMessage();
            stop("start_failed"); throw failure;
        }
        RuntimeService.refreshBackgroundPolicy();
        return status();
    }

    private void ensureTranscription() throws Exception {
        if (muted || resumePending || captureId.isEmpty() || !mode.equals("standard") || !transcriptId.isEmpty()) return;
        JSONObject recording = capture.status(captureId, OWNER);
        if (!recording.optString("state").equals("recording")) return;
        try {
            JSONObject started = transcription.request(new JSONObject().put("operation", "transcribe").put("captureId", captureId).put("language", language),
                OWNER, "voice-asr:" + captureId);
            transcriptId = started.getString("transcriptId");
        } catch (Exception failure) {
            error = failure.getMessage() == null ? io.droyagent.app.NativeLocale.text("语音识别未能开始", "Speech recognition could not start") : failure.getMessage();
            stop("transcription_failed"); throw failure;
        }
    }

    private JSONObject startCapture() throws Exception {
        return capture.startVoice(new JSONObject().put("source", "microphone").put("saveFile", false),
            OWNER, UUID.randomUUID().toString());
    }

    private JSONObject setMuted(boolean nextMuted) throws Exception {
        if (nextMuted == muted) return status();
        muted = nextMuted;
        if (muted) {
            resumePending = false;
            if (!transcriptId.isEmpty()) transcription.request(new JSONObject().put("operation", "stop_transcription")
                .put("transcriptId", transcriptId), OWNER);
            if (!captureId.isEmpty()) capture.stop(captureId, OWNER);
            state = "recording";
        } else {
            resumePending = true;
            resumeCaptureIfReady();
        }
        return status();
    }

    private void resumeCaptureIfReady() throws Exception {
        if (!resumePending || muted) return;
        // Native stop is asynchronous. Existing polling waits for actual resource
        // release instead of racing a new AudioRecord or retrying failed starts.
        JSONObject current = capture.status(captureId, OWNER);
        if (!Set.of("completed", "interrupted", "failed").contains(current.optString("state"))) return;
        if (!transcriptId.isEmpty()) {
            JSONObject job = transcription.request(new JSONObject().put("operation", "transcript")
                .put("transcriptId", transcriptId).put("statusOnly", true), OWNER);
            if (!job.optBoolean("finished")) return;
        }
        JSONObject started = startCapture();
        captureId = started.getString("captureId"); transcriptId = ""; polledCaptureId = "";
        state = started.getString("state"); resumePending = false; lastSpeechMs = 0;
        ensureTranscription();
    }

    private JSONObject poll(JSONObject args) throws Exception {
        if (!active()) return status().put("segments", new JSONArray()).put("audio", "")
            .put("cursor", 0).put("nextCursor", 0).put("transcriptCursor", 0).put("nextTranscriptCursor", 0);
        checkOutputAllowed();
        resumeCaptureIfReady();
        if (muted || resumePending) return status().put("segments", new JSONArray()).put("audio", "")
            .put("cursor", 0).put("nextCursor", 0).put("transcriptCursor", 0).put("nextTranscriptCursor", 0)
            .put("level", 0).put("speechActive", false).put("finished", false);
        ensureTranscription();
        String currentCaptureId = captureId, currentTranscriptId = transcriptId;
        boolean newCapture = !currentCaptureId.equals(polledCaptureId)
            || (args.has("captureId") && !currentCaptureId.equals(args.optString("captureId")));
        long cursor = newCapture ? 0 : args.optLong("cursor", 0);
        polledCaptureId = currentCaptureId;
        AudioCapture.FrameRead frames = capture.readFrames(currentCaptureId, OWNER, cursor, 32768);
        JSONObject metadata = frames.metadata;
        double rms = VoicePcm.level(frames.data);
        long position = metadata.getLong("nextCursor") * 1000L / (AudioCapture.SAMPLE_RATE * 2L);

        JSONObject result = status().put("audio", Base64.encodeToString(frames.data, Base64.NO_WRAP))
            .put("sampleRate", AudioCapture.SAMPLE_RATE).put("channels", 1).put("encoding", "pcm_s16le")
            .put("cursor", metadata.getLong("cursor")).put("nextCursor", metadata.getLong("nextCursor"))
            .put("discontinuity", metadata.optBoolean("discontinuity")).put("ended", metadata.optBoolean("ended"))
            .put("rms", rms).put("level", Math.min(1, rms * 8)).put("speechActive", false).put("lastSpeechMs", lastSpeechMs);
        int transcriptCursor = newCapture || (args.has("transcriptId") && !currentTranscriptId.equals(args.optString("transcriptId")))
            ? 0 : args.optInt("transcriptCursor", 0);
        result.put("segments", new JSONArray()).put("transcriptCursor", transcriptCursor).put("nextTranscriptCursor", transcriptCursor);
        if (!currentTranscriptId.isEmpty()) {
            JSONObject text = transcription.request(new JSONObject().put("operation", "transcript")
                .put("transcriptId", currentTranscriptId).put("cursor", transcriptCursor), OWNER);
            result.put("segments", text.getJSONArray("segments")).put("transcriptId", currentTranscriptId)
                .put("nextTranscriptCursor", text.getInt("nextCursor")).put("transcriptState", text.getString("state"))
                .put("finished", text.optBoolean("finished")).put("speechActive", text.optBoolean("speechActive"));
            if (text.optBoolean("speechActive")) { lastSpeechMs = position; result.put("lastSpeechMs", lastSpeechMs); }
            if (text.optString("state").equals("failed")) {
                error = text.optString("error", io.droyagent.app.NativeLocale.text("语音识别已中断", "Speech recognition was interrupted")); stop("transcription_failed");
                result.put("state", state).put("error", error);
            }
        }
        if (metadata.optBoolean("ended") && active()) {
            String terminal = capture.status(currentCaptureId, OWNER).optString("state");
            stop(terminal.equals("completed") ? "user_stop" : "capture_interrupted");
            result.put("state", state).put("error", error.isEmpty() ? JSONObject.NULL : error);
        }
        return result;
    }

    private JSONObject status() throws Exception {
        JSONObject result = new JSONObject().put("state", state).put("mode", mode).put("sessionId", sessionId)
            .put("captureId", captureId).put("transcriptId", transcriptId).put("muted", muted)
            .put("speaking", output.speaking()).put("level", 0).put("sampleRate", AudioCapture.SAMPLE_RATE)
            .put("channels", 1).put("encoding", "pcm_s16le").put("capturePositionMs", 0);
        if (active() && !captureId.isEmpty()) {
            JSONObject current = capture.status(captureId, OWNER);
            if (!muted && !resumePending) {
                String captureState = current.getString("state");
                if (Set.of("completed", "interrupted", "failed").contains(captureState)) {
                    if (!captureState.equals("completed") && error.isEmpty()) error = current.optString("message", io.droyagent.app.NativeLocale.text("麦克风采集已中断", "Microphone capture was interrupted"));
                    stop(captureState.equals("completed") ? "user_stop" : "capture_interrupted");
                } else state = captureState;
            }
            result.put("state", muted ? "recording" : resumePending ? "starting" : state).put("captureState", current.getString("state"))
                .put("capturePositionMs", current.getLong("nextCursor") * 1000L / (AudioCapture.SAMPLE_RATE * 2L))
                .put("needsPermission", current.opt("needsPermission")).put("signal", current.optString("signal"))
                .put("message", current.optString("signal").equals("system_silenced")
                    ? io.droyagent.app.NativeLocale.text("麦克风正被系统占用", "The system is using the microphone") : current.optString("message"));
        }
        result.put("captureId", captureId).put("transcriptId", transcriptId);
        String outputError = output.error();
        result.put("speechError", outputError.isEmpty() ? JSONObject.NULL : outputError);
        result.put("error", !error.isEmpty() ? error : !outputError.isEmpty() ? outputError : JSONObject.NULL);
        return result;
    }

    private synchronized JSONObject stop(String reason) throws Exception {
        Exception failure = null;
        if (!transcriptId.isEmpty()) {
            try {
                transcription.request(new JSONObject().put("operation", "stop_transcription").put("transcriptId", transcriptId), OWNER);
                transcriptId = "";
            }
            catch (Exception stopped) { failure = stopped; }
        }
        try {
            if (!captureId.isEmpty()) {
                capture.stop(captureId, OWNER);
                // AudioCapture owns asynchronous finalization and prevents a new
                // capture until release. An ended conversation no longer owns its ID.
                captureId = ""; polledCaptureId = "";
            }
        }
        catch (Exception stopped) { if (failure == null) failure = stopped; }
        finally {
            AudioCaptureService.setCameraActive(context, false);
            output.endCommunication(); resumePending = false; muted = false; state = reason.equals("user_stop") ? "ended" : "interrupted";
        }
        if (failure != null) error = failure.getMessage() == null ? io.droyagent.app.NativeLocale.text("语音停止未完成", "Speech could not finish stopping") : failure.getMessage();
        else if (!reason.equals("user_stop") && error.isEmpty()) error = io.droyagent.app.NativeLocale.text("语音对话已中断", "Voice chat was interrupted");
        RuntimeService.refreshBackgroundPolicy();
        return status();
    }

    private synchronized void stopForPolicy(String reason) {
        try { stop(reason); }
        catch (Exception failure) { android.util.Log.w("DroyVoice", "Cannot stop voice resources", failure); }
    }

    private JSONObject dispose() throws Exception {
        try { return stop("user_stop"); }
        finally {
            preferences.unregisterOnSharedPreferenceChangeListener(preferencesChanged);
            try { output.close(); }
            finally { synchronized (VoiceMedia.class) { if (instance == this) instance = null; } }
        }
    }

    private synchronized boolean active() {
        return Set.of("awaiting_user", "starting", "recording", "finalizing").contains(state);
    }

    private void requireActive() { if (!active()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("语音对话尚未开始", "Voice chat has not started")); }
    private void checkOutputAllowed() {
        if (ProductPermissions.stopped(context) || BackgroundRuntime.explicitlyStopped(context))
            throw new SecurityException(io.droyagent.app.NativeLocale.text("Droy 已暂停，请恢复后继续", "Droy is paused. Please resume to continue"));
    }
}
