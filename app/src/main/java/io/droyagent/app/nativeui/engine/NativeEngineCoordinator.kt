package io.droyagent.app.nativeui.engine

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeRuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.util.UUID

internal class NativeEngineCoordinator(private val api: NativeEngineGateway, private val drafts: NativeEngineDraftStore, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(NativeEngineState())
    val state = mutable.asStateFlow()
    private val mutation = Mutex()
    private var catalogRead: Job? = null
    private var sessionRead: Job? = null
    private var action: Job? = null
    private var skillsRead: Job? = null
    private var compactRead: Job? = null
    private var compactCancellation: Job? = null
    private var catalogGeneration = 0L
    private var sessionGeneration = 0L
    private var missingSessionReadId: String? = null
    private var skillsGeneration = 0L
    private var compactGeneration = 0L

    fun runtime(value: NativeRuntimeState) { mutable.update { it.copy(runtime = value, fresh = it.fresh && value.ready) } }
    fun report(code: String) { missingSessionReadId = null; mutable.update { it.copy(error = code) } }
    fun bindSession(id: String?) {
        if (id == mutable.value.sessionId) return
        sessionGeneration++; sessionRead?.cancel(); skillsGeneration++; skillsRead?.cancel()
        mutable.update { it.copy(sessionId = id, session = null, sessionLoading = false, receipt = false, skills = emptyList(), skillsSessionId = null, skillsLoading = false, skillsError = null) }
        if (id != null && mutable.value.runtime.ready) refreshSession()
    }
    fun bindDraft(id: String?) {
        if (mutable.value.draftId == id) return
        try { mutable.update { it.copy(draftId = id, draftSelection = id?.let(drafts::selection)) } }
        catch (error: Exception) { mutable.update { it.copy(draftId = id, draftSelection = null) }; failure(error) }
    }
    fun captureSelection(draftId: String): JSONObject? {
        return drafts.capture(draftId)
    }
    fun stagePreset(draftId: String, id: String) {
        val catalog = mutable.value.catalog
        if (catalog?.presets?.none { it.id == id && !it.broken } != false) throw NativeApiException("NATIVE_ENGINE_UNAVAILABLE")
        val value = drafts.stage(draftId, agent = id)
        if (mutable.value.draftId == draftId) mutable.update { it.copy(draftSelection = value) }
    }
    fun discardDraftSelection() {
        val id = mutable.value.draftId ?: return
        if (!mutable.value.busy) try { drafts.discard(id); mutable.update { it.copy(draftSelection = null) } } catch (error: Exception) { failure(error) }
    }
    fun stopReads() {
        catalogGeneration++; sessionGeneration++; catalogRead?.cancel(); sessionRead?.cancel()
        skillsGeneration++; skillsRead?.cancel(); compactGeneration++; compactRead?.cancel()
        mutable.update { it.copy(loading = false, sessionLoading = false, document = null) }
    }
    fun refresh(preserveFeedback: Boolean = false) {
        if (mutable.value.busy) return
        val generation = ++catalogGeneration
        catalogRead?.cancel()
        mutable.update { it.copy(loading = true, fresh = false, error = if (preserveFeedback) it.error else null, receipt = preserveFeedback && it.receipt) }
        catalogRead = scope.launch {
            try {
                val value = api.catalog()
                if (generation == catalogGeneration) { mutable.update { it.copy(catalog = value, fresh = true) }; refreshSession() }
            } catch (error: Exception) { if (generation == catalogGeneration && (!preserveFeedback || mutable.value.error == null)) failure(error) }
            finally { if (generation == catalogGeneration) mutable.update { it.copy(loading = false) } }
        }
    }
    fun refreshSession() {
        val id = mutable.value.sessionId ?: return
        if (mutable.value.busy) return
        val generation = ++sessionGeneration
        sessionRead?.cancel()
        mutable.update { it.copy(sessionLoading = true, session = it.session?.copy(fresh = false)) }
        sessionRead = scope.launch {
            try {
                val value = api.session(id)
                if (generation == sessionGeneration && mutable.value.sessionId == id) {
                    updateSession(value)
                    // A first-send read can race durable session creation. A fresh read resolves
                    // that missing-session observation, but never confirms a failed mutation.
                    if (value.fresh && missingSessionReadId == id && mutable.value.error == "NATIVE_ENGINE_SESSION_MISSING") {
                        mutable.update { it.copy(error = null) }
                        missingSessionReadId = null
                    }
                }
            } catch (error: Exception) {
                if (generation == sessionGeneration) {
                    failure(error)
                    if (safeCode(error) == "NATIVE_ENGINE_SESSION_MISSING") missingSessionReadId = id
                }
            }
            finally { if (generation == sessionGeneration) mutable.update { it.copy(sessionLoading = false) } }
        }
    }
    fun onAction(value: NativeEngineAction) {
        when (value) {
            is NativeEngineAction.Goal -> mutateSession(value.expectedTarget) { api.goal(it, value.action, value.objective, value.expectedGoal) }
            NativeEngineAction.Refresh -> refresh()
            NativeEngineAction.RefreshSession -> refreshSession()
            NativeEngineAction.DismissError -> mutable.update { it.copy(error = null) }
            NativeEngineAction.CloseDocument -> mutable.update { it.copy(document = null) }
            NativeEngineAction.LoadSkills -> loadSkills()
            NativeEngineAction.Compact -> startCompact()
            is NativeEngineAction.CompactAt -> startCompact(value.expectedTarget)
            NativeEngineAction.CancelCompact -> cancelCompact()
            NativeEngineAction.RefreshCompact -> pollCompact()
            NativeEngineAction.DismissCompactReceipt -> if (mutable.value.compaction?.active != true) mutable.update { it.copy(compaction = null, compactError = null) }
            is NativeEngineAction.SessionPreset -> if (matchesTarget(value.expectedTarget)) {
                if (mutable.value.sessionId == null) stage(agent = value.id) else mutateSession(value.expectedTarget) { id -> api.sessionPreset(id, value.id) }
            } else report("NATIVE_ENGINE_DRAFT_CHANGED")
            is NativeEngineAction.SessionPermission -> if (matchesTarget(value.expectedTarget)) {
                if (mutable.value.sessionId == null) stage(permission = value.id, risk = value.acceptRisk) else mutateSession(value.expectedTarget) { id -> api.sessionPermission(id, value.id, value.acceptRisk) }
            } else report("NATIVE_ENGINE_DRAFT_CHANGED")
            is NativeEngineAction.ViewPreset -> launchMutation { val document = api.document(value.id); mutable.update { it.copy(document = document) } }
            is NativeEngineAction.DefaultPreset -> launchMutation {
                val setting = mutable.value.catalog?.presetSetting ?: throw NativeApiException("NATIVE_ENGINE_UNAVAILABLE")
                val updated = api.defaultPreset(value.id, setting.revision)
                mutable.update { it.copy(catalog = updated, fresh = true, receipt = true) }
            }
            is NativeEngineAction.DefaultPermission -> launchMutation {
                val setting = mutable.value.catalog?.permissionSetting ?: throw NativeApiException("NATIVE_ENGINE_UNAVAILABLE")
                val updated = api.defaultPermission(value.id, setting.revision, value.acceptRisk)
                mutable.update { it.copy(catalog = updated, fresh = true, receipt = true) }
            }
        }
    }
    private fun updateSession(value: NativeEngineSession) {
        mutable.update { current -> current.copy(session = value,
            skills = if (current.session?.agentPreset != value.agentPreset) emptyList() else current.skills,
            skillsSessionId = if (current.session?.agentPreset != value.agentPreset) null else current.skillsSessionId) }
    }
    private fun loadSkills() {
        val id = mutable.value.sessionId
        if (id == null) { mutable.update { it.copy(skillsError = "NATIVE_ENGINE_SKILLS_NEED_SESSION") }; return }
        val generation = ++skillsGeneration; skillsRead?.cancel()
        mutable.update { it.copy(skillsLoading = true, skillsError = null) }
        skillsRead = scope.launch {
            try {
                val (target, skills) = api.skills(id)
                if (target != id) throw NativeApiException("malformed_response")
                if (generation == skillsGeneration && mutable.value.sessionId == id) mutable.update { it.copy(skills = skills, skillsSessionId = id) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == skillsGeneration) mutable.update { it.copy(skillsError = if (error is NativeApiException) error.code else "NATIVE_ENGINE_FAILED") }
            } finally { if (generation == skillsGeneration) mutable.update { it.copy(skillsLoading = false) } }
        }
    }
    private fun startCompact(expectedTarget: NativeEngineTarget = NativeEngineTarget(mutable.value.sessionId, mutable.value.draftId)) {
        if (!matchesTarget(expectedTarget)) return report("NATIVE_ENGINE_DRAFT_CHANGED")
        val id = mutable.value.sessionId ?: return report("NATIVE_ENGINE_SKILLS_NEED_SESSION")
        if (mutable.value.compaction?.active == true) return report("NATIVE_ENGINE_BUSY")
        launchMutation {
            if (!matchesTarget(expectedTarget)) throw NativeApiException("NATIVE_ENGINE_DRAFT_CHANGED")
            val requestId = UUID.randomUUID().toString()
            mutable.update { it.copy(compaction = NativeCompactOperation(id, requestId, "starting"), compactError = null) }
            val value = try { api.compact(id, requestId) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(compaction = NativeCompactOperation(id, requestId, "unknown"), compactError = safeCode(error)) }; throw error
            }
            mutable.update { it.copy(compaction = value) }; pollCompact()
        }
    }
    private fun cancelCompact() {
        val value = mutable.value.compaction ?: return
        if (compactCancellation?.isActive == true) return
        compactGeneration++; compactRead?.cancel()
        mutable.update { it.copy(compactCancelPending = true, compactError = null) }
        compactCancellation = scope.launch {
            try {
                val updated = api.cancelCompact(value.sessionId, value.requestId)
                if (mutable.value.compaction?.requestId == value.requestId) mutable.update { it.copy(compaction = updated) }
                pollCompact()
            } catch (error: Exception) { if (error is CancellationException) throw error; mutable.update { it.copy(compactError = safeCode(error)) } }
            finally { mutable.update { it.copy(compactCancelPending = false) } }
        }
    }
    fun resumeCompact() { if (mutable.value.compaction?.active == true) pollCompact() }
    private fun pollCompact() {
        val value = mutable.value.compaction ?: return
        val generation = ++compactGeneration; compactRead?.cancel()
        compactRead = scope.launch {
            try {
                while (isActive) {
                    val updated = api.compactStatus(value.sessionId, value.requestId)
                    if (generation != compactGeneration || mutable.value.compaction?.requestId != value.requestId) break
                    mutable.update { it.copy(compaction = updated) }
                    if (!updated.active) { if (mutable.value.sessionId == updated.sessionId) refreshSession(); break }
                    delay(1000)
                }
            } catch (error: Exception) { if (error is CancellationException) throw error; if (generation == compactGeneration) mutable.update { it.copy(compactError = safeCode(error)) } }
        }
    }
    private fun stage(agent: String? = null, permission: String? = null, risk: Boolean = false) {
        if (mutable.value.busy || !mutable.value.fresh) return
        val catalog = mutable.value.catalog ?: return
        if (agent != null && catalog.presets.none { it.id == agent && !it.broken }) { report("NATIVE_ENGINE_INVALID"); return }
        val permissionOption = permission?.let { requested -> catalog.permissions.find { it.id == requested } }
        if (permission != null && (permissionOption == null || permissionOption.needsRiskConfirmation && !risk)) { report("NATIVE_ENGINE_INVALID"); return }
        val draftId = mutable.value.draftId ?: return report("NATIVE_ENGINE_DRAFT_CHANGED")
        try { val value = drafts.stage(draftId, agent, permission, risk); mutable.update { it.copy(draftSelection = value, receipt = false, error = null) } }
        catch (error: Exception) { failure(error) }
    }

    /** Called once by the prompt owner after creation and before dispatch; no prompt or model call is issued here. */
    suspend fun prepareSession(sessionId: String, token: JSONObject?) {
        if (token == null) return
        val selection = NativeEngineDraftStore.decode(token)
        if (!mutation.tryLock()) throw NativeApiException("NATIVE_ENGINE_BUSY")
        try {
            val capture = drafts.bind(selection, sessionId)
            mutable.update { it.copy(busy = true, error = null) }
            cancelReads()
            var result = api.session(sessionId)
            if (!result.fresh) throw NativeApiException("NATIVE_ENGINE_UNAVAILABLE")
            fun matches() = (selection.agentPresetId == null || result.agentPreset == selection.agentPresetId)
                && (selection.permissionPresetId == null || result.permission == selection.permissionPresetId)
            if (capture.prepared && !matches()) throw NativeApiException("NATIVE_ENGINE_DRAFT_CHANGED")
            selection.agentPresetId?.let { if (result.agentPreset != it) result = api.sessionPreset(sessionId, it) }
            selection.permissionPresetId?.let { if (result.permission != it) result = api.sessionPermission(sessionId, it, selection.permissionRiskAccepted) }
            result = api.session(sessionId)
            if (!result.fresh || !matches()) throw NativeApiException("NATIVE_ENGINE_SAVE_UNCONFIRMED")
            drafts.prepared(selection, sessionId)
            mutable.update { it.copy(draftSelection = it.draftId?.let(drafts::selection), session = if (it.sessionId == sessionId) result else it.session, receipt = true) }
        } catch (error: Exception) { failure(error); throw error }
        finally {
            mutable.update { it.copy(busy = false) }; mutation.unlock()
            if (!mutable.value.fresh && mutable.value.runtime.ready) refresh(preserveFeedback = true)
        }
    }

    private fun matchesTarget(target: NativeEngineTarget?): Boolean = target == null || target == NativeEngineTarget(mutable.value.sessionId, mutable.value.draftId)
    private fun mutateSession(expectedTarget: NativeEngineTarget?, operation: suspend (String) -> NativeEngineSession) {
        val id = mutable.value.sessionId ?: return
        launchMutation {
            if (!matchesTarget(expectedTarget)) throw NativeApiException("NATIVE_ENGINE_DRAFT_CHANGED")
            val updated = operation(id)
            if (mutable.value.sessionId == id) { updateSession(updated); mutable.update { it.copy(receipt = true) } }
        }
    }
    private fun cancelReads() {
        catalogGeneration++; sessionGeneration++; catalogRead?.cancel(); sessionRead?.cancel()
        skillsGeneration++; skillsRead?.cancel()
        mutable.update { it.copy(loading = false, sessionLoading = false) }
    }
    private fun launchMutation(block: suspend () -> Unit) {
        if (action?.isActive == true || mutable.value.busy) return
        action = scope.launch {
            if (!mutation.tryLock()) { report("NATIVE_ENGINE_BUSY"); return@launch }
            cancelReads(); mutable.update { it.copy(busy = true, error = null, receipt = false) }
            try { block() } catch (error: Exception) { failure(error) }
            finally {
                mutable.update { it.copy(busy = false) }; mutation.unlock()
                if (!mutable.value.fresh && mutable.value.runtime.ready) refresh(preserveFeedback = true)
            }
        }
    }
    private fun failure(error: Exception) {
        if (error is CancellationException) throw error
        missingSessionReadId = null
        mutable.update { it.copy(error = safeCode(error)) }
    }
    private fun safeCode(error: Exception): String = if (error is NativeApiException) error.code else "NATIVE_ENGINE_FAILED"
}
