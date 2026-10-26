package io.droyagent.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.ProcessMemoryTools;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Bundled tools and real DSH providers under the app UID, with a disposable home and no model calls. */
@RunWith(AndroidJUnit4.class)
public class FullDshRuntimeDeviceTest {
    private static final String BRIDGE_TOKEN = "fictional-full-dsh-device-bridge-token";
    private Context context;
    private File runtime;
    private Path fixture;
    private Path temporary;
    private Process child;
    private ProductBridge bridge;
    private Field memorySingleton;
    private Object previousMemoryTools;
    private final Set<String> preferenceNames = ConcurrentHashMap.newKeySet();

    @Before public void setUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Stop the engine before installing or testing its runtime", RuntimeService.canManageHistory());
        assertFalse("The engine must be stopped", RuntimeService.ready);
        assertFalse("Wait for active phone tasks to finish before testing the bridge", ToolProgress.current(context).getBoolean("active"));
        runtime = RuntimeInstaller.install(context);
        fixture = Files.createTempDirectory(context.getFilesDir().toPath(), "full-dsh-device-");
        temporary = Files.createTempDirectory(context.getCacheDir().toPath(), "full-dsh-device-");
        Files.createDirectories(fixture.resolve("home/workspace"));
        Files.createDirectories(fixture.resolve("bin"));
        for (String executable : new String[]{"node", "bash", "rg"}) {
            Path target = new File(context.getApplicationInfo().nativeLibraryDir, "libdroy_" + executable + ".so").toPath();
            assertTrue("Missing packaged executable: " + executable, Files.isRegularFile(target));
            Files.createSymbolicLink(fixture.resolve("bin").resolve(executable), target);
        }
        File bridgeFiles = Files.createDirectory(fixture.resolve("bridge-files")).toFile();
        File bridgeCache = Files.createDirectory(temporary.resolve("bridge-cache")).toFile();
        String preferencePrefix = fixture.getFileName() + "-";
        Context isolated = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return bridgeFiles; }
            @Override public File getCacheDir() { return bridgeCache; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                String isolatedName = preferencePrefix + name;
                preferenceNames.add(isolatedName);
                return super.getSharedPreferences(isolatedName, mode);
            }
        };
        assertTrue(ProductPermissions.preferences(isolated).edit()
            .putString("petId", "default").putString("petName", "Full DSH fixture")
            .putString("assistantName", "Full DSH fixture")
            .putBoolean(ProductPermissions.DEFAULT_DENY, true)
            .putBoolean(ProductPermissions.STOPPED, true).commit());
        // The bridge creates a process-memory singleton even though this test only
        // requests companion identity. Do not leave its disposable context installed.
        memorySingleton = ProcessMemoryTools.class.getDeclaredField("instance");
        memorySingleton.setAccessible(true);
        previousMemoryTools = memorySingleton.get(null);
        bridge = new ProductBridge(isolated, BRIDGE_TOKEN);
        bridge.start(5000, false);
        assertTrue("The companion bridge did not start", bridge.getListeningPort() > 0);
    }

    @After public void tearDown() throws Exception {
        try {
            stopChild();
        } finally {
            try {
                if (bridge != null) { bridge.policy.stop(); bridge.stop(); }
            } finally {
                try {
                    if (memorySingleton != null) memorySingleton.set(null, previousMemoryTools);
                    for (String name : preferenceNames) context.deleteSharedPreferences(name);
                } finally {
                    try { deleteFixture(fixture); } finally { deleteFixture(temporary); }
                }
            }
        }
    }

    @Test public void bundledBashRipgrepPnpmAndPtyRunAsAppUid() throws Exception {
        JSONObject result = runNode("""
            import assert from 'node:assert/strict';
            import { createRequire } from 'node:module';
            import { readFileSync, writeFileSync } from 'node:fs';
            import { join } from 'node:path';
            import { spawnSync } from 'node:child_process';
            const require = createRequire(join(process.env.DROY_RUNTIME_DIR, 'node_modules/@deepseek-ai/dsh/package.json'));
            function checked(command, args) {
              const result = spawnSync(command, args, {cwd:process.cwd(), env:process.env, encoding:'utf8', timeout:15000});
              assert.ifError(result.error);
              assert.equal(result.status, 0, result.stderr);
              return result.stdout.trim();
            }
            assert.equal(process.platform, 'android');
            const bash = checked(process.env.DROY_BASH_PATH, ['--noprofile','--norc','-c', "printf 'bash-device-ok'"]);
            assert.equal(bash, 'bash-device-ok');
            writeFileSync('search-fixture.txt', ['first line','unique-device-search-marker',''].join(String.fromCharCode(10)));
            const rg = checked(process.env.DROY_RG_PATH, ['--no-config','--line-number','unique-device-search-marker','search-fixture.txt']);
            assert.equal(rg, '2:unique-device-search-marker');
            const { rgPath } = await import(require.resolve('@vscode/ripgrep'));
            assert.equal(rgPath, process.env.DROY_RG_PATH, 'DSH search must use the Android binary');
            const pnpmPackage = join(process.env.DROY_RUNTIME_DIR, 'node_modules/pnpm');
            const expectedPnpm = JSON.parse(readFileSync(join(pnpmPackage, 'package.json'),'utf8')).version;
            const pnpm = checked(process.execPath, [join(pnpmPackage,'bin/pnpm.cjs'),'--version']);
            assert.equal(pnpm, expectedPnpm);
            const pty = require('node-pty');
            let terminal;
            const ptyOutput = await new Promise((resolve,reject) => {
              let output = '', exited = false;
              const timer = setTimeout(() => {
                if (!exited && terminal) terminal.kill('SIGKILL');
                reject(new Error('Bundled PTY did not exit within 10 seconds'));
              },10000);
              try {
                terminal = pty.spawn(process.env.DROY_BASH_PATH, ['--noprofile','--norc','-c', "test -t 0 && test -t 1 && printf 'pty-device-ok'"],
                  {name:'xterm-256color',cols:80,rows:24,cwd:process.cwd(),env:process.env});
                terminal.onData(data => { output += data; });
                terminal.onExit(event => {
                  exited = true;
                  clearTimeout(timer);
                  if (event.exitCode !== 0) reject(new Error('PTY exit ' + event.exitCode + ': ' + output));
                  else resolve(output);
                });
              } catch(error) { clearTimeout(timer); reject(error); }
            });
            assert.match(ptyOutput,/pty-device-ok/);
            console.log('DROY_DEVICE_RESULT:' + JSON.stringify({uid:process.getuid(),platform:process.platform,bash,rg,pnpm,pty:true}));
            """, 45);
        assertEquals(android.os.Process.myUid(), result.getInt("uid"));
        assertEquals("android", result.getString("platform"));
        assertEquals("bash-device-ok", result.getString("bash"));
        assertEquals("2:unique-device-search-marker", result.getString("rg"));
        assertTrue(result.getBoolean("pty"));
        assertFalse(result.getString("pnpm").isEmpty());
    }

    @Test public void fullProfileReportsSandboxLimitAndAllowsExplicitFullAccess() throws Exception {
        JSONObject result = runNode("""
            import assert from 'node:assert/strict';
            import { createServer } from 'node:net';
            import { existsSync, readFileSync, realpathSync } from 'node:fs';
            import { join } from 'node:path';
            import { pathToFileURL } from 'node:url';
            const runtime = process.env.DROY_RUNTIME_DIR;
            const load = relative => import(pathToFileURL(join(runtime, relative)).href);
            const reservation = createServer();
            await new Promise(resolve => reservation.listen(0,'127.0.0.1',resolve));
            process.env.DROY_WEB_PORT = String(reservation.address().port);
            await new Promise(resolve => reservation.close(resolve));
            await load('mobile/bootstrap.mjs');
            const { runProfile } = await load('node_modules/@deepseek-ai/dsh/lib/profile-boot.js');
            const { createLaunchEnvironmentSnapshot } = await load('node_modules/@deepseek-ai/dsh-launch-environment/lib/index.js');
            const { mobilePackageManager } = await load('mobile/package-manager.mjs');
            const {ctx,shutdown} = await runProfile({profile:'mobile',patchFiles:[],args:['--no-open'],
              packageManager:mobilePackageManager(),environment:createLaunchEnvironmentSnapshot([{source:'process',values:process.env}])});
            try {
              assert.equal(process.platform,'android');
              assert.equal(ctx.sandboxPolicy.defaultMode,'workspace-write');
              for (const name of ['pluginManager','schedule','skills','terminalController']) assert.ok(ctx.get(name),name);
              const presets = await ctx.agentPresets.list();
              for (const preset of presets) assert.equal(preset.broken,undefined,preset.id + ': ' + preset.broken);
              const workspace = ctx.workspaceRegistry.list()[0];
              // Android may spell this fixture /data/data while DSH stores /data/user/0.
              const realHome = realpathSync(process.env.DSH_HOME);
              const expectedWorkspace = realpathSync(join(process.env.DSH_HOME,'workspace'));
              assert.equal(realpathSync(workspace.path),expectedWorkspace,'DSH must use this exact fixture workspace');
              assert.ok(expectedWorkspace.startsWith(realHome + '/'),'The workspace must remain inside the isolated home');
              const created = await ctx.sessionController.create({workspaceId:workspace.id,agentPreset:'standard'});
              const agent = ctx.agents.get(created.sessionId);
              const tools = ctx.tools.schemas(agent).map(tool => tool.name);
              for (const name of ['bash','skill','schedule_create','android_capabilities']) assert.ok(tools.includes(name),name);
              const commands = ctx.commands.list(agent).map(command => command.name);
              for (const name of ['goal','plan','compact','permission','export']) assert.ok(commands.includes(name),name);

              // No Android confinement backend is shipped by this DSH version. The standing policy must fail closed.
              let blockedCode;
              try {
                const blocked = await ctx.shell.execute(ctx.shell.resolve({command:"printf unsafe > must-not-exist.txt",workdir:workspace.path,timeoutMs:5000}));
                await blocked.result();
              } catch(error) { blockedCode = error.code; }
              assert.equal(blockedCode,'SANDBOX_UNAVAILABLE');
              assert.equal(existsSync(join(workspace.path,'must-not-exist.txt')),false);

              // This override is limited to this synthetic call; it does not change the user's or profile's permissions.
              const execution = await ctx.shell.execute(ctx.shell.resolve({command:"printf 'dsh-shell-device-ok' > explicit-access.txt; cat explicit-access.txt",
                workdir:workspace.path,timeoutMs:5000,sandboxPolicy:{mode:'danger-full-access',workspaceRoot:workspace.path}}));
              const completed = await execution.result();
              assert.equal(completed.exitCode,0,completed.stderr.text);
              assert.equal(completed.stdout.text,'dsh-shell-device-ok');
              assert.equal(completed.sandbox.mode,'danger-full-access');
              assert.equal(readFileSync(join(workspace.path,'explicit-access.txt'),'utf8'),'dsh-shell-device-ok');
              assert.equal(ctx.sandboxPolicy.defaultMode,'workspace-write');
              console.log('DROY_DEVICE_RESULT:' + JSON.stringify({uid:process.getuid(),defaultMode:ctx.sandboxPolicy.defaultMode,
                blockedCode,explicitMode:completed.sandbox.mode,stdout:completed.stdout.text,presets:presets.map(preset=>preset.id)}));
            } finally { await shutdown.shutdown(0); }
            """, 90);
        assertEquals(android.os.Process.myUid(), result.getInt("uid"));
        assertEquals("workspace-write", result.getString("defaultMode"));
        assertEquals("SANDBOX_UNAVAILABLE", result.getString("blockedCode"));
        assertEquals("danger-full-access", result.getString("explicitMode"));
        assertEquals("dsh-shell-device-ok", result.getString("stdout"));
        Set<String> presets = new java.util.HashSet<>();
        for (int index = 0; index < result.getJSONArray("presets").length(); index++)
            presets.add(result.getJSONArray("presets").getString(index));
        assertEquals(Set.of("cordis", "standard"), presets);
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
        environment.put("DROY_BRIDGE_TOKEN", BRIDGE_TOKEN);
        environment.put("DROY_BRIDGE_PORT", Integer.toString(bridge.getListeningPort()));
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
        }, "full-dsh-device-output");
        reader.start();
        boolean exited = running.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!exited) stopChild();
        reader.join(5000);
        String text = output.toString(StandardCharsets.UTF_8.name());
        assertTrue("Native DSH test timed out:\n" + text, exited);
        assertFalse("Native process output did not close", reader.isAlive());
        assertNull("Could not read native process output", readFailure.get());
        assertEquals(text, 0, running.exitValue());
        for (String line : text.split("\\R")) {
            if (line.startsWith("DROY_DEVICE_RESULT:")) return new JSONObject(line.substring("DROY_DEVICE_RESULT:".length()));
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
        // Files.walk does not follow the native-executable symlinks in bin/.
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }
}
