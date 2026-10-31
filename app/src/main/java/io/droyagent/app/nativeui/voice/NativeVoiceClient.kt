package io.droyagent.app.nativeui.voice

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeLocalTransport
import org.json.JSONObject

internal interface NativeVoiceGateway {
    suspend fun read(): NativeVoiceSnapshot
    suspend fun standard(enabled: Boolean)
    suspend fun saveTts(draft: NativeVoiceTtsDraft)
    suspend fun credential(value: String?)
    suspend fun saveWake(draft: NativeVoiceWakeDraft, commitOnly: Boolean)
    suspend fun resource(provider: String, cancel: Boolean)
    suspend fun prepareWake()
    suspend fun preview(owner: String, draft: NativeVoiceTtsDraft)
    suspend fun stopPreview(owner: String)
}
internal class NativeVoiceClient : NativeVoiceGateway {
    private val transport = NativeLocalTransport()
    private suspend fun call(path: String = "", body: JSONObject? = null): JSONObject {
        val envelope = transport.json("/droy/native/voice$path", if (body == null) "GET" else "POST", body)
        if (!envelope.getBoolean("ok")) throw NativeApiException(envelope.getJSONObject("error").getString("code"))
        return envelope.getJSONObject("result")
    }
    override suspend fun read() = NativeVoiceJson.parse(call())
    override suspend fun standard(enabled: Boolean) { call("/settings", JSONObject().put("patch", JSONObject().put("entries", JSONObject().put("standard", enabled)))) }
    override suspend fun saveTts(draft: NativeVoiceTtsDraft) { call("/settings", JSONObject().put("patch", JSONObject().put("tts", NativeVoiceJson.tts(draft)))) }
    override suspend fun credential(value: String?) {
        val request = JSONObject().put("operation", if (value == null) "clear" else "set")
        if (value != null) request.put("value", value)
        call("/credentials", request)
    }
    override suspend fun saveWake(draft: NativeVoiceWakeDraft, commitOnly: Boolean) { call("/wake", JSONObject().put("patch", NativeVoiceJson.wake(draft)).put("commitOnly", commitOnly)) }
    override suspend fun resource(provider: String, cancel: Boolean) { call("/resources", JSONObject().put("provider", provider).put("operation", if (cancel) "cancel" else "download")) }
    override suspend fun prepareWake() { call("/wake/prepare", JSONObject()) }
    override suspend fun preview(owner: String, draft: NativeVoiceTtsDraft) { call("/preview", JSONObject().put("operation", "start").put("owner", owner)
        .put("provider", draft.provider).put("voiceId", draft.voiceId).put("rate", draft.rate)) }
    override suspend fun stopPreview(owner: String) { call("/preview", JSONObject().put("operation", "stop").put("owner", owner)) }
}
