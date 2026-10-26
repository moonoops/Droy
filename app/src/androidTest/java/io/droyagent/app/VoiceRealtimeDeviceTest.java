package io.droyagent.app;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Optional Comni media transport acceptance with the host microphone disabled and a synthetic camera. */
@RunWith(AndroidJUnit4.class)
public final class VoiceRealtimeDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences preferences, wakePreferences;
    private Map<String, ?> savedPreferences, savedWakePreferences;
    private File home, preserved;
    private Map<String, String> originalFiles;
    private MainActivity activity;
    private DeviceUi ui;
    private String serviceUrl, captureId;
    private File referenceWav;
    private Uri referenceDownload;
    private Instrumentation.ActivityMonitor referencePicker;
    private Rect saveTapBounds;
    private final JSONArray nativeSaveTouchEvents = new JSONArray();
    private boolean owned, hadHome, rotated, passed;

    @Before public void setUp() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        serviceUrl = arguments.getString("voiceRealtimeUrl", "");
        Assume.assumeTrue("Real realtime endpoint not supplied; Comni device acceptance was not run", !serviceUrl.isBlank());
        assertEquals("This fixture supports the explicitly configured Comni service", "comni", arguments.getString("voiceRealtimeProvider", "comni"));
        assertEquals("Confirm -no-audio, hostmic off and synthetic emulator cameras before this test", "true", arguments.getString("voiceSyntheticMedia"));
        java.net.URI address = java.net.URI.create(serviceUrl);
        assertTrue("Pass a complete ws:// or wss:// Comni endpoint", ("ws".equals(address.getScheme()) || "wss".equals(address.getScheme())) && address.getHost() != null);
        assertNull("Credentials must not be embedded in the endpoint", address.getUserInfo());
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Real media fixtures require a dedicated emulator", emulator());
        String referencePath = arguments.getString("voiceReferenceWav", "");
        assertFalse("Pass voiceReferenceWav pointing to the prepared app-private stock WAV", referencePath.isBlank());
        referenceWav = new File(referencePath);
        assertTrue("The stock reference WAV must be inside the app's private files directory",
            referenceWav.getCanonicalPath().startsWith(context.getFilesDir().getCanonicalPath() + File.separator));
        assertTrue("The prepared stock reference WAV is missing or empty", referenceWav.isFile() && referenceWav.length() > 0);
        assertEquals("Grant RECORD_AUDIO before starting this synthetic-media fixture", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
        assertEquals("Grant CAMERA before starting this synthetic-media fixture", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.CAMERA));
        assertTrue("Stop existing tasks and the engine before realtime acceptance", RuntimeService.canManageHistory());
        assertTrue("Do not interrupt an existing capture", AudioCapture.get(context).uiStatus().isNull("active"));
        UiAutomation automation = instrumentation.getUiAutomation();
        preferences = ProductPermissions.preferences(context);
        wakePreferences = context.getSharedPreferences("droy-voice-wake", Context.MODE_PRIVATE);
        savedPreferences = new HashMap<>(preferences.getAll());
        savedWakePreferences = new HashMap<>(wakePreferences.getAll());
        home = new File(context.getFilesDir(), "dsh-home");
        preserved = new File(context.getFilesDir(), "droy-voice-realtime-home-backup-" + UUID.randomUUID());
        originalFiles = snapshot(home.toPath());
        hadHome = home.exists();
        if (hadHome) assertTrue("Could not preserve the original DSH home", home.renameTo(preserved));
        owned = true;
        assertTrue(home.mkdirs());
        assertTrue(preferences.edit().putBoolean(ProductPermissions.STOPPED, false).putBoolean("allowAudioMicrophone", true)
            .putBoolean("petEnabled", false).putString("petExternalDisplay", "off").putString("petInternalDisplay", "off")
            .putBoolean("backgroundRuntimeEnabled", false).commit());
        assertTrue(wakePreferences.edit().putBoolean("enabled", false).commit());
        assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0));
        rotated = true;
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
        assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0));
        DeviceUi.await("Realtime fixture did not reach portrait after Activity launch", 5000,
            () -> ui.truth("innerHeight>innerWidth"));
        DeviceUi.await("Voice plugin did not become ready", 15000,
            () -> ui.truth("!!window.DroyVoice?.getSnapshot().config"));
    }

    @After public void tearDown() throws Exception {
        if (!owned) return;
        try {
            try {
                if (!passed && ui != null && activity != null && !activity.isDestroyed()) {
                    reportSettings("failure");
                    capture("failure");
                }
            } finally {
                try {
                    if (ui != null && activity != null && !activity.isDestroyed()) {
                        ui.evaluate("void window.DroyVoice?.end()");
                        DeviceUi.await("Realtime call did not end during cleanup", 12000,
                            () -> ui.truth("!window.DroyVoice?.getSnapshot().mode"));
                    }
                } finally {
                    try {
                        if (activity != null && !activity.isDestroyed()) instrumentation.runOnMainSync(activity::finish);
                    } finally {
                        instrumentation.runOnMainSync(() -> RuntimeService.requestStop(context));
                        DeviceUi.await("Realtime fixture left the engine running", 20000, RuntimeService::canManageHistory);
                    }
                }
            }
            DeviceUi.await("Realtime fixture left a microphone active", 5000,
                () -> AudioCapture.get(context).uiStatus().isNull("active"));
            if (captureId != null) {
                VoiceMedia.get(context).request(new JSONObject().put("operation", "dispose"));
                AudioCapture.get(context).deleteFromUi(captureId);
            }
            assertNoDshInference();
        } finally {
            try {
                if (rotated) instrumentation.getUiAutomation().setRotation(UiAutomation.ROTATION_UNFREEZE);
                assertTrue("Preserve the original profile until the fixture runtime stops", RuntimeService.canManageHistory());
                HistoryData.deleteTree(home.toPath());
                if (hadHome) assertTrue("Could not restore the original DSH home", preserved.renameTo(home));
                assertEquals("Realtime acceptance changed the original DSH profile", originalFiles, snapshot(home.toPath()));
                restore(preferences, savedPreferences);
                restore(wakePreferences, savedWakePreferences);
                owned = false;
            } finally {
                if (referencePicker != null) instrumentation.removeMonitor(referencePicker);
                if (referenceDownload != null) {
                    context.revokeUriPermission(referenceDownload, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    assertEquals("Could not remove the selected stock reference download", 1,
                        context.getContentResolver().delete(referenceDownload, null, null));
                }
            }
        }
    }

    @Test public void hostMicDisabledEmulatorMediaReachesComniAndTheSameCallReleasesItsResources() throws Exception {
        ui.openPermissions();
        ui.tap("[data-droy-settings-section=general]");
        ui.tap("[data-droy-voice-settings-open]");
        DeviceUi.await("Voice settings did not open", 5000,
            () -> ui.truth("!!document.querySelector('[data-droy-voice-settings]')"));
        setEntry("realtimeVoice", true);
        setEntry("realtimeVideo", true);
        selectProvider();
        replaceInput("[data-droy-voice-url]", serviceUrl);
        ui.pressBack();
        DeviceUi.await("Endpoint keyboard did not close", 5000, () -> !ui.keyboardVisible());
        reportSettings("before-service-save");
        android.webkit.WebView saveTouchView = ui.web();
        try {
            saveTapBounds = ui.visibleWebBounds("[data-droy-voice-save]");
            instrumentation.runOnMainSync(() -> saveTouchView.setOnTouchListener((view, event) -> {
                try {
                    nativeSaveTouchEvents.put(new JSONObject().put("action", event.getActionMasked())
                        .put("x", event.getX()).put("y", event.getY()).put("rawX", event.getRawX()).put("rawY", event.getRawY())
                        .put("source", event.getSource()).put("deviceId", event.getDeviceId()));
                } catch (org.json.JSONException invalid) { throw new AssertionError("Invalid native touch diagnostic", invalid); }
                return false;
            }));
            ui.evaluate("window.__voiceSaveTouchEvents=[];window.__voiceSaveTouchListener=e=>{const target=e.target;"
                + "const p=e.touches?.[0]||e.changedTouches?.[0]||e;"
                + "window.__voiceSaveTouchEvents.push({type:e.type,tag:target?.tagName,ariaLabel:target?.getAttribute?.('aria-label'),"
                + "save:!!target?.closest?.('[data-droy-voice-save]'),clientX:p.clientX,clientY:p.clientY,isTrusted:e.isTrusted});"
                + "if(window.__voiceSaveTouchEvents.length>32)window.__voiceSaveTouchEvents.shift()};"
                + "for(const type of ['pointerdown','pointerup','touchstart','touchend','mousedown','mouseup','click'])document.addEventListener(type,window.__voiceSaveTouchListener,true)");
            reportSettings("save-injection-target");
            ui.tapAt(saveTapBounds.centerX(), saveTapBounds.centerY());
            reportSettings("after-service-save-touch");
            DeviceUi.await("Actual Comni service settings were not saved", 10000,
                () -> ui.truth("window.DroyVoice.getSnapshot().config.realtime.provider==='comni'&&window.DroyVoice.getSnapshot().config.realtime.url===" + JSONObject.quote(serviceUrl)));
        } catch (AssertionError failure) {
            throw new AssertionError("Actual Comni service settings were not saved; " + settingsDiagnostic(), failure);
        } finally {
            instrumentation.runOnMainSync(() -> saveTouchView.setOnTouchListener(null));
            ui.evaluate("for(const type of ['pointerdown','pointerup','touchstart','touchend','mousedown','mouseup','click'])document.removeEventListener(type,window.__voiceSaveTouchListener,true);"
                + "delete window.__voiceSaveTouchListener");
        }
        importStockReferenceWav();
        ui.tap("[data-droy-voice-settings-close]");
        DeviceUi.await("Voice settings did not close", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-voice-settings]')"));
        ui.pressBack();
        DeviceUi.await("General settings did not return to chat", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        ui.tap("[data-droy-voice-start=realtime]");
        DeviceUi.await("The real Comni call did not become active", 90000, () -> {
            JSONObject nativeState = nativeState();
            String id = nativeState.optString("captureId");
            if (!id.isEmpty()) captureId = id;
            JSONObject remote = realtimeState();
            assertFalse("Comni transport failed: " + remote.optString("error"), "failed".equals(remote.optString("status")));
            return "recording".equals(nativeState.optString("state")) && "active".equals(remote.optString("status"))
                && remote.optLong("sentAudioFrames") > 0;
        });
        JSONObject initial = realtimeState();
        String callId = initial.getString("id");
        assertEquals("comni", initial.getString("provider"));
        assertTrue(initial.getLong("sentAudioBytes") > 0);
        recordEmulatorInputLevel();
        assertTrue(ui.truth("!!document.querySelector('[data-droy-voice-call]')&&!window.DroyVoice.getSnapshot().videoEnabled"));
        capture("audio-connected");

        ui.tap("[data-droy-voice-mute]");
        DeviceUi.await("Mute did not reach the native capture", 5000,
            () -> nativeState().getBoolean("muted") && ui.truth("window.DroyVoice.getSnapshot().muted"));
        ui.tap("[data-droy-voice-mute]");
        DeviceUi.await("Unmute did not reach the native capture", 5000,
            () -> !nativeState().getBoolean("muted") && ui.truth("!window.DroyVoice.getSnapshot().muted"));

        ui.tap("[data-droy-voice-camera]");
        DeviceUi.await("The synthetic camera did not produce a live media track", 15000,
            () -> ui.truth("(()=>{const s=window.DroyVoice.getSnapshot(),v=document.querySelector('[data-droy-voice-call] video');"
                + "return s.videoEnabled&&s.stream?.active&&s.stream.getVideoTracks().some(t=>t.readyState==='live')&&v?.videoWidth>0&&v?.videoHeight>0})()"));
        DeviceUi.await("No camera frame was actually sent on the Comni socket", 30000,
            () -> realtimeState().optLong("sentVideoFrames") > 0);
        JSONObject video = realtimeState();
        assertEquals("Opening the camera replaced the call", callId, video.getString("id"));
        assertTrue(video.getBoolean("video"));
        assertEquals("recording", nativeState().getString("state"));
        ui.evaluate("window.__voiceRealtimeTracks=window.DroyVoice.getSnapshot().stream.getTracks().slice()");
        capture("video-frame-sent");
        report(video, "video");

        ui.tap("[data-droy-voice-call] [data-droy-voice-end]");
        DeviceUi.await("Ending the call did not release its native microphone and camera", 12000,
            () -> AudioCapture.get(context).uiStatus().isNull("active")
                && !VoiceMedia.hasActiveWork(context)
                && ui.truth("!window.DroyVoice.getSnapshot().mode&&!window.DroyVoice.getSnapshot().stream&&window.__voiceRealtimeTracks.every(t=>t.readyState==='ended')"));
        JSONObject ended = realtimeState();
        assertEquals("ended", ended.getString("status"));
        assertFalse(nativeState().getBoolean("speaking"));
        report(ended, "ended");
        capture("ended");
        passed = true;
    }

    private void importStockReferenceWav() throws Exception {
        String selectedName = "droy-voice-stock-reference-" + UUID.randomUUID() + ".wav";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, selectedName);
        values.put(MediaStore.Downloads.MIME_TYPE, "audio/wav");
        values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/DroyAgentTests");
        referenceDownload = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        assertNotNull("Could not create the explicit stock WAV selection", referenceDownload);
        try (var output = context.getContentResolver().openOutputStream(referenceDownload)) {
            assertNotNull(output);
            Files.copy(referenceWav.toPath(), output);
        }
        context.grantUriPermission(context.getPackageName(), referenceDownload, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        IntentFilter documents = new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);
        documents.addCategory(Intent.CATEGORY_OPENABLE);
        documents.addDataType("*/*");
        referencePicker = new Instrumentation.ActivityMonitor(documents,
            new Instrumentation.ActivityResult(Activity.RESULT_OK,
                new Intent().setData(referenceDownload).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)), true);
        instrumentation.addMonitor(referencePicker);
        try {
            ui.tap("[data-droy-voice-reference-import]");
            DeviceUi.await("The real reference import did not request the system document picker", 10000,
                () -> referencePicker.getHits() > 0);
            assertEquals("Reference import requested more than one picker", 1, referencePicker.getHits());
            DeviceUi.await("The selected WAV was not decoded and uploaded by the browser", 30000, () -> {
                assertFalse("Browser reference import failed: " + ui.evaluate("document.querySelector('[data-droy-voice-reference-error]')?.textContent||''"),
                    ui.truth("!!document.querySelector('[data-droy-voice-reference-error]')"));
                return ui.truth("window.DroyVoice.getSnapshot().config.realtime.hasReferenceAudio===true&&!window.DroyVoice.getSnapshot().busy"
                    + "&&window.DroyVoice.getSnapshot().config.realtime.referenceAudioName===" + JSONObject.quote(selectedName));
            });
            assertFalse("The document picker remained open after import", ChatFileTransfers.isPickerOpen());
            assertEquals(selectedName, ui.evaluate("document.querySelector('[data-droy-voice-reference-name]').textContent"));
            assertTrue("Importing stock WAV unexpectedly opened the microphone", AudioCapture.get(context).uiStatus().isNull("active"));
            ui.evaluate("window.__voiceReferenceRead=null;window.DroyVoice.refresh().then(result=>{const r=result.config.realtime;"
                + "window.__voiceReferenceRead={ok:true,hasReferenceAudio:r.hasReferenceAudio,name:r.referenceAudioName,seconds:r.referenceAudioSeconds}},"
                + "error=>window.__voiceReferenceRead={ok:false,error:error.message})");
            DeviceUi.await("The persisted reference metadata GET did not respond", 10000,
                () -> ui.truth("window.__voiceReferenceRead!==null"));
            JSONObject stored = (JSONObject) ui.evaluate("window.__voiceReferenceRead");
            assertTrue("Reference metadata GET failed", stored.getBoolean("ok"));
            assertTrue("GET did not retain the reference audio", stored.getBoolean("hasReferenceAudio"));
            assertEquals(selectedName, stored.getString("name"));
            assertTrue("GET reference duration is outside the supported range", stored.getDouble("seconds") >= .5 && stored.getDouble("seconds") <= 30);
            Bundle report = new Bundle(); report.putString("stream", "\nDROY_VOICE_REALTIME_REFERENCE " + stored + "\n");
            instrumentation.sendStatus(2, report);
            capture("reference-imported");
        } finally {
            instrumentation.removeMonitor(referencePicker);
            referencePicker = null;
        }
    }

    private void recordEmulatorInputLevel() throws Exception {
        AudioCapture.FrameRead read = AudioCapture.get(context).readFrames(captureId, VoiceMedia.OWNER, 0, 32768);
        assertTrue("Native recording produced no PCM frames", read.data.length > 0);
        assertEquals("Native PCM16 ended inside a sample", 0, read.data.length % 2);
        // A virtual audio HAL can produce a signal while host microphone input is disabled.
        double squareSum = 0;
        int peak = 0;
        for (int index = 0; index < read.data.length; index += 2) {
            int sample = (read.data[index] & 0xff) | (read.data[index + 1] << 8);
            squareSum += (double) sample * sample;
            peak = Math.max(peak, Math.abs(sample));
        }
        int samples = read.data.length / 2;
        JSONObject evidence = new JSONObject().put("input", "host-mic-disabled-emulator")
            .put("bytes", read.data.length).put("samples", samples)
            .put("rmsPcm16", Math.sqrt(squareSum / samples)).put("peakPcm16", peak);
        Bundle status = new Bundle(); status.putString("stream", "\nDROY_VOICE_REALTIME_INPUT " + evidence + "\n");
        instrumentation.sendStatus(2, status);
    }

    private JSONObject nativeState() throws Exception {
        return VoiceMedia.get(context).request(new JSONObject().put("operation", "status"));
    }

    private JSONObject realtimeState() throws Exception {
        ui.evaluate("window.__voiceRealtimeProbe=null;window.DroyVoice.request('poll_realtime').then(result=>window.__voiceRealtimeProbe={ok:true,result},"
            + "error=>window.__voiceRealtimeProbe={ok:false,error:error.message})");
        DeviceUi.await("Realtime service state did not respond", 5000,
            () -> ui.truth("window.__voiceRealtimeProbe!==null"));
        JSONObject response = (JSONObject) ui.evaluate("window.__voiceRealtimeProbe");
        assertTrue("Realtime service state failed: " + response.optString("error"), response.getBoolean("ok"));
        return response.getJSONObject("result");
    }

    private void setEntry(String name, boolean enabled) throws Exception {
        if (ui.truth("window.DroyVoice.getSnapshot().config.entries[" + JSONObject.quote(name) + "]===" + enabled)) return;
        ui.tap("[data-droy-voice-entry-toggle=" + name + "]");
        DeviceUi.await("The realtime entrance touch was not saved", 5000,
            () -> ui.truth("window.DroyVoice.getSnapshot().config.entries[" + JSONObject.quote(name) + "]===" + enabled));
    }

    private void selectProvider() throws Exception {
        ui.tap("[data-droy-voice-provider]");
        DeviceUi.await("The Comni provider option was not shown", 5000, () -> {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root == null) return false;
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("MiniCPM · Comni")) {
                if (!"MiniCPM · Comni".contentEquals(node.getText() == null ? "" : node.getText())) continue;
                Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                if (bounds.isEmpty()) continue;
                ui.tapAt(bounds.centerX(), bounds.centerY()); return true;
            }
            return false;
        });
        DeviceUi.await("The actual Comni select choice did not apply", 5000,
            () -> "comni".equals(ui.evaluate("document.querySelector('[data-droy-voice-provider]').value")));
    }

    private void replaceInput(String selector, String text) throws Exception {
        ui.showKeyboard(selector);
        long now = SystemClock.uptimeMillis();
        instrumentation.sendKeySync(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON));
        instrumentation.sendKeySync(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL); instrumentation.sendStringSync(text);
        DeviceUi.await("The endpoint input did not receive keyboard text", 5000,
            () -> text.equals(ui.evaluate("document.querySelector(" + JSONObject.quote(selector) + ").value")));
    }

    private JSONObject settingsDiagnostic() throws Exception {
        JSONObject diagnostic = (JSONObject) ui.evaluate("(()=>{const s=window.DroyVoice?.getSnapshot()||{},c=s.config?.realtime||{},"
            + "button=document.querySelector('[data-droy-voice-save]'),r=button?.getBoundingClientRect(),"
            + "hit=r?document.elementFromPoint(r.left+r.width/2,r.top+r.height/2):null;"
            + "const url=value=>{if(!value)return '';try{const u=new URL(value);return u.origin+u.pathname;}catch{return '<invalid URL>'}};"
            + "let error=String(s.error||'');for(const secret of [c.key,document.querySelector('[data-droy-voice-key]')?.value])"
            + "if(secret)error=error.split(secret).join('<redacted>');"
            + "error=error.replace(/(key|token|authorization)(\\s*[:=]\\s*)[^\\s,;]+/ig,'$1$2<redacted>');"
            + "return {provider:c.provider,url:url(c.url),draftProvider:document.querySelector('[data-droy-voice-provider]')?.value,"
            + "draftUrl:url(document.querySelector('[data-droy-voice-url]')?.value),state:s.status,error,busy:s.busy||null,"
            + "viewport:{width:innerWidth,height:innerHeight,visualTop:visualViewport?.offsetTop,visualHeight:visualViewport?.height},"
            + "ime:{bottom:getComputedStyle(document.documentElement).getPropertyValue('--droy-ime-bottom').trim(),"
            + "visible:document.documentElement.getAttribute('data-droy-ime-visible')},"
            + "activeElement:{tag:document.activeElement?.tagName,ariaLabel:document.activeElement?.getAttribute('aria-label')},"
            + "touchEvents:window.__voiceSaveTouchEvents||[],"
            + "save:button?{disabled:button.disabled,bounds:{left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height},"
            + "elementFromPoint:hit?{tag:hit.tagName,role:hit.getAttribute('role'),insideSave:button===hit||button.contains(hit)}:null}:null}})()");
        android.webkit.WebView view = ui.web();
        int[] geometry = new int[4];
        instrumentation.runOnMainSync(() -> {
            int[] location = new int[2]; view.getLocationOnScreen(location);
            geometry[0] = location[0]; geometry[1] = location[1]; geometry[2] = view.getWidth(); geometry[3] = view.getHeight();
        });
        diagnostic.put("nativeWebView", new JSONObject().put("screenX", geometry[0]).put("screenY", geometry[1])
            .put("width", geometry[2]).put("height", geometry[3]));
        diagnostic.put("nativeTouchEvents", new JSONArray(nativeSaveTouchEvents.toString()));
        JSONObject navigation = ui.nativeNavigationState();
        diagnostic.put("nativeIme", new JSONObject().put("liveIme", navigation.getBoolean("liveIme"))
            .put("imeVisible", navigation.getBoolean("imeVisible")));
        if (saveTapBounds != null) diagnostic.put("injectedTap", new JSONObject().put("left", saveTapBounds.left)
            .put("top", saveTapBounds.top).put("right", saveTapBounds.right).put("bottom", saveTapBounds.bottom)
            .put("centerX", saveTapBounds.centerX()).put("centerY", saveTapBounds.centerY()));
        return diagnostic;
    }

    private void reportSettings(String stage) throws Exception {
        JSONObject diagnostic = settingsDiagnostic().put("stage", stage);
        Bundle status = new Bundle(); status.putString("stream", "\nDROY_VOICE_REALTIME_SETTINGS " + diagnostic + "\n");
        instrumentation.sendStatus(2, status);
    }

    private void capture(String stage) throws Exception {
        ui.awaitPaint();
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
        try {
            File directory = new File(context.getExternalFilesDir(null), "voice-realtime-evidence");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            String label = InstrumentationRegistry.getArguments().getString("voiceEvidenceLabel", "realtime").replaceAll("[^A-Za-z0-9_-]", "_");
            File output = File.createTempFile(label + "-" + stage + "-", ".png", directory);
            try (FileOutputStream stream = new FileOutputStream(output)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
            Bundle status = new Bundle(); status.putString("stream", "\nDROY_VOICE_REALTIME_EVIDENCE " + output.getAbsolutePath() + "\n");
            instrumentation.sendStatus(2, status);
        } finally { image.recycle(); }
    }

    private void report(JSONObject state, String stage) throws Exception {
        JSONObject evidence = new JSONObject().put("stage", stage).put("provider", state.optString("provider"))
            .put("state", state.optString("status")).put("sentAudioFrames", state.optLong("sentAudioFrames"))
            .put("sentAudioBytes", state.optLong("sentAudioBytes")).put("sentVideoFrames", state.optLong("sentVideoFrames"))
            .put("nativeState", nativeState().optString("state")).put("syntheticMedia", true);
        if (state.has("remoteStopAcknowledged")) evidence.put("remoteStopAcknowledged", state.get("remoteStopAcknowledged"));
        Bundle result = new Bundle(); result.putString("stream", "\nDROY_VOICE_REALTIME " + evidence + "\n");
        instrumentation.sendStatus(2, result);
    }

    private void assertNoDshInference() throws Exception {
        try (java.util.stream.Stream<Path> paths = Files.walk(home.toPath())) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (!Files.isRegularFile(path) || !path.toString().endsWith(".jsonl")) continue;
                for (String line : Files.readAllLines(path)) if (!line.isBlank()) assertNoDshEvent(new JSONObject(line));
            }
        }
    }

    private static void assertNoDshEvent(Object value) throws Exception {
        if (value instanceof JSONObject object) {
            String type = object.optString("type");
            assertFalse("Synthetic Comni transport test submitted a DSH task", type.startsWith("assistant/") || type.startsWith("tool/") || type.equals("turn/start"));
            for (java.util.Iterator<String> keys = object.keys(); keys.hasNext();) assertNoDshEvent(object.get(keys.next()));
        } else if (value instanceof org.json.JSONArray array) {
            for (int i = 0; i < array.length(); i++) assertNoDshEvent(array.get(i));
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
                java.util.Set<String> strings = new java.util.HashSet<>(); for (Object item : set) strings.add((String) item); edit.putStringSet(key, strings);
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
