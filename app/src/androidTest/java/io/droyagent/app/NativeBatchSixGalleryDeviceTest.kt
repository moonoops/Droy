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
import io.droyagent.app.nativeui.voice.NativeVoiceViewModel
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Visible settings with a fictional local companion. No message, model call, audio or permission action. */
@RunWith(AndroidJUnit4::class)
class NativeBatchSixGalleryDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings", allowRoot = false)
    private lateinit var settings: NativeSettingsViewModel
    private lateinit var creation: NativeCompanionCreationViewModel
    private lateinit var companions: NativeCompanionViewModel
    private lateinit var voice: NativeVoiceViewModel
    private var language = "en"

    @Test fun captureProfilesModelAndInteractionWithDiscardedDraft() {
        fixture.scenario.onActivity {
            val models = ViewModelProvider(it)
            settings = models[NativeSettingsViewModel::class.java]
            creation = models[NativeCompanionCreationViewModel::class.java]
            companions = models[NativeCompanionViewModel::class.java]
            voice = models[NativeVoiceViewModel::class.java]
        }
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.writable && settings.state.value.runtime.ready }
        assertTrue(fixture.context.getSharedPreferences("product", 0).edit().putString("appearanceMode", "light").commit())
        request("/user-profile", "PATCH", JSONObject().put("name", "DroyReview").put("bio", ""))
        val profile = request(method = "POST", body = JSONObject()
            .put("requestId", UUID.randomUUID().toString()).put("templateId", "maimai").put("name", "M3E Review")
            .put("model", JSONObject().put("provider", "droy-native-fixture").put("model", "native-alternate"))
            .put("settings", JSONObject().put("proactiveEnabled", false).put("memoryEnabled", true).put("allowProfanity", true)
                .put("quietHours", JSONObject().put("enabled", true).put("start", "22:00").put("end", "09:00"))))
            .getJSONObject("profile")
        val companionId = profile.getString("id")
        fixture.scenario.onActivity { companions.refresh() }
        compose.waitUntil(20_000) { companions.state.value.companions.any { it.id == companionId } && companions.state.value.models.isNotEmpty() && companions.state.value.userProfile.name == "DroyReview" }

        for (locale in listOf("en", "zh")) {
            language = locale
            fixture.scenario.onActivity { settings.setLanguage(locale) }
            compose.waitUntil(20_000) { settings.state.value.locale == locale && NativeLocale.active() == locale && !settings.state.value.saving }
            assertNull(settings.state.value.error)
            settingsEntry("my-profile", "native-user-profile")
            compose.onNodeWithTag("native-user-profile-save").assertIsNotEnabled()
            capture("user-profile")
            back(); awaitTag("native-settings-home-list")

            compose.onNodeWithTag("native-tab-messages").performClick()
            awaitTag("native-companion-conversation:$companionId")
            compose.onNodeWithTag("native-companion-conversation:$companionId").performClick()
            awaitTag("native-open-companion-profile")
            compose.onNodeWithTag("native-open-companion-profile").performClick()
            awaitTag("native-companion-profile")
            compose.onNodeWithTag("native-companion-profile-save").assertIsNotEnabled()
            capture("companion-profile")
            compose.onNodeWithTag("native-companion-model").performScrollTo().performClick()
            awaitTag("native-companion-model-search")
            compose.onNodeWithTag("native-companion-model-option:droy-native-fixture:native-alternate").assertIsDisplayed()
            capture("model-picker")
            back(); awaitTag("native-companion-profile")
            assertEquals("native-alternate", savedProfile(companionId).getJSONObject("model").getString("model"))

            compose.onNodeWithTag("native-companion-interaction").performScrollTo().performClick()
            awaitTag("native-companion-interaction-save")
            compose.onNodeWithTag("native-companion-interaction-save").assertIsNotEnabled()
            val savedSettings = savedProfile(companionId).getJSONObject("settings").toString()
            capture("interaction")
            compose.onNodeWithTag("native-companion-quiet-start").performScrollTo().performClick()
            compose.onNode(hasText(text(R.string.native_companion_cancel)) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            capture("quiet-time")
            compose.onNode(hasText(text(R.string.native_companion_cancel)) and hasAnyAncestor(isDialog())).performClick()
            awaitTag("native-companion-interaction-save")
            compose.onNodeWithTag("native-companion-interaction-save").assertIsNotEnabled()
            compose.onNodeWithText(text(R.string.native_companion_civil_language)).performScrollTo().performClick()
            compose.onNodeWithTag("native-companion-interaction-save").assertIsEnabled()
            back()
            compose.onNode(hasText(text(R.string.native_companion_discard)) and hasAnyAncestor(isDialog())).performClick()
            awaitTag("native-companion-profile")
            assertEquals(savedSettings, savedProfile(companionId).getJSONObject("settings").toString())
            compose.onNodeWithTag("native-companion-interaction").performScrollTo().performClick()
            awaitTag("native-companion-interaction-save")
            compose.onNodeWithTag("native-companion-interaction-save").assertIsNotEnabled()
            back(); awaitTag("native-companion-profile")
            back(); awaitTag("native-companion-chat")
            back(); awaitTag("native-companion-messages")
            // The same model list is also hosted by creation; inspect it without submitting a companion.
            compose.onNodeWithTag("native-add-companion").performClick()
            compose.waitUntil(20_000) { creation.state.value.runtime.ready && creation.state.value.catalogLoaded && !creation.state.value.loading }
            awaitTag("native-creation-template-kaka")
            compose.onNodeWithTag("native-creation-template-kaka").performClick()
            awaitTag("native-creation-name")
            compose.waitUntil(20_000) { creation.state.value.canCreate }
            compose.onNodeWithTag("native-creation-model").performScrollTo().performClick()
            awaitTag("native-companion-model-search")
            compose.onNodeWithTag("native-companion-model-option:droy-native-fixture:native-alternate").assertIsDisplayed()
            capture("creation-model")
            back(); awaitTag("native-creation-name")
            back()
            val discardDraft = hasText(text(R.string.native_companion_creation_discard_draft)) and hasAnyAncestor(isDialog())
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-creation-template-kaka").fetchSemanticsNodes(false).isNotEmpty() || compose.onAllNodes(discardDraft).fetchSemanticsNodes(false).isNotEmpty() }
            if (compose.onAllNodes(discardDraft).fetchSemanticsNodes(false).isNotEmpty()) compose.onNode(discardDraft).performClick()
            awaitTag("native-creation-template-kaka")
            back(); awaitTag("native-companion-messages")
            compose.onNodeWithTag("native-tab-settings").performClick()
            awaitTag("native-settings-home-list")

            settingsEntry("voice", "native-voice")
            compose.waitUntil(20_000) { voice.state.value.fresh && voice.state.value.snapshot != null && !voice.state.value.loading }
            compose.onNodeWithTag("native-voice-open-wake").performClick()
            awaitTag("native-voice-timing:screen_on")
            assertFalse(voice.state.value.snapshot?.actualWake?.enabled == true)
            compose.onNodeWithText(text(R.string.native_voice_off)).assertDoesNotExist()
            capture("wake")
            back(); awaitTag("native-voice-open-readout")
            back(); awaitTag("native-settings-home-list")
        }
        assertFalse(savedProfile(companionId).getJSONObject("settings").getBoolean("proactiveEnabled"))
        for (name in listOf("native-fixture-calls.jsonl", "native-fixture-companion.jsonl")) {
            val calls = File(fixture.home, name)
            assertTrue("Gallery must make zero model calls: $name", !calls.exists() || calls.length() == 0L)
        }
    }

    private fun savedProfile(id: String): JSONObject = request().getJSONArray("companions").let { rows ->
        (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == id }
    }
    private fun request(path: String = "", method: String = "GET", body: JSONObject? = null): JSONObject = runBlocking {
        fixture.runtimeTransport.json("/droy/companions$path", method, body)
    }
    private fun settingsEntry(name: String, tag: String) {
        compose.onNodeWithTag("native-settings-$name").performScrollTo().performClick()
        awaitTag(tag)
    }
    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty() }
        settle()
    }
    private fun text(id: Int) = fixture.context.createConfigurationContext(
        Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
    ).getString(id)
    private fun back() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        settle()
    }
    private fun settle() { compose.waitForIdle(); SystemClock.sleep(400); compose.waitForIdle() }
    private fun capture(scene: String) { settle(); fixture.capture("batch6-$scene-$language") }
}
