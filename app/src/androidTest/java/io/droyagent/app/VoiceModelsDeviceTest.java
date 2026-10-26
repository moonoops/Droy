package io.droyagent.app;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.OnlineStream;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Dedicated emulator only. All audio is explicitly provisioned public release fixtures. */
@RunWith(AndroidJUnit4.class)
public class VoiceModelsDeviceTest {
    private android.content.SharedPreferences policy;
    private final java.util.Map<String, Boolean> savedStops = new java.util.LinkedHashMap<>();

    @Before public void resumeOnlyTheIsolatedTestPolicy() {
        String hardware = android.os.Build.HARDWARE;
        assertTrue("Voice model tests require a dedicated Android emulator",
            hardware.contains("ranchu") || hardware.contains("goldfish"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        policy = ProductPermissions.preferences(context);
        for (String key : new String[]{ProductPermissions.STOPPED, BackgroundRuntime.STOPPED})
            savedStops.put(key, policy.contains(key) ? policy.getBoolean(key, false) : null);
        assertTrue(policy.edit().putBoolean(ProductPermissions.STOPPED, false)
            .putBoolean(BackgroundRuntime.STOPPED, false).commit());
    }

    @After public void restoreTestPolicy() {
        if (policy == null) return;
        android.content.SharedPreferences.Editor editor = policy.edit();
        for (java.util.Map.Entry<String, Boolean> entry : savedStops.entrySet()) {
            if (entry.getValue() == null) editor.remove(entry.getKey());
            else editor.putBoolean(entry.getKey(), entry.getValue());
        }
        assertTrue(editor.commit());
    }

    @Test public void explicitRuntimeStopBlocksWakeResumeAndPreparation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Run without another capture", AudioCapture.get(context).uiStatus().isNull("active"));
        android.content.SharedPreferences prefs = ProductPermissions.preferences(context);
        boolean contained = prefs.contains(BackgroundRuntime.STOPPED);
        boolean original = prefs.getBoolean(BackgroundRuntime.STOPPED, false);
        VoiceWake wake = VoiceWake.get(context);
        boolean suspended = wake.request(new JSONObject().put("operation", "wake.status")).getBoolean("suspended");
        try {
            prefs.edit().putBoolean(BackgroundRuntime.STOPPED, true).commit();
            wake.request(new JSONObject().put("operation", "wake.resume"));
            wake.resumeIfEnabled();
            JSONObject stopped = wake.request(new JSONObject().put("operation", "wake.status"));
            assertTrue(stopped.toString(), stopped.getBoolean("pausedForVoice"));
            assertTrue(AudioCapture.get(context).uiStatus().isNull("active"));
            try { wake.request(new JSONObject().put("operation", "wake.prepare")); fail("Explicit stop must block new work"); }
            catch (SecurityException expected) { }
        } finally {
            android.content.SharedPreferences.Editor edit = prefs.edit();
            if (contained) edit.putBoolean(BackgroundRuntime.STOPPED, original); else edit.remove(BackgroundRuntime.STOPPED);
            edit.commit();
            wake.request(new JSONObject().put("operation", suspended ? "wake.suspend" : "wake.resume"));
        }
    }

    @Test public void voiceprintOperationsStayUnavailableWithoutOpeningMicrophone() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Run without another capture", AudioCapture.get(context).uiStatus().isNull("active"));
        VoiceWake wake = VoiceWake.get(context);
        for (String operation : new String[]{"wake.enroll_start", "wake.enroll_poll", "wake.enroll_commit",
                "wake.enroll_cancel", "wake.enroll_reset"}) {
            try { wake.request(new JSONObject().put("operation", operation)); fail("Voiceprint operation was available: " + operation); }
            catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("暂未开放")); }
        }
        assertTrue(AudioCapture.get(context).uiStatus().isNull("active"));
        assertFalse(wake.request(new JSONObject().put("operation", "wake.status")).getBoolean("hasVoiceprint"));
    }

    @Test public void bundledWakeResourcesExcludeSpeakerModels() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Set<String> names = new HashSet<>();
        try (java.util.zip.ZipInputStream archive = new java.util.zip.ZipInputStream(context.getAssets().open("voice-models.zip"))) {
            java.util.zip.ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) names.add(entry.getName());
        }
        assertEquals(Set.of("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "en.phone"), names);
        assertTrue(prepare().ready());
    }

    @Test public void keywordWakeEnablesWithoutEnrollmentAndIgnoresLegacyTarget() throws Exception {
        prepare();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        VoiceWake wake = VoiceWake.get(context);
        JSONObject before = wake.request(new JSONObject().put("operation", "wake.status"));
        assertFalse("Run without another wake listener", before.getBoolean("enabled"));
        android.content.SharedPreferences prefs = context.getSharedPreferences("droy-voice-wake", Context.MODE_PRIVATE);
        java.util.Map<String, ?> saved = prefs.getAll();
        wake.request(new JSONObject().put("operation", "wake.claim"));
        try {
            JSONObject result = wake.request(new JSONObject().put("operation", "wake.configure").put("enabled", true)
                .put("chinese", "你好小黑").put("english", "Hey Droy").put("target", "realtime"));
            assertTrue(result.getBoolean("enabled")); assertFalse(result.getBoolean("hasVoiceprint"));
            assertEquals("standard", result.getString("target"));
            assertTrue("Conversation claim must keep KWS off the microphone", AudioCapture.get(context).uiStatus().isNull("active"));
        } finally {
            android.content.SharedPreferences.Editor restore = prefs.edit();
            for (String key : new String[]{"enabled", "chinese", "english", "target", "timing", "useCompanionName"}) {
                Object value = saved.get(key);
                if (value instanceof Boolean) restore.putBoolean(key, (Boolean)value);
                else if (value instanceof String) restore.putString(key, (String)value);
                else restore.remove(key);
            }
            assertTrue(restore.commit()); wake.request(new JSONObject().put("operation", "wake.release"));
        }
    }

    @Test public void bundledBilingualKeywordModelDecodesPublicFixtures() throws Exception {
        VoiceModels models = prepare();
        String defaults = models.keywords("你好小黑", "Hey Droy");
        assertTrue(defaults, defaults.contains("@你好小黑"));
        assertTrue(defaults, defaults.contains("@Hey_Droy"));
        assertTrue(defaults, defaults.contains("D R OY1"));
        KeywordSpotter spotter = models.createSpotter();
        try {
            OnlineStream defaultStream = spotter.createStream(defaults);
            defaultStream.release();
            String custom = models.keywords("法国", "Light Up");
            assertTrue(decode(spotter, custom, fixture("zh_3.wav")).contains("法国"));
            assertTrue(decode(spotter, custom, fixture("en_0.wav")).contains("Light_Up"));
        } finally { spotter.release(); }
        try { models.keywords("", "zqxxunregisteredword"); fail("Unknown English word was silently accepted"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("英文单词")); }
    }

    @Test public void mixedCompanionNamesUseBothPronunciationSystems() throws Exception {
        VoiceModels models = prepare();
        for (String name : new String[]{"Droy", "小黑", "小黑 Droy"}) {
            String keywords = models.keywords("你好" + name, "Hi " + name);
            assertTrue(keywords, keywords.contains("@你好" + name.replace(' ', '_')));
            assertTrue(keywords, keywords.contains("@Hi_" + name.replace(' ', '_')));
            KeywordSpotter spotter = models.createSpotter();
            try { OnlineStream stream = spotter.createStream(keywords); stream.release(); }
            finally { spotter.release(); }
        }
        assertEquals(java.util.List.of("Hi", "小黑"), VoiceModels.keywordSegments("Hi小黑"));
        for (String name : new String[]{"猫🐈", "Droy2", "小.黑", "zqxxunregisteredword"}) {
            try { models.keywords("你好" + name, "Hi " + name); fail("Unsupported name was silently accepted: " + name); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void companionBindingPreservesManualWordsAndTracksRenames() throws Exception {
        prepare();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        VoiceWake wake = VoiceWake.get(context);
        JSONObject before = wake.request(new JSONObject().put("operation", "wake.status"));
        assertFalse("Run with wake disabled", before.getBoolean("enabled"));
        android.content.SharedPreferences prefs = context.getSharedPreferences("droy-voice-wake", Context.MODE_PRIVATE);
        java.util.Map<String, ?> saved = prefs.getAll();
        android.content.SharedPreferences product = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        boolean hadName = product.contains("petName");
        String name = product.getString("petName", "Droy");
        wake.request(new JSONObject().put("operation", "wake.claim"));
        try {
            assertTrue(product.edit().putString("petName", "Droy").commit());
            JSONObject bound = wake.request(new JSONObject().put("operation", "wake.configure").put("enabled", false)
                .put("timing", "foreground").put("chinese", "你好小黑").put("english", "Hey Droy").put("useCompanionName", true));
            assertEquals("你好Droy", bound.getString("effectiveChinese"));
            assertEquals("Hi Droy", bound.getString("effectiveEnglish"));
            assertEquals("你好小黑", bound.getString("chineseKeyword"));
            assertEquals("Hey Droy", bound.getString("englishKeyword"));
            assertTrue(product.edit().putString("petName", "小黑").commit());
            JSONObject renamed = wake.request(new JSONObject().put("operation", "wake.status"));
            assertEquals("你好小黑", renamed.getString("effectiveChinese"));
            assertEquals("Hi 小黑", renamed.getString("effectiveEnglish"));
            JSONObject manual = wake.request(new JSONObject().put("operation", "wake.configure").put("useCompanionName", false));
            assertEquals("Hey Droy", manual.getString("effectiveEnglish"));
            assertEquals("foreground", manual.getString("timing"));
        } finally {
            android.content.SharedPreferences.Editor restore = prefs.edit();
            for (String key : new String[]{"enabled", "chinese", "english", "target", "timing", "useCompanionName"}) {
                Object value = saved.get(key);
                if (value instanceof Boolean) restore.putBoolean(key, (Boolean)value);
                else if (value instanceof String) restore.putString(key, (String)value);
                else restore.remove(key);
            }
            assertTrue(restore.commit());
            android.content.SharedPreferences.Editor restoreName = product.edit();
            if (hadName) restoreName.putString("petName", name); else restoreName.remove("petName");
            assertTrue(restoreName.commit());
            wake.request(new JSONObject().put("operation", "wake.release"));
        }
    }

    @Test public void wakeTimingKeepsScreenAndVisibilityConstraintsDistinct() {
        assertTrue(VoiceWake.timingAllows("screen_on", true, false));
        assertFalse(VoiceWake.timingAllows("screen_on", false, true));
        assertTrue(VoiceWake.timingAllows("foreground", true, true));
        assertFalse(VoiceWake.timingAllows("foreground", true, false));
        assertFalse(VoiceWake.timingAllows("foreground", false, true));
    }

    @Test public void preparingOffOnContinuesAfterCancelledWorkerFinishes() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        VoiceModels models = unpreparedModel(context);
        CountDownLatch completed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger superseded = new java.util.concurrent.atomic.AtomicInteger();
        synchronized (models) {
            models.prepare(superseded::incrementAndGet);
            models.cancel();
            models.prepare(completed::countDown);
        }
        assertTrue("The new enable intent was lost after cancellation", completed.await(60, TimeUnit.SECONDS));
        assertTrue(models.status().toString(), models.ready());
        assertFalse(models.preparing());
        assertEquals("Superseded completion must not replace the new preparing state", 0, superseded.get());
    }

    @Test public void preparingOffOnOffDoesNotRestartAfterFinalDisable() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        VoiceModels models = unpreparedModel(context);
        CountDownLatch stopped = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger restarted = new java.util.concurrent.atomic.AtomicInteger();
        synchronized (models) {
            models.prepare(stopped::countDown);
            models.cancel();
            models.prepare(restarted::incrementAndGet);
            models.cancel();
        }
        assertTrue("Cancelled preparation did not finish", stopped.await(60, TimeUnit.SECONDS));
        assertFalse(models.preparing());
        assertEquals("Final disable left a queued preparation", 0, restarted.get());
    }

    private static VoiceModels unpreparedModel(Context context) throws Exception {
        assertFalse("Do not replace model files under an active keyword listener", VoiceWake.hasActiveWork(context));
        VoiceModels models = new VoiceModels(context);
        // Force first-run preparation without deleting another test's verified resource fixture.
        java.lang.reflect.Field state = VoiceModels.class.getDeclaredField("state");
        state.setAccessible(true); state.set(models, "not_prepared");
        return models;
    }

    private static VoiceModels prepare() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("Run on a dedicated emulator with product operations resumed", ProductPermissions.stopped(context));
        VoiceModels models = new VoiceModels(context);
        CountDownLatch prepared = new CountDownLatch(1);
        models.prepare(prepared::countDown);
        assertTrue("Model extraction/loading did not finish", prepared.await(60, TimeUnit.SECONDS));
        assertTrue(models.status().toString(), models.ready());
        return models;
    }

    private static File fixture(String name) throws Exception {
        String directory = InstrumentationRegistry.getArguments().getString("voiceFixtureDirectory", "");
        assertFalse("Provide app-private public release fixture directory via voiceFixtureDirectory", directory.isEmpty());
        File root = new File(directory).getCanonicalFile();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(root.toPath().startsWith(context.getFilesDir().getCanonicalFile().toPath()) ||
            root.toPath().startsWith(context.getCacheDir().getCanonicalFile().toPath()));
        File result = new File(root, name);
        assertTrue("Missing explicitly provisioned public fixture: " + name, result.isFile());
        return result;
    }

    private static Set<String> decode(KeywordSpotter spotter, String keywords, File file) throws Exception {
        OnlineStream stream = spotter.createStream(keywords);
        Set<String> found = new HashSet<>();
        try {
            AudioTranscriptionDecoder.decode(file, (samples, rate) -> {
                stream.acceptWaveform(samples, rate);
                decodeReady(spotter, stream, found);
            }, () -> {});
            stream.acceptWaveform(new float[16000], 16000); stream.inputFinished();
            decodeReady(spotter, stream, found);
            return found;
        } finally { stream.release(); }
    }

    private static void decodeReady(KeywordSpotter spotter, OnlineStream stream, Set<String> found) {
        while (spotter.isReady(stream)) {
            spotter.decode(stream);
            String keyword = spotter.getResult(stream).getKeyword();
            if (!keyword.isEmpty()) { found.add(keyword); spotter.reset(stream); }
        }
    }

}
