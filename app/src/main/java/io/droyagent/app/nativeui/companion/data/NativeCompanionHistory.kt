package io.droyagent.app.nativeui.companion.data

import io.droyagent.app.nativeui.data.NativeApiException
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray
import io.droyagent.app.nativeui.media.NativeLocalAttachment
import io.droyagent.app.nativeui.media.NativeMediaPolicy

/** The companion feed is a paged snapshot, not a DSH event journal. */
internal class NativeCompanionHistory {
    var messages: List<NativeCompanionMessage> = emptyList(); private set
    var hasMore: Boolean = false; private set
    var nextBefore: String? = null; private set
    var loaded: Boolean = false; private set

    fun replace(pages: List<NativeCompanionPage>) {
        require(pages.isNotEmpty())
        messages = pages.asReversed().flatMap { it.messages }.distinctBy { it.id }
        hasMore = pages.last().hasMore; nextBefore = pages.last().nextBefore; loaded = true
    }
    fun prepend(page: NativeCompanionPage) {
        val merged = linkedMapOf<String, NativeCompanionMessage>()
        (page.messages + messages).forEach { merged[it.id] = it }
        messages = merged.values.toList(); hasMore = page.hasMore; nextBefore = page.nextBefore; loaded = true
    }
    fun upsert(message: NativeCompanionMessage) {
        val position = messages.indexOfFirst { it.id == message.id || it.clientId != null && it.clientId == message.clientId }
        messages = messages.toMutableList().apply { if (position == -1) add(message) else set(position, message) }
    }
    fun remove(messageId: String) { messages = messages.filterNot { it.id == messageId } }
    fun contains(clientId: String): Boolean = messages.any { it.id == clientId || it.clientId == clientId }

    fun needsEarlier(page: NativeCompanionPage, retainedBefore: String?): Boolean {
        if (!page.hasMore) return false
        val next = cursor(page.nextBefore ?: throw NativeApiException("malformed_response"))
        return retainedBefore == null || next > cursor(retainedBefore)
    }
    private fun cursor(value: String): Long {
        if (!value.matches(Regex("seq:[0-9]+"))) throw NativeApiException("malformed_response")
        return value.substring(4).toLongOrNull() ?: throw NativeApiException("malformed_response")
    }
}

/** Only unresolved delivery is persisted. A response message is a durable receipt. */
internal data class NativeCompanionSubmission(
    val companionId: String, val clientId: String, val draft: NativeCompanionDraft,
    val createdAt: String, val payload: String, val uncertain: Boolean = false,
    val uploadReceipts: Map<String, NativeCompanionAttachment> = emptyMap(), val payloadReady: Boolean = draft.localAttachments.isEmpty(),
    val draftConsumed: Boolean = false,
) {
    fun json(): JSONObject = JSONObject().put("companionId", companionId).put("clientId", clientId)
        .put("draft", NativeCompanionJson.draft(draft)).put("createdAt", createdAt).put("payload", payload).put("uncertain", uncertain)
        .put("draftConsumed", draftConsumed).put("payloadReady", payloadReady).put("uploadReceipts", JSONArray(uploadReceipts.map { (id, item) ->
            JSONObject().put("localId", id).put("attachment", NativeCompanionJson.attachment(item))
        }))
    fun withUpload(local: NativeLocalAttachment, uploaded: NativeCompanionAttachment): NativeCompanionSubmission {
        require(!payloadReady && local in draft.localAttachments && validUpload(local, uploaded))
        if (!NativeMediaPolicy.ownedUrl(companionId, uploaded.id, uploaded.url)) throw NativeApiException("COMPANION_ATTACHMENT_SCOPE")
        return copy(uploadReceipts = uploadReceipts + (local.id to uploaded))
    }
    private fun materializedDraft(): NativeCompanionDraft {
        require(draft.attachments.size + draft.localAttachments.size <= 10)
        return draft.copy(attachments = draft.attachments + draft.localAttachments.map { uploadReceipts.getValue(it.id) }, localAttachments = emptyList())
    }
    fun freezePayload(): NativeCompanionSubmission = if (payloadReady) this else
        copy(payload = NativeCompanionJson.payload(clientId, materializedDraft()).toString(), payloadReady = true)
    fun optimistic() = NativeCompanionMessage(clientId, "user", if (draft.voice != null) "voice" else if (draft.stickerId != null) "sticker"
        else if (draft.attachments.firstOrNull()?.mediaType?.startsWith("image/") == true || draft.localAttachments.firstOrNull()?.image == true) "image"
        else if (draft.attachments.isNotEmpty() || draft.localAttachments.isNotEmpty()) "file" else "text",
        draft.text, createdAt, if (uncertain) "failed" else "pending", clientId, draft.attachments + draft.localAttachments.mapNotNull { uploadReceipts[it.id] }, draft.voice, draft.stickerId,
        draft.replyTo?.id, draft.replyTo, draft.replyTo != null)
    companion object {
        // Android normalizes image uploads to PNG; the saved bytes legitimately differ from the camera source.
        private fun validUpload(local: NativeLocalAttachment, uploaded: NativeCompanionAttachment): Boolean =
            if (local.image) uploaded.mediaType == "image/png" && uploaded.bytes > 0 && uploaded.bytes <= 20 * 1024 * 1024
            else uploaded.bytes == local.bytes && uploaded.mediaType == local.mediaType
        fun prepare(id: String, draft: NativeCompanionDraft, now: String, clientId: String = UUID.randomUUID().toString()): NativeCompanionSubmission {
            require(draft.canSend)
            require(UUID.fromString(clientId).toString() == clientId)
            val snapshot = NativeCompanionJson.draft(NativeCompanionJson.draft(draft))
            return NativeCompanionSubmission(id, clientId, snapshot, now, NativeCompanionJson.payload(clientId, snapshot).toString())
        }
        fun restore(value: JSONObject): NativeCompanionSubmission {
            val draft = NativeCompanionJson.draft(value.getJSONObject("draft"))
            val receipts = linkedMapOf<String, NativeCompanionAttachment>()
            value.optJSONArray("uploadReceipts")?.let { rows -> for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index); val id = row.getString("localId")
                require(id !in receipts)
                receipts[id] = NativeCompanionJson.attachment(row.getJSONObject("attachment"))
            } }
            val result = NativeCompanionSubmission(value.getString("companionId"), value.getString("clientId"), draft,
                value.getString("createdAt"), value.getString("payload"), value.optBoolean("uncertain"), receipts,
                value.optBoolean("payloadReady", draft.localAttachments.isEmpty()), value.optBoolean("draftConsumed"))
            require(receipts.all { (id, item) -> draft.localAttachments.any { it.id == id && validUpload(it, item) }
                && NativeMediaPolicy.ownedUrl(result.companionId, item.id, item.url) })
            if (result.payloadReady && draft.localAttachments.isNotEmpty())
                require(NativeCompanionJson.matchesPayload(result.payload, result.clientId, result.materializedDraft()))
            return result
        }
    }
}

internal suspend fun materializeCompanionSubmission(initial: NativeCompanionSubmission,
    persist: suspend (NativeCompanionSubmission) -> Unit, upload: suspend (NativeLocalAttachment) -> NativeCompanionAttachment): NativeCompanionSubmission {
    var current = initial
    if (current.payloadReady) return current
    for (item in current.draft.localAttachments) if (item.id !in current.uploadReceipts) {
        current = current.withUpload(item, upload(item)); persist(current)
    }
    current = current.freezePayload(); persist(current)
    return current
}

internal fun companionStopPending(acknowledged: Boolean?, execution: NativeCompanionExecution?): Boolean {
    val confirmed = execution?.cancelRequested == true && execution.quiescent
    return acknowledged != null && !(acknowledged && confirmed) || execution?.cancelRequested == true && !execution.quiescent
}

internal fun canMarkCompanionSubmissionUncertain(
    record: NativeCompanionSubmission, current: NativeCompanionSubmission?, observed: Boolean,
): Boolean = !observed && current?.clientId == record.clientId
