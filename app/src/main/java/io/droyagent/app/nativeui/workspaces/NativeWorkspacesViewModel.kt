package io.droyagent.app.nativeui.workspaces

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.droyagent.app.NativeRuntimeHost
import io.droyagent.app.nativeui.data.NativeRuntimeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NativeWorkspacesViewModel(application: Application) : AndroidViewModel(application) {
    private val coordinator = NativeWorkspacesCoordinator(NativeWorkspacesClient(),
        NativeWorkspacesPreferences(application.getSharedPreferences("native_workspaces", Context.MODE_PRIVATE)), viewModelScope)
    val state = coordinator.state
    private var observing: Job? = null
    private var endpoint: NativeRuntimeHost.Endpoint? = null
    init { inspectRuntime() }
    fun onAction(action: NativeWorkspacesAction) { coordinator.onAction(action) }
    fun refresh() { coordinator.refresh() }
    fun startRuntime() {
        try { NativeRuntimeHost.start(getApplication()); inspectRuntime() }
        catch (_: Exception) { coordinator.report("runtime_start_failed") }
    }
    fun startObserving() {
        if (observing?.isActive == true) return
        observing = viewModelScope.launch {
            var locale: String? = null
            while (isActive) {
                inspectRuntime()
                val runtime = state.value.runtime
                if (runtime.ready && (endpoint?.isCurrent() != true || locale != runtime.locale)) {
                    endpoint = NativeRuntimeHost.connection(); locale = runtime.locale; coordinator.refresh(); coordinator.restoreDirectory()
                }
                if (!runtime.ready) endpoint = null
                delay(1000)
            }
        }
    }
    fun stopObserving() { observing?.cancel(); observing = null; endpoint = null; coordinator.stopReads() }
    private fun inspectRuntime() {
        val value = NativeRuntimeHost.inspect(getApplication())
        coordinator.runtime(NativeRuntimeState(value.ready, value.active, value.canStart, value.status, value.locale))
    }
}
