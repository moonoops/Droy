package io.droyagent.app.nativeui.workspaces

import android.content.SharedPreferences
import io.droyagent.app.nativeui.data.NativeApiException
import org.json.JSONObject

internal data class NativeWorkspacesSaved(val editor: NativeWorkspaceEditor? = null, val pending: NativeWorkspaceOperation? = null,
    val showArchived: Boolean = false, val directoryOpen: Boolean = false, val directoryPath: String? = null)
internal interface NativeWorkspacesStore {
    fun load(): NativeWorkspacesSaved
    fun save(value: NativeWorkspacesSaved)
}

/** The frozen request is committed before dispatch; restoring it only enables an explicit status check. */
internal class NativeWorkspacesPreferences(private val preferences: SharedPreferences) : NativeWorkspacesStore {
    private var durablePending = false
    override fun load(): NativeWorkspacesSaved = try {
        val raw = preferences.getString("state-v1", null)
        if (raw == null) NativeWorkspacesSaved() else {
            val value = JSONObject(raw); require(value.getInt("version") == 1)
            val editor = value.optJSONObject("editor")?.let {
                NativeWorkspaceEditor(NativeWorkspaceEditorKind.valueOf(it.getString("kind")), it.getString("targetId"), it.getString("text"), it.getString("original")) }
            val pending = value.optJSONObject("pending")?.let { NativeWorkspaceOperation(it.getString("requestId"), it.getString("kind"), it.getJSONObject("args").toString()) }
            durablePending = pending != null
            NativeWorkspacesSaved(editor, pending, value.getBoolean("showArchived"), value.getBoolean("directoryOpen"), if (value.has("directoryPath")) value.getString("directoryPath") else null)
        }
    } catch (error: Exception) { throw NativeApiException("NATIVE_WORKSPACES_STORAGE", error) }
    override fun save(value: NativeWorkspacesSaved) {
        val json = JSONObject().put("version", 1).put("showArchived", value.showArchived).put("directoryOpen", value.directoryOpen)
        value.editor?.let { json.put("editor", JSONObject().put("kind", it.kind.name).put("targetId", it.targetId).put("text", it.text).put("original", it.original)) }
        value.pending?.let { json.put("pending", NativeWorkspaceJson.operation(it)) }; value.directoryPath?.let { json.put("directoryPath", it) }
        val edit = preferences.edit().putString("state-v1", json.toString())
        if (durablePending || value.pending != null) {
            durablePending = true
            if (!edit.commit()) throw NativeApiException("NATIVE_WORKSPACES_STORAGE")
            durablePending = value.pending != null
        } else edit.apply()
    }
}
