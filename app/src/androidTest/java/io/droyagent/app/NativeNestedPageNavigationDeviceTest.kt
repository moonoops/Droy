package io.droyagent.app

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import io.droyagent.app.nativeui.setup.NativeOnboardingViewModel
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/** Real navigation controls, paused transition frames, and no model or device-capability requests. */
@RunWith(AndroidJUnit4::class)
class NativeNestedPageNavigationDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings", allowRoot = false)
    @get:Rule(order = 2) val failureEvidence = object : TestWatcher() {
        override fun failed(failure: Throwable, description: Description) {
            try { fixture.capture("nested-failed-$language-${description.methodName}") }
            catch (captureFailure: Throwable) { failure.addSuppressed(captureFailure) }
        }
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val language get() = InstrumentationRegistry.getArguments().getString("nativeFixtureLocale", "en")
    private lateinit var settings: NativeSettingsViewModel
    private lateinit var companions: NativeCompanionViewModel
    private lateinit var setup: NativeOnboardingViewModel

    @Test fun companionNotificationMemoryAndVoicePagesReturnThroughTheirActualParents() {
        bind()
        awaitSettings()
        compose.waitUntil(20_000) { companions.state.value.runtime.ready && companions.state.value.companions.size == 1 }
        val companion = companions.state.value.companions.single()
        compose.onNodeWithTag("native-tab-messages").performClick()
        awaitTag("native-companion-conversation:${companion.id}")
        compose.onNodeWithTag("native-companion-conversation:${companion.id}").performClick()
        awaitTag("native-companion-chat")
        compose.onNodeWithTag("native-open-companion-profile").performClick()
        awaitTag("native-companion-profile")
        compose.onNodeWithTag("native-companion-interaction").performScrollTo()
        transition("profile-interaction", action = {
            compose.onNodeWithTag("native-companion-interaction").performClick()
        }) { compose.onNodeWithTag("native-companion-interaction-save").assertIsDisplayed() }
        compose.onNodeWithTag("native-companion-background").performScrollTo()
        transition("interaction-background", action = {
            compose.onNodeWithTag("native-companion-background").performClick()
        }) { compose.onNodeWithTag("native-background-apply").assertIsDisplayed() }
        transition("background-interaction", action = ::androidBack) {
            compose.onNodeWithTag("native-companion-interaction-save").assertIsDisplayed()
            compose.onNodeWithTag("native-background-apply").assertDoesNotExist()
        }
        transition("interaction-profile", action = ::androidBack) {
            compose.onNodeWithTag("native-companion-profile").assertIsDisplayed()
            compose.onNodeWithTag("native-companion-interaction-save").assertDoesNotExist()
        }
        androidBack(); awaitTag("native-companion-chat")
        androidBack(); awaitTag("native-tab-settings")
        compose.onNodeWithTag("native-tab-settings").performClick()
        awaitTag("native-settings-home-list")

        compose.onNodeWithTag("native-settings-notifications").performScrollTo().performClick()
        awaitTag("native-product-body:notifications")
        for ((field, childTag, title) in listOf(
            Triple("nps_app_management", "native-notification-app-search", R.string.nps_app_management),
            Triple("nps_archive", null, R.string.nps_archive),
        )) {
            compose.onNodeWithTag("native-product-list:notifications")
                .performScrollToNode(hasTestTag("native-product-field:$field"))
            transition("notification-$field", action = {
                compose.onNodeWithTag("native-product-field:$field").performClick()
            }) {
                compose.onNodeWithText(localized(title)).assertIsDisplayed()
                childTag?.let { compose.onNodeWithTag(it).assertIsDisplayed() }
                compose.onNodeWithTag("native-notification-delivery").assertDoesNotExist()
            }
            transition("notification-$field-return", action = {
                compose.onNodeWithTag("native-product-back").performClick()
            }) {
                compose.onNodeWithTag("native-product-body:notifications").assertIsDisplayed()
                compose.onNodeWithTag("native-product-field:$field").assertIsDisplayed()
                childTag?.let { compose.onNodeWithTag(it).assertDoesNotExist() }
            }
        }
        compose.onNodeWithTag("native-product-back").performClick()
        awaitTag("native-settings-home-list")

        compose.onNodeWithTag("native-settings-memory").performScrollTo().performClick()
        awaitTag("native-memory-settings")
        compose.onNodeWithTag("native-memory-open-capture").performScrollTo()
        transition("memory-capture", action = {
            compose.onNodeWithTag("native-memory-open-capture").performClick()
        }) { compose.onNodeWithTag("native-memory-capture").assertIsDisplayed() }
        transition("memory-capture-return", action = ::androidBack) {
            compose.onNodeWithTag("native-memory-settings").assertIsDisplayed()
            compose.onNodeWithTag("native-memory-capture").assertDoesNotExist()
        }
        androidBack(); awaitTag("native-settings-home-list")

        compose.onNodeWithTag("native-settings-voice").performScrollTo().performClick()
        awaitTag("native-voice-open-readout")
        transition("voice-readout", action = {
            compose.onNodeWithTag("native-voice-open-readout").performClick()
        }) { compose.onNodeWithTag("native-voice-save-tts").assertIsDisplayed() }
        transition("voice-readout-return", action = ::androidBack) {
            compose.onNodeWithTag("native-voice-open-readout").assertIsDisplayed()
            compose.onNodeWithTag("native-voice-save-tts").assertDoesNotExist()
        }
        androidBack(); awaitTag("native-settings-home-list")
        val unchanged = companions.state.value.companions.single()
        assertEquals(companion.id, unchanged.id)
        assertEquals(companion.name, unchanged.name)
        assertEquals(companion.settings, unchanged.settings)
        assertEquals(companion.chatBackgroundJson, unchanged.chatBackgroundJson)
        assertNoModelCalls()
    }

    @Test fun onboardingMovesForwardAndBackWithoutSkippingModelVerification() {
        bind()
        awaitSettings()
        assertTrue(fixture.context.getSharedPreferences("installation", 0).edit()
            .putBoolean("completed", false).putInt("nativeWizardStep", 1).remove("nativeUserConfirmation").commit())
        assertTrue(fixture.context.getSharedPreferences("native_setup_draft", 0).edit().clear().commit())
        fixture.relaunch("messages")
        bind()
        awaitStep(1)
        awaitTag("native-onboarding")
        transition("setup-1-2", started = { setup.state.value.route?.step == 2 && !setup.state.value.busy }, action = {
            compose.onNodeWithTag("native-setup-next").assertIsEnabled().performClick()
        }) { assertEquals(2, setup.state.value.route?.step) }
        compose.waitUntil(20_000) { setup.state.value.route?.environment?.ready == true }
        transition("setup-2-3", started = { setup.state.value.route?.step == 3 && !setup.state.value.busy }, action = {
            compose.onNodeWithTag("native-setup-next").assertIsEnabled().performClick()
        }) {
            assertEquals(3, setup.state.value.route?.step)
            compose.onNodeWithTag("native-settings-add-provider").assertIsDisplayed()
            compose.onNodeWithTag("native-setup-next").assertIsNotEnabled()
        }
        transition("setup-3-2", started = { setup.state.value.route?.step == 2 && !setup.state.value.busy }, action = {
            compose.onNodeWithTag("native-setup-back").assertIsEnabled().performClick()
        }) { assertEquals(2, setup.state.value.route?.step) }
        transition("setup-2-1", started = { setup.state.value.route?.step == 1 && !setup.state.value.busy }, action = {
            compose.onNodeWithTag("native-setup-back").assertIsEnabled().performClick()
        }) {
            assertEquals(1, setup.state.value.route?.step)
            compose.onNodeWithTag("native-setup-back").assertDoesNotExist()
            compose.onNodeWithTag("native-setup-next").assertIsEnabled()
        }
        assertFalse(fixture.context.getSharedPreferences("installation", 0).getBoolean("completed", false))
        assertNoModelCalls()
    }

    private fun bind() {
        fixture.scenario.onActivity {
            val models = ViewModelProvider(it)
            settings = models[NativeSettingsViewModel::class.java]
            companions = models[NativeCompanionViewModel::class.java]
            setup = models[NativeOnboardingViewModel::class.java]
        }
    }
    private fun awaitSettings() {
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.writable && settings.state.value.runtime.ready }
        awaitTag("native-settings-home-list")
        assertEquals(language, NativeLocale.active())
    }
    private fun awaitStep(step: Int) {
        compose.waitUntil(20_000) { setup.state.value.route?.step == step && !setup.state.value.busy }
        compose.waitForIdle()
    }
    private fun awaitTag(tag: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(false).isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun transition(name: String, started: (() -> Boolean)? = null, action: () -> Unit, settled: () -> Unit) {
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            action()
            started?.let { compose.waitUntil(20_000, it) }
            for ((index, advance) in listOf(32L, 64L, 80L).withIndex()) {
                compose.mainClock.advanceTimeBy(advance)
                compose.waitForIdle()
                fixture.capture("nested-$language-$name-frame-$index")
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        settled()
        fixture.capture("nested-$language-$name-settled")
    }
    private fun androidBack() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) }
    private fun localized(resource: Int) = fixture.context.createConfigurationContext(
        Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
    ).getString(resource)
    private fun assertNoModelCalls() {
        for (name in listOf("native-fixture-calls.jsonl", "native-fixture-companion.jsonl")) {
            val calls = File(fixture.home, name)
            assertTrue("Navigation must not call models", !calls.exists() || calls.length() == 0L)
        }
    }
}
