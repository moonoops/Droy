package io.droyagent.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Isolated storage checks; pending playback requests never open audio hardware. */
public final class AudioTemporaryStorageDeviceTest {
    @Test public void voiceCaptureIsTransientAndKeepsOnlyABoundedReleaseWindow() throws Exception {
        withStorage((context, directory) -> {
            AudioCapture audio=create(context);
            try {
                String first=null, last=null;
                for(int i=0;i<12;i++) {
                    JSONObject started=audio.start(new JSONObject().put("source","playback").put("scope","system")
                        .put("saveFile",true),"droy-voice-wake",UUID.randomUUID().toString());
                    String id=started.getString("captureId");
                    if(first==null) first=id;
                    last=id;
                    assertFalse("Internal wake recordings must never write a WAV",started.getBoolean("saveFile"));
                    audio.stop(id,"droy-voice-wake");
                    assertEquals("completed",audio.status(id,"droy-voice-wake").getString("state"));
                }
                String expired=first;
                assertThrows(IllegalArgumentException.class,() -> audio.status(expired,"droy-voice-wake"));
                assertEquals("completed",audio.stop(last,"droy-voice-wake").getString("state"));
                assertEquals(0,audio.uiStatus().getJSONArray("recent").length());
                try(java.util.stream.Stream<Path> paths=Files.list(directory)) { assertEquals(0,paths.count()); }
            } finally { unregister(context,audio); }
        });
    }

    @Test public void recoveryRemovesOnlyRecordingsWithKnownInternalOwnership() throws Exception {
        withStorage((context, directory) -> {
            String internal=UUID.randomUUID().toString(), manual=UUID.randomUUID().toString(), orphan=UUID.randomUUID().toString();
            metadata(directory,internal,"droy-voice"); metadata(directory,manual,"product-user-action");
            for(String extension:new String[]{".wav",".wav.partial",".json.tmp"})
                Files.write(directory.resolve(internal+extension),new byte[]{1,2,3});
            Files.write(directory.resolve(manual+".wav"),new byte[]{4,5,6});
            Files.write(directory.resolve(orphan+".wav"),new byte[]{7,8,9});
            AudioCapture audio=create(context);
            try {
                for(String extension:new String[]{".wav",".wav.partial",".json.tmp",".json"})
                    assertFalse(Files.exists(directory.resolve(internal+extension)));
                assertTrue(Files.exists(directory.resolve(manual+".wav")));
                assertTrue(Files.exists(directory.resolve(manual+".json")));
                assertTrue("No owner evidence means the file is retained",Files.exists(directory.resolve(orphan+".wav")));
                assertEquals(1,audio.uiStatus().getJSONArray("recent").length());
                assertEquals(manual,audio.uiStatus().getJSONArray("recent").getJSONObject(0).getString("captureId"));
            } finally { unregister(context,audio); }
        });
    }

    private static void metadata(Path directory,String id,String owner) throws Exception {
        JSONObject data=new JSONObject().put("captureId",id).put("owner",owner).put("requestId",UUID.randomUUID().toString())
            .put("source","microphone").put("scope","microphone").put("saveFile",true).put("createdAt",1)
            .put("state","completed");
        Files.write(directory.resolve(id+".json"),data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private interface Check { void run(Context context,Path directory) throws Exception; }
    private static void withStorage(Check check) throws Exception {
        Context target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Path files=Files.createTempDirectory(target.getCacheDir().toPath(),"audio-retention-");
        String preferences="audio-retention-"+UUID.randomUUID();
        Context isolated=new ContextWrapper(target) {
            @Override public File getFilesDir() { return files.toFile(); }
            @Override public SharedPreferences getSharedPreferences(String name,int mode) { return target.getSharedPreferences(preferences,mode); }
        };
        try { check.run(isolated,Files.createDirectories(files.resolve("audio-captures"))); }
        finally { HistoryData.deleteTree(files); target.deleteSharedPreferences(preferences); }
    }
    private static AudioCapture create(Context context) throws Exception {
        Constructor<AudioCapture> constructor=AudioCapture.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true); return constructor.newInstance(context);
    }
    private static void unregister(Context context,AudioCapture audio) throws Exception {
        Field listener=AudioCapture.class.getDeclaredField("preferencesChanged"); listener.setAccessible(true);
        ProductPermissions.preferences(context).unregisterOnSharedPreferenceChangeListener(
            (SharedPreferences.OnSharedPreferenceChangeListener)listener.get(audio));
    }
}
