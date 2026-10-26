package io.droyagent.app;

import android.app.UiAutomation;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.Assert.*;

/** Authenticated localhost requests against the real bridge; pending playback never records audio. */
@RunWith(AndroidJUnit4.class)
public final class AudioBridgeDeviceTest {
    private static final String TOKEN="fictional-local-audio-bridge-device-fixture-token";
    private static final String[] KEYS={ProductPermissions.STOPPED,"allowAudioPlayback","allowAudioTranscription"};
    private final Map<String,Boolean> previous=new HashMap<>();
    private final List<String> captures=new ArrayList<>();
    private Context context;
    private SharedPreferences preferences;
    private ProductBridge bridge;
    private String owner;

    @Before public void setUp() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Run without an existing user audio capture",AudioCapture.get(context).uiStatus().isNull("active"));
        assertFalse("Run without an existing preparation or transcription",AudioTranscription.get(context).hasActiveJobs());
        preferences=ProductPermissions.preferences(context);
        for(String key:KEYS) if(preferences.contains(key)) previous.put(key,preferences.getBoolean(key,false));
        assertTrue(preferences.edit().putBoolean(ProductPermissions.STOPPED,false)
            .putBoolean("allowAudioPlayback",true).putBoolean("allowAudioTranscription",true).commit());
        owner="audio-http-fixture-"+UUID.randomUUID();
        bridge=new ProductBridge(context,TOKEN); bridge.policy.enable();
        bridge.start(NanoHTTPD.SOCKET_READ_TIMEOUT,false);
        assertTrue(bridge.getListeningPort()>0);
    }

    @After public void tearDown() throws Exception {
        try {
            if(context!=null) for(String id:captures) {
                AudioCapture.get(context).stopFromUi(id);
                AudioCapture.get(context).deleteFromUi(id);
            }
        } finally {
            if(bridge!=null) { bridge.policy.stop(); bridge.stop(); }
            if(preferences!=null) {
                SharedPreferences.Editor restore=preferences.edit();
                for(String key:KEYS) {
                    if(previous.containsKey(key)) restore.putBoolean(key,previous.get(key)); else restore.remove(key);
                }
                assertTrue("Could not restore audio product choices",restore.commit());
            }
        }
    }

    @Test public void audioEndpointRejectsMissingBearerBeforeProcessing() throws Exception {
        Reply reply=post("/audio",new JSONObject().put("operation","preparation"),false);
        assertEquals(401,reply.httpStatus); assertFalse(reply.body.getBoolean("ok"));
        assertEquals("UNAUTHORIZED",reply.body.getJSONObject("error").getString("code"));
        assertFalse(reply.body.has("result"));
    }

    @Test public void pendingPlaybackStatusAndStopStayBoundToOwner() throws Exception {
        String id=pendingPlayback();
        JSONObject own=ok(tool("audio_status",new JSONObject().put("captureId",id),owner));
        assertEquals(id,own.getString("captureId")); assertEquals("awaiting_user",own.getString("state"));
        Reply foreign=tool("audio_status",new JSONObject().put("captureId",id),owner+"-other");
        assertEquals(200,foreign.httpStatus); assertFalse(foreign.body.getBoolean("ok"));
        assertEquals("PERMISSION_DENIED",foreign.body.getJSONObject("error").getString("code"));
        assertFalse(foreign.body.has("result"));
        Reply foreignStop=tool("audio_stop",new JSONObject().put("captureId",id),owner+"-other");
        assertEquals(200,foreignStop.httpStatus); assertFalse(foreignStop.body.getBoolean("ok"));
        assertEquals("PERMISSION_DENIED",foreignStop.body.getJSONObject("error").getString("code"));
        assertEquals("awaiting_user",ok(tool("audio_status",new JSONObject().put("captureId",id),owner)).getString("state"));
    }

    @Test public void normalTaskEndKeepsPersistentPendingCapture() throws Exception {
        String id=pendingPlayback();
        ok(post("/task/end",new JSONObject().put("taskId",owner),true));
        JSONObject status=ok(tool("audio_status",new JSONObject().put("captureId",id),owner));
        assertEquals("awaiting_user",status.getString("state"));
        assertEquals(0,status.getLong("nextCursor"));
        assertFalse(status.optBoolean("fileReady"));
        assertEquals(id,AudioCapture.get(context).uiStatus().getJSONObject("active").getString("captureId"));
    }

    @Test public void stopRemainsAvailableAfterPolicyAndProductPause() throws Exception {
        String id=pendingPlayback(); bridge.policy.stop();
        JSONObject stopped=ok(tool("audio_stop",new JSONObject().put("captureId",id),owner));
        assertEquals("completed",stopped.getString("state"));
        assertTrue(preferences.edit().putBoolean(ProductPermissions.STOPPED,true).putBoolean("allowAudioPlayback",false).commit());
        JSONObject repeated=ok(tool("audio_stop",new JSONObject().put("captureId",id),owner));
        assertEquals(id,repeated.getString("captureId")); assertEquals("completed",repeated.getString("state"));
        assertTrue(AudioCapture.get(context).uiStatus().isNull("active"));
    }

    @Test public void cancelledTranscriptionAdmissionCannotStartLateOrCreateJob() throws Exception {
        String call="cancelled-transcription-"+UUID.randomUUID();
        JSONObject before=AudioTranscription.get(context).uiStatus();
        String previousJob=before.optJSONObject("job")==null ? null : before.getJSONObject("job").getString("transcriptId");
        ok(post("/cancel",new JSONObject().put("taskId",owner).put("callId",call),true));
        Reply late=post("/audio",new JSONObject().put("operation","transcribe").put("taskId",owner).put("callId",call)
            .put("inputPath",new java.io.File(context.getCacheDir(),"missing-audio-http-fixture.wav").getAbsolutePath()),true);
        assertEquals(200,late.httpStatus); assertFalse(late.body.getBoolean("ok"));
        assertEquals("CANCELLED",late.body.getJSONObject("error").getString("code"));
        assertFalse(late.body.has("result")); assertFalse(AudioTranscription.get(context).hasActiveJobs());
        JSONObject after=AudioTranscription.get(context).uiStatus();
        String currentJob=after.optJSONObject("job")==null ? null : after.getJSONObject("job").getString("transcriptId");
        assertEquals("Rejected admission must not replace or create a job",previousJob,currentJob);
    }

    @Test public void capabilitiesExposeAvailabilityWithoutGlobalAudioData() throws Exception {
        String id=pendingPlayback();
        JSONObject result=ok(tool("capabilities",new JSONObject(),owner));
        JSONObject capture=result.getJSONObject("audioCapture"),transcription=result.getJSONObject("audioTranscription");
        assertTrue(capture.getBoolean("busy"));
        assertEquals("per_session",capture.getString("playbackAuthorization"));
        assertEquals(3,capture.getJSONArray("sources").length());
        assertTrue(transcription.has("ready")); assertTrue(transcription.has("busy"));
        Set<String> forbidden=Set.of("job","text","recent","filePath","inputPath","captureId","transcriptId");
        assertNoKeys(result,forbidden);
        assertFalse("Capability query leaked a capture identity",capture.toString().contains(id));
        boolean audioTool=false;
        JSONArray tools=result.getJSONArray("tools");
        for(int i=0;i<tools.length();i++) if(tools.getJSONObject(i).getString("action").equals("audio_start")) audioTool=true;
        assertTrue("Expected real registered audio tool capabilities",audioTool);
    }

    private String pendingPlayback() throws Exception {
        JSONObject result=ok(tool("audio_start",new JSONObject().put("source","playback").put("scope","system")
            .put("saveFile",false),owner));
        String id=result.getString("captureId"); captures.add(id);
        assertEquals("awaiting_user",result.getString("state"));
        assertEquals(0,result.getLong("nextCursor"));
        return id;
    }

    private Reply tool(String action,JSONObject args,String task) throws Exception {
        return post("/tool",new JSONObject().put("action",action).put("args",args).put("taskId",task)
            .put("callId","audio-http-"+UUID.randomUUID()),true);
    }

    private Reply post(String path,JSONObject body,boolean authenticated) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+bridge.getListeningPort()+path).openConnection();
        try {
            connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setConnectTimeout(5000); connection.setReadTimeout(10000);
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
            if(authenticated) connection.setRequestProperty("Authorization","Bearer "+TOKEN);
            byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.length);
            try(java.io.OutputStream output=connection.getOutputStream()) { output.write(bytes); }
            int status=connection.getResponseCode();
            try(InputStream input=status>=400 ? connection.getErrorStream() : connection.getInputStream();
                ByteArrayOutputStream response=new ByteArrayOutputStream()) {
                assertNotNull("HTTP bridge returned no response body",input);
                byte[] buffer=new byte[8192]; int count;
                while((count=input.read(buffer))!=-1) {
                    response.write(buffer,0,count);
                    assertTrue("Audio bridge response unexpectedly large",response.size()<=2*1024*1024);
                }
                return new Reply(status,new JSONObject(response.toString("UTF-8")));
            }
        } finally { connection.disconnect(); }
    }

    private static JSONObject ok(Reply reply) throws Exception {
        assertEquals("Unexpected HTTP transport failure",200,reply.httpStatus);
        assertTrue("Expected successful product envelope: "+reply.body,reply.body.getBoolean("ok"));
        assertFalse(reply.body.has("error")); return reply.body.getJSONObject("result");
    }

    private static void assertNoKeys(Object value,Set<String> forbidden) throws Exception {
        if(value instanceof JSONObject object) {
            Iterator<String> keys=object.keys();
            while(keys.hasNext()) {
                String key=keys.next(); assertFalse("Global audio data field leaked: "+key,forbidden.contains(key));
                assertNoKeys(object.get(key),forbidden);
            }
        } else if(value instanceof JSONArray array) for(int i=0;i<array.length();i++) assertNoKeys(array.get(i),forbidden);
    }

    private static final class Reply {
        final int httpStatus; final JSONObject body;
        Reply(int status,JSONObject body) { this.httpStatus=status; this.body=body; }
    }
}
