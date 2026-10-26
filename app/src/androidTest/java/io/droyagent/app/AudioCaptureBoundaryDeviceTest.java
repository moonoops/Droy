package io.droyagent.app;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import java.util.UUID;
import static org.junit.Assert.*;

/** Exercises authorization waiting without opening a microphone or capturing another app. */
public final class AudioCaptureBoundaryDeviceTest {
    private static final String OWNER = "fixture-audio-owner";

    @Test public void pendingStartIsIdempotentAndAnotherOwnerCannotReadOrStopIt() throws Exception {
        withCapture((audio, id, requestId) -> {
            assertEquals("awaiting_user", audio.status(id, OWNER).getString("state"));
            JSONObject replay = audio.start(request(), OWNER, requestId);
            assertEquals(id, replay.getString("captureId"));
            assertThrows(SecurityException.class, () -> audio.status(id, "other-owner"));
            assertThrows(SecurityException.class, () -> audio.stop(id, "other-owner"));
            assertThrows(SecurityException.class, () -> audio.readFrames(id, "other-owner", 0, 1024));
            assertEquals(id, audio.pendingAuthorization().getString("captureId"));
        });
    }

    @Test public void cancellingAnUnrelatedCallDoesNotStopThePendingCapture() throws Exception {
        withCapture((audio, id, requestId) -> {
            audio.cancelStart(OWNER, "different-call");
            assertEquals("awaiting_user", audio.status(id, OWNER).getString("state"));
            audio.cancelStart(OWNER, requestId);
            assertEquals("interrupted", audio.status(id, OWNER).getString("state"));
            assertEquals("start_cancelled", audio.status(id, OWNER).getString("reason"));
            assertNull(audio.pendingAuthorization());
        });
    }

    @Test public void stoppingBeforeAuthorizationCreatesNoAudioAndIsRepeatable() throws Exception {
        withCapture((audio, id, requestId) -> {
            JSONObject stopped = audio.stop(id, OWNER);
            assertEquals("completed", stopped.getString("state"));
            assertFalse(stopped.getBoolean("fileReady"));
            assertEquals(0, stopped.getLong("bytes"));
            assertEquals("completed", audio.stop(id, OWNER).getString("state"));
            assertEquals(0, audio.readFrames(id, OWNER, 0, 1024).data.length);
            assertTrue(audio.readFrames(id, OWNER, 0, 1024).metadata.getBoolean("ended"));
            assertThrows(java.io.IOException.class, () -> audio.fileForUi(id));
        });
    }

    @Test public void revocationEndsPendingCaptureAndCleanupStillWorks() throws Exception {
        withCapture((audio, id, requestId) -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            SharedPreferences settings = ProductPermissions.preferences(context);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> settings.edit().putBoolean("allowAudioPlayback", false).commit());
            assertEquals("permission_revoked", audio.status(id, OWNER).getString("reason"));
            assertThrows(SecurityException.class, () -> audio.readFrames(id, OWNER, 0, 1024));
            audio.stopFromUi(id);
            audio.stop(id, OWNER);
        });
    }

    @Test public void dictationAndNativeCaptureCannotSilentlyStealTheInput() throws Exception {
        withCapture((audio, id, requestId) -> {
            assertThrows(IllegalStateException.class, audio::claimDictation);
            audio.stop(id, OWNER);
            audio.claimDictation();
            try {
                assertTrue(audio.dictationActive());
                assertThrows(IllegalStateException.class, () -> audio.start(request(), OWNER, UUID.randomUUID().toString()));
            } finally { audio.releaseDictation(); }
        });
    }

    @Test public void finishedHistoryRetainsOnlyABoundedNumberOfPcmWindows() throws Exception {
        withCapture((audio, first, requestId) -> {
            java.util.ArrayList<String> ids = new java.util.ArrayList<>();
            audio.stop(first, OWNER);
            try {
                for (int i = 0; i < 10; i++) {
                    String id = audio.start(request(), OWNER, UUID.randomUUID().toString()).getString("captureId");
                    ids.add(id); audio.stop(id, OWNER);
                }
                int retained = 0;
                for (String id : ids) if (audio.status(id, OWNER).getBoolean("streamBufferRetained")) retained++;
                assertEquals(4, retained);
                assertFalse(audio.status(first, OWNER).getBoolean("streamBufferRetained"));
                assertTrue(audio.readFrames(first, OWNER, 0, 1024).metadata.getBoolean("streamReleased"));
            } finally { for (String id : ids) audio.deleteFromUi(id); }
        });
    }

    @Test public void pendingMetadataFailureStillReleasesTheOccupiedSession() throws Exception {
        withCapture((audio, id, requestId) -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            java.io.File blocked = new java.io.File(context.getFilesDir(), "audio-captures/" + id + ".json.tmp");
            assertTrue(blocked.mkdir());
            try {
                JSONObject stopped = audio.stop(id, OWNER);
                assertEquals("metadata_write_failed", stopped.getString("reason"));
                assertFalse(stopped.isNull("metadataError"));
                assertTrue(audio.uiStatus().isNull("active"));
                String next = audio.start(request(), OWNER, UUID.randomUUID().toString()).getString("captureId");
                audio.stop(next, OWNER); audio.deleteFromUi(next);
            } finally { assertTrue(blocked.delete()); }
        });
    }

    private interface Check { void run(AudioCapture audio, String id, String requestId) throws Exception; }

    private static JSONObject request() throws Exception {
        return new JSONObject().put("source", "playback").put("scope", "system").put("saveFile", false);
    }

    private static void withCapture(Check check) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences settings = ProductPermissions.preferences(context);
        boolean hadStopped = settings.contains(ProductPermissions.STOPPED), originalStopped = settings.getBoolean(ProductPermissions.STOPPED, false);
        boolean hadPlayback = settings.contains("allowAudioPlayback"), originalPlayback = settings.getBoolean("allowAudioPlayback", true);
        AudioCapture audio = AudioCapture.get(context);
        assertTrue("Run boundary fixtures with no existing capture", audio.uiStatus().isNull("active"));
        assertFalse("Run boundary fixtures without active dictation", audio.dictationActive());
        String id = null;
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> settings.edit()
                .putBoolean(ProductPermissions.STOPPED, false).putBoolean("allowAudioPlayback", true).commit());
            String requestId = UUID.randomUUID().toString();
            id = audio.start(request(), OWNER, requestId).getString("captureId");
            check.run(audio, id, requestId);
        } finally {
            if (id != null) { audio.stopFromUi(id); audio.deleteFromUi(id); }
            SharedPreferences.Editor restore = settings.edit();
            if (hadStopped) restore.putBoolean(ProductPermissions.STOPPED, originalStopped); else restore.remove(ProductPermissions.STOPPED);
            if (hadPlayback) restore.putBoolean("allowAudioPlayback", originalPlayback); else restore.remove("allowAudioPlayback");
            InstrumentationRegistry.getInstrumentation().runOnMainSync(restore::commit);
        }
    }
}
