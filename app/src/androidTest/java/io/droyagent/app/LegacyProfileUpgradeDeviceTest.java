package io.droyagent.app;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises the real signed-asset installer without touching the user's home. */
@RunWith(AndroidJUnit4.class)
public class LegacyProfileUpgradeDeviceTest {
    private Context context;
    private File files;
    private Path runtime, profile;
    private String originalManifest;

    @Before public void createLegacyInstallation() throws Exception {
        Context actual = InstrumentationRegistry.getInstrumentation().getTargetContext();
        files = Files.createTempDirectory(actual.getCacheDir().toPath(), "legacy-profile-upgrade-").toFile();
        context = new ContextWrapper(actual) {
            @Override public File getFilesDir() { return files; }
        };
        runtime = files.toPath().resolve("runtime");
        profile = files.toPath().resolve("dsh-home/profiles/mobile");
        Files.createDirectories(profile);
        write(runtime.resolve("node_modules/@droyagent/mobile/package.json"), "{\"name\":\"@droyagent/mobile\",\"version\":\"0.1.0\"}");
        write(runtime.resolve("old-runtime-sentinel"), "old runtime must survive failed migration");
        Files.createSymbolicLink(profile.resolve("node_modules"), runtime.resolve("node_modules"));
        originalManifest = "{\"name\":\"fixture-profile\",\"private\":true,\"description\":\"keep user settings\","
            + "\"dependencies\":{\"fixture-legacy-plugin\":\"1.0.0\"},"
            + "\"dsh\":{\"profile\":{\"bundles\":[\"@deepseek-ai/dsh-base\",\"@deepseek-ai/dsh-web-app\",\"@droyagent/mobile\",\"fixture-legacy-plugin\"]}}}";
        write(profile.resolve("package.json"), originalManifest);
        write(profile.resolve("cordis.patch.yml"), "# retain user edits\n[]\n");
    }

    @After public void removeFixture() throws Exception {
        if (files != null) HistoryData.deleteTree(files.toPath());
    }

    @Test public void installedPluginSurvivesTheActualRuntimeReplacementOrder() throws Exception {
        write(runtime.resolve("node_modules/fixture-legacy-plugin/package.json"),
            "{\"name\":\"fixture-legacy-plugin\",\"version\":\"1.0.0\",\"dependencies\":{\"fixture-helper\":\"1.0.0\"}}");
        write(runtime.resolve("node_modules/fixture-legacy-plugin/index.js"), "module.exports = require('fixture-helper');\n");
        write(runtime.resolve("node_modules/fixture-helper/package.json"), "{\"name\":\"fixture-helper\",\"version\":\"1.0.0\"}");
        write(runtime.resolve("node_modules/fixture-helper/index.js"), "module.exports = 73;\n");
        assertEquals(runtime.toFile(), RuntimeInstaller.install(context));
        assertFalse(Files.exists(runtime.resolve("old-runtime-sentinel")));
        assertFalse(Files.exists(runtime.resolve("node_modules/fixture-legacy-plugin")));
        assertFalse(Files.isSymbolicLink(profile.resolve("node_modules")));
        assertEquals("module.exports = require('fixture-helper');\n", read(profile.resolve("node_modules/fixture-legacy-plugin/index.js")));
        assertEquals("module.exports = 73;\n", read(profile.resolve("node_modules/fixture-helper/index.js")));
        JSONObject manifest = new JSONObject(read(profile.resolve("package.json")));
        assertEquals("1.0.0", manifest.getJSONObject("dependencies").getString("fixture-legacy-plugin"));
        assertEquals("keep user settings", manifest.getString("description"));
        assertEquals(new JSONObject(originalManifest).getJSONObject("dsh").toString(), manifest.getJSONObject("dsh").toString());
        assertEquals("# retain user edits\n[]\n", read(profile.resolve("cordis.patch.yml")));
        assertFalse(Files.exists(profile.resolve(".droy-module-migration.json")));
        assertEquals(runtime.toFile(), RuntimeInstaller.install(context));
        assertEquals("module.exports = 73;\n", read(profile.resolve("node_modules/fixture-helper/index.js")));
    }

    @Test public void incompleteMigrationPreservesTheOldRuntimeAndCanBeRetried() throws Exception {
        IOException failed = assertThrows(IOException.class, () -> RuntimeInstaller.install(context));
        assertTrue(failed.getMessage().contains("旧运行包尚未替换"));
        assertEquals("old runtime must survive failed migration", read(runtime.resolve("old-runtime-sentinel")));
        assertTrue(Files.isSymbolicLink(profile.resolve("node_modules")));
        assertEquals(originalManifest, read(profile.resolve("package.json")));
        write(runtime.resolve("node_modules/fixture-legacy-plugin/package.json"), "{\"name\":\"fixture-legacy-plugin\",\"version\":\"1.0.0\"}");
        write(runtime.resolve("node_modules/fixture-legacy-plugin/index.js"), "module.exports = 42;\n");
        RuntimeInstaller.install(context);
        assertEquals("module.exports = 42;\n", read(profile.resolve("node_modules/fixture-legacy-plugin/index.js")));
        assertFalse(Files.exists(runtime.resolve("old-runtime-sentinel")));
    }

    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
