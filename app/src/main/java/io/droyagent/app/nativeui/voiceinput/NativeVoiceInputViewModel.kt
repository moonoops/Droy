package io.droyagent.app.nativeui.voiceinput

import io.droyagent.app.nativeui.companion.maintenance.NativeCompanionWriteBarrier
import android.app.Application
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.droyagent.app.NativeVoiceInputHost
import io.droyagent.app.NativeRuntimeHost
import io.droyagent.app.nativeui.companion.data.NativeCompanionAttachment
import io.droyagent.app.nativeui.companion.data.NativeCompanionDraft
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.companion.data.NativeCompanionVoice
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.media.NativeDraftMediaStore
import io.droyagent.app.nativeui.media.NativeLocalAttachment
import io.droyagent.app.nativeui.media.NativeMediaViewModel
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class VoiceGesture(val id: String, val target: NativeVoiceInputTarget, var down: Boolean = true, var cancelled: Boolean = false)

/** One persistent recording and one immutable target; permission callbacks only refresh facts. */
class NativeVoiceInputViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("native_voice_input", 0)
    private val persistence = Mutex()
    private var revision = 0L
    private var persistedRevision = -1L
    private val media = NativeDraftMediaStore(application)
    private val speech = NativeSpeechClient()
    private var workbench: NativeWorkbenchViewModel? = null
    private var companions: NativeCompanionViewModel? = null
    private var observing: Job? = null
    private var preparing: Job? = null
    private var processing: Job? = null
    private var ending: Job? = null
    private var refreshing: Job? = null
    private var gesture: VoiceGesture? = null
    private var cursor = 0L
    private var recognitionId: String? = null
    private var recognitionAttempted = false
    private var autoSend = false
    private var rawSubmission = false
    private var uploadedVoice: NativeCompanionAttachment? = null
    private var catalogueAt = 0L
    private var actionEpoch = 0L
    private var previewJob: Job? = null
    private var previewPlayer: MediaPlayer? = null
    private var restoreFailed = false
    private val _state = MutableStateFlow(restore())
    val state: StateFlow<NativeVoiceInputState> = _state.asStateFlow()

    init { refresh() }
    internal fun isQuiescentForCompanionDeletion(): Boolean = _state.value.quiescent
    internal suspend fun freezeForCompanionDeletion(flush: Boolean = true) {
        check(!restoreFailed) { "COMPANION_MAINTENANCE_STORAGE" }
        observing?.cancel(); observing = null
        stopPreview()
        io.droyagent.app.nativeui.companion.maintenance.drainCompanionWriters(viewModelScope)
        check(_state.value.quiescent) { "COMPANION_MAINTENANCE_BUSY" }
        if (flush) persist()
    }
    internal fun reloadAfterCompanionDeletion() {
        restoreFailed = false
        uploadedVoice = null
        _state.value = restore()
        check(_state.value.error != "native_voice_storage") { "COMPANION_MAINTENANCE_STORAGE" }
    }

    fun bind(work: NativeWorkbenchViewModel, companion: NativeCompanionViewModel) { workbench = work; companions = companion }
    fun onAction(action: NativeVoiceInputAction) {
        if (NativeCompanionWriteBarrier.frozen) return
        when (action) {
            NativeVoiceInputAction.TogglePreview -> togglePreview()
            NativeVoiceInputAction.BeginHold -> beginHold()
            is NativeVoiceInputAction.MoveHold -> moveHold(action.cancelled)
            is NativeVoiceInputAction.FinishHold -> finishHold(action.cancelled)
            NativeVoiceInputAction.Cancel -> cancel()
            NativeVoiceInputAction.RetryRecognition -> retryRecognition()
            NativeVoiceInputAction.SendOriginal -> sendOriginal()
            NativeVoiceInputAction.SendRecognized -> sendRecognized()
            NativeVoiceInputAction.RetrySend -> retrySend()
            NativeVoiceInputAction.Refresh -> refresh()
            NativeVoiceInputAction.Leave -> leave()
            NativeVoiceInputAction.Done -> consumeSent()
            NativeVoiceInputAction.DismissError -> dismissError()
            NativeVoiceInputAction.StartRuntime -> {
                try { NativeRuntimeHost.start(getApplication()); refresh() }
                catch (_: Exception) { report("runtime_start_failed") }
            }
        }
    }

    fun open(target: NativeVoiceInputTarget) {
        if (NativeCompanionWriteBarrier.frozen) return
        val current = _state.value
        if (current.operationId != null && current.target != null && current.target != target) {
            _state.update { it.copy(visible = true, error = "native_voice_pending") }; refresh(); return
        }
        _state.update { it.copy(visible = true, target = current.target.takeIf { current.operationId != null } ?: target, error = null) }
        refresh()
    }

    fun refresh() {
        if (NativeCompanionWriteBarrier.frozen || restoreFailed) return
        if (refreshing?.isActive == true) return
        refreshing = viewModelScope.launch {
            try {
                readCapture(recovered = gesture == null && !_state.value.busy)
                if (_state.value.permissions.runtimeReady) {
                    val catalog = speech.catalog(); catalogueAt = System.currentTimeMillis()
                    _state.update { it.copy(catalog = catalog) }
                }
            } catch (error: Exception) { if (error is CancellationException) throw error; report(code(error)) }
        }
    }
    fun startObserving() {
        if (NativeCompanionWriteBarrier.frozen || restoreFailed) return
        if (observing?.isActive == true) return
        observing = viewModelScope.launch {
            while (isActive) {
                try {
                    readCapture()
                    if (_state.value.visible && _state.value.permissions.runtimeReady && _state.value.catalog?.ready != true
                        && System.currentTimeMillis() - catalogueAt > 2000) refresh()
                } catch (error: Exception) { if (error is CancellationException) throw error; report(code(error)) }
                delay(if (_state.value.recording || _state.value.phase == NativeVoiceInputPhase.STARTING) 80 else 600)
            }
        }
    }
    fun stopObserving() { observing?.cancel(); observing = null; leave() }

    fun beginHold() {
        if (NativeCompanionWriteBarrier.frozen) return
        val current = _state.value
        if (!current.visible || gesture != null || current.operationId != null || current.busy) return
        if (!current.canRecord) {
            report(when {
                !current.permissions.runtimeReady -> "runtime_unavailable"
                current.permissions.paused -> "native_voice_paused"
                !current.permissions.permitted -> "native_voice_permission"
                else -> "NATIVE_SPEECH_NOT_READY"
            })
            return
        }
        stopPreview()
        val target = current.target ?: return
        val id = UUID.randomUUID().toString()
        val ticket = VoiceGesture(id, target); gesture = ticket; autoSend = false; cursor = 0
        recognitionId = null; recognitionAttempted = false; uploadedVoice = null; rawSubmission = false
        _state.update { it.copy(operationId = id, phase = NativeVoiceInputPhase.STARTING, captureQuiescent = false, recognitionQuiescent = true, error = null,
            transcript = "", source = null, fileReady = false, accepted = false, sendAttempted = false, resultSessionId = null, cancelGesture = false) }
        preparing = viewModelScope.launch {
            try {
                persist()
                withContext(Dispatchers.IO) { NativeVoiceInputHost.prepare(getApplication(), id, target.json(), current.catalog?.captureLimitMs ?: 60_000L) }
                if (!ticket.down || ticket.cancelled) {
                    withContext(Dispatchers.IO) { NativeVoiceInputHost.stop(getApplication(), id, true) }; return@launch
                }
                val snapshot = withContext(Dispatchers.IO) { NativeVoiceInputHost.start(getApplication(), id) }
                applyCapture(snapshot)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (!ticket.cancelled) _state.update { it.copy(phase = NativeVoiceInputPhase.FAILED, error = code(error)) }
            } finally { persistLater() }
        }
    }
    fun moveHold(cancelled: Boolean) {
        if (NativeCompanionWriteBarrier.frozen) return
        val ticket = gesture ?: return
        if (!ticket.down) return
        ticket.cancelled = cancelled
        _state.update { it.copy(cancelGesture = cancelled) }
    }
    fun finishHold(cancelled: Boolean = false) {
        if (NativeCompanionWriteBarrier.frozen) return
        val ticket = gesture ?: return
        if (!ticket.down) return
        ticket.down = false
        ticket.cancelled = ticket.cancelled || cancelled || _state.value.phase != NativeVoiceInputPhase.RECORDING
        autoSend = !ticket.cancelled
        stopRecording(ticket.id, ticket.cancelled)
    }

    fun cancel() {
        if (NativeCompanionWriteBarrier.frozen) return
        actionEpoch++
        autoSend = false
        gesture?.let { it.down = false; it.cancelled = true }
        if (_state.value.sendAttempted && !_state.value.accepted) {
            _state.update { it.copy(phase = NativeVoiceInputPhase.UNCONFIRMED, error = "native_voice_send_unconfirmed") }; return
        }
        val id = _state.value.operationId ?: run { _state.update { it.copy(visible = false) }; return }
        stopRecording(id, true)
    }
    fun leave() {
        if (NativeCompanionWriteBarrier.frozen) return
        stopPreview()
        if (autoSend && _state.value.showPendingBubble) {
            // Navigation does not revoke a recording the user already released to send.
            _state.update { it.copy(visible = false) }
            persistLater()
            return
        }
        actionEpoch++
        _state.update { it.copy(visible = false) }; autoSend = false
        if (_state.value.operationId != null && !_state.value.quiescent || _state.value.phase == NativeVoiceInputPhase.RECOGNIZING) cancel()
        else persistLater()
    }

    private fun stopRecording(id: String, cancelled: Boolean) {
        if (ending?.isActive == true) return
        _state.update { it.copy(phase = if (cancelled) NativeVoiceInputPhase.CANCELLING else NativeVoiceInputPhase.STOPPING, level = 0f) }
        ending = viewModelScope.launch {
            try {
                preparing?.join()
                val before = withContext(Dispatchers.IO) { NativeVoiceInputHost.snapshot(getApplication(), null, 0) }
                val own = before.optJSONObject("operation")
                if (own != null) {
                    if (own.getString("id") != id) throw NativeApiException("native_voice_owner")
                    try { applyCapture(withContext(Dispatchers.IO) { NativeVoiceInputHost.stop(getApplication(), id, cancelled) }) }
                    catch (error: Exception) { if (error is CancellationException) throw error; report(code(error)) }
                    awaitCaptureQuiet(id)
                } else _state.update { it.copy(captureQuiescent = true, fileReady = false) }
                if (cancelled) {
                    cancelRecognition()
                    processing?.join()
                    if (own != null) withContext(Dispatchers.IO) { NativeVoiceInputHost.discard(getApplication(), id) }
                    clearRecording()
                } else if (_state.value.fileReady && !isVoiceCaptureLongEnough(_state.value.durationMs)) {
                    withContext(Dispatchers.IO) { NativeVoiceInputHost.discard(getApplication(), id) }
                    clearRecording()
                    report("native_voice_too_short")
                } else if (_state.value.fileReady) {
                    _state.update { it.copy(phase = NativeVoiceInputPhase.READY, cancelGesture = false) }
                    if (autoSend) recognizeAndSend()
                } else {
                    _state.update { it.copy(phase = NativeVoiceInputPhase.FAILED, error = "native_voice_no_speech") }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update { it.copy(phase = NativeVoiceInputPhase.CANCELLING, error = code(error)) }
                persistLater()
            }
        }
    }

    private suspend fun awaitCaptureQuiet(id: String) {
        val deadline = System.currentTimeMillis() + 30000
        while (true) {
            applyCapture(withContext(Dispatchers.IO) { NativeVoiceInputHost.snapshot(getApplication(), id, cursor) })
            if (_state.value.captureQuiescent) return
            if (System.currentTimeMillis() >= deadline) throw NativeApiException("native_voice_stop_unconfirmed")
            delay(100)
        }
    }
    private suspend fun nativeAsrQuiet(): Boolean = withContext(Dispatchers.IO) { NativeVoiceInputHost.transcriptionQuiescent(getApplication()) }
    private suspend fun cancelRecognition() {
        val id = recognitionId
        if (id == null || !recognitionAttempted) { _state.update { it.copy(recognitionQuiescent = true) }; return }
        var settled = false
        try { settled = speech.cancel(id).settled }
        catch (error: NativeApiException) { if (error.code == "NATIVE_SPEECH_OPERATION_UNKNOWN") settled = true else throw error }
        val deadline = System.currentTimeMillis() + 30000
        while (!settled || !nativeAsrQuiet()) {
            if (System.currentTimeMillis() >= deadline) throw NativeApiException("native_voice_stop_unconfirmed")
            delay(200)
            if (!settled) settled = speech.operation(id).settled
        }
        _state.update { it.copy(recognitionQuiescent = true) }
    }

    fun retryRecognition() {
        if (NativeCompanionWriteBarrier.frozen) return
        if (!_state.value.canRecognize) return
        autoSend = true; recognizeAndSend()
    }
    private fun recognizeAndSend() {
        if (processing?.isActive == true) return
        val id = _state.value.operationId ?: return
        val epoch = actionEpoch
        processing = viewModelScope.launch {
            _state.update { it.copy(phase = NativeVoiceInputPhase.RECOGNIZING, recognitionQuiescent = false, error = null) }
            NativeMediaViewModel.submissionActivity("voice:$id", true)
            try {
                val source = ensureSource(id)
                if (!autoSend) return@launch
                val permissions = NativeVoiceInputPermissions.parse(withContext(Dispatchers.IO) { NativeVoiceInputHost.permissions(getApplication()) })
                _state.update { it.copy(permissions = permissions) }
                if (permissions.paused) throw NativeApiException("native_voice_paused")
                if (!permissions.runtimeReady) throw NativeApiException("runtime_unavailable")
                // Recognition enriches the original recording; it does not decide whether it can be sent.
                var result: NativeSpeechResult? = null
                if (permissions.transcriptionAllowed && _state.value.catalog?.ready == true) {
                    recognitionId?.takeIf { recognitionAttempted }?.let { old ->
                        val previous = try { speech.operation(old) }
                        catch (error: NativeApiException) { if (error.code == "NATIVE_SPEECH_OPERATION_UNKNOWN") null else throw error }
                        if (previous != null && !previous.settled) throw NativeApiException("native_voice_stop_unconfirmed")
                        if (previous?.quiescent != true && !nativeAsrQuiet()) throw NativeApiException("native_voice_stop_unconfirmed")
                        result = previous?.result
                    }
                    if (result == null) {
                        recognitionId = UUID.randomUUID().toString(); recognitionAttempted = true; persist()
                        if (!autoSend) return@launch
                        val audio = media.bytes(source)
                        result = recognizeVoiceOrOriginal(
                            recognize = { speech.transcribe(audio, recognitionId!!) },
                            settle = { cancelRecognition() },
                        )
                    }
                }
                _state.update { it.copy(transcript = result?.text.orEmpty(), phase = NativeVoiceInputPhase.READY, recognitionQuiescent = true, error = null) }
                persist()
                if (autoSend && gesture?.cancelled != true) sendCaptured(_state.value.transcript.isBlank(), automatic = true, expectedEpoch = epoch)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (ending?.isActive != true) _state.update { it.copy(phase = if (it.sendAttempted) NativeVoiceInputPhase.UNCONFIRMED else NativeVoiceInputPhase.FAILED, error = code(error)) }
                persistLater()
            } finally { NativeMediaViewModel.submissionActivity("voice:$id", false) }
        }
    }

    fun sendOriginal() {
        if (NativeCompanionWriteBarrier.frozen) return
        if (!_state.value.canRetry || _state.value.sendAttempted || processing?.isActive == true) return
        autoSend = false
        val epoch = actionEpoch
        processing = viewModelScope.launch {
            try { cancelRecognition(); sendCaptured(true, expectedEpoch = epoch) }
            catch (error: Exception) { if (error is CancellationException) throw error; _state.update { it.copy(phase = NativeVoiceInputPhase.FAILED, error = code(error)) }; persistLater() }
        }
    }
    fun sendRecognized() {
        if (NativeCompanionWriteBarrier.frozen) return
        if (!_state.value.canRetry || _state.value.sendAttempted || _state.value.transcript.isBlank() || processing?.isActive == true) return
        val epoch = actionEpoch
        processing = viewModelScope.launch {
            try { cancelRecognition(); sendCaptured(false, expectedEpoch = epoch) }
            catch (error: Exception) { if (error is CancellationException) throw error; report(code(error)); persistLater() }
        }
    }
    fun retrySend() {
        if (NativeCompanionWriteBarrier.frozen) return
        if (!_state.value.sendAttempted || _state.value.accepted || processing?.isActive == true) return
        val epoch = actionEpoch
        processing = viewModelScope.launch {
            try { sendCaptured(rawSubmission, expectedEpoch = epoch) }
            catch (error: Exception) { if (error is CancellationException) throw error; _state.update { it.copy(phase = NativeVoiceInputPhase.UNCONFIRMED, error = code(error)) }; persistLater() }
        }
    }
    private suspend fun ensureSource(id: String): NativeLocalAttachment {
        _state.value.source?.let { return it }
        val source = withContext(Dispatchers.IO) { NativeLocalAttachment.parse(NativeVoiceInputHost.admitWave(getApplication(), id)) }
        _state.update { it.copy(source = source) }; persist(); return source
    }
    private suspend fun sendCaptured(originalOnly: Boolean, automatic: Boolean = false, expectedEpoch: Long) {
        if (expectedEpoch != actionEpoch || automatic && !autoSend) return
        val snapshot = _state.value
        val id = snapshot.operationId ?: throw NativeApiException("native_voice_owner")
        val target = snapshot.target ?: throw NativeApiException("native_voice_owner")
        val source = ensureSource(id)
        if (snapshot.sendAttempted && rawSubmission != originalOnly) throw NativeApiException("native_voice_owner")
        rawSubmission = originalOnly
        _state.update { it.copy(phase = NativeVoiceInputPhase.SENDING, sendAttempted = true, error = null) }
        persist()
        if (expectedEpoch != actionEpoch || automatic && !autoSend) {
            _state.update { it.copy(phase = if (snapshot.sendAttempted) NativeVoiceInputPhase.UNCONFIRMED else NativeVoiceInputPhase.READY,
                sendAttempted = snapshot.sendAttempted) }; persist(); return
        }
        val text = if (originalOnly) "" else _state.value.transcript
        NativeMediaViewModel.submissionActivity("voice-send:$id", true)
        try {
            val sessionId = if (target.workbench != null) {
                val service = workbench ?: throw NativeApiException("runtime_unavailable")
                service.submitVoiceInput(target.workbench, id, text, listOf(source), snapshot.durationMs.coerceIn(1L, 60_000L))
            } else {
                val service = companions ?: throw NativeApiException("runtime_unavailable")
                val owner = target.companionId ?: throw NativeApiException("native_voice_owner")
                val upload = uploadedVoice ?: service.uploadAttachment(owner, media.bytes(source), "audio/wav", source.name).also { uploadedVoice = it; persist() }
                val voice = NativeCompanionVoice(upload.id, upload.url, _state.value.durationMs, text.takeIf(String::isNotBlank),
                    if (originalOnly) "failed" else "ready")
                service.submitExternalShare(owner, id, NativeCompanionDraft(text, voice = voice)); null
            }
            stopPreview()
            _state.update { it.copy(accepted = true, phase = NativeVoiceInputPhase.SENT, resultSessionId = sessionId, error = null) }
            persist()
            withContext(Dispatchers.IO) { NativeVoiceInputHost.discard(getApplication(), id) }
            gesture = null
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _state.update { it.copy(phase = if (it.accepted) NativeVoiceInputPhase.SENT else NativeVoiceInputPhase.UNCONFIRMED, error = code(error)) }
            persistLater(); throw error
        } finally { NativeMediaViewModel.submissionActivity("voice-send:$id", false) }
    }
    fun consumeSent() {
        if (NativeCompanionWriteBarrier.frozen) return
        if (!_state.value.accepted) return
        val id = _state.value.operationId
        viewModelScope.launch {
            processing?.join()
            try {
                val current = withContext(Dispatchers.IO) { NativeVoiceInputHost.snapshot(getApplication(), null, 0).optJSONObject("operation") }
                current?.let {
                    if (it.getString("id") != id) throw NativeApiException("native_voice_owner")
                    withContext(Dispatchers.IO) { NativeVoiceInputHost.discard(getApplication(), it.getString("id")) }
                }
                clearRecording()
            } catch (error: Exception) { if (error is CancellationException) throw error; report(code(error)) }
        }
    }

    private suspend fun readCapture(recovered: Boolean = false) {
        applyCapture(withContext(Dispatchers.IO) { NativeVoiceInputHost.snapshot(getApplication(), null, cursor) }, recovered)
    }
    private fun applyCapture(response: JSONObject, recovered: Boolean = false) {
        val permissions = NativeVoiceInputPermissions.parse(response.getJSONObject("permissions"))
        val value = response.optJSONObject("operation")
        if (value == null) {
            _state.update { it.copy(permissions = permissions) }; return
        }
        val id = value.getString("id"); val target = value.optJSONObject("target")?.let(NativeVoiceInputTarget::parse)
        if (_state.value.operationId != null && _state.value.operationId != id) throw NativeApiException("native_voice_owner")
        val nativeState = value.getString("state"); val quiet = value.getBoolean("quiescent")
        cursor = value.getLong("nextCursor")
        _state.update { previous ->
            val phase = when {
                previous.busy && previous.phase !in setOf(NativeVoiceInputPhase.STARTING, NativeVoiceInputPhase.STOPPING) -> previous.phase
                nativeState == "recording" && gesture?.down == true -> NativeVoiceInputPhase.RECORDING
                !quiet -> if (previous.phase == NativeVoiceInputPhase.STOPPING) previous.phase else NativeVoiceInputPhase.STARTING
                previous.accepted -> NativeVoiceInputPhase.SENT
                previous.sendAttempted -> NativeVoiceInputPhase.UNCONFIRMED
                recovered || previous.operationId == null || nativeState in setOf("interrupted", "armed", "failed") -> NativeVoiceInputPhase.INTERRUPTED
                else -> previous.phase
            }
            previous.copy(operationId = id, target = target, permissions = permissions, phase = phase, captureQuiescent = quiet,
                fileReady = value.getBoolean("fileReady"), durationMs = value.getLong("durationMs"), level = value.getDouble("level").toFloat())
        }
        if (quiet && nativeState != "armed" && gesture?.down == true) {
            if (mayAutoFinishVoiceCapture(nativeState, value.optString("reason"), _state.value.fileReady)) finishHold(false)
            else { gesture?.down = false; autoSend = false; _state.update { it.copy(phase = NativeVoiceInputPhase.INTERRUPTED, error = "native_voice_interrupted") }; persistLater() }
        }
    }
    private suspend fun clearRecording() {
        stopPreview()
        gesture = null; recognitionId = null; recognitionAttempted = false; autoSend = false; uploadedVoice = null; rawSubmission = false; cursor = 0
        _state.update { NativeVoiceInputState(visible = it.visible, target = it.target, permissions = it.permissions, catalog = it.catalog) }
        persist()
    }
    fun togglePreview() {
        if (NativeCompanionWriteBarrier.frozen) return
        if (_state.value.previewing) { stopPreview(); return }
        val source = _state.value.source ?: return
        val operationId = _state.value.operationId ?: return
        _state.update { it.copy(previewing = true) }
        previewJob = viewModelScope.launch {
            try {
                val bytes = media.bytes(source)
                if (_state.value.operationId != operationId || !_state.value.visible && !_state.value.showPendingBubble) { stopPreview(); return@launch }
                val player = MediaPlayer()
                previewPlayer = player
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                player.setDataSource(object : MediaDataSource() {
                    override fun getSize() = bytes.size.toLong()
                    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                        if (position < 0 || position >= bytes.size) return -1
                        val count = minOf(size, bytes.size - position.toInt())
                        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                        return count
                    }
                    override fun close() = Unit
                })
                player.setOnPreparedListener { ready ->
                    if (previewPlayer === ready) {
                        try { ready.start() }
                        catch (_: IllegalStateException) { stopPreview(); report("native_voice_playback") }
                    }
                }
                player.setOnCompletionListener { if (previewPlayer === it) stopPreview() }
                player.setOnErrorListener { failed, _, _ ->
                    if (previewPlayer === failed) { stopPreview(); report("native_voice_playback") }; true
                }
                player.prepareAsync()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                stopPreview(); report("native_voice_playback")
            }
        }
    }
    private fun stopPreview() {
        previewJob?.cancel(); previewJob = null
        previewPlayer?.release(); previewPlayer = null
        _state.update { it.copy(previewing = false) }
    }
    override fun onCleared() { stopPreview(); super.onCleared() }
    fun dismissError() { _state.update { it.copy(error = null) } }
    private fun report(code: String) { _state.update { it.copy(error = code) } }
    private fun code(error: Exception): String = when (error) {
        is NativeApiException -> error.code
        is NativeVoiceInputHost.Failure -> error.code
        else -> "native_voice_failed"
    }
    private fun restore(): NativeVoiceInputState = try {
        preferences.getString("state", null)?.let { raw ->
            val value = JSONObject(raw); revision = value.optLong("revision"); persistedRevision = revision
            if (value.isNull("operationId")) return@let NativeVoiceInputState()
            val id = value.getString("operationId"); require(UUID.fromString(id).toString() == id)
            recognitionId = value.optString("recognitionId").takeUnless { it == "null" || it.isEmpty() }
            recognitionAttempted = value.optBoolean("recognitionAttempted")
            rawSubmission = value.optBoolean("rawSubmission")
            uploadedVoice = value.optJSONObject("uploadedVoice")?.let { NativeCompanionAttachment(it.getString("id"), it.getString("name"), it.getString("mediaType"), it.getLong("bytes"), it.getString("url")) }
            val accepted = value.optBoolean("accepted"); val attempted = value.optBoolean("sendAttempted")
            NativeVoiceInputState(target = value.optJSONObject("target")?.let(NativeVoiceInputTarget::parse), operationId = id,
                phase = if (accepted) NativeVoiceInputPhase.SENT else if (attempted) NativeVoiceInputPhase.UNCONFIRMED else NativeVoiceInputPhase.INTERRUPTED,
                transcript = value.optString("transcript"), source = value.optJSONObject("source")?.let(NativeLocalAttachment::parse),
                sendAttempted = attempted, accepted = accepted, recognitionQuiescent = value.optBoolean("recognitionQuiescent", !recognitionAttempted), resultSessionId = value.optString("resultSessionId").takeUnless { it == "null" || it.isEmpty() },
                error = if (accepted) null else "native_voice_interrupted")
        } ?: NativeVoiceInputState()
    } catch (_: Exception) { restoreFailed = true; NativeVoiceInputState(error = "native_voice_storage") }
    private suspend fun persist() {
        if (restoreFailed) throw NativeApiException("native_voice_storage")
        val state = _state.value; val version = ++revision
        val value = JSONObject().put("revision", version).put("operationId", state.operationId ?: JSONObject.NULL)
            .put("target", state.target?.json() ?: JSONObject.NULL).put("transcript", state.transcript)
            .put("source", state.source?.json() ?: JSONObject.NULL).put("sendAttempted", state.sendAttempted).put("accepted", state.accepted)
            .put("resultSessionId", state.resultSessionId ?: JSONObject.NULL).put("recognitionId", recognitionId ?: JSONObject.NULL)
            .put("recognitionAttempted", recognitionAttempted).put("recognitionQuiescent", state.recognitionQuiescent).put("rawSubmission", rawSubmission)
            .put("uploadedVoice", uploadedVoice?.let { JSONObject().put("id", it.id).put("name", it.name).put("mediaType", it.mediaType).put("bytes", it.bytes).put("url", it.url) } ?: JSONObject.NULL).toString()
        persistence.withLock {
            if (version <= persistedRevision) return@withLock
            withContext(Dispatchers.IO) { if (!preferences.edit().putString("state", value).commit()) throw NativeApiException("native_voice_storage") }
            persistedRevision = version
        }
    }
    private fun persistLater() { viewModelScope.launch { try { persist() } catch (error: Exception) { if (error is CancellationException) throw error; report("native_voice_storage") } } }
}
