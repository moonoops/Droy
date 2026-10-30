package io.droyagent.app;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.OnlineStream;
import org.json.JSONObject;
import java.io.IOException;
import java.util.UUID;

/** Local keyword listening with an explicit screen/app timing policy. */
final class VoiceWake {
    interface WakeListener { void onWake(long eventId, String target); }
    private static final String WAKE_OWNER = "droy-voice-wake";
    private static VoiceWake instance;
    private static volatile WakeListener listener;

    static synchronized VoiceWake get(Context context) {
        if (instance == null) instance = new VoiceWake(context.getApplicationContext());
        return instance;
    }

    static void setWakeListener(WakeListener callback) { listener = callback; }

    static void stopIfCreated(String reason) {
        VoiceWake current;
        synchronized (VoiceWake.class) { current = instance; }
        if (current == null) return;
        synchronized (current) { current.pausedForVoice = true; current.voiceClaims.endCurrent(); current.clearPendingEvent(); }
        current.models.cancel(); current.stopAsync(reason);
    }

    static boolean hasActiveWork(Context ignored) {
        VoiceWake current;
        synchronized (VoiceWake.class) { current = instance; }
        return current != null && (current.active != null || current.models.preparing());
    }
    static void retireIfCreated(String owner) {
        VoiceWake current;
        synchronized (VoiceWake.class) { current = instance; }
        if (current != null) current.voiceClaims.retire(owner);
    }
    static void releaseIfCreated(VoiceSessionOwnership.Lease claim) {
        VoiceWake current;
        synchronized (VoiceWake.class) { current = instance; }
        if (current != null) current.releaseVoice(claim);
    }

    static void captureReleasedIfCreated(String owner) {
        if (WAKE_OWNER.equals(owner) || "droy-voice-enrollment".equals(owner)) return;
        VoiceWake current;
        synchronized (VoiceWake.class) { current = instance; }
        if (current == null || current.pluginSuspended || current.voiceClaims.active()
            || BackgroundRuntime.explicitlyStopped(current.context) || ProductPermissions.stopped(current.context)
            || VoiceMedia.hasActiveWork(current.context)) return;
        current.resumeIfEnabled();
    }

    static boolean stopFromNotification(String owner) {
        VoiceWake current;
        synchronized (VoiceWake.class) { current = instance; }
        if (current == null || !WAKE_OWNER.equals(owner)) return false;
        current.preferences.edit().putBoolean("enabled", false).apply();
        current.clearPendingEvent(); current.pausedForVoice = true; current.stopAsync("disabled");
        return true;
    }

    static boolean screenAvailable(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        return power != null && power.isInteractive() && keyguard != null && !keyguard.isKeyguardLocked();
    }

    static boolean timingAllows(String timing, boolean screenAvailable, boolean visible) {
        return screenAvailable && (!timing.equals("foreground") || visible);
    }

    private String timing() { return preferences.getString("timing", "screen_on"); }
    private boolean listeningAllowed() { return timingAllows(timing(), screenAvailable(context), BackgroundRuntime.visible()); }
    private String timingMessage() { return timing().equals("foreground") ? io.droyagent.app.NativeLocale.text("回到 Droy 并解锁后继续监听", "Return to Droy and unlock to continue listening") : io.droyagent.app.NativeLocale.text("亮屏并解锁后继续监听", "Turn on the screen and unlock to continue listening"); }
    private String[] effectiveKeywords() {
        return new String[]{preferences.getString("chinese", "你好Droy"), preferences.getString("english", "Hey Dory")};
    }
    private String encodedKeywords() throws Exception {
        String[] values = effectiveKeywords();
        return models.keywords(values[0], values[1]);
    }

    static void visibilityChangedIfCreated() {
        VoiceWake current;
        synchronized (VoiceWake.class) { current = instance; }
        if (current != null) current.main.post(current::availabilityChanged);
    }

    private void availabilityChanged() {
        if (!listeningAllowed()) {
            synchronized (this) { ++screenEpoch; clearPendingEvent(); }
            stopAsync("screen");
            if (preferences.getBoolean("enabled", false)) { state = "paused"; message = timingMessage(); }
            return;
        }
        new Thread(() -> {
            try {
                Run run = active;
                if (run != null && run.stop && run.stopReason.equals("screen")) stopAndWait(run, "screen");
                resumeIfEnabled();
            } catch (Exception failure) { fail(failure); }
        }, "droy-wake-availability").start();
    }

    private static final class Run {
        final long screenEpoch;
        Run(long screenEpoch) { this.screenEpoch = screenEpoch; }
        volatile Thread thread;
        volatile boolean stop;
        volatile String captureId = "", stopReason = "";
    }

    private final Context context;
    private final SharedPreferences preferences;
    private final VoiceModels models;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Run active;
    private volatile boolean pausedForVoice, pluginSuspended = true;
    private final VoiceSessionOwnership voiceClaims = new VoiceSessionOwnership();
    private volatile String state = "off", message = "", error;
    private volatile float level;
    private long sequence;
    private volatile long pendingEventId, screenEpoch;
    private volatile String lastKeyword = "";

    private final BroadcastReceiver screenChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context unused, Intent intent) { availabilityChanged(); }
    };

    private VoiceWake(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("droy-voice-wake", Context.MODE_PRIVATE);
        models = new VoiceModels(context);
        // Freeze an existing name binding once; later pet renames no longer change wake words.
        if (preferences.getBoolean("useCompanionName", false)) {
            String name = PetOverlay.name(context).strip();
            preferences.edit().putString("chinese", "你好" + name).putString("english", "Hi " + name)
                .remove("useCompanionName").apply();
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(screenChanged, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(screenChanged, filter);
        if (preferences.getBoolean("enabled", false)) { state = "paused"; message = io.droyagent.app.NativeLocale.text("打开语音设置继续监听", "Open voice settings to continue listening"); }
    }

    JSONObject request(JSONObject args) throws Exception {
        String operation = args.getString("operation");
        String owner = VoiceSessionOwnership.owner(args.has("voiceOperationId") ? args.get("voiceOperationId") : null);
        if (operation.startsWith("wake.enroll_")) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("声纹功能暂未开放", "Voiceprint is not available yet"));
        switch (operation) {
            case "wake.status": return status();
            case "wake.prepare":
                checkPreparationAllowed(); prepareModels(); return status();
            case "wake.configure": return configure(args);
            case "wake.consume": {
                if (VoiceMedia.retiredOperation(owner)) throw new VoiceSessionOwnership.Failure("VOICE_OWNER_MISMATCH");
                boolean consumed = consumeWakeEvent(args.getLong("eventId"), owner);
                return status().put("consumed", consumed).put("target", "standard");
            }
            case "wake.claim": claimForVoice(owner); return status();
            case "wake.release": releaseVoice(owner); return status();
            case "wake.pause": pauseForVoice(); return status();
            case "wake.suspend":
                pluginSuspended = true; clearPendingEvent(); models.cancel();
                stopAndWait(active, "suspended"); state = "paused"; message = io.droyagent.app.NativeLocale.text("Droy Voice 已关闭", "Droy Voice is disabled"); return status();
            case "wake.resume": pluginSuspended = false; resumeIfEnabled(); return status();
            default: throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("未知唤醒操作", "Unknown wake-up operation"));
        }
    }

    private JSONObject configure(JSONObject args) throws Exception {
        String chinese = cleanKeyword(args.optString("chinese", preferences.getString("chinese", "你好Droy")), true);
        String english = cleanKeyword(args.optString("english", preferences.getString("english", "Hey Dory")), false);
        String timing = args.optString("timing", timing());
        if (!timing.equals("screen_on") && !timing.equals("foreground"))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("暂不支持该唤醒时机", "This wake-up timing is not supported yet"));
        boolean enabled = args.optBoolean("enabled", preferences.getBoolean("enabled", false));
        String[] effective = new String[]{chinese, english};
        if (enabled) {
            checkAllowed();
            VoiceModels.keywordSegments(effective[0]); VoiceModels.keywordSegments(effective[1]);
            if (models.ready()) models.keywords(effective[0], effective[1]);
        }
        pauseForVoice(); clearPendingEvent();
        preferences.edit().putString("chinese", chinese).putString("english", english)
            .putString("timing", timing).remove("useCompanionName")
            .putString("target", "standard").putBoolean("enabled", enabled).apply();
        error = null;
        if (enabled) {
            if (models.ready()) resumeIfEnabled(); else prepareModels();
        } else {
            models.cancel(); pausedForVoice = voiceClaims.active(); state = "off"; message = "";
        }
        return status();
    }

    private void prepareModels() {
        state = "preparing"; message = io.droyagent.app.NativeLocale.text("正在准备本地唤醒", "Preparing local wake-up"); error = null;
        models.prepare(() -> {
            if (!preferences.getBoolean("enabled", false)) { state = "off"; message = ""; return; }
            if (pluginSuspended) { state = "paused"; message = io.droyagent.app.NativeLocale.text("Droy Voice 已关闭", "Droy Voice is disabled"); return; }
            if (models.ready()) resumeIfEnabled();
            else {
                try {
                    JSONObject preparation = models.status();
                    state = "paused";
                    error = preparation.isNull("error") ? null : preparation.getString("error");
                    message = error == null ? io.droyagent.app.NativeLocale.text("语音唤醒已暂停", "Voice wake-up is paused") : error;
                } catch (Exception failure) { fail(failure); }
            }
        });
    }

    /** A conversation keeps this claim while its microphone is temporarily muted. */
    void claimForVoice() throws Exception { claimForVoice(null); }
    VoiceSessionOwnership.Lease claimForVoice(String owner) throws Exception {
        VoiceSessionOwnership.Lease claim;
        synchronized (this) {
            checkPreparationAllowed();
            if (VoiceMedia.retiredOperation(owner)) throw new VoiceSessionOwnership.Failure("VOICE_OWNER_MISMATCH");
            claim = voiceClaims.begin(owner, "voice");
            if (!voiceClaims.active(claim)) throw new VoiceSessionOwnership.Failure("VOICE_OWNER_MISMATCH");
        }
        try { pauseForVoice(); }
        catch (Exception failure) { synchronized (this) { voiceClaims.end(claim); } throw failure; }
        return claim;
    }

    void releaseVoice() {
        VoiceSessionOwnership.Lease claim = voiceClaims.current();
        if (claim != null && claim.owner == null) releaseVoice(claim);
    }
    void releaseVoice(VoiceSessionOwnership.Lease claim) {
        synchronized (this) {
            if (!voiceClaims.end(claim)) return;
            if (java.util.Objects.equals(voiceClaims.eventOwner(), claim.owner)) clearPendingEvent();
        }
        resumeIfEnabled();
    }
    private void releaseVoice(String owner) {
        synchronized (this) {
            if (!voiceClaims.release(owner)) return;
            if (voiceClaims.eventId() == 0) { pendingEventId = 0; VoiceRuntime.clearWakeNotification(context); }
        }
        resumeIfEnabled();
    }

    /** Called off the UI thread before a deliberate microphone consumer claims it. */
    void pauseForVoice() throws Exception {
        pausedForVoice = true;
        stopAndWait(active, "paused");
        if (preferences.getBoolean("enabled", false)) { state = "paused"; message = io.droyagent.app.NativeLocale.text("麦克风正在使用", "Microphone is in use"); }
    }

    void resumeIfEnabled() {
        if (pluginSuspended) return;
        if (BackgroundRuntime.explicitlyStopped(context) || ProductPermissions.stopped(context)) {
            pausedForVoice = true;
            state = preferences.getBoolean("enabled", false) ? "paused" : "off";
            message = state.equals("paused") ? io.droyagent.app.NativeLocale.text("Droy 已停止，语音唤醒已暂停", "Droy has stopped. Voice wake-up is paused") : "";
            return;
        }
        if (voiceClaims.active()) {
            pausedForVoice = true;
            state = preferences.getBoolean("enabled", false) ? "paused" : "off";
            message = state.equals("paused") ? io.droyagent.app.NativeLocale.text("麦克风正在使用", "Microphone is in use") : "";
            return;
        }
        pausedForVoice = false;
        if (!preferences.getBoolean("enabled", false)) { state = "off"; message = ""; return; }
        if (!listeningAllowed()) {
            clearPendingEvent(); state = "paused"; message = timingMessage(); return;
        }
        if (!models.ready() || active != null || hasPendingEvent()) return;
        try {
            AudioCapture capture = AudioCapture.get(context);
            if (capture.dictationActive() || !capture.uiStatus().isNull("active") || VoiceMedia.hasActiveWork(context)) {
                pausedForVoice = true; state = "paused"; message = io.droyagent.app.NativeLocale.text("麦克风正在使用", "Microphone is in use"); return;
            }
            checkAllowed();
            encodedKeywords();
            launch();
        } catch (Exception failure) { fail(failure); }
    }

    synchronized boolean validWakeEvent(long id) {
        return id != 0 && pendingEventId == id && listeningAllowed() && !pluginSuspended
            && preferences.getBoolean("enabled", false) && !ProductPermissions.stopped(context)
            && !BackgroundRuntime.explicitlyStopped(context);
    }

    private synchronized boolean consumeWakeEvent(long id, String owner) {
        if (voiceClaims.eventId() == id && java.util.Objects.equals(voiceClaims.eventOwner(), owner)) {
            requireStartingEvent(id, owner); return true;
        }
        if (!validWakeEvent(id)) { if (!listeningAllowed()) clearPendingEvent(); return false; }
        voiceClaims.bindEvent(id, owner);
        pendingEventId = 0;
        VoiceRuntime.clearWakeNotification(context);
        return true;
    }

    synchronized void requireStartingEvent(long id) { requireStartingEvent(id, null); }
    synchronized void requireStartingEvent(long id, String owner) {
        voiceClaims.requireEvent(id, owner);
        if (!listeningAllowed() || pluginSuspended
                || !preferences.getBoolean("enabled", false) || ProductPermissions.stopped(context)
                || BackgroundRuntime.explicitlyStopped(context))
            throw new IllegalStateException(io.droyagent.app.NativeLocale.text("本次唤醒已结束，请亮屏解锁后重新唤醒", "This wake-up has ended. Turn on the screen and unlock to wake up again"));
    }

    private synchronized boolean hasPendingEvent() { return pendingEventId != 0; }
    private synchronized void clearPendingEvent() { pendingEventId = 0; voiceClaims.clearEvent(); VoiceRuntime.clearWakeNotification(context); }

    private synchronized void launch() throws Exception {
        if (active != null || voiceClaims.active() || voiceClaims.eventId() != 0 || !listeningAllowed()) return;
        // An existing microphone service can continue outside the app, but a new one needs a visible activity.
        if (!BackgroundRuntime.visible()) { state = "paused"; message = io.droyagent.app.NativeLocale.text("打开 Droy 后继续监听", "Open Droy to continue listening"); return; }
        Run run = new Run(screenEpoch);
        JSONObject capture = AudioCapture.get(context).start(new JSONObject().put("source", "microphone").put("saveFile", false),
            WAKE_OWNER, UUID.randomUUID().toString());
        run.captureId = capture.getString("captureId");
        active = run; state = "starting"; error = null; message = "";
        if (capture.optString("state").equals("awaiting_user")) { state = "awaiting_user"; message = io.droyagent.app.NativeLocale.text("请打开 Droy 完成麦克风授权", "Please open Droy to grant microphone permission"); }
        run.thread = new Thread(() -> run(run), "droy-voice-wake");
        run.thread.start(); RuntimeService.refreshBackgroundPolicy();
    }

    private void run(Run run) {
        KeywordSpotter spotter = null;
        OnlineStream stream = null;
        boolean fired = false;
        try {
            spotter = models.createSpotter();
            stream = spotter.createStream(encodedKeywords());
            KeywordSpotter kws = spotter; OnlineStream input = stream;
            readLoop(run, samples -> {
                level = rms(samples); input.acceptWaveform(samples, 16000);
                while (kws.isReady(input)) kws.decode(input);
                String keyword = kws.getResult(input).getKeyword();
                if (keyword.isBlank()) return;
                kws.reset(input);
                if (!listeningAllowed()) { run.stopReason = "screen"; run.stop = true; return; }
                lastKeyword = keyword.replace('_', ' ');
                state = "triggered"; message = io.droyagent.app.NativeLocale.text("已唤醒", "Awake");
                pausedForVoice = true; run.stopReason = "triggered"; run.stop = true;
            });
            fired = run.stopReason.equals("triggered");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            if (!run.stop) fail(failure);
        } catch (LinkageError unavailable) {
            error = io.droyagent.app.NativeLocale.text("本地唤醒运行时无法加载", "Unable to load local wake-up runtime"); message = error; state = "paused";
        } finally {
            if (stream != null) stream.release();
            if (spotter != null) spotter.release();
            try { releaseCapture(run); }
            catch (Exception failure) { error = io.droyagent.app.NativeLocale.text("麦克风释放失败：", "Microphone release failed: ") + failure.getMessage(); state = "paused"; fired = false; }
            long issued = 0;
            synchronized (this) {
                if (fired && run.stopReason.equals("triggered") && run.screenEpoch == screenEpoch && listeningAllowed() && !pluginSuspended && !voiceClaims.active()
                        && preferences.getBoolean("enabled", false) && !ProductPermissions.stopped(context)
                        && !BackgroundRuntime.explicitlyStopped(context)) {
                    issued = ++sequence; pendingEventId = issued;
                }
                level = 0;
                if (issued == 0 && error == null) {
                    state = preferences.getBoolean("enabled", false) ? "paused" : "off";
                    message = run.stopReason.equals("paused") ? io.droyagent.app.NativeLocale.text("麦克风正在使用", "Microphone is in use") :
                        run.stopReason.equals("suspended") ? io.droyagent.app.NativeLocale.text("Droy Voice 已关闭", "Droy Voice is disabled") :
                        run.stopReason.equals("screen") ? timingMessage() : state.equals("off") ? "" : io.droyagent.app.NativeLocale.text("语音唤醒已暂停", "Voice wake-up is paused");
                }
                if (active == run) active = null;
            }
            if (issued != 0) {
                final long id = issued;
                main.post(() -> { WakeListener callback = listener; if (callback != null && validWakeEvent(id)) callback.onWake(id, "standard"); });
            }
            RuntimeService.refreshBackgroundPolicy();
        }
    }

    private interface Samples { void accept(float[] samples) throws Exception; }

    private void readLoop(Run run, Samples consumer) throws Exception {
        long cursor = 0; float sum = 0; int pending = 0;
        while (!run.stop) {
            if (!listeningAllowed()) { clearPendingEvent(); run.stopReason = "screen"; run.stop = true; break; }
            JSONObject status = AudioCapture.get(context).status(run.captureId, WAKE_OWNER);
            String captureState = status.getString("state");
            if (captureState.equals("awaiting_user") || captureState.equals("starting")) {
                if (captureState.equals("awaiting_user")) { state = "awaiting_user"; message = io.droyagent.app.NativeLocale.text("请打开 Droy 完成麦克风授权", "Please open Droy to grant microphone permission"); }
                Thread.sleep(40); continue;
            }
            if (!captureState.equals("recording")) throw new IOException(io.droyagent.app.NativeLocale.text("麦克风已停止，请重新开始", "Microphone stopped. Please start again"));
            state = "listening"; message = io.droyagent.app.NativeLocale.text("语音唤醒已开启", "Voice wake-up is enabled");
            AudioCapture.FrameRead read = AudioCapture.get(context).readFrames(run.captureId, WAKE_OWNER, cursor, 32760);
            cursor = read.metadata.getLong("nextCursor");
            if (read.metadata.optBoolean("discontinuity")) throw new IOException(io.droyagent.app.NativeLocale.text("音频处理跟不上采集，请重新开始", "Audio processing cannot keep up with capture. Please start again"));
            if (read.data.length == 0) { Thread.sleep(20); continue; }
            float[] source = AudioTranscriptionSegments.pcm16(read.data, 1);
            float[] mono = new float[(source.length + pending) / 3]; int count = 0;
            for (float sample : source) {
                sum += sample; pending++;
                if (pending == 3) { mono[count++] = sum / 3; sum = 0; pending = 0; }
            }
            if (mono.length > 0) consumer.accept(mono);
        }
    }

    private void stopAndWait(Run run, String reason) throws Exception {
        if (run == null) return;
        synchronized (this) { run.stopReason = reason; run.stop = true; }
        if (!run.captureId.isEmpty()) AudioCapture.get(context).stop(run.captureId, WAKE_OWNER);
        if (run.thread != null && run.thread != Thread.currentThread()) {
            run.thread.interrupt(); run.thread.join(2500);
            if (run.thread.isAlive()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("麦克风仍在结束，请稍后再试", "Microphone is still stopping. Please try again shortly"));
        }
    }

    private synchronized void stopAsync(String reason) {
        Run run = active;
        if (run == null) return;
        run.stopReason = reason; run.stop = true;
        if (run.thread != null) run.thread.interrupt();
    }

    private void releaseCapture(Run run) throws Exception {
        if (run.captureId.isEmpty()) return;
        AudioCapture capture = AudioCapture.get(context); capture.stop(run.captureId, WAKE_OWNER);
        long deadline = SystemClock.elapsedRealtime() + 2000;
        boolean interrupted = Thread.interrupted();
        try {
            while (true) {
                String value = capture.status(run.captureId, WAKE_OWNER).getString("state");
                if (!value.equals("recording") && !value.equals("starting") && !value.equals("finalizing") && !value.equals("awaiting_user")) return;
                if (SystemClock.elapsedRealtime() >= deadline) throw new IOException(io.droyagent.app.NativeLocale.text("录音服务尚未释放", "Recording service has not been released"));
                Thread.sleep(10);
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private void checkAllowed() {
        checkPreparationAllowed();
        if (!ProductPermissions.allowed(ProductPermissions.preferences(context), "allowAudioMicrophone"))
            throw new SecurityException(io.droyagent.app.NativeLocale.text("麦克风采集已暂停或关闭", "Microphone capture is paused or disabled"));
    }

    private void checkPreparationAllowed() {
        if (ProductPermissions.stopped(context) || BackgroundRuntime.explicitlyStopped(context))
            throw new SecurityException(io.droyagent.app.NativeLocale.text("Droy 已停止，语音唤醒已暂停", "Droy has stopped. Voice wake-up is paused"));
    }

    private synchronized JSONObject status() throws Exception {
        if (!listeningAllowed()) clearPendingEvent();
        Run run = active;
        return new JSONObject().put("enabled", preferences.getBoolean("enabled", false))
            .put("chineseKeyword", preferences.getString("chinese", "你好Droy"))
            .put("englishKeyword", preferences.getString("english", "Hey Dory"))
            .put("timing", timing()).put("useCompanionName", false)
            .put("effectiveChinese", effectiveKeywords()[0]).put("effectiveEnglish", effectiveKeywords()[1])
            .put("target", "standard").put("hasVoiceprint", false).put("sampleCount", 0)
            .put("suspended", pluginSuspended).put("pausedForVoice", pausedForVoice).put("voiceClaimed", voiceClaims.active())
            .put("voiceOperationId", voiceClaims.active() && voiceClaims.owner() != null ? voiceClaims.owner() : JSONObject.NULL)
            .put("startingVoiceOperationId", voiceClaims.eventOwner() == null ? JSONObject.NULL : voiceClaims.eventOwner())
            .put("screenAvailable", screenAvailable(context)).put("preparation", models.status()).put("state", state)
            .put("message", message).put("error", error == null ? JSONObject.NULL : error)
            .put("eventId", pendingEventId).put("startingEventId", voiceClaims.eventId()).put("keyword", lastKeyword).put("level", level)
            .put("captureId", run == null ? JSONObject.NULL : run.captureId);
    }

    private void fail(Exception failure) { error = failure.getMessage(); message = error; state = "paused"; }

    static String cleanKeyword(String input, boolean chinese) {
        String value = input.strip().replaceAll("\\s+", " ");
        if (value.length() > 48) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("唤醒词请控制在 48 个字符以内", "Wake word must be at most 48 characters"));
        if (!value.isEmpty() && !(chinese ? value.matches("[\\p{IsHan}a-zA-Z ]+") : value.matches("[a-zA-Z' -]+")))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("唤醒词请使用文字，不包含标点或数字", "Use words for the wake word, without punctuation or numbers"));
        return value;
    }

    private static float rms(float[] samples) {
        double sum = 0; for (float sample : samples) sum += sample * sample;
        return Math.min(1f, (float)Math.sqrt(sum / Math.max(1, samples.length)) * 8);
    }
}
