package io.droyagent.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local isolated settings only: no provider discovery, credentials, or model calls. */
@RunWith(AndroidJUnit4::class)
class NativeProfileOfficialProviderDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings")

    @Test fun officialDeepSeekUsesItsNameWithoutCustomEndpointOrProtocol() {
        lateinit var settings: NativeSettingsViewModel
        fixture.scenario.onActivity { settings = ViewModelProvider(it)[NativeSettingsViewModel::class.java] }
        fixture.startRuntime()
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.writable }
        compose.onNodeWithTag("native-settings-models").performClick()
        compose.onNodeWithTag("native-settings-add-provider").performClick()
        compose.onNodeWithTag("native-settings-preset:deepseek-official").performScrollTo().performClick()
        compose.waitUntil(10_000) { settings.state.value.editor?.id == "deepseek-official" }
        val draft = settings.state.value.editor!!
        assertFalse(draft.custom)
        compose.onAllNodesWithText(draft.name).onFirst().assertIsDisplayed()
        compose.onNodeWithTag("native-settings-provider-base-url").assertDoesNotExist()
        compose.onNodeWithTag("native-settings-provider-protocol").assertDoesNotExist()
        compose.onNodeWithTag("native-settings-provider-name").assertDoesNotExist()
        compose.onNodeWithTag("native-settings-provider-key").assertIsDisplayed()
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        fixture.capture("native-official-deepseek-editor")
    }

    @Test fun biographySavesAndSurvivesActivityRecreation() {
        lateinit var model: NativeCompanionViewModel
        fixture.scenario.onActivity { model = ViewModelProvider(it)[NativeCompanionViewModel::class.java] }
        fixture.startRuntime()
        compose.waitUntil(30_000) { model.state.value.runtime.ready && model.state.value.companions.isNotEmpty() }
        compose.onNodeWithTag("native-settings-my-profile").performClick()
        assertEquals("", model.state.value.userProfile.bio)
        compose.onNodeWithTag("native-user-bio").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("native-user-name").performTextReplacement("Fictional Alex")
        compose.onNodeWithTag("native-user-bio").performTextInput("Fictional architect\nEnjoys hiking")
        compose.onNodeWithTag("native-user-profile-save").performClick()
        compose.waitUntil(15_000) { !model.state.value.profileSaving && model.state.value.userProfile.bio == "Fictional architect\nEnjoys hiking" }
        assertNull(model.state.value.error)
        val stored = runBlocking { fixture.runtimeTransport.json("/droy/companions/user-profile", "GET") }
        assertEquals("Fictional architect\nEnjoys hiking", stored.getString("bio"))
        fixture.scenario.recreate()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-user-bio").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("native-user-bio").performScrollTo().assert(hasText("Fictional architect\nEnjoys hiking"))
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        fixture.capture("native-user-biography")
    }
}
