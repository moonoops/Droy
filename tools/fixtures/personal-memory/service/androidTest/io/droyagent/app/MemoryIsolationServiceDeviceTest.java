package io.droyagent.app;

import android.content.*;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class MemoryIsolationServiceDeviceTest {
    @Test public void normalAppServiceRunsReadBrokerMechanism() throws Exception {
        JSONObject report = runProbe("broker", "service-report.json");
        assertEquals(report.toString(), 0, report.getInt("exitCode"));
        JSONObject summary = findObservation(report, "summary");
        assertNotNull(report.toString(), summary);
        assertEquals(summary.toString(), 1, summary.getInt("passed"));
        assertEquals(summary.toString(), 2, summary.getInt("allowed"));
        assertEquals(summary.toString(), 4, summary.getInt("denied"));
        assertFalse(summary.toString(), summary.getBoolean("continue_used"));
    }

    @Test public void normalAppServiceSupportsRequiredPrimitives() throws Exception {
        JSONObject report = runProbe("primitives", "service-primitives-report.json");
        assertEquals(report.toString(), 0, report.getInt("exitCode"));
        JSONObject summary = findObservation(report, "primitives_summary");
        assertNotNull(report.toString(), summary);
        assertEquals(summary.toString(), 4, summary.getInt("passed"));
        assertEquals(summary.toString(), 4, summary.getInt("total"));
    }

    @Test public void normalAppServiceRunsFixedDirectoryObjectReadProbe() throws Exception {
        JSONObject report = runProbe("fixed-root", "service-fixed-root-report.json");
        assertEquals(report.toString(), 0, report.getInt("exitCode"));
        JSONObject contract = findObservation(report, "fixed_root_contract");
        assertNotNull(report.toString(), contract);
        assertEquals("authorized_directory_objects_no_private_import", contract.getString("contract"));
        assertFalse(contract.getBoolean("current_path_beneath"));
        JSONObject summary = findObservation(report, "fixed_root_summary");
        assertNotNull(report.toString(), summary);
        assertEquals(summary.toString(), 1, summary.getInt("passed"));
        assertTrue(summary.toString(), summary.getInt("allowed") >= 3);
        assertTrue(summary.toString(), summary.getInt("denied") >= 13);
        assertEquals(summary.toString(), 271, summary.getInt("copied_paths"));
        assertTrue(summary.toString(), summary.getLong("concurrent_swaps") > 0);
        assertTrue(summary.toString(), summary.getLong("race_swaps_last") > summary.getLong("race_swaps_first"));
        assertEquals(summary.toString(), 256, summary.getInt("race_allowed") + summary.getInt("race_denied"));
        assertFalse(summary.getBoolean("continue_used"));
        assertFalse(summary.getBoolean("openat2_used"));
        JSONObject worker = findObservation(report, "fixed_root_worker");
        assertNotNull(report.toString(), worker);
        assertEquals(worker.toString(), 14, worker.getInt("static_cases"));
        assertEquals(worker.toString(), 9, worker.getInt("mutation_denials"));
        assertEquals(worker.toString(), 256, worker.getInt("race_reads"));
    }

    private JSONObject runProbe(String probe, String evidenceName) throws Exception {
        assertTrue("Only a dedicated emulator may run this probe", Build.FINGERPRINT.contains("generic")
            || Build.MODEL.contains("sdk_gphone"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CompletableFuture<Messenger> connected = new CompletableFuture<>();
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        Messenger receiver = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            if (message.what != 2) return true;
            try { result.complete(new JSONObject(message.getData().getString("result"))); }
            catch (Exception error) { result.completeExceptionally(error); }
            return true;
        }));
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) { connected.complete(new Messenger(binder)); }
            @Override public void onServiceDisconnected(ComponentName name) { result.completeExceptionally(new IOException("Probe service disconnected")); }
            @Override public void onBindingDied(ComponentName name) { result.completeExceptionally(new IOException("Probe binding died")); }
            @Override public void onNullBinding(ComponentName name) { connected.completeExceptionally(new IOException("Probe binding unavailable")); }
        };
        Intent intent = new Intent().setClassName(context, "io.droyagent.app.MemoryIsolationProbeService");
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE));
        JSONObject report;
        try {
            Message request = Message.obtain(null, 1); request.replyTo = receiver;
            Bundle arguments = new Bundle(); arguments.putString("probe", probe); request.setData(arguments);
            connected.get(10, TimeUnit.SECONDS).send(request);
            report = result.get(40, TimeUnit.SECONDS);
        } finally { context.unbindService(connection); }
        File evidence = new File(context.getExternalFilesDir(null), "memory-service-probe-evidence");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        try (OutputStream output = new FileOutputStream(new File(evidence, evidenceName))) {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        assertFalse(report.toString(), report.has("error"));
        assertEquals(report.toString(), context.getApplicationInfo().uid, report.getInt("serviceUid"));
        assertNotEquals(report.toString(), android.os.Process.myPid(), report.getInt("servicePid"));
        assertTrue(report.toString(), report.getString("serviceDomain").contains("untrusted_app"));
        assertTrue(report.toString(), report.getBoolean("completed"));
        assertFalse(report.toString(), report.getBoolean("overflow"));
        assertTrue(report.toString(), report.getBoolean("temporaryDirectoryRemoved"));
        return report;
    }

    private JSONObject findObservation(JSONObject report, String probe) throws JSONException {
        JSONArray observations = report.getJSONArray("observations");
        for (int index = 0; index < observations.length(); index++) {
            JSONObject row = observations.getJSONObject(index);
            if (probe.equals(row.optString("probe"))) return row;
        }
        return null;
    }
}
