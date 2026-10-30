package io.droyagent.app.nativeui.standardvoice

/** draftToken must cover both text and attachments. It is opaque to voice and never edited here. */
data class NativeStandardVoiceTarget(
    val draftId: String,
    val sessionId: String?,
    val workspaceId: String?,
    val draftToken: String,
) {
    val captureSessionId: String get() = sessionId ?: draftId
}

data class NativeStandardVoiceTargetCheck(val writableAndOwned: Boolean, val draftUnchanged: Boolean)

/**
 * runtimeIdentity is a display-safe, non-secret UUID/epoch unique to each Host start, stable across Activities.
 * It is persisted. Never pass web/bridge tokens, API keys, a reusable port, or a Droy version as this handle.
 */
data class NativeStandardVoiceHostFacts(val runtimeIdentity: String, val foreground: Boolean, val screenAvailable: Boolean)

data class NativeStandardVoiceReceipt(val sessionId: String, val rpcId: String)

sealed interface NativeStandardVoiceSubmissionStatus {
    data class Accepted(val receipt: NativeStandardVoiceReceipt) : NativeStandardVoiceSubmissionStatus
    /** Only explicit not-dispatched / controlled rejection evidence can produce this result. */
    data object NotAccepted : NativeStandardVoiceSubmissionStatus
    data object Unknown : NativeStandardVoiceSubmissionStatus
}

/** Required product bindings. No target selection, draft replacement, authorization, or optimistic fallback. */
interface NativeStandardVoiceBindings {
    /** fresh=true allocates one independent local draft UUID for wake; never creates DSH or clears the ordinary draft. */
    suspend fun captureTarget(fresh: Boolean): NativeStandardVoiceTarget
    /** Proves the original product/UI owner and writable target; read-only/invalid never falls back to another space. */
    suspend fun validateTarget(target: NativeStandardVoiceTarget): NativeStandardVoiceTargetCheck
    /** Wrap Workbench.submitExternalShare(target, nonce, text, emptyList()). */
    suspend fun submitText(target: NativeStandardVoiceTarget, nonce: String, text: String): NativeStandardVoiceReceipt
    suspend fun submissionStatus(target: NativeStandardVoiceTarget, nonce: String): NativeStandardVoiceSubmissionStatus
    suspend fun hostFacts(): NativeStandardVoiceHostFacts
    /** Open the existing AudioPermissionActivity for this pending capture; never call authorize directly. */
    suspend fun openAudioPermission(captureId: String)
}

data class NativeStandardVoiceTone(
    val provider: String, val voiceId: String, val rate: Double, val url: String, val model: String,
)
data class NativeStandardVoiceConfig(val enabled: Boolean, val tone: NativeStandardVoiceTone)

data class NativeStandardVoiceNativeState(
    val state: String,
    val mode: String,
    val sessionId: String?,
    val captureId: String?,
    val transcriptId: String?,
    val muted: Boolean,
    val speaking: Boolean,
    val needsPermission: String?,
    val level: Float,
    val failed: Boolean,
    /** null identifies legacy/unclaimed state and is never proof of this native client's ownership. */
    val voiceOperationId: String? = null,
) {
    val active: Boolean get() = state in setOf("awaiting_user", "starting", "recording", "finalizing")
    // This confirms the voice API released its handles, not physical AudioRecord finalization.
    val stopped: Boolean get() = !active && captureId == null && transcriptId == null && !speaking
}

data class NativeStandardVoiceSegment(
    val id: String, val utteranceId: String, val text: String, val final: Boolean, val endOfUtterance: Boolean?,
) {
    val complete: Boolean get() = endOfUtterance ?: final
}
data class NativeStandardVoicePoll(
    val native: NativeStandardVoiceNativeState,
    val nextCursor: Long,
    val nextTranscriptCursor: Int,
    val segments: List<NativeStandardVoiceSegment>,
)
data class NativeStandardVoiceWakeState(
    val enabled: Boolean, val timing: String, val screenAvailable: Boolean, val suspended: Boolean,
    val voiceClaimed: Boolean, val eventId: Long, val startingEventId: Long,
    val voiceOperationId: String? = null, val startingVoiceOperationId: String? = null,
)

/** Every native mutation/poll carries one canonical UUID; the backend atomically fences it, including queued output. */
interface NativeStandardVoiceGateway {
    suspend fun config(): NativeStandardVoiceConfig
    suspend fun status(): NativeStandardVoiceNativeState
    suspend fun start(voiceOperationId: String, sessionId: String, wakeEventId: Long? = null): NativeStandardVoiceNativeState
    suspend fun poll(voiceOperationId: String, cursor: Long, transcriptCursor: Int, captureId: String?, transcriptId: String?): NativeStandardVoicePoll
    suspend fun mute(voiceOperationId: String, muted: Boolean): NativeStandardVoiceNativeState
    suspend fun speak(voiceOperationId: String, text: String, sessionId: String, replyId: String, utteranceId: String, tone: NativeStandardVoiceTone)
    suspend fun stopSpeaking(voiceOperationId: String)
    suspend fun stop(voiceOperationId: String)
    suspend fun wakeStatus(): NativeStandardVoiceWakeState
    suspend fun consumeWake(voiceOperationId: String, eventId: Long): Boolean
    suspend fun claimWake(voiceOperationId: String)
    suspend fun releaseWake(voiceOperationId: String)
}

enum class NativeStandardVoicePhase { IDLE, STARTING, ACTIVE, AWAITING_PERMISSION, REVIEW, RECOVERED, FOREIGN, UNCONFIRMED, STOPPING, ENDED }
enum class NativeStandardVoiceDelivery { READY, SUBMITTING, UNKNOWN, NOT_ACCEPTED, ACCEPTED, DISCARDED }
enum class NativeStandardVoiceWakePhase { CONSUMING, CONSUMED, UNKNOWN, REJECTED }

/** Final text and nonce are immutable, including after a lost submission response. */
data class NativeStandardVoiceSubmission(
    val utteranceKey: String,
    val nonce: String,
    val text: String,
    val delivery: NativeStandardVoiceDelivery,
    val receipt: NativeStandardVoiceReceipt? = null,
)
data class NativeStandardVoiceWake(val eventId: Long, val phase: NativeStandardVoiceWakePhase)
data class NativeStandardVoiceJournal(
    val operationId: String,
    val runtimeIdentity: String,
    val target: NativeStandardVoiceTarget,
    val captureId: String? = null,
    val startAttempted: Boolean = false,
    val stopRequested: Boolean = false,
    val wake: NativeStandardVoiceWake? = null,
    val submissions: List<NativeStandardVoiceSubmission> = emptyList(),
)

data class NativeStandardVoiceState(
    val phase: NativeStandardVoicePhase = NativeStandardVoicePhase.IDLE,
    val journal: NativeStandardVoiceJournal? = null,
    val native: NativeStandardVoiceNativeState? = null,
    val config: NativeStandardVoiceConfig? = null,
    val userMuted: Boolean = false,
    val automaticMuted: Boolean = false,
    val readAloud: Boolean = false,
    val partialTranscript: String = "",
    val reply: String = "",
    val outputPending: Int = 0,
    val outputUnconfirmed: Boolean = false,
    val recovered: Boolean = false,
    val error: String? = null,
) {
    val pending: List<NativeStandardVoiceSubmission> get() = journal?.submissions.orEmpty().filter {
        it.delivery != NativeStandardVoiceDelivery.ACCEPTED && it.delivery != NativeStandardVoiceDelivery.DISCARDED
    }
}

/** Only new live user-source / assistant-text events. The host filters thinking, tool blocks and history. */
sealed interface NativeStandardVoiceReplyEvent {
    val sessionId: String
    val turnId: String
    val live: Boolean
    data class Bound(override val sessionId: String, override val turnId: String, val rpcId: String,
                     override val live: Boolean) : NativeStandardVoiceReplyEvent
    data class Text(override val sessionId: String, override val turnId: String, val attemptId: String,
                    val eventId: String, val text: String, val final: Boolean,
                    override val live: Boolean) : NativeStandardVoiceReplyEvent
    data class Interrupted(override val sessionId: String, override val turnId: String, val attemptId: String,
                           override val live: Boolean) : NativeStandardVoiceReplyEvent
}
