package io.droyagent.app

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
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated local screenshots; model choices are saved without sending messages or running models. */
@RunWith(AndroidJUnit4::class)
class NativeBatchSixCorrectionDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings", allowRoot = false)
    private lateinit var settings: NativeSettingsViewModel
    private lateinit var creation: NativeCompanionCreationViewModel
    private lateinit var companions: NativeCompanionViewModel
    private var language = "en"
    private val modelSearch = "native-companion-model-search"
    private val alternate = "native-companion-model-option:droy-native-fixture:native-alternate"
    private val local = "native-companion-model-option:droy-native-fixture:native-local"
    private val inherited = "native-companion-model-default"

    @Test fun captureCenteredAvatarAndPersistOfficialModelSelection() {
        fixture.scenario.onActivity {
            val models = ViewModelProvider(it)
            settings = models[NativeSettingsViewModel::class.java]
            creation = models[NativeCompanionCreationViewModel::class.java]
            companions = models[NativeCompanionViewModel::class.java]
        }
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.writable && settings.state.value.runtime.ready }
        assertTrue(fixture.context.getSharedPreferences("product", 0).edit().putString("appearanceMode", "light").commit())
        request("/user-profile", "PATCH", JSONObject().put("name", "DroyReview").put("bio", ""))
        val profile = request(method = "POST", body = JSONObject()
            .put("requestId", UUID.randomUUID().toString()).put("templateId", "maimai").put("name", "M3E Review")
            .put("model", model("native-alternate"))
            .put("settings", JSONObject().put("proactiveEnabled", false).put("memoryEnabled", true).put("allowProfanity", true)
                .put("quietHours", JSONObject().put("enabled", true).put("start", "22:00").put("end", "09:00"))))
            .getJSONObject("profile")
        val id = profile.getString("id")
        fixture.scenario.onActivity { companions.refresh() }
        compose.waitUntil(20_000) { companions.state.value.companions.any { it.id == id } && companions.state.value.models.isNotEmpty() && companions.state.value.userProfile.name == "DroyReview" }

        for (locale in listOf("en", "zh")) {
            language = locale
            fixture.scenario.onActivity { settings.setLanguage(locale) }
            compose.waitUntil(20_000) { settings.state.value.locale == locale && NativeLocale.active() == locale && !settings.state.value.saving }
            assertNull(settings.state.value.error)
            compose.onNodeWithTag("native-settings-my-profile").performScrollTo().performClick()
            awaitTag("native-user-profile")
            compose.onNodeWithTag("native-user-profile-save").assertIsNotEnabled()
            captureCorrection("user-profile")
            back(); awaitTag("native-settings-home-list")
            compose.onNodeWithTag("native-tab-messages").performClick()
            awaitTag("native-companion-conversation:$id")
            compose.onNodeWithTag("native-companion-conversation:$id").performClick()
            awaitTag("native-open-companion-profile")
            compose.onNodeWithTag("native-open-companion-profile").performClick()
            awaitTag("native-companion-profile")

            openModel()
            compose.onNodeWithTag(alternate).assertIsSelected().assertIsDisplayed()
            captureCorrection("model-picker-selected")
            compose.onNodeWithTag(local).performScrollTo().performClick()
            awaitSavedPicker()
            assertEquals("native-local", savedProfile(id).getJSONObject("model").getString("model"))
            openModel()
            compose.onNodeWithTag(local).assertIsSelected()
            compose.onNodeWithTag(alternate).assertIsNotSelected()
            captureCorrection("model-picker-switched")
            compose.onNodeWithTag(inherited).performClick()
            awaitSavedPicker()
            assertTrue(savedProfile(id).getBoolean("modelInherited"))
            openModel()
            compose.onNodeWithTag(inherited).assertIsSelected()
            compose.onNodeWithTag(local).assertIsNotSelected()
            captureCorrection("model-picker-default")
            // Restore the same fictional choice for the next language's comparison.
            compose.onNodeWithTag(alternate).performScrollTo().performClick()
            awaitSavedPicker()
            assertEquals("native-alternate", savedProfile(id).getJSONObject("model").getString("model"))

            if (language == "zh") captureNextSettings()
            back(); awaitTag("native-companion-chat")
            back(); awaitTag("native-companion-messages")
            if (language == "zh") captureNextCreation()
            compose.onNodeWithTag("native-tab-settings").performClick()
            awaitTag("native-settings-home-list")
        }
        assertFalse(savedProfile(id).getJSONObject("settings").getBoolean("proactiveEnabled"))
        for (name in listOf("native-fixture-calls.jsonl", "native-fixture-companion.jsonl")) {
            val calls = File(fixture.home, name)
            assertTrue("Screenshot review must make zero model calls: $name", !calls.exists() || calls.length() == 0L)
        }
    }

    private fun captureNextSettings() {
        compose.onNodeWithTag("native-companion-personality").performScrollTo().performClick()
        awaitTag("native-companion-personality-input")
        compose.onNodeWithTag("native-companion-personality-save").assertIsNotEnabled()
        captureBefore("personality-upper")
        compose.onNodeWithTag("native-companion-reply-style-input").performScrollTo().assertIsDisplayed()
        captureBefore("personality-lower")
        back(); awaitTag("native-companion-profile")
        compose.onNodeWithTag("native-companion-interaction").performScrollTo().performClick()
        awaitTag("native-companion-interaction-save")
        compose.onNodeWithTag("native-companion-background").performScrollTo().performClick()
        awaitTag("native-background-apply")
        captureBefore("background")
        back(); awaitTag("native-companion-interaction-save")
        back(); awaitTag("native-companion-profile")
    }

    private fun captureNextCreation() {
        compose.onNodeWithTag("native-add-companion").performClick()
        compose.waitUntil(20_000) { creation.state.value.runtime.ready && creation.state.value.catalogLoaded && !creation.state.value.loading }
        awaitTag("native-creation-template-maimai")
        captureBefore("templates")
        compose.onNodeWithTag("native-creation-template-maimai").performClick()
        awaitTag("native-creation-name")
        compose.waitUntil(20_000) { creation.state.value.canCreate }
        captureBefore("creation")
        compose.onNodeWithTag("native-creation-interaction").performScrollTo().assertIsDisplayed()
        captureBefore("creation-lower")
        back()
        val discardDraft = hasText(fixture.context.createConfigurationContext(android.content.res.Configuration(fixture.context.resources.configuration)
            .apply { setLocale(java.util.Locale.forLanguageTag("zh")) }).getString(R.string.native_companion_creation_discard_draft)) and hasAnyAncestor(isDialog())
        compose.waitUntil(10_000) { exists("native-creation-template-maimai") || compose.onAllNodes(discardDraft).fetchSemanticsNodes(false).isNotEmpty() }
        if (compose.onAllNodes(discardDraft).fetchSemanticsNodes(false).isNotEmpty()) compose.onNode(discardDraft).performClick()
        awaitTag("native-creation-template-maimai")
        back(); awaitTag("native-companion-messages")
    }

    private fun openModel() {
        compose.onNodeWithTag("native-companion-model").performScrollTo().performClick()
        awaitTag(modelSearch)
    }
    private fun awaitSavedPicker() {
        compose.waitUntil(20_000) { !exists(modelSearch) && !companions.state.value.profileSaving }
        assertNull(companions.state.value.error)
        awaitTag("native-companion-profile")
    }
    private fun model(id: String) = JSONObject().put("provider", "droy-native-fixture").put("model", id)
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
    private fun captureCorrection(scene: String) { settle(); fixture.capture("batch6-correction-$scene-$language") }
    private fun captureBefore(scene: String) { settle(); fixture.capture("batch7-before-$scene-zh") }
}
