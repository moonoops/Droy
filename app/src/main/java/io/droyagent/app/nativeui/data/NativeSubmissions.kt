package io.droyagent.app.nativeui.data

import org.json.JSONObject
import io.droyagent.app.nativeui.media.NativeWorkbenchMediaTarget

/** Acceptance is a delivery receipt; visibility in a bounded history window is a different fact. */
class NativeSubmissions {
    private data class Accepted(val sessionId: String, val requestId: String, val text: String, val attachments: List<NativeMessageAttachment>)
    private val accepted = linkedMapOf<String, Accepted>()

    fun accept(record: JSONObject) {
        val value = Accepted(record.getString("sessionId"), record.getString("requestId"), record.getString("text"), attachments(record))
        accepted[value.requestId] = value
    }

    fun rebaseline(sessionId: String) {
        // A Host snapshot includes both its current window and its complete inbox projection.
        accepted.entries.removeAll { it.value.sessionId == sessionId }
    }

    fun messages(sessionId: String, knownRequest: (String) -> Boolean): List<NativeMessage> {
        accepted.entries.removeAll { it.value.sessionId == sessionId && knownRequest(it.value.requestId) }
        return accepted.values.filter { it.sessionId == sessionId }.map {
            NativeMessage("request:${it.requestId}", "user", it.text, kind = "pending", attachments = it.attachments, requestId = it.requestId)
        }
    }

    companion object {
        fun matchesMediaTarget(target: NativeWorkbenchMediaTarget, draftId: String, sessionId: String?, workspaceId: String?,
                               materialized: (String) -> String?): Boolean {
            if (workspaceId != target.workspaceId) return false
            val expected = target.sessionId ?: materialized(target.draftId)
            val current = sessionId ?: materialized(draftId)
            return if (expected != null) current == expected
                else draftId == target.draftId && (current == null || current == target.draftId)
        }

        fun captureSessionPreparation(record: JSONObject, capture: (String) -> JSONObject?) {
            if (record.has("sessionPreparation")) return
            val token = if (record.optBoolean("needsCreate")) capture(record.getString("draftId"))?.let { JSONObject(it.toString()) } else null
            record.put("sessionPreparation", token ?: JSONObject.NULL).put("sessionPrepared", token == null)
                .put("sessionPreparationAttempted", false)
        }

        suspend fun prepareSession(record: JSONObject, persist: suspend (JSONObject) -> Unit,
                                   prepare: suspend (String, JSONObject?) -> Unit) {
            // Pre-hook receipts belong to older submissions; a retry cannot capture a later draft choice.
            if (!record.has("sessionPreparation") || record.optBoolean("sessionPrepared")) return
            val token = if (record.isNull("sessionPreparation")) null else JSONObject(record.getJSONObject("sessionPreparation").toString())
            record.put("sessionPreparationAttempted", true)
            persist(record)
            prepare(record.getString("sessionId"), token)
            record.put("sessionPrepared", true)
            persist(record)
        }

        fun confirmsCreatedSession(record: JSONObject, list: JSONObject, spaces: JSONObject): Boolean {
            val id = record.getString("sessionId")
            fun org.json.JSONArray.strings() = (0 until length()).map(::getString)
            val rows = list.getJSONArray("items")
            val row = (0 until rows.length()).map(rows::getJSONObject).firstOrNull { it.getString("sessionId") == id }
            if (row == null || row.optString("origin") == "subagent"
                || id in list.getJSONArray("managedSessionIds").strings() || id in spaces.getJSONArray("archivedSessionIds").strings()) return false
            val workspace = record.optString("workspaceId").takeUnless { it == "null" || it.isEmpty() }
            if (workspace == null) return true
            if (workspace in spaces.getJSONArray("managedWorkspaceIds").strings()) return false
            val items = spaces.getJSONArray("items")
            return (0 until items.length()).any { index -> items.getJSONObject(index).let {
                it.getString("workspaceId") == workspace && id in it.getJSONArray("sessionIds").strings()
            } }
        }
        fun blocksNewSubmission(record: JSONObject?): Boolean = record != null && record.optString("status") !in setOf("accepted", "abandoned")
        fun voiceDuration(record: JSONObject): Long? = record.optLong("voiceDurationMs", 0L).takeIf { it in 1..60_000L }
        fun withVoiceDuration(message: NativeMessage, durationMs: Long?): NativeMessage =
            if (message.role == "user" && message.attachments.size == 1 && durationMs != null && durationMs in 1..60_000L)
                message.copy(attachments = message.attachments.map { it.copy(kind = "audio", durationMs = durationMs) }) else message
        fun attachments(record: JSONObject): List<NativeMessageAttachment> = record.optJSONArray("attachments")?.let { rows ->
            io.droyagent.app.nativeui.media.NativeLocalAttachment.parseList(rows).map { local ->
                NativeMessageAttachment(if (local.image) "image" else "file", null, local.name, local.mediaType, local.bytes, local, voiceDuration(record))
            }
        } ?: emptyList()
    }
}
