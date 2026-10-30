package io.droyagent.app

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.androiduse.DroyAccessibilityService
import io.droyagent.app.history.HistoryCapture
import io.droyagent.app.nativeui.memory.NativeMemoryClient
import io.droyagent.app.nativeui.memory.NativeMemoryMutation
import io.droyagent.app.nativeui.memory.NativeMemoryPage
import io.droyagent.app.nativeui.memory.NativeMemoryViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** Actual Compose controls and local SQLite stores; the model fixture stays on-device. */
@RunWith(AndroidJUnit4::class)
class RootlessMemoryProductDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val memoryFiles = IsolatedMemoryFiles()
    @get:Rule(order = 2) val fixture = NativeIsolatedRuntimeFixture("settings", allowRoot = false)
    private lateinit var memory: NativeMemoryViewModel
    private lateinit var settings: NativeSettingsViewModel
    private var language = "en"
    private val client = NativeMemoryClient()

    @Test fun memoryDefaultsActivateHostAndKeepAutomaticOffChoiceInBothLanguages() {
        bindModels()
        compose.onNodeWithTag("native-settings-memory").performScrollTo().assertIsEnabled().performClick()
        awaitTag("native-memory-settings")
        awaitMemory()
        assertFalse(memory.state.value.status!!.settings.enabled)
        assertFalse(memory.state.value.status!!.settings.automaticExtraction)
        val modelLogs = listOf("native-fixture-calls.jsonl", "native-fixture-companion.jsonl")
            .associateWith { File(fixture.home, it).length() }
        val preferences = fixture.context.getSharedPreferences("droy_memory", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().remove("enabled").remove("automaticExtraction").commit())
        restartHost()
        compose.waitUntil(30_000) {
            memory.state.value.status?.let { it.gateReady && it.settings.enabled && it.settings.automaticExtraction } == true
        }
        assertTrue(MemoryRuntime.get(fixture.context).status().getJSONObject("execution").getBoolean("ready"))
        assertFalse("Default intent must not require rewriting the saved master choice", preferences.contains("enabled"))
        assertFalse(preferences.contains("automaticExtraction"))
        for (locale in listOf("en", "zh")) {
            language(locale)
            page(NativeMemoryPage.SETTINGS)
            compose.onNodeWithTag("native-memory-master").assertIsOn()
            compose.onNodeWithText(text(R.string.native_memory_automatic)).assertIsOn()
            assertSourcesOff()
            assertNoErrors()
            capture("rootless-memory-defaults-$locale")
        }
        compose.onNodeWithText(text(R.string.native_memory_automatic)).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(20_000) {
            !memory.state.value.busy && !memory.state.value.loading && memory.state.value.status?.settings?.automaticExtraction == false
        }
        assertTrue(preferences.contains("automaticExtraction"))
        assertFalse(preferences.getBoolean("automaticExtraction", true))
        fixture.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("native-settings-memory")
        compose.onNodeWithTag("native-settings-memory").performScrollTo().performClick()
        awaitTag("native-memory-settings")
        awaitMemory()
        compose.onNodeWithTag("native-memory-master").assertIsOn()
        compose.onNodeWithText(text(R.string.native_memory_automatic)).assertIsOff()
        assertSourcesOff()
        capture("rootless-memory-automatic-off-reopened-zh")
        for ((name, length) in modelLogs)
            assertEquals("Default memory and settings navigation must not dispatch model processing: $name", length, File(fixture.home, name).length())
        File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence/rootless-memory-defaults-receipt.json")
            .writeText(JSONObject().put("defaultHostReady", true).put("memoryEnabled", true)
                .put("automaticDefaultEnabled", true).put("savedAutomaticOffRetained", true)
                .put("sourcesEnabled", false).put("modelProcessingCalls", 0)
                .put("languages", org.json.JSONArray(listOf("en", "zh"))).toString(2))
    }

    @Test fun memoryControlsAndDurableRecordsWorkInBothLanguagesWithoutRoot() {
        bindModels()
        assertFalse(ProductPermissions.allowed(ProductPermissions.preferences(fixture.context), "allowRoot"))
        compose.onNodeWithTag("native-settings-memory").performScrollTo().assertIsEnabled().performClick()
        awaitTag("native-memory-settings")
        awaitMemory()
        assertFalse(memory.state.value.status!!.settings.enabled)
        val runtimeBefore = NativeRuntimeHost.runtimeIdentity()
        val epochBefore = MemoryRuntime.get(fixture.context).status().getJSONObject("execution").getString("epoch")
        master(true)
        assertEquals("Opening memory must not restart the host", runtimeBefore, NativeRuntimeHost.runtimeIdentity())
        assertEquals(epochBefore, MemoryRuntime.get(fixture.context).status().getJSONObject("execution").getString("epoch"))
        val root = MemoryRuntime.get(fixture.context).status().getJSONObject("root")
        assertFalse(root.getBoolean("required"))
        assertFalse(root.getBoolean("authorized"))
        assertEquals(0L, root.getLong("checkedAt"))
        verifyIndependentDeviceCapture()

        for (locale in listOf("en", "zh")) {
            language(locale)
            page(NativeMemoryPage.SETTINGS)
            if (memory.state.value.status?.settings?.enabled != true) master(true)
            compose.onNodeWithText(text(R.string.native_memory_master)).assertIsDisplayed()
            capture("rootless-memory-settings-$locale")
            compose.onNodeWithTag("native-memory-open-capture").performScrollTo().performClick()
            awaitTag("native-memory-capture")
            assertSourcesOff()
            capture("rootless-memory-capture-$locale")

            page(NativeMemoryPage.RECORDS)
            compose.onNodeWithTag("native-memory-search").performScrollTo().performTextReplacement("")
            compose.onNodeWithTag("native-memory-search").performImeAction()
            compose.waitUntil(15_000) { memory.state.value.filter.text.isEmpty() && !memory.state.value.recordsLoading }
            val token = "ROOTLESS_MEMORY_${locale.uppercase()}"
            val first = "$token prefers fictional tea."
            val revised = "$token prefers fictional water."
            compose.onNodeWithTag("native-memory-create").assertIsEnabled().performClick()
            awaitTag("native-memory-editor")
            compose.onNodeWithTag("native-memory-content").performTextInput(first)
            compose.onNodeWithTag("native-memory-save").assertIsEnabled().performClick()
            awaitRecords(first)
            val recordId = memory.state.value.records.single { it.text == first }.recordId
            compose.onNodeWithTag("native-memory-search").performScrollTo().performTextReplacement(token)
            compose.onNodeWithTag("native-memory-search").performImeAction()
            compose.waitUntil(15_000) { !memory.state.value.recordsLoading && memory.state.value.filter.text == token && memory.state.value.records.size == 1 }
            compose.onNodeWithTag("native-memory-record-$recordId").performScrollTo().performClick()
            compose.waitUntil(15_000) { !memory.state.value.detailLoading && memory.state.value.selected?.recordId == recordId }
            compose.onNodeWithTag("native-memory-edit").assertIsEnabled().performClick()
            awaitTag("native-memory-editor")
            compose.onNodeWithTag("native-memory-content").performTextReplacement(revised)
            compose.onNodeWithTag("native-memory-save").assertIsEnabled().performClick()
            awaitRecords(revised)
            compose.onNodeWithTag("native-memory-record-$recordId").performScrollTo().assertIsDisplayed()
            capture("rootless-memory-records-$locale")

            val identityBefore = storeIdentity()
            restartHost()
            assertEquals(identityBefore, storeIdentity())
            assertTrue(memory.state.value.status!!.settings.enabled)
            assertFalse(ProductPermissions.allowed(ProductPermissions.preferences(fixture.context), "allowRoot"))
            assertTrue(MemoryRuntime.get(fixture.context).settings().getBoolean("privacyEverActivated"))
            page(NativeMemoryPage.RECORDS)
            fixture.scenario.onActivity { memory.refresh() }
            awaitRecords(revised)
            assertEquals(recordId, memory.state.value.records.single { it.text == revised }.recordId)

            page(NativeMemoryPage.SETTINGS)
            master(false)
            assertTrue(memory.state.value.status!!.managementAvailable)
            page(NativeMemoryPage.RECORDS)
            compose.onNodeWithTag("native-memory-create").assertIsNotEnabled()
            compose.onNodeWithTag("native-memory-record-$recordId").performScrollTo().performClick()
            compose.waitUntil(15_000) { !memory.state.value.detailLoading && memory.state.value.selected?.text == revised }
            compose.onNodeWithTag("native-memory-edit").assertIsNotEnabled()
            compose.onNodeWithTag("native-memory-forget").assertIsEnabled().performClick()
            compose.onNodeWithTag("native-memory-confirm-forget").assertIsEnabled().performClick()
            compose.waitUntil(15_000) { memory.state.value.page == NativeMemoryPage.RECORDS && !memory.state.value.busy && !memory.state.value.recordsLoading && memory.state.value.records.isEmpty() }
            assertNoErrors()
            compose.onNodeWithText(text(R.string.native_memory_no_records)).performScrollTo().assertIsDisplayed()
            capture("rootless-memory-disabled-management-$locale")
        }
        val receipt = JSONObject().put("rootRequired", false).put("rootProductAllowed", false)
            .put("firstEnableRestartedHost", false).put("languages", org.json.JSONArray(listOf("en", "zh")))
            .put("createdRevisedRestartedAndForgottenRecords", 2).put("disabledManagement", true)
            .put("deviceSourceCapturedWithoutRoot", true).put("localProviderOnly", true)
        File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence/rootless-memory-product-receipt.json")
            .writeText(receipt.toString(2))
    }

    private fun bindModels() {
        fixture.scenario.onActivity {
            memory = ViewModelProvider(it)[NativeMemoryViewModel::class.java]
            settings = ViewModelProvider(it)[NativeSettingsViewModel::class.java]
        }
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.writable && !settings.state.value.loading }
    }
    private fun language(value: String) {
        fixture.scenario.onActivity { settings.setLanguage(value) }
        compose.waitUntil(20_000) { settings.state.value.locale == value && NativeLocale.active() == value && !settings.state.value.saving && memory.state.value.runtime.locale == value }
        assertNull(settings.state.value.error)
        language = value
    }
    private fun page(value: NativeMemoryPage) {
        fixture.scenario.onActivity { memory.page(value) }
        compose.waitUntil(10_000) { memory.state.value.page == value }
        awaitTag(when (value) {
            NativeMemoryPage.SETTINGS -> "native-memory-settings"
            NativeMemoryPage.CAPTURE -> "native-memory-capture"
            NativeMemoryPage.RECORDS -> "native-memory-records"
            else -> error("Unsupported fixture page")
        })
    }
    private fun master(enabled: Boolean) {
        assertEquals(!enabled, memory.state.value.status!!.settings.enabled)
        compose.onNodeWithTag("native-memory-master").performScrollTo().assertIsEnabled().performClick()
        try {
            compose.waitUntil(30_000) { !memory.state.value.busy && !memory.state.value.loading && memory.state.value.status?.settings?.enabled == enabled }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            val state = memory.state.value
            val diagnostic = JSONObject().put("busy", state.busy).put("loading", state.loading)
                .put("error", state.error).put("statusError", state.statusError).put("enabled", state.status?.settings?.enabled)
                .put("gateReason", state.status?.gateReason).put("runtimeReady", state.runtime.ready)
                .put("native", MemoryRuntime.get(fixture.context).status()).put("runtimeLog", RuntimeService.diagnosticsSnapshot())
            File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence/rootless-memory-failure.json").writeText(diagnostic.toString(2))
            throw AssertionError("Memory toggle did not settle: error=${state.error}, gate=${state.status?.gateReason}, native=${MemoryRuntime.get(fixture.context).status().getJSONObject("execution").optString("reason")}", timeout)
        }
        assertNoErrors()
        if (enabled) assertTrue(memory.state.value.status!!.gateReady)
    }
    private fun awaitMemory() {
        compose.waitUntil(30_000) { memory.state.value.runtime.ready && memory.state.value.status != null && !memory.state.value.loading }
        assertNoErrors()
    }
    private fun awaitRecords(text: String) {
        compose.waitUntil(20_000) { memory.state.value.page == NativeMemoryPage.RECORDS && !memory.state.value.busy && !memory.state.value.recordsLoading && memory.state.value.records.any { it.text == text } }
        assertNoErrors()
    }
    private fun assertNoErrors() {
        assertNull(memory.state.value.error)
        assertNull(memory.state.value.statusError)
        assertNull(memory.state.value.recordsError)
        assertNull(memory.state.value.unconfirmed)
        val maintenanceCode = memory.state.value.status?.maintenance?.lastCode
        assertTrue("Unexpected maintenance error: $maintenanceCode", maintenanceCode in setOf(null, "MEMORY_DISABLED", "MEMORY_MAINTENANCE_STOPPED"))
    }
    private fun assertSourcesOff() {
        val configuration = memory.state.value.status!!.capture!!.configuration
        assertFalse(configuration.enabled)
        assertFalse(configuration.activityEnabled)
        assertFalse(configuration.pageTextEnabled)
        assertFalse(configuration.locationEnabled)
        assertFalse(configuration.deviceStateEnabled)
    }
    private fun verifyIndependentDeviceCapture() {
        fun configure(enabled: Boolean) = runBlocking {
            client.mutate(NativeMemoryMutation("capture", JSONObject().put("operation", "configure").put("args",
                JSONObject().put("enabled", enabled).put("deviceStateEnabled", enabled)).toString()))
        }
        configure(true)
        compose.waitUntil(10_000) { runBlocking { client.status() }.capture?.state == "collecting" }
        compose.waitUntil(10_000) {
            MemoryRuntime.get(fixture.context).historyRequest(JSONObject().put("operation", "read").put("limit", 64).put("maxBytes", 196608)).getJSONArray("items").length() > 0
        }
        configure(false)
        fixture.scenario.onActivity { memory.refresh() }
        compose.waitUntil(15_000) { !memory.state.value.loading && memory.state.value.status?.capture?.configuration?.deviceStateEnabled == false }
        assertSourcesOff()
    }
    private fun restartHost() {
        val previous = NativeRuntimeHost.runtimeIdentity()
        fixture.scenario.moveToState(Lifecycle.State.CREATED)
        NativeRuntimeHost.stop(fixture.context)
        compose.waitUntil(30_000) { RuntimeService.canManageHistory() && !NativeRuntimeHost.inspect(fixture.context).active }
        fixture.scenario.moveToState(Lifecycle.State.RESUMED)
        fixture.startRuntime()
        compose.waitUntil(30_000) { memory.state.value.runtime.ready }
        fixture.scenario.onActivity { memory.refresh() }
        compose.waitUntil(30_000) { !memory.state.value.loading && memory.state.value.status?.managementAvailable == true }
        assertNotEquals(previous, NativeRuntimeHost.runtimeIdentity())
        assertNoErrors()
    }
    private fun storeIdentity(): String = runBlocking {
        val response = fixture.runtimeTransport.json("/droy/native/memory", "GET", null)
        val store = response.getJSONObject("result").getJSONObject("store")
        store.getString("spaceId") + ":" + store.getString("nodeId")
    }
    private fun text(id: Int): String = fixture.context.createConfigurationContext(Configuration(fixture.context.resources.configuration)
        .apply { setLocale(Locale.forLanguageTag(language)) }).getString(id)
    private fun awaitTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty() }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        SystemClock.sleep(350)
        fixture.capture(name)
        val state = memory.state.value
        File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence/$name-state.json").writeText(
            JSONObject().put("maintenanceCode", state.status?.maintenance?.lastCode).put("error", state.error)
                .put("enabled", state.status?.settings?.enabled).toString(2))
    }
}

/** This rule wraps the existing home fixture, because memory stores live outside dsh-home. */
class IsolatedMemoryFiles : ExternalResource() {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var journal: MemoryFixtureJournal? = null
    override fun before() {
        assumeTrue("Explicit rootless memory product fixture required", InstrumentationRegistry.getArguments().getString("memoryRootlessProductFixture") == "true")
        assertEquals("io.droyagent.app.dev", context.packageName)
        assertTrue("Never move private data on a physical device", Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("emulator"))
        assertTrue("Stop the real runtime before preserving private stores", RuntimeService.canManageHistory())
        assertFalse("Recover the prior native fixture first", File(context.filesDir, "native-workbench-fixture.json").exists())
        assertFalse("Recover the prior memory fixture first", File(context.cacheDir, "memory-rootless-lifecycle-restore.json").exists())
        assertFalse("Recover the prior memory product fixture first", File(context.cacheDir, "memory-rootless-product-restore.json").exists())
        assertFalse("Recover the prior legacy memory fixture first", File(context.cacheDir, "memory-product-fixture-restore.json").exists())
        val probe = ProcessBuilder("/system/bin/sh", "-c", "command -v su").redirectErrorStream(true).start()
        try {
            assertTrue("Root availability probe must finish", probe.waitFor(3, TimeUnit.SECONDS))
            assertNotEquals("This product fixture requires an emulator without su", 0, probe.exitValue())
        } finally { probe.destroyForcibly(); probe.inputStream.close(); probe.outputStream.close() }
        resetNativeMemory()
        try {
            journal = MemoryFixtureJournal.create(context, File(context.cacheDir, "memory-rootless-product-restore.json"), "droy_memory", "history_capture")
            for (name in listOf("droy-memory", "droy-memory.identity.json", "droy-privacy", "droy-privacy.identity.json",
                "droy-privacy-marker.json", "droy-history-spool", "droy-memory-archives")) journal!!.preserve(name)
            for (name in listOf("droy_memory", "history_capture"))
                assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
        } catch (error: Throwable) {
            try { restore() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }
    override fun after() = restore()
    private fun restore() {
        val saved = journal ?: return
        assertTrue("Keep recovery data if the inner runtime did not stop", RuntimeService.canManageHistory())
        resetNativeMemory()
        saved.restore { path ->
            if (Files.isDirectory(path.toPath(), LinkOption.NOFOLLOW_LINKS)) HistoryData.deleteTree(path.toPath())
            else Files.deleteIfExists(path.toPath())
        }
        journal = null
    }
    private fun resetNativeMemory() {
        val runtime = MemoryRuntime::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (runtime.get(null) as? MemoryRuntime)?.runtimeStopped()
        val current = HistoryCapture::class.java.getDeclaredField("current").apply { isAccessible = true }
        (current.get(null) as? HistoryCapture)?.let { capture ->
            capture.close()
            val writer = HistoryCapture::class.java.getDeclaredField("writer").apply { isAccessible = true }.get(capture) as ThreadPoolExecutor
            assertTrue("The memory spool writer must close before moving its files", writer.awaitTermination(10, TimeUnit.SECONDS))
        }
        runtime.set(null, null)
        DroyAccessibilityService.setEventObserver(null)
    }
}
