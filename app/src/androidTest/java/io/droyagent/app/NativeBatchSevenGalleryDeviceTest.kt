package io.droyagent.app

import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.companion.creation.NativeCompanionCreationViewModel
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Preview changes stay local until applied; all review data belongs to the isolated fixture. */
@RunWith(AndroidJUnit4::class)
class NativeBatchSevenGalleryDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings", allowRoot = false)
    private lateinit var settings: NativeSettingsViewModel
    private lateinit var creation: NativeCompanionCreationViewModel
    private lateinit var companions: NativeCompanionViewModel
    private var language = "en"

    @Test fun captureCreationAndPersonalizationAndVerifyDraftBoundaries() {
        fixture.scenario.onActivity {
            val models = ViewModelProvider(it)
            settings = models[NativeSettingsViewModel::class.java]
            creation = models[NativeCompanionCreationViewModel::class.java]
            companions = models[NativeCompanionViewModel::class.java]
        }
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.writable && settings.state.value.runtime.ready }
        assertTrue(fixture.context.getSharedPreferences("product", 0).edit().putString("appearanceMode", "light").commit())
        val id = request(method = "POST", body = JSONObject()
            .put("requestId", UUID.randomUUID().toString()).put("templateId", "maimai").put("name", "M3E Review")
            .put("model", JSONObject().put("provider", "droy-native-fixture").put("model", "native-alternate"))
            .put("settings", JSONObject().put("proactiveEnabled", false).put("memoryEnabled", true).put("allowProfanity", true)
                .put("quietHours", JSONObject().put("enabled", true).put("start", "22:00").put("end", "09:00"))))
            .getJSONObject("profile").getString("id")
        fixture.scenario.onActivity { companions.refresh() }
        compose.waitUntil(20_000) { companions.state.value.companions.any { it.id == id } }
        for (locale in listOf("en", "zh")) {
            language = locale
            fixture.scenario.onActivity { settings.setLanguage(locale) }
            compose.waitUntil(20_000) { settings.state.value.locale == locale && NativeLocale.active() == locale && !settings.state.value.saving }
            assertNull(settings.state.value.error)
            compose.onNodeWithTag("native-tab-messages").performClick()
            awaitTag("native-companion-conversation:$id")
            compose.onNodeWithTag("native-companion-conversation:$id").performClick()
            awaitTag("native-open-companion-profile")
            compose.onNodeWithTag("native-open-companion-profile").performClick()
            awaitTag("native-companion-profile")
            inspectPersonality(id)
            inspectBackground(id)
            back(); awaitTag("native-companion-chat")
            back(); awaitTag("native-companion-messages")
            inspectCreation()
            compose.onNodeWithTag("native-tab-settings").performClick()
            awaitTag("native-settings-home-list")
        }
        assertFalse(savedProfile(id).getJSONObject("settings").getBoolean("proactiveEnabled"))
        for (name in listOf("native-fixture-calls.jsonl", "native-fixture-companion.jsonl")) {
            val calls = File(fixture.home, name)
            assertTrue("Review must not run models: $name", !calls.exists() || calls.length() == 0L)
        }
    }

    private fun inspectPersonality(id: String) {
        val before = savedProfile(id)
        compose.onNodeWithTag("native-companion-personality").performScrollTo().performClick()
        awaitTag("native-companion-personality-input")
        compose.onNodeWithTag("native-companion-personality-save").assertIsNotEnabled()
        capture("personality-upper")
        compose.onNodeWithTag("native-companion-behavior-input").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("native-companion-reply-style-input").performScrollTo().assertIsDisplayed()
        capture("personality-lower")
        compose.onNodeWithTag("native-companion-reply-style-input").performTextReplacement("Fictional review draft")
        compose.onNodeWithTag("native-companion-personality-save").assertIsEnabled()
        back()
        if (!dialogExists(R.string.native_companion_discard)) back()
        clickDialog(R.string.native_companion_discard)
        awaitTag("native-companion-profile")
        val after = savedProfile(id)
        for (key in listOf("personalityOverride", "behaviorStyleOverride", "replyStyleOverride")) assertEquals(before.optString(key), after.optString(key))
    }

    private fun inspectBackground(id: String) {
        compose.onNodeWithTag("native-companion-interaction").performScrollTo().performClick()
        awaitTag("native-companion-interaction-save")
        openBackground()
        compose.onNodeWithTag("native-background-option-default").assertIsSelected()
        capture("background-default")
        val before = savedProfile(id).optJSONObject("chatBackground")?.toString()
        compose.onNodeWithTag("native-background-option-01").performScrollTo().performClick()
        compose.onNodeWithTag("native-background-option-01").assertIsSelected()
        assertEquals("Preview must not persist before Apply", before, savedProfile(id).optJSONObject("chatBackground")?.toString())
        capture("background-selected")
        compose.onNodeWithTag("native-background-apply").performClick()
        awaitBackgroundSave()
        assertEquals("01", savedProfile(id).getJSONObject("chatBackground").getString("id"))
        openBackground()
        compose.onNodeWithTag("native-background-option-01").assertIsSelected()
        compose.onNodeWithTag("native-background-option-02").performScrollTo().performClick()
        back(); clickDialog(R.string.native_companion_discard)
        awaitTag("native-companion-interaction-save")
        assertEquals("Discard must preserve the applied background", "01", savedProfile(id).getJSONObject("chatBackground").getString("id"))
        openBackground()
        compose.onNodeWithTag("native-background-option-01").assertIsSelected()
        compose.onNodeWithTag("native-background-option-default").performScrollTo().performClick()
        compose.onNodeWithTag("native-background-apply").performClick()
        awaitBackgroundSave()
        assertEquals("default", savedProfile(id).getJSONObject("chatBackground").getString("type"))
        back(); awaitTag("native-companion-profile")
    }

    private fun inspectCreation() {
        compose.onNodeWithTag("native-add-companion").performClick()
        compose.waitUntil(20_000) { creation.state.value.runtime.ready && creation.state.value.catalogLoaded && !creation.state.value.loading }
        awaitTag("native-creation-template-maimai")
        compose.onNodeWithTag("native-creation-template-kaka").assertIsDisplayed()
        capture("templates")
        compose.onNodeWithTag("native-creation-template-maimai").performClick()
        awaitTag("native-creation-name")
        compose.waitUntil(20_000) { creation.state.value.canCreate }
        capture("creation-upper")
        compose.onNodeWithTag("native-creation-interaction").performScrollTo().assertIsDisplayed()
        capture("creation-lower")
        // Creation uses the same editor while its changes remain in the creation draft.
        compose.onNodeWithTag("native-creation-personality").performScrollTo().performClick()
        awaitTag("native-companion-personality-input")
        compose.onNodeWithTag("native-companion-personality-save").assertIsNotEnabled()
        compose.onNodeWithTag("native-companion-behavior-input").assertExists()
        compose.onNodeWithTag("native-companion-reply-style-input").assertExists()
        back(); awaitTag("native-creation-name")
        back()
        compose.waitUntil(10_000) { exists("native-creation-template-maimai") || dialogExists(R.string.native_companion_creation_discard_draft) }
        if (dialogExists(R.string.native_companion_creation_discard_draft)) clickDialog(R.string.native_companion_creation_discard_draft)
        awaitTag("native-creation-template-maimai")
        back(); awaitTag("native-companion-messages")
    }

    private fun openBackground() {
        compose.onNodeWithTag("native-companion-background").performScrollTo().performClick()
        awaitTag("native-background-apply")
    }
    private fun awaitBackgroundSave() {
        compose.waitUntil(20_000) { !exists("native-background-apply") && !companions.state.value.profileSaving }
        assertNull(companions.state.value.error)
        awaitTag("native-companion-interaction-save")
    }
    private fun dialogMatcher(resource: Int): SemanticsMatcher {
        val localized = fixture.context.createConfigurationContext(Configuration(fixture.context.resources.configuration)
            .apply { setLocale(Locale.forLanguageTag(language)) })
        return hasText(localized.getString(resource)) and hasAnyAncestor(isDialog())
    }
    private fun dialogExists(resource: Int) = compose.onAllNodes(dialogMatcher(resource)).fetchSemanticsNodes(false).isNotEmpty()
    private fun clickDialog(resource: Int) {
        compose.waitUntil(10_000) { dialogExists(resource) }
        compose.onNode(dialogMatcher(resource)).performClick()
        settle()
    }
    private fun savedProfile(id: String): JSONObject = request().getJSONArray("companions").let { rows ->
        (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == id }
    }
    private fun request(path: String = "", method: String = "GET", body: JSONObject? = null): JSONObject = runBlocking {
        fixture.runtimeTransport.json("/droy/companions$path", method, body)
    }
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty()
    private fun awaitTag(tag: String) { compose.waitUntil(20_000) { exists(tag) }; settle() }
    private fun back() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        settle()
    }
    private fun settle() { compose.waitForIdle(); SystemClock.sleep(400); compose.waitForIdle() }
    private fun capture(scene: String) { settle(); fixture.capture("batch7-$scene-$language") }
}
