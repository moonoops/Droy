package io.droyagent.app

import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses an isolated empty home with only current model configuration, never private histories. */
@RunWith(AndroidJUnit4::class)
class CompanionCurrentModelEvaluationTest {
    @Test fun currentConfiguredModelWithBoundedFictionalPrompts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Explicit current-model evaluation required", InstrumentationRegistry.getArguments().getString("companionCurrentModelEvaluation") == "true")
        assertEquals("Development application only", "io.droyagent.app.dev", context.packageName)
        assertTrue("Stop the runtime before evaluation; never interrupt existing work", RuntimeService.canManageHistory())
        val home = File(context.filesDir, "dsh-home")
        assertTrue("Existing configured development home required", home.isDirectory)
        val commonMarker = File(context.filesDir, "native-workbench-fixture.json")
        assertTrue("Another isolated fixture needs recovery", commonMarker.createNewFile())
        val marker = File(context.filesDir, "companion-model-evaluation-recovery.json")
        if (!marker.createNewFile()) { commonMarker.delete(); fail("Recover the earlier evaluation before rerunning") }
        val runId = "companion-model-evaluation-${System.nanoTime()}"
        val directory = File(context.filesDir, runId)
        val originalHome = File(context.filesDir, "$runId-original-home")
        val preferences = linkedMapOf<String, Map<String, *>>()
        val preferenceRoot = File(context.applicationInfo.dataDir, "shared_prefs")
        val preferenceBackup = File(directory, "preferences")
        val preferenceHashes = linkedMapOf<String, String>()
        var movedHome = false
        var createdHome = false
        var startedRuntime = false
        var preferencesReady = false
        var preparationProcess: Process? = null
        var activity: ActivityScenario<NativeAppActivity>? = null
        fun record(phase: String) = JSONObject().put("fixture", "companion-current-model-isolated-v2")
            .put("directory", directory.name).put("originalHome", originalHome.name).put("phase", phase)
        try {
            assertTrue(directory.mkdir())
            marker.writeText(record("preparing").toString())
            commonMarker.writeText(record("preparing").toString())
            assertTrue(preferenceBackup.mkdir())
            val originalFiles = preferenceRoot.listFiles()?.filter { it.isFile && (it.name.endsWith(".xml") || it.name.endsWith(".xml.bak")) }.orEmpty()
            for (name in originalFiles.map { it.name.removeSuffix(".bak").removeSuffix(".xml") }.distinct()) {
                val source = context.getSharedPreferences(name, 0)
                preferences[name] = source.all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
                assertTrue(source.edit().commit())
            }
            for (source in preferenceRoot.listFiles()?.filter { it.isFile && (it.name.endsWith(".xml") || it.name.endsWith(".xml.bak")) }.orEmpty()) {
                val target = File(preferenceBackup, source.name)
                source.copyTo(target)
                val expected = digest(source)
                assertEquals("Preference backup must be complete", expected, digest(target))
                preferenceHashes[source.name] = expected
            }
            preferencesReady = true
            val originalLanguage = NativeLocale.active()
            for (name in preferences.keys) assertTrue(context.getSharedPreferences(name, 0).edit().clear().commit())
            assertTrue(context.getSharedPreferences("installation", 0).edit().putBoolean("completed", true).commit())
            assertTrue(context.getSharedPreferences("product", 0).edit().putString("languagePreference", originalLanguage)
                .putBoolean(BackgroundRuntime.STOPPED, true).putBoolean("petEnabled", false).commit())
            marker.writeText(record("backed-up").toString())
            assertFalse(originalHome.exists())
            Files.move(home.toPath(), originalHome.toPath(), StandardCopyOption.ATOMIC_MOVE)
            movedHome = true
            assertTrue(home.mkdir()); createdHome = true
            File(home, ".companion-evaluation-owner").writeText(runId)
            for (name in listOf("companion-model-evaluation-plugin.mjs", "companion-model-evaluation-core.mjs", "companion-model-evaluation-prepare.mjs")) {
                instrumentation.context.assets.open(name).use { input -> File(directory, name).outputStream().use { output -> input.copyTo(output) } }
            }
            val runtime = RuntimeInstaller.install(context)
            val process = ProcessBuilder(File(context.applicationInfo.nativeLibraryDir, "libdroy_node.so").absolutePath,
                File(directory, "companion-model-evaluation-prepare.mjs").absolutePath,
                runtime.absolutePath, originalHome.absolutePath, home.absolutePath, directory.absolutePath)
                .redirectErrorStream(true).redirectOutput(File(directory, "prepare-output.private"))
                .apply { environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir }.start()
            preparationProcess = process
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                assertTrue("Preparation must settle before restoration", process.waitFor(5, TimeUnit.SECONDS))
                fail("Evaluation preparation timed out")
            }
            assertEquals("Could not prepare isolated current-model configuration", 0, process.exitValue())
            for (prepared in listOf(File(directory, "expected-model.json"), File(directory, "evaluation-core.mjs"), File(home, "profiles/mobile/cordis.patch.yml"))) {
                assertTrue("Preparation must produce launch artifact: ${prepared.name}", prepared.isFile && prepared.length() > 0L)
            }
            marker.writeText(record("running").toString())
            startedRuntime = true
            activity = ActivityScenario.launch(Intent(context, NativeAppActivity::class.java).putExtra("destination", "workbench"))
            NativeRuntimeHost.start(context)
            val startupStarted = SystemClock.elapsedRealtime()
            while (!RuntimeService.ready && SystemClock.elapsedRealtime() - startupStarted < 60_000) {
                if (SystemClock.elapsedRealtime() - startupStarted > 5_000 && RuntimeService.canManageHistory()) break
                SystemClock.sleep(100)
            }
            val evidence = File(context.getExternalFilesDir(null), "companion-humanization-evaluation").apply { assertTrue(isDirectory || mkdirs()) }
            fun runtimeReceipt(phase: String): String {
                val codes = RuntimeService.diagnosticsSnapshot().lineSequence().mapNotNull { DiagnosticCodes.fromRuntimeOutput(it) }.distinct().toList()
                File(evidence, "current-model-runtime-status.json").writeText(JSONObject().put("phase", phase)
                    .put("ready", RuntimeService.ready).put("active", RuntimeService.isStartingOrRunning())
                    .put("codes", JSONArray(codes)).toString())
                return codes.joinToString(",").ifEmpty { "EVALUATION_RUNTIME_UNAVAILABLE" }
            }
            assertTrue("Evaluation runtime did not start: ${runtimeReceipt("startup")}", RuntimeService.ready)
            val deadline = SystemClock.elapsedRealtime() + 15 * 60_000
            while (!File(directory, "done").isFile && SystemClock.elapsedRealtime() < deadline) {
                if (RuntimeService.canManageHistory()) fail("Evaluation runtime exited: ${runtimeReceipt("early-exit")}")
                SystemClock.sleep(250)
            }
            assertTrue("Evaluation must report a result", File(directory, "done").isFile)
            val result = File(directory, "result.json")
            assertTrue("Evaluation report exists", result.isFile)
            result.copyTo(File(evidence, "current-model-result.json"), overwrite = true)
            val report = JSONObject(result.readText())
            assertTrue("The request bound must be preserved", report.getInt("reservedAttempts") <= 60)
            assertEquals("Current-model evaluation blocked: ${report.optString("errorCode")}", "finished", report.getString("status"))
        } finally {
            assertTrue("Leave isolated home and recovery markers until the preparer has stopped", preparationProcess?.isAlive != true)
            if (startedRuntime) {
                context.startService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.STOP))
                activity?.close(); activity = null
                val deadline = SystemClock.elapsedRealtime() + 30_000
                while (!RuntimeService.canManageHistory() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
                assertTrue("Leave marker and original home if runtime cannot stop", RuntimeService.canManageHistory())
            }
            if (movedHome) {
                if (createdHome) {
                    assertEquals("Delete only the owned isolated home", runId, File(home, ".companion-evaluation-owner").readText())
                    assertTrue(home.deleteRecursively())
                }
                assertFalse("Never overwrite an unexpected current home", home.exists())
                Files.move(originalHome.toPath(), home.toPath(), StandardCopyOption.ATOMIC_MOVE)
                assertTrue(home.isDirectory)
            }
            if (preferencesReady) {
                for ((name, hash) in preferenceHashes) assertEquals("Keep recovery evidence if preference backup changed", hash, digest(File(preferenceBackup, name)))
                val currentNames = preferenceRoot.listFiles()?.filter { it.name.endsWith(".xml") }?.map { it.name.removeSuffix(".xml") }.orEmpty()
                for (name in (preferences.keys + currentNames).distinct()) restorePreferences(context.getSharedPreferences(name, 0), preferences[name].orEmpty())
                for (current in preferenceRoot.listFiles()?.filter { it.isFile && (it.name.endsWith(".xml") || it.name.endsWith(".xml.bak")) }.orEmpty()) {
                    if (current.name !in preferenceHashes) assertTrue(current.delete())
                }
                for ((name, hash) in preferenceHashes) {
                    File(preferenceBackup, name).copyTo(File(preferenceRoot, name), overwrite = true)
                    assertEquals("Original preference bytes must be restored", hash, digest(File(preferenceRoot, name)))
                }
            }
            assertTrue("Remove only the owned evaluation directory", !directory.exists() || directory.deleteRecursively())
            assertTrue(marker.delete())
            assertTrue(commonMarker.delete())
        }
    }
    private fun restorePreferences(target: SharedPreferences, values: Map<String, *>) {
        val edit = target.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
        } }
        assertTrue(edit.commit())
    }
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
