package io.droyagent.app

import android.content.res.Configuration
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.memory.NativeMemoryPage
import io.droyagent.app.nativeui.memory.NativeMemoryViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import io.droyagent.app.nativeui.voice.NativeVoiceViewModel
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Visual pass over reachable settings; hidden memory management is intentionally excluded. */
@RunWith(AndroidJUnit4::class)
class NativeBatchFourGalleryDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val memoryFiles = IsolatedMemoryFiles()
    @get:Rule(order = 2) val fixture = NativeIsolatedRuntimeFixture("settings", allowRoot = false)
    private lateinit var settings: NativeSettingsViewModel
    private lateinit var memory: NativeMemoryViewModel
    private lateinit var voice: NativeVoiceViewModel
    private var language = "en"

    @Test fun captureRelatedSettingsAndDetailsWithoutActivatingDeviceCapabilities() {
        fixture.scenario.onActivity {
            val models = ViewModelProvider(it)
            settings = models[NativeSettingsViewModel::class.java]
            memory = models[NativeMemoryViewModel::class.java]
            voice = models[NativeVoiceViewModel::class.java]
        }
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.writable && settings.state.value.runtime.ready }
        for (locale in listOf("en", "zh")) {
            language = locale
            fixture.scenario.onActivity { settings.setLanguage(locale) }
            compose.waitUntil(20_000) { settings.state.value.locale == locale && NativeLocale.active() == locale && !settings.state.value.saving }
            assertNull(settings.state.value.error)
            entry("permissions", "native-permissions-content")
            compose.onNodeWithTag("native-permissions-set-up-all").performScrollTo().assertIsDisplayed()
            capture("permissions")
            // Opening and cancelling the batch confirmation must not request native permissions.
            compose.onNodeWithTag("native-permissions-set-up-all").performClick()
            compose.onNodeWithTag("native-permissions-confirm:batch").assertIsDisplayed()
            compose.onNode(hasText(text(R.string.native_permissions_cancel)) and hasAnyAncestor(isDialog())).performClick()
            compose.onNodeWithTag("native-permissions-confirm:batch").assertDoesNotExist()
            compose.onNodeWithTag("native-permissions-back").performClick()
            awaitTag("native-settings-home-list")

            entry("memory", "native-memory-settings")
            compose.waitUntil(20_000) { memory.state.value.status != null && !memory.state.value.loading }
            assertNull(memory.state.value.statusError)
            capture("memory")
            compose.onNodeWithTag("native-memory-open-capture").performScrollTo().performClick()
            awaitTag("native-memory-capture")
            val captureConfig = requireNotNull(memory.state.value.status?.capture?.configuration)
            assertFalse(captureConfig.enabled)
            assertFalse(captureConfig.activityEnabled)
            assertFalse(captureConfig.pageTextEnabled)
            assertFalse(captureConfig.deviceStateEnabled)
            assertFalse(captureConfig.locationEnabled)
            capture("phone-recording-settings")
            back(); awaitTag("native-memory-settings")
            back(); awaitTag("native-settings-home-list")

            entry("voice", "native-voice")
            compose.waitUntil(20_000) { voice.state.value.fresh && voice.state.value.snapshot != null && !voice.state.value.loading }
            compose.onNodeWithTag("native-voice-open-readout").assertIsDisplayed()
            capture("voice")
            compose.onNodeWithTag("native-voice-open-readout").performClick()
            awaitTag("native-voice-save-tts")
            compose.onNodeWithTag("native-voice-save-tts").assertIsNotEnabled()
            capture("readout")
            compose.onNode(hasText(text(R.string.native_voice_provider)) and hasClickAction()).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text(R.string.native_voice_melo)).fetchSemanticsNodes(false).isNotEmpty() }
            capture("readout-providers")
            back(); awaitTag("native-voice-save-tts")
            back(); awaitTag("native-voice-open-wake")
            compose.onNodeWithTag("native-voice-open-wake").performClick()
            awaitTag("native-voice-save-wake")
            compose.onNodeWithTag("native-voice-save-wake").assertIsNotEnabled()
            capture("wake")
            back(); awaitTag("native-voice-open-readout")
            back(); awaitTag("native-settings-home-list")
        }
    }

    private fun entry(name: String, tag: String) {
        compose.onNodeWithTag("native-settings-$name").performScrollTo().performClick()
        awaitTag(tag)
    }
    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty() }
        compose.waitForIdle()
    }
    private fun text(id: Int) = fixture.context.createConfigurationContext(
        Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
    ).getString(id)
    private fun back() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(300)
        compose.waitForIdle()
    }
    private fun capture(scene: String) {
        compose.waitForIdle()
        SystemClock.sleep(400)
        fixture.capture("batch4-$scene-$language")
    }
}
