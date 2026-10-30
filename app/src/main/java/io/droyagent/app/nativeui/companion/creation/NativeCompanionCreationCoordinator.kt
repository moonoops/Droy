package io.droyagent.app.nativeui.companion.creation

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeRuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

internal class NativeCreationCoordinator(
    private val repository: NativeCreationRepository,
    private val storage: NativeCreationJournalStore,
    private val files: NativeCreationImageStore,
    private val scope: CoroutineScope,
    private val identifier: () -> String = { UUID.randomUUID().toString() },
    private val activityChanged: (NativeCreationState) -> Unit = {},
) {
    private val mutable = MutableStateFlow(NativeCreationState())
    val state: StateFlow<NativeCreationState> = mutable
    private val journalLock = Mutex()
    private var catalogGeneration = 0L
    private var readGeneration = 0L
    private fun update(block: (NativeCreationState) -> NativeCreationState) { mutable.value = block(mutable.value); activityChanged(mutable.value) }
    private fun code(error: Exception) = (error as? NativeApiException)?.code ?: "creation_operation_failed"
    internal suspend fun flushForCompanionDeletion() = persist()
    private suspend fun persist() {
        try { journalLock.withLock { storage.write(state.value.journal) } }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { update { it.copy(restoreError = true, error = "storage_error") }; throw NativeApiException("storage_error", error) }
    }
    private fun persistLater() { scope.launch { try { persist() } catch (_: NativeApiException) { } } }
    fun runtime(value: NativeRuntimeState) = update { it.copy(runtime = value) }
    fun report(value: String) = update { it.copy(error = value) }
    fun dismissError() = update { it.copy(error = null, imageError = null) }
    fun page(value: NativeCreationPage) = update { it.copy(page = value) }

    fun restore(): Job = scope.launch {
        if (state.value.restored && !state.value.restoreError) return@launch
        try {
            val saved = journalLock.withLock { storage.read() }
            val image = saved?.image?.let { operation ->
                if (operation.phase in setOf(NativeCreationImagePhase.IMPORTING, NativeCreationImagePhase.UPLOADING)) operation.copy(phase = NativeCreationImagePhase.INTERRUPTED) else operation
            }
            update { it.copy(journal = saved?.copy(image = image), restored = true, restoreError = false,
                page = if (saved == null) NativeCreationPage.TEMPLATES else NativeCreationPage.EDITOR,
                imageError = if (image?.phase == NativeCreationImagePhase.INTERRUPTED) "creation_image_interrupted" else null,
                checked = saved?.draftId == null, error = null) }
            if (image?.phase == NativeCreationImagePhase.UPLOADED) cleanUploaded(image)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { update { it.copy(restored = true, restoreError = true, error = "storage_error") } }
    }
    fun refresh(): Job {
        val own = ++catalogGeneration
        return scope.launch {
            if (own != catalogGeneration) return@launch
            update { it.copy(loading = true) }
            try {
                val values = repository.templates()
                if (own == catalogGeneration) update { it.copy(templates = values, catalogLoaded = true, catalogError = null) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (own == catalogGeneration) update { it.copy(catalogError = code(error)) } }
            try {
                val values = repository.models()
                if (own == catalogGeneration) update { it.copy(models = values, defaultModel = repository.defaultModel(), modelsLoaded = true, modelsError = null) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (own == catalogGeneration) update { it.copy(modelsError = code(error)) } }
            if (own == catalogGeneration) update { it.copy(loading = false) }
            if (state.value.journal?.draftId != null && !state.value.busy) reconcile().join()
        }
    }
    fun reconcile(): Job {
        val journal = state.value.journal ?: return Job().apply { complete() }
        val id = journal.draftId ?: return Job().apply { complete() }
        val own = ++readGeneration
        return scope.launch {
            if (own != readGeneration) return@launch
            update { it.copy(checking = true) }
            try {
                val result = repository.draft(id)
                if (own != readGeneration || state.value.journal?.requestId != journal.requestId) return@launch
                applyDraft(result)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (own == readGeneration) update { it.copy(checking = false, checked = false, error = code(error)) } }
        }
    }
    private suspend fun applyDraft(value: NativeCreationDraftStatus) {
        val journal = state.value.journal ?: return
        if (journal.draftId != value.id) throw NativeApiException("COMPANION_ATTACHMENT_SCOPE")
        when (value.state) {
            "committed" -> update { it.copy(createdId = value.companionId ?: throw NativeApiException("malformed_response"), draftState = value.state, checked = true, checking = false, error = null) }
            "cancelled" -> if (journal.pendingCancel) resetCancelled() else update { it.copy(draftState = value.state, checked = true, checking = false, error = "COMPANION_DRAFT_CLOSED") }
            "open" -> update { it.copy(draftState = value.state, checked = true, checking = false,
                error = if (journal.frozenPayload != null) "creation_unconfirmed" else null) }
            else -> throw NativeApiException("malformed_response")
        }
    }

    fun selectTemplate(value: NativeCreationTemplate) {
        if (!state.value.canEdit || state.value.image != null || state.value.templates.none { it.id == value.id }) return
        val current = state.value.journal
        if (current?.editor?.template?.id == value.id) { page(NativeCreationPage.EDITOR); return }
        val journal = if (current == null) NativeCreationJournal(identifier(), NativeCreationEditor(value)) else current.copy(editor = NativeCreationEditor(value))
        update { it.copy(journal = journal, page = NativeCreationPage.EDITOR, checked = journal.draftId == null, error = null, imageError = null) }
    }
    fun leaveEditor(save: Boolean, onDone: () -> Unit): Job? {
        val current = state.value
        val journal = current.journal ?: return null
        if (!current.canEdit || current.image != null) return null
        update { it.copy(busy = true) }
        return scope.launch {
            try {
                if (!save) {
                    journal.draftId?.let { id ->
                        val result = repository.cancelDraft(id)
                        if (!result.cancelled) {
                            update { it.copy(createdId = result.companionId, draftState = "committed", checked = true) }
                            return@launch
                        }
                    }
                    journalLock.withLock {
                        storage.write(null)
                        update { it.copy(journal = null, draftState = null, error = null, imageError = null, checked = true) }
                    }
                } else persist()
                update { it.copy(page = NativeCreationPage.TEMPLATES) }
                onDone()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { update { it.copy(error = code(error)) } }
            finally { update { it.copy(busy = false) } }
        }
    }
    fun edit(block: (NativeCreationEditor) -> NativeCreationEditor) {
        if (!state.value.canEdit) return
        val journal = state.value.journal ?: return
        update { it.copy(journal = journal.copy(editor = block(journal.editor)), error = null) }
        persistLater()
    }
    fun resetAvatar() = edit { it.copy(avatarUpload = null) }
    fun create(): Job? = submit(retry = false)
    fun retryCreate(): Job? = submit(retry = true)
    private fun submit(retry: Boolean): Job? {
        val current = state.value
        val journal = current.journal ?: return null
        if (!current.runtime.ready || current.busy || current.checking || current.restoreError || current.image != null || journal.pendingCancel) return null
        if (retry) { if (journal.frozenPayload == null || !current.checked) return null }
        else if (!current.canCreate || journal.frozenPayload != null) return null
        readGeneration++
        update { it.copy(busy = true, checked = false, error = null) }
        return scope.launch {
            var submitted = false
            try {
                val known = requireOpenDraft() ?: return@launch
                val frozen = known.frozenPayload ?: NativeCreationJson.payload(known)
                update { it.copy(journal = known.copy(frozenPayload = frozen)) }
                persist()
                submitted = true
                val receipt = repository.create(frozen)
                if (receipt.requestId != known.requestId || receipt.templateId != known.editor.template.id) throw NativeApiException("malformed_response")
                readGeneration++
                update { it.copy(createdId = receipt.companionId, draftState = "committed", checked = true, checking = false, error = null) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val code = code(error)
                val definitive = code in setOf("COMPANION_NAME_CONFLICT", "COMPANION_INVALID_INPUT", "COMPANION_INVALID_ID", "COMPANION_TEMPLATE_NOT_FOUND", "COMPANION_ATTACHMENT_SCOPE", "COMPANION_IMAGE_REQUIRED")
                if (submitted && definitive) {
                    update { it.copy(journal = it.journal?.copy(frozenPayload = null), checked = true, error = code) }
                    try { persist() } catch (_: NativeApiException) { }
                } else update { it.copy(checked = false, error = if (submitted && code != "storage_error") "creation_unconfirmed" else code) }
            } finally { update { it.copy(busy = false) } }
        }
    }
    private suspend fun requireOpenDraft(): NativeCreationJournal? {
        var journal = state.value.journal ?: return null
        if (journal.draftId == null) {
            // Only explicit Create / image selection reaches this idempotent
            // preparation. Persist its identity before the first HTTP write.
            persist()
            val value = repository.ensureDraft(journal.requestId)
            journal = journal.copy(draftId = value.id)
            update { it.copy(journal = journal) }; persist()
            applyDraft(value)
            if (value.state != "open") return null
        } else {
            val value = repository.draft(journal.draftId!!)
            applyDraft(value)
            if (value.state != "open") return null
        }
        return state.value.journal
    }
    fun cancelCreation(): Job? {
        val current = state.value
        val journal = current.journal ?: return null
        if (!current.runtime.ready || current.busy || current.checking || journal.draftId == null || !current.checked || current.restoreError) return null
        readGeneration++
        update { it.copy(busy = true, journal = journal.copy(pendingCancel = true), error = null) }
        return scope.launch {
            try {
                persist()
                val result = repository.cancelDraft(journal.draftId)
                if (result.cancelled) resetCancelled() else update { it.copy(createdId = result.companionId, draftState = "committed", checked = true) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { update { it.copy(checked = false, error = code(error)) } }
            finally { update { it.copy(busy = false) } }
        }
    }
    fun resetClosed(): Job? {
        if (!state.value.checked || state.value.draftState != "cancelled" || state.value.busy) return null
        update { it.copy(busy = true) }
        return scope.launch {
            try { resetCancelled() } catch (error: CancellationException) { throw error }
            catch (error: Exception) { update { it.copy(error = code(error)) } }
            finally { update { it.copy(busy = false) } }
        }
    }
    private suspend fun resetCancelled() {
        val journal = state.value.journal ?: return
        val background = JSONObject(journal.editor.backgroundJson)
        val editor = journal.editor.copy(avatarUpload = null,
            backgroundJson = if (background.optString("type") == "upload") journal.editor.template.backgroundJson else journal.editor.backgroundJson)
        journal.image?.local?.let { files.remove(it.importId) }
        update { it.copy(journal = NativeCreationJournal(identifier(), editor), checked = true, checking = false, draftState = null,
            imageError = if (journal.editor.avatarUpload != null || background.optString("type") == "upload") "creation_choose_images_again" else null,
            page = NativeCreationPage.EDITOR, error = null) }
        persist()
    }
    fun acknowledgeCreated(id: String): Job? {
        if (state.value.createdId != id || state.value.busy) return null
        return scope.launch {
            update { it.copy(journal = null, createdId = null, page = NativeCreationPage.TEMPLATES, checked = true, draftState = null) }
            try { persist() } catch (_: NativeApiException) { }
        }
    }

    suspend fun prepareImage(kind: NativeCreationImageKind, pickerAlreadyOpen: Boolean): NativeCreationImageRequest? {
        if (pickerAlreadyOpen || !state.value.runtime.ready || !state.value.canEdit || state.value.image != null) { report("creation_media_busy"); return null }
        update { it.copy(busy = true, error = null, imageError = null) }
        var preparedId: String? = null
        try {
            val journal = requireOpenDraft() ?: return null
            val operation = NativeCreationImageOperation(identifier(), journal.requestId, journal.draftId!!, kind, NativeCreationImagePhase.PICKING)
            preparedId = operation.id
            update { it.copy(journal = journal.copy(image = operation)) }; persist()
            return NativeCreationImageRequest(operation.id, kind)
        } catch (error: CancellationException) {
            if (preparedId != null && state.value.image?.id == preparedId) {
                update { it.copy(journal = it.journal?.copy(image = null)) }
                withContext(NonCancellable) { try { persist() } catch (_: NativeApiException) { } }
            }
            throw error
        }
        catch (error: Exception) { update { it.copy(imageError = code(error)) }; return null }
        finally { update { it.copy(busy = false) } }
    }
    fun selectedImage(operationId: String, uri: String?): Job? {
        val image = state.value.image ?: return null
        if (image.id != operationId || image.phase != NativeCreationImagePhase.PICKING || state.value.busy) return null
        if (uri == null) return scope.launch {
            update { it.copy(journal = it.journal?.copy(image = null), imageError = null) }
            try { persist() } catch (_: NativeApiException) { }
        }
        update { it.copy(journal = it.journal?.copy(image = image.copy(uri = uri, phase = NativeCreationImagePhase.IMPORTING)), busy = true, imageError = null) }
        return scope.launch { processImage() }
    }
    fun retryImage(): Job? {
        val image = state.value.image ?: return null
        if (image.phase != NativeCreationImagePhase.UPLOADED && !state.value.runtime.ready || state.value.busy || state.value.creating || state.value.restoreError
            || image.phase !in setOf(NativeCreationImagePhase.INTERRUPTED, NativeCreationImagePhase.UPLOADED)) return null
        update { it.copy(busy = true, imageError = null) }
        return scope.launch { if (image.phase == NativeCreationImagePhase.UPLOADED) {
            try { cleanUploaded(image) } catch (error: CancellationException) { throw error }
            catch (error: Exception) { update { it.copy(imageError = code(error)) } } finally { update { it.copy(busy = false) } }
        } else processImage() }
    }
    fun discardImage(): Job? {
        val image = state.value.image ?: return null
        if (state.value.busy || image.phase in setOf(NativeCreationImagePhase.IMPORTING, NativeCreationImagePhase.UPLOADING)) return null
        return scope.launch {
            try {
                image.local?.let { files.remove(it.importId) }
                update { it.copy(journal = it.journal?.copy(image = null), imageError = null) }; persist()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { update { it.copy(imageError = code(error)) } }
        }
    }
    private suspend fun processImage() {
        try {
            var image = state.value.image ?: return
            val journal = requireOpenDraft() ?: return
            if (journal.requestId != image.ownerRequestId || journal.draftId != image.draftId) throw NativeApiException("COMPANION_ATTACHMENT_SCOPE")
            persist()
            val item = image.local ?: files.importImage(image.id, image.uri ?: throw NativeApiException("creation_image_unavailable"))
            if (item.importId != image.id || !item.image) throw NativeApiException("COMPANION_ATTACHMENT_SCOPE")
            image = image.copy(local = item, phase = NativeCreationImagePhase.UPLOADING)
            update { it.copy(journal = it.journal?.copy(image = image)) }; persist()
            val uploaded = repository.upload(image.draftId, item)
            if (!uploaded.mediaType.startsWith("image/") || uploaded.url != "/droy/companions/creation-drafts/${image.draftId}/attachments/${uploaded.id}") throw NativeApiException("COMPANION_ATTACHMENT_SCOPE")
            val current = state.value.journal ?: throw NativeApiException("COMPANION_ATTACHMENT_SCOPE")
            if (current.requestId != image.ownerRequestId || current.image?.id != image.id) throw NativeApiException("COMPANION_ATTACHMENT_SCOPE")
            val editor = if (image.kind == NativeCreationImageKind.AVATAR) current.editor.copy(avatarUpload = uploaded) else current.editor.copy(
                backgroundJson = JSONObject().put("type", "upload").put("id", uploaded.id).put("url", uploaded.url).put("position", JSONObject().put("x", 50).put("y", 50)).toString())
            image = image.copy(phase = NativeCreationImagePhase.UPLOADED, uploaded = uploaded)
            update { it.copy(journal = current.copy(editor = editor, image = image), imageError = null) }; persist()
            cleanUploaded(image)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            update { it.copy(journal = it.journal?.copy(image = it.image?.let { image -> if (image.phase == NativeCreationImagePhase.UPLOADED) image else image.copy(phase = NativeCreationImagePhase.INTERRUPTED) }), imageError = code(error)) }
            try { persist() } catch (_: NativeApiException) { }
        } finally { update { it.copy(busy = false) } }
    }
    private suspend fun cleanUploaded(image: NativeCreationImageOperation) {
        image.local?.let { files.remove(it.importId) }
        if (state.value.image?.id == image.id) { update { it.copy(journal = it.journal?.copy(image = null)) }; persist() }
    }
}
