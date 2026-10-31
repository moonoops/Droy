package io.droyagent.app.nativeui.companion.data

import io.droyagent.app.nativeui.data.NativeModel
import io.droyagent.app.nativeui.data.NativeRuntimeState

data class NativeUserProfile(val name: String = "", val avatarUrl: String = "/droy/companions/assets/user-default.png", val bio: String = "")

data class NativeCompanionExecution(val quiescent: Boolean, val cancelRequested: Boolean)
data class NativeCompanionStatus(
    val phase: String = "idle", val error: String? = null,
    val chatExecution: NativeCompanionExecution? = null,
    val execution: NativeCompanionExecution? = null,
) {
    val processing: Boolean get() = (execution ?: chatExecution)?.let { !it.quiescent }
        ?: (phase in setOf("thinking", "typing", "waiting"))
}

internal fun visibleCompanionMessages(messages: List<NativeCompanionMessage>): List<NativeCompanionMessage> {
    val internalIds = messages.filter { it.type == "task" }.map { it.id }.toSet()
    return messages.filterNot { it.type == "task" }.map { message ->
        if (message.replyPreview?.type == "task" || message.replyTo in internalIds)
            message.copy(replyTo = null, replyPreview = null, quoteReply = false) else message
    }
}

data class NativeCompanionAttachment(
    val id: String, val name: String, val mediaType: String, val bytes: Long,
    val url: String, val width: Int? = null, val height: Int? = null,
)

data class NativeCompanionVoice(
    val attachmentId: String, val url: String? = null, val durationMs: Long? = null,
    val transcript: String? = null, val transcriptionState: String? = null,
    val metadataJson: String = "{}",
)

data class NativeCompanionTask(
    val id: String?, val sessionId: String?, val title: String?, val state: String,
    val goal: String? = null, val summary: String? = null, val error: String? = null,
    val metadataJson: String = "{}",
)

data class NativeCompanionReplyPreview(val id: String, val text: String, val type: String)

data class NativeCompanionMessage(
    val id: String, val role: String, val type: String, val text: String,
    val createdAt: String, val status: String, val clientId: String? = null,
    val attachments: List<NativeCompanionAttachment> = emptyList(),
    val voice: NativeCompanionVoice? = null, val stickerId: String? = null,
    val replyTo: String? = null, val replyPreview: NativeCompanionReplyPreview? = null,
    val quoteReply: Boolean = false, val task: NativeCompanionTask? = null,
    val transcript: String? = null, val error: String? = null,
    val contextExcluded: Boolean = false,
)

data class NativeCompanionSettings(
    val proactiveEnabled: Boolean = false, val memoryEnabled: Boolean = true,
    val allowProfanity: Boolean = true, val quietHoursJson: String? = null,
    val stickerPackIds: List<String>? = null,
)

data class NativeCompanion(
    val id: String, val name: String, val avatarUrl: String,
    val pinned: Boolean = false, val hidden: Boolean = false, val unreadCount: Int = 0,
    val lastMessage: NativeCompanionMessage? = null, val lastActivityAt: String? = null,
    val status: NativeCompanionStatus = NativeCompanionStatus(),
    val workspaceId: String? = null, val chatSessionId: String? = null,
    val model: NativeModel? = null, val personality: String = "", val personalityOverride: String = "",
    val modelInherited: Boolean = model == null,
    val settings: NativeCompanionSettings = NativeCompanionSettings(),
    val chatBackgroundJson: String? = null,
    val templateId: String? = null,
    val behaviorStyle: String = "", val replyStyle: String = "",
    val behaviorStyleOverride: String = "", val replyStyleOverride: String = "",
    val presetPersonality: String = "", val presetBehavior: String = "", val presetReply: String = "",
    val customBehavior: String = "", val customReply: String = "",

)

data class NativeCompanionSticker(val id: String, val url: String, val label: String = "", val packId: String = "droy-default")
data class NativeCompanionStickerPack(val id: String, val name: String, val count: Int, val builtin: Boolean = false)

internal fun enabledCompanionStickers(settings: NativeCompanionSettings, stickers: List<NativeCompanionSticker>): List<NativeCompanionSticker> {
    val enabled = settings.stickerPackIds?.toSet() ?: setOf("droy-default")
    return stickers.filter { it.packId in enabled }
}

data class NativeCompanionDraft(
    val text: String = "", val attachments: List<NativeCompanionAttachment> = emptyList(),
    val replyTo: NativeCompanionReplyPreview? = null, val voice: NativeCompanionVoice? = null,
    val stickerId: String? = null,
    val localAttachments: List<io.droyagent.app.nativeui.media.NativeLocalAttachment> = emptyList(),
) {
    val canSend: Boolean get() = text.isNotBlank() || attachments.isNotEmpty() || localAttachments.isNotEmpty() || voice != null || stickerId != null
}

internal fun editableCompanionDraft(draft: NativeCompanionDraft): NativeCompanionDraft =
    if (draft.replyTo?.type == "task") draft.copy(replyTo = null) else draft

data class NativeCompanionProfilePatch(
    val name: String? = null, val avatarUrl: String? = null,
    val personalityOverride: String? = null, val model: NativeModel? = null,
    val settings: NativeCompanionSettings? = null, val chatBackgroundJson: String? = null,
    val clearModel: Boolean = false,
    val behaviorStyleOverride: String? = null, val replyStyleOverride: String? = null,
)

data class NativeCompanionReadingAnchor(val messageId: String, val offset: Int)

data class NativeCompanionState(
    val runtime: NativeRuntimeState = NativeRuntimeState(),
    val companions: List<NativeCompanion> = emptyList(), val userProfile: NativeUserProfile = NativeUserProfile(),
    val selectedCompanionId: String? = null, val messages: List<NativeCompanionMessage> = emptyList(),
    val draft: NativeCompanionDraft = NativeCompanionDraft(), val status: NativeCompanionStatus = NativeCompanionStatus(),
    val loading: Boolean = false, val loadingOlder: Boolean = false, val sending: Boolean = false,
    val hasMore: Boolean = false, val error: String? = null,
    val stopRequested: Boolean = false, val stopAcknowledged: Boolean = false,
    val profileSaving: Boolean = false, val models: List<NativeModel> = emptyList(),
    val defaultModel: NativeModel? = null,
    val stickers: List<NativeCompanionSticker> = emptyList(),
    val stickerPacks: List<NativeCompanionStickerPack> = emptyList(),
    val stickerPackImporting: Boolean = false,
    val readingAnchor: NativeCompanionReadingAnchor? = null,
    val draftsByCompanion: Map<String, NativeCompanionDraft> = emptyMap(),
    val backgroundChoices: Map<String, String> = emptyMap(),
) {
    val selectedCompanion: NativeCompanion? get() = companions.firstOrNull { it.id == selectedCompanionId }
    val selectedBackgroundJson: String? get() = selectedCompanion.let { companion ->
        if (companion != null) companion.chatBackgroundJson else backgroundChoices[selectedCompanionId]
    }
    val conversations: List<NativeCompanion> get() = companions.filterNot { it.hidden }
        .sortedWith(compareByDescending<NativeCompanion> { it.pinned }.thenByDescending { it.lastActivityAt ?: it.lastMessage?.createdAt ?: "" })
}
