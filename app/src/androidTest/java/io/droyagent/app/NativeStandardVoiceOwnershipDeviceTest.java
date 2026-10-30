package io.droyagent.app;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises the real Java capture boundary only while Android microphone permission stays denied. */
@RunWith(AndroidJUnit4.class)
public final class NativeStandardVoiceOwnershipDeviceTest {
    private static final String SESSION = "synthetic-standard-voice-owner-session";
    private static final String[] PRODUCT_KEYS = {ProductPermissions.STOPPED, BackgroundRuntime.STOPPED, "allowAudioMicrophone"};
    private final Map<String, Boolean> saved = new HashMap<>();
    private final Set<String> operations = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<String> captures = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Context context;
    private SharedPreferences product;
    private AudioCapture capture;
    private AudioTranscription transcription;
    private VoiceWake wake;
    private Map<String, String> originalCaptureFiles;
    private boolean owned, wasSuspended, wasPaused;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Only the development package may run this fixture", "io.droyagent.app.dev", context.getPackageName());
        assertTrue("Voice ownership fixtures require the dedicated emulator", Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone"));
        assertMicrophoneDenied();
        assertTrue("Do not interrupt the engine or its tasks", RuntimeService.canManageHistory());
        capture = AudioCapture.get(context);
        assertTrue("Do not interrupt an existing capture", capture.uiStatus().isNull("active"));
        assertFalse("Do not interrupt dictation", capture.dictationActive());
        transcription = AudioTranscription.get(context);
        DeviceUi.await("Let bundled ASR initialization settle without restarting or cancelling it", 30000,
            () -> !transcription.request(new JSONObject().put("operation", "preparation"), VoiceMedia.OWNER).optString("state").equals("preparing"));
        assertFalse("Do not interrupt an active ASR job", transcription.hasActiveJobs());
        assertTrue("Prepare bundled offline ASR before this fixture; the test does not prepare models",
            transcription.request(new JSONObject().put("operation", "preparation"), VoiceMedia.OWNER).getBoolean("ready"));
        assertFalse("Do not interrupt standard voice", VoiceMedia.hasActiveWork(context));
        assertFalse("Do not interrupt wake or enrollment", VoiceWake.hasActiveWork(context));
        assertFalse("Do not interrupt another readout", request(new JSONObject().put("operation", "status")).getBoolean("speaking"));
        wake = VoiceWake.get(context);
        JSONObject original = wake.request(new JSONObject().put("operation", "wake.status"));
        assertFalse("Run this fixture with wake disabled", original.getBoolean("enabled"));
        assertFalse("Do not replace a claimed wake operation", original.getBoolean("voiceClaimed"));
        assertEquals("Do not consume another pending wake", 0, original.getLong("eventId"));
        assertEquals("Do not replace another consumed wake", 0, original.getLong("startingEventId"));
        wasSuspended = original.getBoolean("suspended"); wasPaused = original.getBoolean("pausedForVoice");
        originalCaptureFiles = captureFiles();
        product = ProductPermissions.preferences(context);
        for (String key : PRODUCT_KEYS) if (product.contains(key)) saved.put(key, product.getBoolean(key, false));
        owned = true;
        assertTrue(product.edit().putBoolean(ProductPermissions.STOPPED, false).putBoolean(BackgroundRuntime.STOPPED, false)
            .putBoolean("allowAudioMicrophone", true).commit());
        assertMicrophoneDenied();
    }

    @After public void tearDown() throws Exception {
        if (!owned) return;
        try {
            JSONObject current = request(new JSONObject().put("operation", "status"));
            String owner = current.isNull("voiceOperationId") ? null : current.getString("voiceOperationId");
            if (owner != null && operations.contains(owner)) request(operation("stop", owner));
            for (String id : captures) {
                assertEquals("Only this fixture's standard captures can be cleaned", VoiceMedia.OWNER, capture.ownerForUi(id));
                capture.stop(id, VoiceMedia.OWNER);
            }
            DeviceUi.await("The fixture's capture and ASR must actually finish before restoring configuration", 5000,
                () -> capture.uiStatus().isNull("active") && !capture.dictationActive() && !transcription.hasActiveJobs());
            assertFalse(VoiceMedia.hasActiveWork(context));
            JSONObject wakeState = wake.request(new JSONObject().put("operation", "wake.status"));
            if (wakeState.getBoolean("voiceClaimed")) {
                assertFalse("A legacy or unrelated wake claim must not be released", wakeState.isNull("voiceOperationId"));
                String wakeOwner = wakeState.getString("voiceOperationId");
                assertTrue("Do not release another client's wake claim", operations.contains(wakeOwner));
                wake.request(operation("wake.release", wakeOwner));
            }
            for (String id : captures) capture.deleteFromUi(id);
            assertEquals("Existing recording files must be untouched", originalCaptureFiles, captureFiles());
        } finally {
            try {
                assertTrue("Never resume wake before capture release", capture.uiStatus().isNull("active"));
                assertFalse(transcription.hasActiveJobs());
                assertFalse(wake.request(new JSONObject().put("operation", "wake.status")).getBoolean("enabled"));
                // Disabled wake cannot open input; public lifecycle calls restore the original flags.
                wake.request(new JSONObject().put("operation", "wake.resume"));
                if (wasPaused) wake.request(new JSONObject().put("operation", "wake.pause"));
                if (wasSuspended) wake.request(new JSONObject().put("operation", "wake.suspend"));
                JSONObject restored = wake.request(new JSONObject().put("operation", "wake.status"));
                assertEquals(wasSuspended, restored.getBoolean("suspended"));
                assertEquals(wasPaused, restored.getBoolean("pausedForVoice"));
            } finally {
                SharedPreferences.Editor restore = product.edit();
                for (String key : PRODUCT_KEYS) {
                    if (saved.containsKey(key)) restore.putBoolean(key, saved.get(key)); else restore.remove(key);
                }
                assertTrue(restore.commit());
                assertMicrophoneDenied();
            }
        }
    }

    @Test public void concurrentSameSessionStartsHaveOneRealOwnerAndRejectLegacyControls() throws Exception {
        String a = newOperation(), b = newOperation();
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        StartResult left, right;
        try {
            Future<StartResult> first = pool.submit(() -> compete(a, gate));
            Future<StartResult> second = pool.submit(() -> compete(b, gate));
            gate.countDown(); left = first.get(15, TimeUnit.SECONDS); right = second.get(15, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
        assertNotEquals("Exactly one caller may own a capture", left.value != null, right.value != null);
        StartResult winner = left.value != null ? left : right, loser = left.value == null ? left : right;
        assertEquals("VOICE_OWNER_MISMATCH", loser.error);
        String owner = winner.value.getString("voiceOperationId"), id = winner.value.getString("captureId");
        assertAwaitingOwner(owner, id);
        JSONObject repeated = start(owner);
        assertEquals(id, repeated.getString("captureId"));
        JSONObject poll = request(operation("poll", owner).put("cursor", 0).put("transcriptCursor", 0));
        assertEquals(owner, poll.getString("voiceOperationId")); assertEquals("", poll.getString("audio"));
        assertEquals(0, poll.getLong("nextCursor")); assertEquals("", poll.getString("transcriptId"));
        for (String name : new String[]{"start", "poll", "mute", "stop", "stop_speaking", "speak", "wake.release"}) {
            JSONObject input = new JSONObject().put("operation", name).put("sessionId", SESSION).put("mode", "standard").put("muted", true);
            silentSpeech(input);
            expectOwnerFailure("VOICE_OPERATION_REQUIRED", () -> request(input));
            assertAwaitingOwner(owner, id);
        }
        assertEquals(1, captures.size());
    }

    @Test public void endedOwnersCannotControlTheNextRealCaptureOrRestartAfterItEnds() throws Exception {
        String x = newOperation(), y = newOperation();
        String oldCapture = start(x).getString("captureId"); assertAwaitingOwner(x, oldCapture);
        assertEquals("ended", request(operation("stop", x)).getString("state"));
        assertIdleInput();
        String nextCapture = start(y).getString("captureId"); assertNotEquals(oldCapture, nextCapture);
        for (String name : new String[]{"start", "poll", "mute", "stop", "stop_speaking", "speak", "wake.release", "wake.claim"}) {
            JSONObject input = operation(name, x).put("mode", "standard").put("sessionId", SESSION).put("muted", true);
            silentSpeech(input);
            expectOwnerFailure("VOICE_OWNER_MISMATCH", () -> request(input));
            assertAwaitingOwner(y, nextCapture);
        }
        assertEquals("ended", request(operation("stop", y)).getString("state"));
        assertIdleInput();
        expectOwnerFailure("VOICE_OWNER_MISMATCH", () -> start(x));
        expectOwnerFailure("VOICE_OWNER_MISMATCH", () -> request(silentSpeech(operation("speak", y))));
        JSONObject terminal = start(y);
        assertEquals("ended", terminal.getString("state")); assertEquals(y, terminal.getString("voiceOperationId"));
        assertEquals("", terminal.getString("captureId")); assertEquals(2, captures.size());
        assertIdleInput();
    }

    @Test public void stopBeforeAnOperationWasAdmittedRetiresItWithoutReleasingAnotherWakeOwner() throws Exception {
        String z = newOperation(), y = newOperation();
        expectOwnerFailure("VOICE_OWNER_MISMATCH", () -> request(operation("stop", z)));
        assertIdleInput();
        String id = start(y).getString("captureId"); assertAwaitingOwner(y, id);
        expectOwnerFailure("VOICE_OWNER_MISMATCH", () -> request(operation("stop", z)));
        expectOwnerFailure("VOICE_OWNER_MISMATCH", () -> request(operation("wake.claim", z)));
        expectOwnerFailure("VOICE_OWNER_MISMATCH", () -> start(z));
        assertAwaitingOwner(y, id);
        request(operation("stop", y)); assertIdleInput();
        expectOwnerFailure("VOICE_OWNER_MISMATCH", () -> start(z));
        assertEquals(1, captures.size()); assertIdleInput();
    }

    private StartResult compete(String owner, CountDownLatch gate) throws Exception {
        gate.await();
        try { return new StartResult(start(owner), null); }
        catch (VoiceSessionOwnership.Failure failure) { return new StartResult(null, failure.code); }
    }
    private static final class StartResult {
        final JSONObject value; final String error;
        StartResult(JSONObject value, String error) { this.value = value; this.error = error; }
    }
    private String newOperation() { String id = UUID.randomUUID().toString(); operations.add(id); return id; }
    private JSONObject start(String owner) throws Exception {
        assertMicrophoneDenied();
        JSONObject value = request(operation("start", owner).put("mode", "standard").put("sessionId", SESSION).put("language", "auto"));
        assertMicrophoneDenied();
        return value;
    }
    private JSONObject request(JSONObject input) throws Exception {
        try {
            JSONObject value = VoiceRuntime.request(context, input);
            rememberCapture(value); return value;
        } finally {
            // A partially failed admitted start may still have allocated an owned pending capture.
            JSONObject current = capture == null ? null : capture.uiStatus().optJSONObject("active");
            if (current != null && VoiceMedia.OWNER.equals(current.optString("owner"))) {
                JSONObject status = VoiceRuntime.request(context, new JSONObject().put("operation", "status"));
                rememberCapture(status);
            }
        }
    }
    private void rememberCapture(JSONObject value) throws Exception {
        if (value.isNull("voiceOperationId") || !operations.contains(value.optString("voiceOperationId"))) return;
        String id = value.optString("captureId"); if (!id.isEmpty()) captures.add(id);
    }
    private JSONObject operation(String operation, String owner) throws Exception {
        return new JSONObject().put("operation", operation).put("voiceOperationId", owner);
    }
    private JSONObject silentSpeech(JSONObject input) throws Exception {
        // If an ownership fence regresses, this provider is still rejected before any audible TTS.
        if (input.optString("operation").equals("speak")) input.put("provider", "ownership-fixture-no-audio").put("text", "Must never be played");
        return input;
    }
    private void assertAwaitingOwner(String owner, String id) throws Exception {
        assertMicrophoneDenied();
        JSONObject status = request(new JSONObject().put("operation", "status"));
        assertEquals("standard", status.getString("mode")); assertEquals("awaiting_user", status.getString("state"));
        assertEquals(owner, status.getString("voiceOperationId")); assertEquals(id, status.getString("captureId"));
        assertEquals("", status.getString("transcriptId")); assertFalse(status.getBoolean("speaking"));
        JSONObject recording = capture.status(id, VoiceMedia.OWNER);
        assertEquals("awaiting_user", recording.getString("state")); assertEquals(0, recording.getLong("nextCursor"));
        assertEquals(0, recording.getLong("bytes")); assertFalse(recording.getBoolean("fileReady"));
        assertEquals(id, capture.uiStatus().getJSONObject("active").getString("captureId"));
        JSONObject wakeState = wake.request(new JSONObject().put("operation", "wake.status"));
        assertTrue(wakeState.getBoolean("voiceClaimed")); assertEquals(owner, wakeState.getString("voiceOperationId"));
        assertFalse(wakeState.getBoolean("enabled")); assertEquals(wasSuspended, wakeState.getBoolean("suspended"));
        assertFalse(transcription.hasActiveJobs()); assertFalse(capture.dictationActive());
    }
    private void assertIdleInput() throws Exception {
        assertMicrophoneDenied();
        DeviceUi.await("Owned pending capture must release input", 5000, () -> capture.uiStatus().isNull("active") && !transcription.hasActiveJobs());
        JSONObject state = wake.request(new JSONObject().put("operation", "wake.status"));
        assertFalse(state.getBoolean("voiceClaimed")); assertTrue(state.isNull("voiceOperationId"));
        assertFalse(VoiceMedia.hasActiveWork(context));
    }
    private void assertMicrophoneDenied() {
        assertEquals("Keep RECORD_AUDIO denied; this fixture must never open a microphone", PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
    }
    private Map<String, String> captureFiles() {
        Map<String, String> files = new HashMap<>();
        File[] entries = new File(context.getFilesDir(), "audio-captures").listFiles();
        if (entries != null) for (File file : entries) files.put(file.getName(), file.length() + ":" + file.lastModified());
        return files;
    }
    private interface Checked { void run() throws Exception; }
    private void expectOwnerFailure(String code, Checked action) throws Exception {
        try { action.run(); fail("Expected native ownership refusal: " + code); }
        catch (VoiceSessionOwnership.Failure failure) { assertEquals(code, failure.code); }
    }
}
