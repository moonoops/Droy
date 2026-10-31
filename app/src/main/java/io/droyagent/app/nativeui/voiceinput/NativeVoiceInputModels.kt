package io.droyagent.app.nativeui.voiceinput

import io.droyagent.app.nativeui.media.NativeLocalAttachment
import io.droyagent.app.nativeui.media.NativeWorkbenchMediaTarget
import org.json.JSONObject

data class NativeVoiceInputTarget(val workbench: NativeWorkbenchMediaTarget? = null, val companionId: String? = null) {
    init { require((workbench != null) != (companionId != null)); companionId?.let { require(it.isNotBlank() && it.length <= 1024 && it.none(Char::isISOControl)) } }
    fun json(): JSONObject = workbench?.let { JSONObject().put("kind", "workbench").put("draftId", it.draftId)
        .put("sessionId", it.sessionId ?: JSONObject.NULL).put("workspaceId", it.workspaceId ?: JSONObject.NULL) }
        ?: JSONObject().put("kind", "companion").put("companionId", companionId)
    companion object {
        fun parse(value: JSONObject): NativeVoiceInputTarget = when (value.getString("kind")) {
            "companion" -> NativeVoiceInputTarget(companionId = value.getString("companionId"))
            "workbench" -> NativeVoiceInputTarget(workbench = NativeWorkbenchMediaTarget(value.getString("draftId"),
                if (value.isNull("sessionId")) null else value.getString("sessionId"), if (value.isNull("workspaceId")) null else value.getString("workspaceId")))
            else -> throw IllegalArgumentException("INVALID_VOICE_TARGET")
        }
    }
}

enum class NativeVoiceInputPhase { IDLE, STARTING, RECORDING, STOPPING, RECOGNIZING, READY, FAILED, SENDING, UNCONFIRMED, INTERRUPTED, CANCELLING, SENT }
data class NativeVoiceInputPermissions(
    val microphone: Boolean = false, val microphoneAllowed: Boolean = false, val transcriptionAllowed: Boolean = false,
    val paused: Boolean = true, val runtimeReady: Boolean = false,
) {
    val permitted: Boolean get() = microphone && microphoneAllowed && !paused && runtimeReady
    companion object {
        fun parse(value: JSONObject) = NativeVoiceInputPermissions(value.getBoolean("microphone"), value.getBoolean("microphoneAllowed"),
            value.getBoolean("transcriptionAllowed"), value.getBoolean("paused"), value.getBoolean("runtimeReady"))
    }
}
data class NativeSpeechCatalog(val maxAudioBytes: Long, val maxDurationSeconds: Double, val nativeMaxAudioBytes: Long, val ready: Boolean) {
    val captureLimitMs: Long get() = minOf(60_000L, (nativeMaxAudioBytes - 44) * 1000 / 96000,
        if (ready) minOf((maxDurationSeconds * 1000).toLong(), (maxAudioBytes - 44) * 1000 / 32000) else 60_000L).coerceAtLeast(1)
}
data class NativeSpeechResult(val text: String, val audioSeconds: Double, val inferenceSeconds: Double)
data class NativeSpeechOperation(val phase: String, val quiescent: Boolean, val settled: Boolean, val result: NativeSpeechResult? = null)
data class NativeVoiceInputState(
    val visible: Boolean = false, val target: NativeVoiceInputTarget? = null, val operationId: String? = null,
    val phase: NativeVoiceInputPhase = NativeVoiceInputPhase.IDLE, val permissions: NativeVoiceInputPermissions = NativeVoiceInputPermissions(),
    val catalog: NativeSpeechCatalog? = null, val durationMs: Long = 0, val level: Float = 0f,
    val captureQuiescent: Boolean = true, val recognitionQuiescent: Boolean = true, val fileReady: Boolean = false, val cancelGesture: Boolean = false,
    val transcript: String = "", val source: NativeLocalAttachment? = null, val error: String? = null,
    val previewing: Boolean = false, val sendAttempted: Boolean = false, val accepted: Boolean = false, val resultSessionId: String? = null,
) {
    // Once released, the recording belongs in the timeline while optional transcription runs.
    val showPendingBubble: Boolean get() = operationId != null && !accepted && phase in setOf(
        NativeVoiceInputPhase.STOPPING, NativeVoiceInputPhase.RECOGNIZING, NativeVoiceInputPhase.READY, NativeVoiceInputPhase.SENDING)
    val recording: Boolean get() = phase == NativeVoiceInputPhase.RECORDING
    val quiescent: Boolean get() = captureQuiescent && recognitionQuiescent && phase !in setOf(NativeVoiceInputPhase.STARTING, NativeVoiceInputPhase.SENDING)
    val busy: Boolean get() = phase in setOf(NativeVoiceInputPhase.STARTING, NativeVoiceInputPhase.STOPPING, NativeVoiceInputPhase.RECOGNIZING,
        NativeVoiceInputPhase.SENDING, NativeVoiceInputPhase.CANCELLING)
    val canRecord: Boolean get() = visible && target != null && operationId == null && !busy && permissions.permitted
    val canRetry: Boolean get() = fileReady && captureQuiescent && !busy && !accepted
    val canRecognize: Boolean get() = canRetry && !sendAttempted && permissions.runtimeReady && !permissions.paused && permissions.transcriptionAllowed && catalog?.ready == true
}

internal fun cleanVoiceTranscript(text: String): String = text.replace(Regex("<\\|[^|]*\\|>"), "").trim().takeIf { value -> value.any(Char::isLetterOrDigit) } ?: ""
internal fun isVoiceCaptureLongEnough(durationMs: Long): Boolean = durationMs >= 300
internal fun mayAutoFinishVoiceCapture(state: String, reason: String, fileReady: Boolean): Boolean =
    state == "completed" && reason == "duration_limit" && fileReady

sealed interface NativeVoiceInputAction {
    data object TogglePreview : NativeVoiceInputAction
    data object BeginHold : NativeVoiceInputAction
    data class MoveHold(val cancelled: Boolean) : NativeVoiceInputAction
    data class FinishHold(val cancelled: Boolean) : NativeVoiceInputAction
    data object Cancel : NativeVoiceInputAction
    data object RetryRecognition : NativeVoiceInputAction
    data object SendOriginal : NativeVoiceInputAction
    data object SendRecognized : NativeVoiceInputAction
    data object RetrySend : NativeVoiceInputAction
    data object Refresh : NativeVoiceInputAction
    data object StartRuntime : NativeVoiceInputAction
    data object Leave : NativeVoiceInputAction
    data object Done : NativeVoiceInputAction
    data object DismissError : NativeVoiceInputAction
}

/** ASR is optional metadata. Cancellation still cancels the user's operation. */
internal suspend fun recognizeVoiceOrOriginal(
    recognize: suspend () -> NativeSpeechResult,
    settle: suspend () -> Unit,
): NativeSpeechResult? = try { recognize() }
catch (error: Exception) {
    if (error is kotlinx.coroutines.CancellationException) throw error
    settle()
    null
}
