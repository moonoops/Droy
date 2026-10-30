package io.droyagent.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.droyagent.app.nativeui.engine.*
import io.droyagent.app.nativeui.richtext.NativeRichText
import io.droyagent.app.nativeui.ui.DroyTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeWorkbenchRenderingDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()

    @Test fun streamingUpdatesNeverReturnToRawMarkdownAfterFirstParse() {
        val content = mutableStateOf("**Stable heading**\n\nParagraph")
        fixture.scenario.onActivity { activity -> activity.setContent {
            DroyTheme { NativeRichText(content.value, {}, {}) }
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Stable heading", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        repeat(20) { revision ->
            compose.runOnIdle { content.value = "**Stable heading**\n\nParagraph " + "more ".repeat(revision + 1) }
            compose.onAllNodesWithText("**Stable heading**", substring = true, useUnmergedTree = true).assertCountEquals(0)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Paragraph " + "more ".repeat(20).trimEnd(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        fixture.capture("round4-streaming-rich-text")
    }

    @Test fun permissionLabelSurvivesDraftMaterializationButNeverLeaksToAnotherTarget() {
        val engine = mutableStateOf(NativeEngineState(draftId = "draft-one",
            draftSelection = NativeEngineDraftSelection("draft-one", "nonce", permissionPresetId = "workspace-write")))
        fixture.scenario.onActivity { activity -> activity.setContent {
            DroyTheme { Column { NativeEnginePermissionControl(engine.value, {}) } }
        } }
        val workspace = fixture.context.getString(R.string.native_engine_permission_workspace_short)
        compose.onNodeWithText(workspace).assertIsDisplayed()
        compose.runOnIdle { engine.value = NativeEngineState(sessionId = "draft-one", sessionLoading = true) }
        compose.onNodeWithText(workspace).assertIsDisplayed()
        compose.onNodeWithTag("native-permission").assertIsNotEnabled()
        compose.runOnIdle { engine.value = NativeEngineState(sessionId = "different-session", sessionLoading = true) }
        compose.onNodeWithText(workspace).assertDoesNotExist()
        compose.onNodeWithText(fixture.context.getString(R.string.native_engine_permissions)).assertIsDisplayed()
    }
}
