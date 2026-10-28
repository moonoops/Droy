package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.HashMap;
import java.util.Map;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real settings touches; the recording fixture requires an emulator with host audio input disabled. */
@RunWith(AndroidJUnit4.class)
public final class AudioSettingsDeviceTest {
    private static final String[] KEYS = {"allowAudioMicrophone","allowAudioPlayback","allowAudioTranscription"};
    private final Map<String,Boolean> saved = new HashMap<>();
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences preferences;
    private MainActivity activity;
    private DeviceUi ui;
    private boolean ownsRuntime;
    private File isolatedHome, savedHome;
    private boolean hadHome;
    private boolean ownsHome;
    private Map<String,?> savedProductPreferences;
    private boolean rotated;
    private boolean completedTest, failureCaptured;
    private String lastTarget="[data-droy-menu]";
    private String manualCapture;
    private boolean revokeMicrophonePermission;

    @Before public void setUp() throws Exception {
        instrumentation=InstrumentationRegistry.getInstrumentation();
        UiAutomation automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context=instrumentation.getTargetContext();
        assertTrue("Stop existing tasks and engine before audio UI acceptance",RuntimeService.canManageHistory());
        assertTrue("Do not interrupt an existing recording",AudioCapture.get(context).uiStatus().isNull("active"));
        preferences=ProductPermissions.preferences(context);
        for(String key:KEYS) if(preferences.contains(key)) saved.put(key,preferences.getBoolean(key,true));
        ownsRuntime=true;
        assertTrue("UiAutomation did not establish portrait orientation",automation.setRotation(UiAutomation.ROTATION_FREEZE_0));
        rotated=true;
        Bundle args=InstrumentationRegistry.getArguments();
        if(args.containsKey("layoutMaxTextFont")||args.containsKey("layoutTheme")) {
            assertTrue("Theme/font variants only run on a dedicated emulator",isEmulator());
            savedProductPreferences=new HashMap<>(preferences.getAll());
            RuntimeInstaller.installCertificate(context); RuntimeInstaller.install(context);
            isolatedHome=new File(context.getFilesDir(),"dsh-home");
            savedHome=new File(context.getFilesDir(),"audio-ui-saved-home-"+UUID.randomUUID());
            hadHome=isolatedHome.exists();
            if(hadHome) assertTrue(isolatedHome.renameTo(savedHome));
            ownsHome=true;
            assertTrue(isolatedHome.mkdirs());
        }
        activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ui=new DeviceUi(instrumentation,activity); ui.awaitPage();
        ui.evaluate("(()=>{window.__droyAudioTouches=[];for(const kind of ['pointerdown','pointerup','click','toggle'])window.addEventListener(kind,e=>{"
            +"const entry={kind,x:e.clientX,y:e.clientY,tag:e.target.tagName,role:e.target.getAttribute('role'),preference:e.target.closest('[data-droy-preference]')?.getAttribute('data-droy-preference'),open:e.target.open};"
            +"window.__droyAudioTouches.push(entry);queueMicrotask(()=>entry.prevented=e.defaultPrevented);"
            +"if(window.__droyAudioTouches.length>24)window.__droyAudioTouches.shift()},true)})()");
        DeviceUi.await("The Activity never reached a portrait viewport",5000,()->ui.truth("innerHeight>innerWidth"));
        ui.evaluate("(()=>{for(const h of document.querySelectorAll('[role=dialog] h2')){if(!['开始使用','Get started'].includes(h.textContent.trim()))continue;"
            +"h.closest('[role=dialog]').querySelector('button[aria-label=关闭],button[aria-label=Close]')?.click()}})()");
    }

    @After public void tearDown() throws Exception {
        if(!ownsRuntime) return;
        try {
            if(!completedTest&&!failureCaptured&&ui!=null&&activity!=null&&!activity.isDestroyed()) captureFailure(lastTarget,"test_failed");
            preserveEvidence();
            if(manualCapture!=null) {
                AudioCapture.get(context).stopFromUi(manualCapture);
                DeviceUi.await("Fixture recording did not finish during cleanup",10000,()->AudioCapture.get(context).uiStatus().isNull("active"));
                AudioCapture.get(context).deleteFromUi(manualCapture); manualCapture=null;
            }
            if(activity!=null&&!activity.isDestroyed()) instrumentation.runOnMainSync(activity::finish);
            instrumentation.runOnMainSync(()->RuntimeService.requestStop(context));
            DeviceUi.await("Audio settings left the engine running",15000,RuntimeService::canManageHistory);
        } finally {
            if(rotated) instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).setRotation(UiAutomation.ROTATION_UNFREEZE);
            if(ownsHome) {
                assertTrue("Keep original home until the engine stops",RuntimeService.canManageHistory());
                HistoryData.deleteTree(isolatedHome.toPath());
                if(hadHome) assertTrue(savedHome.renameTo(isolatedHome));
            }
            SharedPreferences.Editor restore=preferences.edit();
            if(savedProductPreferences!=null) {
                restore.clear();
                for(Map.Entry<String,?> entry:savedProductPreferences.entrySet()) {
                    String key=entry.getKey();Object value=entry.getValue();
                    if(value instanceof Boolean b)restore.putBoolean(key,b);
                    else if(value instanceof String s)restore.putString(key,s);
                    else if(value instanceof Integer n)restore.putInt(key,n);
                    else if(value instanceof Long n)restore.putLong(key,n);
                    else if(value instanceof Float n)restore.putFloat(key,n);
                    else if(value instanceof java.util.Set<?> values) {
                        java.util.Set<String> strings=new java.util.HashSet<>();for(Object v:values)strings.add((String)v);restore.putStringSet(key,strings);
                    } else throw new AssertionError("Unsupported preference type: "+key);
                }
            }
            for(String key:KEYS) { if(saved.containsKey(key)) restore.putBoolean(key,saved.get(key)); else restore.remove(key); }
            assertTrue(restore.commit());
            if(revokeMicrophonePermission) instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .revokeRuntimePermission(context.getPackageName(),android.Manifest.permission.RECORD_AUDIO);
        }
    }

    @Test public void audioPanelAndPermissionRowsSupportRealTouchesAndReadableCopy() throws Exception {
        ui.openPermissions();
        Bundle arguments=InstrumentationRegistry.getArguments();
        if(arguments.containsKey("layoutMaxTextFont")||arguments.containsKey("layoutTheme")) {
            ui.tap("[data-droy-settings-section=general]"); configureLayout(); reportViewport();
            ui.tap("[data-droy-settings-section=droy-permissions]");
        }
        for(String key:KEYS) {
            String row="[data-droy-preference="+key+"]";
            boolean original=ui.truth("document.querySelector("+JSONObject.quote(row+" [role=switch]")+").getAttribute('aria-checked')==='true'");
            tapControl(row);
            awaitPreference(key,original,false,row);
            tapControl(row);
            awaitPreference(key,original,true,row);
            ui.evaluate("document.querySelector("+JSONObject.quote(row)+").closest('.droy-row').setAttribute('data-test-audio-permission',"+JSONObject.quote(key)+")");
            capture("[data-test-audio-permission="+key+"]","permission-"+key);
        }
        capture("[data-droy-permission=microphone]","microphone-permission");
        ui.tap("[data-droy-settings-section=droy-voice]");
        reportViewport();
        tapControl("[data-droy-audio-details]>summary");
        DeviceUi.await("Audio section did not expand",5000,()->ui.truth("document.querySelector('[data-droy-audio-details]').open"));
        assertLayout(); capture("[data-droy-general=audio]>.droy-row-copy","audio-header");
        capture("[aria-label='声音来源']","microphone-source");
        selectSource("指定应用");
        DeviceUi.await("App source did not show package input",5000,()->ui.truth("!!document.querySelector('[aria-label=目标应用包名]')"));
        assertLayout(); capture("[aria-label=目标应用包名]","app-source");
        boolean keyboard=!"false".equals(arguments.getString("audioKeyboard","true"));
        if(keyboard) exerciseInput("[aria-label=目标应用包名]","com.example.player","","package-keyboard");
        selectSource("系统可采集音频"); assertLayout();
        capture("[aria-label=采集时长]","duration");
        if(keyboard) exerciseInput("[aria-label=采集时长]","5","0","duration-keyboard");
        capture("[data-droy-action=audioCapture]","start-capture");
        capture("[data-droy-audio-model-state]","recognizer-state");
        capture("[data-droy-action=audioImport]","file-transcription");
        assertTrue("Manual audio import must remain available",ui.truth(
            "document.querySelector('[data-droy-action=audioImport]').textContent.trim()==='选择音频转文字'"));
        assertTrue("Manual capture must still offer saved audio",ui.truth(
            "document.querySelector('[data-droy-general=audio] .droy-audio-check input').checked"));
        tapControl("[data-droy-general=audio] .droy-audio-check");
        DeviceUi.await("Manual capture did not accept streaming without a saved file",5000,()->!ui.truth(
            "document.querySelector('[data-droy-general=audio] .droy-audio-check input').checked"));
        tapControl("[data-droy-general=audio] .droy-audio-check");
        DeviceUi.await("Manual saved recording choice did not restore",5000,()->ui.truth(
            "document.querySelector('[data-droy-general=audio] .droy-audio-check input').checked"));
        // This only verifies presentation. Actual transient-file deletion and
        // preservation of manual recordings are covered by storage device tests.
        assertTrue("Settings must not render recorded history cards",ui.truth(
            "document.querySelectorAll('[data-droy-general=audio] [data-droy-audio-capture]').length===0"));
        assertTrue("The former recording library must not remain in settings",ui.truth(
            "!document.querySelector('[data-droy-audio-library-notice]')"));
        assertEquals("语音对话和唤醒不保留录音；手动采集可选择保存录音文件。",ui.evaluate(
            "document.querySelector('[data-droy-audio-privacy-notice]').textContent.trim()"));
        capture("[data-droy-audio-privacy-notice]","recording-privacy-notice");
        tapControl("[data-droy-audio-details]>summary");
        DeviceUi.await("Audio section did not collapse",5000,()->!ui.truth("document.querySelector('[data-droy-audio-details]').open"));
        ui.pressBack();
        DeviceUi.await("Settings Back did not return to chat",5000,()->!ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        assertTrue(ui.nativeUiReady());
        completedTest=true;
    }

    @Test public void manualRecordingKeepsItsCompletionActionsAndDeletesItsOwnSavedFile() throws Exception {
        assertTrue("Saved recording fixture requires a dedicated emulator with host microphone input disabled",isEmulator());
        boolean hadStopped=preferences.contains(ProductPermissions.STOPPED);
        boolean stopped=preferences.getBoolean(ProductPermissions.STOPPED,false);
        revokeMicrophonePermission=context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            !=android.content.pm.PackageManager.PERMISSION_GRANTED;
        if(revokeMicrophonePermission) instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .grantRuntimePermission(context.getPackageName(),android.Manifest.permission.RECORD_AUDIO);
        try {
            assertTrue(preferences.edit().putBoolean(ProductPermissions.STOPPED,false).putBoolean("allowAudioMicrophone",true).commit());
            ui.openPermissions(); ui.tap("[data-droy-settings-section=droy-voice]");
            tapControl("[data-droy-audio-details]>summary");
            DeviceUi.await("Audio section did not expand",5000,()->ui.truth("document.querySelector('[data-droy-audio-details]').open"));
            assertEquals("microphone",ui.evaluate("document.querySelector('[aria-label=声音来源]').value"));
            assertTrue(ui.truth("document.querySelector('[data-droy-general=audio] .droy-audio-check input').checked"));
            tapControl("[data-droy-audio-start]");
            DeviceUi.await("Manual recording did not start",10000,()->{
                JSONObject active=AudioCapture.get(context).uiStatus().optJSONObject("active");
                if(active==null) return false;
                manualCapture=active.getString("captureId");
                return "recording".equals(active.optString("state"))&&active.optLong("bytes")>0;
            });
            String id=manualCapture;
            tapControl("[data-droy-audio-stop]");
            DeviceUi.await("Manual recording did not produce its saved WAV",10000,()->{
                JSONObject item=AudioCapture.get(context).status(id,"product-user-action");
                return item.optBoolean("fileReady")&&"completed".equals(item.optString("state"));
            });
            File wav=AudioCapture.get(context).fileForUi(id);
            assertTrue("A completed saved recording must contain PCM",wav.length()>44);
            DeviceUi.await("This recording's completion card disappeared",5000,()->ui.truth(
                "document.querySelector('[data-droy-audio-current-result]')?.getAttribute('data-droy-audio-capture')==="+JSONObject.quote(id)));
            assertTrue("Completion actions must remain usable",ui.truth(
                "(()=>{const card=document.querySelector('[data-droy-audio-current-result]');return ['audioPlay','audioExport','audioTranscription'].every(action=>!!card.querySelector('[data-droy-action='+action+']'))})()"));
            assertLayout(); capture("[data-droy-audio-current-result]","manual-recording-completed");
            tapControl("[data-droy-audio-delete]");
            DeviceUi.await("Delete confirmation was not shown",5000,()->{
                AccessibilityNodeInfo root=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow();
                if(root==null) return false;
                for(AccessibilityNodeInfo confirm:root.findAccessibilityNodeInfosByViewId("android:id/button1")) {
                    Rect bounds=new Rect(); confirm.getBoundsInScreen(bounds);
                    if(bounds.isEmpty()) continue;
                    ui.tapAt(bounds.centerX(),bounds.centerY()); return true;
                }
                return false;
            });
            DeviceUi.await("Deleting this recording did not remove its actual WAV",5000,()->!wav.exists());
            manualCapture=null;
            DeviceUi.await("Deleted recording card remained visible",5000,()->!ui.truth("!!document.querySelector('[data-droy-audio-current-result]')"));
            assertThrows(IllegalArgumentException.class,()->AudioCapture.get(context).fileForUi(id));
            completedTest=true;
        } finally {
            SharedPreferences.Editor restore=preferences.edit();
            if(hadStopped) restore.putBoolean(ProductPermissions.STOPPED,stopped); else restore.remove(ProductPermissions.STOPPED);
            assertTrue(restore.commit());
        }
    }

    private void configureLayout() throws Exception {
        Bundle args=InstrumentationRegistry.getArguments();
        boolean maxFont="true".equals(args.getString("layoutMaxTextFont"));
        String theme=args.getString("layoutTheme");
        if(!maxFont&&theme==null) return;
        assertTrue("Theme/font variants only run on a dedicated emulator",isEmulator());
        if(theme!=null) ui.tap("[data-droy-theme-choice="+theme+"]");
        if(maxFont) {
            tapControl("[data-droy-font-expander]");
            String step="[data-droy-font-row=size][data-droy-font-role=text] [data-droy-font-step=increase]";
            for(int n=0;n<60&&!ui.truth("document.querySelector("+JSONObject.quote(step)+").disabled");n++) tapControl(step);
            assertTrue(ui.truth("document.querySelector("+JSONObject.quote(step)+").disabled"));
        }
    }

    private void selectSource(String label) throws Exception {
        tapControl("[aria-label='声音来源']");
        DeviceUi.await("Source option not visible: "+label,5000,()->{
            AccessibilityNodeInfo root=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow();
            if(root==null) return false;
            for(AccessibilityNodeInfo option:root.findAccessibilityNodeInfosByText(label)) {
                if(!label.contentEquals(option.getText()==null?"":option.getText())) continue;
                Rect bounds=new Rect(); option.getBoundsInScreen(bounds);
                if(bounds.isEmpty()) continue;
                ui.tapAt(bounds.centerX(),bounds.centerY()); return true;
            }
            return false;
        });
    }

    private void assertLayout() throws Exception {
        JSONObject result=(JSONObject)ui.evaluate("""
            (()=>{const row=document.querySelector('[data-droy-general=audio]'),r=row.getBoundingClientRect();let fits=true,touches=true;
              for(const e of row.querySelectorAll('p,summary,label,strong,button')){if(!e.getClientRects().length)continue;
                const w=document.createTreeWalker(e,NodeFilter.SHOW_TEXT);while(w.nextNode()){if(!w.currentNode.textContent.trim())continue;
                  const x=document.createRange();x.selectNodeContents(w.currentNode);for(const b of x.getClientRects())if(b.width>.1&&(b.left<r.left-1||b.right>r.right+1))fits=false;}}
              for(const e of row.querySelectorAll('button,select,input:not([type=checkbox]),summary')){if(!e.getClientRects().length)continue;
                if(e.getBoundingClientRect().height<47)touches=false;}
              return {fits,touches,overflow:row.scrollWidth>row.clientWidth+1||document.documentElement.scrollWidth>innerWidth+1};})()
            """);
        assertTrue("Audio text is clipped: "+result,result.getBoolean("fits"));
        assertTrue("Audio controls are too small: "+result,result.getBoolean("touches"));
        assertFalse("Audio section has horizontal overflow: "+result,result.getBoolean("overflow"));
    }

    private void capture(String selector,String name) throws Exception {
        String label=InstrumentationRegistry.getArguments().getString("audioEvidenceLabel","audio").replaceAll("[^A-Za-z0-9_-]","_");
        DiagnosticsSettingsDeviceTest.captureSettingsRow(instrumentation,context,ui,selector,"audio-settings-evidence",label+"-"+name,true);
    }

    private void tapControl(String selector) throws Exception {
        lastTarget=selector;
        assertTrue("Portrait orientation was lost before touching "+selector,ui.truth("innerHeight>innerWidth"));
        // The shared helper may return a target inside the window but covered by
        // the sticky settings header. Reveal it inside the actual scroll viewport.
        for(int attempt=0;attempt<40;attempt++) {
            JSONObject state=(JSONObject)ui.evaluate("(()=>{const e=document.querySelector("+JSONObject.quote(selector)
                +"),r=e.getBoundingClientRect(),p=document.querySelector('[data-droy-settings-options]').getBoundingClientRect();"
                +"return {visible:r.top>=p.top+1&&r.bottom<=p.bottom-1,before:r.top<p.top+1}})()");
            if(state.getBoolean("visible")) {
                DeviceUi.await("Settings touch target remains disabled: "+selector,5000,()->ui.truth("(()=>{const e=document.querySelector("+JSONObject.quote(selector)
                    +"),control=e.querySelector('[role=switch]')||e;return !control.disabled})()"));
                settleTarget(selector);
                Rect hit=ui.visibleWebBounds(selector);
                if(!ui.truth("(()=>{const e=document.querySelector("+JSONObject.quote(selector)
                    +"),r=e.getBoundingClientRect(),h=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return h===e||e.contains(h)})()")) {
                    captureFailure(selector,"target-obscured"); fail("Settings touch center is obscured: "+selector);
                }
                ui.evaluate("window.__droyAudioExpected="+new JSONObject().put("selector",selector).put("nativeX",hit.centerX()).put("nativeY",hit.centerY()).toString());
                ui.tapAt(hit.centerX(),hit.centerY()); return;
            }
            Rect options=ui.webBounds("[data-droy-settings-options]");
            int top=options.top+options.height()/4,bottom=options.bottom-options.height()/4;
            boolean before=state.getBoolean("before");
            ui.swipe(options.centerX(),before?top:bottom,options.centerX(),before?bottom:top,280);
            instrumentation.waitForIdleSync();
            settleTarget(selector);
        }
        captureFailure(selector,"target-not-visible"); fail("Actual scrolling did not reveal "+selector);
    }

    private void awaitPreference(String key,boolean original,boolean restoring,String selector) throws Exception {
        try { DeviceUi.await("Audio capability touch was not "+(restoring?"restored: ":"saved: ")+key,5000,
            ()->preferences.getBoolean(key,restoring?!original:original)==(restoring?original:!original)); }
        catch(AssertionError failure) { captureFailure(selector,"preference-not-saved"); throw failure; }
    }

    private void captureFailure(String selector,String reason) throws Exception {
        String label=InstrumentationRegistry.getArguments().getString("audioEvidenceLabel","audio").replaceAll("[^A-Za-z0-9_-]","_");
        File folder=new File(context.getExternalFilesDir(null),"audio-settings-evidence"); folder.mkdirs();
        JSONObject geometry=(JSONObject)ui.evaluate("(()=>{const e=document.querySelector("+JSONObject.quote(selector)
            +"),r=e?.getBoundingClientRect(),p=document.querySelector('[data-droy-settings-options]')?.getBoundingClientRect(),h=r?document.elementFromPoint(r.left+r.width/2,r.top+r.height/2):null;"
            +"return {viewport:{width:innerWidth,height:innerHeight},rect:r?.toJSON(),clip:p?.toJSON(),hit:h?{tag:h.tagName,role:h.getAttribute('role'),ariaLabel:h.getAttribute('aria-label')}:null,"
            +"targetChecked:e?.querySelector('[role=switch]')?.getAttribute('aria-checked'),targetDisabled:e?.querySelector('[role=switch]')?.disabled,"
            +"detailsOpen:e?.closest('details')?.open,expected:window.__droyAudioExpected,touches:window.__droyAudioTouches}})()");
        java.util.concurrent.atomic.AtomicReference<JSONObject> nativeState=new java.util.concurrent.atomic.AtomicReference<>();
        instrumentation.runOnMainSync(()->{
            try {nativeState.set(new JSONObject().put("rotation",activity.getDisplay().getRotation())
                .put("requestedOrientation",activity.getRequestedOrientation()).put("configurationOrientation",activity.getResources().getConfiguration().orientation));}
            catch(Exception failure){throw new AssertionError(failure);}
        });
        geometry.put("nativeWindow",nativeState.get());
        java.nio.file.Files.write(new File(folder,label+"-failure.json").toPath(),new JSONObject().put("reason",reason).put("geometry",geometry).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        android.graphics.Bitmap screen=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
        if(screen!=null) try(java.io.FileOutputStream output=new java.io.FileOutputStream(new File(folder,label+"-failure.png"))) {
            screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);
        } finally {screen.recycle();}
        failureCaptured=true;
    }

    private void reportViewport() throws Exception {
        JSONObject viewport=(JSONObject)ui.evaluate("({width:innerWidth,height:innerHeight,font:getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size'),theme:document.documentElement.getAttribute('data-ds-theme-source')})");
        assertTrue("Portrait orientation was lost: "+viewport,viewport.getInt("height")>viewport.getInt("width"));
        java.util.concurrent.atomic.AtomicInteger zoom=new java.util.concurrent.atomic.AtomicInteger();
        android.webkit.WebView view=ui.web();
        instrumentation.runOnMainSync(()->zoom.set(view.getSettings().getTextZoom()));
        viewport.put("webTextZoom",zoom.get());
        Bundle result=new Bundle(); result.putString("stream","\nDROY_AUDIO_VIEWPORT "+viewport+"\n"); instrumentation.sendStatus(2,result);
    }

    private void settleTarget(String selector) throws Exception {
        android.webkit.WebView view=ui.web();
        instrumentation.runOnMainSync(()->view.flingScroll(0,0));
        // A CSS overflow scroller can retain compositor momentum after the root
        // WebView fling stops. A neutral gutter contact ends it before the target tap.
        Rect viewport=ui.webBounds("[data-droy-settings-options]");
        ui.tapAt(viewport.left+2,viewport.centerY());
        String[] previous={null};long[] stableSince={0};
        DeviceUi.await("Settings touch target kept scrolling: "+selector,5000,()->{
            String current=String.valueOf(ui.evaluate("(()=>{const r=document.querySelector("+JSONObject.quote(selector)+").getBoundingClientRect();return {left:r.left,top:r.top,width:r.width,height:r.height,vw:innerWidth,vh:innerHeight}})()"));
            if(current.equals(previous[0])) {if(stableSince[0]==0)stableSince[0]=android.os.SystemClock.elapsedRealtime();}
            else stableSince[0]=0;
            previous[0]=current;
            return stableSince[0]!=0&&android.os.SystemClock.elapsedRealtime()-stableSince[0]>=200;
        });
        java.util.concurrent.CountDownLatch committed=new java.util.concurrent.CountDownLatch(1);
        instrumentation.runOnMainSync(()->view.postVisualStateCallback(0,new android.webkit.WebView.VisualStateCallback(){
            @Override public void onComplete(long id){view.postOnAnimation(()->{view.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);view.invalidate();});}
        }));
        assertTrue("The target WebView frame did not commit",committed.await(5,java.util.concurrent.TimeUnit.SECONDS));
    }

    private void exerciseInput(String selector,String value,String restored,String evidence) throws Exception {
        tapControl(selector); awaitKeyboardSettled(true); replaceFocusedText(value);
        DeviceUi.await("Real keyboard input was not accepted",5000,()->value.equals(ui.evaluate("document.querySelector("+JSONObject.quote(selector)+").value")));
        captureKeyboard(evidence);
        ui.pressBack(); awaitKeyboardSettled(false);
        assertTrue("Back closed settings instead of only the keyboard",ui.truth("!!document.querySelector('[data-droy-settings-panel]')&&document.querySelector('[data-droy-audio-details]').open"));
        assertEquals("Back lost the audio input draft",value,ui.evaluate("document.querySelector("+JSONObject.quote(selector)+").value"));
        capture(selector,evidence+"-after-back");
        tapControl(selector); awaitKeyboardSettled(true); replaceFocusedText(restored);
        DeviceUi.await("Audio input draft was not restored",5000,()->restored.equals(ui.evaluate("document.querySelector("+JSONObject.quote(selector)+").value")));
        ui.pressBack(); awaitKeyboardSettled(false);
    }

    private void replaceFocusedText(String value) {
        long now=android.os.SystemClock.uptimeMillis();
        instrumentation.sendKeySync(new android.view.KeyEvent(now,now,android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_A,0,android.view.KeyEvent.META_CTRL_ON));
        instrumentation.sendKeySync(new android.view.KeyEvent(now,android.os.SystemClock.uptimeMillis(),android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_A,0,android.view.KeyEvent.META_CTRL_ON));
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DEL);
        if(!value.isEmpty()) instrumentation.sendStringSync(value);
    }

    private void awaitKeyboardSettled(boolean shown) throws Exception {
        String[] previous={""};long[] stableSince={0};
        DeviceUi.await("Audio keyboard and page insets did not settle",7000,()->{
            int[] nativeInsets=new int[2];
            instrumentation.runOnMainSync(()->{
                android.view.WindowInsets insets=activity.getWindow().getDecorView().getRootWindowInsets();
                nativeInsets[0]=insets.isVisible(android.view.WindowInsets.Type.ime())?1:0;
                nativeInsets[1]=insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
            });
            JSONObject geometry=(JSONObject)ui.evaluate("({ime:parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--droy-ime-bottom'))||0,height:innerHeight,viewport:visualViewport?.height||innerHeight})");
            boolean state=shown?nativeInsets[0]==1&&nativeInsets[1]>0&&geometry.getDouble("ime")>0:nativeInsets[0]==0&&nativeInsets[1]==0&&geometry.getDouble("ime")<1;
            String current=nativeInsets[0]+":"+nativeInsets[1]+":"+geometry;
            if(!state||!current.equals(previous[0])){previous[0]=current;stableSince[0]=android.os.SystemClock.elapsedRealtime();return false;}
            return android.os.SystemClock.elapsedRealtime()-stableSince[0]>=300;
        });
    }

    private void captureKeyboard(String name) throws Exception {
        String label=InstrumentationRegistry.getArguments().getString("audioEvidenceLabel","audio").replaceAll("[^A-Za-z0-9_-]","_");
        File directory=new File(context.getExternalFilesDir(null),"audio-settings-evidence");directory.mkdirs();
        android.graphics.Bitmap screen=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();assertNotNull(screen);
        try(java.io.FileOutputStream output=new java.io.FileOutputStream(new File(directory,label+"-"+name+".png"))){screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}
        finally{screen.recycle();}
        JSONObject geometry=(JSONObject)ui.evaluate("({width:innerWidth,height:innerHeight,ime:parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--droy-ime-bottom'))||0,focused:document.activeElement?.getAttribute('aria-label')})");
        java.nio.file.Files.write(new File(directory,label+"-"+name+".json").toPath(),geometry.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static boolean isEmulator() { return Build.FINGERPRINT.startsWith("generic")
        ||Build.FINGERPRINT.contains("emulator")||Build.MODEL.contains("sdk_gphone"); }

    private void preserveEvidence() throws Exception {
        String label=InstrumentationRegistry.getArguments().getString("audioEvidenceLabel","audio").replaceAll("[^A-Za-z0-9_-]","_");
        File source=new File(context.getExternalFilesDir(null),"audio-settings-evidence");
        File[] files=source.listFiles((directory,name)->name.startsWith(label+"-")&&(name.endsWith(".png")||name.endsWith(".json")));
        if(files==null||files.length==0) return;
        File target=new File(context.getCacheDir(),"audio-settings-evidence");
        if(!target.isDirectory()) assertTrue(target.mkdirs());
        for(File file:files) java.nio.file.Files.copy(file.toPath(),new File(target,file.getName()).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
