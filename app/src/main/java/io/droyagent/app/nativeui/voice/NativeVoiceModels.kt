package io.droyagent.app.nativeui.voice

import io.droyagent.app.nativeui.data.NativeRuntimeState

enum class NativeVoicePage { HOME, READOUT, WAKE }
data class NativeVoiceTtsDraft(val provider: String, val voiceId: String, val rate: Double, val url: String, val model: String)
data class NativeVoiceWakeDraft(val enabled: Boolean, val timing: String, val useCompanionName: Boolean, val chinese: String, val english: String)
data class NativeVoiceProgress(val available: Boolean = false, val state: String = "unknown", val ready: Boolean = false,
    val downloadedBytes: Long = 0, val totalBytes: Long = 0, val failed: Boolean = false)
data class NativeVoiceResource(val provider: String, val progress: NativeVoiceProgress)
data class NativeReadoutVoice(val id: String, val provider: String, val voiceId: String, val name: String, val networkRequired: Boolean?, val language: String? = null)
data class NativeVoicePermissions(val known: Boolean = false, val microphoneProduct: Boolean = false,
    val transcriptionProduct: Boolean = false, val microphoneSystem: Boolean = false, val paused: Boolean = true)
data class NativeVoiceSnapshot(
    val standardEntry: Boolean, val tts: NativeVoiceTtsDraft, val hasKey: Boolean, val savedWake: NativeVoiceWakeDraft,
    val runtimeAvailable: Boolean, val runtimeState: String, val speaking: Boolean, val runtimeFailed: Boolean,
    val wakeAvailable: Boolean, val actualWake: NativeVoiceWakeDraft?, val wakeState: String, val wakeFailed: Boolean,
    val screenAvailable: Boolean, val suspended: Boolean, val pausedForVoice: Boolean, val voiceClaimed: Boolean,
    val companionName: String, val wakePreparation: NativeVoiceProgress,
    val voicesAvailable: Boolean, val systemReady: Boolean, val voicesFailed: Boolean, val voices: List<NativeReadoutVoice>,
    val resourcesAvailable: Boolean, val resources: List<NativeVoiceResource>, val asr: NativeVoiceProgress,
    val previewAvailable: Boolean, val previewOwner: String?, val previewPhase: String?,
)
data class NativeVoiceState(
    val runtime: NativeRuntimeState = NativeRuntimeState(), val page: NativeVoicePage = NativeVoicePage.HOME,
    val snapshot: NativeVoiceSnapshot? = null, val ttsDraft: NativeVoiceTtsDraft? = null, val wakeDraft: NativeVoiceWakeDraft? = null,
    val permissions: NativeVoicePermissions = NativeVoicePermissions(), val fresh: Boolean = false, val loading: Boolean = false, val saving: Boolean = false,
    val error: String? = null, val feedback: String? = null, val credentialRetry: Boolean = false, val wakeSyncRetry: Boolean = false,
    val credentialVersion: Int = 0, val ownPreview: Boolean = false,
) {
    val needsPolling: Boolean get() = snapshot?.let { value ->
        value.speaking || value.actualWake?.enabled == true || value.wakePreparation.state in setOf("preparing", "downloading", "loading", "checking")
            || value.asr.state in setOf("preparing", "downloading", "loading", "checking", "waking")
            || value.resources.any { it.progress.state in setOf("downloading", "verifying") }
    } == true || ownPreview
    val ttsReady: Boolean get() = ttsDraft?.let { draft -> when (draft.provider) {
        "system" -> snapshot?.systemReady == true
        "openai" -> true
        else -> snapshot?.resources?.firstOrNull { it.provider == draft.provider }?.progress?.ready == true
    } } == true
}

sealed interface NativeVoiceAction {
    data object DiscardEdits : NativeVoiceAction
    data class SelectProvider(val provider: String) : NativeVoiceAction
    data object Refresh : NativeVoiceAction
    data object DismissError : NativeVoiceAction
    data object StartRuntime : NativeVoiceAction
    data class Open(val page: NativeVoicePage) : NativeVoiceAction
    data class SetStandard(val enabled: Boolean) : NativeVoiceAction
    data class EditTts(val draft: NativeVoiceTtsDraft) : NativeVoiceAction
    data class EditWake(val draft: NativeVoiceWakeDraft) : NativeVoiceAction
    class SaveTts(val credential: String = "") : NativeVoiceAction { override fun toString() = "SaveTts" }
    class RetryCredential(val credential: String) : NativeVoiceAction { override fun toString() = "RetryCredential" }
    data object ClearCredential : NativeVoiceAction
    data class SetWakeEnabled(val enabled: Boolean) : NativeVoiceAction
    data object SaveWake : NativeVoiceAction
    data object RetryWakeSync : NativeVoiceAction
    data object PrepareWake : NativeVoiceAction
    data class Download(val provider: String) : NativeVoiceAction
    data class CancelDownload(val provider: String) : NativeVoiceAction
    data object Preview : NativeVoiceAction
    data object StopPreview : NativeVoiceAction
}
