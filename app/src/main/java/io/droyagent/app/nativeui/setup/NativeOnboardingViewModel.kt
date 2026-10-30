package io.droyagent.app.nativeui.setup

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.droyagent.app.NativeRuntimeHost
import io.droyagent.app.nativeui.companion.data.NativeUserProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class NativeOnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val coordinator = NativeOnboardingCoordinator(NativeOnboardingClient(application), NativeSetupPreferences(application), viewModelScope)
    val state = coordinator.state
    private var observing: Job? = null

    init { coordinator.loadDraft(); coordinator.refreshLocal() }
    fun startObserving() {
        if (observing?.isActive == true) return
        observing = viewModelScope.launch {
            var nextLive = 0L
            while (isActive) {
                coordinator.refreshLocal().join()
                val current = state.value
                val route = current.route
                if (route?.completed == true) break
                if (route?.required == true && route.environment.ready && route.step >= 3 && !current.busy && !current.checking
                    && SystemClock.elapsedRealtime() >= nextLive) {
                    coordinator.refreshLive()
                    nextLive = SystemClock.elapsedRealtime() + 5_000
                }
                delay(750)
            }
        }
    }
    fun stopObserving() { observing?.cancel(); observing = null }
    fun refresh() {
        viewModelScope.launch { coordinator.refreshLocal().join(); coordinator.refreshLive() }
    }
    fun startRuntime() {
        try { NativeRuntimeHost.start(getApplication()); refresh() }
        catch (_: Exception) { coordinator.report("runtime_start_failed") }
    }
    fun name(value: String) = coordinator.name(value)
    fun avatar(value: String) = coordinator.avatar(value)
    fun previous(step: Int) { coordinator.previous(step) }
    fun next(step: Int, saveProfile: suspend (String, String?) -> NativeUserProfile) { coordinator.next(step, saveProfile) }
    fun complete() { coordinator.complete() }
    fun dismissError() = coordinator.dismissError()
}
