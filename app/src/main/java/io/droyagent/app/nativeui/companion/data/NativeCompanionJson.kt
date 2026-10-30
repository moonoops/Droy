package io.droyagent.app.nativeui.companion.data

import io.droyagent.app.nativeui.data.NativeModel
import org.json.JSONArray
import org.json.JSONObject

internal object NativeCompanionJson {
    fun status(value: JSONObject): NativeCompanionStatus = NativeCompanionStatus(
        value.getString("phase"), value.stringOrNull("error"), value.optJSONObject("chatExecution")?.let {
            NativeCompanionExecution(it.getBoolean("quiescent"), it.getBoolean("cancelRequested"))
        },
    )

    fun attachment(value: JSONObject): NativeCompanionAttachment = NativeCompanionAttachment(
        value.getString("id"), value.getString("name"), value.getString("mediaType"), value.getLong("bytes"),
        value.getString("url"), value.intOrNull("width"), value.intOrNull("height"),
    )
    fun attachment(value: NativeCompanionAttachment): JSONObject = JSONObject().put("id", value.id)
        .put("name", value.name).put("mediaType", value.mediaType).put("bytes", value.bytes).put("url", value.url)
        .apply { value.width?.let { put("width", it) }; value.height?.let { put("height", it) } }

    fun voice(value: JSONObject): NativeCompanionVoice = NativeCompanionVoice(
        value.stringOrNull("attachmentId") ?: value.getString("id"), value.stringOrNull("url"), value.longOrNull("durationMs"),
        value.stringOrNull("transcript"), value.stringOrNull("transcriptionState"), value.toString(),
    )
    fun voice(value: NativeCompanionVoice): JSONObject = JSONObject(value.metadataJson).put("attachmentId", value.attachmentId)
        .apply {
            value.url?.let { put("url", it) }; value.durationMs?.let { put("durationMs", it) }
            value.transcript?.let { put("transcript", it) }; value.transcriptionState?.let { put("transcriptionState", it) }
        }

    fun message(value: JSONObject): NativeCompanionMessage = NativeCompanionMessage(
        id = value.getString("id"), role = value.getString("role"), type = value.getString("type"),
        text = value.getString("text"), createdAt = value.getString("createdAt"), status = value.getString("status"),
        clientId = value.stringOrNull("clientId"), attachments = value.optJSONArray("attachments").objects().map(::attachment),
        voice = value.optJSONObject("voice")?.let(::voice), stickerId = value.stringOrNull("stickerId"),
        replyTo = value.stringOrNull("replyTo"), replyPreview = value.optJSONObject("replyPreview")?.let(::preview),
        quoteReply = value.optBoolean("quoteReply"), task = value.optJSONObject("task")?.let {
            NativeCompanionTask(it.stringOrNull("id"), it.stringOrNull("sessionId"), it.stringOrNull("title"), it.getString("state"),
                it.stringOrNull("goal"), it.stringOrNull("summary"), it.stringOrNull("error"), it.toString())
        }, transcript = value.stringOrNull("transcript"), error = value.stringOrNull("error"),
        contextExcluded = value.optBoolean("contextExcluded"),
    )

    fun preview(value: JSONObject) = NativeCompanionReplyPreview(value.getString("id"), value.getString("text"), value.getString("type"))
    private fun preview(value: NativeCompanionReplyPreview) = JSONObject().put("id", value.id).put("text", value.text).put("type", value.type)
    fun user(value: JSONObject) = NativeUserProfile(value.getString("name"), value.getString("avatarUrl"), value.optString("bio", ""))
    fun model(value: JSONObject): NativeModel = NativeModel(value.getString("provider"), value.getString("model"), value.getString("model"),
        reasoningEffort = value.stringOrNull("reasoningEffort"))

    fun profile(value: JSONObject): NativeCompanion {
        val settings = value.getJSONObject("settings")
        return NativeCompanion(
            id = value.getString("id"), name = value.getString("name"), avatarUrl = value.getString("avatarUrl"),
            pinned = value.optBoolean("pinned"), hidden = value.optBoolean("hidden"),
            unreadCount = if (value.has("unread")) value.getInt("unread") else value.optInt("unreadCount"),
            lastMessage = value.optJSONObject("lastMessage")?.let(::message), lastActivityAt = value.stringOrNull("lastActivityAt"),
            status = value.optJSONObject("status")?.let(::status) ?: NativeCompanionStatus(),
            workspaceId = value.stringOrNull("workspaceId"), chatSessionId = value.stringOrNull("chatSessionId"),
            model = value.optJSONObject("model")?.let(::model), personality = value.optString("personality"),
            modelInherited = value.optBoolean("modelInherited", value.isNull("model")),
            personalityOverride = value.optString("personalityOverride"), settings = NativeCompanionSettings(
                settings.getBoolean("proactiveEnabled"), settings.getBoolean("memoryEnabled"), settings.getBoolean("allowProfanity"),
                settings.optJSONObject("quietHours")?.toString()), chatBackgroundJson = value.optJSONObject("chatBackground")?.toString(),
            templateId = value.stringOrNull("templateId"),
            behaviorStyle = value.optString("behaviorStyle"), replyStyle = value.optString("replyStyle"),
            behaviorStyleOverride = value.optString("behaviorStyleOverride"), replyStyleOverride = value.optString("replyStyleOverride"),
            presetPersonality = value.optJSONObject("personaPreset")?.optString("personality") ?: value.optString("personality"),
            presetBehavior = value.optJSONObject("personaPreset")?.optString("behaviorStyle") ?: value.optString("behaviorStyle"),
            presetReply = value.optJSONObject("personaPreset")?.optString("replyStyle") ?: value.optString("replyStyle"),
            customBehavior = value.optJSONObject("customPersonaDefaults")?.optString("behaviorStyle") ?: "",
            customReply = value.optJSONObject("customPersonaDefaults")?.optString("replyStyle") ?: "",

        )
    }

    fun draft(value: NativeCompanionDraft): JSONObject = JSONObject().put("text", value.text)
        .put("attachments", JSONArray(value.attachments.map(::attachment)))
        .put("localAttachments", JSONArray(value.localAttachments.map { it.json() })).apply {
            value.replyTo?.let { put("replyTo", preview(it)) }; value.voice?.let { put("voice", voice(it)) }
            value.stickerId?.let { put("stickerId", it) }
        }
    fun draft(value: JSONObject): NativeCompanionDraft = NativeCompanionDraft(value.optString("text"),
        value.optJSONArray("attachments").objects().map(::attachment), value.optJSONObject("replyTo")?.let(::preview),
        value.optJSONObject("voice")?.let(::voice), value.stringOrNull("stickerId"),
        value.optJSONArray("localAttachments")?.let(io.droyagent.app.nativeui.media.NativeLocalAttachment::parseList) ?: emptyList()).also {
            require(it.attachments.size + it.localAttachments.size <= 10)
        }
    fun payload(clientId: String, draft: NativeCompanionDraft): JSONObject = JSONObject().put("clientId", clientId)
        .put("text", draft.text).put("attachments", JSONArray(draft.attachments.map { JSONObject().put("id", it.id) })).apply {
            draft.replyTo?.let { put("replyTo", it.id) }; draft.voice?.let { put("voice", voice(it)) }
            draft.stickerId?.let { put("stickerId", it) }
        }

    fun sameSubmissionContent(first: NativeCompanionDraft, second: NativeCompanionDraft): Boolean =
        canonical(payload("draft", first)) == canonical(payload("draft", second)) && first.localAttachments == second.localAttachments

    fun matchesPayload(wirePayload: String, clientId: String, draft: NativeCompanionDraft): Boolean = canonical(JSONObject(wirePayload)) == canonical(payload(clientId, draft))

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }

    fun patch(value: NativeCompanionProfilePatch): JSONObject = JSONObject().apply {
        value.name?.let { put("name", it) }; value.avatarUrl?.let { put("avatarUrl", it) }
        value.personalityOverride?.let { put("personalityOverride", it) }
        value.behaviorStyleOverride?.let { put("behaviorStyleOverride", it) }
        value.replyStyleOverride?.let { put("replyStyleOverride", it) }
        value.model?.let { model -> put("model", JSONObject().put("provider", model.provider).put("model", model.id)
            .apply { model.reasoningEffort?.let { put("reasoningEffort", it) } }) }
        if (value.clearModel) put("model", JSONObject.NULL)
        value.settings?.let { settings -> put("settings", JSONObject().put("proactiveEnabled", settings.proactiveEnabled)
            .put("memoryEnabled", settings.memoryEnabled).put("allowProfanity", settings.allowProfanity)
            .apply { settings.quietHoursJson?.let { put("quietHours", JSONObject(it)) } }) }
        value.chatBackgroundJson?.let { put("chatBackground", JSONObject(it)) }
    }
}

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map(::getJSONObject)
internal fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
private fun JSONObject.longOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
