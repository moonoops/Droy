package io.droyagent.app.nativeui.advanced

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeLocalTransport
import org.json.JSONObject

internal class NativeAdvancedClient {
    private val transport = NativeLocalTransport()
    suspend fun call(path: String = "", body: JSONObject? = null): JSONObject {
        val response = transport.json("/droy/native/advanced$path", if (body == null) "GET" else "POST", body)
        if (!response.optBoolean("ok")) throw NativeApiException(response.optJSONObject("error")?.optString("code") ?: "request_failed")
        return response.optJSONObject("result") ?: throw NativeApiException("malformed_response")
    }
}
