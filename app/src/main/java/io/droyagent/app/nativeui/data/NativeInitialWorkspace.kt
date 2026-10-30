package io.droyagent.app.nativeui.data

internal data class NativeInitialWorkspaceBinding(
    val workspaceId: String?,
    val draftId: String,
    val adoptCurrentDraft: Boolean = false,
    val defaultDraftConflict: Boolean = false,
)

/** Editing before the catalog arrives does not choose Ungrouped. Never replace another space's draft. */
internal fun bindInitialWorkspace(
    currentDraftId: String,
    currentHasContent: Boolean,
    defaultWorkspaceId: String?,
    defaultDraftId: String?,
    defaultHasContent: Boolean,
): NativeInitialWorkspaceBinding {
    if (defaultWorkspaceId == null) return NativeInitialWorkspaceBinding(null, currentDraftId)
    if (defaultDraftId != null && defaultDraftId != currentDraftId && defaultHasContent) {
        return if (currentHasContent) NativeInitialWorkspaceBinding(null, currentDraftId, defaultDraftConflict = true)
        else NativeInitialWorkspaceBinding(defaultWorkspaceId, defaultDraftId)
    }
    return NativeInitialWorkspaceBinding(defaultWorkspaceId, currentDraftId, adoptCurrentDraft = true)
}
