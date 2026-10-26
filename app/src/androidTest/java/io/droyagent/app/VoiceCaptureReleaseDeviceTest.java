package io.droyagent.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.*;

/** Uses real pending capture ownership without opening the microphone or projection. */
@RunWith(AndroidJUnit4.class)
public final class VoiceCaptureReleaseDeviceTest {
    private static final String[] KEYS = {ProductPermissions.STOPPED, BackgroundRuntime.STOPPED,
        "allowAudioMicrophone", "allowAudioPlayback"};
    private final Map<String, Boolean> saved = new HashMap<>();
    private final List<String> captures = new ArrayList<>();
    private SharedPreferences product;
    private AudioCapture capture;
    private VoiceWake wake;
    private boolean owned, wasSuspended;

    @Before public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Wake resource fixtures are emulator-only", Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone"));
        assertTrue("Stop the engine and tasks before this fixture", RuntimeService.canManageHistory());
        capture = AudioCapture.get(context);
        assertTrue("Do not interrupt an existing recording", capture.uiStatus().isNull("active"));
        assertFalse("Do not interrupt dictation", capture.dictationActive());
        assertFalse("Do not interrupt active wake or enrollment", VoiceWake.hasActiveWork(context));
        wake = VoiceWake.get(context);
        JSONObject original = wake.request(new JSONObject().put("operation", "wake.status"));
        assertFalse("Run this fixture with wake disabled", original.getBoolean("enabled"));
        wasSuspended = original.getBoolean("suspended");
        product = ProductPermissions.preferences(context);
        for (String key : KEYS) if (product.contains(key)) saved.put(key, product.getBoolean(key, false));
        owned = true;
        VoiceMedia.stopIfCreated("fixture_setup");
        product.edit().putBoolean(ProductPermissions.STOPPED, false).putBoolean(BackgroundRuntime.STOPPED, false)
            .putBoolean("allowAudioMicrophone", true).putBoolean("allowAudioPlayback", true).commit();
        wake.request(new JSONObject().put("operation", "wake.resume"));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @After public void tearDown() throws Exception {
        if (!owned) return;
        try {
            wake.request(new JSONObject().put("operation", "wake.suspend"));
            capture.releaseDictation();
            for (String id : captures) { capture.stopFromUi(id); capture.deleteFromUi(id); }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        } finally {
            SharedPreferences.Editor restore = product.edit();
            for (String key : KEYS) {
                if (saved.containsKey(key)) restore.putBoolean(key, saved.get(key)); else restore.remove(key);
            }
            assertTrue(restore.commit());
            if (!wasSuspended) wake.request(new JSONObject().put("operation", "wake.resume"));
        }
    }

    @Test public void independentCaptureStopResumesWakeOnlyAfterActualOwnershipRelease() throws Exception {
        wake.pauseForVoice();
        String id = pending("fixture-independent-recording");
        assertTrue(paused());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                capture.stop(id, "fixture-independent-recording");
                assertTrue(capture.uiStatus().isNull("active"));
                assertTrue("Wake resumed inline while the capture operation still held its monitor", paused());
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        DeviceUi.await("Wake did not observe the released capture", 2000, () -> !paused());
        assertTrue(capture.uiStatus().isNull("active"));
    }

    @Test public void wakeAndEnrollmentCaptureReleaseDoNotUndoAnExplicitPause() throws Exception {
        for (String owner : new String[]{"droy-voice-wake", "droy-voice-enrollment"}) {
            wake.pauseForVoice();
            String id = pending(owner);
            capture.stop(id, owner);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue("Own capture release revived wake during its pause", paused());
        }
    }

    @Test public void failedCaptureStartAlsoReleasesItsMicrophoneOwnership() throws Exception {
        wake.pauseForVoice();
        String id = pending("fixture-start-failure");
        capture.serviceFailed(id, "fixture_start_failed");
        assertEquals("failed", capture.status(id, "fixture-start-failure").getString("state"));
        assertTrue(capture.uiStatus().isNull("active"));
        DeviceUi.await("Failed capture start left wake paused", 2000, () -> !paused());
    }

    @Test public void dictationReleaseNotifiesOnceAndAnUnownedReleaseDoesNotResumeWake() throws Exception {
        wake.pauseForVoice(); capture.claimDictation();
        capture.releaseDictation();
        DeviceUi.await("Wake did not observe dictation releasing the input", 2000, () -> !paused());
        wake.pauseForVoice(); capture.releaseDictation();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue("Repeated release without ownership resumed wake", paused());
    }

    @Test public void pluginSuspensionPreventsReleaseFromResumingWake() throws Exception {
        wake.pauseForVoice();
        String id = pending("fixture-independent-recording");
        wake.request(new JSONObject().put("operation", "wake.suspend"));
        capture.stop(id, "fixture-independent-recording");
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue(paused());
        assertTrue(wake.request(new JSONObject().put("operation", "wake.status")).getBoolean("suspended"));
    }

    @Test public void legacyRealtimeCannotStartThroughNativeVoiceBridge() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try {
            VoiceRuntime.request(context, new JSONObject().put("operation", "start").put("mode", "realtime")
                .put("sessionId", "fixture-hidden-realtime"));
            fail("Hidden realtime mode reached native capture");
        } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("暂未开放")); }
        assertTrue(capture.uiStatus().isNull("active"));
        assertFalse(wake.request(new JSONObject().put("operation", "wake.status")).getBoolean("voiceClaimed"));
    }

    @Test public void conversationClaimSurvivesCaptureReleaseAndLifecycleResume() throws Exception {
        wake.request(new JSONObject().put("operation", "wake.claim"));
        try {
            String id = pending("fixture-voice-pause");
            capture.stop(id, "fixture-voice-pause");
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            wake.resumeIfEnabled();
            JSONObject status = wake.request(new JSONObject().put("operation", "wake.status"));
            assertTrue("Temporary microphone release lost the conversation claim", status.getBoolean("voiceClaimed"));
            assertTrue(status.getBoolean("pausedForVoice"));
            assertTrue(capture.uiStatus().isNull("active"));
            java.lang.reflect.Field event = VoiceWake.class.getDeclaredField("pendingEventId");
            event.setAccessible(true); event.setLong(wake, 9003L);
        } finally { wake.request(new JSONObject().put("operation", "wake.release")); }
        JSONObject ended = wake.request(new JSONObject().put("operation", "wake.status"));
        assertFalse(ended.getBoolean("voiceClaimed"));
        assertEquals("An ignored keyword from before the manual conversation replayed on exit", 0, ended.getLong("eventId"));
    }

    @Test public void screenOffInvalidatesPendingWakeAndUnlockDoesNotReplayIt() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.app.KeyguardManager keyguard = context.getSystemService(android.app.KeyguardManager.class);
        assertFalse("Use the dedicated emulator without a secure keyguard", keyguard.isDeviceSecure());
        shellInput("input keyevent KEYCODE_WAKEUP"); shellInput("wm dismiss-keyguard");
        DeviceUi.await("Emulator did not become unlocked", 3000, () -> VoiceWake.screenAvailable(context));
        wake.request(new JSONObject().put("operation", "wake.claim"));
        android.content.SharedPreferences wakePrefs = context.getSharedPreferences("droy-voice-wake", Context.MODE_PRIVATE);
        boolean hadEnabled = wakePrefs.contains("enabled");
        try {
            assertTrue(wakePrefs.edit().putBoolean("enabled", true).commit());
            // A queued native event substitutes only for keyword inference in this lifecycle test.
            java.lang.reflect.Field event = VoiceWake.class.getDeclaredField("pendingEventId");
            event.setAccessible(true); event.setLong(wake, 9001L);
            assertTrue(wake.validWakeEvent(9001L));
            assertTrue(wake.request(new JSONObject().put("operation", "wake.consume").put("eventId", 9001L)).getBoolean("consumed"));
            assertEquals(9001L, wake.request(new JSONObject().put("operation", "wake.status")).getLong("startingEventId"));
            event.setLong(wake, 9002L);
            shellInput("input keyevent KEYCODE_SLEEP");
            DeviceUi.await("Screen-off did not invalidate wake", 3000, () -> !VoiceWake.screenAvailable(context)
                && wake.request(new JSONObject().put("operation", "wake.status")).getLong("eventId") == 0);
            assertFalse(wake.request(new JSONObject().put("operation", "wake.consume").put("eventId", 9002L)).getBoolean("consumed"));
            assertEquals(0, wake.request(new JSONObject().put("operation", "wake.status")).getLong("startingEventId"));
            shellInput("input keyevent KEYCODE_WAKEUP"); shellInput("wm dismiss-keyguard");
            DeviceUi.await("Emulator did not return unlocked", 3000, () -> VoiceWake.screenAvailable(context));
            assertEquals(0, wake.request(new JSONObject().put("operation", "wake.status")).getLong("eventId"));
            assertFalse(wake.validWakeEvent(9001L));
            for (String operation : new String[]{"start", "speak", "play"}) {
                try {
                    VoiceRuntime.request(context, new JSONObject().put("operation", operation).put("wakeEventId", 9001L)
                        .put("text", "我在").put("sessionId", "fixture-delayed-wake"));
                    fail("Stale wake continued after unlocking: " + operation);
                } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("本次唤醒已结束")); }
            }
            assertTrue(capture.uiStatus().isNull("active"));
        } finally {
            android.content.SharedPreferences.Editor restore = wakePrefs.edit();
            if (hadEnabled) restore.putBoolean("enabled", false); else restore.remove("enabled");
            assertTrue(restore.commit());
            wake.request(new JSONObject().put("operation", "wake.release"));
            shellInput("input keyevent KEYCODE_WAKEUP"); shellInput("wm dismiss-keyguard");
        }
    }

    @Test public void pendingWakeIsConsumedOnceAndAlwaysTargetsStandard() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Use an unlocked emulator", VoiceWake.screenAvailable(context));
        android.content.SharedPreferences wakePrefs = context.getSharedPreferences("droy-voice-wake", Context.MODE_PRIVATE);
        boolean hadEnabled = wakePrefs.contains("enabled");
        wake.request(new JSONObject().put("operation", "wake.claim"));
        try {
            assertTrue(wakePrefs.edit().putBoolean("enabled", true).commit());
            java.lang.reflect.Field event = VoiceWake.class.getDeclaredField("pendingEventId");
            event.setAccessible(true); event.setLong(wake, 9002L);
            JSONObject consumed = wake.request(new JSONObject().put("operation", "wake.consume").put("eventId", 9002L));
            assertTrue(consumed.getBoolean("consumed")); assertEquals("standard", consumed.getString("target"));
            assertEquals(9002L, wake.request(new JSONObject().put("operation", "wake.status")).getLong("startingEventId"));
            wake.requireStartingEvent(9002L);
            assertFalse(wake.request(new JSONObject().put("operation", "wake.consume").put("eventId", 9002L)).getBoolean("consumed"));
        } finally {
            android.content.SharedPreferences.Editor restore = wakePrefs.edit();
            if (hadEnabled) restore.putBoolean("enabled", false); else restore.remove("enabled");
            assertTrue(restore.commit()); wake.request(new JSONObject().put("operation", "wake.release"));
        }
    }

    private static void shellInput(String command) throws Exception {
        try (java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command))) { input.readAllBytes(); }
    }

    private String pending(String owner) throws Exception {
        JSONObject result = capture.start(new JSONObject().put("source", "playback").put("scope", "system")
            .put("saveFile", false), owner, UUID.randomUUID().toString());
        assertEquals("awaiting_user", result.getString("state"));
        String id = result.getString("captureId"); captures.add(id); return id;
    }

    private boolean paused() throws Exception {
        return wake.request(new JSONObject().put("operation", "wake.status")).getBoolean("pausedForVoice");
    }
}
