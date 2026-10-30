package io.droyagent.app

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import io.droyagent.app.nativeui.engine.NativeEngineViewModel
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real SDK/local provider and actual native controls; preserves the emulator's original home. */
@RunWith(AndroidJUnit4::class)
class NativeWorkbenchFeedbackDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    private lateinit var work: NativeWorkbenchViewModel
    private lateinit var settings: NativeSettingsViewModel
    private lateinit var engine: NativeEngineViewModel
    private var language = "en"

    @Test fun compactComposerReplyActionsAndStopRemainUsableInBothLanguages() {
        fixture.scenario.onActivity {
            work = ViewModelProvider(it)[NativeWorkbenchViewModel::class.java]
            settings = ViewModelProvider(it)[NativeSettingsViewModel::class.java]
            engine = ViewModelProvider(it)[NativeEngineViewModel::class.java]
        }
        compose.waitUntil(30_000) { work.state.value.runtime.ready && work.state.value.workspaceResolved && work.state.value.selectedModel != null }
        compose.onNodeWithTag("native-composer").performTextInput("NATIVE_HELLO")
        compose.onNodeWithTag("native-send").assertIsEnabled().performClick()
        compose.waitUntil(30_000) { !work.state.value.running && !work.state.value.sending && work.state.value.messages.any { it.role == "assistant" && it.text == "Native DSH reply received." } }
        val reply = work.state.value.messages.last { it.role == "assistant" && it.text == "Native DSH reply received." }
        val sessionId = requireNotNull(work.state.value.selectedSessionId)
        back() // dismiss the keyboard, leaving the actual conversation visible
        compose.onNodeWithTag("native-message-bubble:${reply.id}").assertIsDisplayed()
        // Copy is a context action, never a standalone row below each reply.
        compose.onAllNodesWithContentDescription(text(R.string.native_copy_reply)).assertCountEquals(0)
        compose.onNodeWithTag("native-message-bubble:${reply.id}").performTouchInput { longClick(Offset(4f, 4f)) }
        compose.onNodeWithText(text(R.string.native_copy_reply)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(R.string.native_copied)).assertIsDisplayed()
        verifyControls()
        compose.waitUntil(15_000) { !engine.state.value.sessionLoading && engine.state.value.session?.fresh == true }
        assertNull("A successful first reply must not retain an early missing-session read error", engine.state.value.error)
        val projection = runBlocking { fixture.runtimeTransport.json("/droy/native/engine/session", "POST", JSONObject().put("sessionId", sessionId)) }
        File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence/workbench-feedback-engine-diagnostic.json").writeText(JSONObject()
            .put("error", engine.state.value.error ?: JSONObject.NULL).put("sessionLoading", engine.state.value.sessionLoading)
            .put("nativeSessionFresh", engine.state.value.session?.fresh).put("projection", projection).toString(2))
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text(R.string.native_copied)).fetchSemanticsNodes(false).isEmpty() }
        capture("feedback-workbench-bubbles-en")
        compose.onNodeWithTag("native-workbench-menu").performClick()
        compose.onNodeWithText(text(R.string.native_workbench_font)).performClick()
        compose.onNodeWithTag("native-font-slider").assertIsDisplayed()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(20f) }
        compose.onNodeWithTag("native-font-value").assertTextContains("20")
        capture("feedback-workbench-font-slider-en")
        back() // Cancelling a preview does not change the saved reading size.

        fixture.scenario.onActivity { work.setDraft("NATIVE_HOLD") }
        compose.onNodeWithTag("native-send").assertIsEnabled().performClick()
        // Admission marks the session running before the local provider is invoked.
        // The streamed delta establishes that this is the provider call we intend to cancel.
        compose.waitUntil(30_000) { work.state.value.running && work.state.value.messages.any { it.role == "assistant" && it.text.contains("Local hold started") } }
        fixture.scenario.onActivity { work.setDraft("Retained next draft") }
        compose.onNodeWithTag("native-stop").assertIsEnabled()
        compose.onAllNodesWithTag("native-send").assertCountEquals(0)
        compose.onAllNodesWithContentDescription(text(R.string.native_stop)).assertCountEquals(1)
        capture("feedback-workbench-stop-en")
        compose.onNodeWithTag("native-stop").performClick()
        try {
            compose.waitUntil(30_000) { !work.state.value.running && !work.state.value.sending && File(fixture.home, "native-fixture-hold-aborted.json").isFile }
        } catch (error: ComposeTimeoutException) {
            throw AssertionError("Stop must quiesce the actual local call: running=${work.state.value.running}, sending=${work.state.value.sending}, error=${work.state.value.error}, providerCancelled=${File(fixture.home, "native-fixture-hold-aborted.json").isFile}", error)
        }
        assertEquals("Retained next draft", work.state.value.draft)
        fixture.scenario.onActivity { settings.setLanguage("zh") }
        compose.waitUntil(20_000) { settings.state.value.locale == "zh" && !settings.state.value.saving && work.state.value.runtime.locale == "zh" }
        language = "zh"
        verifyControls()
        capture("feedback-workbench-bubbles-zh")

        // DSH limits accepted titles to 80 UTF-8 bytes; these 26 CJK characters use 78.
        val longTitle = "用于检查空间列表单行省略的很长对话标题，不能挤压菜单"
        val rename = runBlocking { fixture.runtimeTransport.json("/droy/native/workspaces/mutate", "POST", JSONObject()
            .put("requestId", UUID.randomUUID().toString()).put("kind", "session.rename")
            .put("args", JSONObject().put("sessionId", sessionId).put("title", longTitle))) }
        assertTrue(rename.toString(), rename.getBoolean("ok"))
        assertEquals(rename.toString(), "complete", rename.getJSONObject("result").getString("state"))
        assertEquals(longTitle, rename.getJSONObject("result").getString("title"))
        compose.onNodeWithTag("native-workbench-menu").performClick()
        compose.onNodeWithText(text(R.string.native_spaces_chats)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-space-session:$sessionId").fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithTag("native-space-session:$sessionId").assertIsDisplayed()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(longTitle, useUnmergedTree = true).fetchSemanticsNodes(false).isNotEmpty() }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(longTitle, useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertTrue("The long title must truncate within its row", layouts.single().isLineEllipsized(0))
        capture("feedback-workbench-spaces-zh")
        File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence/workbench-feedback-receipt.json").writeText(JSONObject()
            .put("replyBubble", true).put("contextCopy", true).put("fourControls", true).put("singleStopWithUnsentDraft", true)
            .put("stopActuallyCancelled", true).put("spaceTitleSingleLine", true).put("localProviderOnly", true).toString(2))
    }

    @Test fun sendingFromHistoryFollowsTailAndUsageHeaderDragsTheSheet() {
        fixture.scenario.onActivity { work = ViewModelProvider(it)[NativeWorkbenchViewModel::class.java] }
        compose.waitUntil(30_000) { work.state.value.runtime.ready && work.state.value.workspaceResolved && work.state.value.selectedModel != null }
        val longText = (1..32).joinToString("\n") { "Fictional history line $it" }
        fixture.scenario.onActivity { work.setDraft(longText); work.send() }
        compose.waitUntil(30_000) { !work.state.value.sending && !work.state.value.running && work.state.value.messages.any { it.role == "assistant" } }
        compose.onNodeWithTag("native-history").performScrollToIndex(0)
        compose.waitForIdle()
        compose.onNodeWithTag("native-tab-settings").performClick()
        compose.onNodeWithTag("native-tab-workbench").performClick()
        compose.waitForIdle()
        val before = work.state.value.messages.count { it.role == "assistant" }
        compose.onNodeWithTag("native-composer").performTextInput("NATIVE_HELLO")
        compose.onNodeWithTag("native-send").performClick()
        compose.waitUntil(30_000) { !work.state.value.sending && !work.state.value.running && work.state.value.messages.count { it.role == "assistant" } > before }
        val reply = work.state.value.messages.last { it.role == "assistant" }
        compose.onNodeWithTag("native-message-bubble:${reply.id}").assertIsDisplayed()
        back()
        compose.onNodeWithTag("native-context-control").performClick()
        compose.onNodeWithTag("native-usage-drag-region").assertIsDisplayed()
        compose.waitForIdle(); compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle()
        fixture.capture("round3-usage-sheet")
        compose.onNodeWithTag("native-usage-drag-region").performTouchInput {
            swipe(center, Offset(center.x, center.y + 700f), durationMillis = 350)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-usage-drag-region").fetchSemanticsNodes(false).isEmpty() }
        compose.onNodeWithTag("native-context-control").assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(500) // Let the real window ripple/compositor finish after interaction.
        fixture.capture(name)
    }
    private fun verifyControls() {
        val bounds = listOf("native-model", "native-reasoning", "native-permission", "native-context-control")
            .map { compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEach { (left, right) -> assertTrue("Composer controls must not overlap", left.right <= right.left + 1f) }
    }
    private fun text(id: Int): String {
        val config = Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return fixture.context.createConfigurationContext(config).getString(id)
    }
    private fun back() = InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
}
