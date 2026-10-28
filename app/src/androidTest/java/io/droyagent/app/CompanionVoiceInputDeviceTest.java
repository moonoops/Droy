package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import android.view.InputDevice;
import android.view.MotionEvent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Public synthetic audio through the real IM recorder, upload and local ASR; no hardware microphone. */
@RunWith(AndroidJUnit4.class)
public final class CompanionVoiceInputDeviceTest {
    private static final String FIXTURE_SHA256="b77f1794fe374a0ba1ee1dc458bfaf9349496cbbfc32780c50ba3c5a7ad8e373";
    private static final String HOLD=".droy-im-hold";
    private static final String RETAINED="[data-droy-retained-voice]";
    private static final String RETRY=RETAINED+" .droy-im-retained-actions button:first-child";
    private static final String SEND_ORIGINAL=RETAINED+" .droy-im-retained-actions button:nth-child(2)";
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences product,installation,wake;
    private Map<String,?> originalProduct,originalInstallation,originalWake;
    private Map<String,String> originalHome;
    private File home,preserved;
    private String marker,originalRetainedStorage;
    private boolean owned,hadHome,passed,storageCaptured;
    private MainActivity activity;
    private DeviceUi ui;
    private int touchDeviceId=Integer.MIN_VALUE;
    private long downTime;
    private final HashSet<String> recognizedJobs=new HashSet<>();

    @Before public void prepare() throws Exception {
        instrumentation=InstrumentationRegistry.getInstrumentation(); context=instrumentation.getTargetContext();
        assertEquals("The voice fixture may only operate the development app","io.droyagent.app.dev",context.getPackageName());
        boolean emulator=Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone");
        boolean developmentPhone=context.getPackageName().equals("io.droyagent.app.dev")
            && (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
            && "true".equals(InstrumentationRegistry.getArguments().getString("companionVoiceDevelopmentPhoneFixture"));
        assertTrue("Use an emulator or an explicitly selected development-phone voice fixture",emulator || developmentPhone);
        assertTrue("Stop tasks and the engine before the companion voice fixture",RuntimeService.canManageHistory());
        assertTrue("Do not interrupt a user's audio capture",AudioCapture.get(context).uiStatus().isNull("active"));
        assertFalse("Do not interrupt an existing speech job",AudioTranscription.get(context).hasActiveJobs());
        assertFalse("Do not interrupt dictation",AudioCapture.get(context).dictationActive());
        String fixture=InstrumentationRegistry.getArguments().getString("audioAsrFixture","");
        assertFalse("Pass the app-private path of the public SenseVoice zh.wav as audioAsrFixture",fixture.isEmpty());
        File source=AudioTranscription.controlledFile(context.getFilesDir(),context.getCacheDir(),fixture);
        assertEquals("Only the pinned public zh.wav is permitted",FIXTURE_SHA256,sha256(source.toPath()));
        product=ProductPermissions.preferences(context);
        installation=context.getSharedPreferences("installation",Context.MODE_PRIVATE);
        wake=context.getSharedPreferences("droy-voice-wake",Context.MODE_PRIVATE);
        originalProduct=new HashMap<>(product.getAll()); originalInstallation=new HashMap<>(installation.getAll()); originalWake=new HashMap<>(wake.getAll());
        String language=InstrumentationRegistry.getArguments().getString("companionVoiceLanguage","zh");
        assertTrue(product.edit().putBoolean(ProductPermissions.STOPPED,false)
            .putBoolean("allowAudioMicrophone",true).putBoolean("allowAudioPlayback",true).putBoolean("allowAudioTranscription",true)
            .putBoolean("backgroundRuntimeEnabled",false).putBoolean(BackgroundRuntime.STOPPED,false)
            .putBoolean("petEnabled",false).putString("petExternalDisplay","off").putString("petInternalDisplay","off")
            .putString("languagePreference",language).commit());
        assertTrue(wake.edit().putBoolean("enabled",false).commit());
        assertTrue("Prepare the local ASR model before this test; the test never downloads it",
            AudioTranscription.get(context).request(new JSONObject().put("operation","preparation"),"companion-voice-fixture").getBoolean("ready"));
        home=new File(context.getFilesDir(),"dsh-home");
        preserved=new File(context.getFilesDir(),"companion-voice-home-backup-"+UUID.randomUUID());
        originalHome=snapshot(home.toPath()); hadHome=home.exists();
        if(hadHome)assertTrue("Could not preserve the original home",home.renameTo(preserved));
        owned=true; assertTrue("Could not create isolated companion voice home",home.mkdirs());
        marker=UUID.randomUUID().toString(); Files.write(new File(home,"companion-voice-fixture-owner").toPath(),marker.getBytes(StandardCharsets.UTF_8));
        assertTrue(installation.edit().putInt("schemaVersion",1).putBoolean("completed",true).putInt("step",6)
            .putString("mode","dialogue").putBoolean("modeSelected",true).commit());
        File profile=new File(home,"profiles/mobile");assertTrue(profile.mkdirs());
        File provider=new File(home,"companion-voice-local-fixture.mjs");
        Files.write(provider.toPath(),SetupFixtureProvider.source().getBytes(StandardCharsets.UTF_8));
        JSONArray patch=new JSONArray().put(new JSONObject().put("id","agent-default-model").put("config",new JSONObject()
            .put("provider",SetupFixtureProvider.PROVIDER).put("model",SetupFixtureProvider.MODEL)))
            .put(new JSONObject().put("id","locale").put("config",new JSONObject().put("preference",language)))
            .put(new JSONObject().put("insert",new JSONArray().put(new JSONObject().put("id","companion-voice-local-fixture").put("name",provider.getAbsolutePath()))));
        Files.write(new File(profile,"cordis.patch.yml").toPath(),patch.toString().getBytes(StandardCharsets.UTF_8));
        RuntimeInstaller.installCertificate(context);RuntimeInstaller.install(context);
        String launch="am start -W -n "+context.getPackageName()+"/"+MainActivity.class.getName()+" -f 0x10008000";
        try(java.io.InputStream output=new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(launch))) {
            java.io.ByteArrayOutputStream response=new java.io.ByteArrayOutputStream();RuntimeInstaller.copy(output,response);
            assertTrue("The explicit development Activity launch failed",response.toString("UTF-8").contains("Status: ok"));
        }
        AtomicReference<MainActivity> launched=new AtomicReference<>();
        DeviceUi.await("The companion voice Activity did not resume",45000,()->{
            instrumentation.runOnMainSync(()->{
                for(android.app.Activity candidate:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
                    if(candidate instanceof MainActivity main)launched.set(main);
            });
            return launched.get()!=null;
        });
        activity=launched.get();
        ui=new DeviceUi(instrumentation,activity);ui.awaitPage();ui.hideKeyboard();
        DeviceUi.await("The offline companion fixture did not initialize",20000,()->new File(home,"setup-fixture-ready.json").isFile());
        Object retained=ui.evaluate("localStorage.getItem('droy:companion-retained-voice:v1')");
        originalRetainedStorage=retained instanceof String text?text:null; storageCaptured=true;
        installPublicAudio(Base64.encodeToString(Files.readAllBytes(source.toPath()),Base64.NO_WRAP));
        for(int id:InputDevice.getDeviceIds()) {
            InputDevice device=InputDevice.getDevice(id);
            if(device!=null && device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)
                && device.getMotionRange(MotionEvent.AXIS_X)!=null && device.getMotionRange(MotionEvent.AXIS_Y)!=null) {touchDeviceId=id;break;}
        }
        assertNotEquals("No touchscreen input source is available",Integer.MIN_VALUE,touchDeviceId);
        ui.evaluate("window.DroyMobile.openCompanion('kaka')");
        DeviceUi.await("The companion IM chat did not open",15000,()->ui.truth("!!document.querySelector('[data-droy-im-screen=chat][data-companion-id=kaka] .droy-im-chat-empty')"));
        ui.awaitPaint();
        assertFalse("An existing pending voice must never be consumed by the fixture",ui.truth("!!document.querySelector("+JSONObject.quote(RETAINED)+")"));
        assertEquals(0,userMessages().length());
    }

    @Test public void publicAudioRecordsTranscribesRetainsAndRecoversThroughTheRealIm() throws Exception {
        record(false,false);
        DeviceUi.await("Original voice was not saved with its real local transcript",120000,()->userMessages().length()==1);
        JSONObject first=userMessages().getJSONObject(0);
        assertEquals("voice",first.getString("type"));assertRecognized(first);assertNativeRecognition();
        String firstSelector=messageSelector(first.getString("id"));
        DeviceUi.await("The saved voice bubble was not rendered",10000,()->ui.truth("!!document.querySelector("+JSONObject.quote(firstSelector+" audio")+")"));
        assertMutedPlayback(firstSelector);capture("original-voice");awaitIdle();

        record(true,false);
        DeviceUi.await("Right-swipe conversion did not insert actual ASR text into the editable draft",120000,
            ()->ui.truth("(document.querySelector('.droy-im-input')?.value||'').replace(/\\s+/g,'').includes('下午5点')"));
        assertEquals("Conversion must not send a message",1,userMessages().length());assertNativeRecognition();capture("editable-transcript");
        ui.showKeyboard(".droy-im-input");
        ui.replaceFocusedText("",((String)ui.evaluate("document.querySelector('.droy-im-input').value")).length());ui.hideKeyboard();
        assertEquals("The text draft must be cleared before the next recording","",ui.evaluate("document.querySelector('.droy-im-input').value"));

        record(false,true);
        awaitRetained();assertEquals(1,userMessages().length());
        assertMutedPlayback(RETAINED);capture("retained-after-asr-denial");
        assertTrue(product.edit().putBoolean("allowAudioTranscription",true).commit());
        ui.tap(RETRY);
        DeviceUi.await("Retained original audio did not retry through local ASR and send",120000,()->userMessages().length()==2);
        JSONObject recovered=userMessages().getJSONObject(1);assertRecognized(recovered);assertNativeRecognition();
        DeviceUi.await("The recovered recording card did not leave the composer",10000,()->ui.truth("!document.querySelector("+JSONObject.quote(RETAINED)+")"));
        capture("retained-retry-success");awaitIdle();

        record(false,true);awaitRetained();int beforeModelEvents=companionModelEvents();
        ui.tap(SEND_ORIGINAL);
        DeviceUi.await("Sending original unrecognized audio did not save a voice message",15000,()->userMessages().length()==3);
        JSONObject unrecognized=userMessages().getJSONObject(2);String originalId=unrecognized.getString("id");
        assertEquals("",unrecognized.getString("text"));assertEquals("failed",unrecognized.getJSONObject("voice").getString("transcriptionState"));
        SystemClock.sleep(1500);
        assertEquals("Unrecognized original audio must not wake the conversational model",beforeModelEvents,companionModelEvents());
        assertMutedPlayback(messageSelector(originalId));capture("original-unrecognized");
        assertTrue(product.edit().putBoolean("allowAudioTranscription",true).commit());
        String retrySelector=messageSelector(originalId)+" .droy-im-transcription-state button";
        ui.tap(retrySelector);
        DeviceUi.await("Retrying a sent voice did not update that original message",120000,()->{
            JSONArray messages=userMessages();return messages.length()==3 && messages.getJSONObject(2).getString("id").equals(originalId)
                && messages.getJSONObject(2).getJSONObject("voice").optString("transcriptionState").equals("ready");
        });
        JSONObject updated=userMessages().getJSONObject(2);assertRecognized(updated);assertNativeRecognition();awaitIdle();
        DeviceUi.await("The same voice bubble did not leave its failed transcription state",10000,
            ()->ui.truth("!document.querySelector("+JSONObject.quote(retrySelector)+")&&!!document.querySelector("+JSONObject.quote(messageSelector(originalId)+" .droy-im-transcript-toggle")+")"));
        assertTrue("Recognition retry should trigger understanding only after a real transcript",companionModelEvents()>beforeModelEvents);
        assertFalse("Synthetic audio must never claim the hardware microphone lease",AudioCapture.get(context).dictationActive());
        assertEquals(4,((Number)ui.evaluate("window.__droyCompanionVoiceFixture.calls")).intValue());
        assertEquals("Only real companion uploads should supply the voice messages",3,userMessages().length());
        capture("same-message-retranscribed");
        android.util.Log.i("DroyCompanionVoiceEvidence",new JSONObject().put("input","pinned_public_zh_wav_web_audio")
            .put("hardwareMicrophone",false).put("remoteModelCalls",false).put("offlineModelEvents",companionModelEvents())
            .put("originalVoice",true).put("editableAsrDraft",true).put("retainedRetry",true).put("sameMessageRetry",true).toString());
        passed=true;
    }

    @Test public void preparingCaptureRetainsTrustedQuickRightSwipeUntilEditableTranscript() throws Exception {
        ui.evaluate("window.__droyCompanionVoiceFixture.gateCapture=true");
        record(true,false);
        DeviceUi.await("Preparing-phase right swipe did not produce an editable local transcript",120000,
            ()->ui.truth("(document.querySelector('.droy-im-input')?.value||'').replace(/\\s+/g,'').includes('下午5点')"));
        assertEquals("A preparation-phase conversion must not send a chat message",0,userMessages().length());
        assertNativeRecognition();assertEquals(1,((Number)ui.evaluate("window.__droyCompanionVoiceFixture.calls")).intValue());
        assertFalse("Synthetic preparation capture must not acquire the hardware microphone",AudioCapture.get(context).dictationActive());
        capture("preparing-quick-right-swipe");passed=true;
    }

    @Test public void movedRecordingRemainsVisibleAfterPreparationFailureCancellationAndFinish() throws Exception {
        float[] point=beginGatedCapture(true);
        try {
            touch(MotionEvent.ACTION_MOVE,point[0]+12*point[2],point[1]);
            DeviceUi.await("The trusted move did not reject the pending capture",10000,
                ()->ui.truth("window.__droyCompanionVoiceFixture.denied===1"));
            DeviceUi.await("The failed preparing recorder did not leave its overlay",10000,
                ()->ui.truth("!document.querySelector('.droy-im-recording')"));
        } finally {
            touch(MotionEvent.ACTION_UP,point[0]+12*point[2],point[1]);
            ui.evaluate("window.__droyCompanionVoiceFixture.releaseCapture?.()");
        }
        assertChatRemainsVisible();
        assertEquals("A denied preparation must not send a message",0,userMessages().length());
        capture("move-then-preparation-denied");

        point=beginGatedCapture(false);
        float end=point[0];
        try {
            end=rightSwipe(point[0],point[1],point[2]);
            DeviceUi.await("The pending recording did not track the trusted swipe",5000,
                ()->ui.truth("!!document.querySelector('.droy-im-recording.is-text')"));
        } finally {
            try {touch(MotionEvent.ACTION_CANCEL,end,point[1]);}
            finally {ui.evaluate("window.__droyCompanionVoiceFixture.releaseCapture?.()");}
        }
        DeviceUi.await("Cancelling preparation left the recorder on screen",10000,
            ()->ui.truth("!document.querySelector('.droy-im-recording')"));
        DeviceUi.await("The late stream from cancelled preparation remained live",10000,()->ui.truth(
            "window.__droyCompanionVoiceFixture.streams.length===2&&"
            +"window.__droyCompanionVoiceFixture.streams[1].getTracks().length>0&&"
            +"window.__droyCompanionVoiceFixture.streams[1].getTracks().every(t=>t.readyState==='ended')"));
        assertChatRemainsVisible();
        assertEquals("A cancelled preparation must not send a message",0,userMessages().length());
        capture("move-then-preparation-cancelled");

        record(true,false);
        DeviceUi.await("The recovered recorder did not finish into an editable transcript",120000,
            ()->ui.truth("(document.querySelector('.droy-im-input')?.value||'').replace(/\\s+/g,'').includes('下午5点')"));
        assertNativeRecognition();assertChatRemainsVisible();
        assertEquals("Recovery text conversion must not send a message",0,userMessages().length());
        assertEquals("The recovery fixture should open only its three synthetic captures",3,
            ((Number)ui.evaluate("window.__droyCompanionVoiceFixture.calls")).intValue());
        DeviceUi.await("A completed recording transition left a synthetic stream live",10000,()->ui.truth(
            "window.__droyCompanionVoiceFixture.streams.length===3&&"
            +"window.__droyCompanionVoiceFixture.streams.every(s=>s.getTracks().length>0&&s.getTracks().every(t=>t.readyState==='ended'))"));
        assertFalse("The fixture must never open the physical microphone",AudioCapture.get(context).dictationActive());
        capture("move-then-finish-without-black-screen");passed=true;
    }

    private float[] beginGatedCapture(boolean denyOnMove) throws Exception {
        ui.evaluate("(()=>{const s=window.__droyCompanionVoiceFixture;s.gateCapture=true;s.denyOnMove="+denyOnMove+"})()");
        float[] point=recordingTarget();
        int previous=((Number)ui.evaluate("window.__droyCompanionVoiceFixture.calls")).intValue();
        downTime=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,point[0],point[1]);
        try {
            DeviceUi.await("The fixture did not reach a pending public capture",15000,()->ui.truth(
                "window.__droyCompanionVoiceFixture.calls==="+(previous+1)+"&&"
                +"!window.__droyCompanionVoiceFixture.started&&!!window.__droyCompanionVoiceFixture.rejectCapture&&"
                +"!!document.querySelector('.droy-im-recording')"));
            return point;
        } catch(Exception | AssertionError failure) {
            touch(MotionEvent.ACTION_CANCEL,point[0],point[1]);
            ui.evaluate("window.__droyCompanionVoiceFixture.releaseCapture?.()");
            throw failure;
        }
    }

    private void assertChatRemainsVisible() throws Exception {
        ui.awaitPaint();
        assertTrue("A recording state transition removed the companion chat",ui.truth(
            "!!document.querySelector('[data-droy-im-screen=chat][data-companion-id=kaka] .droy-im-composer')"));
        assertFalse("A recording state transition crashed a DSH slot",ui.truth("!!document.querySelector('[data-slot-error]')"));
        assertTrue("The recording transition lost the native page readiness",ui.nativeUiReady());
        assertFalse("The recording transition restored a loading cover",ui.loadingOverlayShown());
    }

    private void installPublicAudio(String base64) throws Exception {
        ui.evaluate("(()=>{const devices=navigator.mediaDevices,original=devices.getUserMedia;"
            +"const state=window.__droyCompanionVoiceFixture={ready:false,calls:0,denied:0,ended:false,pointers:[],streams:[],sources:[]};"
            +"const types=['pointerdown','pointermove','pointerup','pointercancel','gotpointercapture','lostpointercapture'];"
            +"const pointer=e=>{if(!e.target?.closest?.('.droy-im-hold,.droy-im-recording'))return;state.pointers.push({"
            +"type:e.type,trusted:e.isTrusted,x:e.clientX,y:e.clientY,id:e.pointerId,target:e.target.className,"
            +"captured:!!e.target.hasPointerCapture?.(e.pointerId),overlay:document.querySelector('.droy-im-recording')?.className||'',"
            +"timer:document.querySelector('.droy-im-record-card strong')?.textContent||''});if(state.pointers.length>48)state.pointers.shift()};"
            +"for(const type of types)document.addEventListener(type,pointer,true);"
            +"const denyMovedCapture=e=>{if(!state.denyOnMove||!e.isTrusted||!e.target?.closest?.('.droy-im-hold,.droy-im-recording')||!state.rejectCapture)return;"
            +"state.denyOnMove=false;state.denied++;state.rejectCapture(new DOMException('Controlled public capture denial','NotAllowedError'))};"
            +"document.addEventListener('pointermove',denyMovedCapture);"
            +"state.context=new AudioContext();state.context.suspend();"
            +"const bytes=Uint8Array.from(atob("+JSONObject.quote(base64)+"),c=>c.charCodeAt(0));"
            +"state.context.decodeAudioData(bytes.buffer).then(buffer=>{state.buffer=buffer;state.ready=true},error=>state.error=String(error));"
            +"devices.getUserMedia=async constraints=>{if(!constraints?.audio||constraints.video)throw Error('Hardware input is forbidden in this public fixture');"
            +"if(!state.ready)throw Error('Public audio has not decoded');state.calls++;state.ended=false;state.started=false;state.captureRequested=true;"
            +"const destination=state.context.createMediaStreamDestination(),source=state.context.createBufferSource();"
            +"state.destination=destination;state.source=source;state.streams.push(destination.stream);state.sources.push(source);"
            +"source.buffer=state.buffer;source.connect(destination);source.onended=()=>{if(state.source===source)state.ended=true};"
            +"await state.context.resume();if(state.gateCapture){let releaseCapture,rejectCapture;"
            +"try{await new Promise((resolve,reject)=>{state.releaseCapture=releaseCapture=resolve;state.rejectCapture=rejectCapture=reject})}"
            +"catch(error){destination.stream.getTracks().forEach(t=>t.stop());source.disconnect();throw error}"
            +"finally{if(state.releaseCapture===releaseCapture)state.releaseCapture=null;if(state.rejectCapture===rejectCapture)state.rejectCapture=null}}"
            +"source.start(state.context.currentTime+0.15);if(state.source===source)state.started=true;return destination.stream};"
            +"state.cleanup=()=>{state.releaseCapture?.();devices.getUserMedia=original;for(const type of types)document.removeEventListener(type,pointer,true);"
            +"document.removeEventListener('pointermove',denyMovedCapture);"
            +"state.sources.forEach(source=>{try{source.stop()}catch{}source.disconnect()});"
            +"state.streams.forEach(s=>s.getTracks().forEach(t=>t.stop()));state.context.close()};})()");
        DeviceUi.await("The pinned public audio did not decode",10000,()->ui.truth("window.__droyCompanionVoiceFixture.ready===true"));
    }

    private float[] recordingTarget() throws Exception {
        ui.hideKeyboard();
        DeviceUi.await("The closing keyboard still owns the next recording control",5000,
            ()->!ui.keyboardVisible() && ui.truth("document.documentElement.getAttribute('data-droy-ime-visible')!=='true'"
                +"&&parseFloat(document.documentElement.style.getPropertyValue('--droy-ime-bottom')||'0')===0"));
        ui.awaitPaint();
        if(ui.truth("!!document.querySelector('.droy-im-input')"))ui.tap(".droy-im-composer button[aria-label='语音输入'],.droy-im-composer button[aria-label='Voice input']");
        DeviceUi.await("The real IM recorder was not ready",15000,()->ui.truth("!!document.querySelector('.droy-im-hold:not(:disabled)')"));
        assertEquals("The recording button must own drag gestures before pointerdown","none",
            ui.evaluate("getComputedStyle(document.querySelector('.droy-im-hold')).touchAction"));
        ui.awaitPaint();
        Rect bounds=ui.visibleWebBounds(HOLD);float x=bounds.centerX(),y=bounds.centerY();
        float scale=ui.web().getWidth()/((Number)ui.evaluate("innerWidth")).floatValue();
        return new float[]{x,y,scale};
    }

    private void record(boolean asText,boolean denyRecognition) throws Exception {
        float[] point=recordingTarget();float x=point[0],y=point[1],scale=point[2];
        int previous=((Number)ui.evaluate("window.__droyCompanionVoiceFixture.calls")).intValue();
        boolean preparingSwipe=asText && ui.truth("window.__droyCompanionVoiceFixture.gateCapture===true");
        downTime=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,x,y);
        try {
            if(preparingSwipe) {
                DeviceUi.await("The fixture did not hold getUserMedia in the preparing state",15000,()->ui.truth(
                    "window.__droyCompanionVoiceFixture.captureRequested&&!window.__droyCompanionVoiceFixture.started&&"
                    +"!!window.__droyCompanionVoiceFixture.releaseCapture&&!!document.querySelector('.droy-im-recording')"));
                x=rightSwipe(x,y,scale);
                DeviceUi.await("The trusted preparing-phase swipe did not show text conversion",5000,()->ui.truth("!!document.querySelector('.droy-im-recording.is-text')"));
                ui.evaluate("window.__droyCompanionVoiceFixture.releaseCapture()");
            }
            DeviceUi.await("The actual IM recording did not consume the synthetic stream",15000,()->ui.truth(
                "window.__droyCompanionVoiceFixture.calls==="+(previous+1)+"&&window.__droyCompanionVoiceFixture.started&&"
                +"/^\\d{2}:\\d{2}$/.test(document.querySelector('.droy-im-record-card strong')?.textContent||'')"));
            assertFalse("The fixture must not open the physical microphone",AudioCapture.get(context).dictationActive());
            if(asText) {if(!preparingSwipe)x=rightSwipe(x,y,scale);
                DeviceUi.await("The actual right-swipe did not select text conversion",5000,()->ui.truth("!!document.querySelector('.droy-im-recording.is-text')"));}
            if(denyRecognition)assertTrue(product.edit().putBoolean("allowAudioTranscription",false).commit());
            DeviceUi.await("The full public audio did not reach the actual recorder",30000,()->ui.truth("window.__droyCompanionVoiceFixture.ended===true"));
        } finally {touch(MotionEvent.ACTION_UP,x,y);}
        DeviceUi.await("The real IM recording gesture did not finish",5000,()->ui.truth("!document.querySelector('.droy-im-recording')"));
    }

    private float rightSwipe(float x,float y,float scale) {
        float end=x+90*scale;
        for(int step=1;step<=8;step++){touch(MotionEvent.ACTION_MOVE,x+(end-x)*step/8,y);SystemClock.sleep(25);}
        return end;
    }

    private void touch(int action,float x,float y) {
        MotionEvent.PointerProperties pointer=new MotionEvent.PointerProperties();pointer.id=0;pointer.toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords position=new MotionEvent.PointerCoords();position.x=x;position.y=y;position.size=1;position.pressure=action==MotionEvent.ACTION_UP?0:1;
        MotionEvent event=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{pointer},
            new MotionEvent.PointerCoords[]{position},0,0,1,1,touchDeviceId,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try {assertTrue("The device rejected a real touchscreen event",ui.automation().injectInputEvent(event,true));}finally{event.recycle();}
    }

    private JSONObject messages() throws Exception {
        ui.evaluate("window.__droyCompanionVoiceRead=null;fetch('/droy/companions/kaka/messages',{credentials:'same-origin'})"
            +".then(async r=>{const value=await r.json();window.__droyCompanionVoiceRead={ok:r.ok,value}})"
            +".catch(error=>window.__droyCompanionVoiceRead={ok:false,error:String(error)})");
        DeviceUi.await("Could not read actual companion voice messages",10000,()->ui.truth("window.__droyCompanionVoiceRead!==null"));
        JSONObject result=new JSONObject((String)ui.evaluate("JSON.stringify(window.__droyCompanionVoiceRead)"));
        assertTrue("The bundled companion API refused the fixture read",result.getBoolean("ok"));return result.getJSONObject("value");
    }
    private JSONArray userMessages() throws Exception {
        JSONArray result=new JSONArray(),all=messages().getJSONArray("messages");
        for(int i=0;i<all.length();i++){JSONObject item=all.getJSONObject(i);if(item.optString("role").equals("user"))result.put(item);}
        return result;
    }
    private void awaitIdle() throws Exception {DeviceUi.await("The offline companion reply did not finish",30000,()->messages().getJSONObject("status").getString("phase").equals("idle"));}
    private void awaitRetained() throws Exception {
        DeviceUi.await("A real ASR refusal did not keep the uploaded original recording",30000,()->ui.truth("!!document.querySelector("+JSONObject.quote(RETAINED+" audio[src]")+")"));
        assertFalse("Failed recognition must not create a fake transcript",ui.truth("!!document.querySelector("+JSONObject.quote(RETAINED+" .droy-im-transcript")+")"));
    }
    private void assertRecognized(JSONObject message) throws Exception {
        assertTrue("Expected actual recognition of the pinned public audio",message.getString("text").replaceAll("\\s+","").contains("下午5点"));
        JSONObject voice=message.getJSONObject("voice");assertEquals("ready",voice.getString("transcriptionState"));
        assertTrue(voice.getString("url").startsWith("/droy/companions/kaka/attachments/"));assertTrue(voice.getLong("durationMs")>4000);
    }
    private void assertNativeRecognition() throws Exception {
        AudioTranscription asr=AudioTranscription.get(context);String id;
        // Transient dictation is intentionally absent from the product history UI.
        // Read only this fixture's identity, then use the same owner-checked native query as DSH.
        synchronized(asr) {
            java.lang.reflect.Field latest=AudioTranscription.class.getDeclaredField("latest");latest.setAccessible(true);
            Object entry=latest.get(asr);assertNotNull("The local ASR did not create a native job",entry);
            java.lang.reflect.Field owner=entry.getClass().getDeclaredField("owner"),identity=entry.getClass().getDeclaredField("id");
            owner.setAccessible(true);identity.setAccessible(true);
            assertEquals("The latest recognition must belong to the explicit dictation fixture","droy-input-dictation",owner.get(entry));
            id=(String)identity.get(entry);
        }
        assertTrue("Each actual recognition must produce a fresh native job",recognizedJobs.add(id));
        JSONObject job=asr.request(new JSONObject().put("operation","transcript").put("transcriptId",id).put("full",true),"droy-input-dictation");
        assertEquals("completed",job.getString("state"));assertTrue(job.getBoolean("finished"));assertTrue(job.getDouble("audioSeconds")>4);
        assertTrue("The local model did not report inference work",job.getDouble("inferenceSeconds")>0);
        assertTrue(job.getString("text").replaceAll("\\s+","").contains("下午5点"));
    }
    private void assertMutedPlayback(String scope) throws Exception {
        DeviceUi.await("The raw audio element did not reach the visible UI",10000,()->ui.truth("!!document.querySelector("+JSONObject.quote(scope+" audio[src]")+")"));
        ui.awaitPaint();
        ui.evaluate("(()=>{for(const audio of document.querySelectorAll("+JSONObject.quote(scope+" audio")+"))audio.muted=true})()");
        ui.tap(scope+" .droy-im-voice-play");
        DeviceUi.await("The retained/saved original audio did not decode and play",10000,()->ui.truth("(()=>{const a=document.querySelector("+JSONObject.quote(scope+" audio")+");return !!a&&a.muted&&!a.paused&&a.currentTime>0&&a.readyState>=2})()"));
        ui.tap(scope+" .droy-im-voice-play");
        DeviceUi.await("Playback did not pause",5000,()->ui.truth("document.querySelector("+JSONObject.quote(scope+" audio")+")?.paused===true"));
    }
    private static String messageSelector(String id) {assertTrue(id.matches("[A-Za-z0-9:_-]+"));return "[data-message-id='"+id+"']";}
    private int companionModelEvents() throws Exception {
        File log=new File(home,"setup-companion-events.jsonl");return log.isFile()?Files.readAllLines(log.toPath(),StandardCharsets.UTF_8).size():0;
    }
    private void capture(String name) throws Exception {
        ui.hideKeyboard();ui.awaitPaint();File directory=new File(context.getExternalFilesDir(null),"companion-voice-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());Bitmap screenshot=ui.automation().takeScreenshot();assertNotNull(screenshot);
        try(FileOutputStream output=new FileOutputStream(new File(directory,name+".png"))){assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG,100,output));}finally{screenshot.recycle();}
    }

    @After public void restore() throws Exception {
        try {
            try {
                if(ui!=null && activity!=null && !activity.isDestroyed()) {
                    if(!passed)try{capture("failure");}catch(Exception ignored){}
                    File evidence=new File(context.getExternalFilesDir(null),"companion-voice-evidence");
                    assertTrue(evidence.isDirectory() || evidence.mkdirs());
                    Object pointers=ui.evaluate("JSON.stringify({calls:window.__droyCompanionVoiceFixture?.calls,pointers:window.__droyCompanionVoiceFixture?.pointers})");
                    Files.write(new File(evidence,"pointer-diagnostics.json").toPath(),String.valueOf(pointers).getBytes(StandardCharsets.UTF_8));
                    ui.evaluate("(()=>{window.__droyCompanionVoiceFixture?.cleanup?.();for(const a of document.querySelectorAll('audio'))a.pause()})()");
                    if(storageCaptured)ui.evaluate(originalRetainedStorage==null?"localStorage.removeItem('droy:companion-retained-voice:v1')":
                        "localStorage.setItem('droy:companion-retained-voice:v1',"+JSONObject.quote(originalRetainedStorage)+")");
                }
            } finally {
                if(activity!=null && !activity.isDestroyed())instrumentation.runOnMainSync(activity::finish);
                if(owned) {
                    instrumentation.runOnMainSync(()->RuntimeService.requestStop(context));
                    DeviceUi.await("The isolated engine did not stop before restoring the original home",25000,RuntimeService::canManageHistory);
                    DeviceUi.await("A companion fixture ASR job remained active",20000,()->!AudioTranscription.get(context).hasActiveJobs());
                    assertFalse("The synthetic fixture must not leave a microphone lease",AudioCapture.get(context).dictationActive());
                }
            }
        } finally {
            try {
                if(owned) {
                    assertTrue("Keep the original backup untouched until the engine has stopped",RuntimeService.canManageHistory());
                    if(home.exists()) {
                        assertNotNull("Refuse to remove a directory not created by this test",marker);
                        assertEquals(marker,new String(Files.readAllBytes(new File(home,"companion-voice-fixture-owner").toPath()),StandardCharsets.UTF_8));
                        HistoryData.deleteTree(home.toPath());
                    }
                    if(hadHome)assertTrue("Could not restore the preserved original home",preserved.renameTo(home));
                    assertTrue("The original home changed during the isolated voice fixture",originalHome.equals(snapshot(home.toPath())));owned=false;
                }
            } finally {restorePreferences(product,originalProduct);restorePreferences(installation,originalInstallation);restorePreferences(wake,originalWake);}
        }
    }
    private static void restorePreferences(SharedPreferences preferences,Map<String,?> values) {
        if(preferences==null || values==null)return;SharedPreferences.Editor editor=preferences.edit().clear();
        for(Map.Entry<String,?> entry:values.entrySet()) {
            Object value=entry.getValue();String key=entry.getKey();
            if(value instanceof Boolean b)editor.putBoolean(key,b);else if(value instanceof String s)editor.putString(key,s);
            else if(value instanceof Integer n)editor.putInt(key,n);else if(value instanceof Long n)editor.putLong(key,n);
            else if(value instanceof Float n)editor.putFloat(key,n);else if(value instanceof java.util.Set<?> set) {
                HashSet<String> strings=new HashSet<>();for(Object item:set)strings.add((String)item);editor.putStringSet(key,strings);
            }else throw new AssertionError("Unsupported preference type during voice fixture restore");
        }
        assertTrue("The original preferences could not be restored",editor.commit());
    }
    private static String sha256(Path path) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(java.io.InputStream input=Files.newInputStream(path)) {
            byte[] bytes=new byte[65536];int size;while((size=input.read(bytes))>=0)digest.update(bytes,0,size);
        }return RuntimeInstaller.hex(digest.digest());
    }
    private static Map<String,String> snapshot(Path root) throws Exception {
        Map<String,String> files=new TreeMap<>();if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return files;
        List<Path> paths=new ArrayList<>();try(java.util.stream.Stream<Path> walk=Files.walk(root)){walk.forEach(paths::add);}
        for(Path path:paths) {
            String name=root.relativize(path).toString();
            if(Files.isSymbolicLink(path))files.put(name,"link:"+Files.readSymbolicLink(path));
            else files.put(name,Files.isDirectory(path)?"directory":sha256(path));
        }return files;
    }
}
