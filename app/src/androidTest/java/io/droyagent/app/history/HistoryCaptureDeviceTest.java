package io.droyagent.app.history;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.location.Location;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Method;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Callback/queue regressions with an explicit fixture gate; not Root qualification. */
public final class HistoryCaptureDeviceTest {
    private static final String APP="example.history.fixture";
    @Before public void dedicatedEmulatorOnly() {
        assumeTrue(Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk_gphone"));
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Unlock the dedicated emulator before callback tests",context.getSystemService(PowerManager.class).isInteractive());
        assertFalse(context.getSystemService(KeyguardManager.class).isDeviceLocked());
    }
    private void enableActivity(HistoryCaptureFixture fixture) throws Exception {
        fixture.capture.configure(new JSONObject().put("enabled",true).put("activityEnabled",true).put("allowedPackages",new JSONArray().put(APP)));
        fixture.capture.accessibilityConnectionChanged(true); fixture.settle(); fixture.drain();
    }
    private void activity(HistoryCaptureFixture fixture) throws Exception {
        activity(fixture,SystemClock.uptimeMillis());
    }
    private void activity(HistoryCaptureFixture fixture,long sourceTime) throws Exception {
        AccessibilityEvent event=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        try { event.setEventTime(sourceTime);event.setPackageName(APP); fixture.capture.onAccessibilityEvent(event,null,new JSONObject().put("kind","droy_activity").put("taskId","synthetic-task")); }
        finally { event.recycle(); }
    }
    private static int count(JSONObject batch,String source) throws Exception {
        int found=0;JSONArray items=batch.getJSONArray("items");
        for(int i=0;i<items.length();i++)if(source.equals(items.getJSONObject(i).getString("sourceType")))found++;
        return found;
    }
    @Test public void activityAndDeviceCallbacksReachDurableBatchThenAck() throws Exception {
        try(HistoryCaptureFixture fixture=new HistoryCaptureFixture(false)) {
            enableActivity(fixture); activity(fixture);
            JSONObject batch=fixture.batch(); assertEquals(1,count(batch,"android_activity"));
            JSONObject event=batch.getJSONArray("items").getJSONObject(0);
            assertEquals(APP,event.getJSONObject("payload").getString("packageName"));
            assertEquals("droy_activity",event.getJSONObject("actor").getString("kind"));
            String id=event.getString("eventId"),batchId=batch.getString("batchId");
            assertEquals(id,fixture.batch().getJSONArray("items").getJSONObject(0).getString("eventId"));
            assertEquals(1,fixture.capture.acknowledge(batchId).get(5,TimeUnit.SECONDS).getInt("removed"));
            fixture.capture.configure(new JSONObject().put("deviceStateEnabled",true)); fixture.settle();
            BroadcastReceiver receiver=(BroadcastReceiver)HistoryCaptureFixture.field(fixture.capture,"deviceReceiver");
            receiver.onReceive(fixture.isolated,new Intent(Intent.ACTION_BATTERY_CHANGED));
            JSONObject device=fixture.batch(); assertEquals(1,count(device,"android_device"));
            JSONObject payload=device.getJSONArray("items").getJSONObject(0).getJSONObject("payload");
            assertEquals("none",payload.getString("networkType")); assertFalse(payload.has("wifiName"));
            fixture.drain(); assertEquals(0,fixture.batch().getJSONArray("items").length());
        }
    }
    @Test public void disablingOrRevokingLeaseDropsAlreadyQueuedActivity() throws Exception {
        for(String stop:new String[]{"disable","lease","accessibility"})try(HistoryCaptureFixture fixture=new HistoryCaptureFixture(false)) {
            enableActivity(fixture);
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            fixture.writer.execute(()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            try {
                activity(fixture);
                if(stop.equals("disable"))fixture.capture.configure(new JSONObject().put("enabled",false));
                else if(stop.equals("lease")){fixture.ready.set(false);fixture.capture.refreshAuthorization();}
                else fixture.capture.accessibilityConnectionChanged(false);
            } finally {release.countDown();}
            fixture.settle();
            if(stop.equals("lease")){fixture.capture.stop("fixture_end");fixture.ready.set(true);fixture.capture.refreshAuthorization();}
            assertEquals(stop+" must invalidate queued source data",0,count(fixture.batch(),"android_activity"));
        }
    }
    @Test public void stableAuthorizationPreservesObservationButRootLossAndRecreationFenceHistory() throws Exception {
        try(HistoryCaptureFixture fixture=new HistoryCaptureFixture(false)){
            enableActivity(fixture);activity(fixture);JSONObject observed=fixture.batch();
            String original=observed.getJSONArray("items").getJSONObject(0).getString("continuityId");fixture.drain();
            HistoryEpoch epoch=(HistoryEpoch)HistoryCaptureFixture.field(fixture.capture,"epoch");long ticket=epoch.ticket();
            for(int i=0;i<20;i++)fixture.capture.refreshAuthorization();fixture.settle();activity(fixture);
            JSONObject unchanged=fixture.batch();assertEquals(0,count(unchanged,"android_activity"));
            assertEquals(ticket,epoch.ticket());assertEquals(original,unchanged.getString("continuityId"));
            fixture.ready.set(false);fixture.capture.stop("root_revoked");fixture.settle();fixture.ready.set(true);
            String stopped=fixture.batch().getString("continuityId");assertNotEquals(original,stopped);
            fixture.capture.close();assertTrue(fixture.writer.awaitTermination(5,TimeUnit.SECONDS));
            try(HistoryCapture restarted=new HistoryCapture(fixture.isolated,fixture.ready::get)){
                String renewed=restarted.readBatch(10,65536).get(5,TimeUnit.SECONDS).getString("continuityId");assertNotEquals(stopped,renewed);
                restarted.close();java.util.concurrent.ThreadPoolExecutor writer=(java.util.concurrent.ThreadPoolExecutor)HistoryCaptureFixture.field(restarted,"writer");
                assertTrue(writer.awaitTermination(5,TimeUnit.SECONDS));
            }
        }
    }
    private Location mockLocation() {
        Location location=new Location("synthetic-fixture");location.setLatitude(0.25);location.setLongitude(0.5);
        location.setAccuracy(10);location.setTime(System.currentTimeMillis());location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        if(Build.VERSION.SDK_INT>=31)location.setMock(true);return location;
    }
    private void location(HistoryCaptureFixture fixture,Location value) throws Exception {
        HistoryEpoch epoch=(HistoryEpoch)HistoryCaptureFixture.field(fixture.capture,"epoch");
        Method callback=HistoryCapture.class.getDeclaredMethod("captureLocation",long.class,Location.class);callback.setAccessible(true);
        callback.invoke(fixture.capture,epoch.ticket(),value);
    }
    @Test public void mockedLocationRetainsMarkerAndPermissionRevocationDropsPendingSample() throws Exception {
        assumeTrue(Build.VERSION.SDK_INT>=31);
        try(HistoryCaptureFixture fixture=new HistoryCaptureFixture(false)) {
            fixture.locationPermission.set(true);fixture.capture.configure(new JSONObject().put("locationEnabled",true));fixture.settle();
            location(fixture,mockLocation());JSONObject batch=fixture.batch();assertEquals(1,count(batch,"android_location"));
            JSONObject payload=batch.getJSONArray("items").getJSONObject(0).getJSONObject("payload");
            assertTrue(payload.getBoolean("isMock"));assertEquals(0.25,payload.getDouble("latitude"),0);fixture.drain();
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            fixture.writer.execute(()->{entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            try {location(fixture,mockLocation());fixture.locationPermission.set(false);}finally{release.countDown();}
            fixture.settle();assertEquals(0,count(fixture.batch(),"android_location"));
        }
    }
    @Test public void preConsentAndPauseLocationsAreRejectedButFreshResumeSampleIsAccepted() throws Exception {
        try(HistoryCaptureFixture fixture=new HistoryCaptureFixture(false)) {
            Location beforeConsent=mockLocation();
            fixture.locationPermission.set(true);fixture.capture.configure(new JSONObject().put("locationEnabled",true));fixture.settle();
            location(fixture,beforeConsent);assertEquals(0,count(fixture.batch(),"android_location"));
            location(fixture,mockLocation());assertEquals(1,count(fixture.batch(),"android_location"));fixture.drain();
            fixture.capture.configure(new JSONObject().put("paused",true));fixture.settle();
            Location duringPause=mockLocation();
            fixture.capture.configure(new JSONObject().put("paused",false));fixture.settle();
            long lowerBound=(long)HistoryCaptureFixture.field(fixture.capture,"locationNotBeforeNanos");
            for(int i=0;i<20;i++)fixture.capture.refreshAuthorization();fixture.settle();
            assertEquals(lowerBound,(long)HistoryCaptureFixture.field(fixture.capture,"locationNotBeforeNanos"));
            location(fixture,duringPause);assertEquals(0,count(fixture.batch(),"android_location"));
            location(fixture,mockLocation());assertEquals(1,count(fixture.batch(),"android_location"));
        }
    }

    @Test public void activityEventsFromBeforeConsentOrPauseCannotBeReplayedAfterResume() throws Exception {
        try(HistoryCaptureFixture fixture=new HistoryCaptureFixture(false)) {
            long beforeConsent=SystemClock.uptimeMillis()-1;enableActivity(fixture);
            activity(fixture,beforeConsent);activity(fixture,0);assertEquals(0,count(fixture.batch(),"android_activity"));
            activity(fixture);assertEquals(1,count(fixture.batch(),"android_activity"));fixture.drain();
            fixture.capture.configure(new JSONObject().put("paused",true));fixture.settle();
            long duringPause=SystemClock.uptimeMillis()-1;
            fixture.capture.configure(new JSONObject().put("paused",false));fixture.settle();
            long lowerBound=(long)HistoryCaptureFixture.field(fixture.capture,"activityNotBeforeUptime");
            for(int i=0;i<20;i++)fixture.capture.refreshAuthorization();fixture.settle();
            assertEquals(lowerBound,(long)HistoryCaptureFixture.field(fixture.capture,"activityNotBeforeUptime"));
            activity(fixture,duringPause);assertEquals(0,count(fixture.batch(),"android_activity"));
            activity(fixture);assertEquals(1,count(fixture.batch(),"android_activity"));
        }
    }

}
