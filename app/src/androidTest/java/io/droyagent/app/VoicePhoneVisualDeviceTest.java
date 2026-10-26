package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Phone visual fixtures only: empty input events and cancelled synthetic silence.
 * Never submits text, opens hardware audio, changes configuration, or creates a conversation. */
@RunWith(AndroidJUnit4.class)
public final class VoicePhoneVisualDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private DeviceUi ui;
    private Map<String, String> homeBefore, preferencesBefore;
    private String sessionBefore, nativeJobBefore;
    private boolean wrapped;
    private Thread pointer;
    private Throwable pointerFailure;

    @Test public void existingConversationShowsVoiceStatesWithoutChangingUserData() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        assertFalse("Finish the current task before phone visual inspection", ToolProgress.current(context).getBoolean("active"));
        assertTrue("Finish microphone capture first", AudioCapture.get(context).uiStatus().isNull("active"));
        assertFalse("Finish dictation first", AudioCapture.get(context).dictationActive());
        assertFalse("Disable wake before this visual-only fixture", context.getSharedPreferences("droy-voice-wake", Context.MODE_PRIVATE).getBoolean("enabled", false));
        assertFalse("Finish active voice work first", VoiceWake.hasActiveWork(context));
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ui = new DeviceUi(instrumentation, activity); ui.awaitPage(); ui.hideKeyboard();
        DeviceUi.await("Voice input is not ready", 15000,
            () -> ui.truth("!!document.querySelector('[data-droy-voice-activity][data-droy-voice-recognizer-state=ready]')"));
        assertTrue("Finish the existing voice interaction first", ui.truth("!window.DroyVoice.getSnapshot().mode"));
        sessionBefore = String.valueOf(ui.evaluate("[...document.querySelectorAll('[data-conversation-session]')].find(e=>e.getClientRects().length)?.getAttribute('data-conversation-session')||''"));
        assertFalse("Open an existing conversation before this test; it must not create a new one", sessionBefore.isBlank());
        assertFalse("Finish native recognition before this fixture", AudioTranscription.get(context).hasActiveJobs());
        nativeJobBefore = nativeJob();
        homeBefore = snapshotHome(); preferencesBefore = snapshotPreferences();
        installVisualTransport();

        tap("[data-droy-voice-start=standard]");
        DeviceUi.await("The three voice controls did not open", 10000,
            () -> ui.truth("window.DroyVoice.getSnapshot().status==='active'&&window.__voicePhoneVisual.polls>=6"));
        assertTrue("Constant measured input did not fill waveform history", ui.truth("[...document.querySelectorAll('[data-droy-voice-inline] .droy-voice-wave i')].filter(e=>e.style.transform!=='scaleY(0.06)').length>1"));
        DeviceUi.await("Changing input did not reach the waveform", 10000, () -> ui.truth("window.__voicePhoneVisual.polls>=26"));
        assertEquals("Visual start changed the selected conversation", sessionBefore, ui.evaluate("window.DroyVoice.getSnapshot().sessionId"));
        assertInline(false); capture("continuous-changing-levels", "[data-droy-voice-inline]");
        tap("[data-droy-voice-mute]");
        DeviceUi.await("Mute did not flatten the waveform", 5000, () -> ui.truth("window.DroyVoice.getSnapshot().muted&&"
            + "[...document.querySelectorAll('[data-droy-voice-inline] .droy-voice-wave i')].every(e=>e.style.transform==='scaleY(0.06)')"));
        assertInline(true); capture("continuous-muted", "[data-droy-voice-inline]");
        tap("[data-droy-voice-end]");
        DeviceUi.await("Voice controls did not close", 5000, () -> ui.truth("!window.DroyVoice.getSnapshot().mode"));

        exerciseLoadingCancellation();
        exerciseSyntheticHoldCancellation();
        assertEquals("Visual preview created an ASR job", nativeJobBefore, nativeJob());
        assertTrue("Visual preview opened hardware audio", AudioCapture.get(context).uiStatus().isNull("active"));
        assertFalse("Visual preview claimed hardware dictation", AudioCapture.get(context).dictationActive());
        assertEquals("Visual preview changed the selected conversation", sessionBefore,
            ui.evaluate("[...document.querySelectorAll('[data-conversation-session]')].find(e=>e.getClientRects().length)?.getAttribute('data-conversation-session')||''"));
    }

    private void installVisualTransport() throws Exception {
        ui.evaluate("(()=>{const originalFetch=window.fetch,devices=navigator.mediaDevices,originalMedia=devices.getUserMedia;"
            + "const state=window.__voicePhoneVisual={polls:0,muted:false,delayClaim:false,claimStarted:false,claimFinished:false,recorded:false,mediaCalls:0};"
            + "const inspect=node=>{if(node.nodeType===1&&(node.matches('[data-droy-voice-hold-state=recording]')||node.querySelector('[data-droy-voice-hold-state=recording]')))state.recorded=true};"
            + "state.observer=new MutationObserver(rows=>{for(const row of rows){if(row.type==='attributes'){inspect(row.target);if(row.oldValue==='recording')state.recorded=true;}for(const node of row.addedNodes)inspect(node)}});"
            + "state.observer.observe(document.body,{childList:true,subtree:true,attributes:true,attributeOldValue:true,attributeFilter:['data-droy-voice-hold-state']});"
            + "window.fetch=async function(url,options){if(url==='/droy/voice'&&options?.body){const q=JSON.parse(options.body);let result;"
            + "if(q.operation==='start')result={state:'recording',captureId:'phone-visual-fixture'};"
            + "if(q.operation==='poll'){state.polls++;result={state:'recording',captureId:'phone-visual-fixture',segments:[],level:state.muted?0:state.polls<=8?.04:[.0004,.006,.021,.003,.04,.012][state.polls%6]};}"
            + "if(q.operation==='mute'){state.muted=q.muted;result={muted:q.muted};}if(q.operation==='stop')result={state:'ended'};"
            + "if(result)return new Response(JSON.stringify({ok:true,result}),{headers:{'Content-Type':'application/json'}});"
            + "if(q.operation==='wake.claim'&&state.delayClaim){state.claimStarted=true;await new Promise(resolve=>setTimeout(resolve,2000));"
            + "try{return await originalFetch.apply(this,arguments)}finally{state.claimFinished=true}}}return originalFetch.apply(this,arguments)};"
            + "devices.getUserMedia=async constraints=>{if(!constraints?.audio)return originalMedia.call(devices,constraints);state.mediaCalls++;"
            + "state.audio=new AudioContext();state.destination=state.audio.createMediaStreamDestination();state.source=state.audio.createConstantSource();"
            + "state.source.offset.value=0;state.source.connect(state.destination);state.source.start();await state.audio.resume();return state.destination.stream};"
            + "state.cancelGuard=event=>{if(state.guardHold&&event.target.closest?.('[data-droy-voice-hold]')&&!window.DroyVoice.getSnapshot().holdCancelled){state.safetyCancelled=true;window.DroyVoice.moveHold(true)}};"
            + "document.addEventListener('pointerup',state.cancelGuard,true);"
            + "state.cleanup=()=>{window.fetch=originalFetch;devices.getUserMedia=originalMedia;state.observer.disconnect();document.removeEventListener('pointerup',state.cancelGuard,true);try{state.source?.stop()}catch{}"
            + "state.destination?.stream.getTracks().forEach(track=>track.stop());state.audio?.close()};})()");
        wrapped = true;
    }

    private void exerciseLoadingCancellation() throws Exception {
        ui.evaluate("Object.assign(window.__voicePhoneVisual,{delayClaim:true,claimStarted:false,claimFinished:false,recorded:false})");
        Rect button = ui.visibleWebBounds("[data-droy-voice-hold]");
        pointerFailure = null;
        pointer = new Thread(() -> {
            try { shellTouch("swipe " + button.centerX() + " " + button.centerY() + " " + button.centerX() + " " + button.centerY() + " 1400"); }
            catch (Throwable failure) { pointerFailure = failure; }
        }, "phone-voice-loading-touch");
        pointer.start();
        DeviceUi.await("The delayed real claim did not show loading", 5000,
            () -> ui.truth("window.__voicePhoneVisual.claimStarted&&!!document.querySelector('[data-droy-voice-hold-state=starting] .droy-voice-loading')"));
        assertHold("starting"); capture("hold-loading", ".droy-voice-hold-sheet");
        joinPointer();
        DeviceUi.await("Releasing during loading did not finish cancellation", 10000,
            () -> ui.truth("window.__voicePhoneVisual.claimFinished&&!window.DroyVoice.getSnapshot().mode&&!document.querySelector('[data-droy-voice-hold-overlay]')"));
        assertFalse("Loading cancellation entered recording", ui.truth("window.__voicePhoneVisual.recorded"));
        assertEquals("Loading cancellation opened a media source", 0, ((Number) ui.evaluate("window.__voicePhoneVisual.mediaCalls")).intValue());
        ui.evaluate("window.__voicePhoneVisual.delayClaim=false");
    }

    private void exerciseSyntheticHoldCancellation() throws Exception {
        // Prevent an unsuccessful test gesture from submitting even synthetic audio on a personal phone.
        ui.evaluate("Object.assign(window.__voicePhoneVisual,{guardHold:true,safetyCancelled:false})");
        Rect button = ui.visibleWebBounds("[data-droy-voice-hold]");
        int endY = button.centerY() - (int) Math.round(button.height() * 120.0 / 48.0);
        assertTrue("The hold button has insufficient visible space for the cancel gesture", endY > 0);
        pointerFailure = null;
        pointer = new Thread(() -> {
            try { shellTouch("swipe " + button.centerX() + " " + button.centerY() + " " + button.centerX() + " " + endY + " 5000"); }
            catch (Throwable failure) { pointerFailure = failure; }
        }, "phone-voice-cancel-touch");
        pointer.start();
        DeviceUi.await("Actual DSH recorder did not show the silent input", 5000,
            () -> ui.truth("!!document.querySelector('[data-droy-voice-hold-state=recording]')&&window.__voicePhoneVisual.mediaCalls===1"));
        assertHold("recording"); capture("hold-synthetic-silence", ".droy-voice-hold-sheet");
        DeviceUi.await("The real upward gesture did not select cancel", 5000,
            () -> ui.truth("!!document.querySelector('[data-droy-voice-hold-state=cancelling]')"));
        assertHold("cancelling"); capture("hold-slide-cancel", ".droy-voice-hold-sheet");
        joinPointer();
        DeviceUi.await("Releasing the cancel gesture did not close the card", 10000,
            () -> ui.truth("!window.DroyVoice.getSnapshot().mode&&!document.querySelector('[data-droy-voice-hold-overlay]')"));
        assertFalse("The real gesture did not cancel before the fixture's release guard", ui.truth("window.__voicePhoneVisual.safetyCancelled"));
        assertTrue("Cancellation did not dispose synthetic tracks", ui.truth("window.__voicePhoneVisual.destination.stream.getTracks().every(t=>t.readyState==='ended')"));
        assertEquals("Cancelled audio reached native ASR", nativeJobBefore, nativeJob());
    }

    private void assertInline(boolean muted) throws Exception {
        ui.awaitPaint(".droy-voice-wave");
        JSONObject geometry = (JSONObject) ui.evaluate("(()=>{const row=document.querySelector('[data-droy-voice-inline]'),wave=row.querySelector('.droy-voice-wave'),controls=row.querySelector('.droy-voice-controls'),"
            + "r=row.getBoundingClientRect(),w=wave.getBoundingClientRect(),c=controls.getBoundingClientRect(),buttons=[...controls.querySelectorAll('button')].map(e=>e.getBoundingClientRect());"
            + "return {bars:wave.children.length,separated:w.right<=c.left-4,inside:w.left>=r.left&&c.right<=r.right+1,"
            + "buttons:buttons.length,touches:buttons.every(b=>b.width>=47&&b.height>=47),oneRow:buttons.every(b=>Math.abs(b.top-buttons[0].top)<2),"
            + "centered:buttons.every(b=>Math.abs((w.top+w.bottom-b.top-b.bottom)/2)<1),"
            + "varied:new Set([...wave.children].map(e=>e.style.transform)).size>3,flat:[...wave.children].every(e=>e.style.transform==='scaleY(0.06)'),"
            + "overflow:document.documentElement.scrollWidth>innerWidth+1}})()");
        assertEquals(24, geometry.getInt("bars")); assertEquals(3, geometry.getInt("buttons"));
        assertTrue("Wave overlaps controls: " + geometry, geometry.getBoolean("separated"));
        assertTrue("Inline content escapes its row: " + geometry, geometry.getBoolean("inside"));
        assertTrue("Inline controls are too small: " + geometry, geometry.getBoolean("touches"));
        assertTrue("Inline controls are not on one row: " + geometry, geometry.getBoolean("oneRow"));
        assertTrue("Waveform is not vertically centered with each control: " + geometry, geometry.getBoolean("centered"));
        assertFalse("Inline view overflows: " + geometry, geometry.getBoolean("overflow"));
        assertTrue("Waveform does not match the input fixture: " + geometry, geometry.getBoolean(muted ? "flat" : "varied"));
    }

    private void assertHold(String phase) throws Exception {
        JSONObject geometry = (JSONObject) ui.evaluate("(()=>{const overlay=document.querySelector('[data-droy-voice-hold-overlay]'),card=overlay.querySelector('.droy-voice-hold-sheet'),r=card.getBoundingClientRect(),"
            + "color=getComputedStyle(card).backgroundColor.match(/[\\d.]+/g)?.map(Number)||[];return {phase:overlay.getAttribute('data-droy-voice-hold-state'),width:r.width,"
            + "fits:r.left>=0&&r.right<=innerWidth&&r.top>=0&&r.bottom<=innerHeight,neutral:color.length>=3&&Math.max(...color.slice(0,3))-Math.min(...color.slice(0,3))<=12,"
            + "bars:card.querySelectorAll('.droy-voice-wave i').length,dots:card.querySelectorAll('.droy-voice-loading i').length,"
            + "label:card.querySelector('.droy-voice-hold-label').textContent,hint:card.querySelector('.droy-voice-hold-hint').textContent,"
            + "extraFeedback:[...document.querySelectorAll('.droy-voice-feedback')].filter(e=>e.getClientRects().length).length}})()");
        assertEquals(phase, geometry.getString("phase"));
        assertTrue("Hold card exceeds260px: " + geometry, geometry.getDouble("width") <= 260.1);
        assertTrue("Hold card leaves the viewport: " + geometry, geometry.getBoolean("fits"));
        assertTrue("Hold card is not neutral: " + geometry, geometry.getBoolean("neutral"));
        if (phase.equals("recording")) {
            assertEquals(24, geometry.getInt("bars")); assertEquals("松开发送", geometry.getString("label")); assertEquals("上滑取消", geometry.getString("hint"));
        } else if (phase.equals("starting")) {
            assertEquals(3, geometry.getInt("dots")); assertEquals(0, geometry.getInt("extraFeedback"));
        } else if (phase.equals("cancelling")) assertEquals("松开取消", geometry.getString("label"));
    }

    private void tap(String selector) throws Exception {
        Rect bounds = ui.visibleWebBounds(selector); ui.awaitPaint(".droy-voice-wave");
        shellTouch("tap " + bounds.centerX() + " " + bounds.centerY());
    }

    private void shellTouch(String arguments) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand("input touchscreen " + arguments))) {
            byte[] block = new byte[1024];
            for (int count; (count = input.read(block)) != -1;) {
                output.write(block, 0, count); assertTrue("Touch command returned excessive output", output.size() <= 4096);
            }
        }
        assertTrue("Touch command failed: " + output.toString("UTF-8"), output.toString("UTF-8").isBlank());
    }

    private void joinPointer() throws Exception {
        if (pointer == null) return;
        pointer.join(10000); assertFalse("Phone pointer did not release", pointer.isAlive()); pointer = null;
        if (pointerFailure != null) throw new AssertionError("Phone touch failed", pointerFailure);
    }

    private String nativeJob() throws Exception {
        JSONObject job = AudioTranscription.get(context).uiStatus().optJSONObject("job");
        return job == null ? "" : job.optString("transcriptId");
    }

    private void capture(String stage, String selector) throws Exception {
        ui.awaitPaint(".droy-voice-wave"); Rect crop = ui.webBounds(selector);
        Bitmap screen = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot(); assertNotNull(screen);
        try {
            assertTrue(crop.intersect(0, 0, screen.getWidth(), screen.getHeight()) && !crop.isEmpty());
            Bitmap image = Bitmap.createBitmap(screen, crop.left, crop.top, crop.width(), crop.height());
            try {
                File directory = new File(context.getExternalFilesDir(null), "voice-phone-visual-evidence"); assertTrue(directory.isDirectory() || directory.mkdirs());
                File file = File.createTempFile("phone-" + stage + "-", ".png", directory);
                try (FileOutputStream output = new FileOutputStream(file)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); }
                JSONObject conditions = (JSONObject) ui.evaluate("({width:innerWidth,height:innerHeight,theme:document.documentElement.getAttribute('data-ds-theme-source'),status:window.DroyVoice.getSnapshot().status})");
                conditions.put("inputTransport", "memory_fixture_empty_segments").put("holdSource", "synthetic_silence_cancelled").put("hardwareMicrophone", false);
                Files.write(new File(directory, file.getName() + ".json").toPath(), conditions.toString().getBytes(StandardCharsets.UTF_8));
                Bundle status = new Bundle(); status.putString("stream", "\nDROY_VOICE_PHONE_VISUAL " + file.getAbsolutePath() + "\n"); instrumentation.sendStatus(2, status);
            } finally { image.recycle(); }
        } finally { screen.recycle(); }
    }

    @After public void restoreMemoryAndCheckOriginalData() throws Exception {
        if (ui == null) return;
        try {
            try { joinPointer(); }
            finally { if (wrapped) {
                ui.evaluate("window.__voicePhoneEnded=false;window.DroyVoice.end().finally(()=>{window.__voicePhoneVisual.cleanup();window.__voicePhoneEnded=true})");
                DeviceUi.await("Visual fixture did not release its controls", 10000, () -> ui.truth("window.__voicePhoneEnded"));
                assertTrue("Visual fixture opened native input", AudioCapture.get(context).uiStatus().isNull("active"));
                assertFalse("Visual fixture left a dictation lease", AudioCapture.get(context).dictationActive());
                assertEquals("Visual fixture created an ASR job", nativeJobBefore, nativeJob());
            } }
        } finally {
            if (homeBefore != null) assertEquals("Visual fixture changed original DSH files", homeBefore, snapshotHome());
            if (preferencesBefore != null) assertEquals("Visual fixture changed preferences", preferencesBefore, snapshotPreferences());
        }
    }
    private Map<String, String> snapshotHome() throws Exception {
        Path root = new File(context.getFilesDir(), "dsh-home").toPath();
        Map<String, String> result = new TreeMap<>();
        if (!Files.exists(root)) return result;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                String name = root.relativize(path).toString();
                if (Files.isSymbolicLink(path)) result.put(name, hash(Files.readSymbolicLink(path).toString()));
                else if (Files.isDirectory(path)) result.put(name, "directory");
                else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (InputStream input = Files.newInputStream(path)) {
                        byte[] buffer = new byte[65536];
                        for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
                    }
                    result.put(name, RuntimeInstaller.hex(digest.digest()));
                }
            }
        }
        return result;
    }

    private Map<String, String> snapshotPreferences() throws Exception {
        Map<String, String> result = new TreeMap<>();
        File[] files = new File(context.getApplicationInfo().dataDir, "shared_prefs").listFiles();
        if (files == null) return result;
        for (File file : files) {
            if (!file.getName().endsWith(".xml")) continue;
            String name = file.getName().substring(0, file.getName().length() - 4);
            Map<String, ?> values = new TreeMap<>(context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll());
            StringBuilder value = new StringBuilder();
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                Object item = entry.getValue();
                if (item instanceof java.util.Set<?> set) {
                    TreeSet<String> sorted = new TreeSet<>(); for (Object member : set) sorted.add((String) member); item = sorted;
                }
                value.append(entry.getKey().length()).append(':').append(entry.getKey()).append(':')
                    .append(item.getClass().getName()).append(':').append(item).append('\n');
            }
            result.put(name, hash(value.toString()));
        }
        return result;
    }

    private static String hash(String value) throws Exception {
        return RuntimeInstaller.hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
