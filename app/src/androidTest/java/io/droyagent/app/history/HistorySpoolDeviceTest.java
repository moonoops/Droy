package io.droyagent.app.history;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class HistorySpoolDeviceTest {
    @Test public void continuityFenceSurvivesRestartAndSeparatesBothSidesWithinOneBatch() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();File directory=new File(context.getCacheDir(),"history-continuity-"+UUID.randomUUID());
        String initial,paused,batchId;
        try {
            try(HistorySpool spool=new HistorySpool(directory,1024*1024)){
                initial=spool.readBatch(10,65536,1000).getString("continuityId");
                spool.append(event(UUID.randomUUID().toString(),100000),false);spool.noteGap("user_paused");
                spool.append(event(UUID.randomUUID().toString(),100000),false);
                JSONObject batch=spool.readBatch(10,65536,2000);batchId=batch.getString("batchId");
                assertEquals(initial,batch.getJSONArray("items").getJSONObject(0).getString("continuityId"));
                paused=batch.getJSONArray("items").getJSONObject(1).getString("continuityId");assertNotEquals(initial,paused);
                spool.noteGap("user_disabled");
            }
            try(HistorySpool spool=new HistorySpool(directory,1024*1024)){
                JSONObject replay=spool.readBatch(10,65536,2000);assertEquals(batchId,replay.getString("batchId"));
                assertEquals(initial,replay.getJSONArray("items").getJSONObject(0).getString("continuityId"));
                assertEquals(paused,replay.getJSONArray("items").getJSONObject(1).getString("continuityId"));
                spool.acknowledge(batchId);String stopped=spool.readBatch(10,65536,2000).getString("continuityId");assertNotEquals(paused,stopped);
                assertEquals(stopped,spool.readBatch(10,65536,2000).getString("continuityId"));
            }
        }finally{remove(directory);}
    }
    @Test public void expirationBreaksContinuityAcrossSurvivingBacklogAndInvalidatesOldBatch() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();File directory=new File(context.getCacheDir(),"history-expiry-fence-"+UUID.randomUUID());
        try(HistorySpool spool=new HistorySpool(directory,1024*1024)){
            spool.append(event(UUID.randomUUID().toString(),100000),false);spool.append(event(UUID.randomUUID().toString(),5000),false);spool.append(event(UUID.randomUUID().toString(),100000),false);
            JSONObject before=spool.readBatch(10,65536,2000);String old=before.getJSONArray("items").getJSONObject(0).getString("continuityId");
            assertEquals(1,spool.expire(6000));JSONObject after=spool.readBatch(10,65536,6000);
            assertNotEquals(before.getString("batchId"),after.getString("batchId"));assertEquals(2,after.getJSONArray("items").length());
            String first=after.getJSONArray("items").getJSONObject(0).getString("continuityId"),second=after.getJSONArray("items").getJSONObject(1).getString("continuityId");
            assertNotEquals(old,first);assertNotEquals(first,second);
            assertEquals(after.toString(),spool.readBatch(10,65536,6000).toString());
        }finally{remove(directory);}
    }
    private static JSONObject event(String id, long expires) throws Exception {
        return new JSONObject().put("eventId", id).put("sourceType", "android_activity")
            .put("occurredAtMs", 1000).put("recordedAtMs", 1000).put("expiresAtMs", expires)
            .put("actor", new JSONObject().put("kind", "unknown"))
            .put("payload", new JSONObject().put("packageName", "example.fixture").put("transition", "foreground_candidate"));
    }
    @Test public void pendingBatchSurvivesRestartAndAckIsIdempotent() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(), "history-spool-test-" + UUID.randomUUID());
        try {
            String firstId = UUID.randomUUID().toString(), batch;
            try (HistorySpool spool = new HistorySpool(directory, 1024 * 1024)) {
                JSONObject first = event(firstId, 100000);
                assertTrue(spool.append(first, false)); assertTrue(spool.append(first, false));
                assertEquals(1, spool.status().getInt("queued"));
                batch = spool.readBatch(10, 65536, 2000).getString("batchId");
                assertTrue(spool.append(event(UUID.randomUUID().toString(), 100000), false));
            }
            try (HistorySpool spool = new HistorySpool(directory, 1024 * 1024)) {
                JSONObject pending = spool.readBatch(10, 65536, 2000);
                assertEquals(batch, pending.getString("batchId")); assertEquals(1, pending.getJSONArray("items").length());
                assertEquals(firstId, pending.getJSONArray("items").getJSONObject(0).getString("eventId"));
                assertEquals(1, spool.acknowledge(batch).getInt("removed"));
                assertTrue(spool.acknowledge(batch).getBoolean("duplicate"));
                assertEquals(1, spool.status().getInt("queued"));
                try { spool.acknowledge(UUID.randomUUID().toString()); fail(); } catch (IllegalArgumentException expected) { assertEquals("HISTORY_UNKNOWN_BATCH", expected.getMessage()); }
            }
        } finally { remove(directory); }
    }
    @Test public void expiredUnacknowledgedEventsAreExplicitlyCounted() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(), "history-spool-test-" + UUID.randomUUID());
        try (HistorySpool spool = new HistorySpool(directory, 1024 * 1024)) {
            spool.append(event(UUID.randomUUID().toString(), 5000), false);
            String old = spool.readBatch(10, 65536, 2000).getString("batchId");
            assertEquals(1, spool.expire(6000));
            assertEquals(0, spool.status().getInt("queued"));
            assertEquals(0, spool.readBatch(10, 65536, 6000).getJSONArray("items").length());
            assertEquals("retention_expired", spool.status().getString("lastGap"));
            assertEquals(1, spool.status().getInt("dropped"));
            try { spool.acknowledge(old); fail(); } catch (IllegalArgumentException expected) { assertEquals("HISTORY_UNKNOWN_BATCH", expected.getMessage()); }
        } finally { remove(directory); }
    }
    @Test public void futureFormatIsRejectedWithoutReinitialization() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(), "history-spool-test-" + UUID.randomUUID());
        try {
            try (HistorySpool ignored = new HistorySpool(directory, 1024 * 1024)) {}
            android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(new File(directory, "queue.db").getPath(), null, 0);
            db.execSQL("PRAGMA user_version=99"); db.close();
            try (HistorySpool ignored = new HistorySpool(directory, 1024 * 1024)) { fail(); }
            catch (IllegalStateException expected) { assertEquals("HISTORY_SPOOL_FORMAT", expected.getMessage()); }
        } finally { remove(directory); }
    }
    @Test public void capacityStopsAcceptingWithoutEvictingUnacknowledgedEvents() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = new File(context.getCacheDir(), "history-spool-test-" + UUID.randomUUID());
        try (HistorySpool spool = new HistorySpool(directory, 1024 * 1024)) {
            int accepted = 0; boolean limited = false;
            for (int i = 0; i < 200; i++) {
                JSONObject value = event(UUID.randomUUID().toString(), 100000);
                value.getJSONObject("payload").put("text", "x".repeat(8192));
                try {
                    if (!spool.append(value, false)) { limited = true; break; }
                    accepted++;
                } catch (IllegalStateException full) { assertEquals("HISTORY_SPOOL_FULL", full.getMessage()); limited = true; break; }
            }
            assertTrue(limited); assertTrue(accepted > 0); assertEquals(accepted, spool.status().getInt("queued"));
            assertTrue(new File(directory, "queue.db").length() <= 512 * 1024);
        } finally { remove(directory); }
    }
    @Test public void unavailableExecutionPrivacyCannotEnableOrReadCapture() throws Exception {
        assertTrue("Use a dedicated emulator for lifecycle tests", android.os.Build.FINGERPRINT.contains("generic")
            || android.os.Build.MODEL.contains("sdk_gphone"));
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "history-policy-test-" + UUID.randomUUID();
        File directory = new File(base.getCacheDir(), name);
        Context isolated = new ContextWrapper(base) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return directory; }
            @Override public SharedPreferences getSharedPreferences(String ignored, int mode) { return base.getSharedPreferences(name, mode); }
        };
        try (HistoryCapture capture = new HistoryCapture(isolated, () -> false)) {
            capture.start();
            try { capture.configure(new JSONObject().put("enabled", true).put("locationEnabled", true)); fail(); }
            catch (IllegalStateException expected) { assertEquals("HISTORY_EXECUTION_NOT_READY", expected.getMessage()); }
            assertFalse(capture.configuration().getBoolean("enabled"));
            capture.configure(new JSONObject().put("enabled", false).put("paused", true));
            assertFalse(capture.status().getBoolean("executionPrivacyReady"));
            try { capture.readBatch(10, 65536).get(); fail(); }
            catch (java.util.concurrent.ExecutionException expected) { assertEquals("HISTORY_EXECUTION_NOT_READY", expected.getCause().getMessage()); }
            assertFalse(new File(directory, "droy-history-spool/queue.db").exists());
        } finally { base.deleteSharedPreferences(name); remove(directory); }
    }
    @Test public void pageProjectionSuppressesInputsAndUnknownWebOrigins() throws Exception {
        android.view.accessibility.AccessibilityNodeInfo node = android.view.accessibility.AccessibilityNodeInfo.obtain();
        try {
            node.setVisibleToUser(true); node.setText("Fictional visible text");
            assertEquals("Fictional visible text", PageProjection.project(node).getString("text"));
            node.setText("x".repeat(HistoryPolicy.PAGE_BYTES + 1));
            assertTrue(PageProjection.project(node).getBoolean("truncated"));
            assertEquals(HistoryPolicy.PAGE_BYTES, PageProjection.project(node).getString("text").length());
            node.setEditable(true);
            assertEquals("input_present", PageProjection.project(node).getString("suppressed"));
            assertEquals("", PageProjection.project(node).getString("text"));
            node.setEditable(false); node.setPassword(true);
            assertEquals("", PageProjection.project(node).getString("text"));
            node.setPassword(false); node.setClassName("android.webkit.WebView");
            assertEquals("web_origin_unknown", PageProjection.project(node).getString("suppressed"));
        } finally { node.recycle(); }
    }
    private static void remove(File root) throws Exception {
        if (!root.exists()) return;
        try (var paths = Files.walk(root.toPath())) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
}
