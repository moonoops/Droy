package io.droyagent.app

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real settings/controller writes in the isolated home; never discovers or calls an external model. */
@RunWith(AndroidJUnit4::class)
class NativeProviderRemovalDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings")
    private lateinit var settings: NativeSettingsViewModel
    private val providerId = "droy-provider-removal-fixture"
    private val modelId = "local-only-model"
    private val credentialRef = "DROY_PROVIDER_REMOVAL_FIXTURE_KEY"

    @Test fun deletingProviderClearsItsDefaultAndDoesNotReturnAfterHostRestart() {
        fixture.scenario.onActivity { settings = ViewModelProvider(it)[NativeSettingsViewModel::class.java] }
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.writable }
        addProvider()
        native("/droy/native/settings/default-model", JSONObject().put("provider", providerId).put("model", modelId))
        refresh()
        assertEquals(providerId, settings.state.value.defaultModel?.provider)
        openModels()
        compose.onNodeWithTag("native-settings-provider:$providerId").performScrollTo().assertIsDisplayed()
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle(); fixture.capture("native-provider-added-en")
        compose.onNodeWithTag("native-settings-remove-provider:$providerId").assertDoesNotExist()
        compose.onNodeWithTag("native-settings-provider-more:$providerId").performClick()
        compose.onNodeWithTag("native-settings-remove-provider:$providerId").performClick()
        compose.onNodeWithText(text(R.string.native_settings_remove_default_provider, "en")).assertIsDisplayed()
        compose.onNodeWithTag("native-settings-confirm-remove-provider").assertIsEnabled().performClick()
        compose.waitUntil(30_000) { !settings.state.value.saving && settings.state.value.configuredProviders.none { it.id == providerId } }
        assertNull(settings.state.value.error)
        assertNull(settings.state.value.defaultModel)
        assertRemovedFromHost()
        compose.onNodeWithTag("native-settings-providers").performScrollToIndex(0)
        compose.onNodeWithText(text(R.string.native_settings_not_selected, "en")).assertIsDisplayed()
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle(); fixture.capture("native-provider-removed-en")

        fixture.scenario.moveToState(Lifecycle.State.CREATED)
        fixture.context.startService(Intent(fixture.context, RuntimeService::class.java).setAction(RuntimeService.STOP))
        compose.waitUntil(30_000) { RuntimeService.canManageHistory() && !NativeRuntimeHost.inspect(fixture.context).ready }
        fixture.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(120_000) { NativeRuntimeHost.inspect(fixture.context).ready && settings.state.value.runtime.ready }
        refresh()
        assertRemovedFromHost()
        assertTrue(settings.state.value.configuredProviders.none { it.id == providerId })
        assertNull(settings.state.value.defaultModel)
        fixture.scenario.onActivity { settings.setLanguage("zh") }
        compose.waitUntil(30_000) { settings.state.value.locale == "zh" && !settings.state.value.saving }
        assertNull(settings.state.value.error)
        openModels()
        // Activity localization follows the workbench runtime observer, not the
        // settings save receipt. Wait for the localized composition to arrive.
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text(R.string.native_settings_not_selected, "zh")).fetchSemanticsNodes(false).isNotEmpty()
        }
        compose.onNodeWithTag("native-settings-providers").performScrollToIndex(0)
        compose.onNodeWithText(text(R.string.native_settings_not_selected, "zh")).performScrollTo().assertIsDisplayed()
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle(); fixture.capture("native-provider-restart-removed-zh")

        // The same identifier can be added again; deletion is not a hidden UI blacklist.
        addProvider()
        refresh()
        assertNull(settings.state.value.defaultModel)
        assertEquals(1, settings.state.value.configuredProviders.count { it.id == providerId })
        assertEquals(1, settings.state.value.catalog.count { it.provider == providerId && it.id == modelId })
        compose.onNodeWithTag("native-settings-default-model").performScrollTo().performClick()
        compose.waitUntil(10_000) { exists("native-settings-default:$providerId:$modelId") }
        compose.onNodeWithTag("native-settings-default:$providerId:$modelId").performScrollTo().performClick()
        compose.waitUntil(30_000) { !settings.state.value.saving && settings.state.value.defaultModel?.provider == providerId }
        assertNull(settings.state.value.error)
        val selected = native("/droy/native/models").getJSONObject("default")
        assertEquals(providerId, selected.getString("provider"))
        assertEquals(modelId, selected.getString("model"))
        compose.waitUntil(10_000) { !exists("native-settings-default:$providerId:$modelId") }
        compose.onNodeWithTag("native-settings-providers").performScrollToIndex(0)
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle(); fixture.capture("native-provider-readded-selected-zh")
    }

    private fun addProvider() {
        val section = providerSection()
        val profile = JSONObject().put("displayName", "Local review")
            .put("api", "openai-completions").put("baseURL", "http://127.0.0.1:9/v1")
            .put("apiKeyEnv", credentialRef).put("models", JSONArray().put(JSONObject().put("id", modelId).put("name", "Local review model")))
        native("/droy/native/settings/mutate", JSONObject().put("ns", "llm-pi-ai").put("expectedRevision", section.getLong("revision"))
            .put("ops", JSONArray().put(JSONObject().put("op", "set").put("path", JSONArray().put("providers").put(providerId)).put("value", profile))))
        native("/droy/native/settings/credentials/set", JSONObject().put("ref", credentialRef).put("value", "fictional-removal-test-key"))
    }
    private fun assertRemovedFromHost() {
        assertFalse(providerSection().getJSONObject("value").getJSONObject("providers").has(providerId))
        val catalog = native("/droy/native/models")
        assertEquals("", catalog.getJSONObject("default").getString("provider"))
        assertEquals("", catalog.getJSONObject("default").getString("model"))
        val groups = catalog.getJSONArray("groups")
        assertTrue((0 until groups.length()).none { groups.getJSONObject(it).getString("id") == providerId })
    }
    private fun providerSection(): JSONObject {
        val sections = native("/droy/native/settings").getJSONArray("namespaces")
        return (0 until sections.length()).map(sections::getJSONObject).single { it.getString("ns") == "llm-pi-ai" }
    }
    private fun refresh() {
        fixture.scenario.onActivity { settings.refresh() }
        compose.waitUntil(30_000) { settings.state.value.loaded && !settings.state.value.loading }
        assertNull(settings.state.value.error)
    }
    private fun openModels() {
        if (!exists("native-settings-providers")) {
            compose.waitUntil(10_000) { exists("native-settings-models") }
            compose.onNodeWithTag("native-settings-models").performScrollTo().performClick()
        }
        compose.waitUntil(10_000) { exists("native-settings-providers") }
    }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty()
    private fun text(id: Int, locale: String): String = fixture.context.createConfigurationContext(
        Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }).getString(id)
    private fun native(path: String, value: JSONObject? = null): JSONObject = runBlocking {
        val response = fixture.runtimeTransport.json(path, if (value == null) "GET" else "POST", value)
        assertTrue("The isolated native operation must succeed", response.getBoolean("ok"))
        response.getJSONObject("result")
    }
}
