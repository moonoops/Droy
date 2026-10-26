package io.droyagent.app;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Uses a caller-provisioned public/synthetic fixture; never opens the microphone. */
@RunWith(AndroidJUnit4.class)
public class AudioTranscriptionDeviceTest {
    @Test public void cancelledAdmissionRejectsDelayedStartBeforeFileOrModelAccess() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("Run with product operations resumed",ProductPermissions.stopped(context));
        AudioTranscription asr=AudioTranscription.get(context);
        String owner="audio-cancel-fixture",call=java.util.UUID.randomUUID().toString();
        asr.cancelStart(owner,call);
        try {
            asr.request(new JSONObject().put("operation","transcribe").put("inputPath","/does-not-exist.wav"),owner,call);
            fail("A delayed cancelled call must not create a transcription");
        } catch(java.io.InterruptedIOException expected) { }
    }

    @Test public void publicFixtureIsTranscribedWithIncrementalSegments() throws Exception {
        String path=InstrumentationRegistry.getArguments().getString("audioAsrFixture","");
        assumeTrue("Provide an app-private public/synthetic fixture explicitly",!path.isEmpty());
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("Run with product operations resumed",ProductPermissions.stopped(context));
        AudioTranscription asr=AudioTranscription.get(context);
        String owner="audio-device-fixture";
        asr.request(new JSONObject().put("operation","prepare"),owner);
        long limit=SystemClock.elapsedRealtime()+120000;
        JSONObject preparation;
        do {
            preparation=asr.request(new JSONObject().put("operation","preparation"),owner);
            if(!preparation.optString("state").equals("preparing")) break;
            Thread.sleep(100);
        } while(SystemClock.elapsedRealtime()<limit);
        assertTrue(preparation.toString(),preparation.optBoolean("ready"));
        long started=SystemClock.elapsedRealtime();
        String call=java.util.UUID.randomUUID().toString();
        JSONObject start=new JSONObject().put("operation","transcribe").put("inputPath",path).put("language","zh");
        JSONObject result=asr.request(start,owner,call);
        String id=result.getString("transcriptId");
        assertEquals(id,asr.request(start,owner,call).getString("transcriptId"));
        try {
            limit=SystemClock.elapsedRealtime()+120000;
            do {
                result=asr.request(new JSONObject().put("operation","transcript").put("transcriptId",id),owner);
                if(result.optBoolean("finished")) break;
                Thread.sleep(100);
            } while(SystemClock.elapsedRealtime()<limit);
            assertEquals(result.toString(),"completed",result.getString("state"));
            assertTrue(result.toString(),result.getBoolean("finished"));
            assertTrue(result.toString(),result.getString("text").length()>3);
            String expected=InstrumentationRegistry.getArguments().getString("audioAsrExpected","");
            if(!expected.isEmpty()) assertTrue(result.getString("text"),result.getString("text").contains(expected));
            assertTrue(result.getDouble("audioSeconds")>1);
            assertTrue(result.getJSONArray("segments").length()>0);
            int cursor=result.getInt("nextCursor");
            JSONObject tail=asr.request(new JSONObject().put("operation","transcript").put("transcriptId",id).put("cursor",cursor),owner);
            assertEquals(0,tail.getJSONArray("segments").length());
            try {
                asr.request(new JSONObject().put("operation","transcript").put("transcriptId",id),"other-owner");
                fail("Another owner must not read transcript");
            } catch(SecurityException expectedFailure) { }
            assertContentReadBoundary(context,asr,owner,id,result.getString("text"));
            android.util.Log.i("DroyAudioAsrEvidence",new JSONObject().put("elapsedMs",SystemClock.elapsedRealtime()-started)
                .put("result",result).toString());
        } finally {
            asr.request(new JSONObject().put("operation","stop_transcription").put("transcriptId",id),owner);
        }
    }

    private static void assertContentReadBoundary(Context context,AudioTranscription asr,String owner,String id,String expectedText) throws Exception {
        assertTrue("Permission boundary checks must not interrupt an existing capture",AudioCapture.get(context).uiStatus().isNull("active"));
        assertFalse("Permission boundary checks must not interrupt dictation",AudioCapture.get(context).dictationActive());
        android.content.SharedPreferences preferences=ProductPermissions.preferences(context);
        for(String key:new String[]{"allowAudioTranscription",ProductPermissions.STOPPED}) {
            boolean existed=preferences.contains(key);
            boolean original=preferences.getBoolean(key,!key.equals(ProductPermissions.STOPPED));
            try {
                assertTrue(preferences.edit().putBoolean(key,key.equals(ProductPermissions.STOPPED)).commit());
                for(int cursor:new int[]{0,1}) {
                    try {
                        asr.request(new JSONObject().put("operation","transcript").put("transcriptId",id)
                            .put("cursor",cursor).put("full",true),owner);
                        fail("Revoked transcription access must not return historical or incremental content");
                    } catch(SecurityException expected) { }
                }
                JSONObject status=asr.request(new JSONObject().put("operation","transcript").put("transcriptId",id)
                    .put("statusOnly",true).put("full",true),owner);
                assertStatusOnly(status,id);
                JSONObject stopped=asr.request(new JSONObject().put("operation","stop_transcription").put("transcriptId",id).put("full",true),owner);
                assertStatusOnly(stopped,id);
                try {
                    asr.request(new JSONObject().put("operation","transcript").put("transcriptId",id)
                        .put("statusOnly",true),"other-owner");
                    fail("Status-only access must retain owner isolation");
                } catch(SecurityException expected) { }
                JSONObject local=asr.requestFromUi(new JSONObject().put("operation","transcript").put("transcriptId",id));
                assertEquals("A real local UI action must retain access to existing history",expectedText,local.getString("text"));
                assertTrue(local.getJSONArray("segments").length()>0);
            } finally {
                android.content.SharedPreferences.Editor restore=preferences.edit();
                if(existed) restore.putBoolean(key,original); else restore.remove(key);
                assertTrue("Could not restore transcription product choice",restore.commit());
            }
        }
    }

    private static void assertStatusOnly(JSONObject status,String id) throws Exception {
        assertEquals(id,status.getString("transcriptId"));
        assertEquals("completed",status.getString("state")); assertTrue(status.getBoolean("finished"));
        for(String forbidden:new String[]{"text","segments","inputPath","filePath"})
            assertFalse("Cleanup/status endpoint leaked transcription content: "+forbidden,status.has(forbidden));
    }
}
