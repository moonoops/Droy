package io.droyagent.app.nativeui.media

import io.droyagent.app.nativeui.companion.maintenance.NativeCompanionWriteBarrier
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.droyagent.app.media.MediaInputPolicy
import io.droyagent.app.NativeRuntimeHost
import io.droyagent.app.NativeCameraInputHost
import io.droyagent.app.nativeui.companion.data.NativeCompanionAttachment
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.companion.data.NativeCompanionVoice
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import java.io.File
import java.io.IOException
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

internal data class NativeMediaOpen(val id: String, val ownerId: String, val file: File, val mediaType: String)
private data class SelectedDocument(val bytes: ByteArray, val name: String, val mediaType: String)

/** Retains original ownership and receipts; restored uploads require an explicit retry. */
class NativeMediaViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("native_media", 0)
    private val persistence = Mutex()
    private var revision = 0L
    private var persistedRevision = -1L
    private var restoreFailed = false
    private val hasSavedActivityOperation = savedState.contains(STATE_KEY)
    private val initial = restore()
    private var selection: NativeMediaSelection? = initial?.optJSONObject("selection")?.let {
        try { NativeMediaSelection.restore(it) }
        catch (_: org.json.JSONException) { restoreFailed = true; null }
        catch (_: IllegalArgumentException) { restoreFailed = true; null }
    }?.recoverForActivity(hasSavedActivityOperation)
    private val avatars = restoredImages("avatars")
    private val backgrounds = restoredImages("backgrounds")
    private fun restoredImages(key: String) = linkedMapOf<String, NativeAvatarResult>().apply {
        initial?.optJSONArray(key)?.let { values ->
            for (index in 0 until values.length()) {
                try {
                    val value = values.getJSONObject(index)
                    val owner = if (value.isNull("ownerId")) null else value.getString("ownerId")
                    val result = NativeAvatarResult(value.getString("id"), owner, value.getString("url"), value.getString("attachmentId"))
                    if (key == "backgrounds" && owner == null || !NativeMediaPolicy.ownedUrl(owner, result.attachmentId, result.url)) throw IllegalArgumentException()
                    put(NativeMediaPolicy.ownerKey(owner), result)
                } catch (_: org.json.JSONException) { restoreFailed = true }
                catch (_: IllegalArgumentException) { restoreFailed = true }
            }
        }
    }
    private var cameraAlbumResult: NativeCameraAlbumResult? = initial?.optJSONObject("cameraAlbumResult")?.let {
        try { NativeCameraAlbumResult.parse(it) }
        catch (_: Exception) { restoreFailed = true; null }
    }
    private val _state = MutableStateFlow(NativeMediaState(
        phase = selection?.phase, ownerId = selection?.ownerId, avatarResults = avatars.toMap(), backgroundResults = backgrounds.toMap(),
        canRetry = selection?.phase == NativeMediaPhase.INTERRUPTED,
        error = if (restoreFailed) NativeMediaError.STORAGE else if (selection?.phase == NativeMediaPhase.INTERRUPTED) NativeMediaError.INTERRUPTED else null,
        kind = selection?.kind,
        camera = selection?.camera == true, cameraAlbumResult = cameraAlbumResult,
    ))
    val state: StateFlow<NativeMediaState> = _state.asStateFlow()
    private val _openRequests = MutableStateFlow<NativeMediaOpen?>(null)
    internal val openRequests: StateFlow<NativeMediaOpen?> = _openRequests.asStateFlow()
    private var companion: NativeCompanionViewModel? = null
    private var workbench: NativeWorkbenchViewModel? = null
    private val draftStore = NativeDraftMediaStore(application)
    private var upload: Job? = null
    private var cancellingSelection = false
    private var downloading: Job? = null
    private var audioDownload: Job? = null
    private var player: MediaPlayer? = null
    private var playingFile: File? = null
    private var visibleOwner: String? = null
    private var downloadGeneration = 0L
    private var backgroundChanged: () -> Unit = {}
    private val workIdentity = UUID.randomUUID().toString()
    private val cache = NativeMediaCache(File(application.filesDir, "media-input/captures/native-open"))

    internal suspend fun freezeForCompanionDeletion(flush: Boolean = true) {
        downloadGeneration++
        downloading?.cancel(); audioDownload?.cancel(); stopPlayback()
        _openRequests.value = null
        io.droyagent.app.nativeui.companion.maintenance.drainCompanionWriters(viewModelScope)
        check(!restoreFailed) { "COMPANION_MAINTENANCE_STORAGE" }
        if (flush) persist()
    }
    internal fun reloadAfterCompanionDeletion() {
        val raw = preferences.getString(STATE_KEY, null)
        val value = raw?.let(::JSONObject)
        savedState[STATE_KEY] = raw
        revision = value?.optLong("revision") ?: 0; persistedRevision = revision
        selection = value?.optJSONObject("selection")?.let(NativeMediaSelection::restore)
        for ((key, destination) in listOf("avatars" to avatars, "backgrounds" to backgrounds)) {
            destination.clear()
            value?.optJSONArray(key)?.let { rows ->
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONObject(index)
                    val owner = row.optString("ownerId").takeUnless { it.isEmpty() || it == "null" }
                    destination[NativeMediaPolicy.ownerKey(owner)] = NativeAvatarResult(
                        row.getString("id"), owner, row.getString("url"), row.getString("attachmentId"))
                }
            }
        }
        cameraAlbumResult = value?.optJSONObject("cameraAlbumResult")?.let(NativeCameraAlbumResult::parse)
        publish()
    }
    internal fun bind(model: NativeCompanionViewModel, onWorkChanged: () -> Unit) = bind(model, onWorkChanged, null)
    internal fun bind(model: NativeCompanionViewModel, onWorkChanged: () -> Unit, work: NativeWorkbenchViewModel?) {
        companion = model; workbench = work; backgroundChanged = onWorkChanged; updateWorkProtection()
    }
    internal fun pendingSelection(): NativeMediaSelection? = selection

    internal suspend fun begin(ownerId: String?, kind: NativeMediaKind, maximum: Int, imagesOnly: Boolean, target: NativeWorkbenchMediaTarget? = null, camera: Boolean = false): NativeMediaSelection? {
        if (NativeCompanionWriteBarrier.frozen) return null
        if (selection != null || upload?.isActive == true || NativeRuntimeHost.isFilePickerOpen()) { report(NativeMediaError.BUSY); return null }
        ownerFailure(ownerId, kind)?.let { report(it); return null }
        if (maximum !in 1..10) { report(NativeMediaError.TOO_MANY); return null }
        if ((kind == NativeMediaKind.WORKBENCH_DRAFT) != (target != null) || target != null && target.draftId != ownerId) { report(NativeMediaError.OWNER); return null }
        if (camera) {
            if (kind.profileImage || !imagesOnly) { report(NativeMediaError.OWNER); return null }
            try { NativeCameraInputHost.checkStart(getApplication()) }
            catch (error: NativeCameraInputHost.Failure) { report(cameraFailure(error)); return null }
        }
        val id = UUID.randomUUID().toString()
        selection = NativeMediaSelection(id, ownerId, kind, maximum, imagesOnly, workTarget = target, camera = camera, captureId = id.takeIf { camera })
        val requestId = selection!!.id
        publish()
        return try { persist(); selection }
        catch (error: CancellationException) { interruptUnlaunched(requestId); throw error }
        catch (error: NativeMediaProblem) { selection = selection?.copy(phase = NativeMediaPhase.INTERRUPTED); interrupt(error.problem); persistLater(); null }
    }

    internal fun pickerLaunched(id: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        if (selection?.id != id) return
        selection = selection?.copy(launched = true)
        persistLater()
    }
    private fun interruptUnlaunched(id: String) {
        val current = selection ?: return
        if (current.id != id || current.launched || current.phase != NativeMediaPhase.PICKING) return
        selection = current.copy(phase = NativeMediaPhase.INTERRUPTED)
        interrupt(NativeMediaError.INTERRUPTED); persistLater()
    }

    internal fun pickerFailed(id: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        if (selection?.id != id) return
        selection = selection?.copy(phase = NativeMediaPhase.INTERRUPTED, launched = false)
        interrupt(NativeMediaError.PICKER); persistLater()
    }
    internal fun cameraLaunchFailed(id: String, code: String?, captureId: String? = null) {
        if (NativeCompanionWriteBarrier.frozen) return
        if (selection?.id != id || captureId != null && selection?.captureId != captureId) return
        selection = selection?.copy(phase = NativeMediaPhase.INTERRUPTED, launched = false)
        interrupt(when (code) {
            "native_camera_permission" -> NativeMediaError.CAMERA_PERMISSION
            "native_camera_paused" -> NativeMediaError.PAUSED
            else -> NativeMediaError.CAMERA
        }); persistLater()
    }

    internal fun selected(uris: List<Uri>?) {
        if (NativeCompanionWriteBarrier.frozen) return
        val original = selection
        if (original == null) { report(NativeMediaError.UNMATCHED_RESULT); return }
        if (original.camera) { report(NativeMediaError.UNMATCHED_RESULT); return }
        if (original.phase != NativeMediaPhase.PICKING) {
            if (original.uris.isEmpty()) report(NativeMediaError.UNMATCHED_RESULT)
            return
        }
        if (uris == null || original.discardResult) {
            selection = null; publish(); persistLater(); return
        }
        val selected = uris.map(Uri::toString).distinct()
        if (selected.isEmpty()) { selection = null; publish(); persistLater(); return }
        if (selected.size > original.maximum || selected.any { !NativeMediaPolicy.contentUri(it) }) {
            selection = original.copy(phase = NativeMediaPhase.INTERRUPTED)
            interrupt(if (selected.size > original.maximum) NativeMediaError.TOO_MANY else NativeMediaError.READ)
            return
        }
        selection = original.copy(uris = selected, phase = if (original.kind.profileImage) NativeMediaPhase.UPLOADING else NativeMediaPhase.IMPORTING)
        publish(); runUpload()
    }

    internal fun selectedCamera(ok: Boolean) {
        if (NativeCompanionWriteBarrier.frozen) return
        val original = selection
        if (original == null || !original.camera || original.phase != NativeMediaPhase.PICKING) { report(NativeMediaError.UNMATCHED_RESULT); return }
        if (!ok || original.discardResult) {
            selection = null; publish(); persistLater(); discardCameraOutputLater(original); return
        }
        selection = original.copy(cameraReady = true, phase = NativeMediaPhase.IMPORTING)
        publish(); runUpload()
    }

    internal suspend fun retry(): NativeMediaSelection? {
        if (NativeCompanionWriteBarrier.frozen) return null
        val current = selection ?: return null
        if (current.phase != NativeMediaPhase.INTERRUPTED || upload?.isActive == true || cancellingSelection) { report(NativeMediaError.BUSY); return null }
        if (current.needsPicker && NativeRuntimeHost.isFilePickerOpen()) { report(NativeMediaError.BUSY); return null }
        ownerFailure(current.ownerId, current.kind)?.let { report(it); return null }
        selection = current.copy(phase = if (current.needsPicker) NativeMediaPhase.PICKING else if (current.kind.profileImage) NativeMediaPhase.UPLOADING else NativeMediaPhase.IMPORTING,
            launched = false, discardResult = false, captureId = if (current.camera && current.needsPicker) UUID.randomUUID().toString() else current.captureId)
        _state.update { it.copy(error = null) }; publish()
        return try {
            persist()
            if (current.camera && current.needsPicker) discardCameraOutputLater(current)
            if (current.needsPicker) selection else { runUpload(); null }
        } catch (error: CancellationException) { interruptUnlaunched(current.id); throw error }
        catch (error: NativeMediaProblem) { selection = selection?.copy(phase = NativeMediaPhase.INTERRUPTED); interrupt(error.problem); persistLater(); null }
    }

    fun cancelPending() {
        if (NativeCompanionWriteBarrier.frozen) return
        val current = selection ?: return
        if (cancellingSelection) return
        if (current.phase == NativeMediaPhase.PICKING && current.launched) {
            // Keep the registry's original target until its outstanding result returns.
            selection = current.copy(discardResult = true); publish(); persistLater(); return
        }
        val cancelledUpload = upload
        upload?.cancel(); upload = null
        if (current.receipts.isEmpty()) {
            selection = null; publish(); persistLater()
            if (!current.kind.profileImage) viewModelScope.launch {
                try {
                    cancelledUpload?.join()
                    val importId = current.captureId ?: current.id
                    if (current.kind == NativeMediaKind.WORKBENCH_DRAFT) workbench?.releaseImportIfUnused(importId)
                    else companion?.releaseLocalImportIfUnused(importId)
                    discardCameraOutput(current)
                }
                catch (error: Exception) { if (error is CancellationException) throw error; report(NativeMediaError.STORAGE) }
            }
            return
        }
        cancellingSelection = true
        selection = current.copy(phase = NativeMediaPhase.UPLOADING, discardResult = true)
        publish()
        viewModelScope.launch {
            try {
                cancelledUpload?.join()
                companion?.awaitDraftPersistence() ?: throw NativeMediaProblem(NativeMediaError.OWNER)
                if (selection?.id == current.id) selection = null
                try { persist() }
                catch (error: NativeMediaProblem) { selection = current.copy(phase = NativeMediaPhase.INTERRUPTED); throw error }
                publish()
                retireDeliveryLater(current)
                if (current.kind == NativeMediaKind.ATTACHMENTS) companion?.releaseLocalImportIfUnused(current.captureId ?: current.id)
                discardCameraOutput(current)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                selection = current.copy(phase = NativeMediaPhase.INTERRUPTED)
                interrupt(problem(error, NativeMediaError.STORAGE)); persistLater()
            } finally { cancellingSelection = false }
        }
    }

    private fun runUpload() {
        if (upload?.isActive == true) return
        upload = viewModelScope.launch {
            val original = selection ?: return@launch
            try {
                persist()
                if (!original.kind.profileImage) {
                    val attachments = if (original.camera) {
                        if (!original.cameraReady) throw NativeMediaProblem(NativeMediaError.CAMERA)
                        val captureId = original.captureId ?: throw NativeMediaProblem(NativeMediaError.OWNER)
                        val imported = draftStore.importCamera(captureId, original.maximum)
                        cameraAlbumResult = NativeCameraAlbumResult(original.id, captureId, imported.albumSaved, imported.albumFailed)
                        publish(); persist()
                        imported.error?.let { throw NativeMediaProblem(it) }
                        imported.attachments
                    } else draftStore.importSelection(original.id, original.uris.map(Uri::parse), original.imagesOnly, original.maximum)
                    if (original.kind == NativeMediaKind.WORKBENCH_DRAFT) {
                        val service = workbench ?: throw NativeMediaProblem(NativeMediaError.OWNER)
                        val target = original.workTarget ?: throw NativeMediaProblem(NativeMediaError.OWNER)
                        service.deliverLocalAttachments(target, original.id, attachments)
                        service.awaitDraftPersistence()
                    } else {
                        val service = companion ?: throw NativeMediaProblem(NativeMediaError.OWNER)
                        val owner = original.ownerId ?: throw NativeMediaProblem(NativeMediaError.OWNER)
                        // Preserve already-uploaded receipts from a pre-local-draft version without uploading the rest.
                        val oldUploads = original.receipts.filterNot { it.applied }.map { NativeCompanionAttachment(it.id, it.name, it.mediaType, it.bytes, it.url) }
                        if (oldUploads.isNotEmpty()) service.deliverMediaAttachments(owner, original.id, oldUploads)
                        val uploadedIndices = original.receipts.map { it.index }.toSet()
                        service.deliverLocalAttachments(owner, original.id, attachments.filterIndexed { index, _ -> index !in uploadedIndices })
                        service.awaitDraftPersistence()
                    }
                    val completed = selection?.takeIf { it.id == original.id } ?: return@launch
                    selection = null
                    try { persist() } catch (error: NativeMediaProblem) { selection = completed.copy(phase = NativeMediaPhase.INTERRUPTED); throw error }
                    publish()
                    if (original.kind == NativeMediaKind.WORKBENCH_DRAFT) {
                        try { workbench?.retireMediaDelivery(original.workTarget!!, original.id) }
                        catch (error: NativeApiException) { android.util.Log.w("DroyNativeMedia", "MEDIA_DELIVERY_PROOF_CLEANUP_FAILED:${error.code}") }
                    } else retireDeliveryLater(original)
                    try { discardCameraOutput(original) }
                    catch (error: Exception) { if (error is CancellationException) throw error; report(NativeMediaError.STORAGE) }
                    val importId = original.captureId ?: original.id
                    if (original.kind == NativeMediaKind.WORKBENCH_DRAFT) workbench?.releaseImportIfUnused(importId)
                    else companion?.releaseLocalImportIfUnused(importId)
                    return@launch
                }
                val service = companion ?: throw NativeMediaProblem(NativeMediaError.OWNER)
                for ((index, address) in original.uris.withIndex()) {
                    val current = selection?.takeIf { it.id == original.id } ?: return@launch
                    ownerFailure(current.ownerId, current.kind)?.let { throw NativeMediaProblem(it) }
                    var receipt = current.receipts.firstOrNull { it.index == index }
                    if (receipt == null) {
                        val document = readDocument(Uri.parse(address), current.imagesOnly)
                        val saved = if (current.ownerId == null) service.uploadUserAvatar(document.bytes, document.mediaType, document.name)
                            else service.uploadAttachment(current.ownerId, document.bytes, document.mediaType, document.name)
                        if (!NativeMediaPolicy.ownedUrl(current.ownerId, saved.id, saved.url)) throw NativeMediaProblem(NativeMediaError.OWNER)
                        receipt = NativeMediaReceipt(index, saved.id, saved.name, saved.mediaType, saved.bytes, saved.url)
                        selection = selection?.takeIf { it.id == original.id }?.copy(receipts = current.receipts + receipt) ?: return@launch
                        persist()
                    }
                    val admitted = receipt ?: throw NativeMediaProblem(NativeMediaError.UPLOAD)
                    val result = NativeAvatarResult(original.id, current.ownerId, admitted.url, admitted.id)
                    val results = if (current.kind == NativeMediaKind.BACKGROUND) backgrounds else avatars
                    results[NativeMediaPolicy.ownerKey(current.ownerId)] = result
                }
                val completed = selection?.takeIf { it.id == original.id }
                if (completed != null) selection = null
                try { persist() }
                catch (error: NativeMediaProblem) {
                    selection = completed?.copy(phase = NativeMediaPhase.INTERRUPTED)
                    throw error
                }
                _state.update { it.copy(error = null) }; publish()
                retireDeliveryLater(original)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (selection?.id == original.id) {
                    selection = selection?.copy(phase = NativeMediaPhase.INTERRUPTED)
                    interrupt(problem(error, NativeMediaError.UPLOAD)); persistLater()
                }
            }
        }
    }

    private suspend fun readDocument(uri: Uri, imageOnly: Boolean): SelectedDocument = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        if (!NativeMediaPolicy.contentUri(uri.toString())) throw NativeMediaProblem(NativeMediaError.READ)
        val provider = app.packageManager.resolveContentProvider(uri.authority ?: "", 0)
        if (provider?.applicationInfo == null || provider.applicationInfo.uid == Process.myUid()
            || app.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED)
            throw NativeMediaProblem(NativeMediaError.READ)
        val resolver = app.contentResolver
        var name: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn) && cursor.getLong(sizeColumn) > NativeMediaPolicy.MAX_BYTES)
                    throw NativeMediaProblem(NativeMediaError.TOO_LARGE)
            }
        }
        val type = MediaInputPolicy.mediaType(resolver.getType(uri))
        if (imageOnly && !type.startsWith("image/")) throw NativeMediaProblem(NativeMediaError.READ)
        val bytes = resolver.openInputStream(uri)?.use(NativeMediaPolicy::readBounded) ?: throw NativeMediaProblem(NativeMediaError.READ)
        SelectedDocument(bytes, MediaInputPolicy.filename(name), type)
    }

    fun consumeAvatar(ownerId: String?, resultId: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        val key = NativeMediaPolicy.ownerKey(ownerId)
        if (avatars[key]?.id != resultId) return
        avatars.remove(key); publish(); persistLater()
    }
    fun consumeBackground(ownerId: String?, resultId: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        val key = NativeMediaPolicy.ownerKey(ownerId)
        if (backgrounds[key]?.id != resultId) return
        backgrounds.remove(key); publish(); persistLater()
    }
    fun dismissCameraAlbumResult() {
        if (NativeCompanionWriteBarrier.frozen) return
        cameraAlbumResult = null; publish(); persistLater() }
    private suspend fun discardCameraOutput(operation: NativeMediaSelection) {
        if (!operation.camera) return
        withContext(Dispatchers.IO) { NativeCameraInputHost.discardOutput(getApplication(), operation.captureId ?: throw NativeMediaProblem(NativeMediaError.OWNER)) }
    }
    private fun discardCameraOutputLater(operation: NativeMediaSelection) {
        if (!operation.camera) return
        viewModelScope.launch {
            try { discardCameraOutput(operation) }
            catch (error: Exception) { if (error is CancellationException) throw error; report(NativeMediaError.STORAGE) }
        }
    }
    private fun cameraFailure(error: NativeCameraInputHost.Failure) = when (error.code) {
        "native_camera_permission" -> NativeMediaError.CAMERA_PERMISSION
        "native_camera_paused" -> NativeMediaError.PAUSED
        else -> NativeMediaError.CAMERA
    }

    private fun retireDeliveryLater(operation: NativeMediaSelection) {
        val owner = operation.ownerId ?: return
        if (operation.kind != NativeMediaKind.ATTACHMENTS) return
        val service = companion ?: return
        viewModelScope.launch {
            try { service.retireMediaDelivery(owner, operation.id) }
            catch (error: NativeApiException) {
                // A retained proof is harmless after durable media retirement; never replay a completed upload to clean it.
                android.util.Log.w("DroyNativeMedia", "MEDIA_DELIVERY_PROOF_CLEANUP_FAILED:${error.code}")
            }
        }
    }

    fun openAttachment(ownerId: String, attachment: NativeCompanionAttachment) {
        if (NativeCompanionWriteBarrier.frozen) return
        if (!NativeMediaPolicy.ownedUrl(ownerId, attachment.id, attachment.url)) { report(NativeMediaError.OWNER); return }
        if (attachment.bytes !in 1..NativeMediaPolicy.MAX_BYTES.toLong()) { report(NativeMediaError.TOO_LARGE); return }
        downloading?.cancel()
        val generation = ++downloadGeneration
        _openRequests.value = null
        visibleOwner = ownerId
        _state.update { it.copy(opening = true, error = null) }
        updateWorkProtection()
        downloading = viewModelScope.launch {
            try {
                val bytes = companion?.loadImage(attachment.url) ?: throw NativeMediaProblem(NativeMediaError.OWNER)
                if (bytes.size.toLong() != attachment.bytes) throw NativeMediaProblem(NativeMediaError.OPEN)
                val file = withContext(Dispatchers.IO) { cache.write(bytes, attachment.name, pinnedFiles()) }
                if (visibleOwner == ownerId && generation == downloadGeneration) _openRequests.value = NativeMediaOpen(UUID.randomUUID().toString(), ownerId, file, MediaInputPolicy.mediaType(attachment.mediaType))
            } catch (error: Exception) { if (error is CancellationException) throw error; report(problem(error, NativeMediaError.OPEN)) }
            finally { if (generation == downloadGeneration) { _state.update { it.copy(opening = false) }; updateWorkProtection() } }
        }
    }
    internal fun opened(id: String, failure: NativeMediaError? = null) {
        if (_openRequests.value?.id == id) _openRequests.value = null
        failure?.let(::report)
    }

    fun playVoice(ownerId: String, voice: NativeCompanionVoice) {
        if (NativeCompanionWriteBarrier.frozen) return
        if (_state.value.playback?.let { it.ownerId == ownerId && it.attachmentId == voice.attachmentId } == true) { stopPlayback(); return }
        stopPlayback(); visibleOwner = ownerId
        val url = voice.url ?: "/droy/companions/${encode(ownerId)}/attachments/${encode(voice.attachmentId)}"
        if (!NativeMediaPolicy.ownedUrl(ownerId, voice.attachmentId, url)) { report(NativeMediaError.OWNER); return }
        _state.update { it.copy(playback = NativeMediaPlayback(ownerId, voice.attachmentId, true), error = null) }
        audioDownload = viewModelScope.launch {
            try {
                val bytes = companion?.loadImage(url) ?: throw NativeMediaProblem(NativeMediaError.OWNER)
                val file = withContext(Dispatchers.IO) { cache.write(bytes, "${UUID.randomUUID()}.audio", pinnedFiles()) }
                if (visibleOwner != ownerId) return@launch
                val audio = MediaPlayer()
                player = audio; playingFile = file
                audio.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                audio.setDataSource(file.absolutePath)
                audio.setOnPreparedListener { ready ->
                    if (player === ready) {
                        try { ready.start(); _state.update { it.copy(playback = NativeMediaPlayback(ownerId, voice.attachmentId, false)) } }
                        catch (_: IllegalStateException) { stopPlayback(); report(NativeMediaError.PLAYBACK) }
                    }
                }
                audio.setOnCompletionListener { if (player === it) stopPlayback() }
                audio.setOnErrorListener { failed, _, _ -> if (player === failed) { stopPlayback(); report(NativeMediaError.PLAYBACK) }; true }
                audio.prepareAsync()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                stopPlayback(); report(problem(error, NativeMediaError.PLAYBACK))
            }
        }
    }

    fun stopPlayback() {
        audioDownload?.cancel(); audioDownload = null
        val previous = player; player = null; playingFile = null
        previous?.release()
        _state.update { it.copy(playback = null) }
    }
    fun onConversationChanged(ownerId: String?) {
        if (visibleOwner == ownerId) return
        visibleOwner = ownerId
        downloadGeneration++
        downloading?.cancel(); downloading = null; _openRequests.value = null
        stopPlayback(); _state.update { it.copy(opening = false) }
        updateWorkProtection()
    }
    fun dismissError() { _state.update { it.copy(error = null) } }
    internal fun report(error: NativeMediaError) { _state.update { it.copy(error = error) } }
    private fun interrupt(error: NativeMediaError) { publish(); _state.update { it.copy(error = error) } }
    private fun ownerFailure(owner: String?, kind: NativeMediaKind): NativeMediaError? {
        if (kind == NativeMediaKind.WORKBENCH_DRAFT) return if (workbench != null && owner != null) null else NativeMediaError.OWNER
        val model = companion ?: return NativeMediaError.OWNER
        if (kind.profileImage && !model.state.value.runtime.ready) return NativeMediaError.RUNTIME
        val valid = if (owner == null) kind == NativeMediaKind.AVATAR else model.state.value.companions.any { it.id == owner }
        return if (valid) null else NativeMediaError.OWNER
    }
    private fun pinnedFiles(): Set<String> = listOfNotNull(playingFile?.absolutePath, _openRequests.value?.file?.absolutePath).toSet()
    private fun publish() {
        _state.update { it.copy(phase = selection?.phase, ownerId = selection?.ownerId,
            canRetry = selection?.phase == NativeMediaPhase.INTERRUPTED, avatarResults = avatars.toMap(), backgroundResults = backgrounds.toMap(), kind = selection?.kind,
            camera = selection?.camera == true, cameraAlbumResult = cameraAlbumResult) }
        updateWorkProtection()
    }
    private fun updateWorkProtection() {
        if (activityTracker.update(workIdentity, selection?.phase, _state.value.opening)) backgroundChanged()
    }
    private fun problem(error: Exception, fallback: NativeMediaError): NativeMediaError = when (error) {
        is NativeMediaProblem -> error.problem
        is SecurityException -> NativeMediaError.READ
        is NativeApiException -> when (error.code) {
            "response_too_large", "COMPANION_REQUEST_TOO_LARGE" -> NativeMediaError.TOO_LARGE
            "runtime_unavailable", "NATIVE_SERVICE_NOT_READY", "service_temporarily_unavailable" -> NativeMediaError.RUNTIME
            else -> fallback
        }
        is IOException -> if (fallback == NativeMediaError.UPLOAD) NativeMediaError.READ else fallback
        else -> fallback
    }
    private fun encode(value: String) = io.droyagent.app.nativeui.data.NativeApiClient.encodeSegment(value)

    private fun restore(): JSONObject? {
        val candidates = listOf(savedState.get<String>(STATE_KEY), preferences.getString(STATE_KEY, null)).mapNotNull { raw ->
            if (raw == null) null else if (raw.length > 262144) { restoreFailed = true; null }
            else try { JSONObject(raw) } catch (_: org.json.JSONException) { restoreFailed = true; null }
        }
        val value = candidates.maxByOrNull { it.optLong("revision") }
        revision = value?.optLong("revision") ?: 0
        persistedRevision = preferences.getString(STATE_KEY, null)?.let { raw ->
            try { JSONObject(raw).optLong("revision") } catch (_: org.json.JSONException) { -1 }
        } ?: -1
        return value
    }
    private suspend fun persist() {
        val version = ++revision
        val value = JSONObject().put("revision", version).put("selection", selection?.json() ?: JSONObject.NULL)
            .put("cameraAlbumResult", cameraAlbumResult?.json() ?: JSONObject.NULL)
            .put("avatars", JSONArray(avatars.values.map { result -> JSONObject().put("id", result.id).put("ownerId", result.ownerId ?: JSONObject.NULL)
                .put("url", result.url).put("attachmentId", result.attachmentId) }))
            .put("backgrounds", JSONArray(backgrounds.values.map { result -> JSONObject().put("id", result.id).put("ownerId", result.ownerId)
                .put("url", result.url).put("attachmentId", result.attachmentId) })).toString()
        savedState[STATE_KEY] = value
        persistence.withLock {
            if (version <= persistedRevision) return@withLock
            try { NativeMediaImportRetention.commit(getApplication(), value) }
            catch (error: NativeApiException) { throw NativeMediaProblem(NativeMediaError.STORAGE, error) }
            persistedRevision = version
        }
    }
    private fun persistLater() { viewModelScope.launch { try { persist() } catch (error: NativeMediaProblem) { report(error.problem) } } }
    override fun onCleared() {
        stopPlayback()
        if (activityTracker.remove(workIdentity)) backgroundChanged()
        backgroundChanged = {}; companion = null; workbench = null
        super.onCleared()
    }
    companion object {
        private const val STATE_KEY = "native-media-v1"
        private val activityTracker = NativeMediaActivityTracker()
        @JvmStatic fun hasRuntimeWork(): Boolean = activityTracker.hasRuntimeWork()
        @JvmStatic fun isPickerOpen(): Boolean = activityTracker.isPickerOpen()
        internal fun submissionActivity(id: String, active: Boolean) {
            val changed = if (active) activityTracker.update(id, NativeMediaPhase.UPLOADING, false) else activityTracker.remove(id)
            if (changed) NativeRuntimeHost.backgroundWorkChanged()
        }
    }
}
