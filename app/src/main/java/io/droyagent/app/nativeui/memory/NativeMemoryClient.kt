package io.droyagent.app.nativeui.memory

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeLocalTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

internal interface NativeMemoryRepository {
    suspend fun status(): NativeMemoryStatus
    suspend fun endpoints(): List<NativeMemoryEndpoint>
    suspend fun apps(): NativeMemoryApps
    suspend fun query(filter: NativeMemoryFilter, cursor: String? = null): NativeMemoryQueryResult
    suspend fun get(id: String): NativeMemoryRecord?
    suspend fun mutate(value: NativeMemoryMutation): Any?
}

internal class NativeMemoryClient : NativeMemoryRepository {
    private val transport = NativeLocalTransport()
    private val path = "/droy/native/memory"
    override suspend fun status() = withContext(Dispatchers.Default) { NativeMemoryJson.status(objectValue(call())) }
    override suspend fun endpoints() = withContext(Dispatchers.Default) { NativeMemoryJson.endpoints(call("endpoints")) }
    override suspend fun apps() = withContext(Dispatchers.Default) { NativeMemoryJson.apps(call("capture", JSONObject().put("operation", "apps").put("args", JSONObject()))) }
    override suspend fun query(filter: NativeMemoryFilter, cursor: String?) = withContext(Dispatchers.Default) {
        NativeMemoryJson.query(objectValue(call("query", NativeMemoryJson.filterValue(filter, cursor)))) }
    override suspend fun get(id: String) = withContext(Dispatchers.Default) { NativeMemoryJson.recordOrNull(call("get", JSONObject().put("recordId", id))) }
    override suspend fun mutate(value: NativeMemoryMutation): Any? = withContext(Dispatchers.Default) { call(value.operation, JSONObject(value.argsJson)) }

    private fun objectValue(value: Any): JSONObject = value as? JSONObject ?: throw NativeApiException("malformed_response")
    private suspend fun call(operation: String? = null, args: JSONObject = JSONObject()): Any {
        val body = operation?.let { JSONObject().put("operation", it).put("args", args) }
        val value = transport.json(path, if (body == null) "GET" else "POST", body)
        try {
            val ok = value.get("ok") as? Boolean ?: throw NativeApiException("malformed_response")
            if (!ok) throw NativeApiException(value.getJSONObject("error").getString("code"))
            return value.get("result")
        } catch (error: JSONException) { throw NativeApiException("malformed_response", error) }
    }
}
