package io.droyagent.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.app.nativeui.permissions.NativeAuthorizationPolicy;
import io.droyagent.app.nativeui.permissions.NativePermissionPolicyStore;
import java.io.File;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Separate preferences and home preserve the running app's installation and permission state. */
@RunWith(AndroidJUnit4.class)
public final class NativeSetupHostDeviceTest {
    private Context target, context;
    private String prefix;
    private File files;

    @Before public void isolate() throws Exception {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(target.getPackageName().endsWith(".dev"));
        prefix = "native-setup-test-" + UUID.randomUUID();
        files = new File(target.getCacheDir(), prefix);
        assertTrue(files.mkdirs());
        context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return files; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return target.getSharedPreferences(prefix + "-" + name, mode);
            }
        };
    }

    @After public void clean() throws Exception {
        if (target != null) for (String name : new String[]{"installation", "product"})
            target.deleteSharedPreferences(prefix + "-" + name);
        if (files != null) try (var paths = Files.walk(files.toPath())) {
            var iterator = paths.sorted(Comparator.reverseOrder()).iterator();
            while (iterator.hasNext()) Files.delete(iterator.next());
        }
    }

    private SharedPreferences installation() { return context.getSharedPreferences("installation", Context.MODE_PRIVATE); }
    private JSONObject facts(String operation, String profile) throws Exception {
        return new JSONObject().put("operation", operation).put("configFingerprint", JSONObject.NULL)
            .put("profileFingerprint", profile).put("profileReady", true);
    }

    @Test public void nativeRoutingPreservesCompletedSchemaOneAndSavedProductChoices() throws Exception {
        assertTrue(installation().edit().putInt("schemaVersion", 1).putBoolean("completed", true)
            .putInt("step", 4).putString("source", "usb").putLong("completedAt", 42).commit());
        SharedPreferences product = ProductPermissions.preferences(context);
        assertTrue(product.edit().putBoolean(ProductPermissions.DEFAULT_DENY, true).putBoolean(ProductPermissions.STOPPED, true)
            .putBoolean("allowRoot", false).putString("assistantName", "Existing fixture name").commit());
        JSONObject state = NativeSetupHost.routeState(context);
        assertTrue(state.getBoolean("completed")); assertFalse(state.getBoolean("required"));
        assertEquals(5, state.getInt("step")); assertEquals(4, state.getInt("legacyStep"));
        assertFalse(state.getJSONObject("permissionPolicy").getBoolean("selected"));
        NativeSetupHost.setStep(context, 3);
        NativeSetupHost.acknowledge(context, "root", true);
        new NativePermissionPolicyStore(product).select(NativeAuthorizationPolicy.ON_FIRST_USE);
        JSONObject completed = SetupManager.nativeComplete(context, facts("nativeComplete", "b".repeat(64)));
        assertTrue(completed.getBoolean("completed"));
        assertEquals("usb", completed.getString("source")); assertEquals(4, completed.getInt("legacyStep"));
        assertEquals(42, installation().getLong("completedAt", 0));
        assertEquals("Existing fixture name", product.getString("assistantName", ""));
        assertTrue(ProductPermissions.stopped(context)); assertFalse(product.getBoolean("allowRoot", true));
        assertEquals("on_first_use", completed.getJSONObject("permissionPolicy").getString("mode"));
    }

    @Test public void stepAndAcknowledgementsCannotReplaceIdentityConfirmationOrGrantPermissions() throws Exception {
        NativeSetupHost.routeState(context);
        NativeSetupHost.setStep(context, 5);
        NativeSetupHost.acknowledge(context, "welcome", true);
        NativeSetupHost.acknowledge(context, "accessibility", true);
        NativeSetupHost.acknowledge(context, "root", true);
        JSONObject state = SetupManager.nativeStatus(context, facts("nativeStatus", "b".repeat(64)));
        assertFalse(state.getBoolean("completed")); assertFalse(state.getBoolean("canComplete"));
        assertFalse(state.getJSONObject("identity").getBoolean("confirmed"));
        for (String key : SetupManager.capabilityKeys(context))
            assertFalse(key, ProductPermissions.allowed(ProductPermissions.preferences(context), key));
        assertThrows(IllegalStateException.class, () -> SetupManager.nativeComplete(context, facts("nativeComplete", "b".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> NativeSetupHost.acknowledge(context, "modelPassed", true));
        assertThrows(IllegalArgumentException.class, () -> NativeSetupHost.setStep(context, 7));
        assertThrows(IllegalArgumentException.class, () -> SetupManager.nativeAcknowledgeIdentity(context, null));
    }

    @Test public void oldSummaryStepResumesAtPermissionsWithoutChangingCompletionOrPolicy() throws Exception {
        NativeSetupHost.routeState(context);
        assertTrue(installation().edit().putInt("nativeWizardStep", 6).putBoolean("completed", false).commit());
        JSONObject state = NativeSetupHost.routeState(context);
        assertEquals(5, state.getInt("step"));
        assertEquals(5, installation().getInt("nativeWizardStep", 0));
        assertFalse(state.getBoolean("completed"));
        assertTrue(state.getBoolean("required"));
        assertFalse(state.getJSONObject("permissionPolicy").getBoolean("selected"));
        assertThrows(IllegalArgumentException.class, () -> NativeSetupHost.setStep(context, 6));
    }

    @Test public void identityProofBecomesInvalidForAnyDifferentSavedProfileFingerprint() throws Exception {
        SetupManager.nativeAcknowledgeIdentity(context, new JSONObject().put("operation", "nativeAcknowledgeIdentity")
            .put("profileFingerprint", "b".repeat(64)).put("profileReady", true));
        assertTrue(SetupManager.nativeStatus(context, facts("nativeStatus", "b".repeat(64)))
            .getJSONObject("identity").getBoolean("confirmed"));
        assertFalse(SetupManager.nativeStatus(context, facts("nativeStatus", "c".repeat(64)))
            .getJSONObject("identity").getBoolean("confirmed"));
        assertFalse(SetupManager.nativeStatus(context, facts("nativeStatus", "b".repeat(64)).put("profileReady", false))
            .getJSONObject("identity").getBoolean("confirmed"));
    }
}
