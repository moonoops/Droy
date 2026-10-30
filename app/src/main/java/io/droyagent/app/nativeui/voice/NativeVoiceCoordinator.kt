package io.droyagent.app.nativeui.voice

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeRuntimeState
import io.droyagent.app.nativeui.permissions.NativePermissionGrant
import io.droyagent.app.nativeui.permissions.NativePermissionsState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class NativeVoiceCoordinator(private val gateway: NativeVoiceGateway, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(NativeVoiceState())
    val state = mutable.asStateFlow()
    private var refreshJob: Job? = null
    private var operation: Job? = null
    private var previewToken: String? = null
    private var rebaseTts = false
    private var rebaseWake = false
    private val providerDrafts = mutableMapOf<String, NativeVoiceTtsDraft>()

    fun runtime(value: NativeRuntimeState) { mutable.update { it.copy(runtime = value, fresh = it.fresh && value.ready) } }
    fun permissions(value: NativePermissionsState) {
        mutable.update { it.copy(permissions = NativeVoicePermissions(value.fresh,
            value.products.firstOrNull { choice -> choice.preference == "allowAudioMicrophone" }?.enabled == true,
            value.products.firstOrNull { choice -> choice.preference == "allowAudioTranscription" }?.enabled == true,
            value.system.firstOrNull { permission -> permission.id == "microphone" }?.grant == NativePermissionGrant.GRANTED,
            value.toolsPaused)) }
    }
    fun report(code: String) { mutable.update { it.copy(error = code) } }
    fun refresh() {
        if (!state.value.runtime.ready || state.value.saving || refreshJob?.isActive == true) return
        refreshJob = scope.launch { load() }
    }
    fun leave() {
        refreshJob?.cancel(); refreshJob = null
        val token = previewToken ?: return
        val pending = operation
        scope.launch {
            pending?.join()
            try {
                gateway.stopPreview(token)
                if (previewToken == token) { previewToken = null; mutable.update { it.copy(ownPreview = false) } }
            } catch (failure: Exception) { handle(failure, "voice_stop_failed") }
        }
    }
    fun onAction(action: NativeVoiceAction) {
        when (action) {
            NativeVoiceAction.DiscardEdits -> {
                providerDrafts.clear()
                mutable.update { it.copy(ttsDraft = it.snapshot?.tts, wakeDraft = it.snapshot?.actualWake ?: it.snapshot?.savedWake, credentialRetry = false, wakeSyncRetry = false) }
            }
            is NativeVoiceAction.SelectProvider -> {
                val current = state.value.ttsDraft ?: return
                if (action.provider !in setOf("system", "melo", "qwen3", "openai")) return
                leave()
                providerDrafts[current.provider] = current
                val draft = providerDrafts[action.provider] ?: state.value.snapshot?.tts?.takeIf { it.provider == action.provider }
                    ?: current.copy(provider = action.provider, voiceId = when (action.provider) { "melo" -> "zh"; "qwen3" -> "Serena"; else -> "" },
                        rate = if (action.provider == "qwen3") 1.0 else current.rate)
                mutable.update { it.copy(ttsDraft = draft, credentialRetry = false, feedback = null) }
            }
            NativeVoiceAction.Refresh -> { mutable.update { it.copy(error = null) }; refresh() }
            NativeVoiceAction.DismissError -> mutable.update { it.copy(error = null, feedback = null) }
            NativeVoiceAction.StartRuntime -> Unit
            is NativeVoiceAction.Open -> {
                if (state.value.page == NativeVoicePage.READOUT && action.page != NativeVoicePage.READOUT) leave()
                mutable.update { it.copy(page = action.page, feedback = null) }
            }
            is NativeVoiceAction.EditTts -> {
                if (state.value.ttsDraft?.voiceId != action.draft.voiceId || state.value.ttsDraft?.provider != action.draft.provider) leave()
                mutable.update { it.copy(ttsDraft = action.draft, credentialRetry = false, feedback = null) }
            }
            is NativeVoiceAction.EditWake -> mutable.update { it.copy(wakeDraft = action.draft, wakeSyncRetry = false, feedback = null) }
            is NativeVoiceAction.SetStandard -> mutate {
                gateway.standard(action.enabled); mutable.update { it.copy(feedback = "voice_saved") }
            }
            is NativeVoiceAction.SaveTts -> mutate {
                val draft = requireNotNull(state.value.ttsDraft)
                gateway.saveTts(draft)
                rebaseTts = true
                mutable.update { it.copy(credentialRetry = false) }
                if (action.credential.isNotBlank()) {
                    try { gateway.credential(action.credential) }
                    catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        mutable.update { it.copy(credentialRetry = true) }
                        throw NativeApiException("voice_credential_retry")
                    }
                    mutable.update { it.copy(credentialVersion = it.credentialVersion + 1) }
                }
                mutable.update { it.copy(feedback = "voice_saved") }
            }
            is NativeVoiceAction.RetryCredential -> mutate {
                if (action.credential.isBlank()) throw NativeApiException("voice_credential_required")
                gateway.credential(action.credential)
                mutable.update { it.copy(credentialRetry = false, credentialVersion = it.credentialVersion + 1, feedback = "voice_saved") }
            }
            NativeVoiceAction.ClearCredential -> mutate {
                gateway.credential(null)
                mutable.update { it.copy(credentialRetry = false, credentialVersion = it.credentialVersion + 1, feedback = "voice_saved") }
            }
            is NativeVoiceAction.SetWakeEnabled -> saveWake(requireNotNull(state.value.wakeDraft).copy(enabled = action.enabled), false)
            NativeVoiceAction.SaveWake -> state.value.wakeDraft?.let { saveWake(it, false) }
            NativeVoiceAction.RetryWakeSync -> state.value.wakeDraft?.let { saveWake(it, true) }
            NativeVoiceAction.PrepareWake -> mutate { gateway.prepareWake() }
            is NativeVoiceAction.Download -> mutate { gateway.resource(action.provider, false) }
            is NativeVoiceAction.CancelDownload -> mutate { gateway.resource(action.provider, true) }
            NativeVoiceAction.Preview -> mutate {
                val token = UUID.randomUUID().toString()
                val draft = requireNotNull(state.value.ttsDraft)
                previewToken = token; mutable.update { it.copy(ownPreview = true) }
                gateway.preview(token, draft)
            }
            NativeVoiceAction.StopPreview -> mutate {
                previewToken?.let { gateway.stopPreview(it) }
                previewToken = null; mutable.update { it.copy(ownPreview = false) }
            }
        }
    }
    private fun saveWake(draft: NativeVoiceWakeDraft, commitOnly: Boolean) = mutate {
        try { gateway.saveWake(draft, commitOnly) }
        catch (failure: Exception) {
            if (failure is NativeApiException && failure.code == "NATIVE_VOICE_WAKE_NOT_SAVED") {
                rebaseWake = true; mutable.update { it.copy(wakeSyncRetry = true, wakeDraft = draft) }
            }
            throw failure
        }
        rebaseWake = true
        mutable.update { it.copy(wakeDraft = draft, wakeSyncRetry = false, feedback = "voice_saved") }
    }
    private fun mutate(block: suspend () -> Unit) {
        if (operation?.isActive == true || !state.value.runtime.ready) return
        refreshJob?.cancel(); refreshJob = null
        operation = scope.launch {
            mutable.update { it.copy(saving = true, error = null, feedback = null) }
            try { block() }
            catch (failure: Exception) { handle(failure) }
            finally {
                try { load() } finally { mutable.update { it.copy(saving = false) } }
            }
        }
    }
    private suspend fun load() {
        mutable.update { it.copy(loading = it.snapshot == null) }
        try {
            val latest = gateway.read()
            mutable.update { previous ->
                val oldWake = previous.snapshot?.actualWake ?: previous.snapshot?.savedWake
                val keepTts = !rebaseTts && previous.ttsDraft != null && previous.ttsDraft != previous.snapshot?.tts
                val keepWake = !rebaseWake && previous.wakeDraft != null && previous.wakeDraft != oldWake
                previous.copy(snapshot = latest, fresh = true, loading = false,
                    ttsDraft = if (keepTts) previous.ttsDraft else latest.tts,
                    wakeDraft = if (keepWake) previous.wakeDraft else latest.actualWake ?: latest.savedWake,
                    ownPreview = if (latest.previewAvailable) previewToken != null && latest.previewOwner == previewToken else previous.ownPreview)
            }
            rebaseTts = false; rebaseWake = false
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            mutable.update { it.copy(fresh = false, loading = false, error = it.error ?: code(failure)) }
        }
    }
    private fun code(failure: Exception) = (failure as? NativeApiException)?.code ?: "NATIVE_VOICE_FAILED"
    private fun handle(failure: Exception, override: String? = null) {
        if (failure is CancellationException) throw failure
        mutable.update { it.copy(error = override ?: code(failure), feedback = null) }
    }
}
