package io.droyagent.app;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Native lifecycle only; opt-in fixture never opens a model or collects a source. */
public final class RootlessMemoryLifecycleDeviceTest {
    private static final String[] PRIVATE_PATHS = {
        "droy-memory", "droy-memory.identity.json", "droy-privacy", "droy-privacy.identity.json",
        "droy-privacy-marker.json", "droy-history-spool", "droy-memory-archives"
    };
    private Context context;
    private MemoryRuntime runtime;
    private MemoryFixtureJournal journal;

    @Before public void prepareEmptyFixture() throws Exception {
        assumeTrue("Requires an explicit empty-store fixture", "true".equals(
            InstrumentationRegistry.getArguments().getString("memoryRootlessFixture")));
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("io.droyagent.app.dev", context.getPackageName());
        assertTrue("Stop the runtime before this native lifecycle fixture", RuntimeService.canManageHistory());
        for (String name : PRIVATE_PATHS) assertFalse("Existing private data must remain untouched: " + name,
            Files.exists(new File(context.getFilesDir(), name).toPath(), LinkOption.NOFOLLOW_LINKS));
        runtime = MemoryRuntime.get(context);
        JSONObject configuration = runtime.historyRequest(new JSONObject().put("operation", "status")).getJSONObject("configuration");
        for (String name : new String[]{"enabled", "activityEnabled", "pageTextEnabled", "deviceStateEnabled", "locationEnabled"})
            assertFalse("Sources must already be off", configuration.getBoolean(name));
        journal = MemoryFixtureJournal.create(context, new File(context.getCacheDir(), "memory-rootless-lifecycle-restore.json"),
            "product", "droy_memory", "history_capture");
        for (String name : PRIVATE_PATHS) journal.preserve(name);
        runtime.runtimeStopped();
        assertTrue(context.getSharedPreferences("droy_memory", Context.MODE_PRIVATE).edit().clear().commit());
        assertTrue(ProductPermissions.preferences(context).edit().putBoolean("allowRoot", false)
            .putBoolean(ProductPermissions.STOPPED, false).commit());
    }

    @Test public void firstActivationAndLegacyRestartWorkWithoutRootAndKeepNativeGuards() throws Exception {
        runtime.prepareRuntimeStart();
        runtime.runtimeStarted();
        expect("MEMORY_EXECUTION_NOT_READY", () -> request("initialization.prepare"));
        JSONObject prepared = request("activation.prepare");
        assertFalse(prepared.getBoolean("restartRequired"));
        assertEquals(context.getFilesDir().getAbsolutePath(), prepared.getJSONObject("host").getString("filesDir"));
        JSONObject root = request("root-check");
        assertFalse(root.getBoolean("required"));
        assertFalse(root.getBoolean("authorized"));
        assertEquals(0, root.getLong("checkedAt"));
        activate(prepared);
        assertTrue(request("initialization.prepare").getBoolean("initialize"));
        JSONObject ready = runtime.request(new JSONObject().put("operation", "host-ready")
            .put("runtimeEpoch", prepared.getString("runtimeEpoch")));
        assertTrue(ready.getJSONObject("execution").getBoolean("ready"));
        settings(true);
        assertTrue(runtime.settings().getBoolean("enabled"));
        assertTrue(runtime.settings().getBoolean("privacyEverActivated"));

        // A settings refresh or Root revocation must leave a running memory host eligible.
        assertTrue(ProductPermissions.preferences(context).edit().putBoolean("allowRoot", true).commit());
        runtime.operationsChanged();
        assertTrue(ProductPermissions.preferences(context).edit().putBoolean("allowRoot", false).commit());
        runtime.operationsChanged();
        assertTrue(runtime.status().getJSONObject("execution").getBoolean("ready"));
        assertTrue(runtime.settings().getBoolean("enabled"));

        ProductPermissions.setStopped(context, true);
        runtime.operationsChanged();
        expect("HISTORY_EXECUTION_NOT_READY", () -> runtime.historyRequest(new JSONObject().put("operation", "configure")
            .put("patch", new JSONObject().put("enabled", true).put("deviceStateEnabled", true))));
        ProductPermissions.setStopped(context, false);
        runtime.operationsChanged();

        File marker = new File(context.getFilesDir(), "droy-memory.identity.json");
        byte[] original = "synthetic-existing-memory-identity".getBytes(StandardCharsets.UTF_8);
        Files.write(marker.toPath(), original);
        SharedPreferences preferences = context.getSharedPreferences("droy_memory", Context.MODE_PRIVATE);
        assertTrue(preferences.getBoolean("memoryStoreInitializedIntent", false));
        runtime.runtimeStopped();
        assertFalse(runtime.status().getJSONObject("execution").getBoolean("ready"));
        // Existing privacy/store markers previously triggered mandatory Root cleanup.
        runtime.prepareRuntimeStart();
        assertTrue(preferences.getBoolean("privacyEverActivated", false));
        assertTrue(preferences.getBoolean("enabled", false));
        runtime.runtimeStarted();
        expect("MEMORY_EXECUTION_NOT_READY", () -> runtime.request(new JSONObject().put("operation", "host-ready")
            .put("runtimeEpoch", prepared.getString("runtimeEpoch"))));
        JSONObject resumed = request("activation.prepare");
        activate(resumed);
        assertFalse("An existing store must never be recreated", request("initialization.prepare").getBoolean("initialize"));
        runtime.request(new JSONObject().put("operation", "host-ready").put("runtimeEpoch", resumed.getString("runtimeEpoch")));
        assertTrue(runtime.status().getJSONObject("execution").getBoolean("ready"));
        assertTrue(runtime.settings().getBoolean("enabled"));
        assertArrayEquals(original, Files.readAllBytes(marker.toPath()));

        JSONObject cancelled = request("activation.prepare");
        settings(false);
        expect("MEMORY_ACTIVATION_EXPIRED", () -> activate(cancelled));
        assertFalse(runtime.settings().getBoolean("enabled"));
        assertTrue("Disabling collection retains existing model-processing provenance", preferences.getBoolean("privacyEverActivated", false));
    }

    private JSONObject request(String operation) throws Exception { return runtime.request(new JSONObject().put("operation", operation)); }
    private void activate(JSONObject prepared) throws Exception {
        runtime.request(new JSONObject().put("operation", "protection-activate").put("challenge", prepared.getString("challenge"))
            .put("runtimeEpoch", prepared.getString("runtimeEpoch")).put("qualificationId", "a".repeat(64)));
    }
    private void settings(boolean enabled) throws Exception {
        runtime.request(new JSONObject().put("operation", "settings").put("patch", new JSONObject().put("enabled", enabled)));
    }
    private interface Operation { void run() throws Exception; }
    private static void expect(String code, Operation operation) throws Exception {
        try { operation.run(); fail("Expected " + code); }
        catch (IllegalStateException expected) { assertEquals(code, expected.getMessage()); }
    }

    @After public void restoreFixture() throws Exception {
        if (journal == null) return;
        runtime.runtimeStopped();
        assertTrue("Never restore data under a running runtime", RuntimeService.canManageHistory());
        journal.restore(path -> {
            if (Files.isDirectory(path.toPath(), LinkOption.NOFOLLOW_LINKS)) HistoryData.deleteTree(path.toPath());
            else Files.deleteIfExists(path.toPath());
        });
        runtime.operationsChanged();
    }
}
