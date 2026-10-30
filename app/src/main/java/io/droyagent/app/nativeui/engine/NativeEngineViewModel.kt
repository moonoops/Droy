package io.droyagent.app.nativeui.engine

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
import org.json.JSONObject

class NativeEngineViewModel(application: Application) : AndroidViewModel(application) {
    private val coordinator = NativeEngineCoordinator(NativeEngineClient(), NativeEngineDraftStore(application.getSharedPreferences("native-engine-drafts", Context.MODE_PRIVATE)), viewModelScope)
    val state = coordinator.state
    private var observing: Job? = null
    private var endpoint: NativeRuntimeHost.Endpoint? = null
    init {
        val value = NativeRuntimeHost.inspect(application)
        coordinator.runtime(NativeRuntimeState(value.ready, value.active, value.canStart, value.status, value.locale))
    }
    fun bindSession(sessionId: String?) = coordinator.bindSession(sessionId)
    fun bindDraft(draftId: String?) = coordinator.bindDraft(draftId)
    fun captureSelection(draftId: String): JSONObject? = coordinator.captureSelection(draftId)
    fun stagePreset(draftId: String, presetId: String) = coordinator.stagePreset(draftId, presetId)
    fun discardDraftSelection() = coordinator.discardDraftSelection()
    suspend fun prepareSession(sessionId: String, token: JSONObject?) = coordinator.prepareSession(sessionId, token)
    fun onAction(action: NativeEngineAction) = coordinator.onAction(action)
    fun handleCompactCommand(text: String): Boolean {
        if (text.trim() != "/compact") return false
        val target = state.value.let { NativeEngineTarget(it.sessionId, it.draftId) }
        coordinator.onAction(NativeEngineAction.CompactAt(target)); return true
    }
    fun refreshSession() = coordinator.refreshSession()
    fun startObserving() {
        if (observing?.isActive == true) return
        observing = viewModelScope.launch {
            var locale: String? = null
            coordinator.resumeCompact()
            while (isActive) {
                val value = NativeRuntimeHost.inspect(getApplication())
                coordinator.runtime(NativeRuntimeState(value.ready, value.active, value.canStart, value.status, value.locale))
                if (value.ready && (endpoint?.isCurrent() != true || locale != value.locale)) {
                    endpoint = NativeRuntimeHost.connection(); locale = value.locale; coordinator.refresh()
                }
                if (!value.ready) endpoint = null
                delay(1000)
            }
        }
    }
    fun stopObserving() { observing?.cancel(); observing = null; endpoint = null; coordinator.stopReads() }
}
