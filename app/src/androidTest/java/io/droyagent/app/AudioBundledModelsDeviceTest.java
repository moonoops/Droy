package io.droyagent.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Uses installed APK assets and real JNI; isolated files, no model download or microphone. */
@RunWith(AndroidJUnit4.class)
public final class AudioBundledModelsDeviceTest {
    @Test public void firstUseAndUpgradeInitializeOfflineAndRestartReusesModels() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path files=Files.createTempDirectory(target.getCacheDir().toPath(),"bundled-asr-");
        List<AudioTranscription> instances=new ArrayList<>();
        String preferences="bundled-asr-"+java.util.UUID.randomUUID();
        Context isolated=new ContextWrapper(target) {
            @Override public File getFilesDir() { return files.toFile(); }
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode) {
                return target.getSharedPreferences(preferences,mode);
            }
        };
        try {
            Path inputs=Files.createDirectories(files.resolve("dsh-home/speech-to-text/droy-input"));
            Path abandoned=inputs.resolve(java.util.UUID.randomUUID()+".wav");
            Path manual=inputs.resolve("manual-import.wav"), outside=files.resolve(java.util.UUID.randomUUID()+".wav");
            Files.write(abandoned,new byte[]{1}); Files.write(manual,new byte[]{2}); Files.write(outside,new byte[]{3});
            AudioTranscription fresh=create(isolated); instances.add(fresh);
            assertFalse("Old internal UUID input must be removed at native process startup",Files.exists(abandoned));
            assertTrue(Files.exists(manual)); assertTrue(Files.exists(outside));
            ready(fresh);
            Path internal=inputs.resolve(java.util.UUID.randomUUID()+".wav");
            Files.write(internal,new byte[]{1,2,3});
            fresh.initialize();
            assertTrue("Reinitializing an existing service must not scan live input files",Files.exists(internal));
            String owner="droy-input-dictation";
            String id=fresh.request(new JSONObject().put("operation","transcribe").put("inputPath",internal.toString()),owner).getString("transcriptId");
            JSONObject ended;
            long deadline=SystemClock.elapsedRealtime()+30000;
            do {
                ended=fresh.request(new JSONObject().put("operation","transcript").put("transcriptId",id).put("statusOnly",true),owner);
                if(ended.getBoolean("finished")) break;
                Thread.sleep(50);
            } while(SystemClock.elapsedRealtime()<deadline);
            assertTrue(ended.toString(),ended.getBoolean("finished"));
            assertEquals("failed",ended.getString("state"));
            assertFalse("Native failure must still remove its own disposable input",Files.exists(internal));
            assertFalse("Internal transcription must not appear as settings history",fresh.uiStatus().has("job"));
            assertTrue(Files.exists(manual)); assertTrue(Files.exists(outside));
            Path models=files.resolve("audio-models/sensevoice-int8");
            Path main=models.resolve("model.int8.onnx"), tokens=models.resolve("tokens.txt"), vad=models.resolve("silero_vad.onnx");
            assertEquals(239233841,Files.size(main));
            assertEquals(315894,Files.size(tokens));
            assertEquals(643854,Files.size(vad));
            shutdown(fresh);
            long preserved=1600000000000L;
            assertTrue(main.toFile().setLastModified(preserved));
            Files.delete(vad);
            byte[] damaged=Files.readAllBytes(tokens); damaged[0]^=1; Files.write(tokens,damaged);
            Files.write(models.resolve(".verified"),"sensevoice-int8-silero-v2".getBytes(StandardCharsets.UTF_8));
            AudioTranscription upgrade=create(isolated); instances.add(upgrade);
            ready(upgrade);
            assertEquals("Valid large model must be reused",preserved,main.toFile().lastModified());
            assertEquals(643854,Files.size(vad));
            assertNotEquals("Damaged same-size token file must be replaced",damaged[0],Files.readAllBytes(tokens)[0]);
            shutdown(upgrade);
            long vadTime=vad.toFile().lastModified(), tokensTime=tokens.toFile().lastModified();
            AudioTranscription restart=create(isolated); instances.add(restart);
            assertTrue("Restart must already be ready",restart.uiStatus().getBoolean("ready"));
            assertEquals(preserved,main.toFile().lastModified());
            assertEquals(vadTime,vad.toFile().lastModified());
            assertEquals(tokensTime,tokens.toFile().lastModified());
        } finally {
            for(AudioTranscription instance:instances) shutdown(instance);
            HistoryData.deleteTree(files);
            target.deleteSharedPreferences(preferences);
        }
    }

    @Test public void pausingOperationsDoesNotCancelOfflineInitializationOrPermitTranscription() throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path files=Files.createTempDirectory(target.getCacheDir().toPath(),"bundled-asr-paused-");
        String preferences="bundled-asr-paused-"+java.util.UUID.randomUUID();
        Context isolated=new ContextWrapper(target) {
            @Override public File getFilesDir() { return files.toFile(); }
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode) {
                return target.getSharedPreferences(preferences,mode);
            }
        };
        AudioTranscription asr=null;
        try {
            asr=create(isolated);
            long deadline=SystemClock.elapsedRealtime()+30000;
            JSONObject state;
            do {
                state=asr.uiStatus();
                if(state.getString("stage").equals("install") || !state.getString("state").equals("preparing")) break;
                Thread.sleep(10);
            } while(SystemClock.elapsedRealtime()<deadline);
            assertEquals("Pause must occur during real asset extraction: "+state,"install",state.getString("stage"));
            ProductPermissions.setStopped(isolated,true);
            asr.stopAll("operations_paused");
            ready(asr);
            try {
                asr.request(new JSONObject().put("operation","transcribe").put("inputPath","/does-not-exist.wav"),"paused-fixture");
                fail("Initialization must not bypass paused transcription permissions");
            } catch(SecurityException expected) {
                assertTrue(expected.getMessage().contains("暂停或关闭"));
            }
            ProductPermissions.setStopped(isolated,false);
            assertTrue("Resuming operations must not need manual initialization",asr.uiStatus().getBoolean("ready"));
        } finally {
            if(asr!=null) shutdown(asr);
            HistoryData.deleteTree(files);
            target.deleteSharedPreferences(preferences);
        }
    }

    private static AudioTranscription create(Context context) throws Exception {
        Constructor<AudioTranscription> constructor=AudioTranscription.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(context);
    }

    private static void ready(AudioTranscription asr) throws Exception {
        long end=SystemClock.elapsedRealtime()+120000;
        JSONObject state;
        do {
            state=asr.uiStatus();
            if(!state.getString("state").equals("preparing")) break;
            Thread.sleep(100);
        } while(SystemClock.elapsedRealtime()<end);
        assertTrue(state.toString(),state.getBoolean("ready"));
    }

    private static void shutdown(AudioTranscription asr) throws Exception {
        asr.stopAll("fixture_complete");
        Field field=AudioTranscription.class.getDeclaredField("worker"); field.setAccessible(true);
        ExecutorService worker=(ExecutorService)field.get(asr); worker.shutdown();
        assertTrue("ASR worker did not stop",worker.awaitTermination(30,TimeUnit.SECONDS));
    }
}
