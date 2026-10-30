package io.droyagent.app.nativeui.transcript

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.droyagent.app.NativeRuntimeHost
import io.droyagent.app.nativeui.data.NativeReadingPosition
import io.droyagent.app.nativeui.data.NativeRuntimeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NativeTranscriptViewModel(application: Application) : AndroidViewModel(application) {
    private val coordinator = NativeTranscriptCoordinator(NativeTranscriptClient(), viewModelScope)
    val state = coordinator.state
    private var observing: Job? = null
    private var endpoint: NativeRuntimeHost.Endpoint? = null

    fun open(parentSessionId: String, childSessionId: String, title: String?) =
        coordinator.open(NativeSubagentTranscriptAddress(parentSessionId, childSessionId), title)
    fun loadOlder() = coordinator.loadOlder()
    fun refresh() = coordinator.refresh()
    fun setReadingPosition(address: NativeSubagentTranscriptAddress, position: NativeReadingPosition) = coordinator.setReadingPosition(address, position)
    fun startObserving() {
        if (observing?.isActive == true) return
        val value = NativeRuntimeHost.inspect(getApplication())
        coordinator.runtime(NativeRuntimeState(value.ready, value.active, value.canStart, value.status, value.locale))
        endpoint = if (value.ready) NativeRuntimeHost.connection() else null
        coordinator.startObserving()
        observing = viewModelScope.launch {
            while (isActive) {
                val current = NativeRuntimeHost.inspect(getApplication())
                coordinator.runtime(NativeRuntimeState(current.ready, current.active, current.canStart, current.status, current.locale))
                if (current.ready && endpoint?.isCurrent() != true) {
                    endpoint = NativeRuntimeHost.connection(); coordinator.refresh()
                }
                if (!current.ready) endpoint = null
                delay(1000)
            }
        }
    }
    fun stopObserving() { observing?.cancel(); observing = null; endpoint = null; coordinator.stopObserving() }
}
