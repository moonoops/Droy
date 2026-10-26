package io.droyagent.app.history;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Handler;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Isolated storage and consent, never the production singleton or its preferences. */
final class HistoryCaptureFixture implements AutoCloseable {
    final Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
    final String name = "history-callback-fixture-" + UUID.randomUUID();
    final File directory = new File(base.getCacheDir(),name);
    final AtomicBoolean ready = new AtomicBoolean(true), locationPermission = new AtomicBoolean(false);
    final HistoryCapture capture;
    final ThreadPoolExecutor writer;
    final Context isolated;
    private final boolean systemLocation;

    HistoryCaptureFixture(boolean systemLocation) throws Exception {
        this.systemLocation=systemLocation;
        assertTrue(directory.mkdirs());
        isolated=new ContextWrapper(base) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return directory; }
            @Override public SharedPreferences getSharedPreferences(String ignored,int mode) { return base.getSharedPreferences(name,mode); }
            @Override public Object getSystemService(String service) {
                // Source callbacks must not accidentally subscribe to the user's
                // device network, broadcasts or location while testing a queue.
                if (CONNECTIVITY_SERVICE.equals(service)||BATTERY_SERVICE.equals(service)) return null;
                if (LOCATION_SERVICE.equals(service)&&!systemLocation) return null;
                return super.getSystemService(service);
            }
            @Override public int checkSelfPermission(String permission) {
                if(systemLocation)return super.checkSelfPermission(permission);
                if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permission)||Manifest.permission.ACCESS_COARSE_LOCATION.equals(permission)
                    ||Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(permission))
                    return locationPermission.get()?PackageManager.PERMISSION_GRANTED:PackageManager.PERMISSION_DENIED;
                return super.checkSelfPermission(permission);
            }
            @Override public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter) { return null; }
            @Override public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter,int flags) { return null; }
            @Override public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter,String permission,Handler scheduler) { return null; }
            @Override public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter,String permission,Handler scheduler,int flags) { return null; }
            @Override public void unregisterReceiver(BroadcastReceiver receiver) { }
            @Override public ComponentName startForegroundService(Intent intent) { return intent.getComponent(); }
            @Override public boolean stopService(Intent intent) { return true; }
        };
        capture=new HistoryCapture(isolated,ready::get);
        writer=(ThreadPoolExecutor)field(capture,"writer");
        capture.start(); settle();
    }
    static Object field(Object object,String name) throws Exception {
        Field field=object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    void settle() throws Exception {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        writer.submit(()->{}).get(5,TimeUnit.SECONDS);
    }
    JSONObject batch() throws Exception { return capture.readBatch(128,196608).get(5,TimeUnit.SECONDS); }
    void drain() throws Exception {
        JSONObject batch=batch(); if(batch.getJSONArray("items").length()>0) capture.acknowledge(batch.getString("batchId")).get(5,TimeUnit.SECONDS);
    }
    @Override public void close() throws Exception {
        capture.close();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue("Fixture writer must stop before removing its database",writer.awaitTermination(5,TimeUnit.SECONDS));
        base.deleteSharedPreferences(name);
        try(var paths=Files.walk(directory.toPath())) { for(var path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
}
