package io.droyagent.app;

import android.content.*;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class ComputeDeviceTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void isolatedComputeAndArtifacts() throws Exception {
        ComputeTools tools = new ComputeTools(context);
        JSONObject result = tools.execute("compute_run", new JSONObject().put("code", "console.log('ready');artifact('sum.csv','sum\\n'+input.reduce((a,b)=>a+b,0),'text/csv');return input.reduce((a,b)=>a+b,0);")
            .put("input", new JSONArray().put(2).put(5).put(9)), () -> {});
        assertEquals(result.toString(), "completed", result.getString("status"));
        assertEquals(16, result.getInt("result"));
        assertTrue(result.getJSONObject("isolation").getBoolean("androidIsolatedProcess"));
        assertNotEquals(android.os.Process.myUid(), result.getJSONObject("isolation").getInt("uid"));
        assertFalse(result.getJSONObject("isolation").getBoolean("networkPermission"));
        JSONObject artifact = tools.execute("compute_artifact", new JSONObject().put("jobId", result.getString("jobId")).put("name", "sum.csv"), () -> {});
        assertEquals("sum\n16", artifact.getString("text"));
        try {
            tools.execute("compute_artifact", new JSONObject().put("jobId", result.getString("jobId")).put("name", "../models.json"), () -> {});
            fail("Traversal must be rejected");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void timeoutAndCancellationReleaseWorker() throws Exception {
        ComputeTools tools = new ComputeTools(context);
        JSONObject timed = tools.execute("compute_run", new JSONObject().put("code", "while(true){}").put("timeoutMs", 100), () -> {});
        assertEquals(timed.toString(), "timed_out", timed.getString("status"));
        long began = SystemClock.elapsedRealtime();
        try {
            tools.execute("compute_run", new JSONObject().put("code", "while(true){}").put("timeoutMs", 30000), () -> {
                if (SystemClock.elapsedRealtime() - began > 500) throw new InterruptedException("test stop");
            });
            fail("Task should stop");
        } catch (InterruptedException expected) { }
        assertTrue(SystemClock.elapsedRealtime() - began < 4000);
        JSONObject after = tools.execute("compute_run", new JSONObject().put("code", "return 7;"), () -> {});
        assertEquals(after.toString(), "completed", after.getString("status"));
    }

    @Test public void isolatedUidCannotReadAppFilesOrConnectToNetwork() throws Exception {
        File secret = new File(context.getFilesDir(), "fictional-compute-isolation.txt");
        try (OutputStream out = new FileOutputStream(secret)) { out.write("fictional-private-value".getBytes(StandardCharsets.UTF_8)); }
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            String runner = "import fs from 'node:fs';import net from 'node:net';let file;try{fs.readFileSync(" + JSONObject.quote(secret.getAbsolutePath()) + ");file='allowed'}catch(e){file=e.code}"
                + "const socket=net.createConnection({host:'127.0.0.1',port:" + server.getLocalPort() + "});socket.setTimeout(1000);socket.on('connect',()=>{socket.destroy();process.stdout.write(JSON.stringify({status:'completed',result:{file,network:'allowed'}}))});socket.on('error',e=>process.stdout.write(JSON.stringify({status:'completed',result:{file,network:e.code}})));socket.on('timeout',()=>{socket.destroy();process.stdout.write(JSON.stringify({status:'failed',error:'network test timed out'}))});";
            JSONObject response = invokeTrustedTestRunner(runner);
            assertEquals(response.toString(), "completed", response.getString("status"));
            assertEquals("ERR_ACCESS_DENIED", response.getJSONObject("result").getString("file"));
            assertNotEquals("allowed", response.getJSONObject("result").getString("network"));
        } finally { secret.delete(); }
    }

    @Test public void rapidJobsAfterTimeoutAndCancellationNeverReuseDestroyingService() throws Exception {
        ComputeTools tools = new ComputeTools(context);
        // No sleeps between jobs: this exercises bind while the previous
        // isolated process is being destroyed, including after forced stop.
        for (int index = 0; index < 6; index++) {
            JSONObject result = tools.execute("compute_run", new JSONObject().put("code", "return input.sequence;")
                .put("input", new JSONObject().put("sequence", index)), () -> {});
            assertEquals(result.toString(), "completed", result.getString("status"));
            assertEquals(index, result.getInt("result"));
        }
        JSONObject timed = tools.execute("compute_run", new JSONObject().put("code", "while(true){}").put("timeoutMs", 100), () -> {});
        assertEquals(timed.toString(), "timed_out", timed.getString("status"));
        JSONObject afterTimeout = tools.execute("compute_run", new JSONObject().put("code", "return 'after-timeout';"), () -> {});
        assertEquals(afterTimeout.toString(), "completed", afterTimeout.getString("status"));
        assertEquals("after-timeout", afterTimeout.getString("result"));

        long began = SystemClock.elapsedRealtime();
        try {
            tools.execute("compute_run", new JSONObject().put("code", "while(true){}").put("timeoutMs", 30000), () -> {
                if (SystemClock.elapsedRealtime() - began >= 500) throw new InterruptedException("test stop");
            });
            fail("Task should stop");
        } catch (InterruptedException expected) { }
        JSONObject afterStop = tools.execute("compute_run", new JSONObject().put("code", "artifact('after-stop.txt','ready');return 'after-stop';"), () -> {});
        assertEquals(afterStop.toString(), "completed", afterStop.getString("status"));
        assertEquals("after-stop", afterStop.getString("result"));
        JSONObject artifact = tools.execute("compute_artifact", new JSONObject().put("jobId", afterStop.getString("jobId"))
            .put("name", "after-stop.txt"), () -> {});
        assertEquals("ready", artifact.getString("text"));
    }

    private JSONObject invokeTrustedTestRunner(String source) throws Exception {
        CompletableFuture<Messenger> connected = new CompletableFuture<>();
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        Messenger receiver = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            try { result.complete(new JSONObject(message.getData().getString("result"))); }
            catch (Exception error) { result.completeExceptionally(error); }
            return true;
        }));
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) { connected.complete(new Messenger(binder)); }
            private void fail(String message) {
                IOException error = new IOException(message);
                connected.completeExceptionally(error); result.completeExceptionally(error);
            }
            @Override public void onServiceDisconnected(ComponentName name) { fail("Isolation probe disconnected"); }
            @Override public void onBindingDied(ComponentName name) { fail("Isolation probe binding died"); }
            @Override public void onNullBinding(ComponentName name) { fail("Isolation probe unavailable"); }
        };
        String instance = "probe_" + UUID.randomUUID().toString().replace("-", "");
        assertTrue(context.bindIsolatedService(new Intent(context, ComputeService.class), Context.BIND_AUTO_CREATE,
            instance, context.getMainExecutor(), connection));
        try {
            Message message = Message.obtain(null, ComputeService.RUN); Bundle data = new Bundle();
            data.putString("requestId", "isolation-test"); data.putString("runner", source); data.putString("request", "{}"); data.putInt("timeoutMs", 3000);
            message.setData(data); message.replyTo = receiver; connected.get(5, TimeUnit.SECONDS).send(message);
            return result.get(10, TimeUnit.SECONDS);
        } finally { context.unbindService(connection); }
    }
}
