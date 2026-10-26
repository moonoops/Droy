package io.droyagent.app;

import android.content.Context;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Uses downloaded public models and fictional text; does not change preferences or open a microphone. */
@RunWith(AndroidJUnit4.class)
public final class QwenTtsDeviceTest {
    private static final String TEXT = "你好，我在。你可以告诉我需要做什么。";
    private TtsPlaybackFixture playbackFixture;

    @Before public void pauseExistingWakeListener() throws Exception {
        playbackFixture = new TtsPlaybackFixture(); playbackFixture.begin();
    }

    @After public void restoreExistingWakeListener() throws Exception {
        if (playbackFixture != null) playbackFixture.close();
    }

    private Context idleContext() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("Finish an existing voice conversation before model tests", VoiceMedia.hasActiveWork(context));
        assertTrue("Do not interrupt an existing recording", AudioCapture.get(context).uiStatus().isNull("active"));
        return context;
    }

    @Test public void downloadedChineseVoicesGenerateAndCancelledContextCanRestart() throws Exception {
        Context context = idleContext();
        File directory = new TtsModels(context).requireReady("qwen3");
        long started = SystemClock.elapsedRealtime();
        JSONArray results = new JSONArray();
        try (QwenTts engine = new QwenTts(directory, () -> false)) {
            long loadMs = SystemClock.elapsedRealtime() - started;
            AtomicBoolean cancelled = new AtomicBoolean();
            long cancelStarted = SystemClock.elapsedRealtime();
            assertThrows(CancellationException.class, () -> engine.generate(TEXT, "Serena", 1,
                cancelled::get, samples -> { cancelled.set(true); return false; }));
            long cancelMs = SystemClock.elapsedRealtime() - cancelStarted;
            for (String voice : new String[]{"Serena", "Vivian", "Uncle_Fu"}) {
                long synthesisStarted = SystemClock.elapsedRealtime();
                long[] samples = {0}, firstMs = {-1}, observedPssKb = {Debug.getPss()};
                double[] peak = {0};
                engine.generate(TEXT, voice, 1, () -> false, pcm -> {
                    if (firstMs[0] < 0) firstMs[0] = SystemClock.elapsedRealtime() - synthesisStarted;
                    for (float value : pcm) {
                        assertTrue("Generated non-finite PCM", Float.isFinite(value));
                        peak[0] = Math.max(peak[0], Math.abs(value));
                    }
                    samples[0] += pcm.length;
                    observedPssKb[0] = Math.max(observedPssKb[0], Debug.getPss());
                    return true;
                });
                assertTrue("Expected at least half a second of real speech", samples[0] > QwenTts.SAMPLE_RATE / 2);
                assertTrue("Model produced silence", peak[0] > .001);
                results.put(new JSONObject().put("voice", voice).put("sampleRate", engine.sampleRate())
                    .put("samples", samples[0]).put("firstPcmMs", firstMs[0])
                    .put("totalMs", SystemClock.elapsedRealtime() - synthesisStarted)
                    .put("observedPssKb", observedPssKb[0]).put("peak", peak[0]));
            }
            report(new JSONObject().put("loadMs", loadMs).put("cancelAtFirstChunkMs", cancelMs).put("voices", results));
        }
        assertTrue(AudioCapture.get(context).uiStatus().isNull("active"));
    }

    @Test public void actualPlaybackStopsAndFreshRequestCanComplete() throws Exception {
        Context context = idleContext();
        new TtsModels(context).requireReady("qwen3");
        VoiceSpeechOutput output = new VoiceSpeechOutput(context);
        try {
            output.speak(new JSONObject().put("provider", "qwen3").put("voiceId", "Serena").put("text", TEXT));
            assertTrue(output.speaking());
            output.stop();
            assertFalse("Stop must immediately clear pending playback", output.speaking());
            output.speak(new JSONObject().put("provider", "qwen3").put("voiceId", "Vivian").put("text", "你好，我在。"));
            DeviceUi.await("Qwen3 never wrote real PCM", 180000,
                () -> number(output, "writtenFrames") > QwenTts.SAMPLE_RATE / 2 || !output.error().isEmpty());
            assertEquals("", output.error());
            assertTrue(number(output, "writtenFrames") > QwenTts.SAMPLE_RATE / 2);
            DeviceUi.await("Qwen3 playback did not finish", 180000, () -> !output.speaking());
            assertEquals("", output.error());
            synchronized (output) {
                java.lang.reflect.Field field = VoiceSpeechOutput.class.getDeclaredField("track"); field.setAccessible(true);
                android.media.AudioTrack track = (android.media.AudioTrack) field.get(output);
                assertNotNull(track);
                assertTrue("Playback stopped before the final samples", Integer.toUnsignedLong(track.getPlaybackHeadPosition()) >= number(output, "writtenFrames"));
            }
        } finally {
            output.close();
            DeviceUi.await("Qwen3 worker did not release its context", 180000, () -> {
                synchronized (output) {
                    java.lang.reflect.Field field = VoiceSpeechOutput.class.getDeclaredField("localWorker"); field.setAccessible(true);
                    return field.get(output) == null;
                }
            });
        }
        assertTrue(AudioCapture.get(context).uiStatus().isNull("active"));
    }

    private static long number(Object value, String name) throws Exception {
        synchronized (value) {
            java.lang.reflect.Field field = value.getClass().getDeclaredField(name); field.setAccessible(true);
            return ((Number) field.get(value)).longValue();
        }
    }

    private static void report(JSONObject value) {
        Bundle result = new Bundle(); result.putString("stream", "\nDROY_QWEN_TTS " + value + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(2, result);
    }
}
