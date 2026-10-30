package io.droyagent.app.nativeui.engine

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeLocalTransport
import org.json.JSONObject

internal interface NativeEngineGateway {
    suspend fun goal(sessionId: String, action: String, objective: String?, expected: NativeEngineGoal?): NativeEngineSession = throw NativeApiException("NATIVE_ENGINE_UNAVAILABLE")
    suspend fun catalog(): NativeEngineCatalog
    suspend fun session(id: String): NativeEngineSession
    suspend fun document(id: String): NativePresetDocument
    suspend fun defaultPreset(id: String, revision: Long): NativeEngineCatalog
    suspend fun defaultPermission(id: String, revision: Long, acceptRisk: Boolean): NativeEngineCatalog
    suspend fun sessionPreset(sessionId: String, id: String): NativeEngineSession
    suspend fun sessionPermission(sessionId: String, id: String, acceptRisk: Boolean): NativeEngineSession
    suspend fun skills(sessionId: String): Pair<String, List<NativeComposerSkill>>
    suspend fun compact(sessionId: String, requestId: String): NativeCompactOperation
    suspend fun compactStatus(sessionId: String, requestId: String): NativeCompactOperation
    suspend fun cancelCompact(sessionId: String, requestId: String): NativeCompactOperation
}
internal class NativeEngineClient : NativeEngineGateway {
    private val transport = NativeLocalTransport()
    private suspend fun call(path: String = "", input: JSONObject? = null): JSONObject {
        val envelope = transport.json("/droy/native/engine$path", if (input == null) "GET" else "POST", input)
        if (!envelope.getBoolean("ok")) throw NativeApiException(envelope.getJSONObject("error").getString("code"))
        return envelope.getJSONObject("result")
    }
    override suspend fun goal(sessionId: String, action: String, objective: String?, expected: NativeEngineGoal?): NativeEngineSession = NativeEngineJson.session(call("/goal",
        JSONObject().put("sessionId", sessionId).put("action", action).apply {
            objective?.let { put("objective", it) }; expected?.let { put("expectedId", it.id); put("expectedRevision", it.revision) }
        }))
    override suspend fun catalog() = NativeEngineJson.catalog(call())
    override suspend fun session(id: String) = NativeEngineJson.session(call("/session", JSONObject().put("sessionId", id)))
    override suspend fun document(id: String) = NativeEngineJson.document(call("/presets/read", JSONObject().put("id", id)))
    override suspend fun defaultPreset(id: String, revision: Long) = NativeEngineJson.catalog(call("/presets/default", JSONObject().put("id", id).put("expectedRevision", revision)))
    override suspend fun defaultPermission(id: String, revision: Long, acceptRisk: Boolean) = NativeEngineJson.catalog(call("/permissions/default", JSONObject().put("id", id).put("expectedRevision", revision).put("acceptRisk", acceptRisk)))
    override suspend fun sessionPreset(sessionId: String, id: String) = NativeEngineJson.session(call("/presets/select", JSONObject().put("sessionId", sessionId).put("id", id)))
    override suspend fun sessionPermission(sessionId: String, id: String, acceptRisk: Boolean) = NativeEngineJson.session(call("/permissions/select", JSONObject().put("sessionId", sessionId).put("id", id).put("acceptRisk", acceptRisk)))
    override suspend fun skills(sessionId: String) = NativeEngineJson.skills(call("/skills", JSONObject().put("sessionId", sessionId)))
    override suspend fun compact(sessionId: String, requestId: String) = compactCall("/compact", sessionId, requestId)
    override suspend fun compactStatus(sessionId: String, requestId: String) = compactCall("/compact/status", sessionId, requestId)
    override suspend fun cancelCompact(sessionId: String, requestId: String) = compactCall("/compact/cancel", sessionId, requestId)
    private suspend fun compactCall(path: String, sessionId: String, requestId: String) = NativeEngineJson.compaction(call(path, JSONObject().put("sessionId", sessionId).put("requestId", requestId)))
}
