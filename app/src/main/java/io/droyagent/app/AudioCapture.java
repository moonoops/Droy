package io.droyagent.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Owns persistent capture independently of short-lived engine tool calls. */
final class AudioCapture {
    static final int SAMPLE_RATE = 48000;
    static final int CHANNELS = 1;
    static final int BYTES_PER_SAMPLE = 2;
    static final int FRAME_BYTES = SAMPLE_RATE / 50 * BYTES_PER_SAMPLE;
    private static final Set<String> TRANSIENT_OWNERS = Set.of("droy-voice", "droy-voice-wake",
        "droy-voice-enrollment", "droy-dictation", "droy-input-dictation");
    private static AudioCapture instance;
    static synchronized AudioCapture get(Context context) {
        if (instance == null) instance = new AudioCapture(context.getApplicationContext());
        return instance;
    }

    static boolean isTransientOwner(String owner) { return TRANSIENT_OWNERS.contains(owner); }

    static final class FrameRead {
        final byte[] data;
        final JSONObject metadata;
        FrameRead(byte[] data, JSONObject metadata) { this.data = data; this.metadata = metadata; }
    }

    private static final class Session {
        final String id, owner, requestId, source, scope, packageName;
        final boolean saveFile;
        final long limitMs, createdAt;
        volatile PcmFrameBuffer frames;
        volatile String state = "awaiting_user", signal = "unknown", reason = "";
        volatile String message = io.droyagent.app.NativeLocale.text("需要在手机上完成录音授权", "Audio capture must be authorized on your phone");
        volatile String metadataError = "";
        volatile String deviceName = "";
        volatile int deviceId, deviceType;
        volatile boolean stopRequested, fileReady;
        volatile long startElapsed, bytes;
        volatile AudioRecord recorder;
        volatile boolean muted;
        boolean communication;
        volatile MediaProjection projection;
        volatile AudioCaptureService service;
        volatile int serviceStartId;
        Session(String id, String owner, String requestId, String source, String scope,
                String packageName, boolean saveFile, long limitMs, long createdAt) {
            this.id = id; this.owner = owner; this.requestId = requestId; this.source = source;
            this.scope = scope; this.packageName = packageName; this.saveFile = saveFile;
            this.limitMs = limitMs; this.createdAt = createdAt;
        }
        boolean active() { return state.equals("awaiting_user") || state.equals("starting") || state.equals("recording") || state.equals("finalizing"); }
    }

    private final Context context;
    private final File directory;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences settings;
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>();
    private Session active;
    private boolean dictationActive;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferencesChanged = (prefs, key) -> {
        Session current;
        synchronized (this) { current = active; }
        if (current != null && !allowed(current)) requestStop(current, "permission_revoked");
    };

    private AudioCapture(Context context) {
        this.context = context;
        directory = new File(context.getFilesDir(), "audio-captures");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("无法创建音频目录", "Unable to create audio directory"));
        settings = ProductPermissions.preferences(context);
        recover();
        settings.registerOnSharedPreferenceChangeListener(preferencesChanged);
    }

    synchronized JSONObject start(JSONObject args, String owner, String requestId) throws Exception {
        return start(args, owner, requestId, false);
    }

    synchronized JSONObject startVoice(JSONObject args, String owner, String requestId) throws Exception {
        return start(args, owner, requestId, true);
    }

    private JSONObject start(JSONObject args, String owner, String requestId, boolean communication) throws Exception {
        requireIdentity(owner, requestId);
        for (Session existing : sessions.values())
            if (existing.owner.equals(owner) && existing.requestId.equals(requestId)) return describe(existing);
        String source = args.optString("source", "microphone");
        if (!source.equals("microphone") && !source.equals("playback")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("音源必须为 microphone 或 playback", "Audio source must be microphone or playback"));
        String scope = args.optString("scope", "app"), packageName = args.optString("packageName");
        if (source.equals("playback")) {
            if (!scope.equals("app") && !scope.equals("system")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("内录范围必须为 app 或 system", "Playback capture scope must be app or system"));
            if (scope.equals("app") && packageName.isBlank()) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("指定应用内录需要 packageName", "Capturing a specific app requires packageName"));
            if (!packageName.isBlank()) context.getPackageManager().getApplicationInfo(packageName, 0);
        } else { scope = "microphone"; packageName = ""; }
        if (args.has("saveFile") && !(args.get("saveFile") instanceof Boolean)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("saveFile 必须为布尔值", "saveFile must be a boolean"));
        long duration = args.has("durationMs") ? exactLong(args.get("durationMs"), 0, 86_400_000) : 0;
        Session session = new Session(UUID.randomUUID().toString(), owner, requestId, source, scope,
                packageName, !TRANSIENT_OWNERS.contains(owner) && args.optBoolean("saveFile", true), duration, System.currentTimeMillis());
        session.communication = communication;
        requireAllowed(session);
        if (dictationActive) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("输入框正在听写，请先停止听写", "The input field is dictating. Please stop dictation first"));
        if (active != null && active.active()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("已有采集会话运行，请先查询或停止：", "A capture session is already active. Please query or stop it first: ") + active.id);
        session.frames = new PcmFrameBuffer(SAMPLE_RATE * 2 * 8, 2);
        sessions.put(session.id, session); active = session;
        try { persist(session); }
        catch (RuntimeException failure) {
            sessions.remove(session.id); active = null; session.frames = null;
            captureReleased(session.owner);
            throw failure;
        }
        if (source.equals("microphone") && hasMicrophonePermission() && BackgroundRuntime.visible()) launch(context, session, 0, null);
        return describe(session);
    }

    synchronized JSONObject status(String captureId, String owner) throws Exception {
        return describe(find(captureId, owner));
    }

    synchronized JSONObject stop(String captureId, String owner) throws Exception {
        Session session = find(captureId, owner); requestStop(session, "user_stop"); return describe(session);
    }

    synchronized JSONObject mute(String captureId, String owner, boolean muted) throws Exception {
        Session session = find(captureId, owner); requireAllowed(session);
        session.muted = muted;
        return describe(session);
    }

    synchronized void cancelStart(String owner, String requestId) {
        for (Session session : sessions.values())
            if (session.owner.equals(owner) && session.requestId.equals(requestId)) requestStop(session, "start_cancelled");
    }

    synchronized JSONObject stopFromUi(String captureId) throws Exception {
        Session session = lookup(captureId); requestStop(session, "user_stop"); return describe(session);
    }

    synchronized void stopAll(String reason) {
        if (active != null) requestStop(active, reason);
    }

    synchronized JSONObject uiStatus() throws Exception {
        JSONArray recent = new JSONArray();
        Session[] values = sessions.values().toArray(new Session[0]);
        for (int i = values.length - 1; i >= 0 && recent.length() < 20; i--)
            if (!values[i].active() && !TRANSIENT_OWNERS.contains(values[i].owner)) recent.put(describe(values[i]));
        return new JSONObject().put("active", active != null && active.active() ? describe(active) : JSONObject.NULL).put("recent", recent);
    }

    synchronized JSONObject pendingAuthorization() throws Exception {
        return active != null && active.state.equals("awaiting_user") ? describe(active) : null;
    }

    synchronized void authorize(Activity activity, String captureId) throws Exception {
        Session session = pending(captureId); requireAllowed(session);
        if (!session.source.equals("microphone")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("此会话需要内部音频授权", "This session requires playback audio authorization"));
        if (!hasMicrophonePermission()) throw new SecurityException(io.droyagent.app.NativeLocale.text("尚未授予麦克风权限", "Microphone permission has not been granted"));
        launch(activity, session, 0, null);
    }

    synchronized void authorizeProjection(Activity activity, String captureId, int resultCode, Intent data) throws Exception {
        Session session = pending(captureId); requireAllowed(session);
        if (!session.source.equals("playback")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("此会话不是内部音频采集", "This is not a playback audio capture session"));
        if (resultCode != Activity.RESULT_OK || data == null) { denyAuthorization(captureId); return; }
        if (!hasMicrophonePermission()) throw new SecurityException(io.droyagent.app.NativeLocale.text("尚未授予录音权限", "Recording permission has not been granted"));
        launch(activity, session, resultCode, data);
    }

    synchronized void denyAuthorization(String captureId) throws Exception {
        Session session = lookup(captureId);
        if (session.state.equals("awaiting_user")) requestStop(session, "authorization_denied");
    }

    synchronized File file(String captureId, String owner) throws Exception { return completedFile(find(captureId, owner)); }
    synchronized File fileForUi(String captureId) throws Exception { return completedFile(lookup(captureId)); }
    synchronized String ownerForUi(String captureId) throws Exception { return lookup(captureId).owner; }
    synchronized void claimDictation() {
        if (ProductPermissions.stopped(context) || !ProductPermissions.allowed(settings, "allowAudioMicrophone"))
            throw new SecurityException(io.droyagent.app.NativeLocale.text("麦克风采集已暂停或关闭", "Microphone capture is paused or disabled"));
        if (active != null && active.active()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("已有音频采集，请先停止后听写", "Audio capture is active. Please stop it before dictating"));
        dictationActive = true;
    }
    synchronized void releaseDictation() {
        boolean wasActive = dictationActive;
        dictationActive = false;
        if (wasActive) captureReleased("droy-dictation");
    }
    synchronized boolean dictationActive() { return dictationActive; }
    synchronized JSONObject deleteFromUi(String captureId) throws Exception {
        Session session = lookup(captureId);
        if (session.active()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请先停止采集再删除", "Please stop capture before deleting"));
        deleteFiles(session);
        sessions.remove(session.id);
        return uiStatus();
    }

    synchronized FrameRead readFrames(String captureId, String owner, long cursor, int maxBytes) throws Exception {
        Session session = find(captureId, owner); requireAllowed(session);
        if (maxBytes < 2 || maxBytes > 262144 || maxBytes % 2 != 0) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("PCM 读取上限无效", "Invalid PCM read limit"));
        PcmFrameBuffer window = session.frames;
        if (cursor < 0 || cursor % 2 != 0 || cursor > written(session)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("PCM 读取位置无效", "Invalid PCM read position"));
        PcmFrameBuffer.Read read = window == null
            ? new PcmFrameBuffer.Read(new byte[0], session.bytes, cursor != session.bytes)
            : window.read(cursor, maxBytes);
        boolean ended = !session.active() || session.state.equals("finalizing");
        JSONObject metadata = format().put("captureId", session.id).put("state", session.state)
            .put("cursor", read.cursor).put("startCursor", read.cursor).put("nextCursor", read.nextCursor)
            .put("startSample", read.cursor / 2).put("endSample", read.nextCursor / 2)
            .put("discontinuity", read.discontinuity).put("ended", ended && read.nextCursor >= written(session))
            .put("streamReleased", window == null)
            .put("timeBase", "capture_samples").put("startTimeMs", read.cursor * 1000 / (SAMPLE_RATE * 2L));
        return new FrameRead(read.data, metadata);
    }

    synchronized String sourceForService(String id) throws Exception { return lookup(id).source; }

    synchronized void serviceStarted(AudioCaptureService service, int startId, String id, int resultCode, Intent data) throws Exception {
        Session session = lookup(id);
        if (session != active || !session.state.equals("starting") || session.stopRequested) { service.finishCapture(id); return; }
        session.service = service; session.serviceStartId = startId;
        new Thread(() -> capture(session, resultCode, data), "droy-audio-capture").start();
    }

    synchronized void serviceFailed(String id, String reason) {
        Session session = sessions.get(id);
        if (session == null || !session.active()) return;
        session.reason = reason; session.message = io.droyagent.app.NativeLocale.text("录音服务启动失败", "Recording service failed to start"); session.state = "failed"; session.stopRequested = true;
        persistForCleanup(session); if (active == session) active = null;
        trimBuffers();
        captureReleased(session.owner);
    }

    synchronized void serviceDestroyed(AudioCaptureService service) {
        if (active != null && active.service == service && active.active()) requestStop(active, "service_destroyed");
    }

    private void launch(Context caller, Session session, int resultCode, Intent data) throws Exception {
        session.state = "starting"; session.message = io.droyagent.app.NativeLocale.text("正在启动录音", "Starting recording");
        try { persist(session); }
        catch (RuntimeException failure) {
            session.state = "failed"; session.stopRequested = true; session.reason = "metadata_write_failed";
            session.message = io.droyagent.app.NativeLocale.text("录音状态无法保存，采集未开始", "Recording state could not be saved. Capture did not start"); session.metadataError = failure.getMessage();
            if (active == session) active = null;
            trimBuffers(); captureReleased(session.owner); throw failure;
        }
        Intent intent = new Intent(caller, AudioCaptureService.class).putExtra("captureId", session.id)
            .putExtra("captureOwner", session.owner)
            .putExtra("projectionResult", resultCode);
        if (data != null) intent.putExtra("projectionData", data);
        try { caller.startForegroundService(intent); }
        catch (SecurityException | IllegalStateException denied) {
            session.state = "awaiting_user"; session.reason = "foreground_start_required";
            session.message = io.droyagent.app.NativeLocale.text("请在手机上打开录音授权页后开始", "Open the recording authorization page on your phone before starting"); persistForCleanup(session);
        }
    }

    private void capture(Session session, int projectionResult, Intent projectionData) {
        AudioRecord recorder = null;
        android.media.audiofx.AcousticEchoCanceler echoCanceler = null;
        AudioManager.AudioRecordingCallback recordingCallback = null;
        boolean callbackRegistered = false;
        MediaProjection projection = null;
        QueuedWavWriter writer = null;
        try {
            requireAllowed(session);
            if (session.stopRequested) return;
            AudioFormat audioFormat = new AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException(io.droyagent.app.NativeLocale.text("设备不支持 48 kHz 单声道 PCM16 采集", "The device does not support 48 kHz mono PCM16 capture"));
            AudioRecord.Builder builder = new AudioRecord.Builder().setAudioFormat(audioFormat).setBufferSizeInBytes(Math.max(minimum, FRAME_BYTES * 8));
            if (session.source.equals("playback")) {
                if (projectionData == null) throw new SecurityException(io.droyagent.app.NativeLocale.text("内部音频授权已失效", "Playback audio authorization has expired"));
                projection = context.getSystemService(MediaProjectionManager.class).getMediaProjection(projectionResult, projectionData);
                session.projection = projection;
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() { requestStop(session, "projection_revoked"); }
                }, main);
                AudioPlaybackCaptureConfiguration.Builder config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN);
                if (session.scope.equals("app")) config.addMatchingUid(context.getPackageManager().getApplicationInfo(session.packageName, 0).uid);
                else config.excludeUid(context.getApplicationInfo().uid);
                builder.setAudioPlaybackCaptureConfig(config.build());
            } else builder.setAudioSource(session.communication ? MediaRecorder.AudioSource.VOICE_COMMUNICATION : MediaRecorder.AudioSource.MIC);
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException(io.droyagent.app.NativeLocale.text("系统麦克风权限已撤销", "System microphone permission was revoked"));
            recorder = builder.build(); session.recorder = recorder;
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException(io.droyagent.app.NativeLocale.text("音频输入初始化失败", "Audio input initialization failed"));
            final int recordingSessionId = recorder.getAudioSessionId();
            if (session.communication && android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                echoCanceler = android.media.audiofx.AcousticEchoCanceler.create(recordingSessionId);
                if (echoCanceler != null) echoCanceler.setEnabled(true);
            }
            recordingCallback = new AudioManager.AudioRecordingCallback() {
                @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                    for (AudioRecordingConfiguration config : configs)
                        if (config.getClientAudioSessionId() == recordingSessionId) {
                            session.signal = config.isClientSilenced() ? "system_silenced" : "unknown";
                            device(session, config.getAudioDevice());
                        }
                }
            };
            recorder.registerAudioRecordingCallback(context.getMainExecutor(), recordingCallback);
            callbackRegistered = true;
            if (session.saveFile) writer = new QueuedWavWriter(partialFile(session), SAMPLE_RATE, CHANNELS);
            if (session.stopRequested) return;
            recorder.startRecording();
            AudioRecordingConfiguration initial = recorder.getActiveRecordingConfiguration();
            if (initial != null && initial.isClientSilenced()) session.signal = "system_silenced";
            device(session, recorder.getRoutedDevice());
            synchronized (this) {
                if (!session.stopRequested) { session.state = "recording"; session.startElapsed = SystemClock.elapsedRealtime(); session.message = io.droyagent.app.NativeLocale.text("正在采集音频", "Capturing audio"); persist(session); }
            }
            byte[] block = new byte[FRAME_BYTES];
            while (!session.stopRequested) {
                requireAllowed(session);
                int count = recorder.read(block, 0, block.length, AudioRecord.READ_BLOCKING);
                if (session.stopRequested) break;
                if (count < 0) throw new IOException(io.droyagent.app.NativeLocale.text("音频读取失败：", "Audio read failed: ") + count);
                if (count == 0) continue;
                if (count % 2 != 0) throw new IOException(io.droyagent.app.NativeLocale.text("音频样本未对齐", "Audio samples are not aligned"));
                if (session.muted) Arrays.fill(block, 0, count, (byte) 0);
                session.frames.append(block, count); session.bytes += count;
                if (!session.signal.equals("system_silenced")) {
                    boolean nonzero = false;
                    for (int i = 0; i < count; i++) if (block[i] != 0) { nonzero = true; break; }
                    session.signal = nonzero ? "present" : "silence";
                }
                if (writer != null && !writer.append(Arrays.copyOf(block, count))) throw new IOException(io.droyagent.app.NativeLocale.text("录音文件写入跟不上采集或已失败", "Recording file writes cannot keep up with capture or have failed"));
                if (session.limitMs > 0 && session.bytes * 1000 / (SAMPLE_RATE * 2L) >= session.limitMs)
                    requestStop(session, "duration_limit");
            }
        } catch (Exception failure) {
            synchronized (this) {
                if (session.reason.isEmpty()) session.reason = failure instanceof SecurityException ? "permission_revoked" : "capture_error";
                session.message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            }
        } finally {
            session.stopRequested = true;
            if (echoCanceler != null) try { echoCanceler.release(); }
                catch (RuntimeException failure) { cleanupFailure(session, "echo_release_failed", failure); }
            if (recorder != null) {
                try {
                    if (callbackRegistered) recorder.unregisterAudioRecordingCallback(recordingCallback);
                    if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop();
                } catch (RuntimeException failure) { cleanupFailure(session, "input_release_failed", failure); }
                finally {
                    try { recorder.release(); } catch (RuntimeException failure) { cleanupFailure(session, "input_release_failed", failure); }
                    session.recorder = null;
                }
            }
            if (projection != null) try { projection.stop(); } catch (RuntimeException failure) { cleanupFailure(session, "projection_release_failed", failure); }
            synchronized (this) { session.state = "finalizing"; persistForCleanup(session); }
            if (writer == null) finish(session, null);
            else writer.finish().whenComplete((unused, failure) -> finish(session, failure));
        }
    }

    private synchronized void finish(Session session, Throwable fileFailure) {
        try {
            if (session.saveFile && partialFile(session).isFile()) {
                PcmWavFile.recover(partialFile(session), SAMPLE_RATE, CHANNELS);
                Files.move(partialFile(session).toPath(), outputFile(session).toPath(), StandardCopyOption.REPLACE_EXISTING);
                session.fileReady = true;
            }
            if (fileFailure != null) { session.reason = "file_write_failed"; session.message = io.droyagent.app.NativeLocale.text("录音文件未完整写入，已保留可用部分", "The recording file was not fully written. The usable part has been kept"); }
        } catch (IOException failure) {
            session.reason = "file_finalize_failed"; session.message = io.droyagent.app.NativeLocale.text("文件封装失败，原始录音保留在本机待恢复", "File finalization failed. The original recording remains on this device for recovery");
        }
        if (session.reason.isEmpty()) session.reason = "user_stop";
        boolean normal = session.reason.equals("user_stop") || session.reason.equals("duration_limit");
        session.state = normal ? "completed" : session.bytes > 0 ? "interrupted" : "failed";
        if (normal) session.message = session.saveFile ? io.droyagent.app.NativeLocale.text("录音已停止", "Recording stopped") : io.droyagent.app.NativeLocale.text("音频采集已停止", "Audio capture stopped");
        persistForCleanup(session); if (active == session) active = null;
        trimBuffers();
        captureReleased(session.owner);
        AudioCaptureService service = session.service;
        if (service != null) main.post(() -> service.finishCapture(session.id));
    }

    private synchronized void requestStop(Session session, String reason) {
        if (!session.active() || session.stopRequested) return;
        session.reason = reason; session.stopRequested = true;
        if (session.state.equals("awaiting_user") || (session.state.equals("starting") && session.service == null)) {
            session.state = reason.equals("user_stop") ? "completed" : "interrupted";
            session.message = io.droyagent.app.NativeLocale.text("未开始采集", "Capture has not started"); persistForCleanup(session); if (active == session) active = null; trimBuffers();
            captureReleased(session.owner); return;
        }
        AudioRecord recorder = session.recorder;
        if (recorder != null) {
            try { recorder.stop(); } catch (IllegalStateException ignored) { /* Initialization may still be in progress. */ }
        }
    }

    private void captureReleased(String owner) {
        if (owner.equals("droy-voice-wake") || owner.equals("droy-voice-enrollment")) return;
        // Run after releasing this monitor, so wake can claim the now-free input.
        main.post(() -> VoiceWake.captureReleasedIfCreated(owner));
    }

    private synchronized Session pending(String id) throws Exception {
        Session session = lookup(id);
        if (session != active || !session.state.equals("awaiting_user")) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("该录音不再等待授权", "This recording is no longer awaiting authorization"));
        return session;
    }

    private Session find(String id, String owner) throws Exception {
        if (owner == null || owner.isBlank()) throw new SecurityException(io.droyagent.app.NativeLocale.text("缺少录音调用身份", "Recording caller identity is missing"));
        if (id == null || id.isBlank()) {
            Session last = null;
            for (Session session : sessions.values()) if (session.owner.equals(owner)) last = session;
            if (last == null) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("当前对话没有录音会话", "The current conversation has no recording session"));
            return last;
        }
        Session session = lookup(id);
        if (!session.owner.equals(owner)) throw new SecurityException(io.droyagent.app.NativeLocale.text("不能访问其他对话的录音", "Recordings from other conversations cannot be accessed"));
        return session;
    }

    private Session lookup(String id) throws Exception {
        if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("录音标识无效", "Invalid recording ID"));
        Session session = sessions.get(id);
        if (session == null) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("录音会话不存在", "Recording session does not exist"));
        return session;
    }

    private boolean allowed(Session session) {
        return !ProductPermissions.stopped(context) && ProductPermissions.allowed(settings,
                session.source.equals("microphone") ? "allowAudioMicrophone" : "allowAudioPlayback");
    }
    private void requireAllowed(Session session) { if (!allowed(session)) throw new SecurityException(io.droyagent.app.NativeLocale.text("音频采集已暂停或对应能力已关闭", "Audio capture is paused or its capability is disabled")); }
    private boolean hasMicrophonePermission() { return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }
    private static void requireIdentity(String owner, String requestId) {
        if (owner == null || owner.isBlank() || owner.length() > 512 || requestId == null || requestId.isBlank() || requestId.length() > 512)
            throw new SecurityException(io.droyagent.app.NativeLocale.text("缺少有效录音调用身份", "A valid recording caller identity is missing"));
    }
    private static long exactLong(Object input, long minimum, long maximum) {
        if (!(input instanceof Number)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("录音时长必须是整数毫秒", "Recording duration must be an integer in milliseconds"));
        double value = ((Number) input).doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value) || value < minimum || value > maximum)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("录音时长超出范围", "Recording duration is out of range"));
        return (long) value;
    }

    private JSONObject describe(Session session) throws Exception {
        return format().put("captureId", session.id).put("owner", session.owner).put("requestId", session.requestId)
            .put("source", session.source).put("scope", session.scope).put("packageName", session.packageName)
            .put("state", session.state).put("status", session.state).put("signal", session.signal)
            .put("muted", session.muted)
            .put("deviceId", session.deviceId).put("deviceType", session.deviceType).put("deviceName", session.deviceName)
            .put("createdAt", session.createdAt).put("durationMs", session.bytes * 1000 / (SAMPLE_RATE * 2L))
            .put("bytes", session.bytes).put("saveFile", session.saveFile).put("durationLimitMs", session.limitMs)
            .put("nextCursor", written(session)).put("earliestCursor", session.frames == null ? session.bytes : Math.max(0, written(session) - SAMPLE_RATE * 2L * 8))
            .put("streamBufferRetained", session.frames != null)
            .put("fileReady", session.fileReady).put("filePath", session.fileReady ? outputFile(session).getAbsolutePath() : JSONObject.NULL)
            .put("fileBytes", session.fileReady ? outputFile(session).length() : 0)
            .put("fileDurationMs", session.fileReady ? Math.max(0, outputFile(session).length() - 44) * 1000 / (SAMPLE_RATE * 2L) : 0)
            .put("reason", session.reason).put("message", session.message)
            .put("metadataError", session.metadataError.isEmpty() ? JSONObject.NULL : session.metadataError)
            .put("needsPermission", session.state.equals("awaiting_user") ? session.source.equals("playback") ? "playback" : "microphone" : JSONObject.NULL);
    }
    private static JSONObject format() throws Exception {
        return new JSONObject().put("sampleRate", SAMPLE_RATE).put("channels", CHANNELS).put("encoding", "pcm_s16le").put("bitsPerSample", 16);
    }
    private static void device(Session session, AudioDeviceInfo device) {
        if (device == null) return;
        session.deviceId = device.getId(); session.deviceType = device.getType(); session.deviceName = device.getProductName().toString();
    }
    private File partialFile(Session session) { return new File(directory, session.id + ".wav.partial"); }
    private File outputFile(Session session) { return new File(directory, session.id + ".wav"); }
    private File completedFile(Session session) throws IOException {
        File file = outputFile(session);
        if (!session.fileReady || !file.isFile()) throw new IOException(io.droyagent.app.NativeLocale.text("录音文件尚未就绪或未选择保存", "The recording file is not ready or saving was not selected"));
        return file;
    }

    private static long written(Session session) { return session.frames == null ? session.bytes : session.frames.written(); }

    private void trimBuffers() {
        Session[] values = sessions.values().toArray(new Session[0]);
        int retained = 0, transientRetained = 0;
        for (int i = values.length - 1; i >= 0; i--) {
            Session session = values[i];
            if (session.active()) continue;
            if (session.frames != null && ++retained > 4) session.frames = null;
            // Keep a small terminal window for release/status acknowledgements,
            // rather than retaining one in-memory record for every wake cycle.
            if (TRANSIENT_OWNERS.contains(session.owner) && ++transientRetained > 4) sessions.remove(session.id);
        }
    }

    private void deleteFiles(Session session) throws IOException {
        Files.deleteIfExists(outputFile(session).toPath());
        Files.deleteIfExists(partialFile(session).toPath());
        Files.deleteIfExists(new File(directory, session.id + ".json.tmp").toPath());
        Files.deleteIfExists(new File(directory, session.id + ".json").toPath());
    }

    private void persist(Session session) {
        if (TRANSIENT_OWNERS.contains(session.owner)) return;
        try {
            File temporary = new File(directory, session.id + ".json.tmp"), target = new File(directory, session.id + ".json");
            Files.write(temporary.toPath(), describe(session).toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) { throw new IllegalStateException(io.droyagent.app.NativeLocale.text("录音状态保存失败", "Recording state could not be saved"), failure); }
    }

    private void persistForCleanup(Session session) {
        try { persist(session); }
        catch (RuntimeException failure) {
            session.metadataError = io.droyagent.app.NativeLocale.text("录音状态保存失败", "Recording state could not be saved");
            if (session.reason.isEmpty() || session.reason.equals("user_stop") || session.reason.equals("duration_limit")) {
                session.reason = "metadata_write_failed"; session.message = io.droyagent.app.NativeLocale.text("采集已停止，录音状态未能保存", "Capture stopped, but recording state could not be saved");
                if (!session.state.equals("finalizing")) session.state = "interrupted";
            }
            android.util.Log.w("DroyAudio", "Cannot persist audio session during cleanup", failure);
        }
    }

    private synchronized void cleanupFailure(Session session, String reason, RuntimeException failure) {
        if (session.reason.isEmpty() || session.reason.equals("user_stop") || session.reason.equals("duration_limit")) session.reason = reason;
        session.message = io.droyagent.app.NativeLocale.text("音频资源释放发生错误：", "Error while releasing audio resources: ") + failure.getClass().getSimpleName();
        android.util.Log.w("DroyAudio", reason, failure);
    }

    private void recover() {
        File[] metadata = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (metadata == null) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("无法读取音频目录", "Unable to read audio directory"));
        Arrays.sort(metadata, Comparator.comparingLong(File::lastModified));
        for (File path : metadata) {
            try {
                JSONObject saved = new JSONObject(new String(Files.readAllBytes(path.toPath()), StandardCharsets.UTF_8));
                String id = saved.getString("captureId");
                if (!path.getName().equals(id + ".json") || !id.matches("[a-f0-9-]{36}")) continue;
                Session session = new Session(id, saved.getString("owner"), saved.getString("requestId"), saved.getString("source"),
                    saved.getString("scope"), saved.optString("packageName"), saved.optBoolean("saveFile"),
                    saved.optLong("durationLimitMs"), saved.getLong("createdAt"));
                // Only these exact internal owners identify disposable legacy
                // voice data. Unidentified files and manual recordings stay intact.
                if (TRANSIENT_OWNERS.contains(session.owner)) { deleteFiles(session); continue; }
                session.state = saved.getString("state"); session.reason = saved.optString("reason");
                session.message = saved.optString("message"); session.bytes = saved.optLong("bytes");
                session.fileReady = outputFile(session).isFile();
                if (session.active() || partialFile(session).isFile()) {
                    if (session.fileReady) session.bytes = Math.max(0, outputFile(session).length() - 44);
                    if (partialFile(session).isFile()) {
                        session.bytes = PcmWavFile.recover(partialFile(session), SAMPLE_RATE, CHANNELS);
                        Files.move(partialFile(session).toPath(), outputFile(session).toPath(), StandardCopyOption.REPLACE_EXISTING);
                        session.fileReady = true;
                    }
                    session.state = "interrupted"; session.reason = "process_interrupted";
                    session.message = io.droyagent.app.NativeLocale.text("上次采集已中断，保留可恢复的录音", "The previous capture was interrupted. Recoverable audio has been kept");
                }
                sessions.put(id, session); persist(session);
            } catch (Exception damaged) {
                // Keep damaged metadata and PCM on disk for diagnosis; never reopen the microphone on recovery.
                android.util.Log.w("DroyAudio", "Cannot recover audio session metadata: " + path.getName(), damaged);
            }
        }
    }
}
