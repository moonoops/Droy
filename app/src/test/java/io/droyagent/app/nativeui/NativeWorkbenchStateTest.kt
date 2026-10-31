package io.droyagent.app.nativeui

import io.droyagent.app.nativeui.data.NativeSession
import io.droyagent.app.nativeui.data.NativeWorkbenchState
import io.droyagent.app.nativeui.data.NativeWorkspace
import io.droyagent.app.nativeui.data.spaceGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWorkbenchStateTest {
    @Test fun anUnresolvedSavedSelectionCannotBecomeWritableWhenItsCatalogRowIsMissing() {
        val state = NativeWorkbenchState(selectedSessionId = "unknown", draftId = "unknown", workspaceResolved = true)
        assertTrue(state.readOnly)
        assertTrue(state.copy(selectedSessionId = null, selectedWorkspaceId = "unknown-space").readOnly)
        assertFalse(state.copy(pendingRequestId = "accepted-local-creation").readOnly)
        assertTrue(state.copy(pendingRequestId = "accepted-local-creation", managedSessionIds = setOf("unknown")).readOnly)
    }

    @Test fun explicitCompanionOwnershipHidesOnlyCompanionGroupsAndKeepsUserChildren() {
        val state = NativeWorkbenchState(workspaces = listOf(NativeWorkspace("mine", "Mine", listOf("root", "child")),
            NativeWorkspace("friend", "Friend", listOf("friend-work"), readOnly = true)),
            sessions = listOf(NativeSession("root", "Root", workspaceId = "mine"),
                NativeSession("child", "Child", workspaceId = "mine", readOnly = true),
                NativeSession("friend-work", "Internal", workspaceId = "friend", readOnly = true)),
            managedWorkspaceIds = setOf("friend"), managedSessionIds = setOf("friend-work"))
        val groups = state.spaceGroups("Ungrouped")
        assertEquals(listOf("mine", null), groups.map { it.id })
        assertEquals(listOf("root", "child"), groups.first().sessions.map { it.id })
    }

    @Test fun archivedSelectionKeepsItsIdentityAndDraftButCannotBeWrittenUntilRestored() {
        val session = NativeSession("history", "Saved", workspaceId = "work", archived = true)
        val state = NativeWorkbenchState(sessions = listOf(session), selectedSessionId = session.id,
            selectedWorkspaceId = "work", draft = "Unsent continuation")
        assertTrue(state.archived)
        assertTrue(state.readOnly)
        val restored = state.copy(sessions = listOf(session.copy(archived = false)))
        assertFalse(restored.archived)
        assertFalse(restored.readOnly)
        assertEquals(state.draft, restored.draft)
        assertEquals(state.selectedSessionId, restored.selectedSessionId)
    }
    @Test fun companionSessionStaysReadOnlyWithoutRelyingOnWorkspaceMembership() {
        val state = NativeWorkbenchState(
            sessions = listOf(NativeSession("companion-session", "History", readOnly = true)),
            selectedSessionId = "companion-session",
        )
        assertTrue(state.readOnly)
        assertFalse(state.copy(selectedSessionId = null).readOnly)
    }

    @Test fun blankDraftInCompanionWorkspaceCannotBecomeAWorkbenchTask() {
        val state = NativeWorkbenchState(
            workspaces = listOf(NativeWorkspace("companion-space", "Companion", readOnly = true),
                NativeWorkspace("work-space", "Work")),
            selectedWorkspaceId = "companion-space",
        )
        assertTrue(state.readOnly)
        assertFalse(state.copy(selectedWorkspaceId = "work-space").readOnly)
    }

    @Test fun spaceGroupsKeepCustomDefaultUngroupedAndCompanionOrder() {
        val state = NativeWorkbenchState(
            defaultWorkspaceId = "default",
            workspaces = listOf(NativeWorkspace("default", "Renamed default", listOf("ordinary")),
                NativeWorkspace("companion", "Kaka", listOf("managed"), readOnly = true),
                NativeWorkspace("custom", "Custom")),
            sessions = listOf(NativeSession("ordinary", "Existing", workspaceId = "default"),
                NativeSession("ungrouped", "Loose"), NativeSession("managed", "Task", readOnly = true)),
        )
        val groups = state.spaceGroups("Ungrouped")
        assertEquals(listOf("custom", "default", null, "companion"), groups.map { it.id })
        assertEquals(listOf("ungrouped"), groups.single { it.id == null }.sessions.map { it.id })
        assertEquals(listOf("managed"), groups.last().sessions.map { it.id })
        assertTrue(groups.last().readOnly)
    }
}
