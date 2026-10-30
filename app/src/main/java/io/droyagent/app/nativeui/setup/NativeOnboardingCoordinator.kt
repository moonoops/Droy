package io.droyagent.app.nativeui.setup

import io.droyagent.app.nativeui.companion.data.NativeUserProfile
import io.droyagent.app.nativeui.data.NativeApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class NativeOnboardingCoordinator(
    private val repository: NativeOnboardingRepository,
    private val drafts: NativeSetupDraftStore,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(NativeOnboardingState())
    val state: StateFlow<NativeOnboardingState> = mutable.asStateFlow()
    private var localGeneration = 0L
    private var liveGeneration = 0L
    private var localJob: Job? = null

    fun loadDraft() {
        if (state.value.draftLoaded) return
        try { mutable.update { it.copy(draft = drafts.read() ?: NativeSetupIdentityDraft(), draftLoaded = true) } }
        catch (_: Exception) { mutable.update { it.copy(draftLoaded = true, error = "storage_error") } }
    }

    fun refreshLocal(): Job {
        if (state.value.busy) return scope.launch { }
        localJob?.takeIf { it.isActive }?.let { return it }
        val generation = ++localGeneration
        val job = scope.launch {
            try {
                val value = repository.route()
                if (generation == localGeneration) applyRoute(value)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == localGeneration) mutable.update { it.copy(localLoading = false, localError = code(error, "setup_state_unavailable")) }
            }
        }
        localJob = job
        return job
    }

    fun refreshLive(): Job {
        val route = state.value.route
        if (state.value.busy || route == null || !route.required || !route.environment.ready) return scope.launch { }
        val generation = ++liveGeneration
        mutable.update { it.copy(checking = true) }
        return scope.launch {
            try {
                val value = repository.status()
                if (generation == liveGeneration) applyLive(value)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == liveGeneration) mutable.update { it.copy(checking = false, liveFresh = false, statusError = code(error)) }
            }
        }
    }

    fun name(value: String) { edit(state.value.draft.copy(name = value, edited = true)) }
    fun avatar(value: String) {
        if (!NativeSetupJson.validAvatar(value)) { report("setup_identity_invalid"); return }
        edit(state.value.draft.copy(avatarUrl = value, edited = true))
    }
    private fun edit(value: NativeSetupIdentityDraft) {
        if (state.value.busy) return
        mutable.update { it.copy(draft = value, error = if (it.error == "setup_identity_invalid") null else it.error) }
        persist(value)
    }
    private fun persist(value: NativeSetupIdentityDraft) {
        try { drafts.write(value) } catch (_: Exception) { report("storage_error") }
    }

    fun previous(expectedStep: Int): Job {
        if (state.value.route?.step != expectedStep || expectedStep <= 1 || !begin()) return scope.launch { }
        return scope.launch {
            try {
                val current = repository.route()
                applyRoute(current)
                if (current.required && current.step == expectedStep) applyRoute(repository.setStep(expectedStep - 1))
            } catch (error: Exception) { handle(error) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun next(expectedStep: Int, saveProfile: suspend (String, String?) -> NativeUserProfile): Job {
        if (state.value.route?.step != expectedStep || expectedStep !in 1..5 || !begin()) return scope.launch { }
        return scope.launch {
            try {
                val current = repository.route()
                applyRoute(current)
                if (!current.required || current.step != expectedStep) return@launch
                when (expectedStep) {
                    1 -> applyRoute(repository.acknowledge("welcome", true))
                    2 -> if (!current.environment.ready) throw NativeApiException("setup_environment_required")
                    3 -> {
                        val live = repository.status(); applyLive(live)
                        if (live.route.completed) return@launch
                        if (!live.passed("model") || !live.passed("modelTools")) throw NativeApiException("setup_model_required")
                    }
                    4 -> {
                        val desired = NativeSetupJson.validateIdentity(state.value.draft)
                        val saved = saveProfile(desired.name, desired.avatarUrl)
                        if (saved != desired) throw NativeApiException("NATIVE_SETUP_IDENTITY_CHANGED")
                        val confirmed = repository.confirmIdentity(saved); applyLive(confirmed)
                        if (confirmed.route.completed) return@launch
                        if (!confirmed.identitySaved || !confirmed.identityConfirmed || confirmed.profile != saved)
                            throw NativeApiException("NATIVE_SETUP_IDENTITY_CHANGED")
                        val clean = NativeSetupIdentityDraft(saved.name, saved.avatarUrl, edited = false)
                        mutable.update { it.copy(draft = clean) }; persist(clean)
                    }
                    5 -> if (current.permissionPolicy == null) throw NativeApiException("setup_policy_required")
                }
                if (state.value.route?.required == true) applyRoute(repository.setStep(expectedStep + 1))
            } catch (error: Exception) { handle(error) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun complete(): Job {
        if (state.value.route?.step != 6 || !begin()) return scope.launch { }
        return scope.launch {
            try {
                val live = repository.status(); applyLive(live)
                if (live.route.completed) { clearDraft(); return@launch }
                if (!live.canComplete) throw NativeApiException("NATIVE_SETUP_NOT_READY")
                val result = repository.complete(); applyLive(result)
                if (!result.route.completed || result.route.required) throw NativeApiException("setup_result_unconfirmed")
                clearDraft()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                report(code(error))
                // A lost completion receipt is checked by a read, never by repeating completion.
                try {
                    val actual = repository.status(); applyLive(actual)
                    if (actual.route.completed) { clearDraft(); mutable.update { it.copy(error = null) } }
                } catch (read: Exception) {
                    if (read is CancellationException) throw read
                    mutable.update { it.copy(liveFresh = false, statusError = code(read), error = "setup_result_unconfirmed") }
                }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun report(value: String) { mutable.update { it.copy(error = value) } }
    fun dismissError() { mutable.update { it.copy(error = null) } }

    private fun begin(): Boolean {
        val current = state.value
        if (current.busy || current.route?.required != true) return false
        localGeneration++; liveGeneration++
        mutable.update { it.copy(busy = true, error = null, checking = false, liveFresh = false) }
        return true
    }
    private fun applyRoute(value: NativeSetupRoute) {
        mutable.update { it.copy(route = value, localLoading = false, localError = null,
            liveFresh = it.liveFresh && value.environment.ready) }
    }
    private fun applyLive(value: NativeSetupLive) {
        val current = state.value
        val draft = if (!current.draft.edited) NativeSetupIdentityDraft(value.profile.name, value.profile.avatarUrl) else current.draft
        mutable.update { it.copy(route = value.route, localLoading = false, localError = null, live = value, liveFresh = true,
            checking = false, statusError = null, draft = draft, draftLoaded = true) }
        if (draft != current.draft) persist(draft)
    }
    private fun clearDraft() {
        try { drafts.clear() } catch (_: Exception) { report("storage_error") }
    }
    private fun handle(error: Exception) { if (error is CancellationException) throw error; report(code(error)) }
    private fun code(error: Exception, fallback: String = "request_failed"): String = (error as? NativeApiException)?.code ?: fallback
}
