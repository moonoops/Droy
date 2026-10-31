package io.droyagent.app.nativeui.navigation

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.droyagent.app.AudioPermissionActivity
import io.droyagent.app.NativeExternalNavigationHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The durable target survives recreation; the resumed destination supplies the acknowledgement. */
class NativeExternalNavigationViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("native_external_navigation", 0)
    private var wakeOwner: (Long) -> Boolean = { false }
    private val coordinator = NativeExternalNavigationCoordinator(
        NativeExternalNavigationClient(application) { wakeOwner(it) },
        NativeExternalNavigationJsonStore(
            read = { withContext(Dispatchers.IO) { preferences.getString("pending", null) } },
            write = { value -> withContext(Dispatchers.IO) { preferences.edit().putString("pending", value).commit() } }), viewModelScope)
    val state = coordinator.state
    private val inputFailure = MutableStateFlow<String?>(null)
    val error = inputFailure.asStateFlow()
    private var conditions = NativeExternalNavigationConditions()
    private var hostResumed = false
    private var modalBlocked = false
    private var resumedAudio: String? = null
    private val activities = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity !is AudioPermissionActivity || activity.isFinishing || activity.isDestroyed) return
            resumedAudio = activity.intent.getStringExtra("captureId")
            updateHostConditions()
        }
        override fun onActivityPaused(activity: Activity) {
            if (activity is AudioPermissionActivity && resumedAudio == activity.intent.getStringExtra("captureId")) {
                resumedAudio = null; updateHostConditions()
            }
        }
        override fun onActivityCreated(activity: Activity, saved: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, saved: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    init {
        application.registerActivityLifecycleCallbacks(activities)
        run { coordinator.restore() }
        viewModelScope.launch {
            state.collect { current ->
                val request = current.request ?: return@collect
                val target = current.resolved as? NativeExternalResolvedTarget.AudioAuthorization ?: return@collect
                if (current.phase == NativeExternalNavigationPhase.READY && resumedAudio == target.captureId)
                    coordinator.ackOpened(request.id, NativeExternalNavigationOpened(target.surface, dataReady = true, resumed = true))
            }
        }
    }
    fun bindWakeOwner(owner: (Long) -> Boolean) { wakeOwner = owner }
    fun accept(intent: Intent) {
        val input = try {
            val extras = intent.extras
            val values = keys.filter { extras?.containsKey(it) == true }.associateWith { key ->
                @Suppress("DEPRECATION")
                extras?.get(key)
            }
            NativeExternalNavigationParser.parse(values)
        } catch (_: android.os.BadParcelableException) { NativeExternalNavigationInput.Invalid(NativeExternalParseFailure.MALFORMED) }
        when (input) {
            NativeExternalNavigationInput.None -> Unit
            is NativeExternalNavigationInput.Invalid -> inputFailure.value = "native_external_navigation_invalid"
            is NativeExternalNavigationInput.Target -> accept(input.target)
        }
    }
    fun accept(target: NativeExternalNavigationTarget) = run { coordinator.accept(target); inputFailure.value = null }
    fun updateEnvironment(setupComplete: Boolean, runtimeReady: Boolean, permissionFlowIdle: Boolean) {
        conditions = conditions.copy(setupComplete = setupComplete, runtimeReady = runtimeReady, permissionFlowIdle = permissionFlowIdle)
        updateHostConditions()
    }
    fun setHostResumed(value: Boolean) { hostResumed = value; updateHostConditions() }
    fun setModalBlocked(value: Boolean) { if (modalBlocked != value) { modalBlocked = value; updateHostConditions() } }
    private fun updateHostConditions() {
        val next = conditions.copy(foreground = hostResumed || resumedAudio != null,
            permissionFlowIdle = conditions.permissionFlowIdle && !modalBlocked)
        run { coordinator.updateConditions(next) }
    }
    fun acknowledge(id: String, opened: NativeExternalNavigationOpened) = run { coordinator.ackOpened(id, opened) }
    fun retry() = run { inputFailure.value = null; coordinator.retry() }
    fun dismiss() {
        inputFailure.value = null
        state.value.request?.id?.let { id -> run { coordinator.dismiss(id) } }
    }
    fun openAudio(activity: Activity, target: NativeExternalResolvedTarget.AudioAuthorization) = run {
        if (!NativeExternalNavigationHost.pendingAudio(getApplication(), target.captureId)) { coordinator.retry(); return@run }
        activity.startActivity(Intent(activity, AudioPermissionActivity::class.java).putExtra("captureId", target.captureId))
    }
    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                inputFailure.value = if (error is NativeExternalNavigationStorageException) "native_external_navigation_storage" else "native_external_navigation_failed"
            }
        }
    }
    override fun onCleared() {
        getApplication<Application>().unregisterActivityLifecycleCallbacks(activities)
        super.onCleared()
    }
    companion object {
        private val keys = listOf("sessionId", "petTaskId", "petRequestId", "companionId", "browserSessionId", "notificationManagement",
            "voiceWakeEventId", "voiceWakeTarget", "audioCaptureId", "mediaImportId", "destination")
    }
}
