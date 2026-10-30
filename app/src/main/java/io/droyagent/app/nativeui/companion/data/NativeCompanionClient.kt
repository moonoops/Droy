package io.droyagent.app.nativeui.companion.data

import android.util.Base64
import io.droyagent.app.nativeui.data.NativeApiClient
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeLocalTransport
import io.droyagent.app.nativeui.data.NativeTransportEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class NativeCompanionPage(
    val messages: List<NativeCompanionMessage>, val hasMore: Boolean,
    val nextBefore: String?, val nextAfter: String, val status: NativeCompanionStatus,
)

internal class NativeCompanionClient {
    private val transport = NativeLocalTransport()
    private val base = "/droy/companions"
    private fun segment(value: String) = NativeApiClient.encodeSegment(value)
    suspend fun root(): JSONObject = transport.json(base)
    fun events(): Flow<NativeTransportEvent> = transport.events("$base/events")
    suspend fun page(id: String, before: String? = null): NativeCompanionPage {
        val value = transport.json("$base/${segment(id)}/messages" + (before?.let { "?before=${segment(it)}" } ?: ""))
        return NativeCompanionPage(value.getJSONArray("messages").objects().map(NativeCompanionJson::message), value.getBoolean("hasMore"),
            value.stringOrNull("nextBefore"), value.getString("nextAfter"), NativeCompanionJson.status(value.getJSONObject("status")))
    }
    suspend fun send(id: String, payload: JSONObject): NativeCompanionMessage = NativeCompanionJson.message(
        transport.json("$base/${segment(id)}/messages", "POST", payload))
    suspend fun read(id: String, messageId: String) = transport.json("$base/${segment(id)}/read", "POST", JSONObject().put("messageId", messageId))
    suspend fun conversation(id: String, action: String) = transport.json("$base/${segment(id)}/conversation", "POST", JSONObject().put("action", action))
    suspend fun deleteMessage(id: String, messageId: String) = transport.json("$base/${segment(id)}/messages/${segment(messageId)}", "DELETE")
    suspend fun retry(id: String) = transport.json("$base/${segment(id)}/retry", "POST", JSONObject())
    suspend fun voiceTranscript(id: String, messageId: String, text: String): NativeCompanionMessage = NativeCompanionJson.message(
        transport.json("$base/${segment(id)}/messages/${segment(messageId)}/transcript", "PATCH", JSONObject().put("text", text)))
    suspend fun cancel(id: String, taskId: String? = null) = transport.json("$base/${segment(id)}/cancel", "POST", JSONObject().apply { taskId?.let { put("taskId", it) } })
    suspend fun updateProfile(id: String, patch: NativeCompanionProfilePatch) = transport.json("$base/${segment(id)}", "PATCH", NativeCompanionJson.patch(patch))
    suspend fun updateUser(name: String, avatarUrl: String?, bio: String? = null) = NativeCompanionJson.user(transport.json("$base/user-profile", "PATCH",
        JSONObject().put("name", name).apply { avatarUrl?.let { put("avatarUrl", it) }; bio?.let { put("bio", it) } }))
    suspend fun upload(id: String?, bytes: ByteArray, mediaType: String, name: String): NativeCompanionAttachment = withContext(Dispatchers.IO) {
        if (bytes.isEmpty() || bytes.size > 20 * 1024 * 1024) throw NativeApiException("COMPANION_REQUEST_TOO_LARGE")
        val owner = id?.let(::segment) ?: "user-profile"
        val payload = JSONObject().put("name", name).put("mediaType", mediaType).put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        NativeCompanionJson.attachment(transport.json("$base/$owner/uploads", "POST", payload))
    }
    suspend fun media(url: String): ByteArray {
        if (!url.startsWith("$base/") || url.contains('#') || url.contains('\\')) throw NativeApiException("COMPANION_ATTACHMENT_SCOPE")
        val path = try { java.net.URI(url).normalize().path } catch (error: java.net.URISyntaxException) { throw NativeApiException("COMPANION_ATTACHMENT_SCOPE", error) }
        if (!path.startsWith("$base/") || path.split('/').any { it == "." || it == ".." }) throw NativeApiException("COMPANION_ATTACHMENT_SCOPE")
        return transport.bytes(url)
    }
}
