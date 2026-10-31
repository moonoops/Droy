package io.droyagent.app.nativeui.data

import io.droyagent.app.nativeui.media.NativeLocalAttachment

data class NativeRuntimeState(
    val ready: Boolean = false,
    val active: Boolean = false,
    val canStart: Boolean = true,
    val status: String = "",
    val locale: String = "en",
)

data class NativeSession(
    val id: String,
    val title: String,
    val running: Boolean = false,
    val workspaceId: String? = null,
    val readOnly: Boolean = false,
    val archived: Boolean = false,
)

data class NativeWorkspace(
    val id: String,
    val title: String,
    val sessionIds: List<String> = emptyList(),
    val readOnly: Boolean = false,
)

data class NativeReadingPosition(val key: String, val offset: Int, val followingTail: Boolean)

data class NativeMessageAttachment(
    val kind: String, val attachmentId: String?, val name: String?, val mediaType: String? = null,
    val bytes: Long? = null, val local: NativeLocalAttachment? = null, val durationMs: Long? = null,
)

data class NativeMessage(
    val id: String,
    val role: String,
    val text: String,
    val seq: Long? = null,
    val kind: String = "text",
    val streaming: Boolean = false,
    val failed: Boolean = false,
    val toolName: String? = null,
    val sourceKind: String? = null,
    val sourceForm: String? = null,
    val summary: String? = null,
    val attachments: List<NativeMessageAttachment> = emptyList(),
    val requestId: String? = null,
)

data class NativeUsage(
    val totalTokens: Long? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheHitRate: Double? = null,
    val tokensPerSecond: Double? = null,
    val firstTokenMs: Double? = null,
    val partial: Boolean = false,
)

data class NativeWorkbenchState(
    val runtime: NativeRuntimeState = NativeRuntimeState(),
    val sessions: List<NativeSession> = emptyList(),
    val workspaces: List<NativeWorkspace> = emptyList(),
    val defaultWorkspaceId: String? = null,
    val selectedSessionId: String? = null,
    val sessionBaselineId: String? = null,
    val selectedWorkspaceId: String? = null,
    val workspaceResolved: Boolean = false,
    val workspaceNotice: String? = null,
    val messages: List<NativeMessage> = emptyList(),
    val draft: String = "",
    val loading: Boolean = false,
    val sending: Boolean = false,
    val running: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val usage: NativeUsage? = null,
    val decisions: List<NativeDecision> = emptyList(),
    val answeringDecisionId: String? = null,
    val models: List<NativeModel> = emptyList(),
    val selectedModel: NativeModel? = null,
    val modelChanging: Boolean = false,
    val readingPosition: NativeReadingPosition? = null,
    val draftId: String = "",
    val draftAttachments: List<NativeLocalAttachment> = emptyList(),
    val pendingRequestId: String? = null,
    val pendingCreationUnconfirmed: Boolean = false,
    val unreadSessionIds: Set<String> = emptySet(),
    val historyCursor: Long = -1,
    val managedSessionIds: Set<String> = emptySet(),
    val managedWorkspaceIds: Set<String> = emptySet(),
) {
    val readOnly: Boolean
        get() = when {
            selectedSessionId in managedSessionIds || selectedWorkspaceId in managedWorkspaceIds -> true
            selectedSessionId != null -> sessions.firstOrNull { it.id == selectedSessionId }?.let { it.readOnly || it.archived }
                ?: !(selectedSessionId == draftId && pendingRequestId != null)
            selectedWorkspaceId != null -> workspaces.firstOrNull { it.id == selectedWorkspaceId }?.readOnly ?: true
            else -> false
        }
    val archived: Boolean
        get() = sessions.firstOrNull { it.id == selectedSessionId }?.archived == true
}

data class NativeSpaceGroup(val id: String?, val title: String, val sessions: List<NativeSession>, val readOnly: Boolean = false)

fun NativeWorkbenchState.spaceGroups(ungroupedTitle: String): List<NativeSpaceGroup> {
    val userWorkspaces = workspaces.filterNot { it.id in managedWorkspaceIds }
    val userSessions = sessions.filterNot { it.id in managedSessionIds || it.workspaceId in managedWorkspaceIds }
    val groupedIds = userWorkspaces.flatMap { it.sessionIds }.toSet()
    val ordinary = userWorkspaces.filterNot { it.readOnly }
    fun group(space: NativeWorkspace) = NativeSpaceGroup(space.id, space.title,
        userSessions.filter { it.id in space.sessionIds || it.workspaceId == space.id }, space.readOnly)
    return ordinary.filter { it.id != defaultWorkspaceId }.map(::group) +
        ordinary.filter { it.id == defaultWorkspaceId }.map(::group) +
        NativeSpaceGroup(null, ungroupedTitle, userSessions.filter { it.id !in groupedIds && it.workspaceId == null }) +
        userWorkspaces.filter { it.readOnly }.map(::group)
}

data class NativeDecision(
    val id: String,
    val kind: String,
    val sessionId: String,
    val callId: String? = null,
    val toolName: String? = null,
    val reason: String? = null,
    val displayReasonZh: String? = null,
    val displayReasonEn: String? = null,
    val questions: List<NativeQuestion> = emptyList(),
    val state: String = "open",
    val expiresAt: Long? = null,
    val answerPending: Boolean = false,
    val canApprove: Boolean = false,
    val toolArguments: String? = null,
)

data class NativeQuestion(
    val id: String,
    val question: String,
    val header: String? = null,
    val detail: String? = null,
    val options: List<NativeQuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
)

data class NativeQuestionOption(val label: String, val description: String? = null)
data class NativeQuestionAnswer(val id: String, val selected: List<String> = emptyList(), val custom: String? = null)


data class NativeModel(
    val provider: String,
    val id: String,
    val name: String,
    val providerName: String = provider,
    val reasoningEffort: String? = null,
    val reasoningEfforts: List<String> = emptyList(),
    val defaultReasoningEffort: String? = null,
    val reasoningNames: Map<String, String> = emptyMap(),
)
