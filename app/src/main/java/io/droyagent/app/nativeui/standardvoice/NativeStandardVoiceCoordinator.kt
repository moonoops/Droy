package io.droyagent.app.nativeui.standardvoice

import io.droyagent.app.nativeui.data.NativeApiException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One app-scoped instance survives Activity recreation. The host owns lifecycle polling and event subscription. */
class NativeStandardVoiceCoordinator(
    private val gateway: NativeStandardVoiceGateway,
    private val store: NativeStandardVoiceStore,
    private val bindings: NativeStandardVoiceBindings,
    private val scope: CoroutineScope,
    private val nonce: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(NativeStandardVoiceState())
    val state = mutable.asStateFlow()
    @Volatile private var initialized = false
    private var cursor = 0L
    private var transcriptCursor = 0
    private var transcriptId: String? = null
    private var captureMayRotate = false
    private var permissionOpened: String? = null
    private val utterances = linkedMapOf<String, LinkedHashMap<String, String>>()
    private val turns = mutableMapOf<String, Turn>() // session + turn -> admitted nonce
    private val attempts = mutableMapOf<String, Attempt>()
    private val output = ArrayDeque<Speech>()
    private var outputJob: Job? = null
    private var outputEpoch = 0L
    @Volatile private var endingOperation: String? = null

    private class Turn(val rpcId: String) { var readout: Readout? = null }
    private data class Readout(val tone: NativeStandardVoiceTone, val aloud: Boolean, val replyId: String)
    private class Attempt(val session: String, val turn: String, val readout: Readout) {
        val events = mutableSetOf<String>()
        var raw = ""
        var pending = ""
        var interrupted = false
        var finished = false
    }
    private data class Speech(val voiceOperationId: String, val attemptId: String, val text: String, val session: String, val replyId: String,
                              val utteranceId: String, val tone: NativeStandardVoiceTone)

    internal suspend fun flushForCompanionDeletion() {
        outputJob?.join()
        mutex.withLock { initializeLocked(); state.value.journal?.let { store.save(it) } }
    }
    suspend fun initialize() = command { initializeLocked() }
    private suspend fun initializeLocked() {
        if (initialized) return
        val saved = store.load()
        initialized = true
        if (saved != null) {
            // No ASR cursor, partial text, reply event or TTS queue crosses a process boundary.
            mutable.value = NativeStandardVoiceState(phase = NativeStandardVoicePhase.RECOVERED,
                journal = saved.copy(submissions = saved.submissions.map {
                    if (it.delivery == NativeStandardVoiceDelivery.SUBMITTING) it.copy(delivery = NativeStandardVoiceDelivery.UNKNOWN) else it
                }), userMuted = true, recovered = true)
        }
    }

    suspend fun start() = command {
        initializeLocked()
        requireNewOperation()
        val facts = bindings.hostFacts(); requireScreen(facts)
        val target = bindings.captureTarget(fresh = false); validateTarget(target)
        startLocked(newJournal(target, facts), facts)
    }

    /** The event-to-target mapping is durable before the only consume attempt. */
    suspend fun startFromWake(eventId: Long) = command {
        initializeLocked()
        requireNewOperation()
        if (eventId <= 0) invalidVoice()
        val facts = bindings.hostFacts(); requireScreen(facts)
        val config = gateway.config()
        mutable.value = state.value.copy(config = config)
        if (!config.enabled) fail("native_standard_voice_disabled")
        currentOrNull()?.let {
            if (it.runtimeIdentity == facts.runtimeIdentity && it.wake?.eventId == eventId) fail("native_standard_voice_wake_replayed")
        }
        val wake = gateway.wakeStatus()
        if (!validWake(wake, facts) || wake.eventId != eventId || wake.voiceClaimed) fail("native_standard_voice_wake_expired")
        if (!gateway.status().stopped) fail("native_standard_voice_owner")
        val target = bindings.captureTarget(fresh = true); validateTarget(target)
        journal(newJournal(target, facts).copy(wake = NativeStandardVoiceWake(eventId, NativeStandardVoiceWakePhase.CONSUMING)))
        val consumed = try {
            gateway.consumeWake(current().operationId, eventId)
        } catch (failure: Exception) {
            journal(current().copy(wake = NativeStandardVoiceWake(eventId, NativeStandardVoiceWakePhase.UNKNOWN)))
            mutable.value = state.value.copy(phase = NativeStandardVoicePhase.UNCONFIRMED)
            throw failure
        }
        if (!consumed) {
            journal(current().copy(wake = NativeStandardVoiceWake(eventId, NativeStandardVoiceWakePhase.REJECTED)))
            mutable.value = state.value.copy(phase = NativeStandardVoicePhase.ENDED)
            fail("native_standard_voice_wake_expired")
        }
        journal(current().copy(wake = NativeStandardVoiceWake(eventId, NativeStandardVoiceWakePhase.CONSUMED)))
        startLocked(current(), facts)
    }

    /** Explicit continuation after consume reconciliation; reuses the one captured wake draft. */
    suspend fun resumeWake() = command {
        initializeLocked()
        val value = current()
        if (value.startAttempted || value.stopRequested || value.wake?.phase != NativeStandardVoiceWakePhase.CONSUMED)
            fail("native_standard_voice_wake_unconfirmed")
        val facts = bindings.hostFacts(); requireScreen(facts)
        if (facts.runtimeIdentity != value.runtimeIdentity) fail("native_standard_voice_runtime_changed")
        startLocked(value, facts)
    }

    private suspend fun startLocked(value: NativeStandardVoiceJournal, facts: NativeStandardVoiceHostFacts) {
        val config = gateway.config()
        mutable.value = state.value.copy(config = config)
        if (!config.enabled) fail("native_standard_voice_disabled")
        val before = gateway.status()
        mutable.value = state.value.copy(native = before, config = config)
        // Even the same session can belong to another native client or an unacknowledged start.
        if (!before.stopped || gateway.wakeStatus().voiceClaimed) {
            mutable.value = state.value.copy(phase = NativeStandardVoicePhase.FOREIGN)
            fail("native_standard_voice_owner")
        }
        val latestFacts = bindings.hostFacts(); requireScreen(latestFacts)
        if (latestFacts.runtimeIdentity != facts.runtimeIdentity) fail("native_standard_voice_runtime_changed")
        validateTarget(value.target)
        value.wake?.let {
            val wake = gateway.wakeStatus()
            if (it.phase != NativeStandardVoiceWakePhase.CONSUMED || !validWake(wake, latestFacts) || !wakeOwned(wake, value, it.eventId))
                fail("native_standard_voice_wake_expired")
        }
        journal(value.copy(startAttempted = true))
        resetTransient()
        mutable.value = state.value.copy(phase = NativeStandardVoicePhase.STARTING, recovered = false,
            userMuted = false, automaticMuted = false, readAloud = false, error = null)
        try {
            // A blank draft's UUID is a valid native capture identity; it does not create a DSH session.
            if (endingOperation == value.operationId) fail("native_standard_voice_stop_unconfirmed")
            val started = gateway.start(value.operationId, value.target.captureSessionId, value.wake?.eventId)
            if (started.voiceOperationId != value.operationId) fail("VOICE_OWNER_MISMATCH")
            if (!started.active || started.sessionId != value.target.captureSessionId || started.captureId == null)
                fail("native_standard_voice_start_unconfirmed")
            journal(current().copy(captureId = started.captureId))
            applyOwned(started)
            permissionIfNeeded(latestFacts)
        } catch (failure: Exception) {
            mutable.value = state.value.copy(phase = if (code(failure) == "VOICE_OWNER_MISMATCH")
                NativeStandardVoicePhase.FOREIGN else NativeStandardVoicePhase.UNCONFIRMED)
            throw failure
        }
    }

    /** Reads current facts and submission receipts only. Never restarts capture or resends a prompt. */
    suspend fun reconcile() = command {
        initializeLocked()
        val facts = bindings.hostFacts()
        val config = gateway.config()
        mutable.value = state.value.copy(config = config,
            error = state.value.error.takeUnless { config.enabled && it in setOf("native_standard_voice_disabled", "ENTRY_DISABLED") })
        val native = gateway.status()
        mutable.value = state.value.copy(native = native)
        val value = state.value.journal ?: run {
            mutable.value = state.value.copy(phase = if (native.stopped) NativeStandardVoicePhase.IDLE else NativeStandardVoicePhase.FOREIGN)
            return@command
        }
        recoverCaptureProof(native, facts)
        for (row in value.submissions.filter { it.delivery in setOf(NativeStandardVoiceDelivery.SUBMITTING, NativeStandardVoiceDelivery.UNKNOWN) }) {
            when (val receipt = bindings.submissionStatus(value.target, row.nonce)) {
                is NativeStandardVoiceSubmissionStatus.Accepted -> accepted(row.nonce, receipt.receipt)
                NativeStandardVoiceSubmissionStatus.NotAccepted -> replace(row.nonce) { it.copy(delivery = NativeStandardVoiceDelivery.NOT_ACCEPTED) }
                NativeStandardVoiceSubmissionStatus.Unknown -> replace(row.nonce) { it.copy(delivery = NativeStandardVoiceDelivery.UNKNOWN) }
            }
        }
        current().wake?.takeIf { it.phase in setOf(NativeStandardVoiceWakePhase.CONSUMING, NativeStandardVoiceWakePhase.UNKNOWN) }?.let { pending ->
            val wake = gateway.wakeStatus()
            if (facts.runtimeIdentity == current().runtimeIdentity && wakeOwned(wake, current(), pending.eventId) && validWake(wake, facts))
                journal(current().copy(wake = pending.copy(phase = NativeStandardVoiceWakePhase.CONSUMED)))
            // Pending event or missing event cannot prove a lost consume was rejected. Do not consume again.
        }
        when {
            native.stopped -> mutable.value = state.value.copy(phase = if (state.value.pending.isEmpty() && !unresolvedWake())
                NativeStandardVoicePhase.ENDED else NativeStandardVoicePhase.RECOVERED)
            !owned(native, facts) -> {
                mutable.value = state.value.copy(phase = if (current().captureId == null && current().startAttempted && native.voiceOperationId == current().operationId)
                    NativeStandardVoicePhase.UNCONFIRMED else NativeStandardVoicePhase.FOREIGN)
                fail("native_standard_voice_owner")
            }
            state.value.recovered -> {
                if (!native.muted) captureMuted(true)
                if (native.speaking) interruptOutputLocked()
                mutable.value = state.value.copy(phase = NativeStandardVoicePhase.RECOVERED)
            }
            current().stopRequested -> mutable.value = state.value.copy(phase = NativeStandardVoicePhase.STOPPING)
            else -> { applyOwned(native); if (!facts.screenAvailable) pauseForReview("native_standard_voice_screen_unavailable") }
        }
    }

    suspend fun requestPermission() = command {
        initializeLocked(); val facts = bindings.hostFacts(); val native = gateway.status(); requireOwned(native, facts)
        applyOwned(native)
        permissionIfNeeded(facts, explicit = true)
    }

    suspend fun pollOnce() = command {
        initializeLocked()
        if (state.value.recovered || currentOrNull() == null || current().stopRequested || endingOperation == current().operationId) return@command
        val facts = bindings.hostFacts()
        val before = gateway.status()
        if (before.stopped) { applyOwned(before); return@command }
        requireOwned(before, facts)
        if (!facts.screenAvailable) { pauseForReview("native_standard_voice_screen_unavailable"); interruptOutputLocked(); return@command }
        val result = gateway.poll(current().operationId, cursor, transcriptCursor, before.captureId, transcriptId)
        if (endingOperation == current().operationId) return@command
        requireOwned(result.native, facts, allowRotation = captureMayRotate)
        if (captureMayRotate && result.native.captureId != current().captureId) {
            journal(current().copy(captureId = result.native.captureId)); captureMayRotate = false
        }
        if (transcriptId != result.native.transcriptId) { utterances.clear(); transcriptId = result.native.transcriptId }
        cursor = result.nextCursor; transcriptCursor = result.nextTranscriptCursor
        applyOwned(result.native)
        if (!state.value.userMuted && !state.value.automaticMuted && !result.native.speaking && admitFinals(result)) {
            // Preserve the old controller's utterance order. Earlier finals may already be unknown;
            // the exit itself never reaches DSH, and ending voice never cancels a DSH task.
            try { submitReady() } finally { stopLocked() }
            return@command
        }
        if (result.native.failed) { pauseForReview("native_standard_voice_interrupted"); return@command }
        if (!result.native.active) {
            if (state.value.pending.isNotEmpty()) pauseForReview("native_standard_voice_interrupted")
            return@command
        }
        if (result.native.speaking && !state.value.automaticMuted) {
            mutable.value = state.value.copy(automaticMuted = true)
            captureMuted(true)
        }
        if (state.value.automaticMuted && output.isEmpty() && outputJob?.isActive != true && !result.native.speaking && !state.value.outputUnconfirmed) {
            mutable.value = state.value.copy(automaticMuted = false)
            captureMuted(state.value.userMuted)
        }
        if (state.value.userMuted || state.value.automaticMuted) return@command
        submitReady()
    }

    private suspend fun admitFinals(result: NativeStandardVoicePoll): Boolean {
        val transcript = result.native.transcriptId
        if (result.segments.isNotEmpty() && transcript == null) invalidVoice()
        for (segment in result.segments) {
            val key = "$transcript:${segment.utteranceId}"
            if (current().submissions.any { it.utteranceKey == key }) continue
            val pieces = utterances.getOrPut(key) { linkedMapOf() }
            pieces[segment.id] = segment.text
            val text = cleanTranscript(pieces.values.joinToString(""))
            if (text.length > 32768 || pieces.size > 256 || utterances.size > 256) fail("native_standard_voice_transcript_limit")
            mutable.value = state.value.copy(partialTranscript = text)
            if (!segment.complete) continue
            utterances.remove(key)
            if (text.isBlank()) continue
            if (isStandardVoiceExitPhrase(text)) {
                mutable.value = state.value.copy(partialTranscript = "")
                return true
            }
            if (current().submissions.size >= 256) { pauseForReview("native_standard_voice_transcript_limit"); return false }
            val id = nonce(); voiceIdentity(id)
            try {
                journal(current().copy(submissions = current().submissions + NativeStandardVoiceSubmission(key, id, text, NativeStandardVoiceDelivery.READY)))
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                pauseForReview(code(failure)); return false
            }
            mutable.value = state.value.copy(partialTranscript = "")
        }
        return false
    }

    private suspend fun submitReady() {
        while (!state.value.userMuted && !state.value.recovered && endingOperation != current().operationId) {
            val row = state.value.pending.firstOrNull() ?: return
            if (row.delivery != NativeStandardVoiceDelivery.READY) return
            val check = bindings.validateTarget(current().target)
            if (!check.writableAndOwned || !check.draftUnchanged) { pauseForReview("native_standard_voice_draft_changed"); return }
            dispatch(row)
        }
    }
    private suspend fun dispatch(row: NativeStandardVoiceSubmission) {
        replace(row.nonce) { it.copy(delivery = NativeStandardVoiceDelivery.SUBMITTING) }
        try {
            accepted(row.nonce, bindings.submitText(current().target, row.nonce, row.text))
        } catch (failure: Exception) {
            // A thrown call may have dispatched. The frozen text/nonce stays immutable for receipt reconciliation.
            replace(row.nonce) { it.copy(delivery = NativeStandardVoiceDelivery.UNKNOWN) }
            if (failure is CancellationException) throw failure
            pauseForReview(code(failure))
        }
    }

    /** Explicit retry only after positive NotAccepted evidence. Unknown requests cannot be edited or replayed. */
    suspend fun retry(nonce: String) = command {
        initializeLocked()
        val row = current().submissions.singleOrNull { it.nonce == nonce } ?: fail("native_standard_voice_submission_missing")
        if (row.delivery != NativeStandardVoiceDelivery.NOT_ACCEPTED && row.delivery != NativeStandardVoiceDelivery.READY)
            fail("native_standard_voice_submission_unconfirmed")
        validateTarget(current().target)
        dispatch(row)
    }

    /** Returns preserved final text for the host's explicit Review/Insert action; does not touch the composer. */
    fun retainedText(nonce: String): String? = state.value.pending.firstOrNull { it.nonce == nonce }?.text

    suspend fun discard(nonce: String) = command {
        initializeLocked()
        val row = current().submissions.singleOrNull { it.nonce == nonce } ?: fail("native_standard_voice_submission_missing")
        if (row.delivery in setOf(NativeStandardVoiceDelivery.UNKNOWN, NativeStandardVoiceDelivery.SUBMITTING))
            fail("native_standard_voice_submission_unconfirmed")
        if (row.delivery != NativeStandardVoiceDelivery.ACCEPTED) replace(nonce) { it.copy(delivery = NativeStandardVoiceDelivery.DISCARDED) }
    }

    suspend fun setUserMuted(muted: Boolean) = command {
        initializeLocked()
        if (!muted && (state.value.recovered || state.value.pending.isNotEmpty() || state.value.outputUnconfirmed))
            fail("native_standard_voice_review_required")
        val facts = bindings.hostFacts(); requireOwned(gateway.status(), facts)
        if (!muted) { requireScreen(facts); validateTarget(current().target) }
        mutable.value = state.value.copy(userMuted = muted)
        captureMuted(muted || state.value.automaticMuted)
    }

    suspend fun setReadAloud(enabled: Boolean) = command {
        initializeLocked()
        mutable.value = state.value.copy(readAloud = enabled)
        if (!enabled) interruptOutputLocked()
    }

    suspend fun onReplyEvent(event: NativeStandardVoiceReplyEvent) = command {
        if (!initialized || state.value.recovered || !event.live || currentOrNull() == null || current().stopRequested || endingOperation == current().operationId) return@command
        voiceIdentity(event.sessionId); voiceIdentity(event.turnId)
        val key = "${event.sessionId}:${event.turnId}"
        when (event) {
            is NativeStandardVoiceReplyEvent.Bound -> {
                val row = current().submissions.firstOrNull { it.nonce == event.rpcId && it.delivery == NativeStandardVoiceDelivery.ACCEPTED } ?: return@command
                if (row.receipt?.sessionId != event.sessionId) return@command
                if (turns.size >= 256 && key !in turns) fail("native_standard_voice_reply_limit")
                if (turns[key]?.rpcId?.let { it != event.rpcId } == true) fail("native_standard_voice_receipt_mismatch")
                turns.getOrPut(key) { Turn(event.rpcId) }
            }
            is NativeStandardVoiceReplyEvent.Text -> {
                val turn = turns[key] ?: return@command
                voiceIdentity(event.attemptId); voiceIdentity(event.eventId)
                val attemptKey = "$key:${event.attemptId}"
                val attempt = attempts.getOrPut(attemptKey) {
                    if (attempts.size >= 256) fail("native_standard_voice_reply_limit")
                    val readout = turn.readout ?: Readout(state.value.config?.tone ?: fail("native_standard_voice_config_missing"),
                        state.value.readAloud, nonce().also(::voiceIdentity)).also { turn.readout = it }
                    Attempt(event.sessionId, event.turnId, readout)
                }
                if (attempt.interrupted || attempt.finished || !attempt.events.add(event.eventId)) return@command
                if (attempt.events.size > 20000 || event.text.length + attempt.raw.length > 262144) fail("native_standard_voice_reply_limit")
                val suffix = if (event.final) {
                    if (!event.text.startsWith(attempt.raw)) { attempt.interrupted = true; interruptOutputLocked(); fail("native_standard_voice_reply_changed") }
                    event.text.drop(attempt.raw.length)
                } else event.text
                attempt.raw += suffix; attempt.pending += suffix
                mutable.value = state.value.copy(reply = attempt.raw)
                flush(attemptKey, attempt, event.final)
                if (event.final) attempt.finished = true
            }
            is NativeStandardVoiceReplyEvent.Interrupted -> {
                val attempt = attempts["$key:${event.attemptId}"] ?: return@command
                attempt.interrupted = true; attempt.pending = ""
                interruptOutputLocked()
            }
        }
    }

    private fun flush(id: String, attempt: Attempt, final: Boolean) {
        var cut = if (final) attempt.pending.length else Regex("[。！？!?；;\n][”’\"']?").findAll(attempt.pending).lastOrNull()?.range?.last?.plus(1) ?: 0
        if (cut == 0 && attempt.pending.length > 220) cut = 220
        if (cut == 0) return
        val text = cleanSpeech(attempt.pending.take(cut)); attempt.pending = attempt.pending.drop(cut)
        if (!attempt.readout.aloud || !state.value.readAloud || text.isBlank()) return
        text.chunked(220).forEach { part ->
            if (output.size >= 256) fail("native_standard_voice_output_limit")
            output.addLast(Speech(current().operationId, id, part, attempt.session, attempt.readout.replyId, nonce(), attempt.readout.tone))
        }
        mutable.value = state.value.copy(outputPending = output.size)
        pumpOutput()
    }

    private fun pumpOutput() {
        if (outputJob?.isActive == true || output.isEmpty() || state.value.outputUnconfirmed) return
        val epoch = outputEpoch
        outputJob = scope.launch {
            while (true) {
                val speech = mutex.withLock {
                    if (epoch != outputEpoch || state.value.recovered || currentOrNull()?.stopRequested != false || endingOperation == current().operationId) return@launch
                    val next = output.removeFirstOrNull() ?: return@launch
                    if (attempts[next.attemptId]?.interrupted != false) return@withLock null
                    try {
                        val facts = bindings.hostFacts(); requireScreen(facts); requireOwned(gateway.status(), facts)
                        mutable.value = state.value.copy(automaticMuted = true)
                        captureMuted(true)
                        mutable.value = state.value.copy(outputPending = output.size + 1)
                        next
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        output.clear(); mutable.value = state.value.copy(outputPending = 0, error = code(failure)); null
                    }
                } ?: continue
                try {
                    gateway.speak(speech.voiceOperationId, speech.text, speech.session, speech.replyId, speech.utteranceId, speech.tone)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    mutex.withLock {
                        if (epoch == outputEpoch) {
                            output.clear()
                            if (code(failure) == "VOICE_OWNER_MISMATCH") ownerLost()
                            mutable.value = state.value.copy(outputUnconfirmed = true, userMuted = true, error = code(failure))
                        }
                    }
                } finally {
                    mutex.withLock { if (epoch == outputEpoch) mutable.value = state.value.copy(outputPending = output.size) }
                }
            }
        }
    }

    suspend fun interruptOutput() = command { initializeLocked(); interruptOutputLocked() }
    private suspend fun interruptOutputLocked() {
        val possibleOutput = state.value.outputUnconfirmed || state.value.automaticMuted || state.value.outputPending > 0 || outputJob?.isActive == true || state.value.native?.speaking == true
        outputEpoch++; outputJob?.cancel(); outputJob = null; output.clear()
        attempts.values.forEach { it.interrupted = true; it.pending = "" }
        mutable.value = state.value.copy(outputPending = 0, outputUnconfirmed = possibleOutput)
        val value = currentOrNull() ?: return
        val facts = bindings.hostFacts()
        val before = gateway.status()
        if (!owned(before, facts) && !before.stopped) fail("native_standard_voice_owner")
        if (before.stopped && !state.value.outputUnconfirmed) return
        if (facts.runtimeIdentity != value.runtimeIdentity) fail("native_standard_voice_owner")
        if (before.voiceOperationId != value.operationId) fail("VOICE_OWNER_MISMATCH")
        if (!possibleOutput && !before.speaking) return
        mutable.value = state.value.copy(outputUnconfirmed = true)
        var failure: Exception? = null
        try { gateway.stopSpeaking(value.operationId) } catch (error: Exception) { if (error is CancellationException) throw error; failure = error }
        val after = gateway.status()
        if (after.voiceOperationId != value.operationId) {
            mutable.value = state.value.copy(native = after, outputUnconfirmed = true, phase = NativeStandardVoicePhase.FOREIGN)
            fail("VOICE_OWNER_MISMATCH")
        }
        mutable.value = state.value.copy(native = after, outputUnconfirmed = failure != null || after.speaking)
        if (failure != null) throw failure
        if (after.speaking) fail("native_standard_voice_output_unconfirmed")
        if (state.value.automaticMuted && owned(after, facts)) {
            mutable.value = state.value.copy(automaticMuted = false)
            captureMuted(state.value.userMuted)
        }
    }

    private data class StopAttempt(val operationId: String, val failure: Exception?)

    /** Canonical owner fencing may stop an in-flight start/poll before its serialized command returns. */
    suspend fun stop() {
        if (!initialized) initialize()
        val value = state.value.journal ?: return
        endingOperation = value.operationId
        val attempt = if (value.startAttempted) {
            var failure: Exception? = null
            try {
                if (bindings.hostFacts().runtimeIdentity == value.runtimeIdentity) gateway.stop(value.operationId)
                else fail("native_standard_voice_runtime_changed")
            } catch (error: Exception) { if (error is CancellationException) throw error; failure = error }
            StopAttempt(value.operationId, failure)
        } else null
        command {
            if (currentOrNull()?.operationId != value.operationId) return@command
            stopLocked(attempt)
        }
    }

    private suspend fun stopLocked(attempt: StopAttempt? = null) {
        val value = currentOrNull() ?: return
        val possibleOutput = state.value.outputUnconfirmed || state.value.automaticMuted || state.value.outputPending > 0 || outputJob?.isActive == true || state.value.native?.speaking == true
        outputEpoch++; outputJob?.cancel(); outputJob = null; output.clear(); utterances.clear()
        mutable.value = state.value.copy(phase = NativeStandardVoicePhase.STOPPING, outputPending = 0, partialTranscript = "",
            readAloud = false, outputUnconfirmed = possibleOutput)
        journal(value.copy(stopRequested = true))
        val facts: NativeStandardVoiceHostFacts
        val before: NativeStandardVoiceNativeState
        try { facts = bindings.hostFacts(); before = gateway.status() }
        catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            mutable.value = state.value.copy(phase = NativeStandardVoicePhase.UNCONFIRMED, error = code(failure))
            return
        }
        recoverCaptureProof(before, facts)
        if (!before.stopped && !owned(before, facts)) {
            mutable.value = state.value.copy(phase = if (value.startAttempted && value.captureId == null && before.voiceOperationId == value.operationId)
                NativeStandardVoicePhase.UNCONFIRMED else NativeStandardVoicePhase.FOREIGN)
            throw attempt?.failure ?: NativeApiException("native_standard_voice_owner")
        }
        var failure: Exception? = attempt?.failure
        if (attempt == null && (!before.stopped || state.value.outputUnconfirmed || value.startAttempted)) {
            if (facts.runtimeIdentity != value.runtimeIdentity || before.voiceOperationId != value.operationId || before.sessionId != value.target.captureSessionId)
                if (!before.stopped || !value.startAttempted || facts.runtimeIdentity != value.runtimeIdentity) fail("native_standard_voice_owner")
            try { gateway.stop(value.operationId) } catch (error: Exception) { if (error is CancellationException) throw error; failure = error }
        }
        if (!value.startAttempted && value.wake != null && facts.runtimeIdentity == value.runtimeIdentity) {
            val wake = gateway.wakeStatus()
            if (wakeOwned(wake, value, value.wake.eventId) && !wake.voiceClaimed) {
                gateway.releaseWake(value.operationId)
                val released = gateway.wakeStatus()
                if (released.startingEventId == value.wake.eventId && released.startingVoiceOperationId == value.operationId)
                    fail("native_standard_voice_wake_unconfirmed")
            }
        }
        // HTTP stop can fail after native stop succeeded. Its response is never the final status proof.
        val after = try { gateway.status() } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutable.value = state.value.copy(phase = NativeStandardVoicePhase.UNCONFIRMED, error = code(failure ?: error))
            return
        }
        val confirmed = after.stopped && (!value.startAttempted || after.voiceOperationId == value.operationId)
        mutable.value = state.value.copy(native = after, automaticMuted = false, outputUnconfirmed = after.speaking,
            phase = if (confirmed) NativeStandardVoicePhase.ENDED else if (after.voiceOperationId != value.operationId)
                NativeStandardVoicePhase.FOREIGN else NativeStandardVoicePhase.STOPPING,
            error = failure?.let(::code) ?: state.value.error.takeIf { state.value.pending.isNotEmpty() })
        if (!confirmed) fail(if (after.voiceOperationId != value.operationId) "VOICE_OWNER_MISMATCH" else "native_standard_voice_stop_unconfirmed")
    }

    private suspend fun captureMuted(muted: Boolean) {
        val facts = bindings.hostFacts(); val before = gateway.status(); requireOwned(before, facts)
        if (before.muted == muted) { applyOwned(before); return }
        if (!muted) captureMayRotate = true
        val after = gateway.mute(current().operationId, muted)
        requireOwned(after, facts, allowRotation = !muted)
        if (after.captureId != current().captureId) { journal(current().copy(captureId = after.captureId)); captureMayRotate = false }
        applyOwned(after)
        cursor = 0; transcriptCursor = 0; transcriptId = null; utterances.clear()
        mutable.value = state.value.copy(partialTranscript = "")
    }
    private suspend fun pauseForReview(error: String) {
        mutable.value = state.value.copy(userMuted = true, phase = NativeStandardVoicePhase.REVIEW, error = error)
        try {
            val facts = bindings.hostFacts(); val native = gateway.status()
            if (native.stopped) { mutable.value = state.value.copy(native = native); return }
            requireOwned(native, facts)
            captureMuted(true)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            mutable.value = state.value.copy(error = code(failure), phase = NativeStandardVoicePhase.UNCONFIRMED)
        }
    }
    private suspend fun permissionIfNeeded(facts: NativeStandardVoiceHostFacts, explicit: Boolean = false) {
        val native = state.value.native ?: return
        if (native.state != "awaiting_user" || native.needsPermission != "microphone") return
        if (!facts.foreground || !facts.screenAvailable) return
        val id = native.captureId ?: fail("native_standard_voice_owner")
        if (permissionOpened != id || explicit) { permissionOpened = id; bindings.openAudioPermission(id) }
    }
    private fun applyOwned(native: NativeStandardVoiceNativeState) {
        mutable.value = state.value.copy(native = native, phase = when {
            !native.active -> if (native.stopped) NativeStandardVoicePhase.ENDED else NativeStandardVoicePhase.UNCONFIRMED
            state.value.userMuted && state.value.pending.isNotEmpty() -> NativeStandardVoicePhase.REVIEW
            native.state == "awaiting_user" -> NativeStandardVoicePhase.AWAITING_PERMISSION
            native.state == "starting" -> NativeStandardVoicePhase.STARTING
            else -> NativeStandardVoicePhase.ACTIVE
        })
    }
    private fun owned(native: NativeStandardVoiceNativeState, facts: NativeStandardVoiceHostFacts): Boolean {
        val value = currentOrNull() ?: return false
        return facts.runtimeIdentity == value.runtimeIdentity && native.voiceOperationId == value.operationId && value.captureId != null && native.captureId == value.captureId &&
            native.sessionId == value.target.captureSessionId
    }
    private fun requireOwned(native: NativeStandardVoiceNativeState, facts: NativeStandardVoiceHostFacts, allowRotation: Boolean = false) {
        val value = currentOrNull()
        val rotating = allowRotation && value?.captureId != null && native.captureId != null &&
            native.voiceOperationId == value.operationId && native.sessionId == value.target.captureSessionId && facts.runtimeIdentity == value.runtimeIdentity
        if (!owned(native, facts) && !rotating) fail("native_standard_voice_owner")
    }
    private suspend fun validateTarget(target: NativeStandardVoiceTarget) {
        voiceIdentity(target.draftId); target.sessionId?.let(::voiceIdentity); target.workspaceId?.let(::voiceIdentity)
        val check = bindings.validateTarget(target)
        if (!check.writableAndOwned || !check.draftUnchanged) fail("native_standard_voice_draft_changed")
    }
    private suspend fun recoverCaptureProof(native: NativeStandardVoiceNativeState, facts: NativeStandardVoiceHostFacts) {
        val value = currentOrNull() ?: return
        if (value.startAttempted && value.captureId == null && native.active && native.captureId != null &&
            facts.runtimeIdentity == value.runtimeIdentity && native.voiceOperationId == value.operationId && native.sessionId == value.target.captureSessionId) {
            journal(value.copy(captureId = native.captureId))
            mutable.value = state.value.copy(recovered = true, userMuted = true, phase = NativeStandardVoicePhase.RECOVERED)
        }
    }
    private fun wakeOwned(wake: NativeStandardVoiceWakeState, value: NativeStandardVoiceJournal, eventId: Long) =
        wake.startingEventId == eventId && wake.startingVoiceOperationId == value.operationId &&
            (!wake.voiceClaimed || wake.voiceOperationId == value.operationId)
    private fun requireScreen(facts: NativeStandardVoiceHostFacts) {
        voiceIdentity(facts.runtimeIdentity)
        if (!facts.foreground || !facts.screenAvailable) fail("native_standard_voice_screen_unavailable")
    }
    private fun validWake(wake: NativeStandardVoiceWakeState, facts: NativeStandardVoiceHostFacts) =
        wake.enabled && !wake.suspended && wake.screenAvailable && facts.screenAvailable && (wake.timing != "foreground" || facts.foreground)
    private fun newJournal(target: NativeStandardVoiceTarget, facts: NativeStandardVoiceHostFacts) =
        NativeStandardVoiceJournal(nonce().also(::voiceOperationIdentity), facts.runtimeIdentity, target)
    private fun requireNewOperation() {
        if (currentOrNull() != null && (state.value.phase != NativeStandardVoicePhase.ENDED || state.value.pending.isNotEmpty() || unresolvedWake()))
            fail("native_standard_voice_review_required")
    }
    private fun unresolvedWake(): Boolean = currentOrNull()?.let {
        !it.stopRequested && !it.startAttempted && it.wake != null && it.wake.phase != NativeStandardVoiceWakePhase.REJECTED
    } == true
    private fun resetTransient() {
        cursor = 0; transcriptCursor = 0; transcriptId = null; captureMayRotate = false; permissionOpened = null
        utterances.clear(); turns.clear(); attempts.clear(); output.clear(); outputEpoch++; outputJob?.cancel(); outputJob = null
        mutable.value = state.value.copy(partialTranscript = "", reply = "", outputPending = 0, outputUnconfirmed = false)
    }
    private fun currentOrNull() = state.value.journal
    private fun current() = currentOrNull() ?: fail("native_standard_voice_owner")
    private suspend fun journal(value: NativeStandardVoiceJournal) {
        // A failed durable write must still leave the final text visible in this process, but aborts dispatch.
        mutable.value = state.value.copy(journal = value)
        store.save(value)
    }
    private suspend fun replace(nonce: String, change: (NativeStandardVoiceSubmission) -> NativeStandardVoiceSubmission) =
        journal(current().copy(submissions = current().submissions.map { if (it.nonce == nonce) change(it) else it }))
    private suspend fun accepted(nonce: String, receipt: NativeStandardVoiceReceipt) {
        voiceIdentity(receipt.sessionId)
        if (receipt.rpcId != nonce) fail("native_standard_voice_receipt_mismatch")
        replace(nonce) { it.copy(delivery = NativeStandardVoiceDelivery.ACCEPTED, receipt = receipt) }
    }
    private suspend fun command(block: suspend () -> Unit) = mutex.withLock {
        try { block() } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (code(failure) == "VOICE_OWNER_MISMATCH") ownerLost()
            mutable.value = state.value.copy(error = code(failure))
        }
    }
    private fun ownerLost() {
        outputEpoch++; outputJob?.cancel(); outputJob = null; output.clear(); utterances.clear()
        attempts.values.forEach { it.interrupted = true; it.pending = "" }
        mutable.value = state.value.copy(phase = NativeStandardVoicePhase.FOREIGN, userMuted = true,
            outputPending = 0, partialTranscript = "")
    }
    private fun code(failure: Exception) = (failure as? NativeApiException)?.code ?: "native_standard_voice_failed"
    private fun fail(code: String): Nothing = throw NativeApiException(code)
}

internal fun cleanTranscript(text: String): String = text.replace(Regex("<\\|[^|]*\\|>"), "").trim()
    .takeIf { value -> value.any(Char::isLetterOrDigit) }.orEmpty()
internal fun isStandardVoiceExitPhrase(text: String): Boolean {
    val phrase = cleanTranscript(text).replace(Regex("[\\p{P}\\p{Z}\\s]"), "").lowercase(java.util.Locale.ROOT)
    return Regex("^(?:请)?(?:没事了|没事儿了|可以了|闭嘴|停止对话|结束对话|退出语音对话|停止语音对话|结束语音对话|退出语音|结束语音)(?:谢谢|谢谢你)?$").matches(phrase) ||
        Regex("^(?:please)?(?:nevermind|thatsall|thatisall|thatsenough|thatisenough|bequiet|stoptalking|stoptheconversation|endtheconversation|exitvoicechat|stopvoicechat|endvoicechat|exitvoice|endvoice)(?:thanks|thankyou)?$").matches(phrase)
}
internal fun cleanSpeech(text: String): String = text.replace(Regex("```[\\s\\S]*?(?:```|$)"), " ")
    .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "").replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
    .replace(Regex("https?://\\S+"), "").replace(Regex("(?m)^[\\s#>*-]+"), "")
    .replace(Regex("[`*_~]"), "").replace(Regex("\\s+"), " ").trim()
