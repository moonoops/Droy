package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import static org.junit.Assert.*;

/** Public speech through the real DSH queue/local model. Continuous input replays ASR events;
 * hold input replaces only getUserMedia with a pinned WAV stream. No hardware microphone or cloud model. */
@RunWith(AndroidJUnit4.class)
public final class VoiceConversationDeviceTest {
    private static final String SHA256 = "b77f1794fe374a0ba1ee1dc458bfaf9349496cbbfc32780c50ba3c5a7ad8e373";
    private static final String OWNER = "voice-public-conversation-fixture";
    private static final String REPLY = "Public voice fixture received!";
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences product, wake;
    private Map<String, ?> savedProduct, savedWake;
    private MainActivity activity;
    private DeviceUi ui;
    private File home, preserved;
    private Map<String, String> originalFiles;
    private boolean owned, homeOwned, hadHome;
    private String transcriptId, offlineVoice;
    private JSONObject utterance;

    @Before public void prepare() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        assertTrue("Voice conversation fixture requires a dedicated emulator", Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone"));
        assertTrue("Stop tasks and the engine before this fixture", RuntimeService.canManageHistory());
        assertTrue("Do not interrupt a recording", AudioCapture.get(context).uiStatus().isNull("active"));
        awaitBundledRecognizer();
        assertFalse("Do not interrupt a speech job", AudioTranscription.get(context).hasActiveJobs());
        assertFalse("Do not interrupt dictation", AudioCapture.get(context).dictationActive());
        assertFalse("Do not interrupt wake or enrollment", VoiceWake.hasActiveWork(context));
        product = ProductPermissions.preferences(context);
        wake = context.getSharedPreferences("droy-voice-wake", Context.MODE_PRIVATE);
        savedProduct = new HashMap<>(product.getAll()); savedWake = new HashMap<>(wake.getAll()); owned = true;
        assertTrue(product.edit().putBoolean(ProductPermissions.STOPPED, false).putBoolean(BackgroundRuntime.STOPPED, false)
            .putBoolean("allowAudioMicrophone", false).putBoolean("allowAudioTranscription", true)
            .putBoolean("backgroundRuntimeEnabled", false).putBoolean("petEnabled", false).commit());
        assertTrue(wake.edit().putBoolean("enabled", false).commit());
        VoiceMedia.get(context).request(new JSONObject().put("operation", "dispose"));
        utterance = transcribePublicFile();
        offlineVoice = offlineVoice();

        RuntimeInstaller.installCertificate(context); RuntimeInstaller.install(context);
        home = new File(context.getFilesDir(), "dsh-home");
        preserved = new File(context.getFilesDir(), "voice-conversation-preserved-" + UUID.randomUUID());
        originalFiles = snapshot(home.toPath()); hadHome = home.exists();
        if (hadHome) assertTrue("Could not preserve the original DSH home", home.renameTo(preserved));
        homeOwned = true;
        File profile = new File(home, "profiles/mobile"); assertTrue(profile.mkdirs());
        File provider = new File(home, "voice-conversation-provider.mjs");
        try (InputStream source = instrumentation.getContext().getAssets().open("voice-conversation-provider.mjs")) {
            Files.copy(source, provider.toPath());
        }
        JSONArray patch = new JSONArray().put(new JSONObject().put("id", "agent-default-model")
            .put("config", new JSONObject().put("provider", "droy-voice-fixture").put("model", "public-fixture")))
            .put(new JSONObject().put("insert", new JSONArray().put(new JSONObject().put("id", "voice-conversation-fixture")
                .put("name", provider.getAbsolutePath()))));
        Files.write(new File(profile, "cordis.patch.yml").toPath(), patch.toString().getBytes(StandardCharsets.UTF_8));
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity); ui.awaitPage();
        DeviceUi.await("Local scripted provider did not initialize", 15000,
            () -> new File(home, "voice-conversation-ready.json").isFile());
        DeviceUi.await("Voice controller did not initialize", 15000,
            () -> ui.truth("!!window.DroyVoice?.getSnapshot().config"));
        ui.evaluate("(()=>{for(const heading of document.querySelectorAll('[role=dialog] h2')){"
            + "if(!['开始使用','Get started'].includes(heading.textContent.trim()))continue;"
            + "heading.closest('[role=dialog]').querySelector('button[aria-label=关闭],button[aria-label=Close]')?.click()}})()");
        ui.evaluate("window.__voiceConfigSaved=false;window.DroyVoice.saveConfig({tts:{provider:'system',voiceId:"
            + JSONObject.quote(offlineVoice) + ",rate:1}}).then(()=>window.__voiceConfigSaved=true)");
        DeviceUi.await("Offline TTS selection was not saved", 10000, () -> ui.truth("window.__voiceConfigSaved"));
        DeviceUi.await("The shared DSH provider did not observe offline readiness", 15000,
            () -> ui.truth("!!document.querySelector('[data-droy-voice-activity][data-droy-voice-recognizer-state=ready]')"));
        installInputReplay();
    }

    @Test public void publicUtteranceIsQueuedOnceAndItsRealDshReplyReachesNativeTts() throws Exception {
        ui.tap("[data-droy-voice-start=standard]");
        DeviceUi.await("Voice controls did not become active", 10000,
            () -> ui.truth("window.DroyVoice.getSnapshot().status==='active'&&!!document.querySelector('[data-droy-voice-speaker]')"));
        assertFalse("Readout must default to off", ui.truth("window.DroyVoice.getSnapshot().readAloud"));
        ui.tap("[data-droy-voice-speaker]");
        DeviceUi.await("The real speaker button did not enable readout", 5000,
            () -> ui.truth("window.DroyVoice.getSnapshot().readAloud"));
        ui.evaluate("window.__voiceConversation.armed=true");
        DeviceUi.await("Public ASR utterance did not reach DSH", 20000,
            () -> !modelEvents("submission").isEmpty());
        DeviceUi.await("The real DSH reply was not observed by Voice", 20000,
            () -> REPLY.equals(ui.evaluate("window.DroyVoice.getSnapshot().reply")));
        DeviceUi.await("The real reply never called native TTS", 15000,
            () -> ui.truth("window.__voiceConversation.speech.length===1&&window.__voiceConversation.speech[0].ok===true"));
        DeviceUi.await("Native TTS did not complete its offline utterance", 20000,
            () -> ui.truth("window.__voiceConversation.polls>=3&&window.__voiceConversation.nativeStatus"
                + "&&!window.__voiceConversation.nativeStatus.speaking&&!window.__voiceConversation.nativeStatus.error"));
        List<JSONObject> submissions = modelEvents("submission"), calls = modelEvents("model");
        assertEquals("A repeated ASR delivery submitted the same utterance twice", 1, submissions.size());
        assertEquals("queue", submissions.get(0).getString("mode"));
        assertEquals(utterance.getString("text"), submissions.get(0).getString("text"));
        assertFalse("The local LLM adapter was never invoked", calls.isEmpty());
        assertTrue(calls.stream().anyMatch(call -> call.optString("input").contains(utterance.optString("text"))));
        assertEquals("Voice reported an error", JSONObject.NULL, ui.evaluate("window.DroyVoice.getSnapshot().error"));
        assertEquals("The reply was cancelled instead of completing", 0, ((Number) ui.evaluate("window.__voiceConversation.stopRequests")).intValue());
        assertTrue("The controller's input fixture opened a hardware microphone", AudioCapture.get(context).uiStatus().isNull("active"));
        assertFalse(AudioCapture.get(context).dictationActive());
        JSONObject speech = ((JSONArray) ui.evaluate("window.__voiceConversation.speech")).getJSONObject(0);
        assertEquals(REPLY, speech.getString("text")); assertTrue(speech.getBoolean("ok"));
        JSONObject evidence = new JSONObject().put("publicWavSha256", SHA256).put("asr", utterance)
            .put("inputStartPollMuteReplayed", true).put("readAloudDefaultOff", true).put("speakerEnabledByTouch", true).put("hardwareMicrophone", false).put("cloudModel", false)
            .put("bundledAsrInitializedAutomatically", true).put("manualRecognitionSetup", false)
            .put("queuedSubmissions", submissions.size()).put("scriptedReply", REPLY)
            .put("nativeTts", speech).put("nativeTtsFinished", true);
        File directory = new File(context.getExternalFilesDir(null), "voice-conversation-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File result = new File(directory, "public-wav-roundtrip.json");
        Files.write(result.toPath(), evidence.toString(2).getBytes(StandardCharsets.UTF_8));
        android.os.Bundle status = new android.os.Bundle();
        status.putString("stream", "\nDROY_VOICE_CONVERSATION_EVIDENCE " + result.getAbsolutePath() + "\n");
        instrumentation.sendStatus(2, status);
    }

    @Test public void heldPublicAudioUsesDshRecorderAndAutoSendsOnceOnRelease() throws Exception {
        // The continuous-input replay is not involved in this path. Only the
        // microphone source is replaced; recording, Remote, ASR and queue are real.
        ui.evaluate("window.__voiceConversation.cleanup()");
        assertTrue(product.edit().putBoolean("allowAudioMicrophone", true).commit());
        File source = AudioTranscription.controlledFile(context.getFilesDir(), context.getCacheDir(),
            InstrumentationRegistry.getArguments().getString("audioAsrFixture", ""));
        assertEquals("Only the pinned public WAV is accepted", SHA256, hash(source.toPath()));
        String base64 = android.util.Base64.encodeToString(Files.readAllBytes(source.toPath()), android.util.Base64.NO_WRAP);
        ui.evaluate("(()=>{const devices=navigator.mediaDevices,original=devices.getUserMedia;"
            + "const state=window.__voiceHoldAudio={ready:false,started:false,ended:false,calls:0};"
            + "state.context=new AudioContext();state.context.suspend();"
            + "const bytes=Uint8Array.from(atob(" + JSONObject.quote(base64) + "),c=>c.charCodeAt(0));"
            + "state.context.decodeAudioData(bytes.buffer).then(buffer=>{state.buffer=buffer;state.duration=buffer.duration;state.ready=true},"
            + "error=>state.error=error.name+':'+error.message);"
            + "devices.getUserMedia=async constraints=>{if(!constraints?.audio)return original.call(devices,constraints);"
            + "state.calls++;if(!state.ready)throw Error('Public audio fixture has not decoded');"
            + "state.destination=state.context.createMediaStreamDestination();state.source=state.context.createBufferSource();"
            + "state.source.buffer=state.buffer;state.source.connect(state.destination);state.source.onended=()=>state.ended=true;"
            + "await state.context.resume();state.source.start(state.context.currentTime+0.15);state.started=true;return state.destination.stream};"
            + "state.cleanup=()=>{devices.getUserMedia=original;try{state.source?.stop()}catch{}"
            + "state.destination?.stream.getTracks().forEach(track=>track.stop());state.context.close()};})()");
        DeviceUi.await("Public WebAudio fixture did not decode", 10000,
            () -> ui.truth("window.__voiceHoldAudio.ready===true"));
        ui.hideKeyboard();
        assertFalse("Hold readout must stay off", ui.truth("window.DroyVoice.getSnapshot().readAloud"));
        final Throwable[] pointerFailure = {null};
        Thread finger = new Thread(() -> {
            try { ui.longPress("[data-droy-voice-hold]", 7000); }
            catch (Throwable failure) { pointerFailure[0] = failure; }
        }, "public-voice-hold-pointer");
        finger.start();
        try {
            DeviceUi.await("Real DSH recording did not start on the public input", 10000,
                () -> ui.truth("window.__voiceHoldAudio.calls===1&&window.__voiceHoldAudio.started&&"
                    + "window.DroyVoice.getSnapshot().status==='recording'&&!!document.querySelector('[data-droy-voice-hold-overlay]')"));
            assertTrue("Holding submitted before pointer release", modelEvents("submission").isEmpty());
            DeviceUi.await("The public WAV did not finish during the held recording", 10000,
                () -> ui.truth("window.__voiceHoldAudio.ended===true"));
            assertTrue("The hold ended before the complete fixture was delivered", finger.isAlive());
            assertTrue("Audio completion submitted without releasing the button", modelEvents("submission").isEmpty());
        } finally {
            finger.join(15000);
        }
        assertFalse("The real hold pointer did not release", finger.isAlive());
        if (pointerFailure[0] != null) throw new AssertionError("Hold pointer failed", pointerFailure[0]);
        DeviceUi.await("Hold transcription did not automatically reach the real DSH queue", 30000,
            () -> !modelEvents("submission").isEmpty());
        DeviceUi.await("The scripted DSH model did not receive the held utterance", 20000,
            () -> !modelEvents("model").isEmpty());
        DeviceUi.await("The actual DSH reply did not render", 10000,
            () -> ui.truth("document.body.textContent.includes(" + JSONObject.quote(REPLY) + ")"));
        DeviceUi.await("Hold recording/transcription did not settle", 10000,
            () -> ui.truth("!window.DroyVoice.getSnapshot().mode&&!document.querySelector('[data-droy-voice-hold-overlay]')"));
        List<JSONObject> submissions = modelEvents("submission"), calls = modelEvents("model");
        assertEquals("Release sent the held utterance more than once", 1, submissions.size());
        assertEquals("queue", submissions.get(0).getString("mode"));
        String recognized = submissions.get(0).getString("text");
        assertTrue("The DSH queue did not receive recognized public audio: " + recognized,
            recognized.replaceAll("\\s+", "").contains("下午5点"));
        assertTrue(calls.stream().anyMatch(call -> call.optString("input").contains(recognized)));
        assertEquals("Hold reported an error", JSONObject.NULL, ui.evaluate("window.DroyVoice.getSnapshot().error"));
        assertEquals(1, ((Number) ui.evaluate("window.__voiceHoldAudio.calls")).intValue());
        JSONObject job = latestHeldTranscript();
        assertNotEquals("The hold did not invoke a new native ASR job", transcriptId, job.getString("transcriptId"));
        assertEquals("completed", job.getString("state"));
        assertTrue(job.getBoolean("finished"));
        assertTrue("Native ASR did not receive the audible fixture", job.getDouble("audioSeconds") > 4);
        assertTrue("Native ASR and the queue have different transcripts", job.getString("text").replaceAll("\\s+", "").contains("下午5点"));
        assertTrue("Synthetic fixture opened a hardware microphone", AudioCapture.get(context).uiStatus().isNull("active"));
        assertFalse("Synthetic fixture claimed the hardware dictation lease", AudioCapture.get(context).dictationActive());
        assertTrue("Recording disposal left input tracks alive", ui.truth("window.__voiceHoldAudio.destination.stream.getTracks().every(track=>track.readyState==='ended')"));
        JSONObject evidence = new JSONObject().put("publicWavSha256", SHA256).put("microphoneSource", "public_web_audio_stream")
            .put("hardwareMicrophone", false).put("cloudModel", false).put("holdDurationMs", 7000)
            .put("recordingRemoteAndNativeAsrReplayed", false).put("submittedBeforeRelease", false)
            .put("queuedSubmissions", submissions.size()).put("recognizedText", recognized)
            .put("nativeJob", job).put("readAloudDefaultOff", true).put("inputTracksReleased", true);
        File directory = new File(context.getExternalFilesDir(null), "voice-conversation-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File result = new File(directory, "public-hold-roundtrip.json");
        Files.write(result.toPath(), evidence.toString(2).getBytes(StandardCharsets.UTF_8));
        android.os.Bundle status = new android.os.Bundle();
        status.putString("stream", "\nDROY_VOICE_HOLD_EVIDENCE " + result.getAbsolutePath() + "\n");
        instrumentation.sendStatus(2, status);
    }

    private JSONObject latestHeldTranscript() throws Exception {
        AudioTranscription asr = AudioTranscription.get(context);
        java.lang.reflect.Field latest = AudioTranscription.class.getDeclaredField("latest");
        latest.setAccessible(true);
        Object job = latest.get(asr);
        assertNotNull("Hold did not create a native transcription", job);
        java.lang.reflect.Field owner = job.getClass().getDeclaredField("owner");
        java.lang.reflect.Field id = job.getClass().getDeclaredField("id");
        owner.setAccessible(true); id.setAccessible(true);
        assertEquals("Hold used an unexpected native owner", "droy-input-dictation", owner.get(job));
        // Internal jobs are deliberately absent from settings. Inspect identity
        // only, then obtain the real result through the owner-checked endpoint.
        return asr.request(new JSONObject().put("operation", "transcript").put("transcriptId", (String) id.get(job))
            .put("cursor", 0).put("full", true), "droy-input-dictation");
    }

    private JSONObject transcribePublicFile() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("audioAsrFixture", "");
        assertFalse("Pass audioAsrFixture as the pinned public zh.wav app-private path", path.isEmpty());
        File source = AudioTranscription.controlledFile(context.getFilesDir(), context.getCacheDir(), path);
        assertEquals("Only the public SenseVoice zh.wav is accepted", SHA256, hash(source.toPath()));
        AudioTranscription asr = AudioTranscription.get(context);
        // Bundled initialization, not a setup button or out-of-band model copy, supplies the recognizer.
        awaitBundledRecognizer();
        transcriptId = asr.request(new JSONObject().put("operation", "transcribe").put("inputPath", source.getAbsolutePath())
            .put("language", "zh"), OWNER).getString("transcriptId");
        JSONObject[] result = {null};
        DeviceUi.await("Public local ASR did not finish", 120000, () -> {
            result[0] = asr.request(new JSONObject().put("operation", "transcript").put("transcriptId", transcriptId)
                .put("cursor", 0).put("full", true), OWNER);
            return result[0].optBoolean("finished");
        });
        assertEquals("Public ASR failed: " + result[0], "completed", result[0].getString("state"));
        String expected = InstrumentationRegistry.getArguments().getString("audioExpectedText", "下午5点");
        Map<String, JSONArray> groups = new java.util.LinkedHashMap<>();
        JSONArray segments = result[0].getJSONArray("segments");
        for (int index = 0; index < segments.length(); index++) {
            JSONObject segment = segments.getJSONObject(index);
            assertTrue("ASR omitted its real utterance identity", segment.has("utteranceId"));
            groups.computeIfAbsent(segment.getString("utteranceId"), ignored -> new JSONArray()).put(segment);
        }
        for (JSONArray group : groups.values()) {
            StringBuilder text = new StringBuilder();
            for (int index = 0; index < group.length(); index++) text.append(group.getJSONObject(index).getString("text"));
            if (!text.toString().replaceAll("\\s+", "").contains(expected)) continue;
            assertTrue("Selected public utterance has no completion boundary", group.getJSONObject(group.length() - 1).getBoolean("endOfUtterance"));
            return new JSONObject().put("text", text.toString()).put("segments", group).put("transcriptId", transcriptId)
                .put("audioSeconds", result[0].getDouble("audioSeconds")).put("inferenceSeconds", result[0].getDouble("inferenceSeconds"));
        }
        throw new AssertionError("The public utterance was not recognized: " + result[0].optString("text"));
    }

    private void awaitBundledRecognizer() throws Exception {
        AudioTranscription asr = AudioTranscription.get(context);
        DeviceUi.await("Bundled SenseVoice did not initialize automatically", 120000, () -> {
            JSONObject progress = asr.uiStatus();
            assertFalse("Offline SenseVoice initialization failed: " + progress, "failed".equals(progress.optString("state")));
            return progress.optBoolean("ready");
        });
    }

    private String offlineVoice() throws Exception {
        JSONObject[] catalogue = {null};
        DeviceUi.await("The system TTS engine never initialized", 12000, () -> {
            catalogue[0] = VoiceMedia.get(context).request(new JSONObject().put("operation", "voices"));
            return catalogue[0].optBoolean("systemReady") || !catalogue[0].isNull("systemError");
        });
        assertTrue("Install an offline system TTS engine before this acceptance test: " + catalogue[0], catalogue[0].getBoolean("systemReady"));
        JSONArray voices = catalogue[0].getJSONArray("voices"); String fallback = null;
        for (int index = 0; index < voices.length(); index++) {
            JSONObject voice = voices.getJSONObject(index);
            if (!voice.optString("provider").equals("system") || voice.getBoolean("networkRequired")) continue;
            if (fallback == null) fallback = voice.getString("id");
            if (voice.getString("language").startsWith("en")) return voice.getString("id");
        }
        assertNotNull("Install an offline TTS voice; this test cannot send text to a cloud TTS service", fallback);
        return fallback;
    }

    private void installInputReplay() throws Exception {
        ui.evaluate("(()=>{const original=window.fetch.bind(window),input=" + utterance + ";"
            + "const state=window.__voiceConversation={polls:0,deliveries:0,armed:false,muted:false,speech:[],nativeStatus:null,stopRequests:0};"
            + "const json=value=>new Response(JSON.stringify({ok:true,result:value}),{status:200,headers:{'Content-Type':'application/json'}});"
            + "window.fetch=async(resource,options)=>{let body;try{body=JSON.parse(options?.body||'null')}catch{}"
            + "if(typeof resource==='string'&&resource==='/droy/voice'&&body){"
            + "if(body.operation==='start')return json({state:'recording',captureId:'public-file-input-fixture'});"
            + "if(body.operation==='mute'){state.muted=body.muted;return json({state:'recording',muted:state.muted,capturePositionMs:0});}"
            + "if(body.operation==='poll'){state.polls++;const response=await original('/droy/voice',{credentials:'same-origin',method:'POST',"
            + "headers:{'Content-Type':'application/json'},body:JSON.stringify({operation:'status'})});const envelope=await response.json();"
            + "if(!response.ok||!envelope.ok)throw Error('Native playback status failed');state.nativeStatus=envelope.result;"
            + "return json({state:'recording',captureId:'public-file-input-fixture',sampleRate:48000,nextCursor:0,level:0,speechActive:false,"
            + "speaking:!!state.nativeStatus.speaking,transcriptId:input.transcriptId,nextTranscriptCursor:input.segments.length,"
            + "segments:state.armed&&!state.muted&&state.deliveries++<2?input.segments:[],finished:false});}"
            + "if(body.operation==='speak'){const response=await original(resource,options),envelope=await response.clone().json();"
            + "state.speech.push({text:body.text,ok:response.ok&&envelope.ok===true,voiceId:body.voiceId,utteranceId:body.utteranceId});return response;}"
            + "if(body.operation==='stop_speaking')state.stopRequests++;}"
            + "return original(resource,options)};state.cleanup=()=>window.fetch=original;})()");
    }

    private List<JSONObject> modelEvents(String event) throws Exception {
        List<JSONObject> rows = new ArrayList<>(); File file = new File(home, "voice-conversation-events.jsonl");
        if (!file.isFile()) return rows;
        String log = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        int complete = log.lastIndexOf('\n');
        if (complete < 0) return rows;
        for (String line : log.substring(0, complete).split("\\R")) {
            if (line.isBlank()) continue;
            JSONObject row = new JSONObject(line); if (event.equals(row.optString("event"))) rows.add(row);
        }
        return rows;
    }

    @After public void cleanup() throws Exception {
        if (!owned) return;
        try {
            if (ui != null && activity != null && !activity.isDestroyed()) {
                ui.evaluate("window.__voiceEnded=false;window.DroyVoice.end().finally(()=>{window.__voiceConversation?.cleanup?.();window.__voiceHoldAudio?.cleanup?.();window.__voiceEnded=true})");
                DeviceUi.await("Voice controller did not end", 10000, () -> ui.truth("window.__voiceEnded"));
            }
        } finally {
            try {
                if (activity != null && !activity.isDestroyed()) instrumentation.runOnMainSync(activity::finish);
                if (homeOwned) {
                    instrumentation.runOnMainSync(() -> RuntimeService.requestStop(context));
                    DeviceUi.await("The isolated fixture engine did not stop", 20000, RuntimeService::canManageHistory);
                }
                if (transcriptId != null) AudioTranscription.get(context).request(new JSONObject().put("operation", "stop_transcription")
                    .put("transcriptId", transcriptId), OWNER);
                VoiceMedia.get(context).request(new JSONObject().put("operation", "dispose"));
                assertTrue("Fixture opened an audio input", AudioCapture.get(context).uiStatus().isNull("active"));
                if (homeOwned) {
                    HistoryData.deleteTree(home.toPath());
                    if (hadHome) assertTrue("Could not restore original DSH home", preserved.renameTo(home));
                    assertEquals("Original DSH data changed", originalFiles, snapshot(home.toPath()));
                    homeOwned = false;
                }
            } finally { restore(product, savedProduct); restore(wake, savedWake); }
        }
    }

    private static void restore(SharedPreferences preferences, Map<String, ?> values) {
        if (preferences == null || values == null) return;
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof Boolean b) editor.putBoolean(key, b);
            else if (value instanceof String s) editor.putString(key, s);
            else if (value instanceof Integer n) editor.putInt(key, n);
            else if (value instanceof Long n) editor.putLong(key, n);
            else if (value instanceof Float n) editor.putFloat(key, n);
            else if (value instanceof java.util.Set<?> set) {
                java.util.Set<String> copy = new java.util.HashSet<>(); for (Object item : set) copy.add((String) item); editor.putStringSet(key, copy);
            } else throw new AssertionError("Unsupported preference type " + key);
        }
        assertTrue(editor.commit());
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> rows = new TreeMap<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return rows;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                String name = root.relativize(path).toString();
                if (Files.isSymbolicLink(path)) rows.put(name, "link:" + Files.readSymbolicLink(path));
                else if (Files.isDirectory(path)) rows.put(name, "directory");
                else rows.put(name, hash(path));
            }
        }
        return rows;
    }

    private static String hash(Path path) throws Exception {
        return RuntimeInstaller.hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
