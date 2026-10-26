package io.droyagent.app;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real bundled pnpm and bootstrap under the app UID, isolated from the user's profile. */
@RunWith(AndroidJUnit4.class)
public class PersistentPluginsDeviceTest {
    private Context context;
    private File runtime;
    private Path fixture;
    private Path temporary;
    private Process child;

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Stop the engine before installing or testing its runtime", RuntimeService.canManageHistory());
        assertFalse("The engine must be stopped", RuntimeService.ready);
        runtime = RuntimeInstaller.install(context);
        fixture = Files.createTempDirectory(context.getFilesDir().toPath(), "persistent-plugins-device-");
        temporary = Files.createTempDirectory(context.getCacheDir().toPath(), "persistent-plugins-device-");
        Files.createDirectories(fixture.resolve("home/workspace"));
        Files.createDirectories(fixture.resolve("bin"));
        for (String executable : new String[]{"node", "bash", "rg"}) {
            Path target = new File(context.getApplicationInfo().nativeLibraryDir, "libdroy_" + executable + ".so").toPath();
            assertTrue("Missing packaged executable: " + executable, Files.isRegularFile(target));
            Files.createSymbolicLink(fixture.resolve("bin").resolve(executable), target);
        }
    }

    @After public void tearDown() throws Exception {
        try {
            stopChild();
        } finally {
            try { deleteFixture(fixture); } finally { deleteFixture(temporary); }
        }
    }

    @Test public void offlinePluginSurvivesBootstrapRestartAndRuntimePathChange() throws Exception {
        JSONObject result = runNode("""
            import assert from 'node:assert/strict';
            import { existsSync, lstatSync, mkdirSync, readFileSync, readlinkSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
            import { dirname, join } from 'node:path';
            import { pathToFileURL } from 'node:url';
            import { spawnSync } from 'node:child_process';
            const runtime = process.env.DROY_RUNTIME_DIR;
            const home = process.env.DSH_HOME;
            const root = dirname(home);
            const profile = join(home, 'profiles/mobile');
            const modules = join(profile, 'node_modules');
            const load = relative => import(pathToFileURL(join(runtime, relative)).href);
            const { mobilePackageManager } = await load('mobile/package-manager.mjs');
            const { runProfilePnpm } = await load('node_modules/@deepseek-ai/dsh-plugin-manager/lib/types/operations.js');
            assert.equal(process.platform, 'android');
            function checked(args, selectedRuntime = runtime) {
              const completed = spawnSync(process.execPath, args, {
                cwd:process.cwd(), env:{...process.env,DROY_RUNTIME_DIR:selectedRuntime},
                encoding:'utf8', timeout:15000, maxBuffer:1024*1024
              });
              assert.ifError(completed.error);
              assert.equal(completed.status, 0, completed.stdout + completed.stderr);
              return completed.stdout.trim();
            }
            function bootstrap(selectedRuntime) {
              checked(['--expose-internals', join(selectedRuntime, 'mobile/bootstrap.mjs')], selectedRuntime);
              assert.equal(lstatSync(modules).isDirectory(), true);
              assert.equal(lstatSync(modules).isSymbolicLink(), false);
            }
            bootstrap(runtime);
            const sources = join(root, 'sources');
            function localPackage(name, extra, source) {
              const directory = join(sources, name);
              mkdirSync(directory, {recursive:true});
              writeFileSync(join(directory, 'package.json'), JSON.stringify({name,version:'1.0.0',type:'module',main:'index.mjs',...extra}));
              writeFileSync(join(directory, 'index.mjs'), source);
              return directory;
            }
            const helper = localPackage('fixture-offline-helper', {}, 'export default 91;');
            const plugin = localPackage('fixture-offline-plugin', {
              dependencies:{'fixture-offline-helper':`file:${helper}`},
              dsh:{bundle:{patch:'./cordis.patch.yml'}}
            }, "import value from 'fixture-offline-helper'; export default value;");
            writeFileSync(join(plugin, 'cordis.patch.yml'), '[]');
            const manager = mobilePackageManager();
            assert.equal(manager.command, process.execPath);
            const pnpm = checked([...manager.args, '--version']);
            const packagedPnpm = JSON.parse(readFileSync(join(runtime,'node_modules/pnpm/package.json'),'utf8')).version;
            assert.equal(pnpm, packagedPnpm);
            const install = await runProfilePnpm({dir:profile,profile:'mobile',home,cwd:root,
              installAnchor:join(runtime,'node_modules/@deepseek-ai/dsh/package.json')},
              ['add', `file:${plugin}`, '--offline', `--store-dir=${join(root,'store')}`, '--registry=http://127.0.0.1:1/'],
              {...manager,env:{...manager.env,CI:'true'},execution:'service',outputBytes:32768,
                idleTimeoutMs:15000,lookupTimeoutMs:5000,signal:AbortSignal.timeout(45000)});
            assert.equal(install.exitCode, 0, install.output);
            const manifestPath = join(profile, 'package.json');
            const manifestText = readFileSync(manifestPath, 'utf8');
            const manifest = JSON.parse(manifestText);
            assert.ok(manifest.dependencies['fixture-offline-plugin']);
            assert.ok(manifest.dsh.profile.bundles.includes('fixture-offline-plugin'), 'The real plugin manager must enable the installed bundle');
            assert.equal(existsSync(join(runtime, 'node_modules/fixture-offline-plugin')), false);
            const lock = readFileSync(join(profile, 'pnpm-lock.yaml'), 'utf8');
            assert.ok(lock.length > 0);
            const modulesInode = lstatSync(modules).ino;
            const pluginSource = readFileSync(join(modules, 'fixture-offline-plugin/index.mjs'), 'utf8');
            const verifier = join(profile, 'verify-fixture.mjs');
            writeFileSync(verifier, [
              "import assert from 'node:assert/strict';",
              "import value from 'fixture-offline-plugin';",
              "assert.equal(value,91);",
              "console.log(JSON.stringify({value,uid:process.getuid(),pid:process.pid}));"
            ].join(String.fromCharCode(10)));
            function verifyPlugin(selectedRuntime) {
              const loaded = JSON.parse(checked([verifier], selectedRuntime));
              assert.equal(loaded.value, 91);
              assert.equal(loaded.uid, process.getuid());
              assert.notEqual(loaded.pid, process.pid);
              assert.equal(lstatSync(modules).ino, modulesInode, 'Bootstrap must retain the installed node_modules directory');
              assert.equal(readFileSync(join(profile, 'pnpm-lock.yaml'), 'utf8'), lock);
              assert.equal(readFileSync(join(modules, 'fixture-offline-plugin/index.mjs'), 'utf8'), pluginSource);
              assert.equal(readlinkSync(join(modules,'@droyagent/mobile')), join(selectedRuntime,'node_modules/@droyagent/mobile'));
              return loaded;
            }
            // The installed plugin must remain usable without its original source directories.
            rmSync(sources, {recursive:true});
            const installed = verifyPlugin(runtime);
            bootstrap(runtime);
            assert.equal(readFileSync(manifestPath, 'utf8'), manifestText);
            const restarted = verifyPlugin(runtime);
            assert.notEqual(restarted.pid, installed.pid);
            // A second path exercises runtime rebinding without copying or changing the app's installed runtime.
            const nextRuntime = join(root, 'runtime-two');
            symlinkSync(runtime, nextRuntime);
            bootstrap(nextRuntime);
            const nextManifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
            assert.deepEqual(nextManifest, {...manifest,dependencies:{...manifest.dependencies,
              '@droyagent/mobile':`link:${join(nextRuntime,'node_modules/@droyagent/mobile')}`}});
            const switched = verifyPlugin(nextRuntime);
            assert.notEqual(switched.pid, restarted.pid);
            const nextManager = mobilePackageManager({runtime:nextRuntime});
            assert.equal(checked([...nextManager.args, '--version'], nextRuntime), packagedPnpm);
            assert.equal(existsSync(join(runtime, 'node_modules/fixture-offline-plugin')), false);
            console.log('DROY_PLUGIN_RESULT:' + JSON.stringify({uid:process.getuid(),platform:process.platform,pnpm,
              installed:installed.value,restarted:restarted.value,runtimeSwitched:switched.value,
              independentModules:lstatSync(modules).isDirectory(),lockPreserved:true}));
            """, 110);
        assertEquals(android.os.Process.myUid(), result.getInt("uid"));
        assertEquals("android", result.getString("platform"));
        assertEquals(91, result.getInt("installed"));
        assertEquals(91, result.getInt("restarted"));
        assertEquals(91, result.getInt("runtimeSwitched"));
        assertTrue(result.getBoolean("independentModules"));
        assertTrue(result.getBoolean("lockPreserved"));
        assertFalse(result.getString("pnpm").isEmpty());
    }

    private JSONObject runNode(String source, long timeoutSeconds) throws Exception {
        assertTrue("The real engine started during the device test", RuntimeService.canManageHistory());
        Path script = fixture.resolve("fixture.mjs");
        Files.write(script, source.getBytes(StandardCharsets.UTF_8));
        String nativeDirectory = context.getApplicationInfo().nativeLibraryDir;
        ProcessBuilder builder = new ProcessBuilder(nativeDirectory + "/libdroy_node.so", "--expose-internals", script.toString());
        builder.directory(fixture.resolve("home/workspace").toFile()).redirectErrorStream(true);
        Map<String,String> environment = builder.environment();
        environment.clear();
        environment.put("HOME", fixture.resolve("home").toString());
        environment.put("DSH_HOME", fixture.resolve("home").toString());
        environment.put("TMPDIR", temporary.toString());
        environment.put("PATH", fixture.resolve("bin") + ":" + nativeDirectory + ":/system/bin");
        environment.put("SHELL", fixture.resolve("bin/bash").toString());
        environment.put("LD_LIBRARY_PATH", nativeDirectory);
        environment.put("DROY_NATIVE_DIR", nativeDirectory);
        environment.put("DROY_RUNTIME_DIR", runtime.getAbsolutePath());
        environment.put("DROY_BASH_PATH", fixture.resolve("bin/bash").toString());
        environment.put("DROY_RG_PATH", fixture.resolve("bin/rg").toString());
        environment.put("DROY_WEB_TOKEN", "fictional-device-web-token-1234567890");
        environment.put("DROY_BRIDGE_TOKEN", "fictional-device-bridge-token-1234567890");
        environment.put("DROY_BRIDGE_PORT", "1");
        environment.put("DSH_TELEMETRY_DISABLED", "1");
        environment.put("NO_COLOR", "1");
        environment.put("TERM", "dumb");
        environment.put("TZ", "UTC");
        child = builder.start();
        Process running = child;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try (InputStream input = running.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() < 512 * 1024) output.write(buffer, 0, Math.min(count, 512 * 1024 - output.size()));
                }
            } catch (IOException error) { readFailure.set(error); }
        }, "persistent-plugins-device-output");
        reader.start();
        boolean exited = running.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!exited) stopChild();
        reader.join(5000);
        String text = output.toString(StandardCharsets.UTF_8.name());
        assertTrue("Persistent plugin test timed out:\n" + text, exited);
        assertFalse("Native process output did not close", reader.isAlive());
        assertNull("Could not read native process output", readFailure.get());
        assertEquals(text, 0, running.exitValue());
        for (String line : text.split("\\R")) {
            if (line.startsWith("DROY_PLUGIN_RESULT:")) return new JSONObject(line.substring("DROY_PLUGIN_RESULT:".length()));
        }
        throw new AssertionError("Native test emitted no result:\n" + text);
    }

    private void stopChild() throws InterruptedException {
        if (child == null || !child.isAlive()) return;
        child.destroy();
        if (!child.waitFor(2, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            assertTrue("Native test process did not stop", child.waitFor(5, TimeUnit.SECONDS));
        }
    }

    private static void deleteFixture(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) return;
        // Do not follow the runtime alias or executable links into the real installation.
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }
}
