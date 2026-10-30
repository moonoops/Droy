package io.droyagent.app.nativeui.workspaces

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeRuntimeState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

internal class NativeWorkspacesCoordinator(private val repository: NativeWorkspacesRepository, private val store: NativeWorkspacesStore,
    private val scope: CoroutineScope, private val nextRequestId: () -> String = { UUID.randomUUID().toString() }) {
    private val mutable = MutableStateFlow(NativeWorkspacesState())
    val state = mutable.asStateFlow()
    private var catalogGeneration = 0L
    private var directoryGeneration = 0L
    private var directoryPath: String? = null
    private var storageBroken = false
    init {
        try {
            val saved = store.load(); directoryPath = saved.directoryPath
            mutable.value = mutable.value.copy(editor = saved.editor, uncertain = saved.pending?.let { NativeWorkspaceUncertain(it) },
                showArchived = saved.showArchived, directoryOpen = saved.directoryOpen)
        } catch (error: Exception) { storageBroken = true; report(code(error)) }
    }
    fun runtime(value: NativeRuntimeState) { mutable.value = state.value.copy(runtime = value) }
    fun report(error: String) { mutable.value = state.value.copy(error = error) }
    fun stopReads() { catalogGeneration++; directoryGeneration++; mutable.value = state.value.copy(loading = false, directoryLoading = false) }
    fun refresh(): Job {
        val generation = ++catalogGeneration
        mutable.value = state.value.copy(loading = true)
        return scope.launch {
            try {
                val value = repository.catalog()
                if (generation == catalogGeneration) mutable.value = state.value.copy(catalog = value, loading = false)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == catalogGeneration) mutable.value = state.value.copy(loading = false, error = code(error))
            }
        }
    }
    fun restoreDirectory() { if (state.value.directoryOpen && state.value.directory == null && !state.value.directoryLoading) browse(directoryPath) }
    fun onAction(action: NativeWorkspacesAction): Job? {
        when (action) {
            NativeWorkspacesAction.Refresh -> return refresh()
            NativeWorkspacesAction.DismissError -> mutable.value = state.value.copy(error = null)
            is NativeWorkspacesAction.Archived -> saveState(state.value.copy(showArchived = action.show))
            is NativeWorkspacesAction.BeginSessionRename -> {
                val row = state.value.catalog?.sessions?.find { it.id == action.id } ?: return null
                if (writable() && !row.readOnly) saveState(state.value.copy(editor = NativeWorkspaceEditor(NativeWorkspaceEditorKind.SESSION_TITLE, row.id, row.title.orEmpty())))
            }
            is NativeWorkspacesAction.BeginWorkspaceRename -> {
                val row = state.value.catalog?.workspaces?.find { it.id == action.id } ?: return null
                if (writable() && !row.readOnly) saveState(state.value.copy(editor = NativeWorkspaceEditor(NativeWorkspaceEditorKind.WORKSPACE_TITLE, row.id, row.title)))
            }
            is NativeWorkspacesAction.EditText -> if (writable() && action.text.length <= 4096) state.value.editor?.let { saveState(state.value.copy(editor = it.copy(text = action.text))) }
            NativeWorkspacesAction.CloseEditor -> if (writable()) saveState(state.value.copy(editor = null))
            NativeWorkspacesAction.SaveEditor -> return saveEditor()
            is NativeWorkspacesAction.Pin -> return sessionMutation(action.id, if (action.pinned) "session.pin" else "session.unpin")
            is NativeWorkspacesAction.Archive -> return sessionMutation(action.id, "session.archive", JSONObject().put("stopActivity", action.stopActivity))
            is NativeWorkspacesAction.Restore -> return sessionMutation(action.id, "session.restore")
            is NativeWorkspacesAction.Fork -> return sessionMutation(action.id, "session.fork", JSONObject().put("allowMigration", action.allowMigration))
            is NativeWorkspacesAction.RemoveWorkspace -> return workspaceMutation(action.id, "workspace.remove")
            is NativeWorkspacesAction.MoveWorkspace -> return workspaceMutation(action.id, "workspace.move", JSONObject().apply { action.beforeId?.let { put("beforeWorkspaceId", it) } })
            is NativeWorkspacesAction.MoveSession -> {
                if (!workspaceWritable(action.workspaceId) || !sessionWritable(action.id)) return null
                return mutate("session.move", JSONObject().put("workspaceId", action.workspaceId).put("sessionId", action.id)
                    .apply { action.beforeId?.let { put("beforeSessionId", it) } })
            }
            is NativeWorkspacesAction.BrowseDirectory -> { if (!state.value.busy && state.value.uncertain == null) return browse(action.path) }
            NativeWorkspacesAction.CloseDirectory -> if (!state.value.busy) {
                directoryGeneration++; saveState(state.value.copy(directoryOpen = false, directoryLoading = false, directoryError = null))
            }
            NativeWorkspacesAction.BeginWorkspaceCreate -> {
                if (writable()) saveState(state.value.copy(editor = NativeWorkspaceEditor(NativeWorkspaceEditorKind.WORKSPACE_NAME, "new", "")))
            }
            NativeWorkspacesAction.BeginDirectoryCreate -> {
                if (writable()) state.value.directory?.let { saveState(state.value.copy(editor = NativeWorkspaceEditor(NativeWorkspaceEditorKind.DIRECTORY_NAME, it.path, ""))) }
            }
            NativeWorkspacesAction.RegisterDirectory -> {
                val path = state.value.directory?.path ?: return null
                return mutate("workspace.register", JSONObject().put("path", path))
            }
            NativeWorkspacesAction.CheckUncertain -> return checkUncertain()
            NativeWorkspacesAction.AcknowledgeUncertain -> {
                if (!state.value.busy && state.value.uncertain?.checked == true) saveState(state.value.copy(uncertain = null, error = null))
            }
            NativeWorkspacesAction.DismissArchiveActive -> mutable.value = state.value.copy(archiveActiveSessionId = null)
            NativeWorkspacesAction.DismissForkMigration -> mutable.value = state.value.copy(forkMigrationSessionId = null)
            is NativeWorkspacesAction.ConsumeCreatedSession -> if (state.value.createdSessionId == action.id) mutable.value = state.value.copy(createdSessionId = null)
            NativeWorkspacesAction.ConsumeFeedback -> mutable.value = state.value.copy(feedback = null, changedWorkspaceId = null)
        }
        return null
    }
    private fun writable(): Boolean = !storageBroken && !state.value.busy && state.value.uncertain == null
    private fun sessionWritable(id: String) = state.value.catalog?.sessions?.any { it.id == id && !it.readOnly } == true
    private fun workspaceWritable(id: String) = state.value.catalog?.workspaces?.any { it.id == id && !it.readOnly } == true
    private fun sessionMutation(id: String, kind: String, args: JSONObject = JSONObject()): Job? {
        if (!sessionWritable(id)) return null
        return mutate(kind, args.put("sessionId", id))
    }
    private fun workspaceMutation(id: String, kind: String, args: JSONObject = JSONObject()): Job? {
        if (!workspaceWritable(id)) return null
        return mutate(kind, args.put("workspaceId", id))
    }
    private fun saveEditor(): Job? {
        val editor = state.value.editor ?: return null
        val text = editor.text.trim()
        if (text.isEmpty()) { report("NATIVE_WORKSPACES_BAD_REQUEST"); return null }
        return when (editor.kind) {
            NativeWorkspaceEditorKind.SESSION_TITLE -> sessionMutation(editor.targetId, "session.rename", JSONObject().put("title", text))
            NativeWorkspaceEditorKind.WORKSPACE_TITLE -> workspaceMutation(editor.targetId, "workspace.rename", JSONObject().put("title", text))
            NativeWorkspaceEditorKind.WORKSPACE_NAME -> mutate("workspace.create", JSONObject().put("name", text))
            NativeWorkspaceEditorKind.DIRECTORY_NAME -> mutate("directory.create", JSONObject().put("path", editor.targetId).put("name", text))
        }
    }
    private fun mutate(kind: String, args: JSONObject): Job? {
        if (!writable() || !state.value.runtime.ready) return null
        val op = NativeWorkspaceOperation(nextRequestId(), kind, args.toString())
        val editor = state.value.editor
        if (!saveState(state.value.copy(busy = true, uncertain = NativeWorkspaceUncertain(op), error = null,
            archiveActiveSessionId = null, forkMigrationSessionId = null, loading = false))) return null
        catalogGeneration++
        val job = scope.launch {
            try {
                val receipt = repository.mutate(op)
                applyReceipt(op, receipt, editor)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val errorCode = code(error)
                if (errorCode in admissionErrors) saveState(state.value.copy(busy = false, uncertain = null, error = errorCode))
                else saveState(state.value.copy(busy = false, uncertain = NativeWorkspaceUncertain(op), error = errorCode))
            } finally { mutable.value = state.value.copy(busy = false) }
        }
        return job
    }
    private suspend fun applyReceipt(op: NativeWorkspaceOperation, receipt: NativeWorkspaceReceipt, editor: NativeWorkspaceEditor?) {
        if (receipt.requestId != op.requestId || receipt.kind != op.kind) throw NativeApiException("malformed_response")
        val args = JSONObject(op.argumentsJson)
        when (receipt.state) {
            "complete" -> {
                val fresh = state.value.copy(busy = false, uncertain = null, editor = if (state.value.editor == editor) null else state.value.editor,
                    directoryOpen = if (op.kind == "workspace.register") false else state.value.directoryOpen,
                    createdSessionId = receipt.resultSessionId ?: state.value.createdSessionId,
                    changedWorkspaceId = receipt.workspaceId ?: state.value.changedWorkspaceId,
                    feedback = if (receipt.renameUnconfirmed) "fork_title_unconfirmed" else "changed", error = null)
                if (saveState(fresh)) {
                    refresh()
                    if (op.kind == "directory.create" && state.value.directoryOpen) {
                        val path = receipt.path ?: throw NativeApiException("malformed_response"); browse(path)
                    }
                }
            }
            "failed" -> {
                val error = receipt.errorCode ?: "NATIVE_WORKSPACES_FAILED"
                saveState(state.value.copy(busy = false, uncertain = null, error = if (error in setOf("workspace/session-active", "session/migration-required")) null else error,
                    archiveActiveSessionId = if (error == "workspace/session-active") args.getString("sessionId") else null,
                    forkMigrationSessionId = if (error == "session/migration-required") args.getString("sessionId") else null))
            }
            "unknown", "missing" -> saveState(state.value.copy(busy = false, uncertain = NativeWorkspaceUncertain(op), error = receipt.errorCode))
            else -> throw NativeApiException("malformed_response")
        }
    }
    private fun checkUncertain(): Job? {
        val uncertain = state.value.uncertain ?: return null
        if (state.value.busy || !state.value.runtime.ready) return null
        mutable.value = state.value.copy(busy = true, error = null)
        val job = scope.launch {
            try {
                val receipt = repository.status(uncertain.operation)
                if (receipt.requestId != uncertain.operation.requestId || receipt.kind != uncertain.operation.kind) throw NativeApiException("malformed_response")
                if (receipt.state == "complete" || receipt.state == "failed") applyReceipt(uncertain.operation, receipt, state.value.editor)
                else {
                    val catalog = repository.catalog()
                    catalogGeneration++
                    saveState(state.value.copy(busy = false, catalog = catalog, uncertain = uncertain.copy(checked = true), loading = false, error = null))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value = state.value.copy(busy = false, error = code(error))
            } finally { mutable.value = state.value.copy(busy = false) }
        }
        return job
    }
    private fun browse(path: String?): Job {
        val generation = ++directoryGeneration
        mutable.value = state.value.copy(directoryOpen = true, directoryLoading = true, directoryError = null)
        return scope.launch {
            try {
                val value = repository.directory(path)
                if (generation == directoryGeneration) {
                    directoryPath = value.path
                    saveState(state.value.copy(directory = value, directoryOpen = true, directoryLoading = false, directoryError = null))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == directoryGeneration) mutable.value = state.value.copy(directoryLoading = false, directoryError = code(error))
            }
        }
    }
    private fun saveState(value: NativeWorkspacesState): Boolean {
        if (storageBroken) { report("NATIVE_WORKSPACES_STORAGE"); return false }
        try {
            store.save(NativeWorkspacesSaved(value.editor, value.uncertain?.operation, value.showArchived, value.directoryOpen, directoryPath))
            mutable.value = value; return true
        } catch (error: Exception) {
            // A failed commit may have reached storage. Keep the frozen request
            // in memory and require an explicit check rather than a new send.
            mutable.value = state.value.copy(busy = false, uncertain = value.uncertain ?: state.value.uncertain, error = code(error))
            return false
        }
    }
    private fun code(error: Exception) = (error as? NativeApiException)?.code ?: "NATIVE_WORKSPACES_FAILED"
    companion object {
        private val admissionErrors = setOf("NATIVE_WORKSPACES_BAD_REQUEST", "NATIVE_WORKSPACES_NOT_READY", "NATIVE_WORKSPACES_BUSY",
            "COMPANION_WORKSPACE_MANAGED", "COMPANION_SESSION_MANAGED", "NATIVE_WORKSPACES_SUBAGENT", "session/not-found")
    }
}
