package io.droyagent.app.nativeui.companion.creation

import io.droyagent.app.nativeui.companion.data.NativeCompanionAttachment
import io.droyagent.app.nativeui.companion.data.NativeCompanionJson
import io.droyagent.app.nativeui.companion.data.NativeCompanionProfilePatch
import io.droyagent.app.nativeui.companion.data.NativeCompanionSettings
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeModel
import io.droyagent.app.nativeui.media.NativeLocalAttachment
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

internal object NativeCreationJson {
    private fun invalid(): Nothing = throw NativeApiException("malformed_response")
    private inline fun <T> decode(block: () -> T): T = try { block() } catch (error: JSONException) { throw NativeApiException("malformed_response", error) }
    private fun JSONObject.string(key: String): String = get(key) as? String ?: invalid()
    private fun JSONObject.bool(key: String): Boolean = get(key) as? Boolean ?: invalid()
    private fun JSONObject.optionalString(key: String): String? = if (!has(key) || isNull(key)) null else string(key)
    private fun identifier(value: String): String = value.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,160}")) } ?: invalid()
    private fun uuid(value: String): String = try { value.takeIf { UUID.fromString(it).toString() == it } ?: invalid() } catch (_: IllegalArgumentException) { invalid() }
    private fun sameValue(left: Any?, right: Any?): Boolean = when {
        left == null || left === JSONObject.NULL -> right == null || right === JSONObject.NULL
        left is JSONObject && right is JSONObject -> {
            val keys = left.keys().asSequence().toSet()
            keys == right.keys().asSequence().toSet() && keys.all { sameValue(left.get(it), right.get(it)) }
        }
        left is JSONArray && right is JSONArray -> left.length() == right.length() && (0 until left.length()).all { sameValue(left.get(it), right.get(it)) }
        left is Number && right is Number -> left.toString().toBigDecimalOrNull()?.let { number -> right.toString().toBigDecimalOrNull()?.let { number.compareTo(it) == 0 } } == true
        else -> left == right
    }
    private fun settings(value: JSONObject): NativeCompanionSettings {
        val quiet = value.get("quietHours")
        if (quiet !== JSONObject.NULL) {
            val hours = quiet as? JSONObject ?: invalid()
            hours.bool("enabled")
            for (key in listOf("start", "end")) if (!hours.string(key).matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d"))) invalid()
        }
        return NativeCompanionSettings(value.bool("proactiveEnabled"), value.bool("memoryEnabled"), value.bool("allowProfanity"), if (quiet === JSONObject.NULL) null else quiet.toString())
    }
    private fun background(value: JSONObject): String {
        if (value.string("type") !in setOf("default", "preset", "upload")) invalid()
        return value.toString()
    }
    private fun settingsValue(value: NativeCompanionSettings): JSONObject = NativeCompanionJson.patch(NativeCompanionProfilePatch(settings = value)).getJSONObject("settings")
        .apply { if (!has("quietHours")) put("quietHours", JSONObject.NULL) }
    private fun template(value: JSONObject): NativeCreationTemplate = NativeCreationTemplate(identifier(value.string("templateId")), value.string("name"), value.string("suggestedName"),
        value.string("avatarUrl"), value.string("personality"), value.string("behaviorStyle"), value.string("replyStyle"), settings(value.getJSONObject("settings")), background(value.getJSONObject("chatBackground")))
    fun templates(value: JSONObject): List<NativeCreationTemplate> = decode {
        val rows = value.getJSONArray("templates")
        (0 until rows.length()).map { template(rows.getJSONObject(it)) }.also { if (it.map { item -> item.id }.distinct().size != it.size) invalid() }
    }
    fun models(value: JSONObject): List<NativeModel> = decode {
        val rows = value.getJSONArray("models")
        (0 until rows.length()).map { rows.getJSONObject(it).let { row -> NativeModel(row.string("provider"), row.string("id"), row.string("name"), row.optString("providerName", row.string("provider"))) } }
            .distinctBy { it.provider to it.id }
    }
    fun draft(value: JSONObject, expected: String? = null): NativeCreationDraftStatus = decode {
        val id = identifier(value.string("draftId"))
        if (expected != null && id != expected) invalid()
        val state = value.string("state")
        if (state !in setOf("open", "committed", "cancelled")) invalid()
        val owner = value.optionalString("companionId")?.let(::identifier)
        if (state == "committed" && owner == null || state != "committed" && owner != null) invalid()
        val uploads = if (value.has("uploads")) value.getJSONArray("uploads") else JSONArray()
        NativeCreationDraftStatus(id, state, owner, (0 until uploads.length()).map { upload(uploads.getJSONObject(it), id) })
    }
    fun upload(value: JSONObject, draftId: String): NativeCompanionAttachment = decode {
        val item = NativeCompanionJson.attachment(value)
        if (!item.mediaType.startsWith("image/") || item.bytes !in 1..20L * 1024 * 1024 || item.url != "/droy/companions/creation-drafts/$draftId/attachments/${identifier(item.id)}") invalid()
        item
    }
    fun receipt(value: JSONObject, requestId: String): NativeCreationReceipt = decode {
        if (value.string("requestId") != requestId) invalid()
        val profile = value.getJSONObject("profile")
        NativeCreationReceipt(requestId, identifier(profile.string("id")), identifier(profile.string("templateId")), value.bool("replayed"))
    }
    fun cancellation(value: JSONObject): NativeCreationCancel = decode {
        val state = value.string("state")
        val cancelled = value.bool("cancelled")
        val id = value.optionalString("companionId")?.let(::identifier)
        if (state == "cancelled" && (!cancelled || id != null) || state == "committed" && (cancelled || id == null) || state !in setOf("committed", "cancelled")) invalid()
        NativeCreationCancel(cancelled, state, id)
    }
    fun payload(value: NativeCreationJournal): String {
        val draftId = value.draftId ?: throw NativeApiException("COMPANION_DRAFT_NOT_FOUND")
        val editor = value.editor
        if (!editor.valid) throw NativeApiException("COMPANION_INVALID_INPUT")
        return JSONObject().put("requestId", value.requestId).put("draftId", draftId).put("templateId", editor.template.id).put("name", editor.name)
            .put("personalityOverride", editor.personalityOverride).put("model", editor.model?.let(::selectionValue) ?: JSONObject.NULL)
            .put("settings", settingsValue(editor.settings)).put("chatBackground", JSONObject(editor.backgroundJson))
            .apply { editor.avatarUpload?.let { put("avatarUploadId", it.id) } }.toString()
    }
    private fun modelValue(value: NativeModel) = JSONObject().put("provider", value.provider).put("model", value.id)
        .put("name", value.name).put("providerName", value.providerName).apply { value.reasoningEffort?.let { put("reasoningEffort", it) } }
    private fun selectionValue(value: NativeModel) = JSONObject().put("provider", value.provider).put("model", value.id)
        .apply { value.reasoningEffort?.let { put("reasoningEffort", it) } }
    private fun templateValue(value: NativeCreationTemplate) = JSONObject().put("templateId", value.id).put("name", value.name).put("suggestedName", value.suggestedName)
        .put("avatarUrl", value.avatarUrl).put("personality", value.personality).put("behaviorStyle", value.behaviorStyle).put("replyStyle", value.replyStyle)
        .put("settings", settingsValue(value.settings)).put("chatBackground", JSONObject(value.backgroundJson))
    fun journal(value: NativeCreationJournal): String = JSONObject().put("version", 1).put("requestId", value.requestId)
        .put("draftId", value.draftId ?: JSONObject.NULL).put("frozenPayload", value.frozenPayload ?: JSONObject.NULL).put("pendingCancel", value.pendingCancel)
        .put("editor", JSONObject().put("template", templateValue(value.editor.template)).put("name", value.editor.name)
            .put("personalityOverride", value.editor.personalityOverride).put("model", value.editor.model?.let(::modelValue) ?: JSONObject.NULL)
            .put("settings", settingsValue(value.editor.settings)).put("chatBackground", JSONObject(value.editor.backgroundJson))
            .put("avatarUpload", value.editor.avatarUpload?.let(NativeCompanionJson::attachment) ?: JSONObject.NULL))
        .put("image", value.image?.let { image -> JSONObject().put("id", image.id).put("ownerRequestId", image.ownerRequestId).put("draftId", image.draftId)
            .put("kind", image.kind.name).put("phase", image.phase.name).put("uri", image.uri ?: JSONObject.NULL).put("local", image.local?.json() ?: JSONObject.NULL)
            .put("uploaded", image.uploaded?.let(NativeCompanionJson::attachment) ?: JSONObject.NULL) } ?: JSONObject.NULL).toString()

    fun journal(text: String): NativeCreationJournal = decode {
        val value = JSONObject(text)
        if (value.get("version") != 1) invalid()
        val requestId = identifier(value.string("requestId"))
        val draftId = value.optionalString("draftId")?.let(::identifier)
        val row = value.getJSONObject("editor")
        val template = template(row.getJSONObject("template"))
        val model = if (row.isNull("model")) null else row.getJSONObject("model").let { model -> NativeModel(model.string("provider"), model.string("model"), model.string("name"), model.string("providerName"), model.optionalString("reasoningEffort")) }
        val avatar = if (row.isNull("avatarUpload")) null else upload(row.getJSONObject("avatarUpload"), draftId ?: invalid())
        val editor = NativeCreationEditor(template, row.string("name"), row.string("personalityOverride"), model, settings(row.getJSONObject("settings")), background(row.getJSONObject("chatBackground")), avatar)
        val image = if (value.isNull("image")) null else value.getJSONObject("image").let { input ->
            val owner = identifier(input.string("ownerRequestId")); val imageDraft = identifier(input.string("draftId"))
            if (owner != requestId || imageDraft != draftId) invalid()
            val kind = NativeCreationImageKind.entries.find { it.name == input.string("kind") } ?: invalid()
            val phase = NativeCreationImagePhase.entries.find { it.name == input.string("phase") } ?: invalid()
            val local = if (input.isNull("local")) null else input.getJSONObject("local").let { item ->
                val bytes = item.getLong("bytes")
                if (bytes !in 1..20L * 1024 * 1024) invalid()
                NativeLocalAttachment(uuid(item.string("importId")), uuid(item.string("id")), item.string("name"), item.string("mediaType"), bytes)
            }
            val imageId = uuid(input.string("id"))
            val uploaded = if (input.isNull("uploaded")) null else upload(input.getJSONObject("uploaded"), imageDraft)
            if (local != null && local.importId != imageId || phase in setOf(NativeCreationImagePhase.UPLOADING, NativeCreationImagePhase.UPLOADED) && local == null
                || phase == NativeCreationImagePhase.UPLOADED && uploaded == null) invalid()
            NativeCreationImageOperation(imageId, owner, imageDraft, kind, phase, input.optionalString("uri"), local, uploaded)
        }
        val journal = NativeCreationJournal(requestId, editor, draftId, value.optionalString("frozenPayload"), value.bool("pendingCancel"), image)
        journal.frozenPayload?.let { frozen ->
            if (draftId == null || !sameValue(JSONObject(frozen), JSONObject(payload(journal)))) invalid()
        }
        journal
    }
}
