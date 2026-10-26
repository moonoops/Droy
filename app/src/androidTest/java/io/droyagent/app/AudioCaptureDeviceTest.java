package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import static org.junit.Assert.*;

/** Uses real Android consent and a synthetic source; never adopts projection permissions. */
@RunWith(AndroidJUnit4.class)
public final class AudioCaptureDeviceTest {
    private static final String OWNER = "controlled-audio-device-fixture";
    private Instrumentation instrumentation;
    private UiAutomation automation;
    private Context context, testContext;
    private AudioCapture audio;
    private AudioManager audioManager;
    private int originalVolume;
    private SharedPreferences settings;
    private final ArrayList<String> captures = new ArrayList<>();
    private final java.util.HashMap<String, Object> original = new java.util.HashMap<>();
    private File evidence;
    private boolean retainFiles;
    private String speechBase64, transcriptId;

    @Before public void prepare() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext(); testContext = instrumentation.getContext();
        audio = AudioCapture.get(context);
        assertTrue("Run controlled audio tests with no existing capture", audio.uiStatus().isNull("active"));
        assertFalse(audio.dictationActive());
        settings = ProductPermissions.preferences(context);
        String[] keys = {ProductPermissions.STOPPED, "allowAudioMicrophone", "allowAudioPlayback", "allowAudioTranscription"};
        for (String key : keys) original.put(key, settings.contains(key) ? settings.getBoolean(key, true) : null);
        instrumentation.runOnMainSync(() -> settings.edit().putBoolean(ProductPermissions.STOPPED, false)
            .putBoolean("allowAudioMicrophone", true).putBoolean("allowAudioPlayback", true).putBoolean("allowAudioTranscription", true).commit());
        audioManager = context.getSystemService(AudioManager.class);
        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        evidence = new File(context.getExternalFilesDir(null), "audio-capture-evidence");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
    }

    @After public void cleanup() throws Exception {
        if (context == null) return;
        if (transcriptId != null) AudioTranscription.get(context).request(new JSONObject().put("operation", "stop_transcription").put("transcriptId", transcriptId), OWNER);
        for (String id : captures) {
            audio.stopFromUi(id); awaitEnded(id, 15000);
            if (!retainFiles) audio.deleteFromUi(id);
        }
        fixture(false, true);
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0);
        SharedPreferences.Editor restore = settings.edit();
        for (java.util.Map.Entry<String, Object> entry : original.entrySet()) {
            if (entry.getValue() == null) restore.remove(entry.getKey()); else restore.putBoolean(entry.getKey(), (Boolean) entry.getValue());
        }
        instrumentation.runOnMainSync(restore::commit);
    }

    @Test public void longPlaybackRecordingKeepsOneHourOfDecodableAudio() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("Explicit -e audioLongMinutes 60 required", "60".equals(arguments.getString("audioLongMinutes")));
        long duration = 60L * 60 * 1000;
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        fixture(false, false);
        String id = startPlayback(false, duration);
        authorize(id);
        fixture(false, false);
        long cursor = 0, lastProgress = 0;
        boolean signal = false;
        long deadline = SystemClock.elapsedRealtime() + duration + 120000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AudioCapture.FrameRead frame = audio.readFrames(id, OWNER, cursor, 262144);
            assertFalse("Realtime PCM consumer lost samples", frame.metadata.getBoolean("discontinuity"));
            cursor = frame.metadata.getLong("nextCursor"); signal |= nonzero(frame.data);
            JSONObject status = audio.status(id, OWNER);
            if (status.getLong("durationMs") - lastProgress >= 60000) {
                lastProgress = status.getLong("durationMs");
                android.util.Log.i("DroyAudioEvidence", "long capture seconds=" + lastProgress / 1000 + " bytes=" + status.getLong("bytes"));
            }
            if (!active(status)) break;
            SystemClock.sleep(1000);
        }
        JSONObject result = awaitEnded(id, 15000);
        assertEquals(result.toString(), "completed", result.getString("state"));
        assertEquals("duration_limit", result.getString("reason"));
        assertTrue("Muted controlled source was not captured", signal);
        assertTrue(result.getLong("durationMs") >= duration);
        assertTrue(result.getLong("durationMs") <= duration + 100);
        File file = audio.file(id, OWNER);
        JSONObject decoded = verifyWav(file, duration);
        retainFiles = true;
        save("long-60-minutes.json", result.put("decoded", decoded).put("sha256", sha256(file)).put("systemMediaVolume", 0));
    }

    @Test public void playbackAtZeroSystemVolumeProvidesFramesBeforeStopAndSavesWav() throws Exception {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        fixture(false, false);
        String id = startPlayback(false, 0); authorize(id); fixture(false, false);
        JSONObject before = audio.status(id, OWNER);
        assertFalse(before.getBoolean("fileReady"));
        AudioCapture.FrameRead frame = awaitFrames(id, true, 5000);
        assertTrue(frame.data.length > 0);
        assertEquals("recording", audio.status(id, OWNER).getString("state"));
        audio.stop(id, OWNER);
        JSONObject result = awaitEnded(id, 10000);
        assertEquals("completed", result.getString("state"));
        save("playback-muted.json", result.put("decoded", verifyWav(audio.file(id, OWNER), 200)).put("signalNonzero", nonzero(frame.data)));
    }

    @Test public void deniedPlaybackProducesSilenceWithoutClaimingCapturePolicyDiagnosis() throws Exception {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        fixture(true, false);
        String id = startPlayback(false, 0); authorize(id); fixture(true, false);
        SystemClock.sleep(600);
        long cursor = audio.status(id, OWNER).getLong("nextCursor"), total = 0;
        boolean receivedSound = false;
        long deadline = SystemClock.elapsedRealtime() + 2000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AudioCapture.FrameRead frame = audio.readFrames(id, OWNER, cursor, 262144);
            cursor = frame.metadata.getLong("nextCursor"); total += frame.data.length;
            receivedSound |= nonzero(frame.data); SystemClock.sleep(100);
        }
        assertTrue("No input frames arrived", total > 0);
        assertFalse("A denied UID leaked its media audio", receivedSound);
        audio.stop(id, OWNER);
        save("playback-denied.json", awaitEnded(id, 10000).put("controlledSourcePolicy", "ALLOW_CAPTURE_BY_NONE").put("nonzero", receivedSound));
    }

    @Test public void systemPlaybackScopeCapturesTheControlledExternalUid() throws Exception {
        assertTrue("System-mix fixture is emulator-only", Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"));
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        fixture(false, false);
        String id = startPlayback(true, 0); authorize(id); fixture(false, false);
        assertTrue(nonzero(awaitFrames(id, true, 5000).data));
        audio.stop(id, OWNER);
        JSONObject result = awaitEnded(id, 10000);
        assertEquals("system", result.getString("scope"));
        save("playback-system-scope.json", result.put("decoded", verifyWav(audio.file(id, OWNER), 100)));
    }

    @Test public void revokingPlaybackProductGrantStopsTheRealInputAndKeepsPartialFile() throws Exception {
        fixture(false, false);
        String id = startPlayback(false, 0); authorize(id); fixture(false, false);
        awaitFrames(id, true, 5000);
        instrumentation.runOnMainSync(() -> settings.edit().putBoolean("allowAudioPlayback", false).commit());
        JSONObject result = awaitEnded(id, 10000);
        assertEquals("interrupted", result.getString("state"));
        assertEquals("permission_revoked", result.getString("reason"));
        assertTrue(result.getBoolean("fileReady"));
        assertThrows(SecurityException.class, () -> audio.readFrames(id, OWNER, 0, 1024));
        audio.stop(id, OWNER);
        save("playback-revoked.json", result.put("decoded", verifyWav(audio.file(id, OWNER), 100)));
    }

    @Test public void microphoneInputPersistsAfterNormalTaskEndAndFinalizesOnStop() throws Exception {
        assertTrue("Microphone capture fixture is emulator-only", Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"));
        String call = UUID.randomUUID().toString();
        ProductBridge bridge = new ProductBridge(context, "fixture-audio-device-token-0000000000000000000000");
        bridge.policy.enable();
        JSONObject started = bridge.executeTool("audio_start", new JSONObject().put("source", "microphone").put("saveFile", true), OWNER, call);
        String id = started.getString("captureId"); captures.add(id); authorize(id);
        AudioCapture.FrameRead first = awaitFrames(id, false, 5000);
        assertTrue(first.data.length > 0);
        long before = audio.status(id, OWNER).getLong("bytes");
        bridge.start(5000, false);
        try {
            java.net.HttpURLConnection request = (java.net.HttpURLConnection) new java.net.URL(
                "http://127.0.0.1:" + bridge.getListeningPort() + "/task/end").openConnection(java.net.Proxy.NO_PROXY);
            try {
                request.setConnectTimeout(5000); request.setReadTimeout(5000); request.setRequestMethod("POST");
                request.setRequestProperty("Authorization", "Bearer fixture-audio-device-token-0000000000000000000000");
                request.setRequestProperty("Content-Type", "application/json; charset=utf-8"); request.setDoOutput(true);
                byte[] body = new JSONObject().put("taskId", OWNER).toString().getBytes(StandardCharsets.UTF_8);
                request.setFixedLengthStreamingMode(body.length);
                try (java.io.OutputStream output = request.getOutputStream()) { output.write(body); }
                assertEquals(200, request.getResponseCode());
                try (java.io.InputStream input = request.getInputStream()) {
                    java.io.ByteArrayOutputStream receipt = new java.io.ByteArrayOutputStream();
                    byte[] block = new byte[1024]; int count;
                    while ((count = input.read(block)) != -1) receipt.write(block, 0, count);
                    assertTrue(new JSONObject(receipt.toString("UTF-8")).getBoolean("ok"));
                }
            } finally { request.disconnect(); }
        } finally { bridge.stop(); }
        SystemClock.sleep(700);
        assertEquals("recording", audio.status(id, OWNER).getString("state"));
        assertTrue(audio.status(id, OWNER).getLong("bytes") > before);
        bridge.executeTool("audio_stop", new JSONObject().put("captureId", id), OWNER, UUID.randomUUID().toString());
        JSONObject result = awaitEnded(id, 10000);
        assertEquals("completed", result.getString("state"));
        save("microphone-task-end.json", result.put("decoded", verifyWav(audio.file(id, OWNER), 500)).put("firstFrameBytes", first.data.length));
    }

    @Test public void yieldedWakeObservesTheRealMicrophoneRelease() throws Exception {
        assertTrue("Microphone capture fixture is emulator-only", Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic"));
        VoiceWake wake = VoiceWake.get(context);
        JSONObject initial = wake.request(new JSONObject().put("operation", "wake.status"));
        assertFalse("Run this fixture with wake disabled", initial.getBoolean("enabled"));
        assertFalse("Do not interrupt an enrollment or wake session", VoiceWake.hasActiveWork(context));
        boolean hadStopped = settings.contains(BackgroundRuntime.STOPPED);
        boolean wasStopped = settings.getBoolean(BackgroundRuntime.STOPPED, false);
        try {
            VoiceMedia.stopIfCreated("fixture_setup");
            settings.edit().putBoolean(BackgroundRuntime.STOPPED, false).commit();
            wake.request(new JSONObject().put("operation", "wake.resume"));
            wake.pauseForVoice();
            String id = audio.start(new JSONObject().put("source", "microphone").put("saveFile", false),
                OWNER, UUID.randomUUID().toString()).getString("captureId");
            captures.add(id); authorize(id);
            assertTrue(awaitFrames(id, false, 5000).data.length > 0);
            assertTrue(wake.request(new JSONObject().put("operation", "wake.status")).getBoolean("pausedForVoice"));
            audio.stop(id, OWNER); awaitEnded(id, 10000);
            DeviceUi.await("Wake did not observe the finalized microphone input", 2000, () ->
                !wake.request(new JSONObject().put("operation", "wake.status")).getBoolean("pausedForVoice"));
            assertTrue(audio.uiStatus().isNull("active"));
        } finally {
            wake.request(new JSONObject().put("operation", "wake.suspend"));
            SharedPreferences.Editor restore = settings.edit();
            if (hadStopped) restore.putBoolean(BackgroundRuntime.STOPPED, wasStopped); else restore.remove(BackgroundRuntime.STOPPED);
            assertTrue(restore.commit());
            if (!initial.getBoolean("suspended")) wake.request(new JSONObject().put("operation", "wake.resume"));
        }
    }

    @Test public void metadataFailureCannotPreventInputReleaseAndFileFinalization() throws Exception {
        fixture(false, false);
        String id = startPlayback(false, 0); authorize(id); fixture(false, false);
        awaitFrames(id, true, 5000);
        File blockedMetadata = new File(context.getFilesDir(), "audio-captures/" + id + ".json.tmp");
        assertTrue(blockedMetadata.mkdir());
        try {
            audio.stop(id, OWNER);
            JSONObject result = awaitEnded(id, 10000);
            assertEquals("interrupted", result.getString("state"));
            assertEquals("metadata_write_failed", result.getString("reason"));
            assertFalse(result.isNull("metadataError"));
            assertTrue(audio.uiStatus().isNull("active"));
            assertTrue(result.getBoolean("fileReady"));
            save("metadata-write-failure.json", result.put("decoded", verifyWav(audio.file(id, OWNER), 100)));
        } finally { assertTrue(blockedMetadata.delete()); }
    }

    @Test public void livePlaybackTranscriptionProducesTextBeforeCaptureStops() throws Exception {
        String fixturePath = InstrumentationRegistry.getArguments().getString("audioAsrFixture");
        Assume.assumeTrue("Explicit public speech fixture path required", fixturePath != null && !fixturePath.isBlank());
        File source = new File(fixturePath);
        assertTrue(source.isFile()); assertTrue(source.length() > 44 && source.length() < 300000);
        speechBase64 = android.util.Base64.encodeToString(Files.readAllBytes(source.toPath()), android.util.Base64.NO_WRAP);
        AudioTranscription speech = AudioTranscription.get(context);
        JSONObject preparation = speech.request(new JSONObject().put("operation", "preparation"), OWNER);
        assertTrue("Prepare verified local model before running this test: " + preparation, preparation.getBoolean("ready"));
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
        fixture(false, false);
        String id = startPlayback(false, 0); authorize(id); fixture(false, false);
        awaitFrames(id, true, 5000);
        JSONObject job = speech.request(new JSONObject().put("operation", "transcribe").put("captureId", id).put("language", "zh"), OWNER);
        transcriptId = job.getString("transcriptId");
        long deadline = SystemClock.elapsedRealtime() + 60000;
        JSONObject result = null;
        String expected = InstrumentationRegistry.getArguments().getString("audioExpectedText", "下午5点");
        while (SystemClock.elapsedRealtime() < deadline) {
            result = speech.request(new JSONObject().put("operation", "transcript").put("transcriptId", transcriptId).put("full", true), OWNER);
            if (result.optString("text").contains(expected)) break;
            assertFalse("Transcription ended without expected speech: " + result, result.optBoolean("finished"));
            SystemClock.sleep(300);
        }
        assertNotNull(result);
        assertTrue("Expected speech missing from realtime transcript: " + result, result.optString("text").contains(expected));
        assertEquals("recording", audio.status(id, OWNER).getString("state"));
        save("live-transcription-before-stop.json", result.put("captureStillRecording", true).put("expectedText", expected));
        audio.stop(id, OWNER); awaitEnded(id, 10000);
        long tailDeadline = SystemClock.elapsedRealtime() + 30000;
        do {
            result = speech.request(new JSONObject().put("operation", "transcript").put("transcriptId", transcriptId).put("full", true), OWNER);
            if (result.optBoolean("finished")) break;
            SystemClock.sleep(200);
        } while (SystemClock.elapsedRealtime() < tailDeadline);
        assertTrue("Realtime tail did not finish", result.optBoolean("finished"));
        assertEquals("completed", result.optString("state"));
        save("live-transcription-completed.json", result);
    }

    private String startPlayback(boolean system, long durationMs) throws Exception {
        JSONObject request = new JSONObject().put("source", "playback").put("scope", system ? "system" : "app")
            .put("packageName", system ? "" : testContext.getPackageName()).put("saveFile", true).put("durationMs", durationMs);
        String id = audio.start(request, OWNER, UUID.randomUUID().toString()).getString("captureId");
        captures.add(id); return id;
    }

    private void fixture(boolean deny, boolean stop) {
        Intent intent = new Intent().setComponent(new ComponentName(testContext.getPackageName(), AudioPlaybackFixtureActivity.class.getName()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("denyCapture", deny).putExtra("stop", stop);
        if (speechBase64 != null) intent.putExtra("speechBase64", speechBase64);
        context.startActivity(intent);
        SystemClock.sleep(200);
    }

    private void authorize(String id) throws Exception {
        if (audio.status(id, OWNER).getString("state").equals("awaiting_user"))
            context.startActivity(new Intent(context, AudioPermissionActivity.class).putExtra("captureId", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long deadline = SystemClock.elapsedRealtime() + 25000;
        boolean screenshot = false, selectingScope = false, scopeSelected = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONObject state = audio.status(id, OWNER);
            if (state.getString("state").equals("recording")) return;
            if (!active(state)) fail("Capture failed during real consent: " + state);
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) {
                if (!screenshot && contains(root, "Start now", "立即开始", "Start", "开始")) { capture("projection-consent.png"); screenshot = true; }
                if (selectingScope && tap(root, "Entire screen", "整个屏幕", "Share entire screen", "共享整个屏幕")) {
                    scopeSelected = true; selectingScope = false; SystemClock.sleep(250);
                } else if (!scopeSelected && !selectingScope && tap(root, "A single app", "单个应用", "Share one app", "共享一个应用")) {
                    selectingScope = true; SystemClock.sleep(250);
                } else if (tap(root, "While using the app", "仅在使用该应用时允许", "Allow", "允许", "Only this time", "仅限这一次")
                    || tap(root, "Start now", "立即开始", "Start", "开始", "Share screen", "共享屏幕")) SystemClock.sleep(250);
            }
            SystemClock.sleep(100);
        }
        capture("consent-timeout.png"); fail("Real Android audio consent timed out: " + audio.status(id, OWNER));
    }

    private AudioCapture.FrameRead awaitFrames(String id, boolean requireSound, long timeout) throws Exception {
        long cursor = 0, deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            AudioCapture.FrameRead frame = audio.readFrames(id, OWNER, cursor, 262144);
            cursor = frame.metadata.getLong("nextCursor");
            if (frame.data.length > 0 && (!requireSound || nonzero(frame.data))) return frame;
            assertTrue("Input ended while waiting for frames: " + audio.status(id, OWNER), active(audio.status(id, OWNER)));
            SystemClock.sleep(100);
        }
        throw new AssertionError("No " + (requireSound ? "nonzero " : "") + "PCM arrived: " + audio.status(id, OWNER));
    }

    private JSONObject awaitEnded(String id, long timeout) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        JSONObject result;
        do { result = audio.status(id, OWNER); if (!active(result)) return result; SystemClock.sleep(100); }
        while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError("Capture did not end: " + result);
    }

    private static boolean active(JSONObject status) { return java.util.Set.of("awaiting_user", "starting", "recording", "finalizing").contains(status.optString("state")); }
    private static boolean nonzero(byte[] bytes) { for (byte value : bytes) if (value != 0) return true; return false; }

    private static JSONObject verifyWav(File file, long minimumDurationMs) throws Exception {
        byte[] header = new byte[44];
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) { input.readFully(header); }
        ByteBuffer decoded = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RIFF", new String(header, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(header, 8, 4, StandardCharsets.US_ASCII));
        long dataBytes = Integer.toUnsignedLong(decoded.getInt(40));
        assertEquals(file.length() - 44, dataBytes);
        assertEquals(48000, decoded.getInt(24)); assertEquals(1, decoded.getShort(22)); assertEquals(16, decoded.getShort(34));
        long duration = dataBytes * 1000 / 96000;
        assertTrue("Recorded WAV shorter than expected: " + duration, duration >= minimumDurationMs);
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            assertEquals(1, extractor.getTrackCount());
            MediaFormat format = extractor.getTrackFormat(0);
            assertEquals("audio/raw", format.getString(MediaFormat.KEY_MIME));
            extractor.selectTrack(0);
            long sampleBytes = extractor.getSampleSize();
            assertTrue("Extractor did not expose a bounded PCM sample", sampleBytes > 0 && sampleBytes <= dataBytes && sampleBytes <= Integer.MAX_VALUE);
            ByteBuffer sample = ByteBuffer.allocateDirect((int) Math.max(65536, sampleBytes));
            int read = extractor.readSampleData(sample, 0);
            assertEquals("Extractor returned an incomplete PCM sample", sampleBytes, read);
            assertEquals("PCM sample was not channel aligned", 0, read % 2);
            assertEquals(48000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
            return new JSONObject().put("pcmBytes", dataBytes).put("durationMs", duration).put("extractorFormat", format.toString());
        } finally { extractor.release(); }
    }

    private boolean contains(AccessibilityNodeInfo node, String... labels) { return find(node, labels) != null; }
    private boolean tap(AccessibilityNodeInfo root, String... labels) {
        AccessibilityNodeInfo node = find(root, labels);
        if (node == null) return false;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return false;
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0);
        MotionEvent up = MotionEvent.obtain(time, time + 70, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN); up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue(automation.injectInputEvent(down, true)); assertTrue(automation.injectInputEvent(up, true)); }
        finally { down.recycle(); up.recycle(); }
        return true;
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String... labels) {
        if (node == null) return null;
        String text = node.getText() == null ? "" : node.getText().toString().trim();
        if (node.isVisibleToUser() && node.isEnabled())
            for (String label : labels) if (text.equalsIgnoreCase(label)) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo found = find(node.getChild(i), labels); if (found != null) return found; }
        return null;
    }

    private void capture(String name) throws Exception {
        Bitmap bitmap = automation.takeScreenshot();
        if (bitmap != null) try (FileOutputStream output = new FileOutputStream(new File(evidence, name))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
    }
    private void save(String name, JSONObject result) throws Exception {
        Files.write(new File(evidence, name).toPath(), result.toString(2).getBytes(StandardCharsets.UTF_8));
        android.util.Log.i("DroyAudioEvidence", name + " " + result);
    }
    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder(); for (byte b : digest.digest()) value.append(String.format(Locale.ROOT, "%02x", b & 255)); return value.toString();
    }
}
