package io.droyagent.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.navigation.NativeExternalNavigationViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The launch intent must not make Messages restore the Tasks stack that it just popped. */
class NativeRootNavigationDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture("workbench")

    @Test fun externalTasksEntryCanOpenMessagesAndPreserveTaskDraftAndConversation() {
        lateinit var work: NativeWorkbenchViewModel
        lateinit var external: NativeExternalNavigationViewModel
        fixture.scenario.onActivity {
            work = ViewModelProvider(it)[NativeWorkbenchViewModel::class.java]
            external = ViewModelProvider(it)[NativeExternalNavigationViewModel::class.java]
        }
        compose.waitUntil(30_000) {
            external.state.value.restored && external.state.value.request == null
                && work.state.value.runtime.ready && work.state.value.workspaceResolved && work.state.value.selectedModel != null
        }
        compose.onNodeWithTag("native-tab-workbench").assertIsSelected()
        assertNull(work.state.value.selectedSessionId)
        val blankDraft = work.state.value.draftId
        val unsent = "Fictional task draft retained across root tabs"
        fixture.scenario.onActivity { work.setDraft(unsent) }

        openMessages()
        compose.onNodeWithTag("native-tab-workbench").performClick()
        compose.onNodeWithTag("native-tab-workbench").assertIsSelected()
        compose.onNodeWithTag("native-composer").assertTextContains(unsent)
        assertEquals(blankDraft, work.state.value.draftId)
        assertNull(work.state.value.selectedSessionId)

        fixture.scenario.onActivity { work.setDraft("NATIVE_HELLO"); work.send() }
        compose.waitUntil(30_000) {
            !work.state.value.sending && !work.state.value.running
                && work.state.value.messages.any { it.role == "assistant" && it.text == "Native DSH reply received." }
        }
        val session = requireNotNull(work.state.value.selectedSessionId)
        fixture.scenario.onActivity { work.setDraft(unsent) }
        openMessages()
        compose.onNodeWithTag("native-tab-workbench").performClick()
        compose.onNodeWithTag("native-tab-workbench").assertIsSelected()
        compose.onNodeWithTag("native-composer").assertTextContains(unsent)
        compose.onNodeWithText("Native DSH reply received.").assertIsDisplayed()
        assertEquals(session, work.state.value.selectedSessionId)
        openMessages()
        fixture.capture("root-tabs-after-external-tasks")
    }

    private fun openMessages() {
        compose.onNodeWithTag("native-tab-messages").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-add-companion").fetchSemanticsNodes(false).isNotEmpty() }
        compose.onNodeWithTag("native-tab-messages").assertIsSelected()
        compose.onNodeWithTag("native-add-companion").assertIsDisplayed()
    }
}
