package io.droyagent.app.nativeui.standardvoice

import io.droyagent.app.nativeui.companion.maintenance.NativeCompanionWriteBarrier
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.droyagent.app.NativeRuntimeHost
import io.droyagent.app.NativeStandardVoiceHost
import io.droyagent.app.nativeui.data.NativeApiClient
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The retained native owner keeps polling an unlocked background call; cold recovery never resumes ASR. */
class NativeStandardVoiceViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("native_standard_voice", 0)
    private val gateway = NativeStandardVoiceClient()
    private var work: NativeWorkbenchViewModel? = null
    private var permissionLauncher: ((String) -> Unit)? = null
    private val hostError = MutableStateFlow<String?>(null)
    private val ending = MutableStateFlow(false)
    private val entry = MutableStateFlow<Boolean?>(null)
    val entryEnabled = entry.asStateFlow()
    private var entryRefresh: Job? = null
    private val bindings = object : NativeStandardVoiceBindings {
        private fun owner() = work ?: throw NativeApiException("native_standard_voice_failed")
        override suspend fun captureTarget(fresh: Boolean) = owner().captureStandardVoiceTarget(fresh)
        override suspend fun validateTarget(target: NativeStandardVoiceTarget) = owner().validateStandardVoiceTarget(target)
        override suspend fun submitText(target: NativeStandardVoiceTarget, nonce: String, text: String) = owner().submitStandardVoice(target, nonce, text)
        override suspend fun submissionStatus(target: NativeStandardVoiceTarget, nonce: String) = owner().standardVoiceSubmissionStatus(target, nonce)
        override suspend fun hostFacts(): NativeStandardVoiceHostFacts {
            val identity = NativeRuntimeHost.runtimeIdentity().takeIf(String::isNotEmpty) ?: throw NativeApiException("runtime_unavailable")
            return NativeStandardVoiceHostFacts(identity, NativeStandardVoiceHost.foreground(), NativeStandardVoiceHost.screenAvailable(getApplication()))
        }
        override suspend fun openAudioPermission(captureId: String) {
            (permissionLauncher ?: throw NativeApiException("native_standard_voice_screen_unavailable"))(captureId)
        }
    }
    private val coordinator = NativeStandardVoiceCoordinator(gateway, NativeStandardVoiceJsonStore(
        read = { withContext(Dispatchers.IO) { preferences.getString("journal", null) } },
        write = { raw -> withContext(Dispatchers.IO) { preferences.edit().putString("journal", raw).commit() } }), bindings, viewModelScope)
    val state = combine(coordinator.state, hostError, ending) { current, error, stopping ->
        current.copy(error = error ?: current.error, phase = if (stopping) NativeStandardVoicePhase.STOPPING else current.phase)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, coordinator.state.value)
    private var polling: Job? = null
    private var command: Job? = null
    private var finishing: Job? = null
    private var visible = false
    private var follower: Job? = null
    private var followedSession: String? = null
    private var followBaseline: NativeStandardVoiceBaseline? = null
    private var replies: NativeStandardVoiceReplies? = null

    init {
        viewModelScope.launch { coordinator.initialize() }
        viewModelScope.launch {
            coordinator.state.map { current ->
                current.journal?.operationId to (current.phase in setOf(NativeStandardVoicePhase.ENDED, NativeStandardVoicePhase.FOREIGN) || current.journal?.stopRequested == true)
            }.distinctUntilChanged().collect { (_, closed) ->
                if (closed) closeFollow()
            }
        }
    }

    internal fun isQuiescentForCompanionDeletion(): Boolean = state.value.phase !in setOf(
        NativeStandardVoicePhase.STARTING, NativeStandardVoicePhase.ACTIVE,
        NativeStandardVoicePhase.AWAITING_PERMISSION, NativeStandardVoicePhase.STOPPING)
    internal suspend fun freezeForCompanionDeletion() {
        val jobs = listOfNotNull(polling, follower)
        polling?.cancel(); polling = null; closeFollow()
        jobs.forEach { it.join() }
        listOfNotNull(command, finishing, entryRefresh).forEach { it.join() }
        check(isQuiescentForCompanionDeletion()) { "COMPANION_MAINTENANCE_BUSY" }
        coordinator.flushForCompanionDeletion()
    }
    internal fun resumeAfterCompanionDeletion() { if (visible) ensurePolling() }

    fun bind(model: NativeWorkbenchViewModel) {
        work = model
        model.bindStandardVoiceObservation(::observeBeforePrompt)
    }
    fun attachPermissionLauncher(launcher: ((String) -> Unit)?) { permissionLauncher = launcher }
    fun refreshEntry() {
        if (NativeCompanionWriteBarrier.frozen) return
        if (entryRefresh?.isActive == true) return
        entryRefresh = viewModelScope.launch {
            try { entry.value = gateway.config().enabled }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; entry.value = null; if (visible) report(failure) }
        }
    }
    fun setVisible(value: Boolean) { visible = value; if (value) { ensurePolling(); reconcile() } }
    fun canInsertRetained(): Boolean = state.value.journal?.target?.let { work?.canInsertStandardVoice(it) } == true
    fun retainedText(nonce: String): String? = coordinator.retainedText(nonce)

    fun onAction(action: NativeStandardVoiceAction) {
        if (NativeCompanionWriteBarrier.frozen) return
        when (action) {
            NativeStandardVoiceAction.Back, NativeStandardVoiceAction.End -> end()
            NativeStandardVoiceAction.Start -> execute { requireProductAccess(); coordinator.start() }
            is NativeStandardVoiceAction.SetMuted -> execute { coordinator.setUserMuted(action.muted) }
            is NativeStandardVoiceAction.SetReadAloud -> execute { coordinator.setReadAloud(action.enabled) }
            NativeStandardVoiceAction.StopReadout -> execute { coordinator.interruptOutput() }
            NativeStandardVoiceAction.RequestPermission -> execute { coordinator.requestPermission() }
            NativeStandardVoiceAction.Reconcile -> reconcile()
            NativeStandardVoiceAction.ResumeWake -> execute { coordinator.resumeWake() }
            is NativeStandardVoiceAction.Retry -> execute { coordinator.retry(action.nonce) }
            is NativeStandardVoiceAction.Discard -> execute { coordinator.discard(action.nonce) }
            is NativeStandardVoiceAction.InsertDraft -> execute {
                val current = coordinator.state.value
                val row = current.pending.firstOrNull { it.nonce == action.nonce } ?: throw NativeApiException("native_standard_voice_submission_missing")
                if (row.delivery !in setOf(NativeStandardVoiceDelivery.READY, NativeStandardVoiceDelivery.NOT_ACCEPTED))
                    throw NativeApiException("native_standard_voice_submission_unconfirmed")
                work?.insertStandardVoice(current.journal!!.target, row.nonce, row.text) ?: throw NativeApiException("native_standard_voice_failed")
                coordinator.discard(row.nonce)
            }
            is NativeStandardVoiceAction.Copy, NativeStandardVoiceAction.OpenConversation -> Unit // Presentation-owned actions.
        }
    }
    fun startFromWake(eventId: Long) = execute { requireProductAccess(); coordinator.startFromWake(eventId) }
    fun reconcile() = execute {
        if (NativeRuntimeHost.connection() == null) throw NativeApiException("runtime_unavailable")
        coordinator.reconcile()
    }

    private fun execute(block: suspend () -> Unit) {
        if (NativeCompanionWriteBarrier.frozen) return
        if (command?.isActive == true || finishing?.isActive == true) return
        command = viewModelScope.launch {
            hostError.value = null
            try { block(); ensurePolling() }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; report(failure) }
        }
    }

    fun end() {
        if (finishing?.isActive == true) return
        ending.value = true
        // Cancelling the client wait does not cancel DSH. Its durable nonce remains for receipt reconciliation.
        command?.cancel()
        polling?.cancel(); polling = null
        closeFollow()
        finishing = viewModelScope.launch {
            try { coordinator.stop() }
            finally { ending.value = false; ensurePolling() }
        }
    }

    private fun closeFollow() {
        follower?.cancel(); follower = null; followedSession = null; replies = null
        followBaseline?.cancel(); followBaseline = null
    }

    private fun requireProductAccess() {
        if (!NativeStandardVoiceHost.productAccessGranted(getApplication())) throw NativeApiException("PERMISSION_DENIED")
    }

    private fun ensurePolling() {
        if (NativeCompanionWriteBarrier.frozen) return
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            while (isActive) {
                val value = coordinator.state.value
                val active = value.journal?.startAttempted == true && !value.journal.stopRequested &&
                    value.phase in setOf(NativeStandardVoicePhase.ACTIVE, NativeStandardVoicePhase.STARTING, NativeStandardVoicePhase.AWAITING_PERMISSION, NativeStandardVoicePhase.REVIEW)
                if (!visible && !active) break
                if (NativeRuntimeHost.connection() != null && command?.isActive != true && finishing?.isActive != true) {
                    if (active && !value.recovered) coordinator.pollOnce()
                }
                delay(if (active) 120 else 800)
            }
        }
    }

    /** Open the actual DSH follow baseline before dispatch so a fast reply cannot fall into history. */
    private suspend fun observeBeforePrompt(sessionId: String, nonce: String) {
        val voice = coordinator.state.value
        // Explicit recovery may send retained text after voice has ended; that is not a new readout subscription.
        if (voice.recovered || voice.journal?.stopRequested == true || voice.phase in setOf(NativeStandardVoicePhase.ENDED, NativeStandardVoicePhase.FOREIGN)) return
        val existing = replies
        if (followedSession == sessionId && follower?.isActive == true && existing != null) {
            existing.register(nonce); awaitCurrentBaseline(); return
        }
        follower?.cancel()
        val projector = NativeStandardVoiceReplies(sessionId).also { it.register(nonce) }
        val baseline = NativeStandardVoiceBaseline()
        followBaseline?.cancel()
        followedSession = sessionId; replies = projector; followBaseline = baseline
        follower = viewModelScope.launch {
            while (isActive) {
                val ready = baseline.ticket()
                try {
                    NativeApiClient().follow(sessionId).collect { frame ->
                        val events = projector.accept(frame)
                        if (frame.optString("type") == "snapshot") baseline.opened(ready)
                        for (event in events) coordinator.onReplyEvent(event)
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (!baseline.failed(ready, failure)) return@launch
                    hostError.value = (failure as? NativeApiException)?.code ?: "native_standard_voice_failed"
                    delay(1000)
                }
            }
        }
        awaitCurrentBaseline()
    }

    private suspend fun awaitCurrentBaseline() {
        try {
            withTimeout(10_000) { (followBaseline ?: throw NativeApiException("stream_disconnected")).await() }
        } catch (timeout: TimeoutCancellationException) { throw NativeApiException("stream_disconnected", timeout) }
    }

    private fun report(failure: Exception) { hostError.value = (failure as? NativeApiException)?.code ?: "native_standard_voice_failed" }

    override fun onCleared() {
        coordinator.state.value.journal?.takeIf { it.startAttempted }?.let {
            NativeStandardVoiceHost.stopAfterOwnerDestroyed(getApplication(), it.runtimeIdentity, it.operationId)
        }
        permissionLauncher = null
        super.onCleared()
    }
}
