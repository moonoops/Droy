package io.droyagent.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Isolated setup stores on the emulator; no accounts, model requests or device settings are changed. */
@RunWith(AndroidJUnit4.class)
public final class SetupStateDeviceTest {
    private Context context;
    private String prefix;
    private File files;

    @Before public void isolate() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefix = "installation-test-" + UUID.randomUUID();
        files = new File(target.getCacheDir(), prefix);
        assertTrue(files.mkdirs());
        context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return files; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return target.getSharedPreferences(prefix + "-" + name, mode);
            }
            @Override public int checkSelfPermission(String permission) { return PackageManager.PERMISSION_DENIED; }
        };
    }

    @After public void clean() throws Exception {
        for (String name : new String[]{"installation", "product", "droy_selected_files"}) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
            InstrumentationRegistry.getInstrumentation().getTargetContext().deleteSharedPreferences(prefix + "-" + name);
        }
        if (files != null) try (var paths = Files.walk(files.toPath())) {
            var iterator = paths.sorted(java.util.Comparator.reverseOrder()).iterator();
            while (iterator.hasNext()) Files.delete(iterator.next());
        }
        RuntimeService.memoryPermissionsChanged(InstrumentationRegistry.getInstrumentation().getTargetContext());
        RuntimeService.applySavedOperationState();
    }

    @Test public void freshInstallStartsUnselectedWithEveryPhoneCapabilityDisabled() throws Exception {
        new File(files, "dsh-home/providers").mkdirs();
        SetupManager.initialize(context);
        JSONObject state = SetupManager.status(context);
        assertTrue(state.getBoolean("required"));
        assertFalse(state.getBoolean("completed"));
        assertFalse(state.getBoolean("modeSelected"));
        assertEquals(1, state.getInt("step"));
        for (String key : SetupManager.capabilityKeys(context)) assertFalse(key, state.getJSONObject("capabilities").getBoolean(key));
        assertFalse(ProductPermissions.preferences(context).getBoolean("notificationManagementEnabled", true));
        assertTrue(ProductPermissions.stopped(context));
        assertFalse(ProductPermissions.allowed(ProductPermissions.preferences(context), "allowFutureCapability"));
    }

    @Test public void existingPreferencesMigrateWithoutOverwritingNamesOrRevokedChoices() throws Exception {
        ProductPermissions.preferences(context).edit().putString("petName", "旧伙伴")
            .putString("assistantName", "旧页面").putBoolean("allowRoot", false).putBoolean("allowContacts", false).commit();
        SetupManager.initialize(context);
        JSONObject state = SetupManager.status(context);
        assertFalse(state.getBoolean("required"));
        assertEquals("legacy", state.getString("source"));
        assertEquals("旧伙伴", state.getJSONObject("identity").getString("companionName"));
        assertEquals("旧页面", ProductPermissions.preferences(context).getString("assistantName", ""));
        assertFalse(state.getJSONObject("capabilities").getBoolean("allowRoot"));
        assertFalse(state.getJSONObject("capabilities").getBoolean("allowContacts"));
        assertTrue(state.getJSONObject("capabilities").getBoolean("allowApps"));
        assertTrue(ProductPermissions.preferences(context).contains("allowApps"));
        assertFalse(ProductPermissions.allowed(ProductPermissions.preferences(context), "allowFutureCapability"));
    }

    @Test public void priorSchemaOnePhoneSetupAdoptsDenyWithoutEnablingMissingCapabilities() throws Exception {
        SetupManager.initialize(context);
        ProductPermissions.preferences(context).edit().remove(ProductPermissions.DEFAULT_DENY).remove("allowContacts").commit();
        SetupManager.initialize(context);
        assertFalse(ProductPermissions.allowed(ProductPermissions.preferences(context), "allowContacts"));
        assertFalse(ProductPermissions.allowed(ProductPermissions.preferences(context), "allowFutureCapability"));
    }

    @Test public void priorSchemaOneLegacySetupSnapshotsOldDefaultsBeforeDenyingNewOnes() throws Exception {
        context.getSharedPreferences("installation", Context.MODE_PRIVATE).edit().putInt("schemaVersion", 1)
            .putString("source", "legacy").putBoolean("completed", true).commit();
        ProductPermissions.preferences(context).edit().putBoolean("allowContacts", false).commit();
        SetupManager.initialize(context);
        assertTrue(ProductPermissions.allowed(ProductPermissions.preferences(context), "allowApps"));
        assertFalse(ProductPermissions.allowed(ProductPermissions.preferences(context), "allowContacts"));
        assertFalse(ProductPermissions.allowed(ProductPermissions.preferences(context), "allowFutureCapability"));
        assertTrue(SetupManager.status(context).getBoolean("completed"));
    }

    @Test public void actualExistingHomeDataIsPreservedAsAnUpgrade() throws Exception {
        File home = new File(files, "dsh-home/providers"); assertTrue(home.mkdirs());
        Files.write(new File(home, "fixture.json").toPath(), "{\"fictional\":true}".getBytes(StandardCharsets.UTF_8));
        SetupManager.initialize(context);
        assertTrue(SetupManager.status(context).getBoolean("completed"));
        assertTrue(new File(home, "fixture.json").isFile());
    }

    @Test public void usbChoicesResumeAfterInitializationAndPartialUpdatesPreserveOtherFields() throws Exception {
        SetupManager.update(context, new JSONObject().put("source", "usb").put("mode", "assistant")
            .put("companionName", "小黑").put("userAddress", "阿明").put("personality", "简洁沉稳").put("step", 4));
        JSONObject first = SetupManager.status(context);
        assertEquals("usb", first.getString("source")); assertTrue(first.getBoolean("modeSelected"));
        assertTrue(first.getJSONObject("capabilities").getBoolean("allowControl"));
        assertFalse(first.getJSONObject("capabilities").getBoolean("allowContacts"));
        assertFalse(first.getJSONObject("capabilities").getBoolean("allowRoot"));
        SetupManager.update(context, new JSONObject().put("userAddress", "小明"));
        SetupManager.initialize(context);
        JSONObject resumed = SetupManager.status(context);
        assertEquals(4, resumed.getInt("step"));
        assertEquals("小黑", resumed.getJSONObject("identity").getString("companionName"));
        assertEquals("小明", resumed.getJSONObject("identity").getString("userAddress"));
        assertEquals("简洁沉稳", resumed.getJSONObject("identity").getString("personality"));
    }

    @Test public void modelProofIsPrivateAndConfigurationChangeInvalidatesIt() throws Exception {
        SetupManager.initialize(context);
        JSONObject proof = new JSONObject().put("passed", true).put("toolsPassed", true).put("provider", "fixture")
            .put("model", "fictional-model").put("configFingerprint", "a".repeat(64));
        JSONObject state = SetupManager.recordModel(context, proof);
        assertTrue(state.getJSONObject("modelVerification").getBoolean("passed"));
        assertFalse(state.toString().contains("configFingerprint"));
        assertTrue(SetupManager.checkModelFingerprint(context, "a".repeat(64)).getBoolean("matches"));
        assertFalse(SetupManager.checkModelFingerprint(context, "b".repeat(64)).getBoolean("matches"));
        SetupManager.recordModel(context, new JSONObject().put("passed", false));
        assertFalse(SetupManager.checkModelFingerprint(context, "a".repeat(64)).getBoolean("matches"));
    }

    @Test public void partialCapabilityChangesBecomeCustomWithoutResumingPausedOperations() throws Exception {
        SetupManager.update(context, new JSONObject().put("mode", "dialogue"));
        JSONObject state = SetupManager.update(context, new JSONObject().put("capabilities", new JSONObject().put("allowCalendar", true)));
        assertEquals("custom", state.getString("mode"));
        assertTrue(state.getBoolean("modeSelected"));
        assertTrue(state.getBoolean("toolsPaused"));
        assertTrue(ProductPermissions.stopped(context));
        assertTrue(state.getJSONObject("capabilities").getBoolean("allowCalendar"));
        assertFalse(state.getJSONObject("capabilities").getBoolean("allowContacts"));
    }

    @Test public void unverifiedModelAndDeniedSelectedPermissionsCannotCompleteSetup() throws Exception {
        SetupManager.update(context, new JSONObject().put("mode", "custom").put("capabilities", new JSONObject().put("allowCalendar", true)));
        assertThrows(IllegalStateException.class, () -> SetupManager.complete(context));
        JSONObject state = SetupManager.recordModel(context, new JSONObject().put("passed", true).put("toolsPassed", true)
            .put("provider", "fixture").put("model", "fictional-model").put("configFingerprint", "a".repeat(64)));
        boolean calendarPending = false;
        for (int index = 0; index < state.getJSONArray("checks").length(); index++) {
            JSONObject check = state.getJSONArray("checks").getJSONObject(index);
            if (check.getString("id").equals("calendar")) calendarPending = !check.getString("status").equals("passed");
        }
        assertTrue(calendarPending);
        assertFalse(state.getBoolean("canComplete"));
        assertThrows(IllegalStateException.class, () -> SetupManager.complete(context));
        assertFalse(SetupManager.status(context).getBoolean("completed"));
    }
}
