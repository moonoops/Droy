package io.droyagent.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TestRule;
import org.junit.runners.model.Statement;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Runs the actual app-UID su path, changing only independently owned fictional test channels. */
@RunWith(AndroidJUnit4.class)
public final class NotificationChannelControlDeviceTest {
    private static final String SOURCE = InstrumentationRegistry.getInstrumentation().getContext().getPackageName();
    private static final String RECEIVER = SOURCE + "/io.droyagent.app.NotificationChannelControlDeviceTest$FixtureReceiver";
    private static final String AUTHORIZATION_CHECKPOINT = "notification-root-authorization-recovery.json";
    private static final ThreadLocal<FixtureForeground> FOREGROUND = new ThreadLocal<>();

    @Rule public final TestRule temporaryAuthorization = (base, description) -> new Statement() {
        @Override public void evaluate() throws Throwable {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            File checkpoint = authorizationCheckpoint(context);
            assertFalse("Recover the prior temporary Root authorization before running another fixture", checkpointExists(checkpoint));
            if (!"true".equals(InstrumentationRegistry.getArguments().getString("notificationRootTemporaryAuthorization"))) {
                if (!"true".equals(InstrumentationRegistry.getArguments().getString("notificationRootControl"))) { base.evaluate(); return; }
                requireRootFixtureSurface(context);
                try (FixtureForeground ignored = new FixtureForeground()) { base.evaluate(); }
                return;
            }
            requireTemporaryAuthorization(context, "notificationRootTemporaryAuthorization");
            assertNoPriorChannelFixture(context); assertNoUserChannelRestoration(context);
            JSONObject recovery = new JSONObject().put("version", 1)
                    .put("product", preferencesSnapshot(context.getSharedPreferences("product", 0)))
                    .put("installation", preferencesSnapshot(context.getSharedPreferences("installation", 0)));
            saveAuthorizationCheckpoint(checkpoint, recovery);
            try (FixtureForeground ignored = new FixtureForeground()) {
                Throwable failure = null;
                try {
                    SharedPreferences product = context.getSharedPreferences("product", 0);
                    assertTrue(product.edit().putBoolean("notificationManagementEnabled", false).putBoolean(ProductPermissions.STOPPED, true).commit());
                    SetupManager.update(context, new JSONObject().put("capabilities", new JSONObject().put("allowRoot", true)));
                    assertTrue(product.edit().putBoolean(ProductPermissions.STOPPED, true).commit());
                    recovery.put("sourceEnabled", packageEnabled(new NotificationChannelControl(context), context));
                    saveAuthorizationCheckpoint(checkpoint, recovery);
                    base.evaluate();
                } catch (Throwable error) { failure = error; throw error; }
                finally {
                    try {
                        restoreTemporaryAuthorization(context, recovery);
                        recovery.put("restored", true); saveAuthorizationCheckpoint(checkpoint, recovery);
                        reportAuthorizationRestored();
                    } catch (Throwable cleanup) {
                        if (failure != null) failure.addSuppressed(cleanup); else throw cleanup;
                    }
                }
            }
            new android.util.AtomicFile(checkpoint).delete(); assertFalse(checkpointExists(checkpoint));
        }
    };

    @RunWith(AndroidJUnit4.class)
    public static final class AuthorizationRecovery {
        @Test public void restoreInterruptedAuthorization() throws Exception {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            requireTemporaryAuthorization(context, "notificationRootRestoreAuthorization");
            File checkpoint = authorizationCheckpoint(context);
            assertTrue("No temporary Root authorization checkpoint exists", checkpointExists(checkpoint));
            android.util.AtomicFile atomic = new android.util.AtomicFile(checkpoint);
            JSONObject recovery;
            try (InputStream input = atomic.openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[4096]; int count;
                while ((count = input.read(bytes)) != -1) { assertTrue("Authorization checkpoint is too large", output.size() + count <= 2 * 1024 * 1024); output.write(bytes, 0, count); }
                recovery = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            }
            assertEquals(1, recovery.getInt("version"));
            try (FixtureForeground ignored = new FixtureForeground()) {
                restoreTemporaryAuthorization(context, recovery);
                recovery.put("restored", true); saveAuthorizationCheckpoint(checkpoint, recovery);
                reportAuthorizationRestored();
            }
            atomic.delete(); assertFalse(checkpointExists(checkpoint));
        }
    }

    private static void requireTemporaryAuthorization(Context context, String flag) {
        assumeTrue("Explicit notificationRootControl approval is required",
                "true".equals(InstrumentationRegistry.getArguments().getString("notificationRootControl")));
        assumeTrue("Explicit temporary Root authorization or recovery approval is required",
                "true".equals(InstrumentationRegistry.getArguments().getString(flag)));
        requireRootFixtureSurface(context);
    }

    private static void requireRootFixtureSurface(Context context) {
        assertEquals("Root fixtures are development-only", DroyPackages.DEVELOPMENT, context.getPackageName());
        assertTrue("Stop existing tasks before the Root fixture", RuntimeService.canManageHistory());
    }

    private static File authorizationCheckpoint(Context context) { return new File(context.getFilesDir(), AUTHORIZATION_CHECKPOINT); }
    private static boolean checkpointExists(File file) { return file.exists() || new File(file + ".bak").exists() || new File(file + ".new").exists(); }

    private static void saveAuthorizationCheckpoint(File file, JSONObject value) throws Exception {
        android.util.AtomicFile atomic = new android.util.AtomicFile(file); FileOutputStream output = atomic.startWrite();
        try { output.write(value.toString().getBytes(StandardCharsets.UTF_8)); atomic.finishWrite(output); }
        catch (Exception failure) { atomic.failWrite(output); throw failure; }
    }

    private static JSONArray preferencesSnapshot(SharedPreferences preferences) throws Exception {
        JSONArray result = new JSONArray();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            Object value = entry.getValue(); String type;
            if (value instanceof Boolean) type = "boolean";
            else if (value instanceof Integer) type = "int";
            else if (value instanceof Long) type = "long";
            else if (value instanceof Float) type = "float";
            else if (value instanceof String) type = "string";
            else if (value instanceof java.util.Set<?> values) { type = "set"; value = new JSONArray(values); }
            else throw new IllegalStateException("Unsupported preference type in Root authorization checkpoint");
            result.put(new JSONObject().put("key", entry.getKey()).put("type", type).put("value", value));
        }
        return result;
    }

    private static void restorePreferenceSnapshot(SharedPreferences preferences, JSONArray snapshot) throws Exception {
        SharedPreferences.Editor editor = preferences.edit().clear(); Map<String, Object> expected = new HashMap<>();
        for (int i = 0; i < snapshot.length(); i++) {
            JSONObject entry = snapshot.getJSONObject(i); String key = entry.getString("key"); Object value;
            switch (entry.getString("type")) {
                case "boolean" -> { boolean item = entry.getBoolean("value"); editor.putBoolean(key, item); value = item; }
                case "int" -> { int item = entry.getInt("value"); editor.putInt(key, item); value = item; }
                case "long" -> { long item = entry.getLong("value"); editor.putLong(key, item); value = item; }
                case "float" -> { float item = (float) entry.getDouble("value"); editor.putFloat(key, item); value = item; }
                case "string" -> { String item = entry.getString("value"); editor.putString(key, item); value = item; }
                case "set" -> { java.util.Set<String> items = new HashSet<>(); JSONArray values = entry.getJSONArray("value");
                    for (int j = 0; j < values.length(); j++) items.add(values.getString(j)); editor.putStringSet(key, items); value = items; }
                default -> throw new IllegalStateException("Invalid preference type in Root authorization checkpoint");
            }
            assertFalse("Duplicate preference in authorization checkpoint", expected.containsKey(key)); expected.put(key, value);
        }
        assertTrue("Could not restore temporary authorization preferences", editor.commit());
        assertTrue("Temporary authorization preferences did not restore exactly", expected.equals(preferences.getAll()));
    }

    private static void restoreTemporaryAuthorization(Context context, JSONObject recovery) throws Exception {
        assertNoUserChannelRestoration(context);
        java.util.Set<String> fixtures = channelFixtureStores(context);
        if (!fixtures.isEmpty() || recovery.has("sourceEnabled") && !recovery.optBoolean("restored")) {
            SharedPreferences product = context.getSharedPreferences("product", 0);
            assertTrue(product.edit().putBoolean("notificationManagementEnabled", false).commit());
            if (!ProductPermissions.allowed(product, "allowRoot"))
                SetupManager.update(context, new JSONObject().put("capabilities", new JSONObject().put("allowRoot", true)));
            for (String store : fixtures) {
                String journalStore = store.startsWith("notification-revocation-test-") ? store + "-notification-channels" : store;
                SharedPreferences journal = context.getSharedPreferences(journalStore, 0);
                JSONObject originals = new JSONObject(journal.getString("originals", "{}")), packages = new JSONObject(journal.getString("packages", "{}"));
                for (java.util.Iterator<String> keys = originals.keys(); keys.hasNext();)
                    assertEquals("Recovery may only restore the independent test APK", SOURCE, originals.getJSONObject(keys.next()).getString("packageName"));
                for (java.util.Iterator<String> keys = packages.keys(); keys.hasNext();)
                    assertEquals("Recovery may only restore the independent test APK", SOURCE, keys.next());
                Context isolated = new ContextWrapper(context) {
                    @Override public Context getApplicationContext() { return this; }
                    @Override public SharedPreferences getSharedPreferences(String name, int mode) { return super.getSharedPreferences(name.equals("notification-channels") ? journalStore : name, mode); }
                };
                NotificationChannelControl control = new NotificationChannelControl(isolated);
                assertEquals("Interrupted Root fixture settings did not restore", 0, control.synchronize(Map.of(), false).getInt("pendingRestoreCount"));
                assertFalse(control.hasOwnedSettings());
                String suffix = store.substring(store.length() - 12), id = "droy_auto_" + suffix;
                fixtureChannels(id, true);
                assertNull(findChannel(control, context, id)); assertNull(findChannel(control, context, id + "_disabled"));
                if (store.startsWith("notification-revocation-test-")) {
                    for (String name : RootRevocationFixture.STORE_NAMES) context.deleteSharedPreferences(store + "-" + name);
                } else context.deleteSharedPreferences(store);
            }
            if (recovery.has("sourceEnabled")) setPackageEnabled(new NotificationChannelControl(context), context, recovery.getBoolean("sourceEnabled"));
        }
        restorePreferenceSnapshot(context.getSharedPreferences("installation", 0), recovery.getJSONArray("installation"));
        restorePreferenceSnapshot(context.getSharedPreferences("product", 0), recovery.getJSONArray("product"));
        SharedPreferences product = context.getSharedPreferences("product", 0);
        NotificationTestState.reloadNotificationMemory(context, product.contains("notificationManagementData") ? product.getString("notificationManagementData", null) : null);
        RuntimeService.memoryPermissionsChanged(context);
        assertNoPriorChannelFixture(context); assertNoUserChannelRestoration(context);
    }

    private static java.util.Set<String> channelFixtureStores(Context context) {
        java.util.Set<String> result = new HashSet<>(); File[] files = new File(context.getDataDir(), "shared_prefs").listFiles();
        if (files != null) for (File file : files) {
            String name = file.getName();
            if (name.matches("notification-channel-test-[a-f0-9]{12}\\.xml(?:\\.bak)?")) result.add(name.substring(0, "notification-channel-test-".length() + 12));
            if (name.matches("notification-revocation-test-[a-f0-9]{12}-(?:product|installation|notification-channels)\\.xml(?:\\.bak)?"))
                result.add(name.substring(0, "notification-revocation-test-".length() + 12));
        }
        return result;
    }

    private static void assertNoPriorChannelFixture(Context context) { assertTrue("Recover existing Root channel fixtures before starting another", channelFixtureStores(context).isEmpty()); }
    private static void reportAuthorizationRestored() {
        Bundle status = new Bundle(); status.putString("stream", "\nDROY_TEMPORARY_ROOT_AUTHORIZATION_RESTORED\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
    }

    @Test public void appRootSilencesAndRestoresOnlyOwnedChannelSettings() throws Exception {
        assumeTrue("Run explicitly with -e notificationRootControl true on an authorized Root device",
                "true".equals(InstrumentationRegistry.getArguments().getString("notificationRootControl")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNoPriorRevocationFixture(context);
        assertNoUserChannelRestoration(context);
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
            String posted = fixtureChannels(id, false);
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
            assertTrue("The policy must be rechecked before applying a snapshot", checks.get() >= 2);
            assertTrue("A superseded read changed the source channel", NotificationChannelShell.sameState(
                    NotificationChannelShell.state(original), NotificationChannelShell.state(channel(control, context, id))));
            assertFalse("A superseded read enabled the source", packageEnabled(control, context));
            JSONObject result = control.synchronize(Map.of(SOURCE, "managed"), true);
            assertEquals("App-UID Root could not configure the fixture: " + result, "ready", result.getString("state"));
            assertEquals("A disabled source must not be modified", 0, result.getInt("changed"));
            assertFalse("Management re-enabled a source disabled by the user", packageEnabled(control, context));
            assertFalse("An untouched disabled source created restoration work", control.hasOwnedSettings());
            assertTrue("Management silenced channels in a disabled source", NotificationChannelShell.sameState(
                    NotificationChannelShell.state(original), NotificationChannelShell.state(channel(control, context, id))));

            setPackageEnabled(control, context, true);
            result = control.synchronize(Map.of(SOURCE, "managed"), true);
            assertEquals("ready", result.getString("state"));
            assertTrue(result.getInt("changed") >= 1);
            assertTrue("A source already enabled must stay enabled", packageEnabled(control, context));
            assertTrue(control.hasOwnedSettings());
            assertEquals("Active takeover is not a pending restoration", 0, result.getInt("pendingRestoreCount"));
            NotificationChannel quiet = channel(control, context, id);
            assertEquals(NotificationManager.IMPORTANCE_LOW, quiet.getImportance());
            assertNull(quiet.getSound()); assertFalse(quiet.shouldVibrate());
            assertEquals(NotificationManager.IMPORTANCE_NONE, channel(control, context, id + "_disabled").getImportance());

            assertEquals("ready", control.synchronize(Map.of(SOURCE, "allow"), true).getString("state"));
            NotificationChannel restored = channel(control, context, id);
            assertTrue(NotificationChannelShell.sameState(NotificationChannelShell.state(original), NotificationChannelShell.state(restored)));
            assertTrue("Explicit allow did not keep source notifications enabled", packageEnabled(control, context));
            assertFalse("Allow left owned channel changes behind", control.hasOwnedSettings());

            int sourceUid = context.getPackageManager().getApplicationInfo(SOURCE, 0).uid;
            assertTrue(isolated.getSharedPreferences("notification-channels", Context.MODE_PRIVATE).edit()
                    .putString("packages", new JSONObject().put(SOURCE, new JSONObject().put("uid", sourceUid)
                            .put("original", false).put("applied", true)).toString()).commit());
            assertEquals("ready", control.synchronize(Map.of(SOURCE, "allow"), true).getString("state"));
            assertFalse("Allow did not restore the disabled state saved by an older build", packageEnabled(control, context));
            assertFalse("The legacy package restoration was not consumed", control.hasOwnedSettings());

            setPackageEnabled(control, context, true);
            assertTrue(isolated.getSharedPreferences("notification-channels", Context.MODE_PRIVATE).edit()
                    .putString("packages", new JSONObject().put(SOURCE, new JSONObject().put("uid", sourceUid)
                            .put("original", false).put("applied", true)).toString()).commit());
            JSONObject stillManaged = control.synchronize(Map.of(SOURCE, "managed"), true);
            assertEquals("ready", stillManaged.getString("state"));
            assertFalse("A still-selected source retained the legacy forced enable", packageEnabled(control, context));
            assertEquals(0, new JSONObject(isolated.getSharedPreferences("notification-channels", Context.MODE_PRIVATE)
                    .getString("packages", "{}")).length());
            assertEquals("ready", control.synchronize(Map.of(SOURCE, "allow"), true).getString("state"));

            setPackageEnabled(control, context, true);
            assertEquals("ready", control.synchronize(Map.of(SOURCE, "managed"), true).getString("state"));
            NotificationChannel applied = channel(control, context, id);
            NotificationChannel later = NotificationChannelShell.decode(NotificationChannelShell.encode(applied));
            later.setSound(Uri.parse("content://fictional/manual-sound"), original.getAudioAttributes());
            applyFixture(control, context, applied, later);
            assertEquals("ready", control.synchronize(Map.of(SOURCE, "managed"), true).getString("state"));
            assertEquals("ready", control.synchronize(Map.of(SOURCE, "allow"), true).getString("state"));
            NotificationChannel manualRestored = channel(control, context, id);
            assertEquals("A later manual choice was overwritten", later.getSound(), manualRestored.getSound());
            assertEquals("Allow left Droy's reduced importance behind", original.getImportance(), manualRestored.getImportance());
            assertEquals("Allow left Droy's vibration suppression behind", original.shouldVibrate(), manualRestored.shouldVibrate());
            assertEquals("ready", control.synchronize(Map.of(SOURCE, "managed"), true).getString("state"));
            try {
                assertTrue(preferences.edit().putBoolean("allowRoot", false).commit());
                JSONObject pending = control.synchronize(Map.of(), false);
                assertEquals("root_required", pending.getString("state"));
                assertTrue("A failed restoration lost its pending settings", pending.getInt("pendingRestoreCount") > 0);
                assertTrue("Revocation discarded the restoration journal", control.hasOwnedSettings());
            } finally { assertTrue(preferences.edit().putBoolean("allowRoot", true).commit()); }
            assertEquals("ready", control.synchronize(Map.of(), false).getString("state"));
            assertEquals(0, control.status().getInt("pendingRestoreCount"));
            assertFalse(control.hasOwnedSettings());
            assertEquals("ready", control.synchronize(Map.of(SOURCE, "managed"), true).getString("state"));
            NotificationChannel mutedAgain = channel(control, context, id);
            NotificationChannel manuallyBlocked = NotificationChannelShell.decode(NotificationChannelShell.encode(mutedAgain));
            manuallyBlocked.setImportance(NotificationManager.IMPORTANCE_NONE);
            applyFixture(control, context, mutedAgain, manuallyBlocked);
            assertEquals("ready", control.synchronize(Map.of(SOURCE, "managed"), true).getString("state"));
            assertTrue("A later block lost the journal for other owned fields", control.hasOwnedSettings());
            assertEquals("ready", control.synchronize(Map.of(SOURCE, "allow"), true).getString("state"));
            NotificationChannel blockedRestored = channel(control, context, id);
            assertEquals("Releasing takeover re-enabled a channel blocked later", NotificationManager.IMPORTANCE_NONE, blockedRestored.getImportance());
            assertEquals(manualRestored.getSound(), blockedRestored.getSound());
            assertEquals(manualRestored.shouldVibrate(), blockedRestored.shouldVibrate());
            assertFalse(control.hasOwnedSettings());
            setPackageEnabled(control, context, false);
            assertEquals("ready", control.synchronize(Map.of(SOURCE, "allow"), true).getString("state"));
            assertFalse("An allow exception re-enabled a source later disabled by the user", packageEnabled(control, context));
            Bundle status = new Bundle(); status.putString("stream", "\nDROY_AUTO_SILENCE_APP_UID_ROOT_VERIFIED\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
        } finally {
            try {
                control.synchronize(Map.of(), false);
                setPackageEnabled(control, context, true);
                String removed = fixtureChannels(id, true);
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

    @Test public void setupRevocationRestoresTheFixtureBeforeWithdrawingRoot() throws Exception {
        try (RootRevocationFixture fixture = new RootRevocationFixture()) {
            NotificationChannel original = channel(fixture.control, fixture.context, fixture.id);
            fixture.beginTakeover();
            JSONObject state = SetupManager.update(fixture.isolated, new JSONObject().put("capabilities",
                    new JSONObject().put("allowRoot", false)));
            assertFalse(state.getJSONObject("capabilities").getBoolean("allowRoot"));
            assertFalse(fixture.product.getBoolean("allowRoot", true));
            assertFalse(fixture.configuration().getBoolean("silentTakeoverEnabled"));
            assertFalse(fixture.control.hasOwnedSettings());
            assertEquals(0, fixture.control.status().getInt("pendingRestoreCount"));
            fixture.assertNoRootAfterRevocation();
            fixture.allowCleanup();
            assertTrue("The real setup update left the fictional source muted", NotificationChannelShell.sameState(
                    NotificationChannelShell.state(original), NotificationChannelShell.state(channel(fixture.control, fixture.context, fixture.id))));
        }
    }

    @Test public void unavailableRestoreStillWithdrawsRootAndKeepsTheJournal() throws Exception {
        try (RootRevocationFixture fixture = new RootRevocationFixture()) {
            fixture.beginTakeover();
            String journal = fixture.journal.getString("originals", "{}");
            fixture.helperUnavailable.set(true);
            SetupManager.update(fixture.isolated, new JSONObject().put("capabilities", new JSONObject().put("allowRoot", false)));
            assertFalse("A failed restoration prevented Root revocation", fixture.product.getBoolean("allowRoot", true));
            assertFalse(fixture.configuration().getBoolean("silentTakeoverEnabled"));
            assertTrue(fixture.control.hasOwnedSettings());
            assertEquals(journal, fixture.journal.getString("originals", "{}"));
            assertEquals("root_required", fixture.control.status().getString("state"));
            assertTrue(fixture.control.status().getInt("pendingRestoreCount") > 0);
            String message = fixture.control.status().getString("message");
            assertTrue("Pending restoration needs a restoration message", message.contains("恢复") || message.toLowerCase(java.util.Locale.ROOT).contains("restor"));
            fixture.assertNoRootAfterRevocation();
        }
    }

    @Test public void unreadableNotificationConfigurationDoesNotPreventRootRevocation() throws Exception {
        try (RootRevocationFixture fixture = new RootRevocationFixture()) {
            fixture.beginTakeover();
            String journal = fixture.journal.getString("originals", "{}");
            assertTrue(fixture.product.edit().putString("notificationManagementData", "fictional broken configuration").commit());
            try {
                synchronized (NotificationManagement.class) {
                    fixture.singleton.set(null, null);
                    try {
                        SetupManager.update(fixture.isolated, new JSONObject().put("capabilities", new JSONObject().put("allowRoot", false)));
                        fail("The restoration failure must be reported after revocation");
                    } finally { fixture.singleton.set(null, fixture.management); }
                }
            } catch (IllegalStateException failure) {
                assertNotNull(failure.getCause());
                assertTrue("Report the safe result of the completed revocation", failure.getMessage().contains("Root"));
            }
            assertFalse("An unreadable notification configuration kept Root enabled", fixture.product.getBoolean("allowRoot", true));
            assertEquals(journal, fixture.journal.getString("originals", "{}"));
            fixture.assertNoRootAfterRevocation();
            assertTrue(fixture.control.status().getInt("pendingRestoreCount") > 0);
        }
    }

    @Test public void revocationDuringSynchronizationCancelsBeforeApplyingTheSnapshot() throws Exception {
        try (RootRevocationFixture fixture = new RootRevocationFixture()) {
            NotificationChannel original = channel(fixture.control, fixture.context, fixture.id);
            fixture.configuration().put("silentTakeoverEnabled", true);
            CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1), stopped = new CountDownLatch(1);
            AtomicReference<Throwable> listenerFailure = new AtomicReference<>();
            SharedPreferences.OnSharedPreferenceChangeListener listener = (preferences, key) -> {
                if (!"notificationManagementData".equals(key)) return;
                try {
                    JSONObject config = new JSONObject(preferences.getString(key, "{}")).getJSONObject("config");
                    if (!config.optBoolean("silentTakeoverEnabled")) stopped.countDown();
                } catch (Throwable failure) { listenerFailure.set(failure); stopped.countDown(); }
            };
            fixture.product.registerOnSharedPreferenceChangeListener(listener);
            fixture.nextLaunchEntered = entered; fixture.nextLaunchContinue = proceed; fixture.pauseNextLaunch.set(true);
            ExecutorService work = Executors.newFixedThreadPool(2);
            try {
                Future<JSONObject> synchronizing = work.submit(() -> fixture.control.synchronize(Map.of(SOURCE, "managed"), true,
                        () -> { synchronized (fixture.management) { return fixture.configuration().optBoolean("silentTakeoverEnabled"); } }));
                assertTrue("The synchronization did not reach its controlled launch gate", entered.await(15, TimeUnit.SECONDS));
                Future<JSONObject> revocation = work.submit(() -> SetupManager.update(fixture.isolated,
                        new JSONObject().put("capabilities", new JSONObject().put("allowRoot", false))));
                assertTrue("Revocation did not stop takeover before waiting for channel work", stopped.await(15, TimeUnit.SECONDS));
                assertNull(listenerFailure.get());
                proceed.countDown();
                assertEquals("superseded", synchronizing.get(15, TimeUnit.SECONDS).getString("state"));
                revocation.get(15, TimeUnit.SECONDS);
                assertFalse(fixture.product.getBoolean("allowRoot", true));
                assertFalse(fixture.control.hasOwnedSettings());
                fixture.assertNoRootAfterRevocation();
            } finally {
                proceed.countDown(); fixture.pauseNextLaunch.set(false);
                fixture.product.unregisterOnSharedPreferenceChangeListener(listener);
                fixture.control.cancelCurrent(); work.shutdownNow();
                assertTrue("The gated fixture did not stop", work.awaitTermination(15, TimeUnit.SECONDS));
            }
            fixture.allowCleanup();
            assertTrue("A superseded synchronization modified the fictional source", NotificationChannelShell.sameState(
                    NotificationChannelShell.state(original), NotificationChannelShell.state(channel(fixture.control, fixture.context, fixture.id))));
        }
    }

    /** Separate preferences and singleton ensure SetupManager cannot restore any real app's channels. */
    private static final class RootRevocationFixture implements AutoCloseable {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        final String id = "droy_auto_" + suffix, prefix = "notification-revocation-test-" + suffix;
        final AtomicBoolean helperUnavailable = new AtomicBoolean(), pauseNextLaunch = new AtomicBoolean();
        final AtomicInteger launches = new AtomicInteger();
        volatile CountDownLatch nextLaunchEntered, nextLaunchContinue;
        final Context isolated;
        final SharedPreferences product, journal;
        final Field singleton;
        final Object previousSingleton;
        final NotificationManagement management;
        final NotificationChannelControl control;
        Boolean originalEnabled;

        RootRevocationFixture() throws Exception {
            assumeTrue("Run explicitly with -e notificationRootControl true on an authorized Root device",
                    "true".equals(InstrumentationRegistry.getArguments().getString("notificationRootControl")));
            assertTrue("Stop existing tasks before the Root revocation fixture", RuntimeService.canManageHistory());
            assertTrue(ProductPermissions.allowed(ProductPermissions.preferences(context), "allowRoot"));
            assertNoPriorRevocationFixture(context);
            assertNoUserChannelRestoration(context);
            assertFalse("Disable actual notification organization before this isolated fixture",
                    ProductPermissions.preferences(context).getBoolean("notificationManagementEnabled", false));
            isolated = new ContextWrapper(context) {
                @Override public Context getApplicationContext() { return this; }
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    return super.getSharedPreferences(STORE_NAMES.contains(name) ? prefix + "-" + name : name, mode);
                }
            };
            product = isolated.getSharedPreferences("product", Context.MODE_PRIVATE);
            journal = isolated.getSharedPreferences("notification-channels", Context.MODE_PRIVATE);
            assertTrue(product.edit().putBoolean(ProductPermissions.DEFAULT_DENY, true).putBoolean(ProductPermissions.STOPPED, true)
                    .putBoolean("allowRoot", true).putBoolean("notificationManagementEnabled", false).commit());
            assertTrue(isolated.getSharedPreferences("installation", Context.MODE_PRIVATE).edit().putInt("schemaVersion", 1)
                    .putBoolean("completed", true).putString("source", "phone").commit());
            singleton = field("singleton"); previousSingleton = singleton.get(null);
            if (previousSingleton != null) ((ExecutorService) field("worker").get(previousSingleton)).submit(() -> { }).get(15, TimeUnit.SECONDS);
            Constructor<NotificationManagement> constructor = NotificationManagement.class.getDeclaredConstructor(Context.class);
            constructor.setAccessible(true); management = constructor.newInstance(isolated);
            Context channelContext = new ContextWrapper(isolated) {
                @Override public Context getApplicationContext() { return this; }
                @Override public ApplicationInfo getApplicationInfo() {
                    launches.incrementAndGet();
                    if (pauseNextLaunch.compareAndSet(true, false)) {
                        nextLaunchEntered.countDown();
                        try { if (!nextLaunchContinue.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("fixture_gate_timeout"); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                    }
                    ApplicationInfo info = new ApplicationInfo(super.getApplicationInfo());
                    if (helperUnavailable.get()) info.sourceDir = "/nonexistent/droy-notification-fixture.apk";
                    return info;
                }
            };
            control = new NotificationChannelControl(channelContext);
            field("channels").set(management, control);
            try {
                singleton.set(null, management);
                originalEnabled = packageEnabled(control, context);
                setPackageEnabled(control, context, true);
                String created = fixtureChannels(id, false);
                assertNotNull("The isolated fictional channels were not created: " + created, findChannel(control, context, id));
            } catch (Exception | AssertionError failure) {
                try { close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        JSONObject configuration() {
            try { return (JSONObject) field("config").get(management); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }

        void beginTakeover() throws Exception {
            configuration().put("silentTakeoverEnabled", true);
            JSONObject result = control.synchronize(Map.of(SOURCE, "managed"), true);
            assertEquals("ready", result.getString("state"));
            assertTrue(control.hasOwnedSettings());
            JSONObject owned = new JSONObject(journal.getString("originals", "{}"));
            for (java.util.Iterator<String> names = owned.keys(); names.hasNext();)
                assertEquals("Only the independent test APK may be changed", SOURCE, owned.getJSONObject(names.next()).getString("packageName"));
        }

        void assertNoRootAfterRevocation() throws Exception {
            int before = launches.get();
            control.synchronize(Map.of(), false);
            assertEquals("Revoked synchronization attempted to start Root", before, launches.get());
            try {
                invoke(control, new JSONObject().put("operation", "read").put("packages", new JSONArray()));
                fail("The native helper accepted an operation after Root revocation");
            } catch (InvocationTargetException failure) { assertEquals("RootUnavailable", failure.getCause().getClass().getSimpleName()); }
            assertEquals("The native helper reached its launch path after Root revocation", before, launches.get());
        }

        void allowCleanup() { helperUnavailable.set(false); assertTrue(product.edit().putBoolean("allowRoot", true).commit()); }

        @Override public void close() throws Exception {
            pauseNextLaunch.set(false); if (nextLaunchContinue != null) nextLaunchContinue.countDown();
            allowCleanup();
            boolean recovered = false;
            try {
                control.cancelCurrent();
                assertEquals(0, control.synchronize(Map.of(), false).getInt("pendingRestoreCount"));
                setPackageEnabled(control, context, true);
                fixtureChannels(id, true);
                assertNull(findChannel(control, context, id)); assertNull(findChannel(control, context, id + "_disabled"));
                if (originalEnabled != null) setPackageEnabled(control, context, originalEnabled);
                recovered = true;
            } finally {
                try {
                    ((android.os.Handler) field("handler").get(management)).removeCallbacksAndMessages(null);
                    ExecutorService worker = (ExecutorService) field("worker").get(management);
                    worker.shutdownNow(); assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS));
                } finally {
                    singleton.set(null, previousSingleton);
                    RuntimeService.memoryPermissionsChanged(context);
                    if (recovered) for (String name : STORE_NAMES) {
                        String store = prefix + "-" + name;
                        assertTrue(context.deleteSharedPreferences(store)
                                || !new File(context.getDataDir(), "shared_prefs/" + store + ".xml").exists());
                    }
                    else {
                        Bundle report = new Bundle(); report.putString("stream", "\nDROY_ROOT_FIXTURE_RECOVERY_REQUIRED " + prefix + "\n");
                        InstrumentationRegistry.getInstrumentation().sendStatus(2, report);
                    }
                }
            }
        }

        private static final java.util.Set<String> STORE_NAMES = java.util.Set.of("product", "installation", "notification-channels");
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field result = NotificationManagement.class.getDeclaredField(name); result.setAccessible(true); return result;
    }

    private static void assertNoPriorRevocationFixture(Context context) {
        File[] files = new File(context.getDataDir(), "shared_prefs").listFiles();
        if (files != null) for (File file : files) assertFalse("Recover the prior Root fixture before starting another: " + file.getName(),
                file.getName().startsWith("notification-revocation-test-"));
    }

    private static void assertNoUserChannelRestoration(Context context) throws Exception {
        SharedPreferences saved = context.getSharedPreferences("notification-channels", Context.MODE_PRIVATE);
        assertEquals("Resolve existing app channel restoration before this fixture", 0,
                new JSONObject(saved.getString("originals", "{}")).length());
        assertEquals("Resolve existing app notification restoration before this fixture", 0,
                new JSONObject(saved.getString("packages", "{}")).length());
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

    private static String fixtureChannels(String id, boolean remove) throws Exception {
        assertTrue("Only fictional fixture channels may be changed", id.matches("droy_auto_[a-f0-9]{12}"));
        FixtureForeground foreground = FOREGROUND.get();
        assertNotNull("Channel operations require the owned fictional foreground fixture", foreground);
        Throwable failure = null;
        try {
            foreground.showSource();
            int user = android.os.Process.myUid() / 100000;
            String receipt = shell("am broadcast --user " + user + " --receiver-foreground --include-stopped-packages -n "
                    + RECEIVER + " --es id " + id + " --ez remove " + remove);
            assertTrue("The foreground fixture receiver did not acknowledge the channel operation: " + receipt,
                    receipt.contains(remove ? "DROY_AUTO_REMOVED" : "DROY_AUTO_CREATED"));
            return receipt;
        } catch (Exception | AssertionError error) { failure = error; throw error; }
        finally {
            try { foreground.returnToTarget(); }
            catch (Exception | AssertionError cleanup) { if (failure != null) failure.addSuppressed(cleanup); else throw cleanup; }
        }
    }

    private static final class FixtureForeground implements AutoCloseable {
        private final int user = android.os.Process.myUid() / 100000;
        private final String activity = SOURCE + "/io.droyagent.app.history.HistorySourceFixtureActivity";
        private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        private TestPageActivity target;
        private boolean sourceShown;

        FixtureForeground() throws Exception {
            assertNull("A foreground fixture is already owned", FOREGROUND.get());
            FOREGROUND.set(this);
            try {
                android.app.UiAutomation automation = instrumentation.getUiAutomation();
                automation.adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND");
                try {
                    target = (TestPageActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), TestPageActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } finally { automation.dropShellPermissionIdentity(); }
                show();
            }
            catch (Exception | AssertionError failure) {
                try { close(); } catch (Exception | AssertionError cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        void show() throws Exception {
            // The Root caller is the development UID; the separate test APK cannot keep it unfrozen.
            CountDownLatch focused = new CountDownLatch(1);
            android.view.ViewTreeObserver.OnWindowFocusChangeListener listener = hasFocus -> { if (hasFocus) focused.countDown(); };
            instrumentation.runOnMainSync(() -> {
                assertFalse("The owned development practice activity was destroyed", target.isFinishing() || target.isDestroyed());
                target.getWindow().getDecorView().getViewTreeObserver().addOnWindowFocusChangeListener(listener);
                if (target.hasWindowFocus()) focused.countDown();
                else target.startActivity(new Intent(target, TestPageActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            });
            try { assertTrue("The development Root caller did not regain window focus", focused.await(15, TimeUnit.SECONDS)); }
            finally { instrumentation.runOnMainSync(() -> target.getWindow().getDecorView().getViewTreeObserver().removeOnWindowFocusChangeListener(listener)); }
        }

        void showSource() throws Exception {
            sourceShown = true;
            String opened = shell("am start -W --user " + user + " -n " + activity + " --ei phase 0");
            assertTrue("The fictional source activity did not open: " + opened, opened.contains("Status: ok"));
        }

        void returnToTarget() throws Exception {
            try {
                if (sourceShown) {
                    String closed = shell("am start -W --user " + user + " -n " + activity + " --ei phase -1");
                    assertFalse("The fictional source activity did not close: " + closed,
                            closed.contains("Error:") || closed.contains("Exception"));
                    sourceShown = false;
                }
            } finally { show(); }
        }

        @Override public void close() throws Exception {
            try { if (sourceShown && target != null) returnToTarget(); }
            finally {
                try { if (target != null) instrumentation.runOnMainSync(() -> { if (!target.isDestroyed()) target.finish(); }); }
                finally { FOREGROUND.remove(); }
            }
        }
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
