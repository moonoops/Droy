package io.droyagent.app;

import android.Manifest;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Emulator-only: real WebView authorization, then public synthetic audio through actual DSH dictation. */
@RunWith(AndroidJUnit4.class)
public final class AudioDictationDeviceTest {
    private static final String FIXTURE_SHA256="b77f1794fe374a0ba1ee1dc458bfaf9349496cbbfc32780c50ba3c5a7ad8e373";
    private static final String INPUT="[data-composer-input][contenteditable=true]";
    private static final String START="button[aria-label='开始录音'],button[aria-label='Start recording']";
    private static final String STOP="[data-voice-activity=recording] button[aria-label='停止并识别'],[data-voice-activity=recording] button[aria-label='Stop and transcribe']";
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences preferences;
    private Map<String,?> originalPreferences;
    private Map<String,String> originalHome;
    private File home,preservedHome,runtime;
    private boolean ownsHome,hadHome;
    private MainActivity activity;
    private DeviceUi ui;
    private final List<String> fixtureCaptures=new ArrayList<>();

    @Before public void setUp() throws Exception {
        instrumentation=InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context=instrumentation.getTargetContext();
        assertTrue("Audio dictation fixtures require a dedicated emulator with host microphone input disabled",
            Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")
                || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk_gphone"));
        assertTrue("Stop the existing engine before dictation acceptance",RuntimeService.canManageHistory());
        assertTrue("Do not interrupt a user's capture",AudioCapture.get(context).uiStatus().isNull("active"));
        assertFalse("Do not interrupt another speech job",AudioTranscription.get(context).hasActiveJobs());
        assertFalse(AudioCapture.get(context).dictationActive());
        assertEquals("Grant RECORD_AUDIO before starting this test; no runtime system dialog is scripted",
            PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
        preferences=ProductPermissions.preferences(context);
        originalPreferences=new HashMap<>(preferences.getAll());
        assertTrue(preferences.edit().putBoolean(ProductPermissions.STOPPED,false)
            .putBoolean("allowAudioMicrophone",true).putBoolean("allowAudioPlayback",true)
            .putBoolean("allowAudioTranscription",true).commit());
        RuntimeInstaller.installCertificate(context); runtime=RuntimeInstaller.install(context);
        home=new File(context.getFilesDir(),"dsh-home");
        preservedHome=new File(context.getFilesDir(),"audio-dictation-preserved-home-"+UUID.randomUUID());
        hadHome=home.exists(); originalHome=snapshot(home.toPath());
        if(hadHome) assertTrue("Could not preserve DSH home and drafts",home.renameTo(preservedHome));
        ownsHome=true; assertTrue("Could not create isolated dictation home",home.mkdirs());
        activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui=new DeviceUi(instrumentation,activity); ui.awaitPage();
        ui.evaluate("(()=>{for(const heading of document.querySelectorAll('[role=dialog] h2')){"
            +"if(!['开始使用','Get started'].includes(heading.textContent.trim()))continue;"
            +"heading.closest('[role=dialog]').querySelector('button[aria-label=关闭],button[aria-label=Close]')?.click()}})()");
        ui.hideKeyboard();
        DeviceUi.await("The real DSH composer did not mount",10000,()->ui.truth("!!document.querySelector("+JSONObject.quote(INPUT)+")"));
        assertEquals("Isolated composer must start with an empty draft","",composerText());
    }

    @After public void tearDown() throws Exception {
        try {
            if(ui!=null && activity!=null && !activity.isDestroyed()) {
                try {
                    ui.evaluate("(()=>{window.__droySyntheticAudio?.cleanup?.();window.DroyMobile?.stopMicrophone?.();"
                        +"for(const t of window.__droyHardwareStream?.getTracks?.()||[])t.stop();"
                        +"document.querySelector('[data-audio-permission-fixture]')?.remove()})()");
                } finally { instrumentation.runOnMainSync(activity::finish); }
            }
            if(ownsHome) {
                instrumentation.runOnMainSync(()->RuntimeService.requestStop(context));
                DeviceUi.await("Dictation cleanup left the isolated engine alive",20000,RuntimeService::canManageHistory);
                DeviceUi.await("Dictation cleanup left a native speech job alive",20000,()->!AudioTranscription.get(context).hasActiveJobs());
                DeviceUi.await("Dictation lease was not released",5000,()->!AudioCapture.get(context).dictationActive());
                JSONObject audit=auditIsolatedProfile();
                assertEquals("Dictation must never submit the draft",0,audit.getInt("turns"));
                assertEquals("Dictation must never invoke the main model or an agent tool",0,audit.getInt("modelOrToolEvents"));
            }
        } finally {
            try {
                if(ownsHome) {
                    instrumentation.runOnMainSync(()->RuntimeService.requestStop(context));
                    DeviceUi.await("Original home must stay preserved until shutdown",20000,RuntimeService::canManageHistory);
                    HistoryData.deleteTree(home.toPath());
                    if(hadHome) assertTrue("Could not restore original DSH home",preservedHome.renameTo(home));
                    assertEquals("Original home or drafts changed",originalHome,snapshot(home.toPath()));
                    ownsHome=false;
                }
                if(context!=null) for(String id:fixtureCaptures) {
                    AudioCapture.get(context).stopFromUi(id);AudioCapture.get(context).deleteFromUi(id);
                }
            } finally { restorePreferences(); }
        }
    }

    @Test public void realWebViewMicrophoneGrantSharesLeaseAndRevocationEndsTracks() throws Exception {
        ui.evaluate("(()=>{window.__droyHardwareState='idle';window.__droyHardwareEnded=0;"
            +"const button=document.createElement('button');button.textContent='麦克风授权测试';button.dataset.audioPermissionFixture='true';"
            +"button.style.cssText='position:fixed;top:90px;left:20px;width:220px;height:56px;z-index:2147483647';"
            +"button.onclick=()=>{window.__droyHardwareState='requesting';navigator.mediaDevices.getUserMedia({audio:true,video:false}).then(stream=>{"
            +"window.__droyHardwareStream=stream;for(const track of stream.getAudioTracks())track.addEventListener('ended',()=>window.__droyHardwareEnded++);"
            +"window.__droyHardwareState='live'},error=>window.__droyHardwareState='failed:'+error.name)};document.body.append(button)})()");
        ui.tap("[data-audio-permission-fixture]");
        DeviceUi.await("Real getUserMedia did not receive the trusted WebView microphone grant",15000,
            ()->ui.truth("window.__droyHardwareState==='live'&&window.__droyHardwareStream.getAudioTracks().some(t=>t.readyState==='live')"));
        assertTrue("WebView microphone grant did not claim the shared native lease",AudioCapture.get(context).dictationActive());
        try {
            JSONObject unexpected=AudioCapture.get(context).start(new JSONObject().put("source","playback").put("scope","system").put("saveFile",false),
                "audio-dictation-lease-fixture",UUID.randomUUID().toString());
            fixtureCaptures.add(unexpected.getString("captureId"));
            fail("Another capture must not bypass the browser dictation lease");
        } catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("听写")); }
        assertTrue("Lease check must not create a playback capture",AudioCapture.get(context).uiStatus().isNull("active"));
        assertTrue(preferences.edit().putBoolean("allowAudioMicrophone",false).commit());
        DeviceUi.await("Product revocation did not end and release the browser input",10000,
            ()->ui.truth("window.__droyHardwareStream.getAudioTracks().every(t=>t.readyState==='ended')&&window.__droyHardwareEnded>0")
                && !AudioCapture.get(context).dictationActive());
        ui.evaluate("(()=>{window.__droyHardwareDenied='pending';navigator.mediaDevices.getUserMedia({audio:true,video:false})"
            +".then(stream=>{stream.getTracks().forEach(t=>t.stop());window.__droyHardwareDenied='unexpected-grant'},"
            +"()=>window.__droyHardwareDenied='denied')})()");
        DeviceUi.await("Revoked product access still granted a new microphone stream",5000,
            ()->"denied".equals(ui.evaluate("window.__droyHardwareDenied")));
        assertTrue(preferences.edit().putBoolean("allowAudioMicrophone",true).commit());
        ui.tap("[data-audio-permission-fixture]");
        DeviceUi.await("Microphone did not reopen after restoring its product grant",10000,
            ()->ui.truth("window.__droyHardwareState==='live'&&window.__droyHardwareStream.getAudioTracks().some(t=>t.readyState==='live')"));
        instrumentation.runOnMainSync(()->activity.moveTaskToBack(true));
        DeviceUi.await("Leaving the foreground did not stop short dictation",10000,
            ()->!AudioCapture.get(context).dictationActive()
                && ui.truth("window.__droyHardwareStream.getAudioTracks().every(t=>t.readyState==='ended')"));
        context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        ui.awaitPage();
        ui.tap("[data-audio-permission-fixture]");
        DeviceUi.await("Microphone did not reopen in the foreground",10000,
            ()->ui.truth("window.__droyHardwareStream.getAudioTracks().some(t=>t.readyState==='live')"));
        instrumentation.runOnMainSync(()->RuntimeService.requestStop(context));
        DeviceUi.await("Explicit engine stop left the browser microphone open",10000,
            ()->!AudioCapture.get(context).dictationActive()
                && ui.truth("window.__droyHardwareStream.getAudioTracks().every(t=>t.readyState==='ended')"));
        assertEquals("Microphone permission fixture must not write a draft","",composerText());
    }

    @Test public void syntheticPublicAudioUsesRealDshRecorderRemoteAsrAndUnsentDraft() throws Exception {
        String fixture=InstrumentationRegistry.getArguments().getString("audioAsrFixture","");
        assertFalse("Pass audioAsrFixture as the public zh.wav app-private path",fixture.isEmpty());
        File source=AudioTranscription.controlledFile(context.getFilesDir(),context.getCacheDir(),fixture);
        assertEquals("Only the pinned public SenseVoice zh.wav is accepted",FIXTURE_SHA256,sha256(source.toPath()));
        assertTrue("Prepare the local model outside this UI test; no model download is performed",
            AudioTranscription.get(context).request(new JSONObject().put("operation","preparation"),"dictation-fixture").getBoolean("ready"));
        String base64=Base64.encodeToString(Files.readAllBytes(source.toPath()),Base64.NO_WRAP);
        ui.evaluate("(()=>{const devices=navigator.mediaDevices,original=devices.getUserMedia;"
            +"const state=window.__droySyntheticAudio={ready:false,started:false,ended:false,calls:0};"
            +"state.context=new AudioContext();state.context.suspend();"
            +"const bytes=Uint8Array.from(atob("+JSONObject.quote(base64)+"),c=>c.charCodeAt(0));"
            +"state.context.decodeAudioData(bytes.buffer).then(buffer=>{state.buffer=buffer;state.duration=buffer.duration;state.ready=true},"
            +"error=>state.error=error.name+':'+error.message);"
            +"devices.getUserMedia=async constraints=>{if(!constraints?.audio)return original.call(devices,constraints);"
            +"state.calls++;if(!state.ready)throw Error('Public audio fixture has not decoded');"
            +"state.destination=state.context.createMediaStreamDestination();state.source=state.context.createBufferSource();"
            +"state.source.buffer=state.buffer;state.source.connect(state.destination);state.source.onended=()=>state.ended=true;"
            +"await state.context.resume();state.source.start(state.context.currentTime+0.15);state.started=true;return state.destination.stream};"
            +"state.cleanup=()=>{devices.getUserMedia=original;try{state.source?.stop()}catch{}"
            +"state.destination?.stream.getTracks().forEach(track=>track.stop());state.context.close()};})()");
        DeviceUi.await("Public WebAudio fixture did not decode",10000,()->ui.truth("window.__droySyntheticAudio.ready===true"));
        DeviceUi.await("Real DSH microphone trigger did not become available",15000,
            ()->ui.truth("[...document.querySelectorAll("+JSONObject.quote(START)+")].some(e=>!e.disabled&&e.getClientRects().length>0)"));
        ui.tap(START);
        DeviceUi.await("Actual DSH MediaRecorder did not start on the synthetic input",10000,
            ()->ui.truth("window.__droySyntheticAudio.calls===1&&window.__droySyntheticAudio.started&&"
                +"window.__droySyntheticAudio.context.state==='running'&&!!document.querySelector('[data-voice-activity=recording]')"));
        DeviceUi.await("Public audio was not fully delivered to MediaRecorder",20000,
            ()->ui.truth("window.__droySyntheticAudio.ended===true"));
        ui.tap(STOP);
        DeviceUi.await("DSH did not enter actual transcription after recorder stop",10000,
            ()->ui.truth("!!document.querySelector('[data-voice-activity=transcribing]')") || composerText().replaceAll("\\s+","").contains("下午5点"));
        DeviceUi.await("Real local transcription did not insert the public audio into the DSH draft",120000,
            ()->composerText().replaceAll("\\s+","").contains("下午5点"));
        assertEquals(1,((Number)ui.evaluate("window.__droySyntheticAudio.calls")).intValue());
        assertTrue("DSH did not finish the real recording/transcription state",ui.truth("!document.querySelector('[data-voice-activity=recording],[data-voice-activity=transcribing]')"));
        JSONObject job=AudioTranscription.get(context).uiStatus().getJSONObject("job");
        assertEquals("completed",job.getString("state")); assertTrue(job.getBoolean("finished"));
        assertTrue("The native provider did not process audible fixture duration",job.getDouble("audioSeconds")>4);
        assertTrue("Native result and editor must refer to the same recognition",job.getString("text").replaceAll("\\s+","").contains("下午5点"));
        assertFalse("Synthetic fixture must not claim a hardware microphone lease",AudioCapture.get(context).dictationActive());
        android.util.Log.i("DroyAudioDictationEvidence",new JSONObject().put("input","pinned_public_web_audio_stream")
            .put("hardwareMicrophone",false).put("draftInserted",true).put("submitted",false)
            .put("audioSeconds",job.getDouble("audioSeconds")).put("inferenceSeconds",job.getDouble("inferenceSeconds")).toString());
    }

    private String composerText() throws Exception {
        Object value=ui.evaluate("document.querySelector("+JSONObject.quote(INPUT)+")?.textContent||''");
        return value instanceof String text ? text : "";
    }

    private JSONObject auditIsolatedProfile() throws Exception {
        int port;
        try(ServerSocket socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) { port=socket.getLocalPort(); }
        String script="""
            import {pathToFileURL} from 'node:url';
            const base=process.env.DROY_RUNTIME_DIR;
            await import(pathToFileURL(base+'/mobile/bootstrap.mjs'));
            const {runProfile}=await import(pathToFileURL(base+'/node_modules/@deepseek-ai/dsh/lib/profile-boot.js'));
            const {createLaunchEnvironmentSnapshot}=await import(pathToFileURL(base+'/node_modules/@deepseek-ai/dsh-launch-environment/lib/index.js'));
            const instance=await runProfile({profile:'mobile',patchFiles:[],args:['--no-open'],environment:createLaunchEnvironmentSnapshot([{source:'process',values:process.env}])});
            try {
              const {ctx}=instance;let turns=0,modelOrToolEvents=0;
              for(const {header} of await ctx.sessionPersistence.list()) {
                const handle=await ctx.sessionPersistence.open(header.id,'read');
                try { const {events}=await handle.read();turns+=events.filter(e=>e.type==='turn/start').length;
                  modelOrToolEvents+=events.filter(e=>['request/header','assistant/attempt','assistant/message','tool/call'].includes(e.type)).length;
                } finally {await handle.close();}
              }
              console.log('DROY_DICTATION_AUDIT '+JSON.stringify({turns,modelOrToolEvents}));
            } finally {await instance.shutdown.shutdown(0);}
            """;
        String nativeDirectory=context.getApplicationInfo().nativeLibraryDir;
        ProcessBuilder builder=new ProcessBuilder(nativeDirectory+"/libdroy_node.so","--expose-internals","--input-type=module","-e",script);
        builder.directory(context.getFilesDir()).redirectErrorStream(true);
        Map<String,String> env=builder.environment();
        env.put("DSH_HOME",home.getAbsolutePath());env.put("HOME",home.getAbsolutePath());
        env.put("DROY_RUNTIME_DIR",runtime.getAbsolutePath());env.put("DROY_NATIVE_DIR",nativeDirectory);
        env.put("LD_LIBRARY_PATH",nativeDirectory);env.put("TMPDIR",context.getCacheDir().getAbsolutePath());
        env.put("DSH_TELEMETRY_DISABLED","1");env.put("DROY_WEB_PORT",Integer.toString(port));env.put("DROY_BRIDGE_PORT","9");
        env.put("DROY_WEB_TOKEN","fictional-dictation-audit-local-token-123456789");
        env.put("DROY_BRIDGE_TOKEN","fictional-dictation-audit-local-token-123456789");
        Process process=builder.start();ByteArrayOutputStream output=new ByteArrayOutputStream();
        Thread reader=new Thread(()->{try(InputStream input=process.getInputStream()){RuntimeInstaller.copy(input,output);}
            catch(Exception failure){throw new AssertionError("Could not read isolated dictation audit",failure);}},"droy-dictation-audit");
        reader.start();
        if(!process.waitFor(60,TimeUnit.SECONDS)) {process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);fail("Isolated dictation audit timed out");}
        reader.join(2000);String text=output.toString(StandardCharsets.UTF_8.name());
        assertEquals("Isolated dictation audit failed: "+text.substring(Math.max(0,text.length()-2000)),0,process.exitValue());
        String prefix="DROY_DICTATION_AUDIT ";int start=text.lastIndexOf(prefix);assertTrue("Missing dictation audit result",start>=0);
        return new JSONObject(text.substring(start+prefix.length()).split("\\R",2)[0]);
    }

    private void restorePreferences() {
        if(preferences==null || originalPreferences==null) return;
        SharedPreferences.Editor restore=preferences.edit().clear();
        for(Map.Entry<String,?> entry:originalPreferences.entrySet()) {
            String key=entry.getKey();Object value=entry.getValue();
            if(value instanceof Boolean b)restore.putBoolean(key,b);
            else if(value instanceof String s)restore.putString(key,s);
            else if(value instanceof Integer n)restore.putInt(key,n);
            else if(value instanceof Long n)restore.putLong(key,n);
            else if(value instanceof Float n)restore.putFloat(key,n);
            else if(value instanceof java.util.Set<?> set) {java.util.Set<String> strings=new java.util.HashSet<>();for(Object item:set)strings.add((String)item);restore.putStringSet(key,strings);}
            else throw new AssertionError("Unsupported product preference type");
        }
        assertTrue("Could not restore dictation product preferences",restore.commit());
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try(InputStream input=Files.newInputStream(file)) {byte[] block=new byte[131072];int count;while((count=input.read(block))!=-1)hash.update(block,0,count);}
        return RuntimeInstaller.hex(hash.digest());
    }

    private static Map<String,String> snapshot(Path root) throws Exception {
        Map<String,String> result=new TreeMap<>();if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return result;
        List<Path> paths=new ArrayList<>();try(java.util.stream.Stream<Path> walk=Files.walk(root)){walk.forEach(paths::add);}Collections.sort(paths);
        for(Path path:paths) {
            String relative=root.relativize(path).toString();
            if(Files.isSymbolicLink(path))result.put(relative,"link:"+Files.readSymbolicLink(path));
            else if(Files.isDirectory(path))result.put(relative,"directory");
            else result.put(relative,sha256(path));
        }
        return result;
    }
}
