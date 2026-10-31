package io.droyagent.app;

import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.WindowInsets;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real voice UI touches in an isolated profile; only the silent-hold case opens the emulator microphone. */
@RunWith(AndroidJUnit4.class)
public final class VoiceUiDeviceTest {
    private static final String SETTINGS = "[data-droy-voice-settings]";
    private static final String DICTATION = "button[aria-label='开始录音'],button[aria-label='打开语音输入引导'],button[aria-label='Start recording'],button[aria-label='Open voice input setup']";
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences preferences, wakePreferences, installationPreferences;
    private Map<String, ?> originalPreferences, originalWakePreferences, originalInstallationPreferences;
    private MainActivity activity;
    private DeviceUi ui;
    private File home, preserved;
    private Map<String, String> originalFiles;
    private boolean owned, hadHome, rotated, passed;
    private File legacySelectionPatch;
    private String legacySelectionText;
    private JSONObject buttonAppearance;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Voice UI fixtures require a dedicated emulator", emulator());
        assertTrue("Finish tasks and stop the engine before Voice UI tests", RuntimeService.canManageHistory());
        assertTrue("Do not interrupt an existing recording", AudioCapture.get(context).uiStatus().isNull("active"));
        UiAutomation automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        preferences = ProductPermissions.preferences(context);
        wakePreferences = context.getSharedPreferences("droy-voice-wake", Context.MODE_PRIVATE);
        installationPreferences = context.getSharedPreferences("installation", Context.MODE_PRIVATE);
        originalPreferences = new HashMap<>(preferences.getAll());
        originalWakePreferences = new HashMap<>(wakePreferences.getAll());
        originalInstallationPreferences = new HashMap<>(installationPreferences.getAll());
        home = new File(context.getFilesDir(), "dsh-home");
        preserved = new File(context.getFilesDir(), "droy-voice-ui-home-backup-" + UUID.randomUUID());
        originalFiles = snapshot(home.toPath());
        hadHome = home.exists();
        if (hadHome) assertTrue("Could not preserve the original DSH home", home.renameTo(preserved));
        owned = true;
        assertTrue(home.mkdirs());
        assertTrue(installationPreferences.edit().putInt("schemaVersion", 1).putBoolean("completed", true).commit());
        String waveLanguage = InstrumentationRegistry.getArguments().getString("voiceWaveLanguage");
        if (waveLanguage != null) assertTrue(preferences.edit().putString("languagePreference", waveLanguage).commit());
        if ("true".equals(InstrumentationRegistry.getArguments().getString("voiceLegacySelection"))) {
            File profile = new File(home, "profiles/mobile"); assertTrue(profile.mkdirs());
            legacySelectionPatch = new File(profile, "cordis.patch.yml");
            legacySelectionText = new org.json.JSONArray().put(new JSONObject().put("id", "speech-to-text")
                .put("config", new JSONObject().put("defaultProvider", "sensevoice-local").put("language", "en"))).toString();
            Files.write(legacySelectionPatch.toPath(), legacySelectionText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertTrue(preferences.edit().putBoolean("petEnabled", false).putString("petExternalDisplay", "off")
            .putString("petInternalDisplay", "off").putBoolean("backgroundRuntimeEnabled", false)
            .putString("petName", "Droy").putBoolean(ProductPermissions.STOPPED, false).putBoolean("allowAudioMicrophone", false).commit());
        assertTrue(wakePreferences.edit().putBoolean("enabled", false).putString("timing", "screen_on")
            .putBoolean("useCompanionName", false).putString("chinese", "你好小黑").putString("english", "Hey Droy").commit());
        assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0));
        rotated = true;
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
        DeviceUi.await("Voice plugin did not initialize in the real DSH profile", 15000,
            () -> ui.truth("!!window.DroyVoice?.getSnapshot().config"));
        assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0));
        DeviceUi.await("Voice UI did not reach the portrait orientation", 5000,
            () -> ui.truth("innerHeight>innerWidth"));
        configureLayout();
        DeviceUi.await("Bundled Android ASR did not become ready automatically", 120000, () -> {
            JSONObject preparation = AudioTranscription.get(context).uiStatus();
            assertFalse("Offline ASR initialization failed: " + preparation, "failed".equals(preparation.optString("state")));
            return preparation.optBoolean("ready");
        });
        DeviceUi.await("The DSH service did not observe the ready Android recognizer", 15000,
            () -> ui.truth("!!document.querySelector('[data-droy-voice-activity][data-droy-voice-recognizer-state=ready]')"));
    }

    @After public void tearDown() throws Exception {
        if (!owned) return;
        try {
            try {
                if (!passed && ui != null && activity != null && !activity.isDestroyed()) capture("failure");
            } finally {
                try {
                    if (activity != null && !activity.isDestroyed()) instrumentation.runOnMainSync(activity::finish);
                } finally {
                    instrumentation.runOnMainSync(() -> RuntimeService.requestStop(context));
                    DeviceUi.await("Voice UI cleanup left an engine running", 20000, RuntimeService::canManageHistory);
                }
            }
            assertTrue("The Voice UI fixture opened a microphone", AudioCapture.get(context).uiStatus().isNull("active"));
            assertNoInference();
            if (legacySelectionPatch != null) assertEquals("Droy overwrote the saved DSH speech selection",
                legacySelectionText, new String(Files.readAllBytes(legacySelectionPatch.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            if (rotated) instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).setRotation(UiAutomation.ROTATION_UNFREEZE);
            assertTrue("Keep the original profile preserved until the runtime stops", RuntimeService.canManageHistory());
            HistoryData.deleteTree(home.toPath());
            if (hadHome) assertTrue("Could not restore the original DSH home", preserved.renameTo(home));
            assertEquals("Voice UI test changed original DSH data", originalFiles, snapshot(home.toPath()));
            restore(preferences, originalPreferences);
            restore(wakePreferences, originalWakePreferences);
            restore(installationPreferences, originalInstallationPreferences);
            owned = false;
        }
    }

    @Test public void waveformMovesThroughSilenceInBothVoiceEntries() throws Exception {
        assertTrue(preferences.edit().putBoolean("allowAudioMicrophone", true).putBoolean("allowAudioTranscription", true).commit());
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .grantRuntimePermission(context.getPackageName(), android.Manifest.permission.RECORD_AUDIO);
        ui.evaluate("(()=>{const original=window.fetch,devices=navigator.mediaDevices,media=devices.getUserMedia;"
            + "const f=window.__waveFixture={level:0,muted:false};window.fetch=async function(url,options){"
            + "if(url==='/droy/voice'&&options?.body){const q=JSON.parse(options.body);let result;"
            + "if(q.operation==='start')result={state:'recording',captureId:'wave-fixture'};"
            + "if(q.operation==='poll')result={state:'recording',captureId:'wave-fixture',segments:[],level:f.muted?0:f.level};"
            + "if(q.operation==='mute'){f.muted=q.muted;result={muted:q.muted}}if(q.operation==='stop')result={state:'ended'};"
            + "if(result)return new Response(JSON.stringify({ok:true,result}),{headers:{'Content-Type':'application/json'}})}return original.apply(this,arguments)};"
            + "devices.getUserMedia=async()=>{f.audio=new AudioContext();f.destination=f.audio.createMediaStreamDestination();"
            + "f.source=f.audio.createConstantSource();f.source.offset.value=0;f.source.connect(f.destination);f.source.start();await f.audio.resume();return f.destination.stream};"
            + "f.cleanup=()=>{window.fetch=original;devices.getUserMedia=media;f.source?.stop();f.destination?.stream.getTracks().forEach(t=>t.stop());f.audio?.close()};})()");
        Thread finger = null;
        final Throwable[] failure = {null};
        try {
            ui.tap("[data-droy-voice-start=standard]");
            DeviceUi.await("Voice dock did not open", 10000,
                () -> ui.truth("window.DroyVoice.getSnapshot().status==='active'&&!!document.querySelector('[data-droy-voice-inline]')"));
            exerciseWavePulse("[data-droy-voice-inline]", false);
            ui.evaluate("window.__waveFixture.level=.04");
            awaitWaveBar("[data-droy-voice-inline]", "lastElementChild");
            ui.tap("[data-droy-voice-mute]");
            awaitFlatWave("[data-droy-voice-inline]");
            capture("wave-muted");
            ui.tap("[data-droy-voice-end]");
            DeviceUi.await("Voice dock did not close", 5000, () -> ui.truth("!window.DroyVoice.getSnapshot().mode"));
            finger = new Thread(() -> {
                try { ui.longPress("[data-droy-voice-hold]", 10000); }
                catch (Throwable error) { failure[0] = error; }
            });
            finger.start();
            DeviceUi.await("Hold recorder did not open", 10000,
                () -> ui.truth("!!document.querySelector('[data-droy-voice-hold-state=recording]')&&!!window.__waveFixture.source"));
            exerciseWavePulse("[data-droy-voice-hold-state=recording]", true);
            ui.evaluate("window.DroyVoice.moveHold(true)");
            finger.join(15000);
            assertFalse("Hold pointer did not release", finger.isAlive());
            if (failure[0] != null) throw new AssertionError("Hold pointer failed", failure[0]);
            DeviceUi.await("Cancelled hold did not close", 10000, () -> ui.truth("!window.DroyVoice.getSnapshot().mode"));
            assertNoInference();
            passed = true;
        } finally {
            ui.evaluate("window.DroyVoice.moveHold(true);window.DroyVoice.cancelHold()");
            if (finger != null) finger.join(15000);
            ui.evaluate("window.DroyVoice.end()");
            DeviceUi.await("Wave fixture did not stop", 10000, () -> ui.truth("!window.DroyVoice.getSnapshot().mode"));
            ui.evaluate("window.__waveFixture.cleanup()");
        }
    }

    private void exerciseWavePulse(String root, boolean hold) throws Exception {
        String prefix = hold ? "hold-wave" : "conversation-wave";
        ui.evaluate(hold ? "window.__waveFixture.source.offset.value=.04" : "window.__waveFixture.level=.04");
        awaitWaveBar(root, "lastElementChild");
        ui.evaluate(hold ? "window.__waveFixture.source.offset.value=0" : "window.__waveFixture.level=0");
        awaitWaveBar(root, "firstElementChild");
        assertLayout(root);
        capture(prefix + "-left-edge");
        awaitFlatWave(root);
        capture(prefix + "-silence");
    }

    private void awaitWaveBar(String root, String edge) throws Exception {
        DeviceUi.await("Sound did not reach " + edge + " of " + root, 8000, () -> ui.truth(
            "(()=>{const w=document.querySelector(" + JSONObject.quote(root + " .droy-voice-wave")
            + ");return !!w&&w." + edge + ".style.transform!=='scaleY(0.06)'})()"));
    }

    private void awaitFlatWave(String root) throws Exception {
        DeviceUi.await("Silence did not clear the trace in " + root, 8000, () -> ui.truth(
            "(()=>{const bars=[...document.querySelectorAll(" + JSONObject.quote(root + " .droy-voice-wave i")
            + ")];return bars.length===24&&bars.every(e=>e.style.transform==='scaleY(0.06)')})()"));
    }

    @Test public void allSettingsTabsRemainDiscoverableAndReachable() throws Exception {
        ui.openPermissions();
        org.json.JSONArray roster = (org.json.JSONArray) ui.evaluate("[...document.querySelectorAll('[data-droy-settings-section]')].map(e=>({id:e.getAttribute('data-droy-settings-section'),label:e.textContent.trim()}))");
        assertTrue("Settings tab roster must include the new Voice tab", roster.length() > 0
            && ui.truth("!!document.querySelector('[data-droy-settings-section=droy-voice]')"));
        org.json.JSONArray reached = new org.json.JSONArray();
        for (int index = 0; index < roster.length(); index++) {
            String id = roster.getJSONObject(index).getString("id");
            String selector = "[data-droy-settings-section=" + JSONObject.quote(id) + "]";
            ui.tap(selector);
            DeviceUi.await("Settings tab did not become selected: " + id, 5000,
                () -> ui.truth("document.querySelector(" + JSONObject.quote(selector) + ").getAttribute('aria-current')==='true'"));
            if (id.equals("droy-voice")) awaitSettings();
            reached.put(settingsTabGeometry(selector));
            capture("settings-tab-" + id);
        }
        ui.tap("[data-droy-settings-section=droy-voice]");
        awaitSettings();
        File directory = new File(context.getExternalFilesDir(null), "voice-ui-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        String label = InstrumentationRegistry.getArguments().getString("voiceEvidenceLabel", "voice").replaceAll("[^A-Za-z0-9_-]", "_");
        File evidence = new File(directory, label + "-settings-tab-roster.json");
        Files.write(evidence.toPath(), reached.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Bundle status = new Bundle();
        status.putString("stream", "\nDROY_SETTINGS_TAB_EVIDENCE " + evidence.getAbsolutePath()
            + "\nDROY_SETTINGS_TABS_REACHED " + reached + "\n");
        instrumentation.sendStatus(2, status);
        passed = true;
    }

    private JSONObject settingsTabGeometry(String selector) throws Exception {
        JSONObject geometry = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
            + "),r=e.getBoundingClientRect();let left=0,right=innerWidth,top=0,bottom=innerHeight;"
            + "for(let p=e.parentElement;p;p=p.parentElement){const s=getComputedStyle(p),b=p.getBoundingClientRect();"
            + "if(/auto|scroll|hidden|clip/.test(s.overflowX)){left=Math.max(left,b.left);right=Math.min(right,b.right);}"
            + "if(/auto|scroll|hidden|clip/.test(s.overflowY)){top=Math.max(top,b.top);bottom=Math.min(bottom,b.bottom);}}"
            + "let fullText=true;const walker=document.createTreeWalker(e,NodeFilter.SHOW_TEXT);while(walker.nextNode()){"
            + "if(!walker.currentNode.textContent.trim())continue;const range=document.createRange();range.selectNodeContents(walker.currentNode);"
            + "for(const b of range.getClientRects())if(b.left<r.left-1||b.right>r.right+1||b.top<r.top-1||b.bottom>r.bottom+1)fullText=false;}"
            + "const points=[[r.left+4,r.top+4],[r.right-4,r.top+4],[r.left+4,r.bottom-4],[r.right-4,r.bottom-4],"
            + "[(r.left+r.right)/2,(r.top+r.bottom)/2]];"
            + "return {id:e.getAttribute('data-droy-settings-section'),label:e.textContent.trim(),selected:e.getAttribute('aria-current')==='true',"
            + "bounds:{left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height},"
            + "clip:{left,right,top,bottom},fullText,wholeTabVisible:r.left>=left-1&&r.right<=right+1&&r.top>=top-1&&r.bottom<=bottom+1,"
            + "clickable:!e.disabled&&!e.closest('[inert]')&&points.every(([x,y])=>{const hit=document.elementFromPoint(x,y);return hit===e||e.contains(hit);})}})()");
        assertTrue("Selected settings tab is clipped: " + geometry, geometry.getBoolean("wholeTabVisible"));
        assertTrue("Selected settings tab text is clipped: " + geometry, geometry.getBoolean("fullText"));
        assertTrue("Selected settings tab has an obscured touch area: " + geometry, geometry.getBoolean("clickable"));
        assertTrue("Settings tab lost its selected state: " + geometry, geometry.getBoolean("selected"));
        return geometry;
    }

    @Test public void refinedVoiceSettingsAndControlsUseRealTouches() throws Exception {
        assertComposer(true, false, false);
        assertVoiceButtonAppearance();
        capture("cold-hero-entry");
        exerciseDeniedHoldWithoutRecordingOverlay();
        exerciseDelayedClaimLoadingAndRelease();
        assertTrue("Both input icons must share a row", ui.truth("(()=>{const a=document.querySelector('[data-droy-voice-start=standard]').getBoundingClientRect(),b=document.querySelector('[data-droy-voice-hold]').getBoundingClientRect();return Math.abs(a.top-b.top)<2&&a.right<=b.left+1})()"));
        ui.tap("[data-droy-voice-start=standard]");
        DeviceUi.await("Disabled microphone must leave a visible explanation", 15000,
            () -> ui.truth("!!window.DroyVoice.getSnapshot().error&&!window.DroyVoice.getSnapshot().mode"));
        assertTrue(AudioCapture.get(context).uiStatus().isNull("active"));
        capture("blank-session-permission-boundary");
        openSettings();
        assertLayout(SETTINGS);
        assertTrue("Voice conversation entry must default on in a fresh profile", ui.truth("window.DroyVoice.getSnapshot().config.entries.standard===true"));
        assertTrue("Speech settings must distinguish engine and voice without exposing voice management", ui.truth(
            "!!document.querySelector('summary[aria-label=发声方式]')&&!!document.querySelector('summary[aria-label=朗读音色],input[aria-label=朗读音色]')&&"
            + "document.querySelector('[data-droy-voice-settings]').textContent.includes('不是语言设置')&&"
            + "!document.querySelector('[data-droy-voice-settings]').textContent.includes('我的音色')&&"
            + "!document.querySelector('select[aria-label=朗读服务],select[aria-label=默认声音]')"));
        assertTrue("Hidden capabilities leaked into settings", ui.truth("!document.querySelector('[data-droy-voice-provider],[data-droy-voice-enroll],[data-droy-voice-entry-toggle=realtimeVoice],[data-droy-voice-entry-toggle=realtimeVideo]')"));
        assertTrue(ui.truth("document.querySelector('[data-droy-settings-section=droy-voice]').getAttribute('aria-current')==='true'"));
        capture("settings-top");
        setEntry("standard", false);
        closeSettingsAndGeneral();
        assertComposer(false, false, false);
        assertTrue(ui.truth("!!document.querySelector('[data-droy-voice-hold]')&&!document.querySelector(" + JSONObject.quote(DICTATION) + ")"));
        openSettings(); setEntry("standard", true);
        replaceInput("input[aria-label='英文唤醒词']", "Hello Droy");
        assertTrue("The wake input is covered by the keyboard", ui.truth("(()=>{const e=document.querySelector('input[aria-label=英文唤醒词]'),r=e.getBoundingClientRect(),ime=parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--droy-ime-bottom'))||0;return r.bottom<=innerHeight-ime+1})()"));
        capture("wake-keyboard");
        ui.pressBack();
        DeviceUi.await("Back did not close the keyboard", 5000, () -> !keyboardVisible());
        assertTrue(ui.truth("!!document.querySelector('" + SETTINGS + "')"));
        tapStable("[data-droy-voice-save]");
        DeviceUi.await("Voice settings did not save", 5000, () -> ui.truth("[...document.querySelectorAll('[role=status]')].some(e=>e.textContent==='已保存')"));
        capture("wake-bottom");
        assertLayout(SETTINGS);
        exerciseWakeConfiguration();
        exerciseWakeSaveState();
        closeSettingsAndGeneral();
        // Native audio is deliberately stubbed only for control-layout states.
        // These touches verify the real composer and controls, not microphone ASR.
        ui.evaluate("window.__voiceOriginalFetch=window.fetch;window.__voiceLayout={polls:0,muted:false};window.fetch=async function(url,options){if(url==='/droy/voice'&&options?.body){"
            + "const q=JSON.parse(options.body),v=window.__voiceLayout;let result;if(q.operation==='start')result={state:'recording',captureId:'layout-fixture'};"
            + "if(q.operation==='poll'){v.polls++;result={state:'recording',captureId:'layout-fixture',segments:[],level:v.muted?0:v.polls<=8?.04:[.0004,.006,.021,.003,.04,.012][v.polls%6]};}"
            + "if(q.operation==='mute'){v.muted=q.muted;result={muted:q.muted};}if(q.operation==='stop')result={state:'ended'};"
            + "if(result)return new Response(JSON.stringify({ok:true,result}),{headers:{'Content-Type':'application/json'}});}return window.__voiceOriginalFetch.apply(this,arguments)}");
        try {
            ui.tap("[data-droy-voice-start=standard]");
            DeviceUi.await("Voice control row did not open", 5000, () -> ui.truth("!!document.querySelector('[data-droy-voice-inline]')"));
            assertLayout("[data-droy-voice-activity]");
            assertTrue("Controls must remain one row", ui.truth("(()=>{const e=[...document.querySelectorAll('[data-droy-voice-inline] button')].map(e=>e.getBoundingClientRect());return e.length===3&&e.every(r=>Math.abs(r.top-e[0].top)<2)})()"));
            assertTrue(ui.truth("document.querySelector('[data-droy-voice-speaker]').getAttribute('aria-pressed')==='false'"));
            DeviceUi.await("Repeated constant nonzero samples did not advance waveform history", 5000,
                () -> ui.truth("window.__voiceLayout.polls>=6&&[...document.querySelectorAll('[data-droy-voice-inline] .droy-voice-wave i')].filter(e=>e.style.transform!=='scaleY(0.06)').length>1"));
            DeviceUi.await("Wave history did not receive changing input levels", 10000,
                () -> ui.truth("window.__voiceLayout.polls>=26&&new Set([...document.querySelectorAll('[data-droy-voice-inline] .droy-voice-wave i')].map(e=>e.style.transform)).size>3"));
            assertInlineVisualLayout(false);
            capture("controls-fixture-listening");
            ui.tap("[data-droy-voice-speaker]");
            assertTrue(ui.truth("window.DroyVoice.getSnapshot().readAloud"));
            ui.tap("[data-droy-voice-mute]");
            DeviceUi.await("Mute touch did not update", 5000, () -> ui.truth("window.DroyVoice.getSnapshot().muted"));
            DeviceUi.await("Muted waveform did not return to a flat baseline", 5000,
                () -> ui.truth("[...document.querySelectorAll('[data-droy-voice-inline] .droy-voice-wave i')].every(e=>e.style.transform==='scaleY(0.06)')"));
            assertInlineVisualLayout(true);
            capture("controls-fixture-muted");
            ui.tap("[data-droy-voice-end]");
            DeviceUi.await("Exit did not close the row", 5000, () -> !ui.truth("!!document.querySelector('[data-droy-voice-inline]')"));
        } finally { ui.evaluate("window.fetch=window.__voiceOriginalFetch;delete window.__voiceOriginalFetch"); }
        assertTrue(AudioCapture.get(context).uiStatus().isNull("active"));
        exerciseSilentHoldOverlay();
        passed = true;
    }

    @Test public void downloadedVoiceStatesUseRealTouchesWithoutStartingInference() throws Exception {
        // Only download responses are fixtures. Native synthesis is never called in
        // this layout/state test; real model download and playback have a separate test.
        ui.evaluate("window.__voiceDownloadOriginalFetch=window.fetch;window.__voiceDownloadRequests=[];"
            + "window.__voiceDownloadFixture={models:[{provider:'melo',name:'MeloTTS 中文',state:'not_downloaded',ready:false,downloadedBytes:0,totalBytes:177000000,error:null},"
            + "{provider:'qwen3',name:'Qwen3-TTS',state:'not_downloaded',ready:false,downloadedBytes:0,totalBytes:859852832,error:null}],attempts:0};"
            + "window.fetch=function(url,options){if(url==='/droy/voice'&&options?.body){const q=JSON.parse(options.body);window.__voiceDownloadRequests.push(q);"
            + "if(['tts.models','tts.download','tts.cancel_download'].includes(q.operation)){const f=window.__voiceDownloadFixture,m=f.models.find(m=>m.provider===q.provider);"
            + "if(q.operation==='tts.download'){if(q.provider==='melo')f.attempts++;Object.assign(m,{state:f.attempts===2&&q.provider==='melo'?'failed':'downloading',ready:false,downloadedBytes:Math.floor(m.totalBytes/4),error:f.attempts===2&&q.provider==='melo'?'测试下载失败：连接已中断':null});}"
            + "if(q.operation==='tts.cancel_download')Object.assign(m,{state:'not_downloaded',ready:false,downloadedBytes:0,error:null});"
            + "return Promise.resolve(new Response(JSON.stringify({ok:true,result:{models:f.models}}),{headers:{'Content-Type':'application/json'}}));}}"
            + "return window.__voiceDownloadOriginalFetch.apply(this,arguments)}");
        try {
            openSettings();
            assertEquals("system", ui.evaluate("window.DroyVoice.getSnapshot().config.tts.provider"));
            assertEquals("", ui.evaluate("window.DroyVoice.getSnapshot().config.tts.voiceId"));
            assertEquals("system", ui.evaluate("document.querySelector('summary[aria-label=发声方式]').dataset.value"));
            chooseOption("summary[aria-label=发声方式]", "MeloTTS 中文", "melo");
            awaitModelState("melo", "not_downloaded");
            assertLocalVoiceActionsEnabled(false);
            ui.visibleWebBounds("[data-droy-voice-download]"); assertLayout(SETTINGS); capture("melo-not-downloaded-fixture");
            chooseOption("[data-droy-voice-wake-timing]", "仅在 Droy 内", "foreground");
            tapStable("[data-droy-voice-save-wake]");
            DeviceUi.await("An unavailable TTS draft blocked an independent wake setting", 10000, () -> ui.truth(
                "window.DroyVoice.getSnapshot().config.wake.timing==='foreground'&&window.DroyVoice.getSnapshot().config.tts.provider==='system'&&document.querySelector('summary[aria-label=发声方式]').dataset.value==='melo'"));
            assertLocalVoiceActionsEnabled(false);
            tapStable("[data-droy-voice-download]");
            awaitModelState("melo", "downloading");
            assertTrue(ui.truth("document.querySelector('progress[aria-label=语音资源下载进度]').value>0"));
            assertLocalVoiceActionsEnabled(false);
            assertLayout(SETTINGS); capture("melo-downloading-fixture");
            tapStable("[data-droy-voice-download]");
            awaitModelState("melo", "not_downloaded");
            tapStable("[data-droy-voice-download]");
            awaitModelState("melo", "failed");
            assertTrue(ui.truth("document.querySelector('[data-droy-voice-model=melo]').textContent.includes('连接已中断')"));
            assertLocalVoiceActionsEnabled(false);
            assertLayout(SETTINGS); capture("melo-download-failed-fixture");
            tapStable("[data-droy-voice-download]");
            awaitModelState("melo", "downloading");
            ui.evaluate("Object.assign(window.__voiceDownloadFixture.models[0],{state:'verifying',downloadedBytes:177000000})");
            awaitModelState("melo", "verifying");
            assertLocalVoiceActionsEnabled(false);
            assertLayout(SETTINGS); capture("melo-verifying-fixture");
            ui.evaluate("Object.assign(window.__voiceDownloadFixture.models[0],{state:'ready',ready:true,error:null})");
            awaitModelState("melo", "ready");
            assertLocalVoiceActionsEnabled(true);
            assertTrue(ui.truth("!document.querySelector('[data-droy-voice-download]')"));
            assertEquals("Downloading must not save the selected draft", "system", ui.evaluate("window.DroyVoice.getSnapshot().config.tts.provider"));
            assertLayout(SETTINGS); capture("melo-ready-fixture");

            chooseOption("summary[aria-label=发声方式]", "Qwen3-TTS", "qwen3");
            awaitModelState("qwen3", "not_downloaded");
            assertTrue("Qwen should expose its supported standard speed", ui.truth("document.querySelector('summary[aria-label=语速]').getAttribute('aria-disabled')==='true'&&document.querySelector('summary[aria-label=语速]').dataset.value==='1'"));
            assertLocalVoiceActionsEnabled(false);
            ui.visibleWebBounds("[data-droy-voice-download]"); assertLayout(SETTINGS); capture("qwen3-not-downloaded-fixture");
            tapStable("[data-droy-voice-download]");
            awaitModelState("qwen3", "downloading");
            closeSettingsAndGeneral();
            assertTrue("Leaving settings should not cancel a requested download", ui.truth("window.__voiceDownloadRequests.filter(q=>q.operation==='tts.cancel_download'&&q.provider==='qwen3').length===0"));
            openSettings();
            assertEquals("system", ui.evaluate("document.querySelector('summary[aria-label=发声方式]').dataset.value"));
            chooseOption("summary[aria-label=发声方式]", "Qwen3-TTS", "qwen3");
            awaitModelState("qwen3", "downloading");
            tapStable("[data-droy-voice-download]");
            awaitModelState("qwen3", "not_downloaded");
            assertTrue("State fixtures must not be treated as a real playable model", ui.truth("!window.__voiceDownloadRequests.some(q=>q.operation==='preview_voice'||q.operation==='speak')"));
            chooseOption("summary[aria-label=发声方式]", "Android 系统声音", "system");
            assertLocalVoiceActionsEnabled(true);
            tapStable("[data-droy-voice-save]");
            DeviceUi.await("System speech setting did not save", 5000, this::settingsSaved);
            closeSettingsAndGeneral(); openSettings();
            assertEquals("system", ui.evaluate("document.querySelector('summary[aria-label=发声方式]').dataset.value"));
            assertTrue("Inspecting downloads opened microphone capture", AudioCapture.get(context).uiStatus().isNull("active"));
            passed = true;
        } finally {
            ui.evaluate("window.fetch=window.__voiceDownloadOriginalFetch;delete window.__voiceDownloadOriginalFetch;delete window.__voiceDownloadRequests;delete window.__voiceDownloadFixture");
        }
    }

    @Test public void meloDownloadsThenPreviewsDraftsAndPersistsSelection() throws Exception {
        exerciseRealDownloadedVoice("melo", "MeloTTS 中文", "zh", 44100);
    }

    @Test public void qwenDownloadsThenPreviewsDraftsAndPersistsSelection() throws Exception {
        exerciseRealDownloadedVoice("qwen3", "Qwen3-TTS", "Serena", 24000);
    }

    private void exerciseRealDownloadedVoice(String provider, String providerLabel, String voiceId, int sampleRate) throws Exception {
        String modelCard = "[data-droy-voice-model=" + provider + "]";
        openSettings();
        assertEquals("system", ui.evaluate("window.DroyVoice.getSnapshot().config.tts.provider"));
        chooseOption("summary[aria-label=发声方式]", providerLabel, provider);
        DeviceUi.await("Model catalog did not load: " + provider, 10000, () -> ui.truth(
            "!!document.querySelector('" + modelCard + ":not([data-droy-voice-model-state=unavailable])')"));
        if (!ui.truth("!!document.querySelector('" + modelCard + "[data-droy-voice-model-state=ready]')")) {
            assertLocalVoiceActionsEnabled(false);
            if (!ui.truth("['downloading','verifying'].includes(document.querySelector('" + modelCard + "').getAttribute('data-droy-voice-model-state'))"))
                tapStable("[data-droy-voice-download]");
            DeviceUi.await("The real model download did not complete: " + provider, 900000, () -> {
                assertFalse("The real model download failed: " + provider, ui.truth("!!document.querySelector('" + modelCard + "[data-droy-voice-model-state=failed]')"));
                return ui.truth("!!document.querySelector('" + modelCard + "[data-droy-voice-model-state=ready]')");
            });
        }
        assertLocalVoiceActionsEnabled(true);
        DeviceUi.await("Chinese voice did not populate: " + provider, 10000, () -> ui.truth(
            "[...document.querySelector('summary[aria-label=朗读音色]').closest('details').querySelectorAll('[role=option]')].some(o=>o.dataset.value===" + JSONObject.quote(voiceId) + ")"));
        String voiceLabel = String.valueOf(ui.evaluate("[...document.querySelector('summary[aria-label=朗读音色]').closest('details').querySelectorAll('[role=option]')].find(o=>o.dataset.value===" + JSONObject.quote(voiceId) + ").textContent"));
        chooseOption("summary[aria-label=朗读音色]", voiceLabel, voiceId);
        Map<String, String> voiceFiles = snapshot(new File(home, "voice").toPath());
        String savedTts = String.valueOf(ui.evaluate("JSON.stringify(window.DroyVoice.getSnapshot().config.tts)"));
        java.lang.reflect.Field outputField = VoiceMedia.class.getDeclaredField("output"); outputField.setAccessible(true);
        VoiceSpeechOutput output = (VoiceSpeechOutput) outputField.get(VoiceMedia.get(context));
        assertFalse("Finish earlier speech before preview testing", output.speaking());
        ui.evaluate("window.__voicePreviewOriginalFetch=window.fetch;window.__voicePreviewRequests=[];window.fetch=function(url,options){"
            + "if(url==='/droy/voice'&&options?.body){const q=JSON.parse(options.body);window.__voicePreviewRequests.push({operation:q.operation,provider:q.provider,voiceId:q.voiceId})}"
            + "return window.__voicePreviewOriginalFetch.apply(this,arguments)}");
        try {
            for (int index = 0; index < 2; index++) {
                ui.visibleWebBounds("[data-droy-voice-preview]");
                assertLayout(SETTINGS); capture(provider + "-real-draft-preview-" + index);
                tapStable("[data-droy-voice-preview]");
                DeviceUi.await("Preview did not enter speech preparation: " + provider, 10000,
                    () -> output.speaking() && ui.truth("document.querySelector('[data-droy-voice-preview]').textContent==='停止试听'"));
                DeviceUi.await("Preview did not write real audio to the Android output: " + provider, 300000, () -> {
                    assertTrue("Preview failed: " + output.error(), output.error().isEmpty());
                    synchronized (output) {
                        java.lang.reflect.Field frames = VoiceSpeechOutput.class.getDeclaredField("writtenFrames"); frames.setAccessible(true);
                        java.lang.reflect.Field rate = VoiceSpeechOutput.class.getDeclaredField("trackRate"); rate.setAccessible(true);
                        return frames.getLong(output) > 0 && rate.getInt(output) == sampleRate;
                    }
                });
                assertTrue("Preview did not use the selected unsaved voice", ui.truth(
                    "(()=>{const q=window.__voicePreviewRequests.filter(q=>q.operation==='preview_voice').at(-1);return q?.provider===" + JSONObject.quote(provider) + "&&q.voiceId===" + JSONObject.quote(voiceId) + "})()"));
                assertEquals("Preview saved a draft TTS selection", savedTts,
                    ui.evaluate("JSON.stringify(window.DroyVoice.getSnapshot().config.tts)"));
                assertEquals("Preview wrote voice configuration files", voiceFiles, snapshot(new File(home, "voice").toPath()));
                assertTrue("Preview must still be active for the stop/exit touch", output.speaking());
                if (index == 0) {
                    tapStable("[data-droy-voice-preview]");
                    DeviceUi.await("Stopping preview did not restore the preview action", 10000,
                        () -> !output.speaking() && ui.truth("document.querySelector('[data-droy-voice-preview]').textContent==='试听'"));
                } else {
                    closeSettingsAndGeneral();
                    DeviceUi.await("Leaving voice settings did not stop preview audio", 10000, () -> !output.speaking());
                }
                DeviceUi.await("Stopped preview left a native synthesizer running", 60000, () -> localSpeechWorkerStopped(output));
            }
            assertTrue("Preview unexpectedly saved settings or changed wake configuration", ui.truth(
                "!window.__voicePreviewRequests.some(q=>q.operation==='save_config'||q.operation==='wake.configure')"));
            openSettings();
            assertEquals("Unsaved preview choice survived reopening settings", "system",
                ui.evaluate("document.querySelector('summary[aria-label=发声方式]').dataset.value"));
            assertEquals("Leaving previews changed persisted voice files", voiceFiles, snapshot(new File(home, "voice").toPath()));
            chooseOption("summary[aria-label=发声方式]", providerLabel, provider);
            awaitModelState(provider, "ready");
            chooseOption("summary[aria-label=朗读音色]", voiceLabel, voiceId);
            tapStable("[data-droy-voice-save]");
            DeviceUi.await("Ready voice selection did not persist: " + provider, 10000, () -> settingsSaved()
                && ui.truth("window.DroyVoice.getSnapshot().config.tts.provider===" + JSONObject.quote(provider) + "&&window.DroyVoice.getSnapshot().config.tts.voiceId===" + JSONObject.quote(voiceId)));
            closeSettingsAndGeneral(); openSettings();
            assertEquals(provider, ui.evaluate("document.querySelector('summary[aria-label=发声方式]').dataset.value"));
            assertEquals(voiceId, ui.evaluate("document.querySelector('summary[aria-label=朗读音色]').dataset.value"));
            assertTrue("Preview opened microphone capture", AudioCapture.get(context).uiStatus().isNull("active"));
            ui.visibleWebBounds(modelCard); assertLayout(SETTINGS); capture(provider + "-real-saved");
            passed = true;
        } finally {
            try { ui.evaluate("window.fetch=window.__voicePreviewOriginalFetch;delete window.__voicePreviewOriginalFetch;delete window.__voicePreviewRequests"); }
            finally {
                output.stop();
                DeviceUi.await("Preview cleanup left a native synthesizer running", 60000, () -> localSpeechWorkerStopped(output));
                assertFalse("Preview cleanup left queued or playing speech", output.speaking());
            }
        }
    }

    private void awaitModelState(String provider, String state) throws Exception {
        DeviceUi.await("Voice model did not reach " + provider + ":" + state, 10000, () -> ui.truth(
            "!!document.querySelector('[data-droy-voice-model=" + provider + "][data-droy-voice-model-state=" + state + "]')"));
    }

    private void assertLocalVoiceActionsEnabled(boolean enabled) throws Exception {
        DeviceUi.await("Voice action availability differs from model readiness", 5000, () -> ui.truth(
            "document.querySelector('[data-droy-voice-preview]').disabled===" + !enabled
            + "&&document.querySelector('[data-droy-voice-save]').disabled===" + !enabled));
    }

    private boolean localSpeechWorkerStopped(VoiceSpeechOutput output) throws Exception {
        synchronized (output) {
            java.lang.reflect.Field worker = VoiceSpeechOutput.class.getDeclaredField("localWorker"); worker.setAccessible(true);
            return worker.get(output) == null;
        }
    }

    private void exerciseWakeConfiguration() throws Exception {
        String timing = "[data-droy-voice-wake-timing]";
        String bound = "[data-droy-voice-entry-toggle=useCompanionName]";
        assertEquals("screen_on", ui.evaluate("document.querySelector(" + JSONObject.quote(timing) + ").dataset.value"));
        chooseOption(timing, "仅在 Droy 内", "foreground");
        tapStable(bound);
        DeviceUi.await("Companion-name wake must disable manual keywords", 5000, () -> ui.truth(
            "document.querySelector('input[aria-label=中文唤醒词]').disabled&&document.querySelector('input[aria-label=英文唤醒词]').disabled"));
        assertEquals("Hello Droy", ui.evaluate("document.querySelector('input[aria-label=英文唤醒词]').value"));
        assertTrue("Companion wake patterns are missing", ui.truth(
            "document.querySelector('[data-droy-voice-settings]').textContent.includes('你好 + 名称')&&"
            + "document.querySelector('[data-droy-voice-settings]').textContent.includes('Hi + 名称')"));
        tapStable("[data-droy-voice-save]");
        DeviceUi.await("Companion wake settings did not persist in both layers", 10000, () -> {
            JSONObject status = VoiceWake.get(context).request(new JSONObject().put("operation", "wake.status"));
            return status.getBoolean("useCompanionName") && status.getString("timing").equals("foreground")
                && status.getString("effectiveChinese").equals("你好Droy") && status.getString("effectiveEnglish").equals("Hi Droy")
                && ui.truth("window.DroyVoice.getSnapshot().config.wake.useCompanionName&&window.DroyVoice.getSnapshot().config.wake.timing==='foreground'") && settingsSaved();
        });
        assertLayout(SETTINGS); ui.visibleWebBounds(bound); capture("wake-companion-bound");
        closeSettingsAndGeneral(); openSettings();
        assertTrue("Saved companion binding did not survive reopening", ui.truth(
            "document.querySelector('input[aria-label=中文唤醒词]').disabled&&document.querySelector('input[aria-label=英文唤醒词]').disabled"));
        tapStable(bound);
        DeviceUi.await("Turning off companion binding did not restore manual editing", 5000, () -> ui.truth(
            "!document.querySelector('input[aria-label=中文唤醒词]').disabled&&!document.querySelector('input[aria-label=英文唤醒词]').disabled"));
        assertEquals("Hello Droy", ui.evaluate("document.querySelector('input[aria-label=英文唤醒词]').value"));
        chooseOption(timing, "仅亮屏时", "screen_on");
        tapStable("[data-droy-voice-save]");
        DeviceUi.await("Manual wake settings were not restored", 10000, () -> {
            JSONObject status = VoiceWake.get(context).request(new JSONObject().put("operation", "wake.status"));
            return !status.getBoolean("useCompanionName") && status.getString("timing").equals("screen_on")
                && status.getString("effectiveEnglish").equals("Hello Droy") && settingsSaved();
        });
        assertLayout(SETTINGS); ui.visibleWebBounds(bound); capture("wake-manual-restored");
    }

    private void chooseOption(String selector, String label, String value) throws Exception {
        tapStable(selector);
        String option = ".droy-voice-choice[open] [role=option][data-value=" + JSONObject.quote(value) + "]";
        DeviceUi.await("Inline speech choice did not appear: " + label, 5000,
            () -> ui.truth("!!document.querySelector(" + JSONObject.quote(option) + ")"));
        tapStable(option);
        DeviceUi.await("Inline speech choice did not choose " + value, 5000,
            () -> value.equals(ui.evaluate("document.querySelector(" + JSONObject.quote(selector) + ").dataset.value")));
    }

    private void exerciseWakeSaveState() throws Exception {
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .grantRuntimePermission(context.getPackageName(), android.Manifest.permission.RECORD_AUDIO);
        assertTrue(preferences.edit().putBoolean("allowAudioMicrophone", true).commit());
        ui.tap("[data-droy-voice-entry-toggle=wake]");
        DeviceUi.await("Wake did not automatically prepare and enable without voice enrollment", 60000,
            () -> ui.truth("window.DroyVoice.getSnapshot().wake?.enabled===true&&window.DroyVoice.getSnapshot().wake?.preparation?.ready===true"));
        tapStable("[data-droy-voice-save]");
        DeviceUi.await("Saving changed wake's enabled state", 10000, () -> {
            JSONObject status = VoiceWake.get(context).request(new JSONObject().put("operation", "wake.status"));
            return status.getBoolean("enabled") && settingsSaved();
        });
        ui.tap("[data-droy-voice-entry-toggle=wake]");
        DeviceUi.await("Wake did not disable", 10000,
            () -> ui.truth("window.DroyVoice.getSnapshot().wake?.enabled===false"));
        tapStable("[data-droy-voice-save]");
        DeviceUi.await("Saving re-enabled wake", 10000, () -> {
            JSONObject status = VoiceWake.get(context).request(new JSONObject().put("operation", "wake.status"));
            return !status.getBoolean("enabled") && settingsSaved();
        });
        assertTrue(AudioCapture.get(context).uiStatus().isNull("active"));
        assertTrue(preferences.edit().putBoolean("allowAudioMicrophone", false).commit());
    }

    private boolean settingsSaved() throws Exception {
        return ui.truth("document.querySelector('[data-droy-voice-save]').getAttribute('aria-busy')==='false'&&[...document.querySelectorAll('[role=status]')].some(e=>e.textContent==='已保存')");
    }

    private void exerciseSilentHoldOverlay() throws Exception {
        // The dedicated emulator has host audio disabled. This verifies actual
        // WebView capture and pointer UI without sending a user/model message.
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).grantRuntimePermission(context.getPackageName(), android.Manifest.permission.RECORD_AUDIO);
        assertTrue(preferences.edit().putBoolean("allowAudioMicrophone", true).putBoolean("allowAudioTranscription", true).commit());
        DeviceUi.await("The local provider was not ready for the silent hold fixture", 10000,
            () -> ui.truth("!document.querySelector('[data-droy-voice-hold]').disabled"));
        final Throwable[] failure = {null};
        Thread finger = new Thread(() -> {
            try { ui.longPress("[data-droy-voice-hold]", 4000); }
            catch (Throwable error) { failure[0] = error; }
        });
        finger.start();
        DeviceUi.await("Holding the button did not open the recording overlay", 10000,
            () -> ui.truth("window.DroyVoice.getSnapshot().mode==='hold'&&window.DroyVoice.getSnapshot().status==='recording'&&document.querySelector('[data-droy-voice-hold-state=recording] .droy-voice-hold-label')?.textContent==='松开发送'&&document.querySelector('[data-droy-voice-hold-state=recording] .droy-voice-hold-hint')?.textContent==='上滑取消'"));
        assertHoldVisualLayout("recording");
        capture("silent-microphone-hold-overlay");
        finger.join(10000); assertFalse("Hold pointer did not release", finger.isAlive());
        if (failure[0] != null) throw new AssertionError("Hold pointer failed", failure[0]);
        DeviceUi.await("Silent recording did not finish recognition", 15000,
            () -> ui.truth("!window.DroyVoice.getSnapshot().mode"));
        DeviceUi.await("The silent hold left a native capture: " + AudioCapture.get(context).uiStatus().optJSONObject("active")
            + "; wake=" + VoiceWake.get(context).request(new JSONObject().put("operation", "wake.status")), 10000,
            () -> AudioCapture.get(context).uiStatus().isNull("active"));
        assertNoInference();
    }

    private void createEmptySessionFromVoiceEntry() throws Exception {
        assertFalse(ProductPermissions.allowed(preferences, "allowAudioMicrophone"));
        // The user entry creates an ordinary blank DSH session before native capture checks.
        ui.tap("[data-droy-voice-start=standard]");
        DeviceUi.await("Voice entry did not create and select a real empty session", 15000,
            () -> ui.truth("!!window.DroyVoice.getSnapshot().sessionId"));
        DeviceUi.await("Disabled microphone or unprepared ASR did not return its actual native error", 15000,
            () -> ui.truth("window.DroyVoice.getSnapshot().status==='error'&&[...document.querySelectorAll('[data-droy-voice-error]')].some(e=>e.getClientRects().length&&/请先准备本地语音模型|音频采集已暂停或对应能力已关闭/.test(e.textContent))"));
        assertFalse(ProductPermissions.allowed(preferences, "allowAudioMicrophone"));
        assertTrue("Creating a blank session opened the microphone", AudioCapture.get(context).uiStatus().isNull("active"));
        DeviceUi.await("The real blank session did not expose DSH's independent dictation control", 10000,
            () -> ui.truth("!!document.querySelector(" + JSONObject.quote(DICTATION) + ")"));
        assertNoInference();
        capture("blank-session-native-boundary");
    }

    private void exerciseAuthenticationBoundary() throws Exception {
        prepareWakeResources();
        boolean before = VoiceWake.get(context).request(new JSONObject().put("operation", "wake.status")).getBoolean("hasVoiceprint");
        boolean secure = context.getSystemService(KeyguardManager.class).isDeviceSecure();
        ui.tap("[data-droy-voice-enroll=new]");
        if (secure) {
            DeviceUi.await("System credential confirmation did not open", 5000,
                () -> !context.getPackageName().equals(activePackage()));
            capture("system-authentication", false);
            ui.pressBack();
            DeviceUi.await("Cancelled system authentication did not return", 5000,
                () -> context.getPackageName().equals(activePackage()));
        }
        DeviceUi.await("Authentication boundary did not return a visible explanation", 5000,
            () -> ui.truth("[...document.querySelectorAll('[data-droy-voice-error]')].some(e=>e.getClientRects().length&&/锁屏|身份验证/.test(e.textContent))"));
        assertFalse("An unverified user entered recording", ui.truth("!!document.querySelector('[data-droy-voice-enrollment]')"));
        assertEquals(before, VoiceWake.get(context).request(new JSONObject().put("operation", "wake.status")).getBoolean("hasVoiceprint"));
        assertTrue(AudioCapture.get(context).uiStatus().isNull("active"));
        capture(secure ? "authentication-cancelled" : "screen-lock-required");
    }

    private void prepareWakeResources() throws Exception {
        JSONObject nativeState = VoiceWake.get(context).request(new JSONObject().put("operation", "wake.status"));
        assertFalse("Preparing resources must not enable wake listening", nativeState.getBoolean("enabled"));
        if (!nativeState.getJSONObject("preparation").getBoolean("ready")) {
            ui.tap("[data-droy-voice-wake-prepare]");
            DeviceUi.await("Bundled wake resources did not become ready", 60000, () -> {
                JSONObject current = VoiceWake.get(context).request(new JSONObject().put("operation", "wake.status"));
                JSONObject preparation = current.getJSONObject("preparation");
                assertFalse("Wake resource preparation failed: " + preparation.optString("error"), "failed".equals(preparation.optString("state")));
                assertFalse("Preparing resources activated wake listening", current.getBoolean("enabled"));
                assertTrue("Preparing resources opened the microphone", AudioCapture.get(context).uiStatus().isNull("active"));
                return preparation.getBoolean("ready");
            });
        }
        DeviceUi.await("Prepared wake resources were not reflected in the UI", 5000,
            () -> ui.truth("window.DroyVoice.getSnapshot().wake?.preparation?.ready===true"));
        assertFalse("The fixture microphone grant changed during preparation", preferences.getBoolean("allowAudioMicrophone", true));
        capture("wake-resources-ready");
    }

    private String activePackage() {
        android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow();
        return root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
    }

    private void openSettings() throws Exception {
        ui.openPermissions();
        ui.tap("[data-droy-settings-section=droy-voice]");
        awaitSettings();
    }

    private void awaitSettings() throws Exception {
        DeviceUi.await("Voice settings did not open", 5000,
            () -> ui.truth("!!document.querySelector('" + SETTINGS + "')"));
        DeviceUi.await("The fixed local recognition service never became ready", 15000,
            () -> ui.truth("!!document.querySelector('[data-droy-voice-settings] [data-droy-voice-recognizer-state=ready]')"));
        assertTrue("Voice input still asks users to choose or prepare recognition", ui.truth(
            "!document.querySelector('select[aria-label=识别服务],select[aria-label=识别语言]')&&"
            + "![...document.querySelectorAll('[data-droy-voice-settings] button')].some(e=>/准备本地识别模型|取消准备/.test(e.textContent))"));
        assertTrue("Recognition dependency failed", ui.truth("!document.querySelector('" + SETTINGS + "').textContent.includes('without inject')"));
    }

    private void closeSettingsAndGeneral() throws Exception {
        ui.hideKeyboard();
        ui.tap("[data-droy-settings-back]");
        DeviceUi.await("Settings Back did not return to chat", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
    }

    private void setEntry(String name, boolean checked) throws Exception {
        if (ui.truth("window.DroyVoice.getSnapshot().config.entries[" + JSONObject.quote(name) + "]===" + checked)) return;
        ui.tap("[data-droy-voice-entry-toggle=" + name + "]");
        DeviceUi.await("The entry touch was not persisted: " + name, 5000,
            () -> ui.truth("window.DroyVoice.getSnapshot().config.entries[" + JSONObject.quote(name) + "]===" + checked));
        assertEntry(name, checked);
    }

    private void assertEntry(String name, boolean checked) throws Exception {
        assertTrue("Entry switch differs from persisted state: " + name,
            ui.truth("document.querySelector('[data-droy-voice-entry-toggle=" + name + "] [role=switch]').getAttribute('aria-checked')==='" + checked + "'"));
    }

    private void assertComposer(boolean standard, boolean realtime, boolean video) throws Exception {
        DeviceUi.await("Composer voice entrances do not match their independent settings", 5000,
            () -> ui.truth("!!document.querySelector('[data-droy-voice-start=standard]')===" + standard
                + "&&!!document.querySelector('[data-droy-voice-start=realtime]')===" + realtime
                + "&&!!document.querySelector('[data-droy-voice-start=video]')===" + video));
        assertLayout("[data-droy-voice-activity]");
        assertTrue("The original DSH UI duplicated the Droy voice controls", ui.truth(
            "document.querySelectorAll('[data-droy-voice-activity]').length===1&&"
            + "document.querySelectorAll('[data-droy-voice-activity] button').length===" + (standard ? 2 : 1)
            + "&&!document.querySelector(" + JSONObject.quote(DICTATION) + ")"));
    }

    private void assertVoiceButtonAppearance() throws Exception {
        JSONObject geometry = (JSONObject) ui.evaluate("(()=>{const button=document.querySelector('[data-droy-voice-hold]'),face=button.querySelector('.droy-voice-hold-face'),"
            + "send=document.querySelector('button[aria-label=发送消息],button[aria-label=\"Send message\"]'),r=face.getBoundingClientRect(),b=button.getBoundingClientRect(),"
            + "s=getComputedStyle(face),color=s.backgroundColor.match(/[\\d.]+/g)?.map(Number)||[],target=send?.getBoundingClientRect();"
            + "return {width:r.width,height:r.height,touchWidth:b.width,touchHeight:b.height,radius:s.borderRadius,cornerShapeSupported:CSS.supports('corner-shape','round'),cornerShape:s.getPropertyValue('corner-shape'),"
            + "background:s.backgroundColor,neutral:color.length>=3&&Math.max(...color.slice(0,3))-Math.min(...color.slice(0,3))<=12,"
            + "sendPresent:!!target,sendWidth:target?.width,sendHeight:target?.height}})()");
        assertEquals("Hold control must be a true circle: " + geometry, geometry.getDouble("width"), geometry.getDouble("height"), .1);
        if (geometry.getBoolean("cornerShapeSupported"))
            assertEquals("Hold control inherited a non-round corner shape: " + geometry, "round", geometry.getString("cornerShape"));
        assertEquals("50%", geometry.getString("radius"));
        assertTrue("Hold control still has a bright accent background: " + geometry, geometry.getBoolean("neutral"));
        assertTrue("Hold target is too small: " + geometry, geometry.getDouble("touchWidth") >= 48 && geometry.getDouble("touchHeight") >= 48);
        assertTrue("The DSH send control is missing", geometry.getBoolean("sendPresent"));
        assertEquals("Hold/send visible widths differ: " + geometry, geometry.getDouble("sendWidth"), geometry.getDouble("width"), .1);
        assertEquals("Hold/send visible heights differ: " + geometry, geometry.getDouble("sendHeight"), geometry.getDouble("height"), .1);
        buttonAppearance = geometry;
    }

    private void exerciseDeniedHoldWithoutRecordingOverlay() throws Exception {
        assertFalse(ProductPermissions.allowed(preferences, "allowAudioMicrophone"));
        observeRecordingState();
        try {
            ui.longPress("[data-droy-voice-hold]", 800);
            DeviceUi.await("Denied microphone did not show inline feedback", 10000,
                () -> ui.truth("!window.DroyVoice.getSnapshot().mode&&!!document.querySelector('[data-droy-voice-error]')"));
            assertFalse("Permission failure ever displayed an active recording", ui.truth("window.__voiceRecordedState"));
            assertFalse("Permission failure left the recording overlay open", ui.truth("!!document.querySelector('[data-droy-voice-hold-overlay]')"));
            assertTrue("Missing microphone permission was incorrectly reported as ASR setup", ui.truth(
                "!document.querySelector('[data-droy-voice-error]').textContent.includes('准备识别服务')"));
            assertTrue("Denied hold opened native capture", AudioCapture.get(context).uiStatus().isNull("active"));
            capture("hold-permission-denied-closed-overlay");
        } finally {
            ui.evaluate("window.__voiceOverlayObserver.disconnect();delete window.__voiceOverlayObserver");
        }
    }

    private void observeRecordingState() throws Exception {
        ui.evaluate("(()=>{window.__voiceRecordedState=false;const inspect=node=>{if(node.nodeType===1&&(node.matches('[data-droy-voice-hold-state=recording]')||node.querySelector('[data-droy-voice-hold-state=recording]')))window.__voiceRecordedState=true};"
            + "window.__voiceOverlayObserver=new MutationObserver(rows=>{for(const row of rows){if(row.type==='attributes'){inspect(row.target);if(row.oldValue==='recording')window.__voiceRecordedState=true;}for(const node of row.addedNodes)inspect(node)}});"
            + "window.__voiceOverlayObserver.observe(document.body,{childList:true,subtree:true,attributes:true,attributeOldValue:true,attributeFilter:['data-droy-voice-hold-state']})})()");
    }

    private void exerciseDelayedClaimLoadingAndRelease() throws Exception {
        observeRecordingState();
        ui.evaluate("window.__voiceClaimOriginalFetch=window.fetch;window.__voiceDelayedClaim={started:false,completed:false};window.fetch=async function(url,options){"
            + "if(url==='/droy/voice'&&options?.body&&JSON.parse(options.body).operation==='wake.claim'){window.__voiceDelayedClaim.started=true;"
            + "await new Promise(resolve=>setTimeout(resolve,2000));try{return await window.__voiceClaimOriginalFetch.apply(this,arguments)}finally{window.__voiceDelayedClaim.completed=true}}"
            + "return window.__voiceClaimOriginalFetch.apply(this,arguments)}");
        final Throwable[] failure = {null};
        Thread finger = new Thread(() -> {
            try { ui.longPress("[data-droy-voice-hold]", 1400); }
            catch (Throwable error) { failure[0] = error; }
        }, "voice-delayed-claim-pointer");
        try {
            finger.start();
            DeviceUi.await("Delayed real claim did not show loading", 5000,
                () -> ui.truth("window.__voiceDelayedClaim.started&&!!document.querySelector('[data-droy-voice-hold-state=starting] .droy-voice-loading')"));
            assertHoldVisualLayout("starting");
            capture("hold-loading-real-claim-delayed");
            finger.join(6000);
            assertFalse("Loading pointer did not release", finger.isAlive());
            if (failure[0] != null) throw new AssertionError("Loading hold touch failed", failure[0]);
            DeviceUi.await("Releasing during preparation did not cancel after the real claim settled", 10000,
                () -> ui.truth("window.__voiceDelayedClaim.completed&&!window.DroyVoice.getSnapshot().mode&&!document.querySelector('[data-droy-voice-hold-overlay]')"));
            assertFalse("Release during loading started a delayed recording", ui.truth("window.__voiceRecordedState"));
            assertTrue(AudioCapture.get(context).uiStatus().isNull("active"));
            assertFalse(AudioCapture.get(context).dictationActive());
            assertNoInference();
        } finally {
            finger.join(6000);
            ui.evaluate("window.fetch=window.__voiceClaimOriginalFetch;window.__voiceOverlayObserver.disconnect();delete window.__voiceClaimOriginalFetch;delete window.__voiceOverlayObserver");
        }
    }

    private void assertHoldVisualLayout(String phase) throws Exception {
        JSONObject shape = (JSONObject) ui.evaluate("(()=>{const overlay=document.querySelector('[data-droy-voice-hold-overlay]'),card=overlay.querySelector('.droy-voice-hold-sheet'),r=card.getBoundingClientRect(),"
            + "s=getComputedStyle(card),rgb=s.backgroundColor.match(/[\\d.]+/g)?.map(Number)||[],stage=card.querySelector('.droy-voice-hold-stage').getBoundingClientRect(),label=card.querySelector('.droy-voice-hold-label').getBoundingClientRect();"
            + "return {phase:overlay.getAttribute('data-droy-voice-hold-state'),width:r.width,height:r.height,left:r.left,right:r.right,top:r.top,bottom:r.bottom,"
            + "fits:r.left>=0&&r.right<=innerWidth&&r.top>=0&&r.bottom<=innerHeight,neutral:rgb.length>=3&&Math.max(...rgb.slice(0,3))-Math.min(...rgb.slice(0,3))<=12,"
            + "stageBeforeLabel:stage.bottom<=label.top+1,bars:card.querySelectorAll('.droy-voice-wave i').length,dots:card.querySelectorAll('.droy-voice-loading i').length,"
            + "extraFeedback:[...document.querySelectorAll('.droy-voice-feedback')].filter(e=>e.getClientRects().length).length}})()");
        assertEquals(phase, shape.getString("phase"));
        assertTrue("Hold card exceeds its compact width: " + shape, shape.getDouble("width") <= 260.1);
        assertTrue("Hold card overflows the viewport: " + shape, shape.getBoolean("fits"));
        assertTrue("Hold card uses an accent surface: " + shape, shape.getBoolean("neutral"));
        assertTrue("Hold card stage overlaps its label: " + shape, shape.getBoolean("stageBeforeLabel"));
        if (phase.equals("recording")) assertEquals(24, shape.getInt("bars"));
        if (phase.equals("starting") || phase.equals("transcribing")) {
            assertEquals(3, shape.getInt("dots"));
            assertEquals("Loading should not also show an independent feedback row", 0, shape.getInt("extraFeedback"));
        }
    }

    private void assertInlineVisualLayout(boolean muted) throws Exception {
        ui.awaitPaint(".droy-voice-wave");
        assertLayout("[data-droy-voice-inline]");
        JSONObject shape = (JSONObject) ui.evaluate("(()=>{const row=document.querySelector('[data-droy-voice-inline]'),wave=row.querySelector('.droy-voice-wave'),controls=row.querySelector('.droy-voice-controls'),"
            + "r=row.getBoundingClientRect(),w=wave.getBoundingClientRect(),c=controls.getBoundingClientRect();return {bars:wave.children.length,"
            + "separated:w.right<=c.left-4,inside:w.left>=r.left&&c.right<=r.right+1,sameRow:Math.max(w.top,c.top)<Math.min(w.bottom,c.bottom),"
            + "centered:Math.abs((w.top+w.bottom-c.top-c.bottom)/2)<1,"
            + "flat:[...wave.children].every(e=>e.style.transform==='scaleY(0.06)')}})()");
        assertEquals(24, shape.getInt("bars"));
        assertTrue("Inline waveform overlaps the three controls: " + shape, shape.getBoolean("separated"));
        assertTrue("Inline elements escape the row: " + shape, shape.getBoolean("inside"));
        assertTrue("Inline waveform and controls lost their shared row: " + shape, shape.getBoolean("sameRow"));
        assertTrue("Waveform must be vertically centered with the three controls: " + shape, shape.getBoolean("centered"));
        if (muted) assertTrue("Muted waveform is not flat", shape.getBoolean("flat"));
    }

    private void tapStable(String selector) throws Exception {
        ui.visibleWebBounds(selector);
        android.graphics.Rect[] previous = {null}; long[] since = {0};
        DeviceUi.await("Touch target did not settle after the keyboard transition", 5000, () -> {
            android.graphics.Rect current = ui.webBounds(selector);
            if (!current.equals(previous[0])) { previous[0] = current; since[0] = SystemClock.uptimeMillis(); return false; }
            return SystemClock.uptimeMillis() - since[0] >= 150;
        });
        ui.awaitPaint(".droy-voice-wave"); ui.tap(selector);
    }

    private void replaceInput(String selector, String value) throws Exception {
        ui.showKeyboard(selector);
        long now = SystemClock.uptimeMillis();
        instrumentation.sendKeySync(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON));
        instrumentation.sendKeySync(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL);
        instrumentation.sendStringSync(value);
        DeviceUi.await("Actual keyboard input was not accepted", 5000,
            () -> value.equals(ui.evaluate("document.querySelector(" + JSONObject.quote(selector) + ").value")));
    }

    private boolean keyboardVisible() {
        return ui.keyboardVisible();
    }

    private void configureLayout() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        if (!args.containsKey("layoutTheme") && !"true".equals(args.getString("layoutMaxTextFont"))) return;
        ui.openPermissions();
        ui.tap("[data-droy-settings-section=general]");
        String theme = args.getString("layoutTheme", "light");
        assertTrue(theme.equals("light") || theme.equals("dark"));
        ui.tap("[data-droy-theme-choice=" + theme + "]");
        DeviceUi.await("Requested theme did not apply", 5000,
            () -> theme.equals(preferences.getString("themePreference", "")));
        if ("true".equals(args.getString("layoutMaxTextFont"))) {
            ui.tap("[data-droy-font-expander]");
            String increase = "[data-droy-font-row=size][data-droy-font-role=text] [data-droy-font-step=increase]";
            for (int n = 0; n < 60 && !ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"); n++) ui.tap(increase);
            assertTrue(ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"));
        }
        ui.pressBack();
        DeviceUi.await("Layout settings did not close", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
    }

    private void assertLayout(String selector) throws Exception {
        JSONObject geometry = (JSONObject) ui.evaluate("(()=>{const root=document.querySelector(" + JSONObject.quote(selector)
            + "),r=root.getBoundingClientRect();let fits=true,touches=true;for(const e of root.querySelectorAll('p,h2,h3,h4,label,summary,button')){"
            + "if(!e.getClientRects().length)continue;const w=document.createTreeWalker(e,NodeFilter.SHOW_TEXT);while(w.nextNode()){"
            + "if(!w.currentNode.textContent.trim())continue;const x=document.createRange();x.selectNodeContents(w.currentNode);"
            + "for(const b of x.getClientRects())if(b.width>.1&&(b.left<r.left-1||b.right>r.right+1))fits=false;}}"
            + "const measured=new Set(),smallTargets=[];for(const e of root.querySelectorAll('button,select,input:not([type=checkbox]),summary,.droy-voice-toggle')){"
            + "if(!e.getClientRects().length)continue;const target=e.getAttribute('role')==='switch'?e.closest('label.droy-voice-toggle')||e:e;"
            + "if(measured.has(target))continue;measured.add(target);const b=target.getBoundingClientRect();"
            + "if(b.height<47||b.width<47){touches=false;smallTargets.push({tag:target.tagName,role:target.getAttribute('role'),"
            + "label:target.getAttribute('aria-label'),height:b.height,width:b.width});}}"
            + "return {fits,touches,smallTargets,overflow:root.scrollWidth>root.clientWidth+2||document.documentElement.scrollWidth>innerWidth+1}})()");
        assertTrue("Voice text is clipped: " + geometry, geometry.getBoolean("fits"));
        assertTrue("Voice controls are below 48 CSS pixels: " + geometry, geometry.getBoolean("touches"));
        assertFalse("Voice has horizontal overflow: " + geometry, geometry.getBoolean("overflow"));
    }

    private void capture(String stage) throws Exception { capture(stage, true); }

    private void capture(String stage, boolean awaitPaint) throws Exception {
        if (awaitPaint) {
            ui.awaitPaint(".droy-voice-wave");
        }
        assertTrue("Evidence orientation changed before capture", ui.truth("innerHeight>innerWidth"));
        Bitmap screen = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
        assertNotNull(screen);
        try {
            File directory = new File(context.getExternalFilesDir(null), "voice-ui-evidence");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            String label = InstrumentationRegistry.getArguments().getString("voiceEvidenceLabel", "voice").replaceAll("[^A-Za-z0-9_-]", "_");
            File file = File.createTempFile(label + "-" + stage + "-", ".png", directory);
            try (FileOutputStream output = new FileOutputStream(file)) { assertTrue(screen.compress(Bitmap.CompressFormat.PNG, 100, output)); }
            JSONObject conditions = (JSONObject) ui.evaluate("({width:innerWidth,height:innerHeight,textFont:getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim(),"
                + "theme:document.documentElement.getAttribute('data-ds-theme-source'),voiceState:window.DroyVoice?.getSnapshot().status,recognizerState:document.querySelector('[data-droy-voice-activity]')?.getAttribute('data-droy-voice-recognizer-state')})");
            conditions.put("systemFontScale", activity.getResources().getConfiguration().fontScale);
            conditions.put("legacyDshSpeechSelection", legacySelectionPatch != null);
            if (buttonAppearance != null) conditions.put("holdButton", buttonAppearance);
            Files.write(new File(directory, file.getName() + ".json").toPath(), conditions.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Bundle status = new Bundle();
            status.putString("stream", "\nDROY_VOICE_EVIDENCE " + file.getAbsolutePath() + "\nDROY_VOICE_CONDITIONS " + conditions + "\n");
            instrumentation.sendStatus(2, status);
        } finally { screen.recycle(); }
    }

    private void assertNoInference() throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(home.toPath())) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (!Files.isRegularFile(path) || !path.toString().endsWith(".jsonl")) continue;
                for (String line : Files.readAllLines(path)) if (!line.isBlank()) {
                    JSONObject event = new JSONObject(line);
                    assertNoInferenceEvent(event);
                }
            }
        }
    }

    private static void assertNoInferenceEvent(Object value) throws Exception {
        if (value instanceof JSONObject object) {
            String type = object.optString("type");
            assertFalse("Voice settings invoked inference or a tool", type.startsWith("assistant/") || type.startsWith("tool/") || type.equals("turn/start"));
            for (java.util.Iterator<String> keys = object.keys(); keys.hasNext();) assertNoInferenceEvent(object.get(keys.next()));
        } else if (value instanceof org.json.JSONArray array) {
            for (int i = 0; i < array.length(); i++) assertNoInferenceEvent(array.get(i));
        }
    }

    private static void restore(SharedPreferences preferences, Map<String, ?> values) {
        SharedPreferences.Editor edit = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue(); String key = entry.getKey();
            if (value instanceof String s) edit.putString(key, s);
            else if (value instanceof Boolean b) edit.putBoolean(key, b);
            else if (value instanceof Integer n) edit.putInt(key, n);
            else if (value instanceof Long n) edit.putLong(key, n);
            else if (value instanceof Float n) edit.putFloat(key, n);
            else if (value instanceof java.util.Set<?> set) {
                java.util.Set<String> strings = new java.util.HashSet<>(); for (Object v : set) strings.add((String) v); edit.putStringSet(key, strings);
            } else throw new AssertionError("Unsupported preference type: " + key);
        }
        assertTrue(edit.commit());
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> result = new TreeMap<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return result;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                String name = root.relativize(path).toString();
                if (Files.isSymbolicLink(path)) result.put(name, "link:" + Files.readSymbolicLink(path));
                else if (Files.isDirectory(path)) result.put(name, "directory");
                else result.put(name, RuntimeInstaller.hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            }
        }
        return result;
    }

    private static boolean emulator() {
        return Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")
            || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk_gphone");
    }
}
