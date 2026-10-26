package io.droyagent.app;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class LegacyProfileMigrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Path profile(File home) throws Exception {
        return Files.createDirectories(home.toPath().resolve("profiles/mobile"));
    }

    private void manifest(Path profile, JSONObject value) throws Exception {
        Files.write(profile.resolve("package.json"), value.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test public void aLegacyProfileWithoutUserPluginsIsNotChangedBeforeUpgrade() throws Exception {
        File home = temporary.newFolder("home");
        Path profile = profile(home);
        Files.createSymbolicLink(profile.resolve("node_modules"), temporary.newFolder("old-modules").toPath());
        JSONObject original = new JSONObject().put("dependencies", new JSONObject());
        manifest(profile, original);
        RuntimeInstaller.preserveLegacyPlugins(home, temporary.getRoot(), temporary.getRoot(), "/nonexistent-node", "/unused");
        assertTrue(Files.isSymbolicLink(profile.resolve("node_modules")));
        assertEquals(original.toString(), new String(Files.readAllBytes(profile.resolve("package.json")), StandardCharsets.UTF_8));
        assertEquals(2, profile.toFile().list().length);
    }

    @Test public void eachDeclaredDependencyFieldTriggersDetachmentOnlyForTheLegacyLink() throws Exception {
        File home = temporary.newFolder("home");
        Path profile = profile(home);
        Path modules = profile.resolve("node_modules");
        Files.createSymbolicLink(modules, temporary.newFolder("old-modules").toPath());
        for (String field : new String[]{"dependencies", "devDependencies", "optionalDependencies", "peerDependencies"}) {
            manifest(profile, new JSONObject().put(field, new JSONObject().put("fixture-plugin", "1.0.0")));
            assertTrue(field, RuntimeInstaller.needsLegacyPluginMigration(home));
        }
        manifest(profile, new JSONObject().put("dependencies", new JSONObject().put("@droyagent/mobile", "link:/runtime/mobile")));
        assertFalse(RuntimeInstaller.needsLegacyPluginMigration(home));
        Files.delete(modules);
        Files.createDirectory(modules);
        manifest(profile, new JSONObject().put("dependencies", new JSONObject().put("fixture-plugin", "1.0.0")));
        assertFalse(RuntimeInstaller.needsLegacyPluginMigration(home));
    }

    @Test public void pendingJournalIsResumedEvenAfterTheLegacyLinkWasMoved() throws Exception {
        File home = temporary.newFolder("home");
        Path profile = profile(home);
        Files.write(profile.resolve(".droy-module-migration.json"), "{}".getBytes(StandardCharsets.UTF_8));
        assertTrue(RuntimeInstaller.needsLegacyPluginMigration(home));
    }

    @Test public void malformedLegacyDependencyDeclarationsCannotBeSilentlyDiscarded() throws Exception {
        File home = temporary.newFolder("home");
        Path profile = profile(home);
        Files.createSymbolicLink(profile.resolve("node_modules"), temporary.newFolder("old-modules").toPath());
        manifest(profile, new JSONObject().put("dependencies", "fictional-sensitive-dependency-value"));
        Exception rejected = assertThrows(Exception.class, () -> RuntimeInstaller.needsLegacyPluginMigration(home));
        assertFalse(rejected.toString().contains("fictional-sensitive-dependency-value"));
        assertNull(rejected.getCause());
    }
}
