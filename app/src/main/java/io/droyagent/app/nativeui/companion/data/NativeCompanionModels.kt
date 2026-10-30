package io.droyagent.app.nativeui.companion.data

import io.droyagent.app.nativeui.data.NativeModel
import io.droyagent.app.nativeui.data.NativeRuntimeState

data class NativeUserProfile(val name: String = "", val avatarUrl: String = "/droy/companions/assets/user-default.png", val bio: String = "")

data class NativeCompanionExecution(val quiescent: Boolean, val cancelRequested: Boolean)
data class NativeCompanionStatus(
    val phase: String = "idle", val error: String? = null,
    val chatExecution: NativeCompanionExecution? = null,
)

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
)

data class NativeCompanionSticker(val id: String, val url: String, val label: String = "")

data class NativeCompanionDraft(
    val text: String = "", val attachments: List<NativeCompanionAttachment> = emptyList(),
    val replyTo: NativeCompanionReplyPreview? = null, val voice: NativeCompanionVoice? = null,
    val stickerId: String? = null,
    val localAttachments: List<io.droyagent.app.nativeui.media.NativeLocalAttachment> = emptyList(),
) {
    val canSend: Boolean get() = text.isNotBlank() || attachments.isNotEmpty() || localAttachments.isNotEmpty() || voice != null || stickerId != null
}

data class NativeCompanionProfilePatch(
    val name: String? = null, val avatarUrl: String? = null,
    val personalityOverride: String? = null, val model: NativeModel? = null,
    val settings: NativeCompanionSettings? = null, val chatBackgroundJson: String? = null,
    val clearModel: Boolean = false,
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
    val readingAnchor: NativeCompanionReadingAnchor? = null,
    val draftsByCompanion: Map<String, NativeCompanionDraft> = emptyMap(),
) {
    val selectedCompanion: NativeCompanion? get() = companions.firstOrNull { it.id == selectedCompanionId }
    val conversations: List<NativeCompanion> get() = companions.filterNot { it.hidden }
        .sortedWith(compareByDescending<NativeCompanion> { it.pinned }.thenByDescending { it.lastActivityAt ?: it.lastMessage?.createdAt ?: "" })
}
