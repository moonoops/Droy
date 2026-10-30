package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Empty-store fixture: short product checks keep sources off; opt-in observation records only test-APK activity. */
public final class MemoryProductDeviceTest {
    @org.junit.Rule public final org.junit.rules.TestName testName=new org.junit.rules.TestName();
    private static final String[] EMPTY_REQUIRED = {"droy-memory","droy-memory.identity.json","droy-privacy","droy-privacy.identity.json","droy-privacy-marker.json","droy-history-spool","droy-memory-archives"};
    private static final String[] PRESERVE = {"dsh-home","workspace","droy-execution","droy-memory","droy-memory.identity.json","droy-privacy","droy-privacy.identity.json","droy-privacy-marker.json","droy-history-spool","droy-memory-archives"};
    private MemoryFixtureJournal journal;
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;
    private boolean ownsState;

    @Before public void setUp() throws Exception {
        String option=switch(testName.getMethodName()){case "controlledTwoHourCaptureObservation"->"memoryOverheadFixture";case "controlledResourceSamplingSmoke"->"memoryResourceSampleSmoke";default->"memoryProductFixture";};
        assumeTrue("Requires explicit synthetic product fixture", "true".equals(InstrumentationRegistry.getArguments().getString(option)));
        instrumentation=InstrumentationRegistry.getInstrumentation(); context=instrumentation.getTargetContext();
        assertTrue("Stop existing tasks before the synthetic memory fixture",RuntimeService.canManageHistory());
        for(String name:EMPTY_REQUIRED) assertFalse("Existing private memory must not enter this fixture: "+name,Files.exists(new File(context.getFilesDir(),name).toPath(),LinkOption.NOFOLLOW_LINKS));
        assertFalse("Existing memory protection must not be reset by this fixture",context.getSharedPreferences("droy_memory",Context.MODE_PRIVATE).getBoolean("privacyEverActivated",false));
        JSONObject nativeConfiguration=MemoryRuntime.get(context).historyRequest(new JSONObject().put("operation","status")).getJSONObject("configuration");
        for(String field:new String[]{"enabled","activityEnabled","pageTextEnabled","deviceStateEnabled","locationEnabled"}) assertFalse("All collection switches must already be off",nativeConfiguration.getBoolean(field));
        journal=MemoryFixtureJournal.create(context,new File(context.getCacheDir(),"memory-product-fixture-restore.json"),"product","droy_memory","history_capture");
        ownsState=true;
        for(String name:PRESERVE)journal.preserve(name);
        assertTrue(new File(context.getFilesDir(),"dsh-home").mkdirs());
        assertTrue(context.getSharedPreferences("droy_memory",Context.MODE_PRIVATE).edit().clear()
            .putBoolean("enabled",false).putBoolean("automaticExtraction",false).commit());
        assertTrue(ProductPermissions.preferences(context).edit().putBoolean("allowRoot",false).commit());
        progress("fixture_prepared");
        activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
    @Test public void realActivationAndDurableExplicitMemoryKeepEverySourceOff() throws Exception {
        JSONObject before=activateFixtureMemory();
        String space=before.getJSONObject("store").getString("spaceId"),node=before.getJSONObject("store").getString("nodeId");
        String id="synthetic-product-"+UUID.randomUUID(),first="Synthetic memory fixture prefers fictional tea",second="Synthetic memory fixture prefers fictional water";
        JSONObject recorded=post("record",new JSONObject().put("operationId",id+".record").put("kind","preference").put("text",first));
        String recordId=recorded.getString("recordId");
        assertEquals(1,post("query",new JSONObject().put("text",first)).getJSONArray("items").length());
        progress("explicit_record_committed");
        instrumentation.runOnMainSync(()->RuntimeService.requestStop(context));
        DeviceUi.await("Engine did not stop before durable memory restart",30000,RuntimeService::canManageHistory);
        instrumentation.runOnMainSync(()->context.startForegroundService(new Intent(context,RuntimeService.class)));
        awaitReady(180000);
        progress("rootless_restart_ready");
        JSONObject restarted=getStatus(); assertSourcesOff(restarted);
        assertEquals(space,restarted.getJSONObject("store").getString("spaceId")); assertEquals(node,restarted.getJSONObject("store").getString("nodeId"));
        JSONObject saved=post("get",new JSONObject().put("recordId",recordId)); assertEquals(first,saved.getJSONObject("payload").getString("text"));
        JSONObject changed=post("revise",new JSONObject().put("operationId",id+".revise").put("recordId",recordId)
            .put("expectedRevisionId",saved.getString("revisionId")).put("patch",new JSONObject().put("payload",new JSONObject().put("text",second))));
        assertEquals(second,post("get",new JSONObject().put("recordId",recordId)).getJSONObject("payload").getString("text"));
        post("forget",new JSONObject().put("operationId",id+".forget").put("recordId",recordId).put("expectedRevisionId",changed.getString("revisionId")));
        assertEquals(0,post("query",new JSONObject().put("text","Synthetic memory fixture")).getJSONArray("items").length());
        post("settings",new JSONObject().put("enabled",false));
        assertFalse(getStatus().getJSONObject("settings").getBoolean("enabled")); assertSourcesOff(getStatus());
        assertTrue("Disabling memory must retain the protection latch",MemoryRuntime.get(context).settings().getBoolean("privacyEverActivated"));
        progress("record_revised_forgotten_and_memory_disabled");
    }
    @Test public void controlledTwoHourCaptureObservation() throws Exception {
        activateFixtureMemory();
        String text="Synthetic two-hour memory lookup fixture "+UUID.randomUUID();
        post("record",new JSONObject().put("operationId","overhead."+UUID.randomUUID()).put("kind","preference").put("text",text));
        MemoryOverheadObservation.run(context,instrumentation,(operation,args)->operation==null?getStatus():post(operation,args),text);
        progress("two_hour_observation_complete");
    }
    @Test public void controlledResourceSamplingSmoke() throws Exception {
        activateFixtureMemory();
        String text="Synthetic resource-sampling lookup fixture "+UUID.randomUUID();
        post("record",new JSONObject().put("operationId","resource."+UUID.randomUUID()).put("kind","preference").put("text",text));
        MemoryOverheadObservation.sampleOnly(context,instrumentation,(operation,args)->operation==null?getStatus():post(operation,args),text);
        progress("resource_sampling_smoke_complete");
    }
    private JSONObject activateFixtureMemory() throws Exception {
        DeviceUi ui=new DeviceUi(instrumentation,activity); ui.awaitPage();
        JSONObject initial=getStatus(); assertFalse(initial.getJSONObject("settings").getBoolean("enabled")); assertSourcesOff(initial);
        openSettings(ui);
        ui.tap("[data-droy-settings-section=droy-memory]");
        DeviceUi.await("Memory switch did not become ready",10000,()->ui.truth("!!document.querySelector('[data-droy-memory] > .droy-memory-toggle [role=switch]')&&!document.querySelector('[data-droy-memory] > .droy-memory-toggle [role=switch]').disabled"));
        ui.tap("[data-droy-memory] > .droy-memory-toggle [role=switch]");
        progress("activation_requested");
        awaitReady(180000);
        progress("host_ready");
        JSONObject before=getStatus(); assertTrue(before.getJSONObject("settings").getBoolean("enabled")); assertSourcesOff(before);
        assertTrue(MemoryRuntime.get(context).status().getJSONObject("settings").getBoolean("privacyEverActivated"));
        return before;
    }
    private void openSettings(DeviceUi ui) throws Exception {
        ui.hideKeyboard();
        DeviceUi.await("IME touch window did not close",5000,()->!ui.keyboardVisible());ui.awaitPaint();
        if(!ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')")) {
            ui.visibleWebBounds("[data-droy-menu]");android.graphics.Rect[] previous={null};long[] stableSince={0};
            DeviceUi.await("Menu target did not settle",5000,()->{
                android.graphics.Rect current=ui.webBounds("[data-droy-menu]");
                boolean hit=ui.truth("(()=>{const e=document.querySelector('[data-droy-menu]'),r=e.getBoundingClientRect(),h=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return !!h&&(h===e||e.contains(h))&&!e.closest('[inert]')})()");
                if(previous[0]==null||!previous[0].equals(current)||!hit){previous[0]=current;stableSince[0]=SystemClock.uptimeMillis();return false;}
                return SystemClock.uptimeMillis()-stableSince[0]>=150;
            });
            ui.awaitPaint();android.graphics.Rect target=ui.webBounds("[data-droy-menu]");assertEquals(previous[0],target);
            ui.tapAt(target.centerX(),target.centerY());
            DeviceUi.await("Menu did not open",5000,()->ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')"));
        }
        SettingsEntryDeviceUi.openFromDrawer(ui);
    }
    private void awaitReady(long timeout) throws Exception {
        long until=SystemClock.elapsedRealtime()+timeout;String lastHostReason="unavailable";
        while(SystemClock.elapsedRealtime()<until) {
            if(RuntimeService.ready) try { JSONObject status=getStatus();
                JSONObject gate=status.optJSONObject("gate");if(gate!=null)lastHostReason=gate.optString("reason","unavailable");
                if(status.optBoolean("available")&&status.getJSONObject("settings").getBoolean("enabled")) return;
            } catch(IOException transientRestart) { }
            Thread.sleep(250);
        }
        JSONObject nativeStatus=MemoryRuntime.get(context).status();
        String safeReason=lastHostReason.matches("[A-Z_0-9]{1,120}")?lastHostReason:"unavailable";
        progress("activation_timeout");
        throw new AssertionError("Memory activation did not complete: "+nativeStatus.getJSONObject("execution").getString("reason")+"; host="+safeReason);
    }
    private void progress(String phase) throws Exception {
        JSONObject value=new JSONObject().put("phase",phase).put("at",System.currentTimeMillis());
        try(FileOutputStream out=new FileOutputStream(new File(context.getCacheDir(),"memory-product-fixture-progress.ndjson"),true)){
            out.write((value.toString()+"\n").getBytes(StandardCharsets.UTF_8));out.getFD().sync();
        }
    }
    private static void assertSourcesOff(JSONObject status) throws Exception {
        JSONObject configuration=status.getJSONObject("capture").getJSONObject("configuration");
        for(String field:new String[]{"enabled","activityEnabled","pageTextEnabled","deviceStateEnabled","locationEnabled"}) assertFalse("Synthetic fixture unexpectedly enabled acquisition",configuration.getBoolean(field));
        assertTrue("Synthetic fixture must remain off or paused",Set.of("off","paused").contains(status.getJSONObject("capture").getString("state")));
    }
    private JSONObject getStatus() throws Exception { return request(null); }
    private JSONObject post(String operation,JSONObject args) throws Exception { return request(new JSONObject().put("operation",operation).put("args",args)); }
    private JSONObject request(JSONObject body) throws Exception {
        int port=RuntimeService.webPort;
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/droy/memory").openConnection();
        connection.setConnectTimeout(1000); connection.setReadTimeout(50000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Cookie","droy_session="+RuntimeService.webToken);
        connection.setRequestProperty("Origin","http://127.0.0.1:"+port);
        try {
            if(body!=null) { connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type","application/json; charset=utf-8"); try(OutputStream out=connection.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));} }
            int code=connection.getResponseCode(); InputStream stream=code>=400?connection.getErrorStream():connection.getInputStream();
            if(stream==null)throw new IOException("Memory response unavailable");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=stream){byte[] buffer=new byte[4096];int count;while((count=in.read(buffer))!=-1){if(bytes.size()+count>131072)throw new IOException("Memory response too large");bytes.write(buffer,0,count);}}
            JSONObject result=new JSONObject(bytes.toString("UTF-8"));
            if(!result.optBoolean("ok"))throw new IOException("Memory operation failed: "+result.optJSONObject("error").optString("code","unknown"));
            return result.getJSONObject("result");
        } finally { connection.disconnect(); }
    }
    @After public void restore() throws Exception {
        if(!ownsState)return;
        try {
            instrumentation.runOnMainSync(()->{if(activity!=null&&!activity.isDestroyed())activity.finish();RuntimeService.requestStop(context);});
            DeviceUi.await("Keep preserved data until fixture runtime stops",30000,RuntimeService::canManageHistory);
        } finally {
            assertTrue("Never restore under a live fixture runtime",RuntimeService.canManageHistory());
            journal.restore(path->{
                if(Files.isDirectory(path.toPath(),LinkOption.NOFOLLOW_LINKS))HistoryData.deleteTree(path.toPath());else Files.deleteIfExists(path.toPath());
            });
            ownsState=false;
            progress("all_paths_and_preferences_restored");
        }
    }
}
