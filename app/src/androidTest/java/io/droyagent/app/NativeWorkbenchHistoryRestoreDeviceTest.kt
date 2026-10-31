package io.droyagent.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import io.droyagent.app.nativeui.data.NativeMessage
import io.droyagent.app.nativeui.data.NativeReadingPosition
import io.droyagent.app.nativeui.data.NativeRuntimeState
import io.droyagent.app.nativeui.data.NativeSession
import io.droyagent.app.nativeui.data.NativeWorkbenchState
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.ui.DroyTheme
import io.droyagent.app.nativeui.ui.WorkbenchScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Saveable root-tab content must restore its current reading position before its first draw. */
class NativeWorkbenchHistoryRestoreDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()

    @Test fun returningToTailAfterBackgroundMessagesNeverDrawsTheOldTail() {
        verifyFirstFrame(followingTail = true)
    }

    @Test fun returningToEarlierHistoryKeepsTheSavedReadingPosition() {
        verifyFirstFrame(followingTail = false)
    }

    private fun verifyFirstFrame(followingTail: Boolean) {
        val session = "synthetic-reading-session"
        val visible = mutableStateOf(true)
        fun messages(count: Int) = (1..count).map { index ->
            NativeMessage("reading-$index", "assistant", "**Fictional history message $index**\n\n- First fixture item\n- Second fixture item\n\n> A fictional quoted paragraph.")
        }
        val state = mutableStateOf(NativeWorkbenchState(runtime = NativeRuntimeState(ready = true),
            workspaceResolved = true, selectedSessionId = session, sessionBaselineId = session,
            sessions = listOf(NativeSession(session, "Reading fixture")), messages = messages(30),
            draftId = session, readingPosition = if (followingTail) NativeReadingPosition("tail", 0, true)
                else NativeReadingPosition("reading-10", 16, false)))
        fixture.scenario.onActivity { activity ->
            val model = ViewModelProvider(activity)[NativeWorkbenchViewModel::class.java]
            activity.setContent { DroyTheme {
                val pages = rememberSaveableStateHolder()
                if (visible.value) pages.SaveableStateProvider("workbench") {
                    WorkbenchScreen(state.value, model, onSchedules = {})
                }
            } }
        }
        compose.waitForIdle()
        val anchor = compose.onNodeWithTag("native-message-bubble:reading-${if (followingTail) 30 else 10}").assertIsDisplayed()
        val previousTop = anchor.fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { state.value = state.value.copy(messages = messages(50)) }

        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { visible.value = true }
            compose.mainClock.advanceTimeByFrame()
            if (followingTail) {
                compose.onNodeWithTag("native-message-bubble:reading-50").assertIsDisplayed()
                compose.onNodeWithText("Fictional history message 50", useUnmergedTree = true).assertIsDisplayed()
            }
            else assertEquals(previousTop, anchor.assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top, 1f)
        } finally { compose.mainClock.autoAdvance = true }
    }
}
