package io.droyagent.app.nativeui.companion.data

import io.droyagent.app.nativeui.companion.maintenance.NativeCompanionWriteBarrier
import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.droyagent.app.NativeRuntimeHost
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeApiClient
import io.droyagent.app.nativeui.data.NativeModel
import io.droyagent.app.nativeui.data.NativeRuntimeState
import io.droyagent.app.nativeui.data.NativeTransportEvent
import io.droyagent.app.nativeui.media.NativeDraftMediaStore
import io.droyagent.app.nativeui.media.NativeLocalAttachment
import io.droyagent.app.nativeui.media.NativeMediaViewModel
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Companion messages remain owned by the existing product service and SQLite store. */
class NativeCompanionViewModel(application: Application) : AndroidViewModel(application) {
    private val api = NativeCompanionClient(application.assets)
    private val preferences = application.getSharedPreferences("native_companions", 0)
    private val localMedia = NativeDraftMediaStore(application)
    private val histories = mutableMapOf<String, NativeCompanionHistory>()
    private val statuses = mutableMapOf<String, NativeCompanionStatus>()
    private val statusEpochs = mutableMapOf<String, Long>()
    private val drafts = mutableMapOf<String, NativeCompanionDraft>()
    private val stops = mutableMapOf<String, Boolean>()
    private val readThrough = mutableMapOf<String, String>()
    private val historyJobs = mutableMapOf<String, Job>()
    private val refreshAgain = mutableSetOf<String>()
    private val sendJobs = mutableMapOf<String, Job>()
    private val invalidPending = mutableSetOf<String>()
    private val retrying = mutableSetOf<String>()
    private var rootJob: Job? = null
    private var rootAgain = false
    private var rootLoaded = false
    private var userProfileRevision = 0L
    private var observing: Job? = null
    private var events: Job? = null
    private var endpoint: NativeRuntimeHost.Endpoint? = null
    private var restoreError: String? = null
    private val initialRuntime = NativeRuntimeHost.inspect(application)
    private val initialId = preferences.getString("selectedCompanionId", null)
    private val _state = MutableStateFlow(NativeCompanionState(
        runtime = NativeRuntimeState(initialRuntime.ready, initialRuntime.active, initialRuntime.canStart, initialRuntime.status, initialRuntime.locale),
        selectedCompanionId = initialId, draft = initialId?.let(::draft) ?: NativeCompanionDraft(),
        error = restoreError,
        backgroundChoices = if (NativeCompanionWriteBarrier.frozen) emptyMap() else NativeCompanionAppearance.read(preferences.all),
    ))
    val state: StateFlow<NativeCompanionState> = _state.asStateFlow()

    internal suspend fun freezeForCompanionDeletion() {
        stopObserving()
        io.droyagent.app.nativeui.companion.maintenance.drainCompanionWriters(viewModelScope)
        awaitDraftPersistence()
    }
    internal fun retireCompanionLocal(id: String) {
        histories.remove(id); statuses.remove(id); statusEpochs.remove(id); drafts.remove(id)
        stops.remove(id); readThrough.remove(id); invalidPending.remove(id); retrying.remove(id)
        _state.update { current ->
            val selected = current.selectedCompanionId == id
            current.copy(companions = current.companions.filterNot { it.id == id },
                selectedCompanionId = current.selectedCompanionId.takeUnless { selected },
                messages = if (selected) emptyList() else current.messages,
                draft = if (selected) NativeCompanionDraft() else current.draft,
                status = if (selected) NativeCompanionStatus() else current.status,
                readingAnchor = if (selected) null else current.readingAnchor,
                draftsByCompanion = drafts.toMap(), backgroundChoices = current.backgroundChoices - id)
        }
    }

    fun startObserving() {
        if (NativeCompanionWriteBarrier.frozen) return
        if (observing?.isActive == true) return
        observing = viewModelScope.launch {
            while (isActive) {
                val value = NativeRuntimeHost.inspect(getApplication())
                val localeChanged = value.locale != _state.value.runtime.locale
                _state.update { it.copy(runtime = NativeRuntimeState(value.ready, value.active, value.canStart, value.status, value.locale)) }
                if (value.ready && endpoint?.isCurrent() != true) {
                    endpoint = NativeRuntimeHost.connection()
                    startEvents(); refresh()
                } else if (!value.ready) {
                    endpoint = null; events?.cancel(); events = null
                    rootJob?.cancel(); rootJob = null
                    historyJobs.values.toList().forEach { it.cancel() }; historyJobs.clear(); refreshAgain.clear()
                    _state.update { it.copy(loading = false, loadingOlder = false) }
                }
                if (value.ready && localeChanged) refresh()
                delay(750)
            }
        }
    }

    fun stopObserving() {
        observing?.cancel(); observing = null
        events?.cancel(); events = null; endpoint = null
        rootJob?.cancel(); rootJob = null; rootAgain = false
        historyJobs.values.toList().forEach { it.cancel() }; historyJobs.clear(); refreshAgain.clear()
        _state.update { it.copy(loading = false, loadingOlder = false) }
    }

    fun startRuntime() {
        try { NativeRuntimeHost.start(getApplication()); dismissError() }
        catch (_: IllegalStateException) { fail("runtime_start_failed") }
        catch (_: SecurityException) { fail("runtime_start_failed") }
    }

    fun refresh() {
        if (NativeCompanionWriteBarrier.frozen) return
        refreshRoot()
        _state.value.selectedCompanionId?.let { refreshHistory(it) }
        if (observing?.isActive == true && _state.value.runtime.ready && events?.isActive != true) startEvents()
    }

    fun selectCompanion(id: String?) {
        if (NativeCompanionWriteBarrier.frozen) return
        if (id != null && _state.value.companions.isNotEmpty() && _state.value.companions.none { it.id == id }) { fail("COMPANION_NOT_FOUND"); return }
        preferences.edit().putString("selectedCompanionId", id).apply { if (id != null) remove("anchor:$id") }.apply()
        _state.update { it.copy(selectedCompanionId = id, draft = id?.let(::draft) ?: NativeCompanionDraft(),
            readingAnchor = null, loading = id != null && histories[id]?.loaded != true, error = null) }
        publish()
        if (id != null && _state.value.runtime.ready) refreshHistory(id)
    }

    fun setDraft(text: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        _state.value.selectedCompanionId?.let { id -> setDraft(id, draft(id).copy(text = text)) } }
    fun setReplyTo(message: NativeCompanionMessage?) {
        if (NativeCompanionWriteBarrier.frozen) return
        _state.value.selectedCompanionId?.let { id -> setDraft(id, draft(id).copy(replyTo = message?.let { NativeCompanionReplyPreview(it.id, it.text, it.type) })) }
    }
    fun addAttachments(companionId: String, uploads: List<NativeCompanionAttachment>) {
        val merged = (draft(companionId).attachments + uploads).distinctBy { it.id }
        if (merged.size + draft(companionId).localAttachments.size > 10) { fail("COMPANION_INVALID_INPUT"); return }
        setDraft(companionId, draft(companionId).copy(attachments = merged))
    }
    fun deliverMediaAttachments(companionId: String, operationId: String, uploads: List<NativeCompanionAttachment>) {
        val key = "media-delivered:$companionId:$operationId"
        val delivered = preferences.getStringSet(key, emptySet()).orEmpty()
        val incoming = uploads.filterNot { it.id in delivered }
        if (incoming.isEmpty()) return
        val merged = (draft(companionId).attachments + incoming).distinctBy { it.id }
        if (merged.size + draft(companionId).localAttachments.size > 10) throw NativeApiException("COMPANION_INVALID_INPUT")
        val value = draft(companionId).copy(attachments = merged)
        // Delivery proof and the initial draft change share one preference-file transaction.
        // A later user removal retains that proof, so resuming another upload cannot re-add it.
        preferences.edit().putString("draft:$companionId", NativeCompanionJson.draft(value).toString())
            .putStringSet(key, delivered + incoming.map { it.id }).apply()
        drafts[companionId] = value
        _state.update { it.copy(draft = if (it.selectedCompanionId == companionId) value else it.draft, draftsByCompanion = drafts.toMap()) }
    }
    fun deliverLocalAttachments(companionId: String, operationId: String, incoming: List<NativeLocalAttachment>) {
        if (_state.value.companions.none { it.id == companionId }) throw NativeApiException("COMPANION_NOT_FOUND")
        val key = "media-delivered:$companionId:$operationId"
        val delivered = preferences.getStringSet(key, emptySet()).orEmpty()
        val current = draft(companionId)
        val merged = (current.localAttachments + incoming.filterNot { it.id in delivered }).distinctBy { it.id }
        if (merged.size + current.attachments.size > 10) throw NativeApiException("COMPANION_INVALID_INPUT")
        val value = current.copy(localAttachments = merged)
        preferences.edit().putString("draft:$companionId", NativeCompanionJson.draft(value).toString())
            .putStringSet(key, delivered + incoming.map { it.id }).apply()
        drafts[companionId] = value
        _state.update { it.copy(draft = if (it.selectedCompanionId == companionId) value else it.draft, draftsByCompanion = drafts.toMap()) }
    }
    suspend fun loadLocalAttachment(item: NativeLocalAttachment): ByteArray = localMedia.bytes(item)
    fun removeLocalAttachment(companionId: String, attachmentId: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        val previous = draft(companionId)
        val removed = previous.localAttachments.filter { it.id == attachmentId }
        setDraft(companionId, previous.copy(localAttachments = previous.localAttachments.filterNot { it.id == attachmentId }))
        removed.map { it.importId }.distinct().forEach(::releaseLocalImportLater)
    }
    suspend fun releaseLocalImportIfUnused(importId: String) {
        awaitDraftPersistence()
        val referenced = withContext(Dispatchers.IO) {
            preferences.all.any { (key, raw) ->
                if (raw !is String || !key.startsWith("draft:") && !key.startsWith("pending:")) false
                else {
                    val json = JSONObject(raw)
                    val value = if (key.startsWith("pending:")) json.getJSONObject("draft") else json
                    NativeCompanionJson.draft(value).localAttachments.any { it.importId == importId }
                }
            }
        }
        if (!referenced) localMedia.removeImport(importId)
    }
    private fun releaseLocalImportLater(importId: String) {
        viewModelScope.launch {
            try { releaseLocalImportIfUnused(importId) }
            catch (error: Exception) { if (error is CancellationException) throw error; fail("native_media_cleanup_failed") }
        }
    }
    suspend fun retireMediaDelivery(companionId: String, operationId: String) = withContext(Dispatchers.IO) {
        if (!preferences.edit().remove("media-delivered:$companionId:$operationId").commit()) throw NativeApiException("storage_error")
    }
    fun removeAttachment(companionId: String, attachmentId: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        setDraft(companionId, draft(companionId).copy(attachments = draft(companionId).attachments.filterNot { it.id == attachmentId }))
    }
    suspend fun awaitDraftPersistence() = withContext(Dispatchers.IO) {
        // A synchronous commit waits for prior apply writes without overwriting newer input.
        // Media receipts in another file can be retired only after this owner draft is durable.
        if (!preferences.edit().commit()) throw NativeApiException("storage_error")
    }
    fun setVoice(companionId: String, voice: NativeCompanionVoice?, text: String? = null) {
        if (NativeCompanionWriteBarrier.frozen) return
        setDraft(companionId, draft(companionId).copy(voice = voice, text = text ?: draft(companionId).text))
    }
    fun sendSticker(stickerId: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        val id = _state.value.selectedCompanionId ?: return
        val companion = _state.value.companions.firstOrNull { it.id == id } ?: return
        if (enabledCompanionStickers(companion.settings, _state.value.stickers).none { it.id == stickerId }) { fail("COMPANION_INVALID_STICKER"); return }
        submit(id, NativeCompanionDraft(stickerId = stickerId))
    }
    fun send() {
        if (NativeCompanionWriteBarrier.frozen) return
        _state.value.selectedCompanionId?.let { submit(it, draft(it)) } }

    /** An external preview has its own immutable content and never replaces the ordinary draft. */
    suspend fun submitExternalShare(companionId: String, clientId: String, content: NativeCompanionDraft): NativeCompanionMessage = withContext(Dispatchers.Main.immediate) {
        require(java.util.UUID.fromString(clientId).toString() == clientId && content.canSend)
        if (sendJobs[companionId]?.isActive == true || companionId in invalidPending) throw NativeApiException("pending_send_conflict")
        if (companionId in stops || statuses[companionId]?.chatExecution?.let { it.cancelRequested && !it.quiescent } == true)
            throw NativeApiException("companion_stop_pending")
        if (_state.value.companions.none { it.id == companionId }) throw NativeApiException("COMPANION_NOT_FOUND")
        pending(companionId)?.takeIf { it.clientId != clientId }?.let(::archiveFailed)
        val prior = pending(companionId) ?: failedSubmissions(companionId).firstOrNull { it.clientId == clientId }
        val record = prior ?: NativeCompanionSubmission.prepare(companionId, content, Instant.now().toString(), clientId)
        val job = currentCoroutineContext()[Job]!!
        sendJobs[companionId] = job
        var accepted = false
        NativeMediaViewModel.submissionActivity("companion:$clientId", true)
        try {
            withContext(Dispatchers.IO) {
                if (!preferences.edit().putString(pendingKey(companionId), record.json().toString())
                        .putBoolean("external:$clientId", true).commit()) throw NativeApiException("storage_error")
            }
            publish()
            val materialized = materializeAttachments(record)
            val message = api.send(companionId, JSONObject(materialized.payload))
            if (message.id != clientId && message.clientId != clientId) throw NativeApiException("session_identity_conflict")
            accepted = true
            history(companionId).upsert(message)
            retirePending(record)
            awaitDraftPersistence()
            refreshRoot(); refreshHistory(companionId)
            message
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val observed = history(companionId).messages.firstOrNull { it.id == clientId || it.clientId == clientId }
            if (observed != null) {
                retirePending(record); awaitDraftPersistence(); observed
            } else {
                val current = pending(companionId)
                if (!accepted && canMarkCompanionSubmissionUncertain(record, current, false))
                    archiveFailed(current!!)
                fail(code(error)); throw error
            }
        } finally { if (sendJobs[companionId] === job) sendJobs.remove(companionId); NativeMediaViewModel.submissionActivity("companion:$clientId", false); publish() }
    }
    fun retryPending(messageId: String? = null) {
        if (NativeCompanionWriteBarrier.frozen) return
        val id = _state.value.selectedCompanionId ?: return
        if (id in stops || _state.value.stopRequested) { fail("companion_stop_pending"); return }
        val saved = if (messageId == null) pending(id) else pending(id)?.takeIf { it.clientId == messageId } ?: failedSubmissions(id).firstOrNull { it.clientId == messageId }
        if (saved == null) return
        if (sendJobs[id]?.isActive != true) dispatch(saved)
    }

    private fun submit(id: String, value: NativeCompanionDraft) {
        if (!value.canSend || sendJobs[id]?.isActive == true) return
        if (id in stops || _state.value.selectedCompanionId == id && _state.value.stopRequested) { fail("companion_stop_pending"); return }
        pending(id)?.let { archiveFailed(it) }
        if (id in invalidPending) { fail("storage_error"); return }
        val record = NativeCompanionSubmission.prepare(id, value, Instant.now().toString())
        dispatch(record.copy(draftConsumed = true), consumeDraft = NativeCompanionJson.sameSubmissionContent(draft(id), value))
    }

    private fun dispatch(record: NativeCompanionSubmission, consumeDraft: Boolean = false) {
        val id = record.companionId
        stops.remove(id)
        // Install the immutable owner/content in memory before another UI action can remove a local source.
        val editor = preferences.edit().putString(pendingKey(id), record.json().toString())
        if (consumeDraft) {
            drafts[id] = NativeCompanionDraft()
            editor.putString("draft:$id", NativeCompanionJson.draft(NativeCompanionDraft()).toString())
        }
        // The draft and immutable outbox move together, before upload or network work can suspend.
        editor.apply()
        sendJobs[id] = viewModelScope.launch {
            var accepted = false
            NativeMediaViewModel.submissionActivity("companion:${record.clientId}", true)
            try {
                withContext(Dispatchers.IO) {
                    if (!preferences.edit().putString(pendingKey(id), record.json().toString()).commit()) throw NativeApiException("storage_error")
                }
                dismissError(); publish()
                val materialized = materializeAttachments(record)
                val message = api.send(id, JSONObject(materialized.payload))
                if (message.id != record.clientId && message.clientId != record.clientId) throw NativeApiException("session_identity_conflict")
                accepted = true
                history(id).upsert(message)
                retirePending(record)
                refreshRoot(); refreshHistory(id)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val current = pending(id)
                if (!accepted && canMarkCompanionSubmissionUncertain(record, current, history(id).contains(record.clientId))) {
                    archiveFailed(current!!)
                }
                fail(code(error))
            } finally { sendJobs.remove(id); NativeMediaViewModel.submissionActivity("companion:${record.clientId}", false); publish() }
        }
        publish()
    }

    private suspend fun materializeAttachments(record: NativeCompanionSubmission): NativeCompanionSubmission =
        materializeCompanionSubmission(record, { updated ->
            withContext(Dispatchers.IO) {
                if (!preferences.edit().putString(pendingKey(updated.companionId), updated.json().toString()).commit()) throw NativeApiException("storage_error")
            }
        }) { item -> api.upload(record.companionId, localMedia.bytes(item), item.mediaType, item.name) }

    fun loadOlder() {
        if (NativeCompanionWriteBarrier.frozen) return
        val id = _state.value.selectedCompanionId ?: return
        val history = history(id)
        if (!history.hasMore || historyJobs[id]?.isActive == true) return
        val before = history.nextBefore ?: return
        historyJobs[id] = viewModelScope.launch {
            _state.update { it.copy(loadingOlder = true) }
            try { history.prepend(api.page(id, before)); publish() }
            catch (error: Exception) { if (error is CancellationException) throw error; fail(code(error)) }
            finally {
                if (historyJobs[id] === currentCoroutineContext()[Job]) {
                    historyJobs.remove(id)
                    if (_state.value.selectedCompanionId == id) _state.update { it.copy(loadingOlder = false) }
                    if (refreshAgain.remove(id)) refreshHistory(id)
                }
            }
        }
    }

    /** The caller supplies the last fully visible, committed row while this conversation is foreground. */
    fun markVisibleRead(messageId: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        val id = _state.value.selectedCompanionId ?: return
        if (observing?.isActive != true || !_state.value.runtime.ready) return
        val rows = history(id).messages
        val index = rows.indexOfFirst { it.id == messageId }
        if (index == -1 || rows[index].status in setOf("pending", "sending", "failed") || readThrough[id] == messageId) return
        if (rows.indexOfFirst { it.id == readThrough[id] } > index) return
        readThrough[id] = messageId
        action {
            try { api.read(id, messageId); refreshRoot() }
            catch (error: Exception) { if (readThrough[id] == messageId) readThrough.remove(id); throw error }
        }
    }

    fun setPinned(id: String, pinned: Boolean) = conversation(id, if (pinned) "pin" else "unpin")
    fun hideConversation(id: String) = conversation(id, "hide")
    fun markConversationRead(id: String) = conversation(id, "read")
    private fun conversation(id: String, operation: String) = action { api.conversation(id, operation); refreshRoot() }

    fun deleteMessage(messageId: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        val id = _state.value.selectedCompanionId ?: return
        val local = pending(id)?.takeIf { it.clientId == messageId } ?: failedSubmissions(id).firstOrNull { it.clientId == messageId }
        if (local != null && sendJobs[id]?.isActive != true) {
            // It may have been admitted before a lost response; delete by the same stable identity.
            action {
                api.deleteMessage(id, messageId)
                val editor = preferences.edit().remove(failedKey(local)).remove("external:${local.clientId}")
                if (pending(id)?.clientId == messageId) editor.remove(pendingKey(id))
                if (NativeCompanionJson.sameSubmissionContent(draft(id), local.draft)) setDraft(id, NativeCompanionDraft())
                editor.apply(); history(id).remove(messageId)
                local.draft.localAttachments.map { it.importId }.distinct().forEach(::releaseLocalImportLater)
                publish(); refreshRoot()
            }
            return
        }
        action {
            api.deleteMessage(id, messageId); history(id).remove(messageId)
            if (draft(id).replyTo?.id == messageId) setDraft(id, draft(id).copy(replyTo = null))
            publish(); refreshRoot(); refreshHistory(id)
        }
    }

    fun retryTurn() {
        if (NativeCompanionWriteBarrier.frozen) return
        val id = _state.value.selectedCompanionId ?: return
        if (id in stops || _state.value.stopRequested) { fail("companion_stop_pending"); return }
        if (!retrying.add(id)) return
        stops.remove(id)
        action { try { api.retry(id); refreshRoot(); refreshHistory(id) } finally { retrying.remove(id) } }
    }

    fun retryVoiceTranscript(messageId: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        val owner = _state.value.selectedCompanionId ?: return
        val message = history(owner).messages.firstOrNull { it.id == messageId } ?: return
        val voice = message.voice ?: return
        if (voice.transcriptionState == "ready") return
        val key = "voice-asr:$owner:$messageId"
        if (!retrying.add(key)) return
        action {
            try {
                val facts = withContext(Dispatchers.IO) { io.droyagent.app.NativeVoiceInputHost.permissions(getApplication()) }
                if (facts.getBoolean("paused") || !facts.getBoolean("transcriptionAllowed")) throw NativeApiException("native_voice_permission")
                val url = voice.url ?: "/droy/companions/${NativeApiClient.encodeSegment(owner)}/attachments/${NativeApiClient.encodeSegment(voice.attachmentId)}"
                if (!io.droyagent.app.nativeui.media.NativeMediaPolicy.ownedUrl(owner, voice.attachmentId, url)) throw NativeApiException("COMPANION_ATTACHMENT_SCOPE")
                val speech = io.droyagent.app.nativeui.voiceinput.NativeSpeechClient()
                var result: io.droyagent.app.nativeui.voiceinput.NativeSpeechResult? = null
                preferences.getString(key, null)?.let { previous ->
                    val status = try { speech.operation(previous) }
                    catch (error: NativeApiException) { if (error.code == "NATIVE_SPEECH_OPERATION_UNKNOWN") null else throw error }
                    if (status != null && !status.settled) throw NativeApiException("native_voice_stop_unconfirmed")
                    if (status?.quiescent != true && !withContext(Dispatchers.IO) { io.droyagent.app.NativeVoiceInputHost.transcriptionQuiescent(getApplication()) })
                        throw NativeApiException("native_voice_stop_unconfirmed")
                    result = status?.result?.takeIf { it.text.isNotBlank() }
                }
                if (result == null) {
                    val operation = java.util.UUID.randomUUID().toString()
                    withContext(Dispatchers.IO) { if (!preferences.edit().putString(key, operation).commit()) throw NativeApiException("storage_error") }
                    result = speech.transcribe(api.media(url), operation)
                }
                val saved = api.voiceTranscript(owner, messageId, result!!.text)
                history(owner).upsert(saved)
                preferences.edit().remove(key).apply()
                publish(); refreshRoot(); refreshHistory(owner)
            } finally { retrying.remove(key) }
        }
    }

    fun cancelChat() {
        val id = _state.value.selectedCompanionId ?: return
        if (id in stops) return
        stops[id] = false; publish()
        action {
            try {
                // Stop follows the user's in-flight admission; it must not overtake that send.
                sendJobs[id]?.join()
                val receipt = api.cancel(id)
                if (!receipt.getBoolean("cancelRequested")) throw NativeApiException("malformed_response")
                stops[id] = true; publish(); refreshRoot(); refreshHistory(id)
            } catch (error: Exception) { stops.remove(id); publish(); throw error }
        }
    }
    fun cancelTask(taskMessageId: String) {
        val id = _state.value.selectedCompanionId ?: return
        if (history(id).messages.none { it.id == taskMessageId && it.task != null }) return
        action { api.cancel(id, taskMessageId); refreshHistory(id) }
    }

    fun updateProfile(id: String, patch: NativeCompanionProfilePatch) {
        if (NativeCompanionWriteBarrier.frozen) return
        if (_state.value.profileSaving || _state.value.stickerPackImporting) return
        _state.update { it.copy(profileSaving = true, error = null) }
        action {
            try { api.updateProfile(id, patch); refreshRoot() }
            finally { _state.update { it.copy(profileSaving = false) } }
        }
    }
    fun importStickerPack(id: String, uri: Uri) {
        if (NativeCompanionWriteBarrier.frozen || _state.value.profileSaving || _state.value.stickerPackImporting) return
        if (_state.value.companions.none { it.id == id }) { fail("COMPANION_NOT_FOUND"); return }
        action {
            _state.update { it.copy(stickerPackImporting = true) }
            try {
                val (name, bytes) = withContext(Dispatchers.IO) {
                    if (uri.scheme != "content") throw NativeApiException("COMPANION_STICKER_PACK_INVALID")
                    val resolver = getApplication<Application>().contentResolver
                    var filename = "stickers.zip"
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) filename = cursor.getString(nameIndex).take(160)
                            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex) && cursor.getLong(sizeIndex) > MAX_STICKER_PACK_BYTES)
                                throw NativeApiException("COMPANION_STICKER_PACK_TOO_LARGE")
                        }
                    }
                    val data = resolver.openInputStream(uri)?.use(::readCompanionStickerPack)
                        ?: throw NativeApiException("COMPANION_STICKER_PACK_INVALID")
                    filename to data
                }
                if (NativeCompanionWriteBarrier.frozen) return@action
                api.importStickerPack(id, bytes, name)
                refreshRoot()
                rootJob?.join()
            } catch (error: java.io.IOException) {
                throw NativeApiException("COMPANION_STICKER_PACK_INVALID", error)
            } catch (error: SecurityException) {
                throw NativeApiException("COMPANION_STICKER_PACK_INVALID", error)
            } finally { _state.update { it.copy(stickerPackImporting = false) } }
        }
    }
    fun updateUserProfile(name: String, avatarUrl: String? = null, bio: String? = null) {
        if (NativeCompanionWriteBarrier.frozen) return
        if (_state.value.profileSaving) return
        action { saveUserProfileForSetup(name, avatarUrl, bio) }
    }
    suspend fun saveUserProfileForSetup(name: String, avatarUrl: String? = null, bio: String? = null): NativeUserProfile = withContext(Dispatchers.Main.immediate) {
        if (_state.value.profileSaving) throw NativeApiException("COMPANION_BUSY")
        userProfileRevision++
        _state.update { it.copy(profileSaving = true, error = null) }
        try {
            val user = api.updateUser(name, avatarUrl, bio)
            _state.update { it.copy(userProfile = user) }
            refreshRoot()
            user
        } finally { _state.update { it.copy(profileSaving = false) } }
    }
    suspend fun uploadAttachment(companionId: String, bytes: ByteArray, mediaType: String, name: String): NativeCompanionAttachment = api.upload(companionId, bytes, mediaType, name)
    suspend fun uploadUserAvatar(bytes: ByteArray, mediaType: String, name: String): NativeCompanionAttachment = api.upload(null, bytes, mediaType, name)
    suspend fun loadImage(url: String): ByteArray = api.media(url)

    private fun refreshRoot() {
        if (rootJob?.isActive == true) { rootAgain = true; return }
        rootJob = viewModelScope.launch {
            if (!rootLoaded) _state.update { it.copy(loading = true) }
            do {
                rootAgain = false
                val epochs = statusEpochs.toMap()
                val userRevision = userProfileRevision
                try {
                    val value = api.root()
                    val companions = value.getJSONArray("companions").objects().map(NativeCompanionJson::profile).map { profile ->
                        if (epochs[profile.id] == statusEpochs[profile.id]) statuses[profile.id] = profile.status
                        profile.copy(status = statuses[profile.id] ?: profile.status)
                    }
                    val models = value.getJSONArray("models").objects().map {
                        NativeModel(it.getString("provider"), it.getString("id"), it.getString("name"), it.optString("providerName", it.getString("provider")))
                    }
                    val stickers = value.getJSONArray("stickers").objects().map(NativeCompanionJson::sticker)
                    val stickerPacks = value.optJSONArray("stickerPacks").objects().map(NativeCompanionJson::stickerPack)
                    rootLoaded = true
                    val user = NativeCompanionJson.user(value.getJSONObject("userProfile"))
                    val backgrounds = NativeCompanionAppearance.snapshot(companions)
                    NativeCompanionAppearance.save(preferences, backgrounds)
                    _state.update { it.copy(companions = companions, backgroundChoices = backgrounds, userProfile = if (userRevision == userProfileRevision) user else it.userProfile,
                        models = models, defaultModel = value.optJSONObject("defaultModel")?.let(NativeCompanionJson::model)?.let { route -> models.firstOrNull { it.provider == route.provider && it.id == route.id } ?: route }, stickers = stickers, stickerPacks = stickerPacks, loading = it.selectedCompanionId != null && histories[it.selectedCompanionId]?.loaded != true,
                        draftsByCompanion = companions.associate { profile -> profile.id to draft(profile.id) },
                        error = if (it.error in transientErrors) null else it.error) }
                    publish()
                } catch (error: Exception) { if (error is CancellationException) throw error; fail(code(error)) }
            } while (rootAgain && isActive)
        }
    }

    private fun refreshHistory(id: String) {
        if (historyJobs[id]?.isActive == true) { refreshAgain += id; return }
        historyJobs[id] = viewModelScope.launch {
            try {
                do {
                    refreshAgain.remove(id)
                    val history = history(id)
                    val retained = history.nextBefore
                    val epoch = statusEpochs[id]
                    val pages = mutableListOf(api.page(id))
                    val cursors = mutableSetOf<String>()
                    while (history.loaded && history.needsEarlier(pages.last(), retained)) {
                        val before = pages.last().nextBefore ?: throw NativeApiException("malformed_response")
                        if (!cursors.add(before)) throw NativeApiException("malformed_response")
                        pages += api.page(id, before)
                    }
                    history.replace(pages)
                    if (epoch == statusEpochs[id]) statuses[id] = pages.first().status
                    pending(id)?.takeIf { history.contains(it.clientId) }?.let(::retirePending)
                    failedSubmissions(id).filter { history.contains(it.clientId) }.forEach { record ->
                        preferences.edit().remove(failedKey(record)).remove("external:${record.clientId}").apply()
                        record.draft.localAttachments.map { it.importId }.distinct().forEach(::releaseLocalImportLater)
                    }
                    publish()
                } while (refreshAgain.remove(id) && isActive)
            } catch (error: Exception) { if (error is CancellationException) throw error; fail(code(error)) }
            finally {
                if (historyJobs[id] === currentCoroutineContext()[Job]) {
                    historyJobs.remove(id)
                    if (_state.value.selectedCompanionId == id) _state.update { it.copy(loading = false) }
                }
            }
        }
    }

    private fun startEvents() {
        events?.cancel()
        events = viewModelScope.launch {
            while (isActive) {
                try {
                    api.events().collect { event ->
                        when (event) {
                            NativeTransportEvent.Opened -> { refreshRoot(); _state.value.selectedCompanionId?.let(::refreshHistory) }
                            is NativeTransportEvent.Frame -> {
                                val value = event.value
                                val id = value.stringOrNull("companionId")
                                val kind = value.getString("kind")
                                if (id != null && value.optJSONObject("status") != null) {
                                    statusEpochs[id] = (statusEpochs[id] ?: 0) + 1
                                    statuses[id] = NativeCompanionJson.status(value.getJSONObject("status")); publish()
                                }
                                if (kind != "status") {
                                    refreshRoot()
                                    if (kind != "profile" && kind != "read" && id != null && (id == _state.value.selectedCompanionId || histories.containsKey(id))) refreshHistory(id)
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    fail(code(error))
                    if (code(error) !in transientErrors) break
                    delay(1000)
                }
            }
        }
    }

    private fun publish() {
        val id = _state.value.selectedCompanionId
        val committedRows = id?.let { history(it).messages } ?: emptyList()
        val failures = id?.let(::failedSubmissions).orEmpty().filterNot { record -> committedRows.any { it.id == record.clientId || it.clientId == record.clientId } }
        val rows = (committedRows + failures.map { it.optimistic() }).sortedBy { it.createdAt }
        val pending = id?.let(::pending)
        val sending = id?.let { sendJobs[it]?.isActive } == true
        val optimistic = pending?.let { record ->
            if (committedRows.any { it.id == record.clientId || it.clientId == record.clientId }) null
            else record.copy(uncertain = record.uncertain || !sending).optimistic()
        }
        val status = id?.let(statuses::get) ?: _state.value.companions.firstOrNull { it.id == id }?.status ?: NativeCompanionStatus()
        val execution = status.chatExecution
        if (id != null && execution?.quiescent == true && execution.cancelRequested && stops[id] == true) stops.remove(id)
        val localStop = id?.let(stops::get)
        val stopPending = companionStopPending(localStop, execution)
        _state.update { it.copy(messages = if (optimistic == null) rows else (rows.filterNot { it.id == optimistic.id } + optimistic).sortedBy { it.createdAt },
            draft = id?.let(::draft) ?: NativeCompanionDraft(), status = status, hasMore = id?.let { histories[it]?.hasMore } == true,
            sending = sending, stopRequested = stopPending,
            stopAcknowledged = localStop == true || execution?.cancelRequested == true,
            companions = it.companions.map { profile -> statuses[profile.id]?.let { value -> profile.copy(status = value) } ?: profile },
            draftsByCompanion = drafts.toMap(), error = restoreError ?: it.error) }
        restoreError = null
    }

    private fun history(id: String) = histories.getOrPut(id, ::NativeCompanionHistory)
    private fun draft(id: String): NativeCompanionDraft = drafts.getOrPut(id) {
        val raw = preferences.getString("draft:$id", null)
        try { raw?.let { NativeCompanionJson.draft(JSONObject(it)) } ?: NativeCompanionDraft() }
        catch (_: org.json.JSONException) { restoreError = "storage_error"; NativeCompanionDraft() }
        catch (_: IllegalArgumentException) { restoreError = "storage_error"; NativeCompanionDraft() }
    }
    private fun setDraft(id: String, value: NativeCompanionDraft) {
        drafts[id] = value
        preferences.edit().putString("draft:$id", NativeCompanionJson.draft(value).toString()).apply()
        _state.update { it.copy(draft = if (it.selectedCompanionId == id) value else it.draft, draftsByCompanion = drafts.toMap()) }
    }
    private fun pending(id: String): NativeCompanionSubmission? = try {
        preferences.getString(pendingKey(id), null)?.let { NativeCompanionSubmission.restore(JSONObject(it)) }
    } catch (_: org.json.JSONException) { invalidPending += id; restoreError = "storage_error"; null }
    catch (_: IllegalArgumentException) { invalidPending += id; restoreError = "storage_error"; null }
    catch (_: NativeApiException) { invalidPending += id; restoreError = "storage_error"; null }
    private fun retirePending(record: NativeCompanionSubmission) {
        val id = record.companionId
        if (pending(id)?.clientId != record.clientId) return
        val editor = preferences.edit().remove(pendingKey(id)).remove(failedKey(record)).remove("external:${record.clientId}")
        val external = preferences.getBoolean("external:${record.clientId}", false)
        if (!external && !record.draftConsumed) {
            val current = draft(id)
            val sent = record.draft.localAttachments.map { it.id }.toSet()
            val remaining = if (NativeCompanionJson.sameSubmissionContent(current, record.draft)) NativeCompanionDraft()
                else current.copy(localAttachments = current.localAttachments.filterNot { it.id in sent })
            drafts[id] = remaining
            editor.putString("draft:$id", NativeCompanionJson.draft(remaining).toString())
        }
        // Both keys are one atomic preference-file update; a crash keeps either the old identity or the cleared draft.
        editor.apply()
        if (!external) record.draft.localAttachments.map { it.importId }.distinct().forEach(::releaseLocalImportLater)
    }
    private fun failedKey(record: NativeCompanionSubmission) = "pending:${record.companionId}:${record.clientId}"
    private fun failedSubmissions(id: String): List<NativeCompanionSubmission> = preferences.all.entries
        .filter { it.key.startsWith("pending:$id:") }.mapNotNull { (_, raw) ->
            try { NativeCompanionSubmission.restore(JSONObject(raw as String)) }
            catch (_: org.json.JSONException) { restoreError = "storage_error"; null }
            catch (_: IllegalArgumentException) { restoreError = "storage_error"; null }
        }
    private fun archiveFailed(record: NativeCompanionSubmission) {
        val editor = preferences.edit().putString(failedKey(record), record.copy(uncertain = true).json().toString())
        if (pending(record.companionId)?.clientId == record.clientId) editor.remove(pendingKey(record.companionId))
        // The failed bubble owns its immutable media. New input gets an independent draft.
        if (!record.draftConsumed && !preferences.getBoolean("external:${record.clientId}", false) && NativeCompanionJson.sameSubmissionContent(draft(record.companionId), record.draft)) {
            drafts[record.companionId] = NativeCompanionDraft()
            editor.putString("draft:${record.companionId}", NativeCompanionJson.draft(NativeCompanionDraft()).toString())
        }
        editor.apply()
    }
    private fun pendingKey(id: String) = "pending:$id"
    private fun action(block: suspend () -> Unit) {
        if (NativeCompanionWriteBarrier.frozen) return
        viewModelScope.launch {
            try { dismissError(); block() }
            catch (error: Exception) { if (error is CancellationException) throw error; fail(code(error)) }
        }
    }
    fun dismissError() { _state.update { it.copy(error = null) } }
    private fun fail(error: String) { _state.update { it.copy(error = error, loading = false) } }
    private fun code(error: Exception) = (error as? NativeApiException)?.code ?: "malformed_response"
    private val transientErrors = setOf("network_error", "stream_disconnected", "runtime_unavailable", "service_temporarily_unavailable", "NATIVE_SERVICE_NOT_READY")
}
