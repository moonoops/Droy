package io.droyagent.app.nativeui

import io.droyagent.app.nativeui.data.bindInitialWorkspace
import org.junit.Assert.*
import org.junit.Test

class NativeInitialWorkspaceTest {
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
