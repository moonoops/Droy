package io.droyagent.app.nativeui.companion.creation

import io.droyagent.app.nativeui.companion.maintenance.NativeCompanionWriteBarrier
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.droyagent.app.NativeRuntimeHost
import io.droyagent.app.nativeui.data.NativeRuntimeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class NativeCompanionCreationViewModel(application: Application) : AndroidViewModel(application) {
    private val workIdentity = UUID.randomUUID().toString()
    private var cleared = false
    private val files = NativeCreationFileStore(application)
    private val client = NativeCreationClient(files)
    private val coordinator = NativeCreationCoordinator(client, NativeCreationPreferences(application), files, viewModelScope,
        activityChanged = { value -> if (!cleared && tracker.update(workIdentity, value)) NativeRuntimeHost.backgroundWorkChanged() })
    val state = coordinator.state
    private var restoring = coordinator.restore()
    private var observing: Job? = null
    init { inspectRuntime() }
    internal suspend fun freezeForCompanionDeletion() {
        stopObserving()
        io.droyagent.app.nativeui.companion.maintenance.drainCompanionWriters(viewModelScope)
        check(!state.value.restoreError) { "COMPANION_MAINTENANCE_STORAGE" }
        coordinator.flushForCompanionDeletion()
    }

    fun startObserving() {
        if (NativeCompanionWriteBarrier.frozen) return
        if (observing?.isActive == true) return
        observing = viewModelScope.launch {
            restoring.join()
            var wasReady = false
            var locale: String? = null
            var nextRead = 0L
            while (isActive) {
                inspectRuntime()
                val current = state.value
                if (current.runtime.ready && !current.busy && !current.loading && !current.restoreError) {
                    if (!wasReady || locale != current.runtime.locale) {
                        coordinator.refresh(); locale = current.runtime.locale
                        nextRead = SystemClock.elapsedRealtime() + 5_000
                    } else if (current.journal?.draftId != null && (current.creating || current.journal.pendingCancel) && !current.checking && SystemClock.elapsedRealtime() >= nextRead) {
                        coordinator.reconcile(); nextRead = SystemClock.elapsedRealtime() + 5_000
                    }
                }
                wasReady = current.runtime.ready
                delay(750)
            }
        }
    }
    fun stopObserving() { observing?.cancel(); observing = null }
    fun refresh() {
        if (NativeCompanionWriteBarrier.frozen) return
        viewModelScope.launch {
            if (state.value.restoreError) restoring = coordinator.restore()
            restoring.join(); inspectRuntime()
            if (state.value.runtime.ready && !state.value.restoreError) coordinator.refresh()
        }
    }
    fun startRuntime() {
        try { NativeRuntimeHost.start(getApplication()); inspectRuntime() }
        catch (_: Exception) { coordinator.report("runtime_start_failed") }
    }
    fun page(value: NativeCreationPage) = coordinator.page(value)
    fun selectTemplate(value: NativeCreationTemplate) { if (!NativeCompanionWriteBarrier.frozen) coordinator.selectTemplate(value) }
    fun edit(value: NativeCreationEditor) { if (!NativeCompanionWriteBarrier.frozen) coordinator.edit { value } }
    fun leaveEditor(save: Boolean, onDone: () -> Unit) {
        if (NativeCompanionWriteBarrier.frozen) return
        coordinator.leaveEditor(save, onDone) }
    fun resetAvatar() { if (!NativeCompanionWriteBarrier.frozen) coordinator.resetAvatar() }
    fun create() {
        if (NativeCompanionWriteBarrier.frozen) return
        coordinator.create() }
    fun retryCreate() {
        if (NativeCompanionWriteBarrier.frozen) return
        coordinator.retryCreate() }
    fun cancelCreation() {
        if (NativeCompanionWriteBarrier.frozen) return
        coordinator.cancelCreation() }
    fun resetClosed() {
        if (NativeCompanionWriteBarrier.frozen) return
        coordinator.resetClosed() }
    fun acknowledgeCreated(id: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        coordinator.acknowledgeCreated(id) }
    fun retryImage() {
        if (NativeCompanionWriteBarrier.frozen) return
        coordinator.retryImage() }
    fun discardImage() {
        if (NativeCompanionWriteBarrier.frozen) return
        coordinator.discardImage() }
    fun dismissError() = coordinator.dismissError()
    suspend fun loadImage(url: String) = client.loadImage(url)
    internal suspend fun prepareImage(kind: NativeCreationImageKind): NativeCreationImageRequest? {
        if (NativeCompanionWriteBarrier.frozen) return null
        restoring.join()
        return coordinator.prepareImage(kind, NativeRuntimeHost.isFilePickerOpen())
    }
    internal suspend fun pendingPicker(): NativeCreationImageOperation? {
        restoring.join()
        return state.value.image?.takeIf { it.phase == NativeCreationImagePhase.PICKING }
    }
    internal fun imagePicked(operationId: String, uri: String?) {
        if (NativeCompanionWriteBarrier.frozen) return
        viewModelScope.launch {
            restoring.join()
            coordinator.selectedImage(operationId, uri)
        }
    }
    internal fun cancelPreparedImage(id: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        coordinator.selectedImage(id, null) }
    internal fun pickerFailed(id: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        coordinator.selectedImage(id, null); coordinator.report("creation_picker_unavailable") }
    private fun inspectRuntime() {
        val value = NativeRuntimeHost.inspect(getApplication())
        coordinator.runtime(NativeRuntimeState(value.ready, value.active, value.canStart, value.status, value.locale))
    }
    override fun onCleared() { cleared = true; if (tracker.remove(workIdentity)) NativeRuntimeHost.backgroundWorkChanged(); super.onCleared() }
    companion object {
        private val tracker = NativeCreationActivityTracker()
        @JvmStatic fun isPickerOpen() = tracker.isPickerOpen()
        @JvmStatic fun hasRuntimeWork() = tracker.hasRuntimeWork()
    }
}
