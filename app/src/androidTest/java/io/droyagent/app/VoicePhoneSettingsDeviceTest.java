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
import android.webkit.WebView;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Read-only inspection of the installed phone's Voice settings and original data. */
@RunWith(AndroidJUnit4.class)
public final class VoicePhoneSettingsDeviceTest {
    private static final String SETTINGS = "[data-droy-voice-settings]";
    private static final String LAUNCHER = "[data-droy-sidebar] [data-droy-settings-launcher]";
    private Instrumentation instrumentation;
    private Context context;
    private DeviceUi ui;
    private Map<String, String> homeBefore, preferencesBefore;
    private Boolean wakeSuspendedBefore;

    @Test public void installedPhoneVoiceSettingsAreReadableAndKeepUserData() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        assertFalse("Finish the current task before phone settings inspection", ToolProgress.current(context).getBoolean("active"));
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
        ui.hideKeyboard();
        DeviceUi.await("Voice client did not become ready", 10000,
            () -> ui.truth("!!window.DroyVoice?.getSnapshot().config"));
        assertTrue("Finish the current voice activity before this read-only check", ui.truth("!window.DroyVoice.getSnapshot().mode"));
        JSONObject activeCapture = AudioCapture.get(context).uiStatus().optJSONObject("active");
        if (activeCapture != null) {
            assertEquals("Finish the user's recording or conversation before phone settings inspection",
                "droy-voice-wake", activeCapture.optString("owner"));
            VoiceWake wake = VoiceWake.get(context);
            wakeSuspendedBefore = wake.request(new JSONObject().put("operation", "wake.status")).getBoolean("suspended");
            wake.request(new JSONObject().put("operation", "wake.suspend"));
            DeviceUi.await("Temporary wake suspension did not release microphone capture", 10000,
                () -> AudioCapture.get(context).uiStatus().isNull("active"));
            Bundle pause = new Bundle();
            pause.putString("stream", "\nDROY_VOICE_PHONE_WAKE temporarily suspended existing keyword listener; user conversation and preferences unchanged\n");
            instrumentation.sendStatus(2, pause);
        }
        assertTrue("This read-only check must begin without microphone capture", AudioCapture.get(context).uiStatus().isNull("active"));
        WebView original = ui.web();
        // Normal app initialization is complete; the inspection below must not
        // modify any original home files or preference values.
        homeBefore = snapshotHome();
        preferencesBefore = snapshotPreferences();

        JSONObject button = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector('.droy-voice-hold-face'),s=getComputedStyle(e),r=e.getBoundingClientRect();"
            + "return {width:r.width,height:r.height,radius:s.borderRadius,cornerShape:s.getPropertyValue('corner-shape'),supportsCornerShape:CSS.supports('corner-shape','round'),background:s.backgroundColor}})()");
        assertEquals(34, button.getDouble("width"), .1);
        assertEquals(34, button.getDouble("height"), .1);
        assertEquals("50%", button.getString("radius"));
        if (button.getBoolean("supportsCornerShape")) assertEquals("round", button.getString("cornerShape"));
        assertTrue("Both adjacent voice entries must be available", ui.truth("document.querySelectorAll('[data-droy-voice-activity] button').length===2"));
        capture("toolbar", "[data-droy-voice-activity]");
        Bundle geometry = new Bundle(); geometry.putString("stream", "\nDROY_VOICE_PHONE_BUTTON " + button + "\n");
        instrumentation.sendStatus(2, geometry);

        openGeneralByTouch();
        tap("[data-droy-settings-section=droy-voice]");
        DeviceUi.await("Voice settings tab did not open", 5000,
            () -> ui.truth("!!document.querySelector('" + SETTINGS + "')"));
        DeviceUi.await("Bundled recognition did not become ready automatically", 30000,
            () -> ui.truth("!!document.querySelector('[data-droy-voice-settings] [data-droy-voice-recognizer-state=ready]')"));
        assertTrue("Manual recognition configuration should be absent", ui.truth("!document.querySelector('select[aria-label=识别服务],select[aria-label=识别语言]')"));
        assertTrue(ui.truth("document.querySelector('[data-droy-settings-section=droy-voice]').getAttribute('aria-current')==='true'"));
        assertTrue(ui.truth("!document.querySelector('[data-droy-voice-enroll],[data-droy-voice-provider],[data-droy-voice-start=realtime],[data-droy-voice-start=video]')"));
        assertTrue("Speech engine and voice controls are missing", ui.truth("!!document.querySelector('summary[aria-label=发声方式]')&&!!document.querySelector('summary[aria-label=朗读音色],input[aria-label=朗读音色]')"));
        assertTrue("Hidden voice management leaked into settings", ui.truth("!document.querySelector('[data-droy-voice-settings]').textContent.includes('我的音色')"));
        assertLayout(); assertSafeHeaderAndFooter(); capture("input-and-voice-tab");
        reveal("summary[aria-label='发声方式']");
        assertTrue("Engine/voice explanation is missing", ui.truth("document.querySelector('[data-droy-voice-settings]').textContent.includes('不是语言设置')"));
        assertTrue("System and downloadable speech providers are missing", ui.truth("(()=>{const values=[...document.querySelector('summary[aria-label=发声方式]').closest('details').querySelectorAll('[role=option]')].map(o=>o.dataset.value);return ['system','melo','qwen3'].every(v=>values.includes(v))&&!values.includes('kokoro')})()"));
        DeviceUi.await("The Melo Chinese voice catalog did not load", 10000, () -> ui.truth(
            "window.DroyVoice.getSnapshot().voices.some(v=>v.provider==='melo'&&v.voiceId==='zh')"));
        String originalProvider = String.valueOf(ui.evaluate("document.querySelector('summary[aria-label=发声方式]').dataset.value"));
        String savedTts = String.valueOf(ui.evaluate("JSON.stringify(window.DroyVoice.getSnapshot().config.tts)"));
        assertLayout(); capture("speech-engine-and-voice");
        for (String provider : new String[]{"melo", "qwen3"}) {
            chooseOption("summary[aria-label=发声方式]", provider.equals("melo") ? "MeloTTS 中文" : "Qwen3-TTS", provider);
            String card = "[data-droy-voice-model=" + provider + "]";
            DeviceUi.await("Downloadable voice resource status did not load", 10000,
                () -> ui.truth("!!document.querySelector('" + card + ":not([data-droy-voice-model-state=unavailable])')"));
            boolean ready = ui.truth("document.querySelector('" + card + "').getAttribute('data-droy-voice-model-state')==='ready'");
            assertEquals("Preview availability must follow downloaded resources", !ready,
                ui.truth("document.querySelector('[data-droy-voice-preview]').disabled"));
            assertEquals("Saving must not enable an unavailable local engine", !ready,
                ui.truth("document.querySelector('[data-droy-voice-save]').disabled"));
            if (!ready) assertTrue("Missing resources need a visible download action", ui.truth("!!document.querySelector('[data-droy-voice-download]')"));
            reveal(card); assertLayout(); capture(provider + "-download-status");
            assertEquals("Selecting a draft engine saved the user's configuration", savedTts,
                ui.evaluate("JSON.stringify(window.DroyVoice.getSnapshot().config.tts)"));
        }
        // Closing discards the selected draft; this phone check never starts a
        // download, plays audio, or changes the user's saved provider.
        closeSettings(); openGeneralByTouch(); tap("[data-droy-settings-section=droy-voice]");
        DeviceUi.await("Voice settings did not reopen", 5000, () -> ui.truth("!!document.querySelector('" + SETTINGS + "')"));
        assertEquals("Unsaved engine selection changed the phone setting", originalProvider,
            ui.evaluate("document.querySelector('summary[aria-label=发声方式]').dataset.value"));
        reveal("[data-droy-voice-wake-timing]");
        assertTrue("Wake timing choices are missing", ui.truth("(()=>{const values=[...document.querySelector('[data-droy-voice-wake-timing]').closest('details').querySelectorAll('[role=option]')].map(o=>o.dataset.value);return values.includes('screen_on')&&values.includes('foreground')})()"));
        assertLayout(); capture("wake-timing");
        reveal("input[aria-label='英文唤醒词']");
        assertTrue(ui.truth("(()=>{const e=document.querySelector('" + SETTINGS + "');return ['关键词唤醒','唤醒时机','唤醒关键词','中文唤醒词','英文唤醒词','使用 Droy 伙伴名称唤醒','你好 + 名称','Hi + 名称'].every(text=>e.textContent.includes(text))})()"));
        assertTrue("Saved companion binding differs from manual field availability", ui.truth("(()=>{const s=document.querySelector('[data-droy-voice-entry-toggle=useCompanionName] [role=switch]'),bound=s.getAttribute('aria-checked')==='true';return document.querySelector('input[aria-label=中文唤醒词]').disabled===bound&&document.querySelector('input[aria-label=英文唤醒词]').disabled===bound})()"));
        assertLayout(); capture("wake");
        reveal("[data-droy-voice-save]");
        assertLayout(); capture("save-and-recording-tools");
        assertTrue("Reading settings opened a microphone", AudioCapture.get(context).uiStatus().isNull("active"));
        assertFalse("Reading settings started a call", ui.truth("!!window.DroyVoice.getSnapshot().mode"));

        closeSettings();
        assertSame("Read-only Voice inspection replaced the chat WebView", original, ui.web());
        assertTrue("Returning from settings left the normal page unavailable", ui.nativeUiReady());
    }

    @After public void leaveNormalPageAndCheckOriginalData() throws Exception {
        if (ui == null) return;
        try { closeSettings(); }
        finally {
            try {
                if (homeBefore != null) assertEquals("Voice settings changed original DSH files", homeBefore, snapshotHome());
                if (preferencesBefore != null) assertEquals("Voice settings changed saved preferences", preferencesBefore, snapshotPreferences());
            } finally {
                if (wakeSuspendedBefore != null) {
                    JSONObject restored = VoiceWake.get(context).request(new JSONObject().put("operation",
                        wakeSuspendedBefore ? "wake.suspend" : "wake.resume"));
                    assertEquals("Phone inspection did not restore the original wake suspension state",
                        wakeSuspendedBefore.booleanValue(), restored.getBoolean("suspended"));
                    wakeSuspendedBefore = null;
                }
            }
        }
    }

    private void chooseOption(String selector, String label, String value) throws Exception {
        reveal(selector); tap(selector);
        String option = "details.droy-voice-choice[open] [role=option][data-value=" + value + "]";
        DeviceUi.await("Droy dropdown did not open: " + label, 5000,
            () -> ui.truth("!!document.querySelector(" + JSONObject.quote(option) + ")"));
        assertTrue("Speech controls must use Droy dropdowns; only the separate audio capture source may use a native picker", ui.truth("(()=>{const root=document.querySelector('[data-droy-voice-settings]');return [...root.querySelectorAll('select')].every(e=>!!e.closest('[data-droy-general=audio]')&&['声音来源','Audio source'].includes(e.getAttribute('aria-label')))&&['发声方式','语速'].every(label=>!!root.querySelector('.droy-voice-choice>summary[aria-label='+label+']'))&&!!root.querySelector('.droy-voice-choice>summary[aria-label=朗读音色],input[aria-label=朗读音色]')})()"));
        capture("dropdown-" + value);
        tap(option);
        DeviceUi.await("Droy dropdown did not choose " + value, 5000,
            () -> value.equals(ui.evaluate("document.querySelector(" + JSONObject.quote(selector) + ").dataset.value")));
        assertTrue("Selected dropdown should close", ui.truth("!document.querySelector('details.droy-voice-choice[open]')"));
    }

    private void tap(String selector) throws Exception {
        ui.visibleWebBounds(selector);
        Rect[] stable = {null};
        long[] unchangedSince = {0};
        DeviceUi.await("Phone touch target did not settle: " + selector, 5000, () -> {
            Rect current = ui.webBounds(selector);
            if (stable[0] == null || !near(stable[0], current)) {
                stable[0] = current;
                unchangedSince[0] = SystemClock.uptimeMillis();
                return false;
            }
            return SystemClock.uptimeMillis() - unchangedSince[0] >= 150;
        });
        ui.awaitPaint();
        Rect bounds = ui.webBounds(selector);
        assertTrue("Phone touch target moved before the committed frame", near(stable[0], bounds));
        assertTrue("Phone touch target is covered", ui.truth("(()=>{const e=document.querySelector("
            + JSONObject.quote(selector) + "),r=e.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return e===hit||e.contains(hit)})()"));
        shellTouch("tap " + bounds.centerX() + " " + bounds.centerY());
    }

    private static boolean near(Rect first, Rect second) {
        return Math.abs(first.left - second.left) <= 1 && Math.abs(first.top - second.top) <= 1
            && Math.abs(first.right - second.right) <= 1 && Math.abs(first.bottom - second.bottom) <= 1;
    }

    private void swipe(int fromX, int fromY, int toX, int toY, int durationMs) throws Exception {
        shellTouch("swipe " + fromX + " " + fromY + " " + toX + " " + toY + " " + durationMs);
    }

    private void shellTouch(String arguments) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .executeShellCommand("input touchscreen " + arguments))) {
            byte[] buffer = new byte[1024];
            for (int count; (count = input.read(buffer)) != -1;) {
                output.write(buffer, 0, count);
                assertTrue("The shell touch command returned excessive output", output.size() <= 4096);
            }
        }
        String error = output.toString("UTF-8").trim();
        assertTrue("The shell touch command failed: " + error, error.isEmpty());
    }

    private void openGeneralByTouch() throws Exception {
        ui.evaluate("window.__voicePhoneMenuEvents=[];window.__voicePhoneMenuListener=e=>{window.__voicePhoneMenuEvents.push({"
            + "type:e.type,trusted:e.isTrusted,pointerType:e.pointerType||null,x:e.clientX,y:e.clientY,"
            + "menu:!!e.target?.closest?.('[data-droy-menu]'),sidebar:!!e.target?.closest?.('[data-droy-sidebar]'),"
            + "backdrop:!!e.target?.closest?.('[data-droy-backdrop]'),defaultPrevented:e.defaultPrevented});"
            + "if(window.__voicePhoneMenuEvents.length>20)window.__voicePhoneMenuEvents.shift()};"
            + "for(const type of ['pointerdown','pointerup','pointercancel','click'])document.addEventListener(type,window.__voicePhoneMenuListener,{capture:true,passive:true})");
        try {
            recordDrawerDiagnostic("before-touch");
            if (!ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')")) tap("[data-droy-menu]");
            DeviceUi.await("Phone drawer did not open", 5000,
                () -> ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')"));
            DeviceUi.await("The open phone drawer did not finish becoming visible and interactive", 5000, () -> ui.truth(
                "(()=>{const e=document.querySelector('[data-droy-sidebar]');if(!e)return false;const r=e.getBoundingClientRect(),s=getComputedStyle(e);"
                + "return !e.inert&&e.getAttribute('aria-hidden')!=='true'&&r.width>0&&r.height>0&&r.left>=-1&&r.right<=innerWidth+1&&s.visibility!=='hidden'&&s.display!=='none'})()"));
            ui.awaitPaint();
            recordDrawerDiagnostic("opened");
        } catch (AssertionError failure) {
            try { recordDrawerDiagnostic("failed-touch"); captureAppFailure("drawer-failure"); }
            catch (Exception | AssertionError diagnosticFailure) { failure.addSuppressed(diagnosticFailure); }
            throw failure;
        } finally {
            ui.evaluate("for(const type of ['pointerdown','pointerup','pointercancel','click'])document.removeEventListener(type,window.__voicePhoneMenuListener,true);delete window.__voicePhoneMenuListener;delete window.__voicePhoneMenuEvents");
        }
        tap(LAUNCHER);
        DeviceUi.await("Phone Settings did not open", 5000,
            () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        tap("[data-droy-settings-section=general]");
        DeviceUi.await("Droy Voice entry is unavailable", 5000,
            () -> ui.truth("!!document.querySelector('[data-droy-settings-section=droy-voice]')"));
    }

    private void recordDrawerDiagnostic(String stage) throws Exception {
        JSONObject geometry = (JSONObject) ui.evaluate("(()=>{const bounds=e=>{if(!e)return null;const r=e.getBoundingClientRect(),s=getComputedStyle(e);return {x:r.x,y:r.y,width:r.width,height:r.height,visibility:s.visibility,display:s.display,inert:!!e.inert,ariaHidden:e.getAttribute('aria-hidden')}};"
            + "const v=visualViewport;return {stage:" + JSONObject.quote(stage)
            + ",drawerOpen:!!document.querySelector('[data-droy-drawer-open=true]'),menu:bounds(document.querySelector('[data-droy-menu]')),sidebar:bounds(document.querySelector('[data-droy-sidebar]')),"
            + "viewport:{width:innerWidth,height:innerHeight,pixelRatio:devicePixelRatio,visualWidth:v?.width,visualHeight:v?.height,offsetLeft:v?.offsetLeft,offsetTop:v?.offsetTop,scale:v?.scale},"
            + "documentVisible:document.visibilityState,events:window.__voicePhoneMenuEvents||[]}})()");
        JSONObject nativeGeometry = new JSONObject();
        WebView web = ui.web();
        instrumentation.runOnMainSync(() -> {
            int[] location = new int[2]; web.getLocationOnScreen(location);
            try {
                nativeGeometry.put("x", location[0]).put("y", location[1]).put("width", web.getWidth()).put("height", web.getHeight())
                    .put("shown", web.isShown()).put("windowFocused", web.hasWindowFocus()).put("viewFocused", web.hasFocus())
                    .put("displayId", web.getDisplay() == null ? -1 : web.getDisplay().getDisplayId());
            } catch (org.json.JSONException invalid) { throw new AssertionError(invalid); }
        });
        geometry.put("nativeWeb", nativeGeometry);
        Bundle status = new Bundle(); status.putString("stream", "\nDROY_VOICE_PHONE_DRAWER " + geometry + "\n");
        instrumentation.sendStatus(2, status);
    }

    private void captureAppFailure(String stage) throws Exception {
        // Keep full failure pixels locally for diagnosis, without copying chat
        // text or accessibility labels into instrumentation output.
        Rect app = new Rect();
        WebView web = ui.web();
        instrumentation.runOnMainSync(() -> {
            android.view.View root = web.getRootView();
            int[] location = new int[2]; root.getLocationOnScreen(location);
            app.set(location[0], location[1], location[0] + root.getWidth(), location[1] + root.getHeight());
        });
        Bitmap screen = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
        assertNotNull("Failed to capture the phone's failure state", screen);
        try {
            assertTrue("The app has no visible screenshot bounds", app.intersect(0, 0, screen.getWidth(), screen.getHeight()) && !app.isEmpty());
            Bitmap image = Bitmap.createBitmap(screen, app.left, app.top, app.width(), app.height());
            try {
                File directory = new File(context.getExternalFilesDir(null), "voice-phone-settings-evidence");
                assertTrue(directory.isDirectory() || directory.mkdirs());
                File output = File.createTempFile("phone-" + stage + "-", ".png", directory);
                try (FileOutputStream file = new FileOutputStream(output)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, file)); }
                Bundle status = new Bundle(); status.putString("stream", "\nDROY_VOICE_PHONE_FAILURE_EVIDENCE " + output.getAbsolutePath() + "\n");
                instrumentation.sendStatus(2, status);
            } finally { if (image != screen) image.recycle(); }
        } finally { screen.recycle(); }
    }

    private void openDetail(String title, String marker) throws Exception {
        String selector = "[data-test-voice-phone-detail=" + marker + "]";
        assertTrue("Voice service section is missing", ui.truth("(()=>{const e=[...document.querySelectorAll('" + SETTINGS
            + " details>summary')].find(e=>e.textContent.trim()===" + JSONObject.quote(title)
            + ");if(!e)return false;e.setAttribute('data-test-voice-phone-detail'," + JSONObject.quote(marker) + ");return true})()"));
        reveal(selector);
        if (!ui.truth("document.querySelector(" + JSONObject.quote(selector) + ").parentElement.open")) {
            ui.evaluate("window.__voicePhoneSummaryEvents=[];window.__voicePhoneSummaryListener=e=>{"
                + "window.__voicePhoneSummaryEvents.push({type:e.type,tag:e.target?.tagName,target:!!e.target?.closest?.("
                + JSONObject.quote(selector) + "),isTrusted:e.isTrusted});if(window.__voicePhoneSummaryEvents.length>16)window.__voicePhoneSummaryEvents.shift()};"
                + "for(const type of ['pointerdown','pointerup','click'])document.addEventListener(type,window.__voicePhoneSummaryListener,true)");
            try {
                tap(selector);
                DeviceUi.await("Service details did not expand after touch", 5000,
                    () -> ui.truth("document.querySelector(" + JSONObject.quote(selector) + ").parentElement.open"));
            } finally {
                Object events = ui.evaluate("window.__voicePhoneSummaryEvents||[]");
                ui.evaluate("for(const type of ['pointerdown','pointerup','click'])document.removeEventListener(type,window.__voicePhoneSummaryListener,true);delete window.__voicePhoneSummaryListener;delete window.__voicePhoneSummaryEvents");
                Bundle status = new Bundle(); status.putString("stream", "\nDROY_VOICE_PHONE_SUMMARY_TOUCH " + events + "\n");
                instrumentation.sendStatus(2, status);
            }
        }
    }

    private void reveal(String selector) throws Exception {
        for (int attempt = 0; attempt < 16; attempt++) {
            JSONObject target = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
                + "),r=e.getBoundingClientRect(),p=document.querySelector('[data-droy-settings-options]').getBoundingClientRect();"
                + "return {visible:r.top>=p.top+4&&r.bottom<=p.bottom-4,before:r.top<p.top+4}})()");
            if (target.getBoolean("visible")) { ui.visibleWebBounds(selector); return; }
            Rect body = ui.webBounds("[data-droy-settings-options]");
            JSONObject clip = (JSONObject) ui.evaluate("(()=>{const r=document.querySelector('[data-droy-settings-options]').getBoundingClientRect();return {top:r.top,bottom:r.bottom}})()");
            Rect visible = body;
            int top = visible.top + visible.height() / 3, bottom = visible.bottom - visible.height() / 5;
            assertTrue("Voice settings has no scrollable content viewport", clip.getDouble("bottom") > clip.getDouble("top"));
            swipe(body.centerX(), target.getBoolean("before") ? top : bottom,
                body.centerX(), target.getBoolean("before") ? bottom : top, 280);
            ui.awaitPaint();
        }
        fail("Touch scrolling did not reveal Voice settings control");
    }

    private void assertLayout() throws Exception {
        JSONObject result = (JSONObject) ui.evaluate("(()=>{const root=document.querySelector('" + SETTINGS
            + "'),r=root.getBoundingClientRect();let fits=true,touches=true;const measured=new Set(),smallTargets=[];"
            + "for(const e of root.querySelectorAll('p,h3,h4,label,summary,button')){if(!e.getClientRects().length)continue;"
            + "const walker=document.createTreeWalker(e,NodeFilter.SHOW_TEXT);while(walker.nextNode()){if(!walker.currentNode.textContent.trim())continue;const range=document.createRange();range.selectNodeContents(walker.currentNode);for(const b of range.getClientRects())if(b.width>.1&&(b.left<r.left-1||b.right>r.right+1))fits=false;}}"
            + "for(const e of root.querySelectorAll('button,input,select,summary,.droy-voice-toggle')){if(!e.getClientRects().length)continue;const target=e.getAttribute('role')==='switch'?e.closest('label.droy-voice-toggle')||e:e.matches('input[type=checkbox]')?e.closest('label')||e:e;if(measured.has(target))continue;measured.add(target);const b=target.getBoundingClientRect();if(b.width<47||b.height<47){touches=false;smallTargets.push({tag:target.tagName,role:target.getAttribute('role'),label:target.getAttribute('aria-label'),width:b.width,height:b.height,minHeight:getComputedStyle(target).minHeight});}}"
            + "return {fits,touches,smallTargets,overflow:root.scrollWidth>root.clientWidth+2||document.documentElement.scrollWidth>innerWidth+1}})()");
        assertTrue("Voice settings copy extends outside its content", result.getBoolean("fits"));
        assertTrue("Voice settings controls lack 48px effective touch regions: " + result, result.getBoolean("touches"));
        assertFalse("Voice settings has horizontal overflow", result.getBoolean("overflow"));
    }

    private void assertSafeHeaderAndFooter() throws Exception {
        assertTrue("Settings header overlaps system bars", ui.truth("(()=>{const root=document.querySelector('[data-droy-settings-header]'),r=root.getBoundingClientRect(),s=getComputedStyle(document.documentElement),top=parseFloat(s.getPropertyValue('--droy-safe-top'))||0;return r.top>=top-1})()"));
    }

    private void closeSettings() throws Exception {
        if (ui.truth("!!document.querySelector('[data-droy-settings-panel]')")) tap("[data-droy-settings-back]");
        DeviceUi.await("Phone settings did not return to the normal page", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        if (ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')")) ui.pressBack();
    }

    private void capture(String stage) throws Exception { capture(stage, "[data-droy-settings-panel]"); }

    private void capture(String stage, String selector) throws Exception {
        WebView web = ui.web();
        CountDownLatch committed = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> web.postVisualStateCallback(0, new WebView.VisualStateCallback() {
            @Override public void onComplete(long id) {
                web.postOnAnimation(() -> {
                    web.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
                    web.invalidate();
                });
            }
        }));
        assertTrue("Voice settings screenshot did not reach a committed frame", committed.await(5, TimeUnit.SECONDS));
        Rect crop = ui.webBounds(selector);
        Bitmap screen = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
        assertNotNull(screen);
        try {
            assertTrue(crop.intersect(0, 0, screen.getWidth(), screen.getHeight()) && !crop.isEmpty());
            Bitmap image = Bitmap.createBitmap(screen, crop.left, crop.top, crop.width(), crop.height());
            try {
                File directory = new File(context.getExternalFilesDir(null), "voice-phone-settings-evidence");
                assertTrue(directory.isDirectory() || directory.mkdirs());
                File output = File.createTempFile("phone-" + stage + "-", ".png", directory);
                try (FileOutputStream file = new FileOutputStream(output)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, file)); }
                Bundle status = new Bundle();
                status.putString("stream", "\nDROY_VOICE_PHONE_EVIDENCE " + output.getAbsolutePath() + "\n");
                instrumentation.sendStatus(2, status);
            } finally { if (image != screen) image.recycle(); }
        } finally { screen.recycle(); }
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
