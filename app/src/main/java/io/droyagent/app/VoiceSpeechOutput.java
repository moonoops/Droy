package io.droyagent.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/** Owns system speech and the small PCM playback queue for one voice conversation. */
final class VoiceSpeechOutput {
    private static final AudioAttributes SPEECH = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private static final class Chunk {
        final byte[] bytes;
        final int rate;
        final long generation;
        Chunk(byte[] bytes, int rate, long generation) { this.bytes = bytes; this.rate = rate; this.generation = generation; }
    }
    private static final class LocalSpeech {
        final String text, id, provider, voiceId;
        final float rate;
        LocalSpeech(String text, String id, String provider, String voiceId, float rate) {
            this.text = text; this.id = id; this.provider = provider; this.voiceId = voiceId; this.rate = rate;
        }
    }

    private final Context context;
    private final AudioManager audioManager;
    private final TextToSpeech tts;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Chunk> queue = new ArrayDeque<>();
    private final Set<String> pendingSpeech = new HashSet<>();
    private final Set<String> rejectedResponses = new HashSet<>();
    private final ArrayDeque<LocalSpeech> localQueue = new ArrayDeque<>();
    private final Set<String> localPending = new HashSet<>();
    private AudioTrack track;
    private Thread player;
    private Thread localWorker;
    private TtsModels models;
    private AudioFocusRequest focus;
    private int previousMode, trackRate, queuedBytes, playerRate;
    private boolean previousSpeaker, communicating, ttsReady, closed;
    private long generation, writtenFrames, playerGeneration = -1;
    private long localEpoch;
    private String responseId = "", error = "";
    private String systemError = "";
    private Voice systemDefaultVoice;

    VoiceSpeechOutput(Context context) {
        this.context = context;
        audioManager = context.getSystemService(AudioManager.class);
        tts = new TextToSpeech(context.getApplicationContext(), status -> main.post(() -> initialized(status)));
    }

    private synchronized void initialized(int status) {
        if (closed) return;
        ttsReady = status == TextToSpeech.SUCCESS;
        if (!ttsReady) { systemError = io.droyagent.app.NativeLocale.text("系统朗读引擎不可用，请在系统设置中安装语音服务", "System text-to-speech is unavailable. Please install a speech service in system settings"); return; }
        systemDefaultVoice = tts.getVoice();
        tts.setAudioAttributes(SPEECH);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { finished(id, null); }
            @Override public void onError(String id) { finished(id, io.droyagent.app.NativeLocale.text("系统朗读失败", "System text-to-speech failed")); }
            @Override public void onError(String id, int code) { finished(id, io.droyagent.app.NativeLocale.text("系统朗读失败（", "System text-to-speech failed (") + code + NativeLocale.text("）", ")")); }
            @Override public void onStop(String id, boolean interrupted) { finished(id, null); }
        });
    }

    private synchronized void finished(String id, String failure) {
        if (!pendingSpeech.remove(id)) return;
        if (failure != null) error = failure;
    }

    synchronized JSONObject voices() throws Exception {
        JSONArray rows = MeloTts.voices();
        JSONArray qwen = QwenTts.voices();
        for (int index = 0; index < qwen.length(); index++) rows.put(qwen.getJSONObject(index));
        Voice selected = systemDefaultVoice;
        if (ttsReady) {
            Set<Voice> available = tts.getVoices();
            if (available != null) available.stream().sorted((a, b) -> a.getName().compareTo(b.getName())).forEach(voice -> {
                try {
                    rows.put(new JSONObject().put("id", voice.getName()).put("name", voice.getName())
                        .put("provider", "system")
                        .put("language", voice.getLocale().toLanguageTag()).put("networkRequired", voice.isNetworkConnectionRequired()));
                } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
            });
        }
        return new JSONObject().put("ready", true).put("systemReady", ttsReady).put("voices", rows)
            .put("models", models().status().getJSONArray("models"))
            .put("systemError", systemError.isEmpty() ? JSONObject.NULL : systemError)
            .put("defaultVoiceId", selected == null ? "" : selected.getName()).put("error", error.isEmpty() ? JSONObject.NULL : error);
    }

    synchronized JSONObject speak(JSONObject args) throws Exception {
        String provider = args.optString("provider", "system");
        if (provider.equals("melo") || provider.equals("qwen3")) return speakLocal(args);
        if (!provider.equals("system")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("朗读服务无效", "Invalid speech service"));
        if (!localPending.isEmpty() || pcmPlaying()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请先停止当前朗读再切换声音", "Please stop current speech before switching voices"));
        requireTts();
        String text = args.getString("text");
        if (text.isBlank() || text.length() > TextToSpeech.getMaxSpeechInputLength())
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("朗读内容为空或过长，请分句播放", "The text is empty or too long. Please play it in smaller sentences"));
        String voiceId = args.optString("voiceId");
        if (!voiceId.isEmpty()) {
            Voice selected = null;
            Set<Voice> available = tts.getVoices();
            if (available != null) for (Voice voice : available) if (voice.getName().equals(voiceId)) { selected = voice; break; }
            if (selected == null || tts.setVoice(selected) != TextToSpeech.SUCCESS)
                throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("所选声音不可用，请重新选择", "The selected voice is unavailable. Please select it again"));
        } else {
            if (systemDefaultVoice != null) {
                if (tts.setVoice(systemDefaultVoice) != TextToSpeech.SUCCESS)
                    throw new IllegalStateException(io.droyagent.app.NativeLocale.text("系统默认声音不可用", "The system default voice is unavailable"));
            } else {
                if (tts.setLanguage(Locale.getDefault()) < TextToSpeech.LANG_AVAILABLE)
                    throw new IllegalStateException(io.droyagent.app.NativeLocale.text("系统尚未安装当前语言的朗读声音", "A speech voice for the current language is not installed"));
                systemDefaultVoice = tts.getVoice();
            }
        }
        double rate = args.optDouble("rate", 1);
        if (!Double.isFinite(rate) || rate < 0.25 || rate > 4) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("语速无效", "Invalid speech speed"));
        if (tts.setSpeechRate((float) rate) != TextToSpeech.SUCCESS) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("朗读服务不支持该语速", "The speech service does not support this speed"));
        String id = args.optString("utteranceId", UUID.randomUUID().toString());
        if (id.isEmpty() || id.length() > 256) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("朗读标识无效", "Invalid speech ID"));
        String queuedId = generation + ":" + id;
        if (pendingSpeech.contains(queuedId)) return new JSONObject().put("utteranceId", id).put("state", "queued");
        if (localWorker != null) { localEpoch++; notifyAll(); }
        pendingSpeech.add(queuedId); error = "";
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, new Bundle(), queuedId) != TextToSpeech.SUCCESS) {
            pendingSpeech.remove(queuedId);
            throw new IllegalStateException(io.droyagent.app.NativeLocale.text("系统朗读未能开始", "System text-to-speech could not start"));
        }
        return new JSONObject().put("utteranceId", id).put("state", "queued");
    }

    synchronized JSONObject modelRequest(JSONObject args) throws Exception {
        return switch (args.getString("operation")) {
            case "tts.models" -> models().status();
            case "tts.download" -> models().download(args.getString("provider"));
            case "tts.cancel_download" -> models().cancel(args.getString("provider"));
            default -> throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("未知朗读资源操作", "Unknown speech resource operation"));
        };
    }

    private synchronized TtsModels models() throws Exception {
        if (models == null) models = new TtsModels(context);
        return models;
    }

    private void requireTts() {
        if (!ttsReady) throw new IllegalStateException(systemError.isEmpty() ? io.droyagent.app.NativeLocale.text("系统朗读正在准备，请稍后重试", "System text-to-speech is preparing. Please retry shortly") : systemError);
    }

    private JSONObject speakLocal(JSONObject args) throws Exception {
        if (closed) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("语音输出已关闭", "Speech output is disabled"));
        if (!pendingSpeech.isEmpty()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请先停止当前朗读再切换声音", "Please stop current speech before switching voices"));
        String text = args.getString("text"), id = args.optString("utteranceId", UUID.randomUUID().toString());
        if (text.isBlank() || text.length() > 4000) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("朗读内容为空或过长，请分句播放", "The text is empty or too long. Please play it in smaller sentences"));
        if (id.isEmpty() || id.length() > 256) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("朗读标识无效", "Invalid speech ID"));
        double rate = args.optDouble("rate", 1);
        if (!Double.isFinite(rate) || rate < .5 || rate > 2) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("语速无效", "Invalid speech speed"));
        String provider = args.getString("provider"), voiceId = args.optString("voiceId");
        if (provider.equals("melo")) MeloTts.validateVoice(voiceId);
        else {
            QwenTts.validateVoice(voiceId);
            if (rate != 1) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("Qwen3-TTS 暂仅支持标准语速", "Qwen3-TTS currently supports only normal speed"));
        }
        models().requireReady(provider);
        if (localPending.add(id)) {
            localQueue.add(new LocalSpeech(text, id, provider, voiceId, (float) rate)); error = "";
            startLocalWorker(); notifyAll();
        }
        return new JSONObject().put("utteranceId", id).put("state", "queued");
    }

    private void startLocalWorker() {
        if (localWorker != null || localQueue.isEmpty() || closed) return;
        long epoch = localEpoch;
        localWorker = new Thread(() -> synthesizeQueued(epoch), "droy-local-tts-output");
        localWorker.setDaemon(true); localWorker.start();
    }

    private synchronized boolean localCancelled(long epoch) { return closed || epoch != localEpoch; }

    private void synthesizeQueued(long epoch) {
        LocalTts engine = null;
        String engineProvider = "";
        try {
            while (true) {
                LocalSpeech speech;
                synchronized (this) {
                    while (!localCancelled(epoch) && localQueue.isEmpty() && communicating) wait();
                    if (localCancelled(epoch) || localQueue.isEmpty()) return;
                    speech = localQueue.remove();
                }
                if (engine == null || !engineProvider.equals(speech.provider)) {
                    if (engine != null) { engine.close(); engine = null; }
                    java.io.File directory = models().requireReady(speech.provider);
                    engine = speech.provider.equals("melo") ? new MeloTts(directory, () -> localCancelled(epoch))
                        : new QwenTts(directory, () -> localCancelled(epoch));
                    engineProvider = speech.provider;
                }
                int sampleRate = engine.sampleRate();
                awaitSampleRate(sampleRate, epoch);
                RuntimeException[] callbackFailure = {null};
                engine.generate(speech.text, speech.voiceId, speech.rate, () -> localCancelled(epoch), samples -> {
                    try { return enqueueLocal(samples, epoch, sampleRate); }
                    catch (RuntimeException failure) { callbackFailure[0] = failure; return false; }
                });
                if (callbackFailure[0] != null) throw callbackFailure[0];
                synchronized (this) { if (epoch == localEpoch) localPending.remove(speech.id); }
            }
        } catch (CancellationException cancelled) {
            // stop() has already cleared pending identities and flushed playback.
        } catch (Exception | LinkageError failure) {
            synchronized (this) {
                if (!localCancelled(epoch)) {
                    error = io.droyagent.app.NativeLocale.text("内置朗读失败：", "Built-in speech failed: ") + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
                    localEpoch++; localQueue.clear(); localPending.clear(); clearTrack();
                }
            }
        } finally {
            try { if (engine != null) engine.close(); }
            finally {
                synchronized (this) { localWorker = null; startLocalWorker(); notifyAll(); }
            }
        }
    }

    private synchronized void awaitSampleRate(int rate, long epoch) throws InterruptedException {
        boolean changingRate = (trackRate != 0 && trackRate != rate)
            || (player != null && playerGeneration == generation && playerRate != rate)
            || queue.stream().anyMatch(chunk -> chunk.rate != rate);
        if (!changingRate) return;
        // Switching local engines must play the old tail before changing AudioTrack's format.
        while (!localCancelled(epoch) && (!queue.isEmpty() || (player != null && playerGeneration == generation)
            || (track != null && Integer.toUnsignedLong(track.getPlaybackHeadPosition()) < writtenFrames))) wait(20);
        if (!localCancelled(epoch)) clearTrack();
    }

    private boolean enqueueLocal(float[] samples, long epoch, int sampleRate) {
        for (int offset = 0; offset < samples.length; offset += 4800) {
            int count = Math.min(4800, samples.length - offset);
            byte[] bytes = new byte[count * 2];
            for (int index = 0; index < count; index++) {
                float value = samples[offset + index];
                if (!Float.isFinite(value)) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("内置朗读返回了无效采样", "Built-in speech returned invalid samples"));
                int pcm = Math.round(Math.max(-1, Math.min(1, value)) * 32767);
                bytes[index * 2] = (byte) pcm; bytes[index * 2 + 1] = (byte) (pcm >> 8);
            }
            synchronized (this) {
                while (!localCancelled(epoch) && queuedBytes + bytes.length > sampleRate * 4) {
                    try { wait(20); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(io.droyagent.app.NativeLocale.text("内置朗读已中断", "Built-in speech was interrupted"), interrupted); }
                }
                if (localCancelled(epoch)) return false;
                play(bytes, sampleRate, "local:" + epoch);
            }
        }
        return !localCancelled(epoch);
    }

    synchronized void beginCommunication() {
        if (closed) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("语音输出已关闭", "Speech output is disabled"));
        if (communicating) return;
        rejectedResponses.clear();
        previousMode = audioManager.getMode(); previousSpeaker = audioManager.isSpeakerphoneOn();
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(SPEECH).setOnAudioFocusChangeListener(change -> {
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop();
            }, main).build();
        audioManager.requestAudioFocus(focus);
        if (Build.VERSION.SDK_INT >= 31) {
            AudioDeviceInfo selected = null;
            for (AudioDeviceInfo device : audioManager.getAvailableCommunicationDevices()) {
                int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    || type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_USB_HEADSET) { selected = device; break; }
                if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) selected = device;
            }
            if (selected != null) audioManager.setCommunicationDevice(selected);
        } else audioManager.setSpeakerphoneOn(true);
        communicating = true;
    }

    synchronized void endCommunication() {
        stop();
        if (!communicating) return;
        if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice();
        else audioManager.setSpeakerphoneOn(previousSpeaker);
        audioManager.setMode(previousMode);
        if (focus != null) audioManager.abandonAudioFocusRequest(focus);
        focus = null; communicating = false;
    }

    synchronized void play(byte[] bytes, int rate, String id) {
        if (closed) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("语音输出已关闭", "Speech output is disabled"));
        VoicePcm.requirePlayback(bytes, rate);
        if (!id.isEmpty() && rejectedResponses.contains(id)) return;
        if (!responseId.equals(id)) {
            clearTrack(); responseId = id;
        }
        if (queuedBytes + bytes.length > rate * 4) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("语音播放积压，请检查服务或网络速度", "Speech playback is backing up. Please check the service or network speed"));
        queue.add(new Chunk(bytes, rate, generation)); queuedBytes += bytes.length; error = "";
        if (player == null || !player.isAlive()) {
            player = new Thread(this::playQueued, "droy-voice-output"); player.setDaemon(true); player.start();
        }
        notifyAll();
    }

    private void playQueued() {
        while (true) {
            Chunk chunk;
            synchronized (this) {
                chunk = queue.poll();
                if (chunk == null) { player = null; return; }
                playerGeneration = chunk.generation;
                playerRate = chunk.rate;
                queuedBytes -= chunk.bytes.length;
                notifyAll();
            }
            try {
                int offset = 0;
                while (offset < chunk.bytes.length) {
                    int count;
                    synchronized (this) {
                        if (chunk.generation != generation) break;
                        AudioTrack output = trackFor(chunk.rate);
                        count = output.write(chunk.bytes, offset, chunk.bytes.length - offset, AudioTrack.WRITE_NON_BLOCKING);
                        if (count < 0) throw new IOException(io.droyagent.app.NativeLocale.text("音频播放失败（", "Audio playback failed (") + count + NativeLocale.text("）", ")"));
                        writtenFrames += count / 2;
                    }
                    offset += count;
                    if (count == 0) Thread.sleep(5);
                }
            } catch (Exception failure) {
                synchronized (this) {
                    if (chunk.generation != generation) continue;
                    error = failure.getMessage() == null ? io.droyagent.app.NativeLocale.text("音频播放失败", "Audio playback failed") : failure.getMessage();
                    if (responseId.startsWith("local:")) { localEpoch++; localQueue.clear(); localPending.clear(); }
                    clearTrack(); player = null; return;
                }
            }
        }
    }

    private AudioTrack trackFor(int rate) throws IOException {
        if (track != null && trackRate == rate) return track;
        if (track != null) throw new IOException(io.droyagent.app.NativeLocale.text("语音服务在同一回复中改变了采样率", "The speech service changed sample rates within one reply"));
        int minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IOException(io.droyagent.app.NativeLocale.text("设备不支持该语音播放格式", "The device does not support this speech playback format"));
        track = new AudioTrack.Builder().setAudioAttributes(SPEECH)
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(minimum, rate / 2)).build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) { track.release(); track = null; throw new IOException(io.droyagent.app.NativeLocale.text("语音播放器初始化失败", "Speech player initialization failed")); }
        trackRate = rate; writtenFrames = 0; track.play(); return track;
    }

    synchronized void clear(String rejectId) {
        String rejected = rejectId.isEmpty() ? responseId : rejectId;
        if (!rejected.isEmpty()) {
            if (rejectedResponses.size() >= 64) rejectedResponses.clear();
            rejectedResponses.add(rejected);
        }
        if (!rejectId.isEmpty() && !responseId.isEmpty() && !responseId.equals(rejectId)) return;
        clearTrack(); responseId = "";
    }

    synchronized void stop() {
        localEpoch++; localQueue.clear(); localPending.clear(); notifyAll();
        pendingSpeech.clear();
        tts.stop();
        clearTrack(); responseId = "";
    }

    private void clearTrack() {
        generation++; queue.clear(); queuedBytes = 0;
        if (track != null) {
            try { track.pause(); track.flush(); }
            finally { track.release(); track = null; }
        }
        trackRate = 0; writtenFrames = 0;
        notifyAll();
    }

    synchronized boolean speaking() {
        return !localPending.isEmpty() || !pendingSpeech.isEmpty() || pcmPlaying();
    }

    private boolean pcmPlaying() {
        return !queue.isEmpty()
            || (player != null && playerGeneration == generation) || (track != null
            && (Integer.toUnsignedLong(track.getPlaybackHeadPosition()) < writtenFrames || queuedBytes > 0));
    }

    synchronized String error() { return error; }

    synchronized void close() {
        if (closed) return;
        closed = true;
        if (models != null) models.cancelAll();
        try { endCommunication(); }
        finally { ttsReady = false; tts.shutdown(); }
    }
}
