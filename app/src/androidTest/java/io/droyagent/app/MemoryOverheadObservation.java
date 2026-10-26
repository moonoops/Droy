package io.droyagent.app;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import android.system.Os;
import android.system.OsConstants;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.app.history.HistorySourceFixtureActivity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.json.*;
import static org.junit.Assert.*;

/** Explicit two-hour synthetic observation; no account data, models, or real location. */
final class MemoryOverheadObservation {
    interface Requests { JSONObject call(String operation,JSONObject args) throws Exception; }
    private static final long MINUTE=TimeUnit.MINUTES.toMillis(1);
    private final Context context;
    private final Instrumentation instrumentation;
    private final Requests requests;
    private final String text,fixturePackage;
    private final File report,cancel;
    private final PowerManager power;
    private final long clockTicks;
    private long started;
    private MemoryOverheadObservation(Context context,Instrumentation instrumentation,Requests requests,String text,String reportName) throws Exception {
        this.context=context;this.instrumentation=instrumentation;this.requests=requests;this.text=text;
        fixturePackage=instrumentation.getContext().getPackageName();power=context.getSystemService(PowerManager.class);
        report=new File(context.getCacheDir(),reportName);cancel=new File(context.getCacheDir(),"memory-overhead-cancel");
        clockTicks=Os.sysconf(OsConstants._SC_CLK_TCK);assertTrue(clockTicks>0);
        assertFalse("Preserve the previous observation report before another run",report.exists());
        assertFalse("Remove the old explicit observation cancellation marker before another run",cancel.exists());
    }
    static void run(Context context,Instrumentation instrumentation,Requests requests,String text) throws Exception {
        new MemoryOverheadObservation(context,instrumentation,requests,text,"memory-overhead-observation.ndjson").observe();
    }
    static void sampleOnly(Context context,Instrumentation instrumentation,Requests requests,String text) throws Exception {
        assertEquals("123 123 123 123",line("Name:\tlibdroy_node.so\nUid:\t123 123 123 123\nVmRSS:\t42 kB\n","Uid:"));
        MemoryOverheadObservation sample=new MemoryOverheadObservation(context,instrumentation,requests,text,"memory-resource-smoke-"+System.currentTimeMillis()+".ndjson");
        sample.started=SystemClock.elapsedRealtime();sample.sample("smoke");
    }
    private void observe() throws Exception {
        boolean wasInteractive=power.isInteractive();assertTrue("Start the controlled bright-screen phase unlocked",wasInteractive);
        assertFalse(context.getSystemService(android.app.KeyguardManager.class).isDeviceLocked());
        started=SystemClock.elapsedRealtime();boolean recording=false,slept=false;int nextSample=0,nextPage=0;
        append(new JSONObject().put("type","observation-start").put("baselineMinutes",10).put("captureMinutes",110)
            .put("sources","test-apk-activity-only").put("automaticExtraction",false).put("clockTicksPerSecond",clockTicks));
        try {
            showFixture(0,true);
            while(SystemClock.elapsedRealtime()-started<120*MINUTE) {
                if(cancel.exists())throw new AssertionError("Controlled observation cancelled; restore the durable fixture journal");
                long elapsed=SystemClock.elapsedRealtime()-started;
                if(!recording&&elapsed>=10*MINUTE) {
                    sample("baseline-end");
                    JSONObject patch=new JSONObject().put("enabled",true).put("activityEnabled",true).put("pageTextEnabled",false)
                        .put("deviceStateEnabled",false).put("locationEnabled",false).put("paused",false)
                        .put("allowedPackages",new JSONArray().put(fixturePackage));
                    requests.call("capture",new JSONObject().put("operation","configure").put("args",patch));recording=true;
                    showFixture(1,true,true);append(new JSONObject().put("type","capture-start").put("elapsedMs",elapsed));
                }
                if(!slept&&elapsed>=20*MINUTE) {
                    showFixture(1,false);shell("input keyevent KEYCODE_SLEEP");
                    long until=SystemClock.elapsedRealtime()+5000;while(power.isInteractive()&&SystemClock.elapsedRealtime()<until)Thread.sleep(50);
                    assertFalse("System sleep command did not switch off the display",power.isInteractive());slept=true;
                    append(new JSONObject().put("type","screen-off").put("elapsedMs",SystemClock.elapsedRealtime()-started));
                }
                if(elapsed>=nextSample*MINUTE) {sample(recording?"capture":"baseline");nextSample+=5;}
                if(!slept&&elapsed>=nextPage*MINUTE){showFixture(nextPage%2,true);nextPage++;}
                Thread.sleep(Math.min(30000,Math.max(100,120*MINUTE-(SystemClock.elapsedRealtime()-started))));
            }
            sample("capture-end");
            JSONArray activities=requests.call("query",new JSONObject().put("eventTypes",new JSONArray().put("activity"))).getJSONArray("items");
            assertTrue("The protected host must ingest the real test-APK activity source",activities.length()>0);
            for(int i=0;i<activities.length();i++)assertEquals(fixturePackage,activities.getJSONObject(i).getJSONObject("payload").getString("appId"));
            append(new JSONObject().put("type","observation-complete").put("elapsedMs",SystemClock.elapsedRealtime()-started)
                .put("modelCalls",0).put("testProviderUsed",false));
        } finally {
            try {
                MemoryRuntime.get(context).historyRequest(new JSONObject().put("operation","configure").put("patch",new JSONObject()
                    .put("enabled",false).put("activityEnabled",false).put("pageTextEnabled",false).put("deviceStateEnabled",false).put("locationEnabled",false)));
            } finally {
                showFixture(-1,false);
                if(wasInteractive&&!power.isInteractive())shell("input keyevent KEYCODE_WAKEUP");
                append(new JSONObject().put("type","sources-stopped").put("interactive",power.isInteractive())
                    .put("keyguardLocked",context.getSystemService(android.app.KeyguardManager.class).isDeviceLocked()));
            }
        }
    }
    private void sample(String phase) throws Exception {
        JSONObject status=requests.call(null,null),settings=status.getJSONObject("settings");
        assertTrue("Protected memory host must stay ready",status.getBoolean("available"));
        assertFalse(settings.getBoolean("automaticExtraction"));
        JSONObject capture=status.getJSONObject("capture").getJSONObject("configuration");
        for(String field:new String[]{"pageTextEnabled","deviceStateEnabled","locationEnabled"})assertFalse(capture.getBoolean(field));
        JSONObject maintenance=status.getJSONObject("maintenance"),budget=maintenance.optJSONObject("budget");
        assertEquals("Automatic capture must not call a model",0,budget==null?0:budget.getInt("calls"));
        long queryStart=SystemClock.elapsedRealtimeNanos();JSONObject result=requests.call("query",new JSONObject().put("text",text));
        long queryNanos=SystemClock.elapsedRealtimeNanos()-queryStart;assertEquals(1,result.getJSONArray("items").length());
        Intent battery=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));assertNotNull(battery);
        Debug.MemoryInfo memory=new Debug.MemoryInfo();Debug.getMemoryInfo(memory);
        JSONObject row=new JSONObject().put("type","sample").put("phase",phase).put("elapsedMs",SystemClock.elapsedRealtime()-started)
            .put("wallTimeMs",System.currentTimeMillis()).put("interactive",power.isInteractive()).put("modelCalls",0)
            .put("queryNanos",queryNanos).put("javaPssKiB",memory.getTotalPss()).put("processes",processUsage())
            .put("memoryBytes",directoryBytes(new File(context.getFilesDir(),"droy-memory")))
            .put("spoolBytes",directoryBytes(new File(context.getFilesDir(),"droy-history-spool")))
            .put("batteryLevel",battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)).put("batteryScale",battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1))
            .put("plugged",battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,-1)).put("batteryStatus",battery.getIntExtra(BatteryManager.EXTRA_STATUS,-1))
            .put("captureState",status.getJSONObject("capture").getString("state"));
        append(row);
    }
    private JSONArray processUsage() throws Exception {
        java.lang.reflect.Field holder=RuntimeService.class.getDeclaredField("lastProcess");holder.setAccessible(true);
        java.lang.Process host=(java.lang.Process)holder.get(null);assertNotNull("RuntimeService must own the current host",host);assertTrue(host.isAlive());
        int javaPid=android.os.Process.myPid();JSONArray values=new JSONArray();values.put(processRecord(javaPid,"java"));
        java.util.Set<Integer> children=new java.util.HashSet<>();
        File[] tasks=new File("/proc/self/task").listFiles();assertNotNull(tasks);
        for(File task:tasks)try {
            String list=new String(Files.readAllBytes(new File(task,"children").toPath()),StandardCharsets.UTF_8).trim();
            if(!list.isEmpty())for(String pid:list.split("\\s+"))children.add(Integer.parseInt(pid));
        }catch(java.nio.file.NoSuchFileException endedThread){ /* A completed Java worker is no longer a child owner. */ }
        File expected=new File(context.getApplicationInfo().nativeLibraryDir,"libdroy_node.so");int nodeCount=0;
        for(int pid:children) {
            File process=new File("/proc",Integer.toString(pid));
            try {
                String status=new String(Files.readAllBytes(new File(process,"status").toPath()),StandardCharsets.UTF_8);
                String uid=line(status,"Uid:");if(uid==null||Integer.parseInt(uid.split("\\s+")[0])!=android.os.Process.myUid())continue;
                String stat=new String(Files.readAllBytes(new File(process,"stat").toPath()),StandardCharsets.UTF_8);
                if(Integer.parseInt(statFields(stat)[1])!=javaPid||!Files.isSameFile(new File(process,"exe").toPath(),expected.toPath()))continue;
                values.put(processRecord(pid,"node"));nodeCount++;
            }catch(java.nio.file.NoSuchFileException endedChild){ /* An exited direct child is not the live host. */ }
        }
        assertEquals("The live RuntimeService child must uniquely match the installed Node executable",1,nodeCount);
        assertSame("The host changed while measuring its resources",host,holder.get(null));assertTrue(host.isAlive());return values;
    }
    private static String[] statFields(String stat) throws IOException {
        int close=stat.lastIndexOf(')');if(close<0)throw new IOException("Invalid process stat header");
        String[] fields=stat.substring(close+1).trim().split("\\s+");if(fields.length<20)throw new IOException("Incomplete process stat");return fields;
    }
    private static JSONObject processRecord(int pid,String role) throws Exception {
        File process=new File("/proc",Integer.toString(pid));
        String status=new String(Files.readAllBytes(new File(process,"status").toPath()),StandardCharsets.UTF_8);
        String[] fields=statFields(new String(Files.readAllBytes(new File(process,"stat").toPath()),StandardCharsets.UTF_8));
        String rss=line(status,"VmRSS:");
        return new JSONObject().put("role",role).put("pid",pid).put("startTicks",fields[19])
            .put("selfCpuTicks",Long.parseLong(fields[11])+Long.parseLong(fields[12]))
            .put("reapedChildrenCpuTicks",Long.parseLong(fields[13])+Long.parseLong(fields[14]))
            .put("rssKiB",rss==null?0:Long.parseLong(rss.split("\\s+")[0]));
    }
    private static String line(String text,String key){for(String line:text.split("\n"))if(line.startsWith(key))return line.substring(key.length()).trim();return null;}
    private static long directoryBytes(File root) throws Exception {
        if(!root.exists())return 0;try(var paths=Files.walk(root.toPath())){long total=0;for(var path:paths.filter(Files::isRegularFile).toList())total+=Files.size(path);return total;}
    }
    private void showFixture(int phase,boolean keepScreenOn) {showFixture(phase,keepScreenOn,false);}
    private void showFixture(int phase,boolean keepScreenOn,boolean freshTask) {
        context.startActivity(new Intent().setComponent(new ComponentName(fixturePackage,HistorySourceFixtureActivity.class.getName()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|(freshTask?Intent.FLAG_ACTIVITY_CLEAR_TASK:Intent.FLAG_ACTIVITY_SINGLE_TOP)).putExtra("phase",phase).putExtra("keepScreenOn",keepScreenOn));
    }
    private void append(JSONObject value) throws Exception {
        try(FileOutputStream output=new FileOutputStream(report,true)){output.write((value.toString()+"\n").getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
    }
    private void shell(String command) throws Exception {
        try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command))){
            ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] data=new byte[256];int count;
            while((count=input.read(data))!=-1){output.write(data,0,count);assertTrue(output.size()<4096);}assertEquals("",output.toString("UTF-8").trim());
        }
    }
}
