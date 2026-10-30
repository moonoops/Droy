package io.droyagent.app.nativeui.navigation

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** One durable latest intent. Resolution is read-only; the host owns every navigation and resource side effect. */
class NativeExternalNavigationCoordinator(
    private val gateway: NativeExternalNavigationGateway,
    private val store: NativeExternalNavigationStore,
    private val scope: CoroutineScope,
    private val requestId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutable = MutableStateFlow(NativeExternalNavigationState())
    val state = mutable.asStateFlow()
    private val lock = Mutex()
    private var loaded = false
    private var conditions = NativeExternalNavigationConditions()
    private var generation = 0L
    private var reading: Job? = null

    suspend fun restore() = lock.withLock {
        if (!loaded) { loadLocked(); scheduleLocked() }
    }
    suspend fun accept(target: NativeExternalNavigationTarget): NativeExternalNavigationRequest = lock.withLock {
        if (!loaded) loadLocked()
        val next = NativeExternalNavigationRequest(requestId(), target)
        require(next.id != mutable.value.request?.id) { "duplicate_external_request_identity" }
        invalidateLocked()
        mutable.value = mutable.value.copy(phase = NativeExternalNavigationPhase.PERSISTING, blocker = null, resolved = null, failure = null)
        saveLocked(next)
        mutable.value = NativeExternalNavigationState(restored = true, request = next)
        scheduleLocked()
        next
    }
    suspend fun updateConditions(value: NativeExternalNavigationConditions) = lock.withLock {
        val wasLoaded = loaded
        if (!loaded) loadLocked()
        if (!wasLoaded || conditions != value) { conditions = value; scheduleLocked() }
    }
    suspend fun retry() = lock.withLock {
        if (!loaded) loadLocked()
        scheduleLocked()
    }

    /** Evidence must come from the actual resumed page and its loaded target, never from navigate() returning. */
    suspend fun ackOpened(id: String, opened: NativeExternalNavigationOpened): Boolean = lock.withLock {
        if (!loaded) loadLocked()
        val current = mutable.value
        val request = current.request ?: return@withLock false
        val resolved = current.resolved ?: return@withLock false
        if (id != request.id || current.phase != NativeExternalNavigationPhase.READY || current.storageError
            || !opened.resumed || !opened.dataReady || opened.surface != resolved.surface || blocked(request.target) != null) return@withLock false
        clearLocked()
        true
    }
    suspend fun ackFailure(id: String, reason: NativeExternalNavigationFailure): Boolean = lock.withLock {
        if (!loaded) loadLocked()
        val current = mutable.value
        if (current.request?.id != id || current.phase != NativeExternalNavigationPhase.FAILED || current.failure != reason || current.storageError) return@withLock false
        clearLocked()
        true
    }

    /** Explicitly dismisses only this open request; it never consumes a wake event or a shared item. */
    suspend fun dismiss(id: String): Boolean = lock.withLock {
        if (!loaded) loadLocked()
        if (mutable.value.request?.id != id) return@withLock false
        clearLocked()
        true
    }

    private suspend fun loadLocked() {
        try {
            val request = store.load()
            loaded = true
            mutable.value = NativeExternalNavigationState(restored = true, request = request)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            storageFaultLocked()
            throw NativeExternalNavigationStorageException()
        }
    }
    private suspend fun saveLocked(request: NativeExternalNavigationRequest?) {
        try { store.save(request) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            storageFaultLocked()
            throw NativeExternalNavigationStorageException()
        }
    }
    private suspend fun clearLocked() {
        invalidateLocked()
        saveLocked(null)
        mutable.value = NativeExternalNavigationState(restored = true)
    }
    private fun storageFaultLocked() {
        invalidateLocked(); loaded = false
        mutable.value = mutable.value.copy(phase = NativeExternalNavigationPhase.WAITING, blocker = NativeExternalNavigationBlocker.STORAGE,
            resolved = null, failure = null, storageError = true)
    }
    private fun invalidateLocked() { generation++; reading?.cancel(); reading = null }
    private fun blocked(target: NativeExternalNavigationTarget): NativeExternalNavigationBlocker? = when {
        !conditions.foreground -> NativeExternalNavigationBlocker.FOREGROUND
        !conditions.setupComplete -> NativeExternalNavigationBlocker.SETUP
        !conditions.permissionFlowIdle -> NativeExternalNavigationBlocker.PERMISSION_FLOW
        requiresRuntime(target) && !conditions.runtimeReady -> NativeExternalNavigationBlocker.RUNTIME
        else -> null
    }
    private fun scheduleLocked() {
        invalidateLocked()
        val request = mutable.value.request ?: run {
            mutable.value = NativeExternalNavigationState(restored = true)
            return
        }
        val waiting = blocked(request.target)
        mutable.value = NativeExternalNavigationState(restored = true, request = request,
            phase = if (waiting == null) NativeExternalNavigationPhase.RESOLVING else NativeExternalNavigationPhase.WAITING, blocker = waiting)
        if (waiting != null) return
        val ticket = generation
        reading = scope.launch {
            val result = try {
                gateway.resolve(request.target).also { coroutineContext.ensureActive() }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Unknown reader failures stay visible and retryable; they never become a not-found acknowledgement.
                NativeExternalNavigationResolution.Wait(if (error is IOException) NativeExternalNavigationBlocker.DISCONNECTED else NativeExternalNavigationBlocker.RESOLVER_FAILED)
            }
            coroutineContext.ensureActive()
            lock.withLock {
                if (ticket != generation || mutable.value.request?.id != request.id || blocked(request.target) != null) return@withLock
                mutable.value = when (result) {
                    is NativeExternalNavigationResolution.Wait -> mutable.value.copy(phase = NativeExternalNavigationPhase.WAITING, blocker = result.reason)
                    is NativeExternalNavigationResolution.Missing -> mutable.value.copy(phase = NativeExternalNavigationPhase.FAILED, failure = result.reason)
                    is NativeExternalNavigationResolution.Ready -> {
                        val failure = validate(request.target, result.target)
                        if (failure != null) mutable.value.copy(phase = NativeExternalNavigationPhase.FAILED, failure = failure)
                        else mutable.value.copy(phase = NativeExternalNavigationPhase.READY, resolved = readOnlyHistory(result.target))
                    }
                }
            }
        }
    }

    private fun validate(request: NativeExternalNavigationTarget, resolved: NativeExternalResolvedTarget): NativeExternalNavigationFailure? {
        val valid = when (request) {
            is NativeExternalNavigationTarget.EngineSession -> when (resolved) {
                is NativeExternalResolvedTarget.EngineSession -> resolved.session.id == request.sessionId && resolved.petRequestId == request.petRequestId
                is NativeExternalResolvedTarget.CompanionTask -> resolved.session.id == request.sessionId && resolved.petRequestId == request.petRequestId
                is NativeExternalResolvedTarget.CompanionChat -> request.petRequestId == null && resolved.chatSessionId == request.sessionId
                else -> false
            }
            is NativeExternalNavigationTarget.ImportedMedia -> resolved is NativeExternalResolvedTarget.ImportedMedia && resolved.requestedImportId == request.importId
            else -> resolved.surface == request
        }
        if (!valid) return NativeExternalNavigationFailure.INVALID_RESOLUTION
        return null
    }
    private fun readOnlyHistory(target: NativeExternalResolvedTarget): NativeExternalResolvedTarget = when (target) {
        is NativeExternalResolvedTarget.EngineSession -> if (target.session.archived) target.copy(session = target.session.copy(readOnly = true)) else target
        is NativeExternalResolvedTarget.CompanionTask -> if (target.session.archived) target.copy(session = target.session.copy(readOnly = true)) else target
        else -> target
    }

    private fun requiresRuntime(target: NativeExternalNavigationTarget): Boolean = when (target) {
        is NativeExternalNavigationTarget.EngineSession, is NativeExternalNavigationTarget.CompanionChat,
        is NativeExternalNavigationTarget.CompanionTask, is NativeExternalNavigationTarget.BrowserTab,
        is NativeExternalNavigationTarget.VoiceWake -> true
        NativeExternalNavigationTarget.NotificationManagement, is NativeExternalNavigationTarget.AudioAuthorization,
        is NativeExternalNavigationTarget.ImportedMedia, is NativeExternalNavigationTarget.Root -> false
    }
}
