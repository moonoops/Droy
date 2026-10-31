package io.droyagent.app.nativeui.settings

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeLocalTransport
import org.json.JSONArray
import org.json.JSONObject

internal class NativeSettingsClient {
    private val transport = NativeLocalTransport()
    suspend fun describe(): JSONObject = call("") as? JSONObject ?: throw NativeApiException("malformed_response")
    suspend fun providers(): JSONObject = call("/providers") as? JSONObject ?: throw NativeApiException("malformed_response")
    suspend fun catalog(): JSONObject {
        val value = transport.json("/droy/native/models")
        if (!value.getBoolean("ok")) throw NativeApiException("request_failed")
        return value.getJSONObject("result")
    }
    suspend fun metadata(refs: List<String>): JSONObject = call("/credentials/describe", JSONObject().put("refs", JSONArray(refs))) as? JSONObject
        ?: throw NativeApiException("malformed_response")
    suspend fun mutate(body: JSONObject): JSONObject = call("/mutate", body) as? JSONObject ?: throw NativeApiException("malformed_response")
    suspend fun setCredential(ref: String, key: String): JSONObject = call("/credentials/set", JSONObject().put("ref", ref).put("value", key)) as? JSONObject
        ?: throw NativeApiException("malformed_response")
    suspend fun clearCredential(ref: String): JSONObject = call("/credentials/unset", JSONObject().put("ref", ref)) as? JSONObject
        ?: throw NativeApiException("malformed_response")
    suspend fun discover(body: JSONObject): JSONArray = call("/discover-models", body) as? JSONArray ?: throw NativeApiException("malformed_response")
    suspend fun setDefault(provider: String, model: String): JSONObject = call("/default-model", JSONObject().put("provider", provider).put("model", model)) as? JSONObject
        ?: throw NativeApiException("malformed_response")
    suspend fun removeProvider(provider: String, revision: Long): JSONObject = call("/providers/remove",
        JSONObject().put("provider", provider).put("expectedRevision", revision)) as? JSONObject ?: throw NativeApiException("malformed_response")
    suspend fun verification(): JSONObject = checkedSetup(transport.json("/droy/setup/status"))
    suspend fun testDefault(): JSONObject = checkedSetup(transport.json("/droy/setup/model-test", "POST", JSONObject()))
    private fun checkedSetup(value: JSONObject): JSONObject {
        if (!value.getBoolean("ok")) throw NativeApiException(value.stringOrNull("code") ?: "request_failed")
        return value
    }
    private suspend fun call(path: String, body: JSONObject? = null): Any {
        val envelope = transport.json("/droy/native/settings$path", if (body == null) "GET" else "POST", body)
        if (!envelope.getBoolean("ok")) throw NativeApiException(envelope.getJSONObject("error").getString("code"))
        return envelope.get("result")
    }
}
