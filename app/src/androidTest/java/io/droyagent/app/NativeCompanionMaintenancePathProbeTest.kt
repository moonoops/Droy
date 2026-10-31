package io.droyagent.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No Activity, runtime start, maintenance prepare or mutation of the preserved fixture. */
@RunWith(AndroidJUnit4::class)
class NativeCompanionMaintenancePathProbeTest {
    @Test fun inspectPathsInTheActualApplicationMountNamespace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.droyagent.app.dev", context.packageName)
        assertTrue(File(context.filesDir, "native-workbench-fixture.json").isFile)
        val home = File(context.filesDir, "dsh-home")
        val runtime = File(context.filesDir, "runtime")
        val report = File(context.filesDir, "native-maintenance-path-probe.json")
        val diagnostic = File(context.filesDir, "native-maintenance-path-probe-stderr.txt")
        val script = """
            const fs=require('node:fs/promises'),url=require('node:url');
            (async()=>{
              const home=process.env.DSH_HOME;
              const result={home,cwd:process.cwd(),real:await fs.realpath(home),symbolic:(await fs.lstat(home)).isSymbolicLink()};
              const {createCompanionDeletionMaintenance}=await import(url.pathToFileURL(process.env.DROY_RUNTIME_DIR+'/node_modules/@droyagent/mobile/companions/maintenance-delete.mjs'));
              try {await createCompanionDeletionMaintenance({home,operationId:'read-only-inspection',companionId:'synthetic',admissionEpoch:'inspection',authority:{assertHeld(){throw Error('READ_ONLY')}}});result.constructed=true;}
              catch(error){result.code=error.code??error.message;}
              result.realAfterImport=await fs.realpath(home);
              process.stdout.write(JSON.stringify(result));
            })().catch(error=>{process.stderr.write(String(error));process.exitCode=1});
        """.trimIndent()
        val native = context.applicationInfo.nativeLibraryDir
        val process = ProcessBuilder(File(native, "libdroy_node.so").absolutePath, "--expose-internals", "-e", script)
            .directory(home).redirectOutput(report).redirectError(diagnostic).apply {
                environment().putAll(mapOf("DSH_HOME" to home.absolutePath, "HOME" to home.absolutePath,
                    "DROY_RUNTIME_DIR" to runtime.absolutePath, "DROY_NATIVE_DIR" to native,
                    "LD_LIBRARY_PATH" to native, "TMPDIR" to context.cacheDir.absolutePath))
            }.start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Read-only path probe did not finish") }
        assertEquals(diagnostic.readText().take(2000), 0, process.exitValue())
        val value = JSONObject(report.readText()).put("javaHome", home.absolutePath).put("javaCanonicalHome", home.canonicalPath)
            .put("contextFiles", context.filesDir.absolutePath).put("applicationFiles", context.applicationContext.filesDir.absolutePath)
        report.writeText(value.toString())
        println("DROY_MAINTENANCE_PATH_PROBE=$value")
    }
}
