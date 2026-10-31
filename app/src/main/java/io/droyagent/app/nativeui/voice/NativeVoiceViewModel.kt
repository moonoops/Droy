package io.droyagent.app.nativeui.voice

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.droyagent.app.NativeRuntimeHost
import io.droyagent.app.nativeui.data.NativeRuntimeState
import io.droyagent.app.nativeui.permissions.NativePermissionsState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NativeVoiceViewModel(application: Application) : AndroidViewModel(application) {
    private val coordinator = NativeVoiceCoordinator(NativeVoiceClient(), viewModelScope)
    val state = coordinator.state
    private var observing: Job? = null
    init { inspectRuntime() }
    fun updatePermissions(value: NativePermissionsState) = coordinator.permissions(value)
    fun onAction(action: NativeVoiceAction) {
        if (action == NativeVoiceAction.StartRuntime) {
            try { NativeRuntimeHost.start(getApplication()); inspectRuntime() }
            catch (_: Exception) { coordinator.report("runtime_start_failed") }
        } else coordinator.onAction(action)
    }
    fun startObserving() {
        if (observing?.isActive == true) return
        observing = viewModelScope.launch {
            var previousReady = false
            var previousLocale: String? = null
            while (isActive) {
                inspectRuntime()
                val current = state.value
                if (current.runtime.ready && (!previousReady || previousLocale != current.runtime.locale || current.needsPolling)) coordinator.refresh()
                previousReady = current.runtime.ready; previousLocale = current.runtime.locale
                delay(1000)
            }
        }
    }
    fun stopObserving() { observing?.cancel(); observing = null; coordinator.leave() }
    private fun inspectRuntime() {
        val value = NativeRuntimeHost.inspect(getApplication())
        coordinator.runtime(NativeRuntimeState(value.ready, value.active, value.canStart, value.status, value.locale))
    }
}
