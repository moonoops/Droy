package io.droyagent.app

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.advanced.NativeAdvancedViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Visits approved settings only; never installs a plugin or calls an external provider. */
@RunWith(AndroidJUnit4::class)
class NativeApprovedPluginsDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings")

    @Test fun approvedPluginRoutesAreScopedAndOldDuplicateEntriesAreGone() {
        lateinit var settings: NativeSettingsViewModel
        lateinit var advanced: NativeAdvancedViewModel
        fixture.scenario.onActivity {
            settings = ViewModelProvider(it)[NativeSettingsViewModel::class.java]
            advanced = ViewModelProvider(it)[NativeAdvancedViewModel::class.java]
        }
        compose.waitUntil(30_000) { settings.state.value.loaded && settings.state.value.runtime.ready }
        compose.onNodeWithTag("native-settings-web-search").assertDoesNotExist()
        compose.onNodeWithTag("native-settings-models").performClick()
        compose.onAllNodesWithText(text(R.string.native_settings_subtask_models)).assertCountEquals(0)
        compose.onNodeWithTag("native-settings-back").performClick()
        compose.onNodeWithTag("native-settings-plugins").performScrollTo().performClick()
        val destinations = listOf("web-search", "subtask-models", "agent-loop", "terminal", "agent-team", "session-titles", "persistent-terminal")
        destinations.forEach { compose.onNodeWithTag("native-settings-group:$it").assertExists() }
        compose.onAllNodes(SemanticsMatcher("approved settings links") {
            SemanticsProperties.TestTag in it.config && it.config[SemanticsProperties.TestTag].startsWith("native-settings-group:")
        }).assertCountEquals(7)
        capture("round4-approved-plugins")
        for (destination in destinations.take(4)) {
            compose.onNodeWithTag("native-settings-group:$destination").performClick()
            when (destination) {
                "web-search" -> compose.onNodeWithText(text(R.string.native_settings_search_service)).assertIsDisplayed()
                "subtask-models" -> compose.onNodeWithText(text(R.string.native_settings_allow_subtask_models)).assertIsDisplayed()
                else -> {
                    val id = if (destination == "terminal") "bash-sandbox" else "agent-loop"
                    compose.waitUntil(20_000) { advanced.state.value.ready && !advanced.state.value.busy && advanced.state.value.draft?.section?.id == id }
                    assertNull(advanced.state.value.error)
                    compose.onNodeWithTag("native-advanced-page:configuration").assertTextContains(text(
                        if (destination == "terminal") R.string.native_settings_terminal else R.string.native_advanced_loop))
                    compose.onAllNodesWithText(text(R.string.native_advanced_account)).assertCountEquals(0)
                    compose.onAllNodesWithText(text(R.string.native_advanced_install)).assertCountEquals(0)
                    capture("round4-plugin-$destination")
                }
            }
            back()
            compose.onNodeWithTag("native-settings-group:$destination").assertIsDisplayed()
        }
        back()
        for ((tag, body) in listOf("native-settings-memory" to "native-memory-settings", "native-settings-voice" to "native-voice-content")) {
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            compose.onNodeWithTag(body).assertIsDisplayed()
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
            capture("round4-$tag")
            back()
        }
    }
    private fun text(id: Int) = fixture.context.getString(id)
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
    private fun capture(name: String) {
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle(); fixture.capture(name)
    }
}
