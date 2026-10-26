package io.droyagent.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** The model used here must first be downloaded through the App's real resource flow. */
@RunWith(AndroidJUnit4.class)
public final class MeloTtsDeviceTest {
    private static final String TEXT = "你好，我是多瑞。很高兴和你聊天。";
    private TtsPlaybackFixture playbackFixture;

    @Before public void pauseExistingWakeListener() throws Exception {
        playbackFixture = new TtsPlaybackFixture(); playbackFixture.begin();
    }

    @After public void restoreExistingWakeListener() throws Exception {
        if (playbackFixture != null) playbackFixture.close();
    }

    @Test public void publicDownloadsVerifyBothModels() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        VoiceMedia media = VoiceMedia.get(context);
        for (String provider : new String[]{"melo", "qwen3"}) {
            media.request(new JSONObject().put("operation", "tts.download").put("provider", provider));
            long deadline = SystemClock.elapsedRealtime() + 15 * 60 * 1000;
            long reportedAt = 0;
            while (true) {
                JSONObject row = model(media.request(new JSONObject().put("operation", "tts.models")), provider);
                if (row.getBoolean("ready")) {
                    assertTrue(new TtsModels(context).requireReady(provider).isDirectory());
                    report("DROY_TTS_DOWNLOAD", row); break;
                }
                if (row.getString("state").equals("failed")) fail(row.toString());
                if (SystemClock.elapsedRealtime() >= deadline) {
                    media.request(new JSONObject().put("operation", "tts.cancel_download").put("provider", provider));
                    fail("Download timed out: " + row);
                }
                if (SystemClock.elapsedRealtime() - reportedAt >= 10000) {
                    report("DROY_TTS_DOWNLOAD", row); reportedAt = SystemClock.elapsedRealtime();
                }
                SystemClock.sleep(500);
            }
        }
    }

    @Test public void missingDownloadCannotStartSpeechOrCreatePartialModels() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path directory = Files.createTempDirectory(target.getCacheDir().toPath(), "melo-missing-");
        Context isolated = new ContextWrapper(target) {
            @Override public File getFilesDir() { return directory.toFile(); }
        };
        VoiceSpeechOutput output = new VoiceSpeechOutput(isolated);
        try {
            for (String provider : new String[]{"melo", "qwen3"}) {
                try { output.speak(new JSONObject().put("provider", provider).put("text", TEXT)); fail("Undownloaded model was accepted"); }
                catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("下载")); }
            }
            assertFalse(output.speaking()); assertNull(value(output, "localWorker"));
            try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
        } finally { output.close(); HistoryData.deleteTree(directory); }
    }

    @Test public void downloadedMeloGeneratesRealPcmAndPlaybackCanBeStoppedAndRestarted() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File modelDirectory = new TtsModels(context).requireReady("melo");
        assertEquals(Integer.class, MeloTts.AudioCallback.class.getDeclaredMethod("invoke", float[].class).getReturnType());
        long began = SystemClock.elapsedRealtime(), samples[] = {0};
        float[] peak = {0};
        try (MeloTts engine = new MeloTts(modelDirectory, () -> false)) {
            engine.generate(TEXT, "zh", 1, () -> false, pcm -> {
                for (float sample : pcm) {
                    assertTrue(Float.isFinite(sample)); peak[0] = Math.max(peak[0], Math.abs(sample));
                }
                samples[0] += pcm.length; return true;
            });
        }
        assertTrue(samples[0] > MeloTts.SAMPLE_RATE / 2); assertTrue(peak[0] > .001f);
        long generationMs = SystemClock.elapsedRealtime() - began;
        VoiceSpeechOutput output = new VoiceSpeechOutput(context);
        try {
            output.beginCommunication();
            began = SystemClock.elapsedRealtime();
            output.speak(new JSONObject().put("provider", "melo").put("voiceId", "zh").put("text", TEXT));
            assertTrue(output.speaking());
            try { output.speak(new JSONObject().put("provider", "system").put("text", "不能同时朗读")); fail("System and local speech overlapped"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("停止当前朗读")); }
            await("No real Melo playback: " + output.error(), 120000, () -> number(output, "writtenFrames") > 0);
            long firstPcmMs = SystemClock.elapsedRealtime() - began;
            await("Melo playback never finished: " + output.error(), 120000, () -> !output.speaking());
            assertEquals("", output.error()); assertEquals(MeloTts.SAMPLE_RATE, number(output, "trackRate"));
            synchronized (output) {
                AudioTrack track = (AudioTrack) value(output, "track"); assertNotNull(track);
                assertTrue(Integer.toUnsignedLong(track.getPlaybackHeadPosition()) >= number(output, "writtenFrames"));
            }
            long before = number(output, "writtenFrames");
            output.speak(new JSONObject().put("provider", "melo").put("text", TEXT.repeat(8)));
            await("Long Melo sentence never started", 120000, () -> number(output, "writtenFrames") > before + 1200);
            output.stop();
            assertFalse(output.speaking());
            output.speak(new JSONObject().put("provider", "melo").put("text", "我在。"));
            await("New utterance was lost after stop", 120000, () -> number(output, "writtenFrames") > 0);
            await("Restarted utterance never finished", 120000, () -> !output.speaking());
            assertEquals("", output.error());
            output.stop();
            await("Cancelled worker did not release model", 120000, () -> value(output, "localWorker") == null);
            assertNull(value(output, "track")); assertEquals(0, number(output, "writtenFrames"));
            Bundle report = new Bundle();
            report.putString("stream", "\nDROY_MELO_NATIVE " + new JSONObject().put("sampleRate", MeloTts.SAMPLE_RATE)
                .put("samples", samples[0]).put("loadAndGenerationMs", generationMs).put("firstPlaybackMs", firstPcmMs) + "\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(2, report);
        } finally {
            output.close(); await("Closed worker did not release", 120000, () -> value(output, "localWorker") == null);
        }
    }

    @Test public void systemSpeechRejectsLocalOverlapAndEmptyVoiceRestoresSystemDefault() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        VoiceSpeechOutput output = new VoiceSpeechOutput(context);
        try {
            await("System TTS unavailable", 30000, () -> output.voices().getBoolean("systemReady"));
            String defaultVoice = output.voices().getString("defaultVoiceId");
            var voices = output.voices().getJSONArray("voices");
            String otherVoice = "";
            for (int index = 0; index < voices.length(); index++) {
                JSONObject voice = voices.getJSONObject(index);
                if (voice.getString("provider").equals("system") && !voice.getString("id").equals(defaultVoice)
                    && !voice.optBoolean("networkRequired")) { otherVoice = voice.getString("id"); break; }
            }
            if (!otherVoice.isEmpty()) {
                output.speak(new JSONObject().put("provider", "system").put("voiceId", otherVoice).put("text", "你好"));
                output.stop();
            }
            output.speak(new JSONObject().put("provider", "system").put("voiceId", "").put("text", TEXT.repeat(4)));
            assertTrue(output.speaking());
            if (!defaultVoice.isEmpty()) {
                android.speech.tts.TextToSpeech engine = (android.speech.tts.TextToSpeech) value(output, "tts");
                assertEquals(defaultVoice, engine.getVoice().getName());
            }
            try { output.speak(new JSONObject().put("provider", "melo").put("text", TEXT)); fail("Local speech overlapped system speech"); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("停止当前朗读")); }
        } finally { output.close(); }
    }

    private interface Condition { boolean ready() throws Exception; }
    private static void await(String message, long timeout, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!condition.ready()) {
            if (SystemClock.elapsedRealtime() >= deadline) fail(message);
            SystemClock.sleep(20);
        }
    }
    private static Object value(VoiceSpeechOutput output, String name) throws Exception {
        synchronized (output) {
            Field field = VoiceSpeechOutput.class.getDeclaredField(name); field.setAccessible(true); return field.get(output);
        }
    }
    private static long number(VoiceSpeechOutput output, String name) throws Exception { return ((Number) value(output, name)).longValue(); }
    private static JSONObject model(JSONObject status, String provider) throws Exception {
        var models = status.getJSONArray("models");
        for (int index = 0; index < models.length(); index++)
            if (models.getJSONObject(index).getString("provider").equals(provider)) return models.getJSONObject(index);
        throw new AssertionError("Missing provider " + provider);
    }
    private static void report(String name, JSONObject value) {
        Bundle bundle = new Bundle(); bundle.putString("stream", "\n" + name + " " + value + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(2, bundle);
    }
}
