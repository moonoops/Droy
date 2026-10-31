package io.droyagent.app.nativeui.companion.creation

import android.util.Base64
import io.droyagent.app.nativeui.companion.data.NativeCompanionClient
import io.droyagent.app.nativeui.data.NativeApiClient
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeLocalTransport
import io.droyagent.app.nativeui.media.NativeLocalAttachment
import io.droyagent.app.nativeui.media.NativeMediaPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class NativeCreationClient(private val files: NativeCreationFileStore, assets: android.content.res.AssetManager) : NativeCreationRepository {
    private val transport = NativeLocalTransport()
    private val companions = NativeCompanionClient(assets)
    private val base = "/droy/companions"
    private fun segment(value: String) = NativeApiClient.encodeSegment(value)
    override suspend fun templates() = NativeCreationJson.templates(transport.json("$base/templates"))
    private var modelDefault: io.droyagent.app.nativeui.data.NativeModel? = null
    override fun defaultModel() = modelDefault
    override suspend fun models(): List<io.droyagent.app.nativeui.data.NativeModel> {
        val root = transport.json(base)
        val models = NativeCreationJson.models(root)
        modelDefault = root.optJSONObject("defaultModel")?.let(io.droyagent.app.nativeui.companion.data.NativeCompanionJson::model)?.let { route ->
            models.firstOrNull { it.provider == route.provider && it.id == route.id } ?: route
        }
        return models
    }
    override suspend fun ensureDraft(requestId: String) = NativeCreationJson.draft(transport.json("$base/creation-drafts", "POST", JSONObject().put("requestId", requestId)))
    override suspend fun draft(id: String) = NativeCreationJson.draft(transport.json("$base/creation-drafts/${segment(id)}"), id)
    override suspend fun create(payload: String): NativeCreationReceipt {
        val input = JSONObject(payload)
        return NativeCreationJson.receipt(transport.json(base, "POST", input), input.getString("requestId"))
    }
    override suspend fun cancelDraft(id: String) = NativeCreationJson.cancellation(transport.json("$base/creation-drafts/${segment(id)}/cancel", "POST", JSONObject()))
    override suspend fun upload(draftId: String, item: NativeLocalAttachment) = withContext(Dispatchers.IO) {
        val bytes = files.open(item).use(NativeMediaPolicy::readBounded)
        if (bytes.size.toLong() != item.bytes) throw NativeApiException("native_attachment_changed")
        val body = JSONObject().put("name", item.name).put("mediaType", item.mediaType).put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        NativeCreationJson.upload(transport.json("$base/creation-drafts/${segment(draftId)}/uploads", "POST", body), draftId)
    }
    suspend fun loadImage(url: String): ByteArray = companions.media(url)
}
