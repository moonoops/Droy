package io.droyagent.app.nativeui.workspaces

import io.droyagent.app.nativeui.data.NativeRuntimeState

data class NativeWorkspaceRow(val id: String, val path: String, val title: String, val sessionIds: List<String>, val readOnly: Boolean)
data class NativeWorkspaceSession(val id: String, val title: String?, val running: Boolean, val blank: Boolean,
    val readOnly: Boolean, val workspaceId: String?, val parentSessionId: String? = null, val origin: String? = null,
    val needsMigration: Boolean = false)
data class NativeWorkspacesCatalog(val workspaces: List<NativeWorkspaceRow>, val sessions: List<NativeWorkspaceSession>,
    val defaultWorkspaceId: String?, val pinnedSessionIds: List<String>, val archivedSessionIds: List<String>,
    val directoryBrowsingAvailable: Boolean)
data class NativeWorkspaceDirectoryEntry(val name: String, val path: String, val hidden: Boolean)
data class NativeWorkspaceDirectory(val path: String, val home: String, val crumbs: List<NativeWorkspaceDirectoryEntry>,
    val entries: List<NativeWorkspaceDirectoryEntry>, val truncated: Boolean)
enum class NativeWorkspaceEditorKind { SESSION_TITLE, WORKSPACE_TITLE, WORKSPACE_NAME, DIRECTORY_NAME }
data class NativeWorkspaceEditor(val kind: NativeWorkspaceEditorKind, val targetId: String, val text: String,
    val original: String = text)
data class NativeWorkspaceOperation(val requestId: String, val kind: String, val argumentsJson: String)
data class NativeWorkspaceUncertain(val operation: NativeWorkspaceOperation, val checked: Boolean = false)
data class NativeWorkspaceReceipt(val requestId: String, val kind: String, val state: String,
    val resultSessionId: String? = null, val workspaceId: String? = null, val title: String? = null,
    val errorCode: String? = null, val renameUnconfirmed: Boolean = false, val path: String? = null)
data class NativeWorkspacesState(val runtime: NativeRuntimeState = NativeRuntimeState(), val catalog: NativeWorkspacesCatalog? = null,
    val loading: Boolean = false, val busy: Boolean = false, val error: String? = null,
    val showArchived: Boolean = false, val editor: NativeWorkspaceEditor? = null,
    val directoryOpen: Boolean = false, val directory: NativeWorkspaceDirectory? = null,
    val directoryLoading: Boolean = false, val directoryError: String? = null,
    val uncertain: NativeWorkspaceUncertain? = null, val archiveActiveSessionId: String? = null,
    val forkMigrationSessionId: String? = null, val createdSessionId: String? = null, val changedWorkspaceId: String? = null,
    val feedback: String? = null)

sealed interface NativeWorkspacesAction {
    data object Refresh : NativeWorkspacesAction
    data object DismissError : NativeWorkspacesAction
    data class Archived(val show: Boolean) : NativeWorkspacesAction
    data class BeginSessionRename(val id: String) : NativeWorkspacesAction
    data class BeginWorkspaceRename(val id: String) : NativeWorkspacesAction
    data class EditText(val text: String) : NativeWorkspacesAction
    data object CloseEditor : NativeWorkspacesAction
    data object SaveEditor : NativeWorkspacesAction
    data class Pin(val id: String, val pinned: Boolean) : NativeWorkspacesAction
    data class Archive(val id: String, val stopActivity: Boolean = false) : NativeWorkspacesAction
    data class Restore(val id: String) : NativeWorkspacesAction
    data class Fork(val id: String, val allowMigration: Boolean = false) : NativeWorkspacesAction
    data class RemoveWorkspace(val id: String) : NativeWorkspacesAction
    data class MoveWorkspace(val id: String, val beforeId: String?) : NativeWorkspacesAction
    data class MoveSession(val workspaceId: String, val id: String, val beforeId: String?) : NativeWorkspacesAction
    data class BrowseDirectory(val path: String? = null) : NativeWorkspacesAction
    data object CloseDirectory : NativeWorkspacesAction
    data object BeginWorkspaceCreate : NativeWorkspacesAction
    data object BeginDirectoryCreate : NativeWorkspacesAction
    data object RegisterDirectory : NativeWorkspacesAction
    data object CheckUncertain : NativeWorkspacesAction
    data object AcknowledgeUncertain : NativeWorkspacesAction
    data object DismissArchiveActive : NativeWorkspacesAction
    data object DismissForkMigration : NativeWorkspacesAction
    data class ConsumeCreatedSession(val id: String) : NativeWorkspacesAction
    data object ConsumeFeedback : NativeWorkspacesAction
}
