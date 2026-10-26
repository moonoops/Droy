package io.droyagent.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real native bridge and silent playback checks; no microphone or model requests. */
@RunWith(AndroidJUnit4.class)
public final class VoiceMediaDeviceTest {
    private static final String TOKEN = "fictional-voice-device-local-bridge-token";
    private static final String[] KEYS = {ProductPermissions.STOPPED, BackgroundRuntime.STOPPED, "allowAudioMicrophone", "allowAudioPlayback"};
    private final Map<String, Boolean> saved = new HashMap<>();
    private Context context;
    private SharedPreferences preferences;
    private ProductBridge bridge;
    private boolean owned;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Voice fixtures require a dedicated emulator", emulator());
        assertTrue("Finish tasks and stop the engine before native Voice tests", RuntimeService.canManageHistory());
        assertTrue("Do not interrupt an existing recording", AudioCapture.get(context).uiStatus().isNull("active"));
        preferences = ProductPermissions.preferences(context);
        for (String key : KEYS) if (preferences.contains(key)) saved.put(key, preferences.getBoolean(key, false));
        // The fixture never needs a microphone, even if a regression starts capture.
        assertTrue(preferences.edit().putBoolean(ProductPermissions.STOPPED, false)
            .putBoolean("allowAudioMicrophone", false).putBoolean("allowAudioPlayback", true)
            .putBoolean(BackgroundRuntime.STOPPED, false).commit());
        owned = true;
        bridge = new ProductBridge(context, TOKEN);
        bridge.policy.enable();
        bridge.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        assertTrue(bridge.getListeningPort() > 0);
    }

    @After public void tearDown() throws Exception {
        if (!owned) return;
        try {
            VoiceRuntime.stop("device_test_finished");
            assertTrue("The fixture opened an audio input", AudioCapture.get(context).uiStatus().isNull("active"));
        } finally {
            if (bridge != null) { bridge.policy.stop(); bridge.stop(); }
            if (preferences != null) {
                SharedPreferences.Editor restore = preferences.edit();
                for (String key : KEYS) {
                    if (saved.containsKey(key)) restore.putBoolean(key, saved.get(key));
                    else restore.remove(key);
                }
                assertTrue(restore.commit());
            }
        }
    }

    @Test public void hiddenRealtimeModeCannotStartThroughNativeBoundary() throws Exception {
        try {
            VoiceMedia.get(context).request(new JSONObject().put("operation", "start")
                .put("mode", "realtime").put("sessionId", "fictional-realtime-session"));
            fail("The hidden mode must be rejected before opening capture");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("普通语音"));
        }
        assertTrue(AudioCapture.get(context).uiStatus().isNull("active"));
    }

    @Test public void voiceEndpointRejectsUnauthenticatedRequestsBeforeDispatch() throws Exception {
        Reply response = post(new JSONObject().put("operation", "voices"), false);
        assertEquals(401, response.status);
        assertFalse(response.body.getBoolean("ok"));
        assertEquals("UNAUTHORIZED", response.body.getJSONObject("error").getString("code"));
        assertFalse(response.body.has("result"));
    }

    @Test public void systemCatalogueReportsActualReadinessAndStopWorksAfterPause() throws Exception {
        JSONObject[] catalogue = {null};
        DeviceUi.await("The TTS catalogue never reported readiness or an engine error", 12000, () -> {
            catalogue[0] = ok(post(new JSONObject().put("operation", "voices"), true));
            return catalogue[0].getBoolean("systemReady") || !catalogue[0].isNull("systemError");
        });
        JSONArray voices = catalogue[0].getJSONArray("voices");
        String defaultId = catalogue[0].getString("defaultVoiceId");
        boolean foundDefault = defaultId.isEmpty();
        for (int i = 0; i < voices.length(); i++) {
            JSONObject voice = voices.getJSONObject(i);
            assertFalse(voice.getString("id").isBlank());
            assertFalse(voice.getString("language").isBlank());
            assertTrue(voice.get("networkRequired") instanceof Boolean);
            if (voice.getString("id").equals(defaultId)) foundDefault = true;
        }
        assertTrue("The default voice is not in the device catalogue", foundDefault);
        if (!catalogue[0].getBoolean("systemReady")) {
            for (int i = 0; i < voices.length(); i++) assertTrue(java.util.Set.of("melo", "qwen3").contains(voices.getJSONObject(i).getString("provider")));
            assertFalse("Unavailable TTS needs a usable explanation", catalogue[0].getString("systemError").isBlank());
        }
        android.os.Bundle result = new android.os.Bundle();
        result.putString("stream", "\nDROY_VOICE_TTS ready=" + catalogue[0].getBoolean("ready")
            + " voices=" + voices.length() + "\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(2, result);

        ProductPermissions.setStopped(context, true);
        Reply speech = post(new JSONObject().put("operation", "speak").put("text", "fixture must never be spoken"), true);
        assertEquals(200, speech.status);
        assertFalse("Paused Voice accepted new speech", speech.body.getBoolean("ok"));
        assertEquals("PERMISSION_DENIED", speech.body.getJSONObject("error").getString("code"));
        JSONObject stopped = ok(post(new JSONObject().put("operation", "stop_speaking"), true));
        assertFalse(stopped.getBoolean("speaking"));
        assertFalse(ok(post(new JSONObject().put("operation", "stop_speaking"), true)).getBoolean("speaking"));
    }

    @Test public void interruptedResponseCannotReenterTheRealPcmPlayer() throws Exception {
        VoiceSpeechOutput output = new VoiceSpeechOutput(context);
        try {
            byte[] silence = new byte[24000 * 2];
            output.clear("old-response");
            output.play(silence, 24000, "old-response");
            assertFalse("A cancelled response was queued again", output.speaking());
            output.play(silence, 24000, "current-response");
            assertTrue("A fresh PCM response was not queued", output.speaking());
            output.clear("current-response");
            assertFalse("Clear left PCM buffered", output.speaking());
            output.play(silence, 24000, "current-response");
            assertFalse("Late PCM resumed after clear", output.speaking());
            output.stop();
            assertFalse(output.speaking());
            assertEquals("", output.error());
        } finally {
            output.close();
        }
    }

    @Test public void endedConversationDoesNotKeepExpiredCaptureReferences() throws Exception {
        AudioCapture capture=AudioCapture.get(context);
        VoiceMedia voice=VoiceMedia.get(context);
        java.util.List<String> created=new java.util.ArrayList<>();
        try {
            for(boolean finishThroughPoll:new boolean[]{false,true}) {
                voice.request(new JSONObject().put("operation","stop"));
                String original=pendingCapture(capture,VoiceMedia.OWNER); created.add(original);
                bindPendingConversation(voice,original);
                JSONObject ended;
                if(finishThroughPoll) {
                    capture.stop(original,VoiceMedia.OWNER);
                    ended=voice.request(new JSONObject().put("operation","poll"));
                } else ended=voice.request(new JSONObject().put("operation","stop"));
                assertEquals("ended",ended.getString("state"));
                assertEquals("",ended.getString("captureId"));
                assertEquals("",ended.getString("transcriptId"));
                for(int i=0;i<6;i++) {
                    String next=pendingCapture(capture,"droy-voice-wake"); created.add(next);
                    capture.stop(next,"droy-voice-wake");
                }
                assertThrows("Fixture must really roll the original capture out of retention",IllegalArgumentException.class,
                    ()->capture.status(original,VoiceMedia.OWNER));
                for(String operation:new String[]{"status","poll","stop_speaking","clear_playback","stop"}) {
                    JSONObject result=voice.request(new JSONObject().put("operation",operation));
                    assertEquals("ended",result.getString("state"));
                    assertEquals("",result.getString("captureId"));
                    assertTrue(result.has("speechError"));
                    assertEquals("Expired capture leaked into playback status: "+result,result.opt("speechError"),result.opt("error"));
                }
            }
        } finally {
            voice.request(new JSONObject().put("operation","stop"));
            deleteRetainedCaptures(capture,created);
        }
    }

    @Test public void endingConversationLeavesFinalizationOwnedByCaptureService() throws Exception {
        AudioCapture capture=AudioCapture.get(context);
        VoiceMedia voice=VoiceMedia.get(context);
        voice.request(new JSONObject().put("operation","stop"));
        String id=pendingCapture(capture,VoiceMedia.OWNER);
        try {
            bindPendingConversation(voice,id);
            java.lang.reflect.Field sessions=AudioCapture.class.getDeclaredField("sessions"); sessions.setAccessible(true);
            Object session=((Map<?,?>)sessions.get(capture)).get(id);
            java.lang.reflect.Field state=session.getClass().getDeclaredField("state"); state.setAccessible(true);
            // Represents the worker's asynchronous release window; no hardware is opened.
            state.set(session,"finalizing");
            JSONObject ended=voice.request(new JSONObject().put("operation","stop"));
            assertEquals("ended",ended.getString("state")); assertEquals("",ended.getString("captureId"));
            assertEquals("finalizing",capture.status(id,VoiceMedia.OWNER).getString("state"));
            assertEquals(id,capture.uiStatus().getJSONObject("active").getString("captureId"));
            assertThrows("Another consumer must wait for the capture worker to release",IllegalStateException.class,
                ()->pendingCapture(capture,"droy-voice-wake"));
            capture.serviceFailed(id,"fixture_finalization_finished");
            assertTrue(capture.uiStatus().isNull("active"));
            assertEquals("ended",voice.request(new JSONObject().put("operation","status")).getString("state"));
        } finally {
            capture.serviceFailed(id,"fixture_cleanup");
            voice.request(new JSONObject().put("operation","stop"));
            capture.deleteFromUi(id);
        }
    }

    private static String pendingCapture(AudioCapture capture,String owner) throws Exception {
        JSONObject pending=capture.start(new JSONObject().put("source","playback").put("scope","system")
            .put("saveFile",false),owner,java.util.UUID.randomUUID().toString());
        assertEquals("awaiting_user",pending.getString("state"));
        return pending.getString("captureId");
    }

    private static void bindPendingConversation(VoiceMedia voice,String id) throws Exception {
        for(Map.Entry<String,String> entry:Map.of("captureId",id,"transcriptId","","state","awaiting_user",
            "sessionId","fixture-ended-voice","error","").entrySet()) {
            java.lang.reflect.Field field=VoiceMedia.class.getDeclaredField(entry.getKey()); field.setAccessible(true);
            field.set(voice,entry.getValue());
        }
    }

    private static void deleteRetainedCaptures(AudioCapture capture,java.util.List<String> ids) throws Exception {
        java.lang.reflect.Field field=AudioCapture.class.getDeclaredField("sessions"); field.setAccessible(true);
        Map<?,?> sessions=(Map<?,?>)field.get(capture);
        for(String id:ids) if(sessions.containsKey(id)) {
            capture.stopFromUi(id); capture.deleteFromUi(id);
        }
    }

    private Reply post(JSONObject data, boolean authenticate) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + bridge.getListeningPort() + "/voice").openConnection();
        try {
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setConnectTimeout(5000); connection.setReadTimeout(10000);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (authenticate) connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
            byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int status = connection.getResponseCode();
            try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                assertNotNull(input);
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                    assertTrue("Voice metadata response is unexpectedly large", out.size() < 1024 * 1024);
                }
                return new Reply(status, new JSONObject(out.toString("UTF-8")));
            }
        } finally { connection.disconnect(); }
    }

    private static JSONObject ok(Reply response) throws Exception {
        assertEquals(200, response.status);
        assertTrue("Native Voice failed: " + response.body, response.body.getBoolean("ok"));
        return response.body.getJSONObject("result");
    }

    private static boolean emulator() {
        return Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")
            || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk_gphone");
    }

    private static final class Reply {
        final int status; final JSONObject body;
        Reply(int status, JSONObject body) { this.status = status; this.body = body; }
    }
}
