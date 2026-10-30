package io.droyagent.app.nativeui.memory

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

class NativeMemoryViewModel(application: Application) : AndroidViewModel(application) {
    private val coordinator = NativeMemoryCoordinator(NativeMemoryClient(), viewModelScope)
    val state = coordinator.state
    private var observing: Job? = null
    init { inspectRuntime() }
    fun startObserving() {
        if (observing?.isActive == true) return
        observing = viewModelScope.launch {
            var nextRefresh = 0L
            var wasReady = false
            var first = true
            while (isActive) {
                inspectRuntime()
                val current = state.value
                if (current.runtime.ready && (!wasReady || first || SystemClock.elapsedRealtime() >= nextRefresh) && !current.loading && !current.busy) {
                    coordinator.refresh(includePage = first || !wasReady)
                    first = false
                    nextRefresh = SystemClock.elapsedRealtime() + 5_000
                }
                wasReady = current.runtime.ready
                delay(750)
            }
        }
    }
    fun stopObserving() { observing?.cancel(); observing = null }
    fun startRuntime() {
        try { NativeRuntimeHost.start(getApplication()); inspectRuntime() }
        catch (_: Exception) { coordinator.report("runtime_start_failed") }
    }
    fun refresh() { coordinator.refresh() }
    fun page(value: NativeMemoryPage) = coordinator.page(value)
    fun filter(value: NativeMemoryFilter) = coordinator.filter(value)
    fun loadMore() { coordinator.query(reset = false) }
    fun open(id: String) = coordinator.open(id)
    fun create() = coordinator.create()
    fun edit() = coordinator.edit()
    fun draft(value: NativeMemoryDraft) = coordinator.draft(value)
    fun leaveEditor() = coordinator.leaveEditor()
    fun resumeEditor() = coordinator.resumeEditor()
    fun reloadEditor() = coordinator.reloadEditor()
    fun save() { coordinator.save() }
    fun forget() { coordinator.forget() }
    fun settings(key: String, enabled: Boolean) { coordinator.settings(key, enabled) }
    fun enableAllApplicationCapture() { coordinator.enableAllApplicationCapture() }
    fun captureWithPermission(key: String, enabled: Boolean) { coordinator.captureWithPermission(key, enabled) }
    fun capture(key: String, enabled: Boolean) { coordinator.capture(key, enabled) }
    fun openPermission(source: String) { coordinator.openPermission(source) }
    fun grant(endpoint: NativeMemoryEndpoint, categories: List<String>) { coordinator.grant(endpoint, categories) }
    fun revoke(endpoint: NativeMemoryEndpoint) { coordinator.revoke(endpoint) }
    fun maintenance(id: String, retry: Boolean) { coordinator.maintenance(id, retry) }
    fun appScope(id: String, selection: Int) = coordinator.appScope(id, selection)
    fun saveApps() { coordinator.saveApps() }
    fun retryConfirmedIdentity() { coordinator.retryConfirmedIdentity() }
    fun acknowledgeReview() = coordinator.acknowledgeReview()
    fun dismissError() = coordinator.dismissError()
    fun consumeFeedback(code: String) = coordinator.consumeFeedback(code)
    private fun inspectRuntime() {
        val current = NativeRuntimeHost.inspect(getApplication())
        coordinator.runtime(NativeRuntimeState(current.ready, current.active, current.canStart, current.status, current.locale))
    }
}
