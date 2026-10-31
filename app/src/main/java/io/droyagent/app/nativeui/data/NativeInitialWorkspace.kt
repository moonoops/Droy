package io.droyagent.app.nativeui.data

internal data class NativeInitialWorkspaceBinding(
    val workspaceId: String?,
    val draftId: String,
    val adoptCurrentDraft: Boolean = false,
    val defaultDraftConflict: Boolean = false,
)

internal data class NativeUserSelection(val sessionId: String?, val workspaceId: String?)

/** Only proven companion identities invalidate a saved choice; a newly created user Session may not be listed yet. */
internal fun recoverUserSelection(
    selectedSessionId: String?, selectedWorkspaceId: String?,
    lastUserSessionId: String?, lastUserWorkspaceId: String?, hasUserSelection: Boolean,
    sessions: List<NativeSession>, workspaces: List<NativeWorkspace>, defaultWorkspaceId: String?,
    managedSessionIds: Set<String>, managedWorkspaceIds: Set<String>,
): NativeUserSelection? {
    if (selectedSessionId !in managedSessionIds && selectedWorkspaceId !in managedWorkspaceIds) return null
    val savedSession = sessions.firstOrNull { it.id == lastUserSessionId && it.id !in managedSessionIds && it.workspaceId !in managedWorkspaceIds }
    if (savedSession != null) return NativeUserSelection(savedSession.id, savedSession.workspaceId)
    val workspace = when {
        hasUserSelection && lastUserWorkspaceId == null -> null
        hasUserSelection && workspaces.any { it.id == lastUserWorkspaceId && !it.readOnly && it.id !in managedWorkspaceIds } -> lastUserWorkspaceId
        else -> defaultWorkspaceId?.takeIf { id -> workspaces.any { it.id == id && !it.readOnly && it.id !in managedWorkspaceIds } }
    }
    return NativeUserSelection(null, workspace)
}

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
