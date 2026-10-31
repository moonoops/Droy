package io.droyagent.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only the existing isolated local runtime; does not discover models or save credentials. */
@RunWith(AndroidJUnit4::class)
class NativeProviderInputDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("settings")

    @Test fun rapidImeInputAndBackgroundRefreshRetainEveryProviderField() {
        lateinit var model: NativeSettingsViewModel
        fixture.scenario.onActivity { model = ViewModelProvider(it)[NativeSettingsViewModel::class.java] }
        fixture.startRuntime()
        compose.waitUntil(30_000) { model.state.value.loaded && model.state.value.writable }
        compose.onNodeWithTag("native-settings-models").performClick()
        compose.onNodeWithTag("native-settings-add-provider").performClick()
        compose.onNodeWithTag("native-settings-custom-provider").performClick()
        compose.waitUntil(10_000) { model.state.value.editor != null }
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
                .bufferedReader().use { assertTrue(it.readText().isBlank()) }
        }
        compose.onNodeWithTag("native-settings-provider-name").performClick()
        shell("input text DroyLocalReview")
        compose.waitForIdle()
        compose.onNodeWithTag("native-settings-provider-name").assert(hasText("DroyLocalReview"))
        assertEquals("DroyLocalReview", model.state.value.editor!!.name)

        compose.onNodeWithTag("native-settings-provider-editor").performScrollToNode(hasTestTag("native-settings-provider-base-url"))
        compose.onNodeWithTag("native-settings-provider-base-url").performClick()
        fixture.scenario.onActivity { model.refresh() }
        shell("input text http://127.0.0.1:1234/v1")
        compose.waitUntil(20_000) { !model.state.value.loading }
        compose.onNodeWithTag("native-settings-provider-base-url").assert(hasText("http://127.0.0.1:1234/v1"))
        assertEquals("DroyLocalReview", model.state.value.editor!!.name)
        assertEquals("http://127.0.0.1:1234/v1", model.state.value.editor!!.baseUrl)

        // Apply another field without giving Compose a frame to collect the previous edit.
        fixture.scenario.onActivity {
            val id = model.state.value.editor!!.id
            model.editProviderProtocol(id, "anthropic-messages")
            model.editProviderDefaultModels(id, false)
        }
        assertEquals("DroyLocalReview", model.state.value.editor!!.name)
        assertEquals("http://127.0.0.1:1234/v1", model.state.value.editor!!.baseUrl)
        assertEquals("anthropic-messages", model.state.value.editor!!.protocol)
        compose.onNodeWithTag("native-settings-provider-editor").performScrollToNode(hasTestTag("native-settings-provider-key"))
        compose.onNodeWithTag("native-settings-provider-key").performTextInput("fictional-key-not-saved")
        compose.onNodeWithTag("native-settings-provider-editor").performScrollToNode(hasTestTag("native-settings-manual-model"))
        compose.onNodeWithTag("native-settings-manual-model").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("native-settings-manual-model-id").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("native-settings-manual-model-id").assertIsDisplayed()
        // System Back may only dismiss the IME; this verifies the model editor's navigation action.
        compose.onNodeWithTag("native-settings-model-back").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("native-settings-provider-editor").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("native-settings-provider-editor").performScrollToNode(hasTestTag("native-settings-provider-name"))
        compose.onNodeWithTag("native-settings-provider-name").assert(hasText("DroyLocalReview"))
        assertEquals("http://127.0.0.1:1234/v1", model.state.value.editor!!.baseUrl)
        fixture.capture("native-provider-input-retained")
    }
}
