package io.droyagent.app;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Pauses only the user's wake listener while synthetic test speech is playing. */
final class TtsPlaybackFixture implements AutoCloseable {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private VoiceWake wake;
    private Map<String, ?> preferences;
    private boolean resumeWake;

    void begin() throws Exception {
        AudioCapture capture = AudioCapture.get(context);
        JSONObject active = capture.uiStatus().optJSONObject("active");
        assertTrue("Do not interrupt an existing recording", active == null || active.optString("owner").equals("droy-voice-wake"));
        assertFalse("Finish an existing voice conversation first", VoiceMedia.hasActiveWork(context));
        assertFalse("Finish an existing dictation first", capture.dictationActive());
        assertFalse("Finish existing speech playback first", VoiceMedia.get(context)
            .request(new JSONObject().put("operation", "status")).optBoolean("speaking"));
        wake = VoiceWake.get(context);
        JSONObject previous = wake.request(new JSONObject().put("operation", "wake.status"));
        assertFalse("Do not consume a pending user wake event", previous.optBoolean("voiceClaimed")
            || previous.optLong("eventId") > 0 || previous.optLong("startingEventId") > 0);
        preferences = new HashMap<>(context.getSharedPreferences("droy-voice-wake", Context.MODE_PRIVATE).getAll());
        if (!previous.optBoolean("suspended") && (previous.optBoolean("enabled") || active != null)) {
            resumeWake = true;
            wake.request(new JSONObject().put("operation", "wake.suspend"));
        }
        DeviceUi.await("Wake listener did not release its own microphone", 15000,
            () -> capture.uiStatus().isNull("active"));
    }

    @Override public void close() throws Exception {
        try {
            if (resumeWake) wake.request(new JSONObject().put("operation", "wake.resume"));
        } finally {
            if (preferences != null) assertEquals("TTS tests changed wake settings", preferences,
                context.getSharedPreferences("droy-voice-wake", Context.MODE_PRIVATE).getAll());
        }
    }
}
