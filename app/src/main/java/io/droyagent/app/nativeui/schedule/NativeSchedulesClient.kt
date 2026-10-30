package io.droyagent.app.nativeui.schedule

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeLocalTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

internal interface NativeSchedulesRepository {
    suspend fun catalog(): List<NativeScheduleEntry>
    suspend fun targets(): NativeScheduleTargets
    suspend fun create(draft: NativeScheduleDraft): NativeScheduleRecord
    suspend fun update(request: NativeScheduleUpdate): NativeScheduleUpdateResult
    suspend fun delete(key: NativeScheduleKey): NativeScheduleDeleteResult
    suspend fun history(key: NativeScheduleKey, before: String?): NativeScheduleHistoryResult
}

internal class NativeSchedulesClient : NativeSchedulesRepository {
    private val transport = NativeLocalTransport()
    private val base = "/droy/native/schedules"
    override suspend fun catalog(): List<NativeScheduleEntry> = withContext(Dispatchers.Default) { NativeScheduleJson.catalog(call(base)) }
    override suspend fun targets(): NativeScheduleTargets = withContext(Dispatchers.Default) { NativeScheduleJson.targets(
        objectResult(call("/droy/native/workspaces")), objectResult(call("/droy/native/session/list", JSONObject()))) }
    override suspend fun create(draft: NativeScheduleDraft): NativeScheduleRecord = withContext(Dispatchers.Default) {
        NativeScheduleJson.record(objectResult(call("$base/create", NativeScheduleJson.createValue(draft)))) }
    override suspend fun update(request: NativeScheduleUpdate): NativeScheduleUpdateResult = withContext(Dispatchers.Default) {
        NativeScheduleJson.updateResult(request.key, objectResult(call("$base/update", NativeScheduleJson.updateValue(request)))) }
    override suspend fun delete(key: NativeScheduleKey): NativeScheduleDeleteResult = withContext(Dispatchers.Default) {
        NativeScheduleJson.deleteResult(key, objectResult(call("$base/delete", NativeScheduleJson.keyValue(key)))) }
    override suspend fun history(key: NativeScheduleKey, before: String?): NativeScheduleHistoryResult = withContext(Dispatchers.Default) {
        NativeScheduleJson.history(key, objectResult(call("$base/history", NativeScheduleJson.keyValue(key).put("limit", 30).apply { before?.let { put("before", it) } }))) }

    private fun objectResult(value: Any): JSONObject = value as? JSONObject ?: throw NativeApiException("malformed_response")
    private suspend fun call(path: String, body: JSONObject? = null): Any {
        val value = transport.json(path, if (body == null) "GET" else "POST", body)
        try {
            val ok = value.get("ok") as? Boolean ?: throw NativeApiException("malformed_response")
            if (!ok) throw NativeApiException(value.getJSONObject("error").getString("code"))
            return value.get("result").takeUnless { it === JSONObject.NULL } ?: throw NativeApiException("malformed_response")
        } catch (error: JSONException) { throw NativeApiException("malformed_response", error) }
    }
}
