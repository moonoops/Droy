package io.droyagent.app.nativeui.voiceinput

import android.util.Base64
import io.droyagent.app.nativeui.data.NativeApiClient
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeLocalTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class NativeSpeechClient {
    private val transport = NativeLocalTransport()
    private suspend fun call(path: String, body: JSONObject? = null): JSONObject {
        val value = transport.json("/droy/native/speech/$path", if (body == null) "GET" else "POST", body)
        if (!value.getBoolean("ok")) throw NativeApiException(value.getJSONObject("error").getString("code"))
        return value.getJSONObject("result")
    }
    suspend fun catalog(): NativeSpeechCatalog {
        val value = call("catalog")
        val providers = value.getJSONArray("providers")
        val local = (0 until providers.length()).map(providers::getJSONObject).firstOrNull { it.getString("id") == "droy-sensevoice-local" }
        return NativeSpeechCatalog(value.getLong("maxAudioBytes"), value.getDouble("maxDurationSeconds"), value.getLong("nativeMaxAudioBytes"),
            local?.getJSONObject("preparation")?.getString("phase") in setOf("ready", "standby", "waking"))
    }
    suspend fun transcribe(bytes: ByteArray, operationId: String): NativeSpeechResult = withContext(Dispatchers.IO) {
        val request = JSONObject().put("audioBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("providerId", "droy-sensevoice-local").put("language", "auto")
        result(call("transcribe", JSONObject().put("operationId", operationId).put("request", request)))
    }
    suspend fun operation(operationId: String): NativeSpeechOperation = operationValue(call("operations/${NativeApiClient.encodeSegment(operationId)}"))
    suspend fun cancel(operationId: String): NativeSpeechOperation = operationValue(call("operations/${NativeApiClient.encodeSegment(operationId)}/cancel", JSONObject()))
    private fun operationValue(value: JSONObject) = NativeSpeechOperation(value.getString("phase"), value.getBoolean("quiescent"), value.getBoolean("settled"), value.optJSONObject("result")?.let { result(it) })
    private fun result(value: JSONObject): NativeSpeechResult {
        val text = cleanVoiceTranscript(value.getString("text"))
        if (text.length > 32768) throw NativeApiException("native_voice_transcript_limit")
        return NativeSpeechResult(text, value.getDouble("audioSeconds"), value.getDouble("inferenceSeconds"))
    }
}
