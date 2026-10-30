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
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** Writes isolated local settings only; never supplies keys, discovers models, or sends prompts. */
@RunWith(AndroidJUnit4::class)
class NativePluginConfigurationDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings")
    private lateinit var settings: NativeSettingsViewModel
    private var language = "en"

    @Test fun subagentLimitsAndSearchApiSelectionPersistAcrossReloadInBothLanguages() {
        bind()
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.writable }
        val initialMessages = section("web-search-deepseek").toString()
        val initialApi = section("web-deepseek-api").toString()
        val initialSettingsCalls = lines("native-fixture-settings.jsonl")
        val initialModelCalls = lines("native-fixture-calls.jsonl")
        compose.onNodeWithTag("native-settings-plugins").performScrollTo().performClick()
        compose.onNodeWithTag("native-settings-group:subtask-models").performClick()
        compose.onNodeWithText(text(R.string.native_settings_subagent_depth)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.native_settings_subagent_concurrency)).assertIsDisplayed()
        compose.onNodeWithTag("native-subagent-depth").performTextReplacement("2")
        compose.onNodeWithTag("native-subagent-concurrency").performTextReplacement("6")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        save()
        assertEquals(2, section("subagent").getInt("maxDepth"))
        assertEquals(6, section("subagent").getInt("maxActiveSubagents"))
        fixture.scenario.recreate()
        bind()
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.featureSettings.containsKey("subagent") }
        compose.onNodeWithTag("native-subagent-depth").assertTextContains("2")
        compose.onNodeWithTag("native-subagent-concurrency").assertTextContains("6")
        capture("round5-subagent-settings-en")
        back()
        compose.onNodeWithTag("native-settings-group:web-search").performClick()
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
        compose.waitUntil(20_000) { settings.state.value.locale == "zh" && !settings.state.value.saving }
        language = "zh"
        compose.onNodeWithTag("native-settings-group:subtask-models").performClick()
        compose.onNodeWithText(text(R.string.native_settings_subagent_depth)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.native_settings_subagent_concurrency)).assertIsDisplayed()
        compose.onNodeWithTag("native-subagent-depth").assertTextContains("2")
        compose.onNodeWithTag("native-subagent-concurrency").assertTextContains("6")
        capture("round5-subagent-settings-zh")
        back()
        compose.onNodeWithTag("native-settings-group:web-search").performClick()
        compose.onNodeWithText(text(R.string.native_settings_new_web_api)).assertIsDisplayed()
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

    private fun bind() { fixture.scenario.onActivity { settings = ViewModelProvider(it)[NativeSettingsViewModel::class.java] } }
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
