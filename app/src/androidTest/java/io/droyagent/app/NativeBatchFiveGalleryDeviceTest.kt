package io.droyagent.app

import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import io.droyagent.app.nativeui.voice.NativeVoiceViewModel
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real visible routes only, using preserved local configuration; no audio, sorting or permission activation. */
@RunWith(AndroidJUnit4::class)
class NativeBatchFiveGalleryDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings", allowRoot = false)
    private lateinit var settings: NativeSettingsViewModel
    private lateinit var voice: NativeVoiceViewModel
    private var language = "en"
    private val host get() = NativeProductSettingsHost(fixture.context)

    @Test fun captureVisibleProductSettingsAndDiscardWakeTimingDraft() {
        fixture.scenario.onActivity {
            val models = ViewModelProvider(it)
            settings = models[NativeSettingsViewModel::class.java]
            voice = models[NativeVoiceViewModel::class.java]
        }
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.writable && settings.state.value.runtime.ready }
        // Both actual pet display surfaces stay off; the isolated settings choice remains inspectable.
        assertTrue(fixture.context.getSharedPreferences("product", 0).edit()
            .putBoolean("petEnabled", true).putString("petExternalDisplay", "off")
            .putString("petInternalDisplay", "off").putString("appearanceMode", "light").commit())
        val notificationBefore = host.snapshot("notifications")
        val enabledBefore = notificationBefore.getBoolean("enabled")

        for (locale in listOf("en", "zh")) {
            language = locale
            fixture.scenario.onActivity { settings.setLanguage(locale) }
            compose.waitUntil(20_000) { settings.state.value.locale == locale && NativeLocale.active() == locale && !settings.state.value.saving }
            assertNull(settings.state.value.error)
            val ruleBefore = host.snapshot("notifications").getString("ruleText")
            entry("notifications", "native-product-body:notifications")
            notificationEntry("nps_app_management")
            compose.onNodeWithText(text(R.string.nps_app_management)).assertIsDisplayed()
            capture("notification-apps")
            productBack(); awaitTag("native-product-field:nps_rules")
            notificationEntry("nps_rules")
            compose.onNodeWithText(text(R.string.nps_restore_default)).assertIsDisplayed()
            capture("notification-rules")
            back()
            compose.onNodeWithText(text(R.string.nps_restore_default)).assertDoesNotExist()
            awaitTag("native-product-body:notifications")
            assertEquals(ruleBefore, host.snapshot("notifications").getString("ruleText"))
            notificationEntry("nps_archive")
            compose.onNodeWithText(text(R.string.nps_archive)).assertIsDisplayed()
            capture("notification-history")
            productBack(); awaitTag("native-product-field:nps_rules")
            productBack(); awaitTag("native-settings-home-list")

            entry("pet", "native-product-body:pet")
            compose.onNodeWithTag("native-product-pet-preview").assertIsDisplayed()
            capture("pet")
            compose.onNodeWithTag("native-product-list:pet").performScrollToNode(hasTestTag("native-product-field:nps_external"))
            compose.onNodeWithTag("native-product-field:nps_external").assertIsEnabled().performClick()
            awaitTag("native-product-choice:adaptive")
            capture("pet-display-choice")
            back(); awaitTag("native-product-body:pet")
            assertEquals("off", host.snapshot("pet").getString("petExternalDisplay"))
            assertEquals("off", host.snapshot("pet").getString("petInternalDisplay"))
            productBack(); awaitTag("native-settings-home-list")

            entry("about", "native-product-body:diagnostics")
            compose.onNodeWithTag("native-product-version").assertIsDisplayed()
            compose.onNodeWithTag("native-product-diagnostics-text").assertDoesNotExist()
            capture("about")
            productBack(); awaitTag("native-settings-home-list")

            entry("voice", "native-voice")
            compose.waitUntil(20_000) { voice.state.value.fresh && voice.state.value.snapshot != null && !voice.state.value.loading }
            compose.onNodeWithTag("native-voice-open-readout").performClick()
            awaitTag("native-voice-save-tts")
            compose.onNode(hasText(text(R.string.native_voice_provider)) and hasClickAction()).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text(R.string.native_voice_melo)).fetchSemanticsNodes(false).isNotEmpty() }
            capture("voice-providers")
            back(); awaitTag("native-voice-save-tts")
            back(); awaitTag("native-voice-open-wake")
            compose.onNodeWithTag("native-voice-open-wake").performClick()
            awaitTag("native-voice-timing:screen_on")
            val originalTiming = requireNotNull(voice.state.value.wakeDraft).timing
            val originalSaved = requireNotNull(voice.state.value.snapshot).savedWake
            compose.onNodeWithTag("native-voice-save-wake").assertIsNotEnabled()
            capture("wake")
            val otherTiming = if (originalTiming == "screen_on") "foreground" else "screen_on"
            compose.onNodeWithTag("native-voice-timing:$otherTiming").performClick()
            compose.waitUntil(10_000) { voice.state.value.wakeDraft?.timing == otherTiming }
            compose.onNodeWithTag("native-voice-save-wake").assertIsEnabled()
            back()
            compose.onNode(hasText(text(R.string.native_voice_discard)) and hasAnyAncestor(isDialog())).performClick()
            awaitTag("native-voice-open-wake")
            assertEquals(originalSaved, voice.state.value.snapshot?.savedWake)
            compose.onNodeWithTag("native-voice-open-wake").performClick()
            awaitTag("native-voice-timing:$originalTiming")
            assertEquals(originalTiming, voice.state.value.wakeDraft?.timing)
            compose.onNodeWithTag("native-voice-save-wake").assertIsNotEnabled()
            back(); awaitTag("native-voice-open-readout")
            back(); awaitTag("native-settings-home-list")
            assertEquals(ruleBefore, host.snapshot("notifications").getString("ruleText"))
        }
        assertEquals(enabledBefore, host.snapshot("notifications").getBoolean("enabled"))
    }

    private fun notificationEntry(field: String) {
        compose.onNodeWithTag("native-product-list:notifications").performScrollToNode(hasTestTag("native-product-field:$field"))
        compose.onNodeWithTag("native-product-field:$field").performClick()
        settle()
    }
    private fun entry(name: String, tag: String) {
        compose.onNodeWithTag("native-settings-$name").performScrollTo().performClick()
        awaitTag(tag)
    }
    private fun productBack() {
        compose.onNodeWithTag("native-product-back").performClick()
        settle()
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
    private fun capture(scene: String) { settle(); fixture.capture("batch5-$scene-$language") }
}
