package io.droyagent.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Runs the actual app-UID su path, changing only independently owned fictional test channels. */
@RunWith(AndroidJUnit4.class)
public final class NotificationChannelControlDeviceTest {
    private static final String SOURCE = "io.droyagent.app.test";
    private static final String RECEIVER = SOURCE + "/io.droyagent.app.NotificationChannelControlDeviceTest$FixtureReceiver";

    @Test public void appRootSilencesAndRestoresOnlyOwnedChannelSettings() throws Exception {
        assumeTrue("Run explicitly with -e notificationRootControl true on an authorized Root device",
                "true".equals(InstrumentationRegistry.getArguments().getString("notificationRootControl")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Stop existing tasks before the Root channel fixture", RuntimeService.canManageHistory());
        assertTrue("The user's Root capability is required", ProductPermissions.allowed(ProductPermissions.preferences(context), "allowRoot"));
        SharedPreferences preferences = ProductPermissions.preferences(context);
        boolean hadEnabled = preferences.contains("notificationManagementEnabled"), enabled = preferences.getBoolean("notificationManagementEnabled", true);
        assertTrue(preferences.edit().putBoolean("notificationManagementEnabled", false).commit());
        String marker = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String id = "droy_auto_" + marker, store = "notification-channel-test-" + marker;
        Context isolated = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(name.equals("notification-channels") ? store : name, mode);
            }
        };
        NotificationChannelControl control = new NotificationChannelControl(isolated);
        Boolean originalEnabled = null;
        try {
            originalEnabled = packageEnabled(control, context);
            String posted = shell("am broadcast --receiver-foreground --include-stopped-packages -n " + RECEIVER + " --es id " + id);
            NotificationChannel original = findChannel(control, context, id);
            assertNotNull("The separate test UID did not create its channels: " + posted, original);
            assertEquals(NotificationManager.IMPORTANCE_HIGH, original.getImportance());
            assertNotNull(original.getSound()); assertTrue(original.shouldVibrate());
            setPackageEnabled(control, context, false);
            assertFalse("The source fixture must start with notifications disabled", packageEnabled(control, context));
            assertEquals("ready", control.synchronize(Map.of(SOURCE, "allow"), true).getString("state"));
            assertFalse("An untouched allow exception changed the user's disabled source", packageEnabled(control, context));
            java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
            JSONObject superseded = control.synchronize(Map.of(SOURCE, "managed"), true, () -> checks.incrementAndGet() < 2);
            assertEquals("superseded", superseded.getString("state"));
            assertTrue("The policy must be checked after the actual Root read", checks.get() >= 2);
            assertTrue("A superseded read changed the source channel", NotificationChannelShell.sameState(
                    NotificationChannelShell.state(original), NotificationChannelShell.state(channel(control, context, id))));
            assertFalse("A superseded read enabled the source", packageEnabled(control, context));
            JSONObject result = control.synchronize(Map.of(SOURCE, "managed"), true);
            assertEquals("App-UID Root could not configure the fixture: " + result, "ready", result.getString("state"));
            assertTrue(result.getInt("changed") >= 1);
            assertTrue("Managed source notifications were not enabled", packageEnabled(control, context));
            NotificationChannel quiet = channel(control, context, id);
            assertEquals(NotificationManager.IMPORTANCE_LOW, quiet.getImportance());
            assertNull(quiet.getSound()); assertFalse(quiet.shouldVibrate());
            assertEquals(NotificationManager.IMPORTANCE_NONE, channel(control, context, id + "_disabled").getImportance());

            assertEquals("ready", control.synchronize(Map.of(SOURCE, "allow"), true).getString("state"));
            NotificationChannel restored = channel(control, context, id);
            assertTrue(NotificationChannelShell.sameState(NotificationChannelShell.state(original), NotificationChannelShell.state(restored)));
            assertTrue("Explicit allow did not keep source notifications enabled", packageEnabled(control, context));
            assertEquals("ready", control.synchronize(Map.of(), false).getString("state"));
            assertFalse("Disabling management did not restore the original disabled source", packageEnabled(control, context));

            assertEquals("ready", control.synchronize(Map.of(SOURCE, "managed"), true).getString("state"));
            NotificationChannel applied = channel(control, context, id);
            NotificationChannel later = NotificationChannelShell.decode(NotificationChannelShell.encode(applied));
            later.setSound(Uri.parse("content://fictional/manual-sound"), original.getAudioAttributes());
            applyFixture(control, context, applied, later);
            assertEquals("ready", control.synchronize(Map.of(SOURCE, "allow"), true).getString("state"));
            NotificationChannel manualRestored = channel(control, context, id);
            assertEquals("A later manual choice was overwritten", later.getSound(), manualRestored.getSound());
            assertEquals("Allow left Droy's reduced importance behind", original.getImportance(), manualRestored.getImportance());
            assertEquals("Allow left Droy's vibration suppression behind", original.shouldVibrate(), manualRestored.shouldVibrate());
            setPackageEnabled(control, context, false);
            assertEquals("ready", control.synchronize(Map.of(SOURCE, "allow"), true).getString("state"));
            assertFalse("An allow exception re-enabled a source later disabled by the user", packageEnabled(control, context));
            Bundle status = new Bundle(); status.putString("stream", "\nDROY_AUTO_SILENCE_APP_UID_ROOT_VERIFIED\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
        } finally {
            try {
                control.synchronize(Map.of(), false);
                setPackageEnabled(control, context, true);
                String removed = shell("am broadcast --receiver-foreground --include-stopped-packages -n " + RECEIVER + " --es id " + id + " --ez remove true");
                assertNull("Fictional Root channel was not removed: " + removed, findChannel(control, context, id));
                assertNull("Fictional blocked channel was not removed: " + removed, findChannel(control, context, id + "_disabled"));
            } finally {
                try {
                    if (originalEnabled != null) setPackageEnabled(control, context, originalEnabled);
                } finally {
                    context.deleteSharedPreferences(store);
                    SharedPreferences.Editor restore = preferences.edit();
                    if (hadEnabled) restore.putBoolean("notificationManagementEnabled", enabled); else restore.remove("notificationManagementEnabled");
                    assertTrue(restore.commit());
                }
            }
        }
    }

    private static boolean packageEnabled(NotificationChannelControl control, Context context) throws Exception {
        JSONObject read = invoke(control, new JSONObject().put("operation", "read").put("packages", new JSONArray()
                .put(new JSONObject().put("packageName", SOURCE).put("uid", context.getPackageManager().getApplicationInfo(SOURCE, 0).uid))));
        return read.getJSONArray("packages").getJSONObject(0).getBoolean("enabled");
    }

    private static void setPackageEnabled(NotificationChannelControl control, Context context, boolean enabled) throws Exception {
        boolean current = packageEnabled(control, context);
        if (current == enabled) return;
        JSONObject receipt = invoke(control, new JSONObject().put("operation", "apply").put("changes", new JSONArray())
                .put("packageChanges", new JSONArray().put(new JSONObject().put("packageName", SOURCE)
                        .put("uid", context.getPackageManager().getApplicationInfo(SOURCE, 0).uid)
                        .put("expected", current).put("enabled", enabled))));
        assertEquals(1, receipt.getJSONArray("packagesApplied").length());
    }

    private static NotificationChannel channel(NotificationChannelControl control, Context context, String id) throws Exception {
        NotificationChannel result = findChannel(control, context, id);
        assertNotNull("The fictional channel is missing", result);
        return result;
    }

    private static NotificationChannel findChannel(NotificationChannelControl control, Context context, String id) throws Exception {
        JSONObject read = invoke(control, new JSONObject().put("operation", "read").put("packages", new JSONArray()
                .put(new JSONObject().put("packageName", SOURCE).put("uid", context.getPackageManager().getApplicationInfo(SOURCE, 0).uid))));
        JSONArray channels = read.getJSONArray("packages").getJSONObject(0).getJSONArray("channels");
        for (int i = 0; i < channels.length(); i++) if (id.equals(channels.getJSONObject(i).getString("id")))
            return NotificationChannelShell.decode(channels.getJSONObject(i).getString("parcel"));
        return null;
    }

    private static void applyFixture(NotificationChannelControl control, Context context, NotificationChannel expected, NotificationChannel desired) throws Exception {
        JSONObject receipt = invoke(control, new JSONObject().put("operation", "apply").put("changes", new JSONArray()
                .put(new JSONObject().put("packageName", SOURCE).put("uid", context.getPackageManager().getApplicationInfo(SOURCE, 0).uid)
                    .put("id", expected.getId()).put("expected", NotificationChannelShell.state(expected)).put("desired", NotificationChannelShell.encode(desired)))));
        assertEquals(1, receipt.getJSONArray("applied").length());
    }

    private static JSONObject invoke(NotificationChannelControl control, JSONObject request) throws Exception {
        Method method = NotificationChannelControl.class.getDeclaredMethod("run", JSONObject.class); method.setAccessible(true);
        return (JSONObject) method.invoke(control, request);
    }

    private static String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096]; int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            return output.toString("UTF-8");
        }
    }

    public static final class FixtureReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String id = intent.getStringExtra("id");
            if (id == null || !id.matches("droy_auto_[a-f0-9]{12}")) return;
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (intent.getBooleanExtra("remove", false)) {
                manager.deleteNotificationChannel(id); manager.deleteNotificationChannel(id + "_disabled");
                setResultData("DROY_AUTO_REMOVED"); return;
            }
            NotificationChannel channel = new NotificationChannel(id, "Fictional automatic silence fixture", NotificationManager.IMPORTANCE_HIGH);
            channel.setSound(Uri.parse("content://fictional/original-sound"), new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
            channel.enableVibration(true); channel.setVibrationPattern(new long[]{0, 200, 100, 200});
            manager.createNotificationChannel(channel);
            manager.createNotificationChannel(new NotificationChannel(id + "_disabled", "Fictional blocked fixture", NotificationManager.IMPORTANCE_NONE));
            setResultData("DROY_AUTO_CREATED");
        }
    }
}
