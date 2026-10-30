package io.droyagent.app.nativeui.workspaces

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeLocalTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal interface NativeWorkspacesRepository {
    suspend fun catalog(): NativeWorkspacesCatalog
    suspend fun directory(path: String?): NativeWorkspaceDirectory
    suspend fun mutate(operation: NativeWorkspaceOperation): NativeWorkspaceReceipt
    suspend fun status(operation: NativeWorkspaceOperation): NativeWorkspaceReceipt
}

internal class NativeWorkspacesClient : NativeWorkspacesRepository {
    private val transport = NativeLocalTransport()
    private val base = "/droy/native/workspaces"
    override suspend fun catalog() = NativeWorkspaceJson.catalog(call(""))
    override suspend fun directory(path: String?) = NativeWorkspaceJson.directory(call("/directory/list", JSONObject().apply { path?.let { put("path", it) } }))
    override suspend fun mutate(operation: NativeWorkspaceOperation) = NativeWorkspaceJson.receipt(call("/mutate", NativeWorkspaceJson.operation(operation)))
    override suspend fun status(operation: NativeWorkspaceOperation) = NativeWorkspaceJson.receipt(call("/operations/status", NativeWorkspaceJson.operation(operation)))
    private suspend fun call(path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.Default) {
        val value = transport.json(base + path, if (body == null) "GET" else "POST", body)
        try {
            if (value.get("ok") !is Boolean) throw NativeApiException("malformed_response")
            if (!value.getBoolean("ok")) throw NativeApiException(value.getJSONObject("error").getString("code"))
            value.getJSONObject("result")
        } catch (error: JSONException) { throw NativeApiException("malformed_response", error) }
    }
}

internal object NativeWorkspaceJson {
    private fun malformed(): Nothing = throw NativeApiException("malformed_response")
    private fun string(value: JSONObject, key: String): String = (value.get(key) as? String)?.takeIf { it.isNotEmpty() } ?: malformed()
    private fun optional(value: JSONObject, key: String): String? = if (!value.has(key) || value.isNull(key)) null else string(value, key)
    private fun bool(value: JSONObject, key: String): Boolean = value.get(key) as? Boolean ?: malformed()
    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { (array.get(it) as? String)?.takeIf(String::isNotBlank) ?: malformed() }
    private inline fun <T> decode(block: () -> T): T = try { block() } catch (error: JSONException) { throw NativeApiException("malformed_response", error) }
    private fun <T> rows(value: JSONObject, key: String, parse: (JSONObject) -> T): List<T> {
        val array = value.getJSONArray(key); return (0 until array.length()).map { parse(array.getJSONObject(it)) }
    }
    fun catalog(value: JSONObject): NativeWorkspacesCatalog = decode {
        val spaces = rows(value, "workspaces") { NativeWorkspaceRow(string(it, "id"), string(it, "path"), string(it, "title"), strings(it.getJSONArray("sessionIds")), bool(it, "readOnly")) }
        val sessions = rows(value, "sessions") { NativeWorkspaceSession(string(it, "id"), optional(it, "title"), bool(it, "running"), bool(it, "blank"),
            bool(it, "readOnly"), optional(it, "workspaceId"), optional(it, "parentSessionId"), optional(it, "origin"), bool(it, "needsMigration")) }
        if (spaces.map { it.id }.distinct().size != spaces.size || sessions.map { it.id }.distinct().size != sessions.size) malformed()
        NativeWorkspacesCatalog(spaces, sessions, optional(value, "defaultWorkspaceId"), strings(value.getJSONArray("pinnedSessionIds")),
            strings(value.getJSONArray("archivedSessionIds")), bool(value, "directoryBrowsingAvailable"))
    }
    private fun entry(value: JSONObject) = NativeWorkspaceDirectoryEntry(string(value, "name"), string(value, "path"), bool(value, "hidden"))
    fun directory(value: JSONObject): NativeWorkspaceDirectory = decode { NativeWorkspaceDirectory(string(value, "path"), string(value, "home"),
        rows(value, "crumbs", ::entry), rows(value, "entries", ::entry), bool(value, "truncated")) }
    fun receipt(value: JSONObject): NativeWorkspaceReceipt = decode {
        val state = string(value, "state"); if (state !in setOf("complete", "failed", "unknown", "missing")) malformed()
        NativeWorkspaceReceipt(string(value, "requestId"), string(value, "kind"), state, optional(value, "resultSessionId"), optional(value, "workspaceId"),
            optional(value, "title"), optional(value, "errorCode"), if (value.has("renameUnconfirmed")) bool(value, "renameUnconfirmed") else false, optional(value, "path"))
    }
    fun operation(value: NativeWorkspaceOperation): JSONObject = JSONObject().put("requestId", value.requestId).put("kind", value.kind).put("args", JSONObject(value.argumentsJson))
}
