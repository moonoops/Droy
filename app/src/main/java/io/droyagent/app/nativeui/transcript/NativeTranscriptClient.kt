package io.droyagent.app.nativeui.transcript

import io.droyagent.app.nativeui.data.NativeApiClient
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeHttpErrors
import io.droyagent.app.nativeui.data.NativeLocalTransport
import io.droyagent.app.nativeui.data.NativeTransportEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import org.json.JSONObject

/** Deliberately read-only: no generic session activation, prompt, cancellation or approval methods. */
interface NativeTranscriptGateway {
    fun follow(address: NativeSubagentTranscriptAddress, maxMessages: Int): Flow<JSONObject>
    suspend fun page(address: NativeSubagentTranscriptAddress, throughSeq: Long, beforeSeq: Long, maxMessages: Int): JSONObject
}
interface NativeTranscriptTransport {
    fun events(path: String): Flow<NativeTransportEvent>
    suspend fun post(path: String, body: JSONObject): JSONObject
}
private class NativeTranscriptLocalTransport : NativeTranscriptTransport {
    private val transport = NativeLocalTransport()
    override fun events(path: String) = transport.events(path)
    override suspend fun post(path: String, body: JSONObject) = transport.json(path, "POST", body)
}

class NativeTranscriptClient(private val transport: NativeTranscriptTransport = NativeTranscriptLocalTransport()) : NativeTranscriptGateway {
    override fun follow(address: NativeSubagentTranscriptAddress, maxMessages: Int): Flow<JSONObject> {
        require(maxMessages > 0)
        val path = "/droy/native/transcript/follow?parentSessionId=${NativeApiClient.encodeSegment(address.parentSessionId)}" +
            "&childSessionId=${NativeApiClient.encodeSegment(address.childSessionId)}&maxMessages=$maxMessages"
        return transport.events(path).transform { event ->
            if (event is NativeTransportEvent.Frame) {
                checked(event.value)
                emit(event.value)
            }
        }
    }
    override suspend fun page(address: NativeSubagentTranscriptAddress, throughSeq: Long, beforeSeq: Long, maxMessages: Int): JSONObject {
        require(throughSeq >= 0 && beforeSeq >= 0 && beforeSeq <= throughSeq && maxMessages > 0)
        val envelope = transport.post("/droy/native/session/page", JSONObject().put("address", address.wire())
            .put("throughSeq", throughSeq).put("beforeSeq", beforeSeq).put("maxMessages", maxMessages))
        checked(envelope)
        return envelope.optJSONObject("result") ?: throw NativeApiException("malformed_response")
    }
    private fun checked(value: JSONObject) {
        if (value.has("ok") && !value.getBoolean("ok")) throw NativeApiException(NativeHttpErrors.code(200, value.toString()))
    }
}
