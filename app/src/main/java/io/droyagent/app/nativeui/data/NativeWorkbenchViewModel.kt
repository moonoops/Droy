package io.droyagent.app.nativeui.data

import io.droyagent.app.nativeui.companion.maintenance.NativeCompanionWriteBarrier
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.droyagent.app.NativeRuntimeHost
import io.droyagent.app.nativeui.media.NativeDraftMediaStore
import io.droyagent.app.nativeui.media.NativeLocalAttachment
import io.droyagent.app.nativeui.media.NativeWorkbenchMediaTarget
import io.droyagent.app.nativeui.media.NativeMediaViewModel
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import io.droyagent.app.nativeui.workspaces.NativeWorkspaceJson
import io.droyagent.app.nativeui.standardvoice.*

/** One owner of native workbench state; observers do not own the Agent lifetime. */
class NativeWorkbenchViewModel(application: Application) : AndroidViewModel(application) {
    private val api = NativeApiClient()
    private val preferences = application.getSharedPreferences("native_workbench", 0)
    private val mediaStore = NativeDraftMediaStore(application)
    private val submissionLock = Mutex()
    private val draftPersistenceLock = Any()
    private val uploadConnections = mutableMapOf<String, NativeRuntimeHost.Endpoint>()
    private var refreshRequested = false
    private var blankId = recoveredMaintenanceDraft(preferences.getString("selectedWorkspaceId", null)) ?: blankDraftId(preferences.getString("selectedWorkspaceId", null))
    private var workspaceChosen = preferences.getBoolean("workspaceChosen", preferences.contains("selectedWorkspaceId") || preferences.getString("selectedSessionId", null) != null)
    private val reducer = NativeSessionReducer()
    private val decisionReducer = NativeDecisionReducer()
    private val unreadTurns = NativeUnreadTurns(preferences.all.entries
        .filter { it.key.startsWith("workReadTurn:") && it.value is Int }
        .associate { it.key.removePrefix("workReadTurn:") to it.value as Int })
    private var presentedSessionId: String? = null
    private var unreadBaselineEstablished = false
    private var savedUnreadTurns = unreadTurns.snapshot()
    private val submissions = NativeSubmissions()
    private val initialRuntime = NativeRuntimeHost.inspect(application)
    private val _state = MutableStateFlow(NativeWorkbenchState(
        runtime = NativeRuntimeState(initialRuntime.ready, initialRuntime.active, initialRuntime.canStart, initialRuntime.status, initialRuntime.locale),
        selectedSessionId = preferences.getString("selectedSessionId", null),
        selectedWorkspaceId = preferences.getString("selectedWorkspaceId", null),
        workspaceResolved = workspaceChosen,
        draft = preferences.getString(draftKey(preferences.getString("selectedSessionId", null)), "") ?: "",
        readingPosition = readingPosition(preferences.getString("selectedSessionId", null)),
        draftId = preferences.getString("selectedSessionId", null) ?: blankId,
        draftAttachments = localAttachments(preferences.getString("selectedSessionId", null) ?: blankId),
    ))
    val state: StateFlow<NativeWorkbenchState> = _state.asStateFlow()
    private var observing: Job? = null
    private var following: Job? = null
    private var controlling: Job? = null
    private var decisionObservation: Job? = null
    private var refreshing: Job? = null
    private var sending: Job? = null
    private var activeSubmission: Job? = null
    private var activeSubmissionSessionId: String? = null
    private var paging: Job? = null
    private var rendering: Job? = null
    private var endpoint: NativeRuntimeHost.Endpoint? = null
    private var selectionGeneration = 0L
    private var surfacedFailureSeq: Long? = null
    private var loadedMessageBudget = historyBudget(preferences.getString("selectedSessionId", null))
    private var defaultModel: NativeModel? = null
    private var draftModel: NativeModel? = preferences.getString("newModel", null)?.let { saved ->
        val model = JSONObject(saved)
        NativeModelJson.draft(model)
    }
    private var modelRefresh: Job? = null
    private var captureSessionSelection: ((String) -> JSONObject?)? = null
    private var prepareSessionSelection: (suspend (String, JSONObject?) -> Unit)? = null
    private var observeVoiceSubmission: (suspend (String, String) -> Unit)? = null

    internal fun retireDeletedConversationViews(receipt: JSONObject, recoveredDraftId: String?) {
        val sessions = receipt.optJSONArray("removedSessionIds")?.let { rows -> (0 until rows.length()).map(rows::getString).toSet() }.orEmpty()
        val workspaces = receipt.optJSONArray("workspaceIds")?.let { rows -> (0 until rows.length()).map(rows::getString).toSet() }.orEmpty()
        if (recoveredDraftId != null || _state.value.selectedSessionId in sessions || _state.value.selectedWorkspaceId in workspaces) {
            switchSession(null, null, resolved = recoveredDraftId != null, recoveredDraftId = recoveredDraftId)
        }
        sessions.forEach(unreadTurns::forget)
        savedUnreadTurns = unreadTurns.snapshot()
        _state.update { it.copy(sessions = it.sessions.filterNot { row -> row.id in sessions },
            workspaces = it.workspaces.filterNot { row -> row.id in workspaces },
            unreadSessionIds = it.unreadSessionIds - sessions) }
    }
    internal suspend fun freezeForCompanionDeletion() {
        stopObserving()
        io.droyagent.app.nativeui.companion.maintenance.drainCompanionWriters(viewModelScope)
        persistDraft()
        awaitDraftPersistence()
    }

    fun bindSessionPreparation(captureSelection: (String) -> JSONObject?, prepareSession: suspend (String, JSONObject?) -> Unit) {
        captureSessionSelection = captureSelection; prepareSessionSelection = prepareSession
    }

    fun bindStandardVoiceObservation(observe: suspend (String, String) -> Unit) { observeVoiceSubmission = observe }

    fun startObserving() {
        if (NativeCompanionWriteBarrier.frozen) return
        if (observing?.isActive == true) return
        observing = viewModelScope.launch {
            var maintenanceHeld = false
            while (isActive) {
                val maintenance = NativeRuntimeHost.companionMaintenanceActive()
                if (maintenance) {
                    following?.cancel(); following = null
                    rendering?.cancel(); rendering = null
                } else if (maintenanceHeld && _state.value.runtime.ready) refresh()
                maintenanceHeld = maintenance
                val status = NativeRuntimeHost.inspect(getApplication())
                _state.update { it.copy(runtime = NativeRuntimeState(status.ready, status.active, status.canStart, status.status, status.locale)) }
                if (status.ready && endpoint?.isCurrent() != true) {
                    _state.update { it.copy(sessionBaselineId = null) }
                    endpoint = NativeRuntimeHost.connection()
                    refresh()
                    startControl(); startDecisions()
                } else if (!status.ready) {
                    endpoint = null; following?.cancel(); controlling?.cancel(); decisionObservation?.cancel(); rendering?.cancel()
                    decisionReducer.clear()
                    _state.update { it.copy(decisions = emptyList(), answeringDecisionId = null) }
                    _state.update { it.copy(loading = false, running = false, sessionBaselineId = null) }
                }
                delay(750)
            }
        }
        if (_state.value.runtime.ready) { refresh(); startControl(); startDecisions() }
    }

    fun stopObserving() {
        observing?.cancel(); observing = null
        following?.cancel(); following = null
        rendering?.cancel(); rendering = null
        controlling?.cancel(); controlling = null
        decisionObservation?.cancel(); decisionObservation = null
        refreshing?.cancel(); refreshing = null
        refreshRequested = false
        endpoint = null
        _state.update { it.copy(sessionBaselineId = null) }
    }

    fun startRuntime() {
        try {
            NativeRuntimeHost.start(getApplication())
            dismissError()
        } catch (_: IllegalStateException) {
            _state.update { it.copy(error = "runtime_start_failed") }
        } catch (_: SecurityException) {
            _state.update { it.copy(error = "runtime_start_failed") }
        }
    }

    fun refresh() {
        if (NativeCompanionWriteBarrier.frozen) return
        if (NativeRuntimeHost.companionMaintenanceActive()) return
        if (refreshing?.isActive == true) { refreshRequested = true; return }
        refreshing = viewModelScope.launch {
            do {
                refreshRequested = false
                try {
                    _state.update { it.copy(loading = it.selectedSessionId != null && reducer.cursor < 0,
                        error = if (it.error in connectionErrors) null else it.error) }
                    val workspaces = api.get("workspaces") as? JSONObject ?: throw NativeApiException("malformed_response")
                    val catalog = NativeWorkspaceJson.catalog(workspaces)
                    val items = catalog.workspaces.map {
                        NativeWorkspace(it.id, it.title, it.sessionIds, it.readOnly)
                    }
                    val archived = catalog.archivedSessionIds.toSet()
                    val sessions = catalog.sessions.map {
                        NativeSession(it.id, it.title.orEmpty(), it.running, it.workspaceId, it.readOnly, it.id in archived)
                    }
                    val defaultWorkspace = catalog.defaultWorkspaceId
                    synchronized(draftPersistenceLock) {
                        val current = _state.value
                        val selected = sessions.firstOrNull { it.id == current.selectedSessionId }
                        val initial = if (!workspaceChosen && current.selectedSessionId == null) {
                            val target = defaultWorkspace?.takeIf { id -> items.any { it.id == id && !it.readOnly } }
                            val existing = preferences.getString(blankKey(target), null)?.takeUnless { preferences.contains("materialized:$it") }
                            bindInitialWorkspace(current.draftId, current.draft.isNotBlank() || current.draftAttachments.isNotEmpty(),
                                target, existing, existing != null && (!preferences.getString("draft:$existing", "").isNullOrBlank()
                                    || localAttachments(existing).isNotEmpty()))
                        } else null
                        val workspace = initial?.workspaceId ?: when {
                            initial != null -> null
                            selected != null -> selected.workspaceId
                            workspaceChosen -> current.selectedWorkspaceId?.takeIf { id ->
                                items.any { it.id == id } || hasWorkspaceDraft(id)
                            }
                            else -> defaultWorkspace?.takeIf { id -> items.any { it.id == id } }
                        }
                        val localId = initial?.draftId ?: current.selectedSessionId ?: recoveredMaintenanceDraft(workspace) ?: blankDraftId(workspace)
                        if (current.selectedSessionId == null) blankId = localId
                        if (initial != null) {
                            val editor = preferences.edit().putBoolean("workspaceChosen", true).putString("selectedWorkspaceId", workspace)
                            if (initial.adoptCurrentDraft) {
                                editor.putString(blankKey(workspace), localId)
                                if (workspace != null && preferences.getString(blankKey(null), null) == localId) editor.remove(blankKey(null))
                            }
                            editor.apply()
                            workspaceChosen = true
                        }
                        _state.value = current.copy(
                            workspaces = items, sessions = sessions, defaultWorkspaceId = defaultWorkspace,
                            selectedWorkspaceId = workspace, workspaceResolved = true, loading = false,
                            workspaceNotice = if (initial?.defaultDraftConflict == true) "native_default_workspace_draft_conflict" else current.workspaceNotice,
                            draftId = localId,
                            draft = if (localId == current.draftId) current.draft else preferences.getString("draft:$localId", "") ?: "",
                            draftAttachments = localAttachments(localId),
                            error = if (current.error == "native_workspace_resolving" || current.error == "workspace/session-archived" && selected?.archived == false) null else current.error,
                        )
                    }
                    refreshModels()
                    val selected = _state.value.selectedSessionId
                    if (selected != null && sessions.any { it.id == selected } && following?.isActive != true) startFollow(selected)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    _state.update { it.copy(loading = false, error = errorCode(error)) }
                }
            } while (refreshRequested)
        }
    }

    fun selectSession(session: NativeSession) {
        if (NativeCompanionWriteBarrier.frozen) return
        _state.update { it.copy(sessions = it.sessions.filterNot { row -> row.id == session.id } + session) }
        if (_state.value.selectedSessionId == session.id) _state.update { it.copy(selectedWorkspaceId = session.workspaceId) }
        else selectSession(session.id)
    }

    fun selectSession(id: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        if (_state.value.selectedSessionId == id) return
        persistDraft()
        val workspace = _state.value.sessions.firstOrNull { it.id == id }?.workspaceId
        switchSession(id, workspace)
        if (_state.value.runtime.ready) startFollow(id)
    }

    fun selectWorkspace(id: String?) {
        if (NativeCompanionWriteBarrier.frozen) return
        // A deliberate Ungrouped choice and a first launch both have a null ID.
        workspaceChosen = true
        preferences.edit().putBoolean("workspaceChosen", true).putString("selectedWorkspaceId", id).apply()
        _state.update { it.copy(workspaceResolved = true) }
        if (_state.value.selectedWorkspaceId == id) return
        persistDraft()
        switchSession(null, id)
    }

    /** Return to the space's unsent draft before an explicit new-chat action can replace it. */
    fun openDraft(workspaceId: String?): Boolean {
        if (NativeCompanionWriteBarrier.frozen) return false
        if (_state.value.workspaces.any { it.id == workspaceId && it.readOnly }) {
            _state.update { it.copy(error = "COMPANION_WORKSPACE_MANAGED") }; return false
        }
        persistDraft()
        switchSession(null, workspaceId)
        return true
    }

    fun workspacePendingSession(workspaceId: String): String? = preferences.all.entries.asSequence()
        .filter { it.key.startsWith("pending:") && it.value is String }
        .map { JSONObject(it.value as String) }
        .firstOrNull { it.optString("workspaceId") == workspaceId && NativeSubmissions.blocksNewSubmission(it) }
        ?.getString("sessionId")

    fun hasWorkspaceDraft(workspaceId: String): Boolean {
        if (workspacePendingSession(workspaceId) != null) return true
        val id = preferences.getString(blankKey(workspaceId), null) ?: return false
        if (preferences.contains("materialized:$id")) return false
        return !preferences.getString("draft:$id", "").isNullOrBlank() || localAttachments(id).isNotEmpty()
    }

    /** A blank draft is local; the first explicit send materializes its durable Session. */
    fun createSession() {
        if (NativeCompanionWriteBarrier.frozen) return
        if (rejectManagedTarget()) return
        if (_state.value.sending) { _state.update { it.copy(error = "pending_send_conflict") }; return }
        persistDraft()
        draftModel = null
        val discardingBlank = _state.value.selectedSessionId == null && !preferences.contains("materialized:$blankId")
        val discarded = if (discardingBlank) localAttachments(blankId) else emptyList()
        if (discardingBlank) preferences.edit().remove("attachments:$blankId").remove("draft:$blankId").apply {
            if (preferences.getString("maintenanceDraftId", null) == blankId) remove("maintenanceDraftId")
        }.apply()
        blankId = UUID.randomUUID().toString()
        preferences.edit().remove("newModel").putString(blankKey(_state.value.selectedWorkspaceId), blankId).apply()
        switchSession(null, _state.value.selectedWorkspaceId, resolved = _state.value.workspaceResolved)
        discarded.map { it.importId }.distinct().forEach(::cleanupImportLater)
    }

    private fun switchSession(id: String?, workspaceId: String?, resolved: Boolean = true, recoveredDraftId: String? = null) {
        if (id == null) blankId = recoveredDraftId ?: recoveredMaintenanceDraft(workspaceId) ?: blankDraftId(workspaceId)
        selectionGeneration++
        following?.cancel(); following = null
        rendering?.cancel(); rendering = null
        paging?.cancel(); paging = null
        reducer.reset(); loadedMessageBudget = historyBudget(id); surfacedFailureSeq = null
        workspaceChosen = resolved
        preferences.edit().putString("selectedSessionId", id).putString("selectedWorkspaceId", workspaceId).putBoolean("workspaceChosen", resolved).apply()
        _state.update { it.copy(
            selectedSessionId = id, sessionBaselineId = null, selectedWorkspaceId = workspaceId, workspaceResolved = resolved, workspaceNotice = null, messages = emptyList(),
            draft = preferences.getString(draftKey(id), "") ?: "", running = false,
            hasMore = false, loading = id != null, usage = null, error = null,
            readingPosition = readingPosition(id),
            draftId = id ?: blankId, draftAttachments = localAttachments(id ?: blankId),
        ) }
        publishMessages()
    }

    fun setDraft(value: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        synchronized(draftPersistenceLock) {
            _state.update { it.copy(draft = value) }
            preferences.edit().putString(draftKey(_state.value.selectedSessionId), value).apply()
        }
    }

    fun mediaTargetOrNull(): NativeWorkbenchMediaTarget? {
        if (!_state.value.workspaceResolved) {
            _state.update { it.copy(error = "native_workspace_resolving") }
            return null
        }
        return mediaTarget()
    }

    fun mediaTarget(): NativeWorkbenchMediaTarget {
        val value = _state.value
        if (!value.workspaceResolved) throw NativeApiException("native_workspace_resolving")
        return NativeWorkbenchMediaTarget(value.draftId, value.selectedSessionId ?: preferences.getString("materialized:${value.draftId}", null), value.selectedWorkspaceId)
    }

    fun isCurrentMediaTarget(target: NativeWorkbenchMediaTarget): Boolean {
        val current = _state.value
        if (!current.workspaceResolved) return false
        return NativeSubmissions.matchesMediaTarget(target, current.draftId, current.selectedSessionId, current.selectedWorkspaceId) {
            preferences.getString("materialized:$it", null)
        }
    }

    suspend fun captureStandardVoiceTarget(fresh: Boolean): NativeStandardVoiceTarget = withContext(Dispatchers.Main.immediate) {
        val current = _state.value
        if (!current.workspaceResolved) throw NativeApiException("native_workspace_resolving")
        if (!current.runtime.ready) throw NativeApiException("runtime_unavailable")
        if (!fresh && current.readOnly) throw NativeApiException(if (current.archived) "workspace/session-archived" else "COMPANION_WORKSPACE_MANAGED")
        if (current.sending || !fresh && current.pendingRequestId != null) throw NativeApiException("native_standard_voice_draft_changed")
        if (fresh) {
            val workspace = standardVoiceFreshWorkspace(current.selectedWorkspaceId, current.defaultWorkspaceId, current.workspaces)
            // Both the viewed conversation's draft and the destination space's unsent draft survive wake-up.
            if (!openDraft(workspace)) throw NativeApiException("COMPANION_WORKSPACE_MANAGED")
            val previous = mediaTarget()
            if (_state.value.draft.isNotBlank() || _state.value.draftAttachments.isNotEmpty()) ensureSessionForGoal()
            if (!isCurrentMediaTarget(previous)) throw NativeApiException("native_standard_voice_draft_changed")
            createSession()
            if (_state.value.draftId == previous.draftId || _state.value.selectedSessionId != null)
                throw NativeApiException("native_standard_voice_draft_changed")
        }
        val media = mediaTarget()
        val target = NativeStandardVoiceTarget(media.draftId, media.sessionId, media.workspaceId, voiceDraftToken(media.draftId))
        val record = JSONObject().put("draftId", target.draftId).put("sessionId", target.sessionId ?: JSONObject.NULL)
            .put("workspaceId", target.workspaceId ?: JSONObject.NULL).put("draftToken", target.draftToken).put("fresh", fresh)
        withContext(Dispatchers.IO) {
            if (!preferences.edit().putString("voice-target:${target.draftId}", record.toString()).commit()) throw NativeApiException("storage_error")
        }
        target
    }

    private fun voiceDraftToken(id: String): String = standardVoiceDraftToken(
        preferences.getString("draft:$id", "") ?: "", JSONArray(localAttachments(id).map(NativeLocalAttachment::json)).toString())

    private fun voiceTargetRecord(target: NativeStandardVoiceTarget): JSONObject? {
        val record = preferences.getString("voice-target:${target.draftId}", null)?.let(::JSONObject) ?: return null
        fun same(key: String, value: String?) = if (value == null) record.isNull(key) else record.getString(key) == value
        return record.takeIf { it.getString("draftId") == target.draftId && same("sessionId", target.sessionId)
            && same("workspaceId", target.workspaceId) && it.getString("draftToken") == target.draftToken }
    }

    fun canInsertStandardVoice(target: NativeStandardVoiceTarget): Boolean {
        val record = voiceTargetRecord(target) ?: return false
        return !record.getBoolean("fresh") && !state.value.readOnly && isCurrentMediaTarget(target.mediaTarget())
            && voiceDraftToken(target.draftId) == target.draftToken
    }

    suspend fun validateStandardVoiceTarget(target: NativeStandardVoiceTarget): NativeStandardVoiceTargetCheck {
        val record = voiceTargetRecord(target) ?: return NativeStandardVoiceTargetCheck(false, false)
        val sameScene = record.getBoolean("fresh") || isCurrentMediaTarget(target.mediaTarget())
        val catalog = NativeWorkspaceJson.catalog(api.get("workspaces") as? JSONObject ?: throw NativeApiException("malformed_response"))
        val space = target.workspaceId?.let { id -> catalog.workspaces.firstOrNull { it.id == id } }
        val id = target.sessionId ?: preferences.getString("materialized:${target.draftId}", null)
        val session = (id ?: target.draftId).let { expected -> catalog.sessions.firstOrNull { it.id == expected } }
        val valid = sameScene && (target.workspaceId == null || space != null && !space.readOnly)
            && (id == null || session != null) && (session == null || !session.readOnly && session.id !in catalog.archivedSessionIds
                && session.workspaceId == target.workspaceId)
        return NativeStandardVoiceTargetCheck(valid, voiceDraftToken(target.draftId) == target.draftToken)
    }

    suspend fun submitStandardVoice(target: NativeStandardVoiceTarget, nonce: String, text: String): NativeStandardVoiceReceipt = withContext(Dispatchers.Main.immediate) {
        val check = validateStandardVoiceTarget(target)
        if (!check.writableAndOwned || !check.draftUnchanged) throw NativeApiException("native_standard_voice_draft_changed")
        if (isCurrentMediaTarget(target.mediaTarget()) && _state.value.selectedSessionId == null) {
            // Select before session creation: its catalog refresh must not replace this visible voice draft.
            val sessionId = target.sessionId ?: preferences.getString("materialized:${target.draftId}", null) ?: target.draftId
            switchSession(sessionId, target.workspaceId)
        }
        val id = submitExternalText(target.mediaTarget(), nonce, text, emptyList(), "voice")
        NativeStandardVoiceReceipt(id, nonce)
    }

    /** A bounded page can prove acceptance; absence can never prove rejection. */
    suspend fun standardVoiceSubmissionStatus(target: NativeStandardVoiceTarget, nonce: String): NativeStandardVoiceSubmissionStatus {
        suspend fun accepted(id: String): NativeStandardVoiceSubmissionStatus {
            pending(id)?.takeIf { it.getString("requestId") == nonce && it.optString("origin") == "voice" }?.let { retireAccepted(it) }
            return NativeStandardVoiceSubmissionStatus.Accepted(NativeStandardVoiceReceipt(id, nonce))
        }
        preferences.getString("voice-receipt:$nonce", null)?.let { raw ->
            val receipt = JSONObject(raw)
            if (receipt.getString("draftId") != target.draftId || receipt.getString("rpcId") != nonce) throw NativeApiException("native_standard_voice_receipt_mismatch")
            return NativeStandardVoiceSubmissionStatus.Accepted(NativeStandardVoiceReceipt(receipt.getString("sessionId"), nonce))
        }
        val id = target.sessionId ?: preferences.getString("materialized:${target.draftId}", null) ?: target.draftId
        pending(id)?.takeIf { it.getString("requestId") == nonce && it.optString("origin") == "voice"
            && !it.optBoolean("promptAttempted") && activeSubmissionSessionId != id }?.let {
            return NativeStandardVoiceSubmissionStatus.NotAccepted
        }
        val projection = api.post("projections", JSONObject().put("sessionId", id)) as? JSONObject
            ?: return NativeStandardVoiceSubmissionStatus.Unknown
        if (standardVoiceInboxContains(projection.optJSONObject("values"), nonce))
            return accepted(id)
        if (projection.optString("kind") != "sequenced" || !projection.has("asOfSeq")) return NativeStandardVoiceSubmissionStatus.Unknown
        val page = api.post("page", JSONObject().put("address", JSONObject().put("kind", "session").put("sessionId", id))
            .put("throughSeq", projection.getLong("asOfSeq")).put("maxMessages", 60)) as? JSONObject
            ?: throw NativeApiException("malformed_response")
        return if (standardVoicePageContains(page, nonce)) accepted(id)
            else NativeStandardVoiceSubmissionStatus.Unknown
    }

    suspend fun insertStandardVoice(target: NativeStandardVoiceTarget, nonce: String, text: String) {
        val proof = "voice-inserted:$nonce"
        if (preferences.getBoolean(proof, false)) return
        val check = validateStandardVoiceTarget(target)
        if (!check.writableAndOwned || !check.draftUnchanged || !canInsertStandardVoice(target)) throw NativeApiException("native_standard_voice_draft_changed")
        val before = state.value.draft
        val next = if (before.isBlank()) text else "$before\n$text"
        withContext(Dispatchers.IO) {
            synchronized(draftPersistenceLock) {
                if (voiceDraftToken(target.draftId) != target.draftToken) throw NativeApiException("native_standard_voice_draft_changed")
                if (!preferences.edit().putString("draft:${target.draftId}", next).putBoolean(proof, true).commit()) throw NativeApiException("storage_error")
            }
        }
        if (isCurrentMediaTarget(target.mediaTarget()) && state.value.draft == before && preferences.getString("draft:${target.draftId}", null) == next)
            _state.update { it.copy(draft = next) }
    }

    private fun NativeStandardVoiceTarget.mediaTarget() = NativeWorkbenchMediaTarget(draftId, sessionId, workspaceId)

    fun deliverLocalAttachments(target: NativeWorkbenchMediaTarget, operationId: String, attachments: List<NativeLocalAttachment>) {
        if (_state.value.workspaces.any { it.id == target.workspaceId && it.readOnly }) throw NativeApiException("COMPANION_WORKSPACE_MANAGED")
        val proof = "media-delivered:${target.draftId}:$operationId"
        val merged = synchronized(draftPersistenceLock) {
            val delivered = preferences.getStringSet(proof, emptySet()).orEmpty()
            val next = (localAttachments(target.draftId) + attachments.filterNot { it.id in delivered }).distinctBy { it.id }
            if (next.size > 10) throw NativeApiException("native_attachment_too_many")
            preferences.edit().putString("attachments:${target.draftId}", JSONArray(next.map(NativeLocalAttachment::json)).toString())
                .putStringSet(proof, delivered + attachments.map { it.id }).apply()
            next
        }
        if (_state.value.draftId == target.draftId) _state.update { it.copy(draftAttachments = merged) }
    }

    suspend fun awaitDraftPersistence() = withContext(Dispatchers.IO) {
        if (!preferences.edit().commit()) throw NativeApiException("storage_error")
    }
    suspend fun retireMediaDelivery(target: NativeWorkbenchMediaTarget, operationId: String) = withContext(Dispatchers.IO) {
        if (!preferences.edit().remove("media-delivered:${target.draftId}:$operationId").commit()) throw NativeApiException("storage_error")
    }
    fun removeDraftAttachment(id: String) {
        if (NativeCompanionWriteBarrier.frozen) return
        val target = _state.value.draftId
        val before = localAttachments(target)
        val next = synchronized(draftPersistenceLock) {
            val value = localAttachments(target).filterNot { it.id == id }
            preferences.edit().putString("attachments:$target", JSONArray(value.map(NativeLocalAttachment::json)).toString()).apply()
            value
        }
        _state.update { it.copy(draftAttachments = next) }
        before.filter { it.id == id }.map { it.importId }.distinct().forEach(::cleanupImportLater)
    }

    internal suspend fun releaseImportIfUnused(importId: String) {
        awaitDraftPersistence()
        val referenced = synchronized(draftPersistenceLock) {
            preferences.all.any { (key, raw) ->
                if (key.startsWith("media-delivered:") && key.endsWith(":$importId")) true
                else if (raw !is String) false
                else if (key.startsWith("attachments:")) NativeLocalAttachment.parseList(JSONArray(raw)).any { it.importId == importId }
                else if (key.startsWith("pending:")) JSONObject(raw).optJSONArray("attachments")?.let(NativeLocalAttachment::parseList)?.any { it.importId == importId } == true
                else false
            }
        }
        if (!referenced) mediaStore.removeImport(importId)
    }
    private fun cleanupImportLater(id: String) {
        viewModelScope.launch {
            try { releaseImportIfUnused(id) }
            catch (error: Exception) { if (error is CancellationException) throw error; _state.update { it.copy(error = "native_media_cleanup_failed") } }
        }
    }

    suspend fun loadLocalAttachment(item: NativeLocalAttachment): ByteArray = mediaStore.bytes(item)

    fun setReadingPosition(sessionId: String?, position: NativeReadingPosition) {
        if (NativeCompanionWriteBarrier.frozen) return
        if (sessionId == null) return
        preferences.edit().putString("readingKey:$sessionId", position.key)
            .putInt("readingOffset:$sessionId", position.offset).putBoolean("followingTail:$sessionId", position.followingTail).apply()
        if (_state.value.selectedSessionId == sessionId) _state.update { it.copy(readingPosition = position) }
        if (position.followingTail && presentedSessionId == sessionId
            && _state.value.historyCursor >= (unreadTurns.completionSeq(sessionId) ?: Long.MAX_VALUE)) {
            unreadTurns.markRead(sessionId)
            publishUnread()
        }
    }

    fun presentSession(sessionId: String?) { presentedSessionId = sessionId }

    private fun observeTurnBoundary(sessionId: String, boundary: JSONObject?, seq: Long, sequenced: Boolean = true, historical: Boolean = false, publishNow: Boolean = true) {
        val completed = boundary?.takeIf { it.has("lastTurn") }?.let {
            (it.getInt("lastTurn") - if (it.isNull("openTurnStartSeq")) 0 else 1).coerceAtLeast(0)
        }
        if (historical) unreadTurns.seedHistory(sessionId, completed)
        if (completed == null) return
        val value = boundary ?: return
        // Cached summaries establish turn counts only. Exact follow/control snapshots establish
        // the reading gate; the same turn's gate never moves forward on reconnect.
        val through = if (!sequenced) null else if (value.isNull("openTurnStartSeq")) seq else value.getLong("openTurnStartSeq") - 1
        val advanced = unreadTurns.observe(sessionId, completed, through)
        if (publishNow) publishUnread()
        if (advanced && !historical && sessionId in unreadTurns.unread()) refresh()
    }

    private fun publishUnread() {
        val snapshot = unreadTurns.snapshot()
        if (snapshot != savedUnreadTurns) {
            val editor = preferences.edit()
            snapshot.forEach { (id, turn) -> if (savedUnreadTurns[id] != turn) editor.putInt("workReadTurn:$id", turn) }
            editor.apply()
            savedUnreadTurns = snapshot
        }
        _state.update { it.copy(unreadSessionIds = unreadTurns.unread()) }
    }

    private fun readingPosition(id: String?): NativeReadingPosition? = id?.let { sessionId ->
        preferences.getString("readingKey:$sessionId", null)?.let { key ->
            NativeReadingPosition(key, preferences.getInt("readingOffset:$sessionId", 0), preferences.getBoolean("followingTail:$sessionId", true))
        }
    }

    private fun historyBudget(id: String?): Int = if (id == null) 60 else preferences.getInt("historyBudget:$id", 60).coerceAtLeast(60)

    fun send() {
        if (NativeCompanionWriteBarrier.frozen) return
        if (rejectManagedTarget()) return
        val current = _state.value
        if (current.sending || sending?.isActive == true || current.modelChanging || current.draft.isBlank() && current.draftAttachments.isEmpty()) return
        val target = mediaTargetOrNull() ?: return
        val id = target.sessionId ?: target.draftId
        if (pending(id) != null) { _state.update { it.copy(error = "pending_send_conflict") }; return }
        val body = try { prepareSubmission(target, UUID.randomUUID().toString(), current.draft, current.draftAttachments, "draft") }
        catch (error: Exception) { if (error is CancellationException) throw error; _state.update { it.copy(error = errorCode(error)) }; return }
        try {
            synchronized(draftPersistenceLock) {
                // The outbox owns the submitted content before the composer is cleared. A later
                // receipt must never consume a new draft, even when its text is identical.
                body.put("draftConsumed", true)
                if (!preferences.edit().putString(pendingKey(id), body.toString()).putString("selectedSessionId", id)
                        .putString("selectedWorkspaceId", target.workspaceId).putBoolean("workspaceChosen", true)
                        .putString("draft:${target.draftId}", "").putString("attachments:${target.draftId}", "[]").commit())
                    throw NativeApiException("storage_error")
                _state.update { it.copy(selectedSessionId = id, draft = "", draftAttachments = emptyList()) }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _state.update { it.copy(error = errorCode(error)) }
            return
        }
        dispatch(body)
    }

    /** Shares submit exactly their preview; they never edit or select the ordinary draft. */
    suspend fun submitExternalShare(target: NativeWorkbenchMediaTarget, requestId: String, text: String,
                                    attachments: List<NativeLocalAttachment>): String = submitExternalText(target, requestId, text, attachments, "share")

    suspend fun submitVoiceInput(target: NativeWorkbenchMediaTarget, requestId: String, text: String,
                                 attachments: List<NativeLocalAttachment>, durationMs: Long? = null): String = submitExternalText(target, requestId, text, attachments, "voice-input", durationMs)

    private suspend fun submitExternalText(target: NativeWorkbenchMediaTarget, requestId: String, text: String,
                                    attachments: List<NativeLocalAttachment>, origin: String, voiceDurationMs: Long? = null): String = withContext(Dispatchers.Main.immediate) {
        if (!_state.value.workspaceResolved) throw NativeApiException("native_workspace_resolving")
        if (_state.value.sending || sending?.isActive == true) throw NativeApiException("pending_send_conflict")
        if (_state.value.sessions.any { it.id == target.sessionId && it.archived }) throw NativeApiException("workspace/session-archived")
        if (_state.value.workspaces.any { it.id == target.workspaceId && it.readOnly }
            || _state.value.sessions.any { it.id == target.sessionId && it.readOnly }) throw NativeApiException("COMPANION_SESSION_MANAGED")
        val id = target.sessionId ?: target.draftId
        val prior = pending(id)
        if (prior != null && prior.getString("requestId") != requestId) throw NativeApiException("pending_send_conflict")
        val body = prior ?: prepareSubmission(target, requestId, text, attachments, origin).also { record ->
            voiceDurationMs?.let { require(it in 1..60_000L); record.put("voiceDurationMs", it) }
        }
        if (origin == "voice-input" && isCurrentMediaTarget(target) && _state.value.selectedSessionId == null) {
            // Keep the visible recording's identity while creation refreshes the catalog. Otherwise
            // the now-materialized blank draft is replaced and the Shell treats it as navigation away.
            switchSession(body.getString("sessionId"), target.workspaceId)
        }
        performSubmission(body)
    }

    private fun prepareSubmission(target: NativeWorkbenchMediaTarget, requestId: String, text: String,
                                  attachments: List<NativeLocalAttachment>, origin: String): JSONObject {
        require(text.isNotBlank() || attachments.isNotEmpty())
        require(attachments.size <= 10)
        val id = target.sessionId ?: preferences.getString("materialized:${target.draftId}", null) ?: target.draftId
        val preparation = preferences.getString("sessionPreparation:${target.draftId}", null)?.let(::JSONObject)
        val body = JSONObject().put("sessionId", id).put("requestId", requestId).put("text", text)
            .put("workspaceId", target.workspaceId ?: JSONObject.NULL).put("draftId", target.draftId).put("origin", origin)
            .put("attachments", JSONArray(attachments.map(NativeLocalAttachment::json)))
            .put("needsCreate", target.sessionId == null && !preferences.contains("materialized:${target.draftId}"))
            .put("createAttempted", false)
            .put("status", "prepared")
        if (preparation != null) preparation.keys().forEach { key -> body.put(key, preparation.get(key)) }
        captureDraftPreparation(body, target)
        return body
    }

    private fun captureDraftPreparation(body: JSONObject, target: NativeWorkbenchMediaTarget) {
        NativeSubmissions.captureSessionPreparation(body) { draft ->
            (captureSessionSelection ?: throw NativeApiException("NATIVE_ENGINE_UNAVAILABLE"))(draft)
        }
        if (body.getBoolean("needsCreate") && !body.has("model") && target.draftId == _state.value.draftId) draftModel?.let { model ->
            val selection = JSONObject().put("provider", model.provider).put("model", model.id)
            model.reasoningEffort?.let { selection.put("reasoningEffort", it) }
            body.put("model", selection).put("needsModel", true)
        }
    }

    fun retryPending() {
        if (NativeCompanionWriteBarrier.frozen) return
        if (rejectManagedTarget()) return
        val id = _state.value.selectedSessionId ?: return
        val body = pending(id) ?: return
        if (!_state.value.sending && sending?.isActive != true) dispatch(body)
    }

    /** A separate draft is explicit abandonment, never another dispatch of the uncertain request. */
    fun abandonPendingToNewDraft() {
        if (NativeCompanionWriteBarrier.frozen) return
        val id = _state.value.selectedSessionId ?: return
        val body = pending(id) ?: return
        if (_state.value.sending || sending?.isActive == true) return
        viewModelScope.launch {
            try {
                val next = UUID.randomUUID().toString()
                val workspace = body.optString("workspaceId").takeUnless { it == "null" || it.isEmpty() }
                body.put("status", "abandoned")
                withContext(Dispatchers.IO) {
                    if (!preferences.edit().putString(pendingKey(id), body.toString()).putString(blankKey(workspace), next)
                            .putString("draft:$next", body.getString("text"))
                            .putString("attachments:$next", body.optJSONArray("attachments")?.toString() ?: "[]").commit())
                        throw NativeApiException("storage_error")
                }
                blankId = next
                switchSession(null, workspace)
            } catch (error: Exception) { if (error is CancellationException) throw error; _state.update { it.copy(error = errorCode(error)) } }
        }
    }

    private fun dispatch(body: JSONObject) {
        sending = viewModelScope.launch {
            try { performSubmission(body) }
            catch (error: Exception) { if (error is CancellationException) throw error; _state.update { it.copy(error = errorCode(error)) } }
        }
    }

    private suspend fun performSubmission(body: JSONObject): String {
        if (!submissionLock.tryLock()) throw NativeApiException("pending_send_conflict")
        val id = body.getString("sessionId")
        val requestId = body.getString("requestId")
        val submissionJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        activeSubmission = submissionJob
        activeSubmissionSessionId = id
        var admitted = false
        NativeMediaViewModel.submissionActivity("workbench:$requestId", true)
        try {
            _state.update { it.copy(sending = true, error = null) }; publishMessages()
            commitPending(body)
            prepareCreatedSession(body, ::commitPending)
            val content = prepareContent(body)
            if (body.optString("origin") == "voice")
                (observeVoiceSubmission ?: throw NativeApiException("native_standard_voice_failed"))(id, requestId)
            val request = JSONObject().put("sessionId", id).put("requestId", requestId)
                .put("mode", "queue").put("clientTimeZone", java.util.TimeZone.getDefault().id).put("content", content)
            if (request.toString().toByteArray(Charsets.UTF_8).size > 32 * 1024 * 1024) throw NativeApiException("native_attachment_batch_too_large")
            body.put("promptAttempted", true); commitPending(body)
            val receipt = api.post("prompt", request) as? JSONObject ?: throw NativeApiException("malformed_response")
            if (!receipt.getBoolean("accepted")) throw NativeApiException("malformed_response")
            admitted = true
            body.put("status", "accepted"); commitPending(body)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (_state.value.selectedSessionId == id && reducer.containsRequest(requestId)) admitted = true
            if (!admitted) {
                pending(id)?.takeIf { it.getString("requestId") == requestId }?.let {
                    body.put("status", "uncertain"); commitPending(body)
                }
                _state.update { it.copy(error = errorCode(error)) }
                throw error
            }
        } finally {
            try {
                if (admitted) { submissions.accept(body); retireAccepted(body) }
            } finally {
                try { _state.update { it.copy(sending = false) }; publishMessages() }
                finally {
                    if (activeSubmission === submissionJob) { activeSubmission = null; activeSubmissionSessionId = null }
                    NativeMediaViewModel.submissionActivity("workbench:$requestId", false); submissionLock.unlock()
                }
            }
        }
        return id
    }

    private suspend fun prepareCreatedSession(body: JSONObject, persist: suspend (JSONObject) -> Unit) {
        val id = body.getString("sessionId")
        if (body.optBoolean("needsCreate")) {
            // Older pending records did not persist this boundary; their creation outcome is unknown.
            if (!body.has("createAttempted")) { body.put("createAttempted", true); persist(body) }
            if (body.optBoolean("createAttempted")) verifyCreatedSession(body)
            else {
                // session.create may mint a different Session when the named one is no longer blank.
                body.put("createAttempted", true); persist(body)
                val create = JSONObject().put("sessionId", id)
                body.optString("workspaceId").takeIf { it != "null" && it.isNotEmpty() }?.let { create.put("workspaceId", it) }
                val created = api.post("create", create) as? JSONObject ?: throw NativeApiException("malformed_response")
                if (created.getString("sessionId") != id) throw NativeApiException("session_identity_conflict")
            }
            unreadTurns.trackNew(id); publishUnread()
            body.put("needsCreate", false)
            preferences.edit().putString("materialized:${body.optString("draftId", id)}", id).apply()
            persist(body)
            if (_state.value.selectedSessionId == id && observing?.isActive == true) startFollow(id)
            refresh()
        }
        NativeSubmissions.prepareSession(body, persist) { sessionId, token ->
            (prepareSessionSelection ?: throw NativeApiException("NATIVE_ENGINE_UNAVAILABLE"))(sessionId, token)
        }
        if (body.optBoolean("needsModel")) {
            api.post("model", JSONObject(body.getJSONObject("model").toString()).put("sessionId", id))
            body.put("needsModel", false); persist(body)
        }
        preferences.edit().remove("sessionPreparation:${body.getString("draftId")}").apply()
    }

    /** Materialize a draft for goal settings without submitting a prompt or clearing its contents. */
    suspend fun ensureSessionForGoal(): String = withContext(Dispatchers.Main.immediate) {
        check(!NativeCompanionWriteBarrier.frozen) { "COMPANION_MAINTENANCE_BUSY" }
        if (rejectManagedTarget()) throw NativeApiException("COMPANION_SESSION_MANAGED")
        val target = mediaTargetOrNull() ?: throw NativeApiException("native_workspace_resolving")
        target.sessionId?.let { return@withContext it }
        if (!submissionLock.tryLock()) throw NativeApiException("pending_send_conflict")
        try {
            persistDraft()
            val id = target.draftId
            if (pending(id) != null) throw NativeApiException("pending_send_conflict")
            val key = "sessionPreparation:$id"
            val body = preferences.getString(key, null)?.let(::JSONObject) ?: JSONObject()
                .put("sessionId", id).put("draftId", id).put("workspaceId", target.workspaceId ?: JSONObject.NULL)
                .put("needsCreate", !preferences.contains("materialized:$id")).put("createAttempted", false)
                .also { captureDraftPreparation(it, target) }
            suspend fun persistPreparation(record: JSONObject) = withContext(Dispatchers.IO) {
                if (!preferences.edit().putString(key, record.toString()).commit()) throw NativeApiException("storage_error")
            }
            _state.update { it.copy(sending = true, error = null) }
            persistPreparation(body)
            prepareCreatedSession(body, ::persistPreparation)
            if (isCurrentMediaTarget(target)) {
                switchSession(id, target.workspaceId)
                if (observing?.isActive == true) startFollow(id)
            }
            refresh()
            id
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _state.update { it.copy(error = errorCode(error)) }
            throw error
        } finally {
            _state.update { it.copy(sending = false) }
            submissionLock.unlock()
        }
    }

    suspend fun openScheduledTaskDraft(text: String): Boolean {
        if (_state.value.sending || sending?.isActive == true) return false
        // Keep the space's previous draft reachable in history before replacing the blank slot.
        if (!openDraft(_state.value.selectedWorkspaceId)) return false
        val target = mediaTarget()
        if (_state.value.draft.isNotBlank() || _state.value.draftAttachments.isNotEmpty()) ensureSessionForGoal()
        if (!isCurrentMediaTarget(target)) return false
        createSession()
        setDraft(text)
        return true
    }

    private suspend fun verifyCreatedSession(body: JSONObject) {
        val id = body.getString("sessionId")
        val list = api.post("list") as? JSONObject ?: throw NativeApiException("malformed_response")
        val spaces = api.get("workspaces") as? JSONObject ?: throw NativeApiException("malformed_response")
        if (!NativeSubmissions.confirmsCreatedSession(body, list, spaces)) throw NativeApiException("native_creation_unconfirmed")
    }

    private suspend fun prepareContent(body: JSONObject): JSONArray {
        val id = body.getString("sessionId"); val request = body.getString("requestId")
        val attachments = body.optJSONArray("attachments")?.let(NativeLocalAttachment::parseList) ?: emptyList()
        val encodedBudget = body.getString("text").toByteArray(Charsets.UTF_8).size.toLong() + 32768L +
            attachments.filter { it.image }.sumOf { ((it.bytes + 2) / 3) * 4 }
        if (encodedBudget > 32L * 1024 * 1024) throw NativeApiException("native_attachment_batch_too_large")
        val content = JSONArray()
        if (body.getString("text").isNotBlank()) content.put(JSONObject().put("type", "text").put("text", body.getString("text")))
        if (attachments.any { it.image }) {
            val projection = api.post("projections", JSONObject().put("sessionId", id)) as? JSONObject ?: throw NativeApiException("malformed_response")
            val limits = projection.getJSONObject("values").getJSONObject("imageLimits")
            val images = attachments.filter { it.image }
            if (images.size > limits.getInt("maxImagesPerMessage") || images.sumOf { it.bytes } > limits.getLong("maxMessageImageBytes")
                || images.any { it.bytes > limits.getLong("maxImageBytes") || it.mediaType !in limits.getJSONArray("mediaTypes").strings() })
                throw NativeApiException("native_attachment_batch_too_large")
        }
        var receipts = body.optJSONObject("uploads") ?: JSONObject()
        if (uploadConnections[request]?.isCurrent() != true) { receipts = JSONObject(); body.put("uploads", receipts) }
        for (item in attachments) {
            if (item.image) {
                val bytes = mediaStore.bytes(item)
                content.put(JSONObject().put("type", "image").put("mediaType", item.mediaType).put("name", item.name)
                    .put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
            } else {
                var receipt = receipts.optJSONObject(item.id)
                if (receipt == null) {
                    val uploadConnection = NativeRuntimeHost.connection() ?: throw NativeApiException("runtime_unavailable")
                    receipt = api.uploadFile(id, item.name, item.bytes) { mediaStore.open(item) }
                    val file = receipt.getJSONObject("file")
                    if (file.getLong("bytes") != item.bytes || receipt.getString("receiptId").isBlank()) throw NativeApiException("malformed_response")
                    receipts.put(item.id, receipt); body.put("uploads", receipts)
                    if (!uploadConnection.isCurrent()) throw NativeApiException("runtime_unavailable")
                    uploadConnections[request] = uploadConnection
                    commitPending(body)
                }
                content.put(JSONObject().put("type", "file").put("receiptId", receipt.getString("receiptId")))
            }
        }
        return content
    }

    fun stop() {
        if (rejectManagedTarget()) return
        val id = _state.value.selectedSessionId ?: return
        if (activeSubmissionSessionId == id) activeSubmission?.cancel()
        viewModelScope.launch {
            try {
                api.post("cancel", JSONObject().put("sessionId", id))
                // Admission is not quiescence; the stream remains the authority for running.
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update { it.copy(error = errorCode(error)) }
            }
        }
    }

    fun loadOlder() {
        if (NativeCompanionWriteBarrier.frozen) return
        val id = _state.value.selectedSessionId ?: return
        val before = reducer.oldestSeq() ?: return
        if (!_state.value.hasMore || paging?.isActive == true) return
        val generation = selectionGeneration
        paging = viewModelScope.launch {
            try {
                val page = api.post("page", JSONObject()
                    .put("address", JSONObject().put("kind", "session").put("sessionId", id))
                    .put("throughSeq", reducer.cursor).put("beforeSeq", before).put("maxMessages", 60)) as? JSONObject
                    ?: throw NativeApiException("malformed_response")
                if (generation == selectionGeneration) {
                    reducer.prepend(page); loadedMessageBudget = maxOf(loadedMessageBudget, reducer.messages().size + 20)
                    preferences.edit().putInt("historyBudget:$id", loadedMessageBudget).apply()
                    publishMessages()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == selectionGeneration) _state.update { it.copy(error = errorCode(error)) }
            }
        }
    }

    fun refreshUsage() {
        if (NativeCompanionWriteBarrier.frozen) return
        val id = _state.value.selectedSessionId ?: return
        val generation = selectionGeneration
        viewModelScope.launch {
            try {
                val value = api.post("projections", JSONObject().put("sessionId", id)) as? JSONObject
                if (generation == selectionGeneration && value?.optString("kind") == "sequenced") {
                    reducer.replaceProjections(value); publishMessages()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == selectionGeneration) _state.update { it.copy(error = errorCode(error)) }
            }
        }
    }

    fun refreshModels() {
        if (NativeCompanionWriteBarrier.frozen) return
        if (modelRefresh?.isActive == true) return
        modelRefresh = viewModelScope.launch {
            try {
                val catalog = api.get("models") as? JSONObject ?: throw NativeApiException("malformed_response")
                val models = catalog.getJSONArray("groups").objects().flatMap { group ->
                    group.getJSONArray("models").objects().map { model ->
                        NativeModelJson.catalog(group.getString("id"), group.getString("name"), model)
                    }
                }
                val selection = catalog.getJSONObject("default")
                defaultModel = modelForSelection(selection, models)
                draftModel = draftModel?.let { selected ->
                    models.firstOrNull { it.provider == selected.provider && it.id == selected.id }
                        ?.copy(reasoningEffort = selected.reasoningEffort) ?: selected
                }
                _state.update { it.copy(models = models) }
                publishMessages()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update { it.copy(error = errorCode(error)) }
            }
        }
    }

    fun selectModel(model: NativeModel) {
        if (NativeCompanionWriteBarrier.frozen) return
        if (rejectManagedTarget()) return
        if (_state.value.modelChanging || _state.value.sending) return
        val id = _state.value.selectedSessionId
        if (id == null) {
            draftModel = model
            preferences.edit().putString("newModel", NativeModelJson.draft(model).toString()).apply()
            _state.update { it.copy(selectedModel = model) }
            return
        }
        _state.update { it.copy(modelChanging = true, error = null) }
        viewModelScope.launch {
            try {
                val request = JSONObject().put("sessionId", id).put("provider", model.provider).put("model", model.id)
                model.reasoningEffort?.let { request.put("reasoningEffort", it) }
                api.post("model", request)
                val projection = api.post("projections", JSONObject().put("sessionId", id)) as? JSONObject
                    ?: throw NativeApiException("malformed_response")
                if (_state.value.selectedSessionId == id && projection.optString("kind") == "sequenced") {
                    reducer.replaceProjections(projection); publishMessages()
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update { it.copy(error = errorCode(error)) }
            } finally { _state.update { it.copy(modelChanging = false) } }
        }
    }

    private fun modelForSelection(selection: JSONObject, catalog: List<NativeModel> = _state.value.models): NativeModel? {
        val provider = selection.getString("provider"); val id = selection.getString("model")
        if (provider.isBlank() || id.isBlank()) return null
        return (catalog.firstOrNull { it.provider == provider && it.id == id } ?: NativeModel(provider, id, id))
            .copy(reasoningEffort = if (selection.has("reasoningEffort")) selection.getString("reasoningEffort") else null)
    }

    private fun startFollow(id: String) {
        following?.cancel(); following = null
        if (NativeRuntimeHost.companionMaintenanceActive()) return
        _state.update { it.copy(sessionBaselineId = null) }
        val generation = selectionGeneration
        following = viewModelScope.launch {
            while (isActive && _state.value.selectedSessionId == id) {
                try {
                    _state.update { it.copy(sessionBaselineId = null) }
                    api.follow(id, loadedMessageBudget).collect { frame ->
                        if (generation != selectionGeneration) return@collect
                        if (frame.optString("type") == "snapshot" && frame.getJSONObject("header").getString("id") != id) {
                            throw NativeApiException("session_identity_conflict")
                        }
                        if (!reducer.accept(frame)) throw NativeApiException("stream_gap")
                        if (frame.optString("type") == "snapshot") {
                            submissions.rebaseline(id)
                            val projection = frame.getJSONObject("projections")
                            observeTurnBoundary(id, projection.getJSONObject("values").optJSONObject("droyTurnBoundary"), projection.getLong("asOfSeq"), historical = !unreadBaselineEstablished)
                        }
                        pending(id)?.let { pending ->
                            if (reducer.containsRequest(pending.getString("requestId"))) {
                                retireAccepted(pending)
                            }
                        }
                        if (generation != selectionGeneration || _state.value.selectedSessionId != id) return@collect
                        _state.update { it.copy(loading = false,
                            sessionBaselineId = if (frame.optString("type") == "snapshot") id else it.sessionBaselineId,
                            error = if (it.error in connectionErrors) null else it.error) }
                        if (frame.optString("type") == "assistant-stream" && frame.getJSONObject("frame").optString("type") == "chunk") {
                            // Every frame is folded; only presentation publication is coalesced.
                            if (rendering?.isActive != true) rendering = viewModelScope.launch { delay(16); publishMessages() }
                        } else {
                            rendering?.cancel(); rendering = null; publishMessages()
                        }
                        if (frame.optString("type") == "event" && frame.getJSONObject("event").optString("type") == "turn/end") {
                            refreshUsage(); refresh()
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (generation == selectionGeneration) _state.update { it.copy(loading = false, sessionBaselineId = null, error = errorCode(error)) }
                    if (!canReconnect(error)) break
                    delay(1000)
                }
            }
        }
    }

    private fun startControl() {
        controlling?.cancel()
        controlling = viewModelScope.launch {
            while (isActive) {
                try {
                    // Control baselines contain attached Sessions only. Read existing cold summaries
                    // before subscribing so reconnect does not erase results or mark all history unread.
                    val catalog = api.post("list") as? JSONObject ?: throw NativeApiException("malformed_response")
                    catalog.getJSONArray("items").objects().forEach { row ->
                        val block = row.optJSONObject("projections")
                        observeTurnBoundary(row.getString("sessionId"), block?.optJSONObject("values")?.optJSONObject("droyTurnBoundary"),
                            block?.optLong("asOfSeq", -1) ?: -1, sequenced = block?.optString("kind") == "sequenced",
                            historical = !unreadBaselineEstablished, publishNow = false)
                    }
                    unreadBaselineEstablished = true
                    publishUnread()
                    api.control().collect { frame ->
                        val selected = _state.value.selectedSessionId
                        when (frame.getString("type")) {
                            "baseline" -> {
                                val values = frame.getJSONObject("value").getJSONObject("projections")
                                values.keys().forEach { id ->
                                    val block = values.getJSONObject(id)
                                    observeTurnBoundary(id, block.getJSONObject("values").optJSONObject("droyTurnBoundary"), block.getLong("asOfSeq"), publishNow = false)
                                }
                                publishUnread()
                                selected?.let { id -> values.optJSONObject(id)?.getJSONObject("values")?.let { projection ->
                                    reducer.replaceProjections(values.getJSONObject(id)); publishMessages()
                                } }
                            }
                            "projection" -> {
                                val id = frame.getString("sessionId")
                                if (frame.getString("key") == "droyTurnBoundary") observeTurnBoundary(id, frame.optJSONObject("value"), frame.getLong("seq"))
                                if (selected == id) {
                                    reducer.updateProjection(frame.getString("key"), frame.get("value"), frame.getLong("seq")); publishMessages()
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    _state.update { it.copy(error = errorCode(error)) }
                    if (!canReconnect(error)) break
                    delay(1000)
                }
            }
        }
    }

    private fun startDecisions() {
        decisionObservation?.cancel()
        decisionObservation = viewModelScope.launch {
            while (isActive) {
                try {
                    api.decisions().collect { frame ->
                        decisionReducer.accept(frame)
                        _state.update { it.copy(decisions = decisionReducer.decisions()) }
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    _state.update { it.copy(error = errorCode(error)) }
                    if (!canReconnect(error)) break
                    delay(1000)
                }
            }
        }
    }

    fun answerApproval(id: String, allow: Boolean) {
        val decision = decisionReducer.find(id) ?: return
        if (decision.kind != "approval" || allow && !decision.canApprove) return
        submitDecision(decision, JSONObject().put("sessionId", decision.sessionId)
            .put("outcome", if (allow) "allowed-once" else "rejected"))
    }

    fun answerQuestion(id: String, answers: List<NativeQuestionAnswer>) {
        val decision = decisionReducer.find(id) ?: return
        if (decision.kind != "question") return
        val items = JSONArray()
        answers.forEach { answer ->
            val item = JSONObject().put("id", answer.id).put("selected", JSONArray(answer.selected))
            answer.custom?.let { item.put("custom", it) }
            items.put(item)
        }
        submitDecision(decision, JSONObject().put("sessionId", decision.sessionId).put("answers", items))
    }

    private fun submitDecision(decision: NativeDecision, body: JSONObject) {
        if (decision.answerPending || _state.value.answeringDecisionId != null) return
        _state.update { it.copy(answeringDecisionId = decision.id, error = null) }
        viewModelScope.launch {
            try {
                val receipt = api.answerDecision(decision.id, body) as? JSONObject
                    ?: throw NativeApiException("malformed_response")
                if (!receipt.getBoolean("accepted")) throw NativeApiException("malformed_response")
                decisionReducer.answersAccepted(decision.id)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (errorCode(error) == "NATIVE_DECISION_ENDED") decisionReducer.remove(decision.id)
                _state.update { it.copy(error = errorCode(error)) }
            } finally {
                _state.update { it.copy(answeringDecisionId = null, decisions = decisionReducer.decisions()) }
            }
        }
    }

    private fun publishMessages() {
        val id = _state.value.selectedSessionId
        val pending = id?.let(::pending)
        val messages = reducer.messages().toMutableList()
        if (id != null) messages += submissions.messages(id, reducer::containsRequest)
        if (pending != null && !reducer.containsRequest(pending.getString("requestId"))) {
            messages += NativeMessage("request:${pending.getString("requestId")}", "user", pending.getString("text"),
                kind = "pending", failed = pending.optString("status") == "uncertain" || pending.optString("status") == "prepared" && !_state.value.sending,
                attachments = NativeSubmissions.attachments(pending), requestId = pending.getString("requestId"))
        }
        val selected = reducer.projections.optJSONObject("modelSelection")?.let {
            it.optJSONObject("next") ?: it.optJSONObject("lastUsed")
        }
        val model = if (id == null) draftModel ?: defaultModel else selected?.let { modelForSelection(it) } ?: defaultModel
        val failure = reducer.latestFailure()?.takeIf { it.first != surfacedFailureSeq }
        if (failure != null) surfacedFailureSeq = failure.first
        _state.update { it.copy(messages = messages.map { message ->
            val duration = message.requestId?.let { request -> preferences.getLong("voice-duration:$id:$request", 0L).takeIf { it in 1..60_000L } }
            NativeSubmissions.withVoiceDuration(message, duration)
        }, historyCursor = reducer.cursor, running = reducer.running, hasMore = reducer.hasMore,
            usage = reducer.usage(), selectedModel = model, error = failure?.second ?: it.error,
            pendingRequestId = pending?.optString("requestId"),
            pendingCreationUnconfirmed = pending?.let { it.optBoolean("needsCreate") && it.optBoolean("createAttempted") } == true) }
    }

    private fun retireAcceptedPreferences(body: JSONObject, durable: Boolean) {
        synchronized(draftPersistenceLock) {
            val id = body.getString("sessionId")
            val raw = preferences.getString(pendingKey(id), null)?.let(::JSONObject)
            if (raw == null || raw.getString("requestId") != body.getString("requestId")) return
            val editor = preferences.edit().remove(pendingKey(id))
            if (body.optString("origin") == "voice") editor.putString("voice-receipt:${body.getString("requestId")}",
                JSONObject().put("draftId", body.getString("draftId")).put("sessionId", id).put("rpcId", body.getString("requestId")).toString())
            if (body.optString("origin", "draft") == "draft" && !body.optBoolean("draftConsumed")) {
                val draftId = body.optString("draftId", id)
                if (preferences.getString("draft:$draftId", "") == body.getString("text")) editor.putString("draft:$draftId", "")
                val sentIds = body.optJSONArray("attachments")?.let(NativeLocalAttachment::parseList)?.map { it.id }.orEmpty().toSet()
                val remaining = localAttachments(draftId).filterNot { it.id in sentIds }
                editor.putString("attachments:$draftId", JSONArray(remaining.map(NativeLocalAttachment::json)).toString())
            }
            if (durable) { if (!editor.commit()) throw NativeApiException("storage_error") } else editor.apply()
        }
    }
    private suspend fun retireAccepted(body: JSONObject) {
        withContext(Dispatchers.IO) { retireAcceptedPreferences(body, true) }
        if (!body.optBoolean("draftConsumed")) {
            val draft = _state.value.draftId
            _state.update { it.copy(draft = preferences.getString("draft:$draft", "") ?: "", draftAttachments = localAttachments(draft)) }
        }
        if (body.optString("origin", "draft") == "draft") body.optJSONArray("attachments")?.let(NativeLocalAttachment::parseList)
            ?.map { it.importId }?.distinct()?.forEach(::cleanupImportLater)
    }
    private suspend fun commitPending(body: JSONObject) = withContext(Dispatchers.IO) {
        synchronized(draftPersistenceLock) {
            val key = pendingKey(body.getString("sessionId"))
            // A history receipt may already have atomically retired this draft while the POST was returning.
            if (body.optString("status") == "accepted" && !preferences.contains(key)) return@synchronized
            val editor = preferences.edit().putString(key, body.toString())
            NativeSubmissions.voiceDuration(body)?.let { duration ->
                editor.putLong("voice-duration:${body.getString("sessionId")}:${body.getString("requestId")}", duration)
            }
            if (!editor.commit()) throw NativeApiException("storage_error")
        }
    }
    private fun persistDraft() = synchronized(draftPersistenceLock) {
        preferences.edit().putString(draftKey(_state.value.selectedSessionId), _state.value.draft).apply()
    }
    private fun pending(id: String): JSONObject? {
        val record = preferences.getString(pendingKey(id), null)?.let(::JSONObject) ?: return null
        if (!NativeSubmissions.blocksNewSubmission(record)) {
            if (record.optString("status") == "accepted") retireAcceptedPreferences(record, false)
            return null
        }
        return record
    }
    private fun localAttachments(id: String): List<NativeLocalAttachment> =
        NativeLocalAttachment.parseList(JSONArray(preferences.getString("attachments:$id", "[]")))
    private fun recoveredMaintenanceDraft(workspace: String?): String? =
        if (workspace != null) null else preferences.getString("maintenanceDraftId", null)?.takeUnless { preferences.contains("materialized:$it") }
    private fun blankKey(workspace: String?) = if (workspace == null) "blank-draft:null" else "blank-draft:id:$workspace"
    private fun blankDraftId(workspace: String?): String {
        preferences.getString(blankKey(workspace), null)?.let { if (!preferences.contains("materialized:$it")) return it }
        val id = UUID.randomUUID().toString()
        val editor = preferences.edit().putString(blankKey(workspace), id)
        // M1 had one global blank text draft; adopt it once without losing existing text.
        preferences.getString("draft:new", null)?.let { editor.putString("draft:$id", it).remove("draft:new") }
        editor.apply()
        return id
    }
    fun dismissError() { _state.update { it.copy(error = null) } }
    fun consumeWorkspaceNotice() { _state.update { it.copy(workspaceNotice = null) } }
    private fun rejectManagedTarget(): Boolean {
        val current = _state.value
        if (!current.readOnly) return false
        _state.update { it.copy(error = when {
            current.archived -> "workspace/session-archived"
            current.selectedSessionId == null -> "COMPANION_WORKSPACE_MANAGED"
            else -> "COMPANION_SESSION_MANAGED"
        }) }
        return true
    }
    private val connectionErrors = setOf(
        "network_error", "stream_disconnected", "stream_gap", "runtime_unavailable", "NATIVE_SERVICE_NOT_READY", "NATIVE_WORKSPACES_NOT_READY", "service_temporarily_unavailable",
    )
    private fun canReconnect(error: Exception): Boolean = errorCode(error) in connectionErrors
    private fun errorCode(error: Exception): String = (error as? NativeApiException)?.code ?: "malformed_response"
    private fun draftKey(id: String?): String = "draft:${id ?: blankId}"
    private fun pendingKey(id: String): String = "pending:$id"
}

private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
