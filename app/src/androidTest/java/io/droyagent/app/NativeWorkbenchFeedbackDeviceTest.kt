package io.droyagent.app

import android.content.res.Configuration
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
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
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assume.assumeTrue
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Real SDK/local provider and actual native controls; preserves the emulator's original home. */
@RunWith(AndroidJUnit4::class)
class NativeWorkbenchFeedbackDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    @get:Rule(order = 2) val failureEvidence = object : TestWatcher() {
        override fun failed(failure: Throwable, description: Description) {
            val name = "native-feedback-failed-${description.methodName}"
            try { fixture.capture(name) } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
            try {
                File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence/$name.txt")
                    .writeText(compose.onRoot(useUnmergedTree = true).printToString())
            } catch (diagnostic: Throwable) { failure.addSuppressed(diagnostic) }
        }
    }
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

    @Test fun narrowLargeTextComposerKeepsModelAndExecutionControlsReadable() {
        assumeTrue("Run with -e nativeComposerLargeTextFixture true at 320dp and system font 1.3",
            InstrumentationRegistry.getArguments().getString("nativeComposerLargeTextFixture") == "true")
        val configuration = fixture.context.resources.configuration
        assertTrue("This regression requires an actual 320dp portrait viewport", configuration.screenWidthDp <= 320)
        assertTrue("This regression requires enlarged system text", configuration.fontScale >= 1.3f)
        enableLocalReasoningCatalog()
        fixture.scenario.onActivity {
            val models = ViewModelProvider(it)
            work = models[NativeWorkbenchViewModel::class.java]
            settings = models[NativeSettingsViewModel::class.java]
            engine = models[NativeEngineViewModel::class.java]
            work.refresh()
        }
        compose.waitUntil(30_000) { work.state.value.workspaceResolved && work.state.value.models.any { it.id == "native-alternate" && "high" in it.reasoningEfforts } &&
            settings.state.value.loaded && settings.state.value.writable && engine.state.value.fresh && !engine.state.value.loading }
        compose.onNodeWithTag("native-tab-workbench").performClick()
        compose.onNodeWithTag("native-workbench-menu").performClick()
        compose.onNodeWithText(text(R.string.native_workbench_font)).performClick()
        compose.onNodeWithTag("native-font-slider").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(22f) }
        compose.onNodeWithText(text(R.string.native_font_apply)).assertIsEnabled().performClick()
        assertEquals(22, fixture.context.getSharedPreferences("droy_native_reading", 0).getInt("workbenchTextSize", 0))
        val draft = "Composer 1\nComposer 2\nComposer 3\nComposer 4"
        fixture.scenario.onActivity { work.setDraft(draft) }
        val selected = work.state.value.models.single { it.id == "native-alternate" }
        for (locale in listOf("en", "zh")) {
            language = locale
            fixture.scenario.onActivity { settings.setLanguage(locale) }
            compose.waitUntil(20_000) { settings.state.value.locale == locale && !settings.state.value.saving &&
                work.state.value.runtime.locale == locale && engine.state.value.runtime.locale == locale && NativeLocale.active() == locale }
            assertNull(settings.state.value.error)
            compose.onNodeWithTag("native-model").assertIsDisplayed().assertIsEnabled().performClick()
            compose.onNode(hasText(selected.name) and hasClickAction() and hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { work.state.value.selectedModel?.id == selected.id && !work.state.value.modelChanging }
            compose.onNodeWithTag("native-reasoning").assertIsDisplayed().assertIsEnabled().performClick()
            compose.onNode(hasText(text(R.string.native_effort_high)) and hasClickAction() and hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { work.state.value.selectedModel?.reasoningEffort == "high" }
            compose.onNodeWithTag("native-permission").assertIsDisplayed().assertIsEnabled().performClick()
            compose.onNode(hasText(text(R.string.native_engine_workspace_write)) and hasClickAction() and hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { engine.state.value.draftSelection?.permissionPresetId == "workspace-write" }
            verifyControls()
            assertFourLineDraft(draft)
            assertControlText("native-model", selected.name)
            assertControlText("native-reasoning", text(R.string.native_effort_high))
            assertControlText("native-permission", text(R.string.native_engine_permission_workspace_short))
            val modelBounds = compose.onNodeWithTag("native-model").fetchSemanticsNode().boundsInRoot
            val contextBounds = compose.onNodeWithTag("native-context-control").fetchSemanticsNode().boundsInRoot
            val reasoningBounds = compose.onNodeWithTag("native-reasoning").fetchSemanticsNode().boundsInRoot
            val permissionBounds = compose.onNodeWithTag("native-permission").fetchSemanticsNode().boundsInRoot
            assertTrue("Execution choices must have their own row below the model", modelBounds.bottom <= reasoningBounds.top + 1f)
            assertTrue("Context must remain beside the model above execution choices", contextBounds.bottom <= permissionBounds.top + 1f)
            compose.onNodeWithTag("native-composer").assertIsDisplayed().assertTextContains(draft)
            capture("feedback-composer-320dp-large-$locale")
            compose.onNodeWithTag("native-context-control").assertIsDisplayed().performClick()
            compose.onNodeWithTag("native-usage-drag-region").assertIsDisplayed()
            back()
            compose.onNodeWithTag("native-composer").assertTextContains(draft)
            assertEquals(selected.id, work.state.value.selectedModel?.id)
            assertEquals("high", work.state.value.selectedModel?.reasoningEffort)
        }
        val calls = File(fixture.home, "native-fixture-calls.jsonl")
        assertTrue("Configuring local composer controls must not invoke a model", !calls.exists() || calls.length() == 0L)
    }

    private fun enableLocalReasoningCatalog() {
        fixture.scenario.moveToState(Lifecycle.State.CREATED)
        fixture.context.startService(Intent(fixture.context, RuntimeService::class.java).setAction(RuntimeService.STOP))
        compose.waitUntil(30_000) { RuntimeService.canManageHistory() }
        val provider = File(fixture.home, "native-fixture.mjs")
        val source = provider.readText()
        val declaration = "context: { contextWindow: 32768 }, defaultMaxTokens: 1024"
        assertEquals("Patch only the owned local fixture's model declaration", 1, source.split(declaration).size - 1)
        provider.writeText(source.replace(declaration,
            "reasoning: { efforts: [{ id: 'high', name: 'High' }, { id: 'low', name: 'Low' }], defaultEffort: 'high' }, $declaration"))
        fixture.scenario.moveToState(Lifecycle.State.RESUMED)
        fixture.startRuntime()
    }

    private fun assertControlText(tag: String, expected: String) {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        val textNode = compose.onNode(hasText(expected) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertIsDisplayed()
        textNode.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val visible = textNode.fetchSemanticsNode().boundsInRoot
        val control = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val metrics = textLayoutMetrics(layout, visible)
            .put("controlBounds", boundsJson(control)).put("tag", tag).put("locale", language)
        writeMetrics("composer-text-metrics-$tag-$language", metrics)
        assertEquals("$tag must show the selected label", expected, layout.layoutInput.text.text)
        assertEquals("$tag must retain its complete single-line label: $metrics", 1, layout.lineCount)
        assertEquals("$tag must retain the beginning of the label: $metrics", 0, layout.getLineStart(0))
        assertEquals("$tag must retain the end of the label: $metrics", expected.length, layout.getLineEnd(0))
        assertEquals("$tag must visibly retain the end of the label: $metrics", expected.length, layout.getLineEnd(0, visibleEnd = true))
        // Compose can retain the parent's paragraph width (721px) after wrapping the
        // Text node to its glyph width (529px), making didOverflowWidth true without
        // clipping. Check the actual lines, glyphs and visible node instead.
        val pixelTolerance = 1f
        assertTrue("$tag must expose its entire measured text node: $metrics",
            visible.width + pixelTolerance >= layout.size.width && visible.height + pixelTolerance >= layout.size.height)
        assertTrue("$tag label must stay inside its control: $metrics", visible.left >= control.left - pixelTolerance &&
            visible.right <= control.right + pixelTolerance && visible.top >= control.top - pixelTolerance &&
            visible.bottom <= control.bottom + pixelTolerance)
        fun fitsVisibleText(bounds: androidx.compose.ui.geometry.Rect) = bounds.left >= -pixelTolerance &&
            bounds.top >= -pixelTolerance && bounds.right <= visible.width + pixelTolerance &&
            bounds.bottom <= visible.height + pixelTolerance
        for (line in 0 until layout.lineCount) {
            assertTrue("$tag line $line must fit its visible text bounds: $metrics", fitsVisibleText(androidx.compose.ui.geometry.Rect(
                layout.getLineLeft(line), layout.getLineTop(line), layout.getLineRight(line), layout.getLineBottom(line))))
        }
        expected.indices.filter { !expected[it].isWhitespace() }.forEach { offset ->
            assertTrue("$tag glyph at $offset must fit its visible text bounds: $metrics", fitsVisibleText(layout.getBoundingBox(offset)))
        }
        assertFalse("$tag text must fit the control height: $metrics", layout.didOverflowHeight)
        assertTrue("$tag must not replace the selected label with an ellipsis: $metrics", (0 until layout.lineCount).none(layout::isLineEllipsized))
    }

    private fun assertFourLineDraft(expected: String) {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        val composer = compose.onNodeWithTag("native-composer").assertIsDisplayed().assertTextContains(expected)
        composer.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val visible = composer.fetchSemanticsNode().boundsInRoot
        val metrics = textLayoutMetrics(layout, visible).put("locale", language)
        writeMetrics("composer-four-line-metrics-$language", metrics)
        assertEquals("Keep all four explicit lines in the retained draft", expected, layout.layoutInput.text.text)
        assertEquals("Each short draft line must remain on its own complete line: $metrics", 4, layout.lineCount)
        assertTrue("Four enlarged lines must expand beyond the retired 144dp cap: $metrics",
            visible.height / fixture.context.resources.displayMetrics.density > 144f)
        assertTrue("The final draft line must fit its measured text viewport: $metrics", layout.getLineBottom(3) <= layout.size.height)
        assertFalse("The final draft line must not be ellipsized: $metrics", layout.isLineEllipsized(3))
    }

    private fun textLayoutMetrics(layout: androidx.compose.ui.text.TextLayoutResult, visible: androidx.compose.ui.geometry.Rect): JSONObject {
        val input = layout.layoutInput
        val constraints = input.constraints
        val glyphs = input.text.text.indices.filter { !input.text.text[it].isWhitespace() }.map(layout::getBoundingBox)
        return JSONObject().put("text", input.text.text).put("fontScale", input.density.fontScale).put("density", input.density.density)
            .put("size", JSONObject().put("width", layout.size.width).put("height", layout.size.height))
            .put("paragraph", JSONObject().put("width", layout.multiParagraph.width).put("height", layout.multiParagraph.height))
            .put("constraints", JSONObject().put("minWidth", constraints.minWidth).put("maxWidth", constraints.maxWidth)
                .put("minHeight", constraints.minHeight).put("maxHeight", constraints.maxHeight))
            .put("didOverflowWidth", layout.didOverflowWidth).put("didOverflowHeight", layout.didOverflowHeight)
            .put("visibleBounds", boundsJson(visible))
            .put("glyphBounds", if (glyphs.isEmpty()) JSONObject.NULL else boundsJson(androidx.compose.ui.geometry.Rect(
                glyphs.minOf { it.left }, glyphs.minOf { it.top }, glyphs.maxOf { it.right }, glyphs.maxOf { it.bottom })))
            .put("lines", JSONArray((0 until layout.lineCount).map { line ->
                JSONObject().put("left", layout.getLineLeft(line)).put("right", layout.getLineRight(line))
                    .put("top", layout.getLineTop(line)).put("bottom", layout.getLineBottom(line))
                    .put("start", layout.getLineStart(line)).put("end", layout.getLineEnd(line))
                    .put("visibleEnd", layout.getLineEnd(line, visibleEnd = true)).put("ellipsized", layout.isLineEllipsized(line))
            }))
    }

    private fun boundsJson(bounds: androidx.compose.ui.geometry.Rect) = JSONObject().put("left", bounds.left)
        .put("top", bounds.top).put("right", bounds.right).put("bottom", bounds.bottom)

    private fun writeMetrics(name: String, metrics: JSONObject) {
        val directory = File(fixture.context.getExternalFilesDir(null), "native-isolated-evidence").apply { assertTrue(isDirectory || mkdirs()) }
        File(directory, "$name.json").writeText(metrics.toString(2))
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(500) // Let the real window ripple/compositor finish after interaction.
        fixture.capture(name)
    }
    private fun verifyControls() {
        val bounds = listOf("native-model", "native-reasoning", "native-permission", "native-context-control")
            .map { compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        for (left in bounds.indices) for (right in left + 1 until bounds.size) {
            val a = bounds[left]; val b = bounds[right]
            assertTrue("Composer controls must not overlap", a.right <= b.left + 1f || b.right <= a.left + 1f ||
                a.bottom <= b.top + 1f || b.bottom <= a.top + 1f)
        }
    }
    private fun text(id: Int): String {
        val config = Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        return fixture.context.createConfigurationContext(config).getString(id)
    }
    private fun back() = InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
}
