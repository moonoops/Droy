package io.droyagent.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.data.NativeLocalTransport
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.rules.ExternalResource

/** Compose's rule must wrap this rule; the shared marker also blocks legacy fixture overlap. */
class NativeIsolatedRuntimeFixture(private val destination: String = "workbench", private val allowRoot: Boolean? = null) : ExternalResource() {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context: Context get() = instrumentation.targetContext
    val runtimeTransport = NativeLocalTransport()
    lateinit var home: File
        private set
    val scenario: ActivityScenario<NativeAppActivity> get() = requireNotNull(activity)
    private var activity: ActivityScenario<NativeAppActivity>? = null
    private lateinit var marker: File
    private lateinit var backup: File
    private lateinit var preferenceBackup: File
    private val preferences = linkedMapOf<String, Map<String, *>>()
    private val originalPreferenceFiles = linkedSetOf<String>()
    private var hadHome = false
    private var ownsHome = false
    private var ownsMarker = false
    private var ownsLock = false
    private var changedPreferences = false

    override fun before() = prepare()
    override fun after() = restore()

    fun prepare() {
        assumeTrue("Explicit isolated native fixture required", InstrumentationRegistry.getArguments().getString("nativeWorkbenchFixture") == "true")
        val fixtureLocale = InstrumentationRegistry.getArguments().getString("nativeFixtureLocale", "en")
        require(fixtureLocale in setOf("en", "zh")) { "Fixture locale must be en or zh" }
        assertEquals("Only the development application can run this fixture", "io.droyagent.app.dev", context.packageName)
        assertTrue("A dedicated emulator is required", Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("emulator"))
        assertTrue("Do not interrupt an existing runtime", RuntimeService.canManageHistory())
        assertTrue("Do not interrupt audio capture", AudioCapture.get(context).uiStatus().isNull("active"))
        assertTrue("Native isolated fixtures cannot run in parallel", lock.compareAndSet(false, true)); ownsLock = true
        try {
            home = File(context.filesDir, "dsh-home")
            backup = File(context.filesDir, "native-workbench-home-${System.nanoTime()}")
            preferenceBackup = File(context.filesDir, "${backup.name}-preferences")
            marker = File(context.filesDir, "native-workbench-fixture.json")
            assertTrue("Recover the previous native fixture before running again", marker.createNewFile()); ownsMarker = true
            hadHome = home.exists()
            marker.writeText(record("preparing").toString())
            assertTrue(preferenceBackup.mkdir())
            val preferenceRoot = File(context.applicationInfo.dataDir, "shared_prefs")
            val original = preferenceRoot.listFiles()?.filter { it.isFile && (it.name.endsWith(".xml") || it.name.endsWith(".xml.bak")) }.orEmpty()
            val existing = original.map { it.name.removeSuffix(".bak").removeSuffix(".xml") }
            for (name in (nativePreferenceNames + existing).distinct()) {
                val source = context.getSharedPreferences(name, 0)
                preferences[name] = source.all.mapValues { (_, value) -> if (value is Set<*>) value.toSet() else value }
                // Flush preceding apply() calls without changing existing values before copying disk recovery data.
                if (name in existing) assertTrue(source.edit().commit())
            }
            preferenceRoot.listFiles()?.filter { it.isFile && (it.name.endsWith(".xml") || it.name.endsWith(".xml.bak")) }?.forEach { source ->
                source.copyTo(File(preferenceBackup, source.name)); originalPreferenceFiles += source.name
            }
            for (name in preferences.keys) {
                if ("$name.xml" !in originalPreferenceFiles) File(preferenceBackup, "$name.absent").writeText("")
            }
            marker.writeText(record("backed-up").toString())
            if (hadHome) assertTrue("Preserve the existing home", home.renameTo(backup))
            ownsHome = true
            val profile = File(home, "profiles/mobile").apply { assertTrue(mkdirs()) }
            val plugin = File(home, "native-fixture.mjs")
            plugin.writeText(instrumentation.context.assets.open("native-workbench-provider.mjs").bufferedReader().use { it.readText() })
            File(profile, "cordis.patch.yml").writeText(JSONArray()
                .put(JSONObject().put("id", "agent-default-model").put("config", JSONObject().put("provider", "droy-native-fixture").put("model", "native-local")))
                .put(JSONObject().put("id", "locale").put("config", JSONObject().put("preference", fixtureLocale)))
                .put(JSONObject().put("insert", JSONArray().put(JSONObject().put("id", "native-local-fixture").put("name", plugin.absolutePath)))).toString())
            changedPreferences = true
            preferences.keys.forEach { assertTrue(context.getSharedPreferences(it, 0).edit().clear().commit()) }
            assertTrue(context.getSharedPreferences("installation", 0).edit().putBoolean("completed", true).commit())
            val product = context.getSharedPreferences("product", 0).edit().putString("languagePreference", fixtureLocale)
                .putBoolean(BackgroundRuntime.STOPPED, true).putBoolean("petEnabled", false)
            allowRoot?.let { product.putBoolean("allowRoot", it) }
            assertTrue(product.commit())
            marker.writeText(record("running").toString())
            launch(destination)
            // Native foreground entry now starts the engine. Never depend on the retired
            // explicit-stop preference to hold an opened Activity offline.
            awaitRuntime(120_000)
        } catch (error: Throwable) {
            try { restore() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    fun startRuntime(timeoutMs: Long = 120_000) {
        NativeRuntimeHost.start(context)
        awaitRuntime(timeoutMs)
    }
    private fun awaitRuntime(timeoutMs: Long) {
        await(timeoutMs, "The local fixture must boot the real DSH Host") {
            RuntimeService.ready && File(home, "native-fixture-ready.json").isFile && NativeRuntimeHost.inspect(context).ready
        }
    }
    fun recreate() = scenario.recreate()
    fun relaunch(destination: String = this.destination) { activity?.close(); activity = null; launch(destination) }
    private fun launch(destination: String) { activity = ActivityScenario.launch(Intent(context, NativeAppActivity::class.java).putExtra("destination", destination)) }
    fun capture(name: String) {
        require(name.matches(Regex("[A-Za-z0-9_-]+")))
        instrumentation.waitForIdleSync()
        val directory = File(context.getExternalFilesDir(null), "native-isolated-evidence").apply { assertTrue(isDirectory || mkdirs()) }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    fun restore() {
        try {
            if (!ownsMarker) return
            if (ownsHome) {
                context.startService(Intent(context, RuntimeService::class.java).setAction(RuntimeService.STOP))
                activity?.close(); activity = null
                await(30_000, "Leave recovery evidence if the fixture engine cannot stop") { RuntimeService.canManageHistory() }
                assertTrue("Remove only the owned fixture home", !home.exists() || home.deleteRecursively())
                if (hadHome) assertTrue("Restore the original home", backup.renameTo(home))
                ownsHome = false
            }
            if (changedPreferences) {
                val preferenceRoot = File(context.applicationInfo.dataDir, "shared_prefs")
                val names = preferenceRoot.listFiles()?.mapNotNull { it.name.takeIf { n -> n.endsWith(".xml") }?.removeSuffix(".xml") }.orEmpty()
                for (name in (preferences.keys + names).distinct()) restorePreferences(context.getSharedPreferences(name, 0), preferences[name].orEmpty())
                preferenceRoot.listFiles()?.filter { it.isFile && (it.name.endsWith(".xml") || it.name.endsWith(".xml.bak")) }?.forEach { current ->
                    if (current.name !in originalPreferenceFiles) assertTrue("Remove only fixture-created preferences", current.delete())
                }
                // The in-memory values were restored first; preserve the original persisted snapshot as well.
                for (name in originalPreferenceFiles) File(preferenceBackup, name).copyTo(File(preferenceRoot, name), overwrite = true)
                changedPreferences = false
            }
            if (preferenceBackup.exists()) assertTrue(preferenceBackup.deleteRecursively())
            assertTrue(marker.delete()); ownsMarker = false
        } finally {
            if (ownsLock) { lock.set(false); ownsLock = false }
        }
    }
    private fun record(phase: String) = JSONObject().put("backup", backup.name).put("hadHome", hadHome)
        .put("preferences", preferenceBackup.name).put("phase", phase).put("fixture", "native-isolated-runtime-v1")
    private fun await(timeoutMs: Long, message: String, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
        assertTrue(message, predicate())
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
    companion object {
        private val lock = AtomicBoolean()
        private val nativePreferenceNames = listOf("product", "installation", "native_workbench", "native_companions", "native_media",
            "native_companion_deletion", "native_companion_local_maintenance",
            "native_setup_draft", "native_companion_creation", "native-engine-drafts", "native_voice_input", "native_voice_capture",
            "native_share_preview", "native_share_inbox", "native_workspaces", "native_standard_voice")
    }
}
