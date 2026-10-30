package io.droyagent.app

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.settings.NativeSettingsClient
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.util.Locale

/** Writes isolated local settings only; never supplies keys, discovers models, or sends prompts. */
@RunWith(AndroidJUnit4::class)
class NativePluginConfigurationDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings")
    @get:Rule(order = 2) val failureEvidence = object : TestWatcher() {
        override fun failed(failure: Throwable, description: Description) {
            val name = "native-plugin-failed-${description.methodName}"
            try { fixture.capture(name) } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
            try {
                val locales = "expected=$language native=${NativeLocale.active()}" +
                    if (::settings.isInitialized && ::work.isInitialized)
                        " settings=${settings.state.value.locale} settingsRuntime=${settings.state.value.runtime.locale} shellRuntime=${work.state.value.runtime.locale}" else ""
                File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence/$name.txt")
                    .writeText(locales + "\n" + compose.onRoot(useUnmergedTree = true).printToString())
            } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
        }
    }
    private lateinit var settings: NativeSettingsViewModel
    private lateinit var work: NativeWorkbenchViewModel
    private var language = "en"

    @Test fun subagentLimitsAndSearchApiSelectionPersistAcrossReloadInBothLanguages() {
        bind()
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.writable }
        awaitLanguage()
        val initialMessages = section("web-search-deepseek").toString()
        val initialApi = section("web-deepseek-api").toString()
        val initialSettingsCalls = lines("native-fixture-settings.jsonl")
        val initialModelCalls = lines("native-fixture-calls.jsonl")
        compose.onNodeWithTag("native-settings-plugins").performScrollTo().performClick()
        openSubagentSettings()
        compose.onNodeWithTag("native-subagent-depth").performTextReplacement("2")
        compose.onNodeWithTag("native-subagent-concurrency").performTextReplacement("6")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        save()
        assertEquals(2, section("subagent").getInt("maxDepth"))
        assertEquals(6, section("subagent").getInt("maxActiveSubagents"))
        fixture.scenario.recreate()
        bind()
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.featureSettings.containsKey("subagent") }
        awaitLanguage()
        awaitSubagentSettings()
        compose.onNodeWithTag("native-subagent-depth").assertTextContains("2")
        compose.onNodeWithTag("native-subagent-concurrency").assertTextContains("6")
        capture("round5-subagent-settings-en")
        back()
        openWebSearchSettings()
        compose.onNodeWithText(text(R.string.native_settings_messages_base_url)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.native_settings_search_uses)).assertIsDisplayed()
        compose.onNodeWithTag("native-search-new-api").assertIsOff().performClick()
        compose.onNodeWithText(text(R.string.native_settings_search_count)).assertIsDisplayed()
        save()
        assertRouting("deepseek-search-api", "deepseek-fetch-api")
        assertEquals(initialMessages, section("web-search-deepseek").toString())
        assertEquals(initialApi, section("web-deepseek-api").toString())
        back()

        fixture.scenario.onActivity { settings.setLanguage("zh") }
        language = "zh"
        awaitLanguage()
        openSubagentSettings()
        compose.onNodeWithTag("native-subagent-depth").assertTextContains("2")
        compose.onNodeWithTag("native-subagent-concurrency").assertTextContains("6")
        capture("round5-subagent-settings-zh")
        back()
        openWebSearchSettings()
        compose.onNodeWithTag("native-search-new-api").assertIsOn().performClick()
        compose.onNodeWithText(text(R.string.native_settings_messages_base_url)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.native_settings_search_uses)).assertIsDisplayed()
        save()
        assertRouting("deepseek-official", "http")
        capture("round5-search-settings-zh")
        fixture.scenario.onActivity { settings.refresh() }
        compose.waitUntil(15_000) { !settings.state.value.loading }
        compose.onNodeWithTag("native-search-new-api").assertIsOff()
        assertEquals(initialSettingsCalls, lines("native-fixture-settings.jsonl"))
        assertEquals(initialModelCalls, lines("native-fixture-calls.jsonl"))
    }

    private fun bind() { fixture.scenario.onActivity {
        val models = ViewModelProvider(it)
        settings = models[NativeSettingsViewModel::class.java]
        work = models[NativeWorkbenchViewModel::class.java]
    } }
    private fun awaitLanguage() {
        // Activity resources follow the workbench runtime, which refreshes independently
        // of the settings save receipt. Wait for both owners before checking rendered text.
        compose.waitUntil(20_000) {
            settings.state.value.locale == language && !settings.state.value.saving &&
                settings.state.value.runtime.locale == language && work.state.value.runtime.locale == language &&
                NativeLocale.active() == language
        }
        assertNull(settings.state.value.error)
    }
    private fun openPluginEntry(id: String, title: Int) {
        val tag = "native-settings-group:$id"
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasText(text(title)) and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))))
                .fetchSemanticsNodes(false).isNotEmpty()
        }
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
    }
    private fun openSubagentSettings() {
        openPluginEntry("subtask-models", R.string.native_settings_subtask_models)
        awaitSubagentSettings()
    }
    private fun awaitSubagentSettings() {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("native-subagent-depth").fetchSemanticsNodes(false).isNotEmpty() &&
                compose.onAllNodesWithText(text(R.string.native_settings_subagent_depth)).fetchSemanticsNodes(false).isNotEmpty() &&
                compose.onAllNodesWithText(text(R.string.native_settings_subagent_concurrency)).fetchSemanticsNodes(false).isNotEmpty()
        }
        compose.onNodeWithTag("native-subagent-depth").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.native_settings_subagent_depth)).assertIsDisplayed()
        compose.onNodeWithTag("native-subagent-concurrency").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.native_settings_subagent_concurrency)).assertIsDisplayed()
    }
    private fun openWebSearchSettings() {
        openPluginEntry("web-search", R.string.native_settings_web_search)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("native-search-new-api").fetchSemanticsNodes(false).isNotEmpty() &&
                compose.onAllNodesWithText(text(R.string.native_settings_new_web_api)).fetchSemanticsNodes(false).isNotEmpty()
        }
        compose.onNodeWithTag("native-search-new-api").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.native_settings_new_web_api)).assertIsDisplayed()
    }
    private fun save() {
        val receipt = settings.state.value.featureSaveReceipt
        compose.onNodeWithText(text(R.string.native_settings_save)).assertIsEnabled().performClick()
        compose.waitUntil(20_000) { settings.state.value.featureSaveReceipt > receipt && !settings.state.value.saving || settings.state.value.error != null }
        assertNull(settings.state.value.error)
        assertTrue(settings.state.value.featureSaveReceipt > receipt)
    }
    private fun assertRouting(search: String, fetch: String) {
        val value = section("web")
        assertEquals(search, value.getString("searchProvider"))
        assertEquals(fetch, value.getString("fetchProvider"))
    }
    private fun section(namespace: String): JSONObject = runBlocking {
        val entries = NativeSettingsClient().describe().getJSONArray("namespaces")
        (0 until entries.length()).map(entries::getJSONObject).single { it.getString("ns") == namespace }.getJSONObject("value")
    }
    private fun lines(name: String) = File(fixture.home, name).takeIf(File::isFile)?.readLines().orEmpty()
    private fun text(id: Int) = fixture.context.createConfigurationContext(Configuration(fixture.context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(language))
    }).getString(id)
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
    private fun capture(name: String) { compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle(); fixture.capture(name) }
}
