package io.droyagent.app.nativeui.media

import io.droyagent.app.nativeui.companion.maintenance.NativeCompanionWriteBarrier
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.droyagent.app.nativeui.companion.data.NativeCompanionAttachment
import io.droyagent.app.nativeui.companion.data.NativeCompanionDraft
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class NativeShareDestination(val workbench: NativeWorkbenchMediaTarget? = null, val companionId: String? = null) {
    init {
        require((workbench != null) != (companionId != null))
        companionId?.let { require(it.isNotBlank() && it.length <= 1024 && it == it.trim() && it.none(Char::isISOControl)) }
    }

    fun json(): JSONObject = JSONObject().put("companionId", companionId ?: JSONObject.NULL).put("workbench", workbench?.let {
        JSONObject().put("draftId", it.draftId).put("sessionId", it.sessionId ?: JSONObject.NULL).put("workspaceId", it.workspaceId ?: JSONObject.NULL)
    } ?: JSONObject.NULL)
    companion object {
        fun parse(value: JSONObject): NativeShareDestination = NativeShareDestination(value.optJSONObject("workbench")?.let {
            NativeWorkbenchMediaTarget(it.getString("draftId"), it.optional("sessionId"), it.optional("workspaceId"))
        }, value.optional("companionId"))
        private fun JSONObject.optional(key: String): String? = if (isNull(key)) null else getString(key)
    }
}

data class NativeSharePreviewState(
    val importId: String? = null, val requestId: String? = null, val visible: Boolean = false,
    val text: String = "", val attachments: List<NativeLocalAttachment> = emptyList(),
    val destination: NativeShareDestination? = null, val attempted: Boolean = false, val accepted: Boolean = false,
    val resultSessionId: String? = null, val loading: Boolean = false, val sending: Boolean = false, val error: String? = null,
    val pendingImportIds: List<String> = emptyList(), val discarded: Boolean = false, val importFailed: Boolean = false,
) {
    val editable: Boolean get() = importId != null && !attempted && !loading && !sending && !discarded && !importFailed
    val canSend: Boolean get() = importId != null && destination != null && !loading && !sending && !accepted && !discarded && !importFailed && (text.isNotBlank() || attachments.isNotEmpty())
    fun json(): JSONObject = JSONObject().put("importId", importId ?: JSONObject.NULL).put("requestId", requestId ?: JSONObject.NULL)
        .put("visible", visible).put("text", text).put("attachments", JSONArray(attachments.map(NativeLocalAttachment::json)))
        .put("destination", destination?.json() ?: JSONObject.NULL).put("attempted", attempted).put("accepted", accepted)
        .put("resultSessionId", resultSessionId ?: JSONObject.NULL).put("discarded", discarded).put("importFailed", importFailed)
    companion object {
        fun parse(value: JSONObject): NativeSharePreviewState {
            if (value.isNull("importId")) return NativeSharePreviewState()
            val id = io.droyagent.app.media.MediaInputPolicy.id(value.getString("importId"))
            val request = io.droyagent.app.media.MediaInputPolicy.id(value.getString("requestId"))
            val attempted = value.getBoolean("attempted"); val accepted = value.getBoolean("accepted")
            require(!accepted || attempted)
            val discarded = value.optBoolean("discarded"); val importFailed = value.optBoolean("importFailed")
            require(!discarded || !attempted)
            val attachments = NativeLocalAttachment.parseList(value.getJSONArray("attachments"))
            require(attachments.all { it.importId == id })
            return NativeSharePreviewState(id, request, value.getBoolean("visible"), io.droyagent.app.media.MediaInputPolicy.text(value.getString("text")),
                attachments, value.optJSONObject("destination")?.let(NativeShareDestination::parse),
                attempted, accepted, if (value.isNull("resultSessionId")) null else value.getString("resultSessionId"),
                error = if (importFailed) "native_share_import_failed" else if (attempted && !accepted) "native_share_interrupted" else null,
                discarded = discarded, importFailed = importFailed)
        }
    }
}

internal suspend fun awaitAcceptedShare(requestId: String, submission: Job?, current: () -> NativeSharePreviewState): NativeSharePreviewState? {
    submission?.join()
    return current().takeIf { it.requestId == requestId && it.accepted }
}

/** Import, edits and target selection are local. Only confirm() can enter a product submission path. */
class NativeSharePreviewViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("native_share_preview", 0)
    private val store = NativeDraftMediaStore(application)
    private var workbench: NativeWorkbenchViewModel? = null
    private var companions: NativeCompanionViewModel? = null
    private var operation: Job? = null
    private var consumption: Job? = null
    private var previewEpoch = 0L
    private val persistence = Mutex()
    private var revision = 0L
    private var persistedRevision = -1L
    private val uploaded = linkedMapOf<String, NativeCompanionAttachment>()
    private var invalidJournal = false
    private val _state = MutableStateFlow(restore())
    val state: StateFlow<NativeSharePreviewState> = _state.asStateFlow()

    internal suspend fun freezeForCompanionDeletion(flush: Boolean = true) {
        io.droyagent.app.nativeui.companion.maintenance.drainCompanionWriters(viewModelScope)
        if (invalidJournal) throw NativeApiException("storage_error")
        if (flush) persist()
    }
    internal fun reloadAfterCompanionDeletion() {
        uploaded.clear(); invalidJournal = false
        _state.value = restore().copy(pendingImportIds = _state.value.pendingImportIds)
        if (invalidJournal) throw NativeApiException("storage_error")
    }

    fun bind(work: NativeWorkbenchViewModel, companion: NativeCompanionViewModel) { workbench = work; companions = companion }

    /** Polling an empty FIFO must not capture a target and pin the initial workbench space. */
    fun advanceInbox(defaultWorkbench: () -> NativeWorkbenchMediaTarget) {
        if (NativeCompanionWriteBarrier.frozen) return
        val epoch = previewEpoch
        viewModelScope.launch {
            try {
                if (_state.value.importId == null) consumption?.join()
                val pending = withContext(Dispatchers.IO) { NativeShareInbox.pending(getApplication()) }
                _state.update { it.copy(pendingImportIds = pending) }
                if (epoch != previewEpoch || invalidJournal || _state.value.importId != null || operation?.isActive == true || consumption?.isActive == true) return@launch
                pending.firstOrNull()?.let { claimImport(it, defaultWorkbench()) }
            } catch (error: Exception) { if (error is CancellationException) throw error; fail(code(error)) }
        }
    }

    fun openImport(importId: String, defaultWorkbench: NativeWorkbenchMediaTarget) {
        if (NativeCompanionWriteBarrier.frozen) return
        if (invalidJournal) { fail("storage_error"); return }
        if (consumption?.isActive == true) { fail("native_share_busy"); return }
        if (_state.value.importId == importId) { _state.update { it.copy(visible = true) }; persistLater(); return }
        if (_state.value.importId != null || operation?.isActive == true) { fail("native_share_busy"); return }
        val epoch = previewEpoch
        operation = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { NativeShareInbox.enqueue(getApplication(), importId) }
                val pending = withContext(Dispatchers.IO) { NativeShareInbox.pending(getApplication()) }
                _state.update { it.copy(pendingImportIds = pending) }
                // The queue head remains authoritative even when a second intent supplies another ID.
                if (epoch == previewEpoch) pending.firstOrNull()?.let { loadClaimedImport(it, defaultWorkbench) }
            } catch (error: Exception) { if (error is CancellationException) throw error; fail(code(error)) }
        }
    }

    private fun claimImport(id: String, defaultWorkbench: NativeWorkbenchMediaTarget) {
        operation = viewModelScope.launch { loadClaimedImport(id, defaultWorkbench) }
    }
    private suspend fun loadClaimedImport(id: String, defaultWorkbench: NativeWorkbenchMediaTarget) {
        uploaded.clear()
        _state.value = NativeSharePreviewState(importId = id, requestId = UUID.randomUUID().toString(), visible = true,
            destination = NativeShareDestination(workbench = defaultWorkbench), loading = true, importFailed = true,
            pendingImportIds = _state.value.pendingImportIds)
        try {
            // A crash during import validation must preserve the original queue head and request identity.
            persist()
            val imported = store.share(id)
            if (_state.value.importId != id || _state.value.discarded) return
            _state.update { it.copy(text = imported.text, attachments = imported.attachments, loading = false,
                importFailed = imported.error != null, error = if (imported.error != null) "native_share_import_failed" else null) }
            persist()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _state.update { it.copy(loading = false, importFailed = true, error = code(error)) }; persistLater()
        } finally { _state.update { if (it.importId == id) it.copy(loading = false) else it } }
    }

    fun setText(value: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        if (!_state.value.editable) return
        if (value.length > io.droyagent.app.media.MediaInputPolicy.MAX_TEXT) { fail("native_share_text_too_long"); return }
        _state.update { it.copy(text = value) }; persistLater()
    }
    fun selectWorkbench(target: NativeWorkbenchMediaTarget) = editDestination(NativeShareDestination(workbench = target))
    fun selectCompanion(id: String) = editDestination(NativeShareDestination(companionId = id))
    private fun editDestination(destination: NativeShareDestination) {
        if (NativeCompanionWriteBarrier.frozen) return
        if (!_state.value.editable) return
        uploaded.clear(); _state.update { it.copy(destination = destination) }; persistLater()
    }
    fun removeAttachment(id: String) { if (_state.value.editable) {
        if (NativeCompanionWriteBarrier.frozen) return
        _state.update { it.copy(attachments = it.attachments.filterNot { item -> item.id == id }) }; persistLater() } }
    suspend fun loadAttachment(item: NativeLocalAttachment): ByteArray = store.bytes(item)

    fun confirm() {
        if (NativeCompanionWriteBarrier.frozen) return
        if (!_state.value.canSend || operation?.isActive == true || consumption?.isActive == true) return
        operation = viewModelScope.launch {
            val snapshot = _state.value.copy(attempted = true, sending = true, error = null)
            NativeMediaViewModel.submissionActivity("share:${snapshot.requestId}", true)
            _state.value = snapshot
            try {
                persist()
                val request = snapshot.requestId ?: throw NativeApiException("native_share_scope")
                val destination = snapshot.destination ?: throw NativeApiException("native_share_scope")
                val result = if (destination.workbench != null) {
                    val service = workbench ?: throw NativeApiException("runtime_unavailable")
                    service.submitExternalShare(destination.workbench, request, snapshot.text, snapshot.attachments)
                } else {
                    val service = companions ?: throw NativeApiException("runtime_unavailable")
                    val owner = destination.companionId ?: throw NativeApiException("native_share_scope")
                    for (item in snapshot.attachments) if (item.id !in uploaded) {
                        val saved = service.uploadAttachment(owner, store.bytes(item), item.mediaType, item.name)
                        if (!NativeMediaPolicy.ownedUrl(owner, saved.id, saved.url)) throw NativeApiException("COMPANION_ATTACHMENT_SCOPE")
                        uploaded[item.id] = saved
                        persist()
                    }
                    service.submitExternalShare(owner, request, NativeCompanionDraft(snapshot.text, snapshot.attachments.map { uploaded.getValue(it.id) }))
                    null
                }
                _state.update { it.copy(accepted = true, sending = false, resultSessionId = result, error = null) }
                persist()
            } catch (error: Exception) {
                if (error is CancellationException) {
                    _state.update { it.copy(sending = false, error = "native_share_interrupted") }; persistLater(); throw error
                }
                _state.update { it.copy(sending = false, error = code(error)) }
                persistLater()
            } finally { _state.update { it.copy(sending = false) }; NativeMediaViewModel.submissionActivity("share:${snapshot.requestId}", false) }
        }
    }

    /** Closing an uncertain submission keeps its identity and files for an explicit retry. */
    fun cancel() {
        if (NativeCompanionWriteBarrier.frozen) return
        previewEpoch++
        val snapshot = _state.value
        if (snapshot.accepted) { snapshot.requestId?.let(::consumeAccepted); return }
        if (snapshot.attempted || snapshot.sending) { _state.update { it.copy(visible = false) }; persistLater(); return }
        if (snapshot.importId == null || consumption?.isActive == true) return
        _state.update { it.copy(discarded = true, visible = false) }
        val pendingOperation = operation
        consumption = viewModelScope.launch {
            try {
                pendingOperation?.join()
                val current = _state.value
                if (current.importId == snapshot.importId && current.discarded) finishLocalDisposition(current)
            } catch (error: Exception) { if (error is CancellationException) throw error; fail(localCode(error)) }
        }
    }

    /** Accepted may publish before confirm's final commit. Consumption waits for that job and sends nothing. */
    fun consumeAccepted(requestId: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        if (consumption?.isActive == true || _state.value.requestId != requestId || !_state.value.accepted) return
        val pendingSubmission = operation
        consumption = viewModelScope.launch {
            try { awaitAcceptedShare(requestId, pendingSubmission) { _state.value }?.let { finishLocalDisposition(it) } }
            catch (error: Exception) { if (error is CancellationException) throw error; fail(localCode(error)) }
        }
    }

    private suspend fun finishLocalDisposition(snapshot: NativeSharePreviewState) {
        val id = snapshot.importId ?: return
        if (!snapshot.accepted && !snapshot.discarded) throw NativeApiException("native_share_scope")
        // Keep a durable local disposition until both queue retirement and scoped file cleanup have succeeded.
        persist()
        withContext(Dispatchers.IO) { NativeShareInbox.acknowledge(getApplication(), id) }
        store.removeShareImport(id)
        val remaining = withContext(Dispatchers.IO) { NativeShareInbox.pending(getApplication()) }
        val cleared = NativeSharePreviewState(pendingImportIds = remaining)
        persist(cleared, emptyMap())
        uploaded.clear(); _state.value = cleared
    }

    fun discardImport(importId: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        if (_state.value.importId != importId) { fail("native_share_busy"); return }
        cancel()
    }
    fun showPending() { if (_state.value.importId != null && consumption?.isActive != true) {
        if (NativeCompanionWriteBarrier.frozen) return
        _state.update { it.copy(visible = true) }; persistLater() } }
    fun dismissError() { _state.update { it.copy(error = null) } }
    private fun fail(error: String) { _state.update { it.copy(error = error) } }
    private fun code(error: Exception) = when (error) {
        is NativeApiException -> error.code
        is NativeMediaProblem -> when (error.problem) {
            NativeMediaError.TOO_MANY -> "native_attachment_too_many"
            NativeMediaError.TOO_LARGE -> "native_attachment_too_large"
            NativeMediaError.STORAGE -> "storage_error"
            else -> "native_share_import_failed"
        }
        else -> "native_share_failed"
    }
    private fun localCode(error: Exception) = (error as? NativeApiException)?.code ?: "native_media_cleanup_failed"

    private fun restore(): NativeSharePreviewState = try {
        preferences.getString("preview", null)?.let { raw ->
            val value = JSONObject(raw)
            revision = value.optLong("revision")
            persistedRevision = revision
            value.optJSONObject("uploaded")?.let { rows -> rows.keys().forEach { key -> rows.getJSONObject(key).let { item ->
                val attachment = NativeCompanionAttachment(item.getString("id"), item.getString("name"), item.getString("mediaType"), item.getLong("bytes"), item.getString("url"))
                uploaded[key] = attachment
            } } }
            NativeSharePreviewState.parse(value).also { state ->
                if (uploaded.isNotEmpty()) {
                    val owner = state.destination?.companionId ?: throw IllegalArgumentException()
                    require(uploaded.keys.all { id -> state.attachments.any { it.id == id } })
                    require(uploaded.values.all { NativeMediaPolicy.ownedUrl(owner, it.id, it.url) && it.bytes in 1..NativeMediaPolicy.MAX_BYTES.toLong() })
                }
            }
        } ?: NativeSharePreviewState()
    } catch (_: Exception) { invalidJournal = true; NativeSharePreviewState(error = "storage_error") }
    private suspend fun persist(snapshot: NativeSharePreviewState = _state.value, uploads: Map<String, NativeCompanionAttachment> = uploaded.toMap()) {
        val version = ++revision
        val value = snapshot.json().put("revision", version).put("uploaded", JSONObject().apply { uploads.forEach { (id, item) ->
            put(id, JSONObject().put("id", item.id).put("name", item.name).put("mediaType", item.mediaType).put("bytes", item.bytes).put("url", item.url))
        } }).toString()
        persistence.withLock {
            if (version <= persistedRevision) return@withLock
            withContext(Dispatchers.IO) { if (!preferences.edit().putString("preview", value).commit()) throw NativeApiException("storage_error") }
            persistedRevision = version
        }
    }
    private fun persistLater() { viewModelScope.launch { try { persist() } catch (error: Exception) { if (error is CancellationException) throw error; fail("storage_error") } } }
}
