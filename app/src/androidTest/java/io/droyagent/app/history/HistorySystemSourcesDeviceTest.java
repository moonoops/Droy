package io.droyagent.app.history;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import android.os.Process;
import io.droyagent.androiduse.DroyAccessibilityService;
import io.droyagent.androiduse.AccessibilityRecovery;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.TimeUnit;
import org.json.*;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Android source delivery through production callbacks, with isolated storage and explicit opt-in. */
public final class HistorySystemSourcesDeviceTest {
    private android.app.Instrumentation instrumentation;
    private Context context;
    @Before public void requireExplicitControlledFixture() throws Exception {
        assumeTrue("Explicit controlled source fixture only","true".equals(InstrumentationRegistry.getArguments().getString("historySystemSourcesFixture")));
        instrumentation=InstrumentationRegistry.getInstrumentation();context=instrumentation.getTargetContext();
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        boolean emulator=Build.FINGERPRINT.contains("generic")||Build.MODEL.contains("sdk_gphone");
        if(!emulator) {
            // This is a real native probe, never a caller-supplied ready flag.
            // The separate product fixture proves host/store activation.
            Class<?> qualifier=Class.forName("io.droyagent.app.MemoryExecutionQualification");
            Method run=qualifier.getDeclaredMethod("run",Context.class);run.setAccessible(true);
            try{assertNotNull(run.invoke(null,context));}catch(InvocationTargetException failed){throw new AssertionError("Native execution qualification must pass before phone source tests",failed.getCause());}
        }
        assertTrue(context.getSystemService(PowerManager.class).isInteractive());
        assertFalse(context.getSystemService(android.app.KeyguardManager.class).isDeviceLocked());
    }
    private Intent fixtureIntent(int phase) {
        return new Intent().setComponent(new ComponentName(instrumentation.getContext().getPackageName(),HistorySourceFixtureActivity.class.getName()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("phase",phase);
    }
    @Test public void realAccessibilityTestAppEventsReachQueueAndPauseStopsThem() throws Exception {
        assertTrue("Existing accessibility authorization is required",AccessibilityRecovery.get(context).enabled());
        AccessibilityRecovery.get(context).onAppOpened(false);
        long bindingDeadline=SystemClock.elapsedRealtime()+15000;
        while(!DroyAccessibilityService.isConnected(context)&&SystemClock.elapsedRealtime()<bindingDeadline)SystemClock.sleep(100);
        assertTrue("The real accessibility service must already be enabled",DroyAccessibilityService.isConnected(context));
        String fixturePackage=instrumentation.getContext().getPackageName();
        Field observerField=DroyAccessibilityService.class.getDeclaredField("observer");observerField.setAccessible(true);
        DroyAccessibilityService.EventObserver original=(DroyAccessibilityService.EventObserver)observerField.get(null);
        try(HistoryCaptureFixture fixture=new HistoryCaptureFixture(false)) {
            fixture.capture.configure(new JSONObject().put("enabled",true).put("activityEnabled",true).put("pageTextEnabled",true)
                .put("allowedPackages",new JSONArray().put(fixturePackage)));
            DroyAccessibilityService.setEventObserver(new DroyAccessibilityService.EventObserver(){
                @Override public void onConnectionChanged(boolean connected){fixture.capture.accessibilityConnectionChanged(connected);}
                @Override public void onEvent(android.view.accessibility.AccessibilityEvent event,DroyAccessibilityService service){
                    if(fixturePackage.contentEquals(event.getPackageName()==null?"":event.getPackageName())) fixture.capture.onAccessibilityEvent(event,service,null);
                }
            });
            fixture.settle();fixture.drain();context.startActivity(fixtureIntent(0));
            JSONObject page=awaitRecord(fixture,"android_page",15000);
            assertEquals(fixturePackage,page.getJSONObject("payload").getString("packageName"));
            assertTrue(page.getJSONObject("payload").getString("text").contains("Fictional history source breakfast fixture"));
            assertEquals("unknown",page.getJSONObject("actor").getString("kind"));
            fixture.capture.configure(new JSONObject().put("paused",true));fixture.settle();fixture.drain();
            context.startActivity(fixtureIntent(1));SystemClock.sleep(750);fixture.settle();
            assertNoSource(fixture,"android_page");assertNoSource(fixture,"android_activity");
        } finally {
            DroyAccessibilityService.setEventObserver(original);
            context.startActivity(fixtureIntent(-1));
        }
    }
    @Test public void androidTestProviderDeliversMockSampleAndStopRemovesListener() throws Exception {
        assumeTrue(Build.VERSION.SDK_INT>=31);
        var automation=instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        int fineBefore=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION),coarseBefore=context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
        AppOpsManager appOps=context.getSystemService(AppOpsManager.class);
        int mockBefore=appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,Process.myUid(),context.getPackageName());
        String mockConfiguration=mockConfiguration(shell("cmd appops get "+context.getPackageName()+" android:mock_location"),mockBefore);
        String shellMockConfiguration=mockConfiguration(shell("cmd appops get com.android.shell android:mock_location"),-1);
        LocationManager manager=context.getSystemService(LocationManager.class);
        assertTrue("Location must already be enabled; fixture does not change the user's system switch",manager.isLocationEnabled());
        // A temporary shell permission identity is dropped in finally; no runtime
        // permission is permanently granted or revoked and no process is killed.
        boolean providerAdded=false;
        try {
            shell("cmd appops set "+context.getPackageName()+" android:mock_location allow");
            assertEquals("The temporary mock-location operation must be effective before installing a provider",AppOpsManager.MODE_ALLOWED,
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,Process.myUid(),context.getPackageName()));
            manager.addTestProvider(LocationManager.NETWORK_PROVIDER,false,false,false,false,false,false,false,Criteria.POWER_LOW,Criteria.ACCURACY_FINE);
            providerAdded=true;manager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER,true);
            Location sample=new Location(LocationManager.NETWORK_PROVIDER);sample.setLatitude(0.25);sample.setLongitude(0.5);sample.setAccuracy(10);
            long beforeConsentNanos=SystemClock.elapsedRealtimeNanos()-TimeUnit.MINUTES.toNanos(6);
            assertTrue("The controlled device must have six minutes uptime",beforeConsentNanos>0);
            sample.setTime(System.currentTimeMillis()-TimeUnit.MINUTES.toMillis(6));sample.setElapsedRealtimeNanos(beforeConsentNanos);
            // Seed the test provider before subscribing, so any cached first
            // callback is fictional and predates consent. Six-minute spacing
            // keeps the later fresh sample outside the five-minute rate limit.
            manager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER,sample);
            shell("cmd appops set com.android.shell android:mock_location allow");
            adoptLocationPermissions(automation);
            assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION));
            try(HistoryCaptureFixture fixture=new HistoryCaptureFixture(true)) {
                fixture.capture.configure(new JSONObject().put("locationEnabled",true));fixture.settle();
                instrumentation.runOnMainSync(fixture.capture::locationServiceStarted);fixture.settle();
                LocationListener listener=(LocationListener)HistoryCaptureFixture.field(fixture.capture,"locationListener");assertNotNull(listener);
                for(int i=0;i<20;i++)fixture.capture.refreshAuthorization();fixture.settle();
                assertSame("Unchanged Root/permission refresh must retain the five-minute subscription",listener,HistoryCaptureFixture.field(fixture.capture,"locationListener"));
                assertNoSource(fixture,"android_location");
                sample.setLatitude(0.252);sample.setLongitude(0.502);
                sample.setTime(System.currentTimeMillis());sample.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                // Permission delegation also delegates MOCK_LOCATION to shell.
                // Keep it continuous so LocationManager does not deactivate this
                // registration between permission revocation and re-adoption.
                manager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER,sample);
                JSONObject record=awaitRecord(fixture,"android_location",15000);JSONObject payload=record.getJSONObject("payload");
                assertTrue("Android must mark its test-provider delivery",payload.getBoolean("isMock"));
                assertEquals(0.252,payload.getDouble("latitude"),0);assertEquals(0.502,payload.getDouble("longitude"),0);
                fixture.capture.configure(new JSONObject().put("locationEnabled",false));fixture.settle();fixture.drain();
                assertNull("Disabling location must unsubscribe production listener",HistoryCaptureFixture.field(fixture.capture,"locationListener"));
                // Also deliver an already-received callback after stop; its epoch
                // must reject it even though the system subscription is gone.
                sample.setMock(true);instrumentation.runOnMainSync(()->listener.onLocationChanged(sample));fixture.settle();
                assertNoSource(fixture,"android_location");
            }
        } finally {
            try {
                automation.dropShellPermissionIdentity();
                try {if(providerAdded)manager.removeTestProvider(LocationManager.NETWORK_PROVIDER);}
                finally {
                    try{shell("cmd appops set "+context.getPackageName()+" android:mock_location "+mockConfiguration);}
                    finally{shell("cmd appops set com.android.shell android:mock_location "+shellMockConfiguration);}
                }
            } finally {automation.dropShellPermissionIdentity();}
            assertEquals(fineBefore,context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION));
            assertEquals(coarseBefore,context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION));
            assertEquals(mockBefore,appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,Process.myUid(),context.getPackageName()));
            assertEquals(shellMockConfiguration,mockConfiguration(shell("cmd appops get com.android.shell android:mock_location"),-1));
        }
    }
    private static void adoptLocationPermissions(android.app.UiAutomation automation) {
        automation.adoptShellPermissionIdentity(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }
    private JSONObject awaitRecord(HistoryCaptureFixture fixture,String source,long timeout) throws Exception {
        long deadline=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<deadline){
            JSONObject batch=fixture.batch();JSONArray items=batch.getJSONArray("items");JSONObject match=null;
            for(int i=0;i<items.length();i++){JSONObject item=items.getJSONObject(i);if(source.equals(item.getString("sourceType")))match=item;}
            if(items.length()>0)fixture.capture.acknowledge(batch.getString("batchId")).get(5,TimeUnit.SECONDS);
            if(match!=null)return match;SystemClock.sleep(100);
        }
        throw new AssertionError("Missing controlled Android source: "+source+"; state="+fixture.capture.status().getString("state"));
    }
    private void assertNoSource(HistoryCaptureFixture fixture,String source) throws Exception {
        JSONArray items=fixture.batch().getJSONArray("items");
        for(int i=0;i<items.length();i++)assertNotEquals(source,items.getJSONObject(i).getString("sourceType"));
    }
    private static String mockConfiguration(String output,int effectiveMode){
        var match=java.util.regex.Pattern.compile("(?m)^\\s*(?:MOCK_LOCATION|android:mock_location):\\s*(allow|ignore|deny|default|foreground)\\b").matcher(output);
        if(match.find())return match.group(1);
        var defaultMode=java.util.regex.Pattern.compile("Default mode: (allow|ignore|deny|default|foreground)\\b").matcher(output);
        if(output.contains("No operations.")&&defaultMode.find())return defaultMode.group(1);
        // "default" is MODE_DEFAULT, which is not necessarily this operation's
        // default (MOCK_LOCATION defaults to MODE_ERRORED on the test device).
        if(output.contains("No operations."))return switch(effectiveMode){case AppOpsManager.MODE_ALLOWED->"allow";case AppOpsManager.MODE_IGNORED->"ignore";case AppOpsManager.MODE_ERRORED->"deny";case AppOpsManager.MODE_DEFAULT->"default";case AppOpsManager.MODE_FOREGROUND->"foreground";default->throw new IllegalStateException("Unsupported mock-location mode");};
        throw new IllegalStateException("Cannot preserve existing mock-location setting");
    }
    private String shell(String command) throws Exception {
        String checked="("+command+") 2>&1; droy_fixture_exit=$?; printf '\\nDROY_FIXTURE_EXIT:%s\\n' \"$droy_fixture_exit\"";
        ParcelFileDescriptor[] pipes=instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommandRw("/system/bin/sh");
        try(OutputStream commands=new ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]);InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(pipes[0])) {
            commands.write((checked+"\nexit\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));commands.flush();commands.close();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] data=new byte[1024];int count;
            while((count=input.read(data))!=-1){if(bytes.size()+count>8192)throw new IOException("Fixture command output exceeded limit");bytes.write(data,0,count);}
            String value=bytes.toString("UTF-8");int result=value.lastIndexOf("DROY_FIXTURE_EXIT:");
            assertTrue("Fixture permission command did not report completion",result>=0);
            assertEquals("Fixture permission command failed", "0",value.substring(result+"DROY_FIXTURE_EXIT:".length()).trim());
            value=value.substring(0,result);assertFalse("Fixture system permission operation failed",value.contains("Exception")||value.contains("Error:"));
            return value;
        }
    }
}
