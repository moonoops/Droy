package io.droyagent.app.nativeui

import io.droyagent.app.nativeui.data.bindInitialWorkspace
import io.droyagent.app.nativeui.data.recoverUserSelection
import io.droyagent.app.nativeui.data.NativeSession
import io.droyagent.app.nativeui.data.NativeWorkspace
import org.junit.Assert.*
import org.junit.Test

class NativeInitialWorkspaceTest {
    private val userSpaces = listOf(NativeWorkspace("default", "Default"), NativeWorkspace("custom", "Custom"))
    private val userSessions = listOf(NativeSession("user-task", "Work", workspaceId = "custom"),
        NativeSession("user-child", "Child", workspaceId = "custom", readOnly = true))

    @Test fun aSavedCompanionTaskReturnsToTheLastUsersSession() {
        val result = recoverUserSelection("friend-work", "friend-space", "user-task", "custom", true,
            userSessions, userSpaces, "default", setOf("friend-work"), setOf("friend-space"))!!
        assertEquals("user-task", result.sessionId)
        assertEquals("custom", result.workspaceId)
    }

    @Test fun aCompanionSelectionWithoutAUserHistoryReturnsToTheUserSpaceDraft() {
        val result = recoverUserSelection("friend-work", "friend-space", null, null, false,
            userSessions, userSpaces, "default", setOf("friend-work"), setOf("friend-space"))!!
        assertNull(result.sessionId)
        assertEquals("default", result.workspaceId)
        val ungrouped = recoverUserSelection(null, "friend-space", null, null, true,
            userSessions, userSpaces, "default", setOf("friend-work"), setOf("friend-space"))!!
        assertNull(ungrouped.workspaceId)
    }

    @Test fun anUnlistedNewUserSessionAndAReadOnlyUserChildAreNotCompanions() {
        for (id in listOf("new-user-task", "user-child")) assertNull(recoverUserSelection(id, "custom", null, null, false,
            userSessions, userSpaces, "default", setOf("friend-work"), setOf("friend-space")))
    }

    @Test fun firstCatalogBindsEarlyTextAndAttachmentDraftWithoutChangingItsIdentity() {
        val result = bindInitialWorkspace("offline-draft", true, "stable-default", null, false)
        assertEquals("stable-default", result.workspaceId)
        assertEquals("offline-draft", result.draftId)
        assertTrue(result.adoptCurrentDraft)
        assertFalse(result.defaultDraftConflict)
    }

    @Test fun emptyPlaceholderInDefaultDoesNotReplaceTheOfflineDraft() {
        val result = bindInitialWorkspace("offline-draft", true, "stable-default", "empty-default", false)
        assertEquals("offline-draft", result.draftId)
        assertTrue(result.adoptCurrentDraft)
    }

    @Test fun twoNonemptyDraftsKeepTheCurrentDraftInUngroupedWithAnExplicitNotice() {
        val result = bindInitialWorkspace("offline-draft", true, "stable-default", "saved-default", true)
        assertNull(result.workspaceId)
        assertEquals("offline-draft", result.draftId)
        assertFalse(result.adoptCurrentDraft)
        assertTrue(result.defaultDraftConflict)
    }

    @Test fun emptyInitialDraftRestoresAnExistingDefaultDraft() {
        val result = bindInitialWorkspace("empty-initial", false, "stable-default", "saved-default", true)
        assertEquals("stable-default", result.workspaceId)
        assertEquals("saved-default", result.draftId)
        assertFalse(result.defaultDraftConflict)
        assertFalse(result.adoptCurrentDraft)
    }

    @Test fun anAlreadySharedPointerIsNotAnExistingDraftConflict() {
        val result = bindInitialWorkspace("same-draft", true, "stable-default", "same-draft", true)
        assertEquals("stable-default", result.workspaceId)
        assertEquals("same-draft", result.draftId)
        assertFalse(result.defaultDraftConflict)
    }

    @Test fun catalogWithoutADefaultResolvesToRealUngroupedWithoutReplacingContent() {
        val result = bindInitialWorkspace("offline-draft", true, null, null, false)
        assertNull(result.workspaceId)
        assertEquals("offline-draft", result.draftId)
        assertFalse(result.defaultDraftConflict)
        assertFalse(result.adoptCurrentDraft)
    }
}
