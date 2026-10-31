package io.droyagent.app.nativeui.schedule

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

class NativeSchedulesViewModel(application: Application) : AndroidViewModel(application) {
    private val coordinator = NativeSchedulesCoordinator(NativeSchedulesClient(), viewModelScope)
    val state = coordinator.state
    private var observing: Job? = null

    init { inspectRuntime() }
    fun startObserving() {
        if (observing?.isActive == true) return
        observing = viewModelScope.launch {
            var nextCatalog = 0L
            var ready = false
            while (isActive) {
                inspectRuntime()
                val current = state.value
                if (current.runtime.ready && (!ready || SystemClock.elapsedRealtime() >= nextCatalog) && !current.loading && !current.saving) {
                    coordinator.refresh()
                    if (!ready) coordinator.refreshTargets()
                    nextCatalog = SystemClock.elapsedRealtime() + 5_000
                }
                ready = current.runtime.ready
                delay(750)
            }
        }
    }
    fun stopObserving() { observing?.cancel(); observing = null }
    fun startRuntime() {
        try { NativeRuntimeHost.start(getApplication()); inspectRuntime() }
        catch (_: Exception) { coordinator.report("runtime_start_failed") }
    }
    fun refresh() { coordinator.refresh(); coordinator.refreshTargets() }
    fun refreshTargets() { coordinator.refreshTargets() }
    fun open(key: NativeScheduleKey) = coordinator.open(key)
    fun beginEdit() = coordinator.beginEdit()
    fun beginCreate() = coordinator.beginCreate()
    fun updateDraft(value: NativeScheduleDraft) = coordinator.edit { value }
    fun reloadEditor() { coordinator.refresh(rebaseEditor = true) }
    fun leaveEditor() = coordinator.leaveEditor()
    fun catalogPage() = coordinator.catalogPage()
    fun historyPage() = coordinator.historyPage()
    fun detailPage() = coordinator.detailPage()
    fun refreshHistory() { coordinator.history(reset = true) }
    fun loadMoreHistory() { coordinator.history(reset = false) }
    fun save() { coordinator.save() }
    fun delete() { coordinator.delete() }
    fun reviewCatalog() = coordinator.reviewCatalog()
    fun acknowledgeReview() = coordinator.acknowledgeReview()
    fun dismissError() = coordinator.dismissError()
    fun consumeFeedback(value: String) = coordinator.consumeFeedback(value)

    private fun inspectRuntime() {
        val value = NativeRuntimeHost.inspect(getApplication())
        coordinator.runtime(NativeRuntimeState(value.ready, value.active, value.canStart, value.status, value.locale))
    }
}
