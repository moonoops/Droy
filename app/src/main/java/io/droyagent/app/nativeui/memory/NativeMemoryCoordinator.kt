package io.droyagent.app.nativeui.memory

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeRuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal class NativeMemoryCoordinator(
    private val repository: NativeMemoryRepository,
    private val scope: CoroutineScope,
    private val operationId: () -> String = { "native:${UUID.randomUUID()}" },
) {
    private val mutable = MutableStateFlow(NativeMemoryState())
    val state: StateFlow<NativeMemoryState> = mutable
    private var statusGeneration = 0L
    private var queryGeneration = 0L
    private var detailGeneration = 0L
    private var endpointGeneration = 0L
    private var appGeneration = 0L
    private var activeMutations = 0
    private val deterministic = setOf("MEMORY_REVISION_CONFLICT", "MEMORY_OPERATION_CONFLICT", "MEMORY_INVALID_INPUT", "MEMORY_INVALID_REQUEST",
        "MEMORY_INVALID_KIND", "MEMORY_INVALID_TIME", "MEMORY_INVALID_ID", "MEMORY_NOT_FOUND", "MEMORY_SOURCE_CHANGED", "MEMORY_SOURCE_FORGOTTEN",
        "MEMORY_READ_ONLY", "MEMORY_DISABLED", "MEMORY_PROTECTION_NOT_READY", "MEMORY_FEATURE_UNAVAILABLE", "MEMORY_ENDPOINT_REFRESH_REQUIRED",
        "PRIVACY_ROUTING_CHANGED", "PRIVACY_ENDPOINT_REVOKED", "PRIVACY_ENDPOINT_NOT_AUTHORIZED", "HISTORY_INVALID_SETTING", "HISTORY_INVALID_PACKAGES",
        "MEMORY_EXECUTION_NOT_READY", "MEMORY_REQUIRED_PLUGIN_MISSING",
        "MEMORY_NOT_INITIALIZED", "MEMORY_CAPACITY_REACHED", "HISTORY_EXECUTION_NOT_READY")

    fun runtime(value: NativeRuntimeState) { mutable.update { it.copy(runtime = value) } }
    fun report(code: String) { mutable.update { it.copy(error = code) } }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun consumeFeedback(code: String) { mutable.update { if (it.feedback == code) it.copy(feedback = null) else it } }

    fun page(value: NativeMemoryPage) {
        if (value != NativeMemoryPage.DETAIL && value != NativeMemoryPage.EDITOR) detailGeneration++
        mutable.update { it.copy(page = value, error = null) }
        when (value) {
            NativeMemoryPage.RECORDS -> if (!state.value.recordsLoaded) query()
            NativeMemoryPage.ENDPOINTS -> endpoints()
            NativeMemoryPage.APPS -> apps()
            else -> Unit
        }
    }

    fun refresh(includePage: Boolean = true): Job {
        val own = ++statusGeneration
        val reviewing = state.value.unconfirmed
        return scope.launch {
        if (own != statusGeneration) return@launch
        mutable.update { it.copy(loading = true) }
        try {
            val value = repository.status()
            if (own != statusGeneration) return@launch
            mutable.update {
                val config = value.capture?.configuration
                it.copy(status = value, loading = false, statusError = null,
                    records = if (value.managementAvailable) it.records else emptyList(),
                    query = if (value.managementAvailable) it.query else null,
                    recordsLoaded = value.managementAvailable && it.recordsLoaded,
                    selected = if (value.managementAvailable) it.selected else null,
                    appAllow = if (it.appScopeDirty) it.appAllow else config?.allowedPackages.orEmpty(),
                    appExclude = if (it.appScopeDirty) it.appExclude else config?.excludedPackages.orEmpty())
            }
            var verified = true
            if (includePage) {
                when (reviewing?.mutation?.operation) {
                    "grant", "revoke" -> { endpoints().join(); verified = state.value.endpointsError == null }
                    "record" -> { query(reset = true).join(); verified = value.managementAvailable && state.value.recordsError == null }
                    "revise", "forget" -> {
                        val id = JSONObject(reviewing.mutation.argsJson).getString("recordId")
                        readDetail(id, replaceEditor = false).join()
                        verified = value.managementAvailable && state.value.error in setOf(null, "MEMORY_REVISION_CONFLICT")
                    }
                    else -> when (state.value.page) {
                    NativeMemoryPage.RECORDS -> { query(reset = true).join(); verified = state.value.recordsError == null }
                    NativeMemoryPage.DETAIL, NativeMemoryPage.EDITOR -> {
                        state.value.selected?.recordId?.let { id -> readDetail(id, replaceEditor = false).join() }
                        verified = state.value.error in setOf(null, "MEMORY_REVISION_CONFLICT")
                    }
                    NativeMemoryPage.ENDPOINTS -> { endpoints().join(); verified = state.value.endpointsError == null }
                    NativeMemoryPage.APPS -> { apps().join(); verified = state.value.appsError == null }
                    else -> Unit
                    }
                }
                if (verified && own == statusGeneration && reviewing != null) mutable.update {
                    if (it.unconfirmed?.mutation == reviewing.mutation) it.copy(unconfirmed = it.unconfirmed?.copy(checked = true)) else it
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (own == statusGeneration) mutable.update { it.copy(loading = false, statusError = code(error)) } }
        }
    }

    fun filter(value: NativeMemoryFilter) {
        if (value.text.trim().toByteArray().size > 1024) { report("MEMORY_INVALID_INPUT"); return }
        try {
            if (value.from.isNotBlank() && value.to.isNotBlank() && !java.time.Instant.parse(value.to).isAfter(java.time.Instant.parse(value.from))) {
                report("memory_invalid_range"); return
            }
        } catch (_: java.time.DateTimeException) { report("MEMORY_INVALID_TIME"); return }
        queryGeneration++
        mutable.update { it.copy(filter = value, query = null, records = emptyList(), recordsLoaded = false, recordsError = null) }
        query()
    }
    fun query(reset: Boolean = true): Job {
        val current = state.value
        if (current.status?.managementAvailable != true || !reset && (current.query?.nextCursor == null || current.recordsLoading)) return Job().apply { complete() }
        val cursor = if (reset) null else current.query?.nextCursor
        val filter = current.filter
        val own = ++queryGeneration
        mutable.update { it.copy(recordsLoading = true, recordsError = null) }
        return scope.launch {
        if (own != queryGeneration) return@launch
        try {
            val value = repository.query(filter, cursor)
            if (own != queryGeneration || state.value.filter != filter) return@launch
            if (!reset && current.query?.revision != value.revision) throw NativeApiException("MEMORY_CURSOR_STALE")
            val rows = if (reset) value.items else (current.records + value.items).distinctBy { it.recordId }
            mutable.update { it.copy(records = rows, query = value, recordsLoaded = true, recordsLoading = false, recordsError = null) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (own == queryGeneration) mutable.update { it.copy(recordsLoading = false, recordsError = code(error)) } }
        }
    }

    fun open(id: String) {
        mutable.update { it.copy(page = NativeMemoryPage.DETAIL, selected = null, error = null) }
        readDetail(id)
    }
    private fun readDetail(id: String, replaceEditor: Boolean = false): Job {
        val own = ++detailGeneration
        return scope.launch {
        if (own != detailGeneration) return@launch
        mutable.update { it.copy(detailLoading = true) }
        try {
            val record = repository.get(id) ?: throw NativeApiException("MEMORY_NOT_FOUND")
            if (own != detailGeneration) return@launch
            mutable.update {
                val draft = it.editor
                val editor = if (replaceEditor && draft?.original?.recordId == id && record.editable) {
                    val original = draft.original
                    NativeMemoryDraft(record, record.kind,
                        if (draft.text != original.text.orEmpty()) draft.text else record.text.orEmpty(),
                        if (draft.place != original.placeName.orEmpty()) draft.place else record.placeName.orEmpty(),
                        if (draft.city != original.city.orEmpty()) draft.city else record.city.orEmpty())
                } else draft
                it.copy(selected = record, editor = editor, detailLoading = false,
                    conflict = if (replaceEditor && record.editable) false else it.conflict,
                    error = if (replaceEditor && !record.editable) "MEMORY_READ_ONLY" else if (it.conflict && !replaceEditor) "MEMORY_REVISION_CONFLICT" else null)
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (own == detailGeneration) mutable.update { it.copy(detailLoading = false, error = code(error)) } }
        }
    }
    fun create() {
        if (!writable()) return
        mutable.update { it.copy(page = NativeMemoryPage.EDITOR, selected = null, editor = NativeMemoryDraft(), conflict = false, error = null) }
    }
    fun edit() {
        val row = state.value.selected ?: return
        if (!writable() || !row.editable) return
        mutable.update { it.copy(page = NativeMemoryPage.EDITOR, editor = NativeMemoryDraft(row, row.kind, row.text.orEmpty(), row.placeName.orEmpty(), row.city.orEmpty()), conflict = false, error = null) }
    }
    fun draft(value: NativeMemoryDraft) {
        if (state.value.busy || state.value.unconfirmed != null) return
        mutable.update { it.copy(editor = value, error = null) }
    }
    fun leaveEditor() {
        mutable.update { it.copy(page = if (it.editor?.creating == false) NativeMemoryPage.DETAIL else NativeMemoryPage.RECORDS,
            editor = if (it.unconfirmed?.mutation?.hasReceipt == true) it.editor else null, conflict = false, error = null) }
    }
    fun resumeEditor() { if (state.value.editor != null) mutable.update { it.copy(page = NativeMemoryPage.EDITOR) } }
    fun reloadEditor() { state.value.editor?.original?.recordId?.let { readDetail(it, replaceEditor = true) } }
    fun save(): Job? {
        val draft = state.value.editor ?: return null
        if (!writable() || state.value.conflict || !draft.valid || !draft.creating && !draft.dirty) return null
        val mutation = try { NativeMemoryJson.mutation(draft, operationId()) } catch (error: Exception) { report(code(error)); return null }
        return execute(mutation)
    }
    fun forget(): Job? {
        val row = state.value.selected ?: return null
        if (!manageable() || row.state !in setOf("active", "candidate") || state.value.unconfirmed != null) return null
        return execute(NativeMemoryMutation("forget", JSONObject().put("operationId", operationId()).put("recordId", row.recordId)
            .put("expectedRevisionId", row.revisionId).toString(), true))
    }

    fun settings(key: String, enabled: Boolean): Job? {
        if (key !in setOf("enabled", "recallEnabled", "automaticExtraction")) return null
        if (!state.value.runtime.ready) return null
        if (enabled && !writable(allowEnabling = key == "enabled")) { report(state.value.status?.gateReason ?: "MEMORY_FEATURE_UNAVAILABLE"); return null }
        return execute(NativeMemoryMutation("settings", JSONObject().put(key, enabled).toString()), reducing = !enabled)
    }
    fun capture(key: String, enabled: Boolean): Job? {
        if (key !in setOf("enabled", "paused", "activityEnabled", "pageTextEnabled", "deviceStateEnabled", "locationEnabled")) return null
        val reducing = if (key == "paused") enabled else !enabled
        if (!state.value.runtime.ready) return null
        if (!reducing && !writable()) { report(state.value.status?.gateReason ?: "MEMORY_FEATURE_UNAVAILABLE"); return null }
        val patch = JSONObject().put(key, enabled)
        if (key == "enabled" && enabled) patch.put("paused", false)
        return captureConfigure(patch, reducing)
    }
    fun enableAllApplicationCapture(): Job? {
        if (!state.value.runtime.ready) return null
        if (!writable()) { report(state.value.status?.gateReason ?: "MEMORY_FEATURE_UNAVAILABLE"); return null }
        return captureConfigure(JSONObject().put("enabled", true).put("paused", false).put("allApplications", true)
            .put("allowedPackages", JSONArray()).put("excludedPackages", JSONArray()))
    }
    fun captureWithPermission(key: String, enabled: Boolean): Job = scope.launch {
        val saving = capture(key, enabled) ?: return@launch
        saving.join()
        if (!enabled || state.value.error != null || state.value.unconfirmed != null) return@launch
        val capture = state.value.status?.capture ?: return@launch
        if (key in setOf("activityEnabled", "pageTextEnabled") && capture.activityState == "waiting_permission") openPermission("accessibility")
        else if (key == "locationEnabled" && !capture.locationPermission) openPermission("location")
    }
    private fun captureConfigure(patch: JSONObject, reducing: Boolean = false) = execute(NativeMemoryMutation("capture",
        JSONObject().put("operation", "configure").put("args", patch).toString()), reducing)
    fun openPermission(source: String): Job? {
        if (source !in setOf("location", "backgroundLocation", "accessibility") || !state.value.runtime.ready || state.value.busy) return null
        return execute(NativeMemoryMutation("capture", JSONObject().put("operation", "permission").put("args", JSONObject().put("source", source)).toString()))
    }
    fun endpoints(): Job {
        val own = ++endpointGeneration
        return scope.launch {
        if (own != endpointGeneration) return@launch
        mutable.update { it.copy(endpointsLoading = true, endpointsError = null) }
        try {
            val value = repository.endpoints()
            if (own == endpointGeneration) mutable.update { it.copy(endpoints = value, endpointsLoaded = true, endpointsLoading = false) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (own == endpointGeneration) mutable.update { it.copy(endpointsLoading = false, endpointsError = code(error)) } }
        }
    }
    fun grant(endpoint: NativeMemoryEndpoint, categories: List<String>): Job? {
        if (!writable() || categories.isEmpty() || categories.any { it !in setOf("memory", "activity", "location", "derived") }) return null
        if (state.value.endpoints.none { it == endpoint }) { report("MEMORY_ENDPOINT_REFRESH_REQUIRED"); return null }
        return execute(NativeMemoryMutation("grant", JSONObject().put("id", endpoint.id).put("purpose", endpoint.purpose)
            .put("categories", JSONArray(categories.distinct())).toString()))
    }
    fun revoke(endpoint: NativeMemoryEndpoint): Job? {
        if (!state.value.runtime.ready) return null
        return execute(NativeMemoryMutation("revoke", JSONObject().put("id", endpoint.id).put("purpose", endpoint.purpose).toString()), reducing = true)
    }
    fun maintenance(id: String, retry: Boolean): Job? {
        if (!state.value.runtime.ready || state.value.status?.maintenanceAvailable != true || if (retry) !writable() else !manageable()) return null
        return execute(NativeMemoryMutation("maintenance", JSONObject().put("operation", if (retry) "retry" else "discard").put("id", id).toString()))
    }
    fun apps(): Job {
        val own = ++appGeneration
        return scope.launch {
        if (own != appGeneration) return@launch
        mutable.update { it.copy(appsLoading = true, appsError = null) }
        try {
            val value = repository.apps()
            if (own == appGeneration) mutable.update { it.copy(apps = value.items, appsLoaded = true, appsLoading = false, appsTruncated = value.truncated) }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (own == appGeneration) mutable.update { it.copy(appsLoading = false, appsError = code(error)) } }
        }
    }
    fun appScope(id: String, selection: Int) {
        if (state.value.busy || state.value.unconfirmed != null || selection !in 0..2) return
        mutable.update { it.copy(appAllow = (it.appAllow - id).let { rows -> if (selection == 1) rows + id else rows },
            appExclude = (it.appExclude - id).let { rows -> if (selection == 2) rows + id else rows }, appScopeDirty = true) }
    }
    fun saveApps(): Job? {
        val current = state.value
        if (!current.runtime.ready || !current.appScopeDirty || current.appAllow.size > 128 || current.appExclude.size > 128) return null
        if (current.status?.capture?.configuration == null) return null
        val reducing = current.appScopeReducesConsent
        if (!reducing && !writable()) return null
        return captureConfigure(JSONObject().put("allowedPackages", JSONArray(current.appAllow)).put("excludedPackages", JSONArray(current.appExclude)), reducing)
    }
    fun retryConfirmedIdentity(): Job? {
        val pending = state.value.unconfirmed ?: return null
        if (!pending.checked || !pending.mutation.hasReceipt || !state.value.runtime.ready || state.value.busy) return null
        return execute(pending.mutation, retry = true)
    }
    fun acknowledgeReview() {
        val pending = state.value.unconfirmed ?: return
        if (!pending.checked || pending.mutation.hasReceipt) return
        mutable.update { it.copy(unconfirmed = null, error = null) }
    }

    private fun execute(mutation: NativeMemoryMutation, reducing: Boolean = false, retry: Boolean = false): Job? {
        if (!state.value.runtime.ready || state.value.busy && !reducing || state.value.unconfirmed != null && !reducing && !retry) return null
        if (mutation.hasReceipt) { queryGeneration++; detailGeneration++ }
        statusGeneration++
        activeMutations++
        mutable.update { it.copy(busy = true, error = null, feedback = null, loading = false,
            recordsLoading = if (mutation.hasReceipt) false else it.recordsLoading, detailLoading = if (mutation.hasReceipt) false else it.detailLoading) }
        return scope.launch {
            try {
                val result = repository.mutate(mutation)
                if (mutation.hasReceipt) {
                    val receipt = result as? JSONObject ?: throw NativeApiException("malformed_response")
                    val id = receipt.opt("recordId") as? String ?: throw NativeApiException("malformed_response")
                    val revision = receipt.opt("revisionId") as? String ?: throw NativeApiException("malformed_response")
                    val expectedId = JSONObject(mutation.argsJson).optString("recordId", "")
                    if (id.isEmpty() || revision.isEmpty() || expectedId.isNotEmpty() && id != expectedId) throw NativeApiException("malformed_response")
                    mutable.update { it.copy(unconfirmed = if (it.unconfirmed?.mutation == mutation) null else it.unconfirmed,
                        feedback = if (mutation.operation == "forget") "memory_forgotten" else "memory_saved", editor = null, conflict = false,
                        page = if (it.page == NativeMemoryPage.EDITOR || mutation.operation == "forget") NativeMemoryPage.RECORDS else it.page,
                        selected = if (mutation.operation == "forget") null else it.selected, recordsLoaded = false) }
                    query().join()
                } else {
                    mutable.update { it.copy(feedback = if (mutation.operation == "capture" && JSONObject(mutation.argsJson).optString("operation") == "permission") null else "memory_updated") }
                    if (mutation.operation == "grant" || mutation.operation == "revoke") endpoints().join()
                    if (mutation.operation == "capture" && JSONObject(mutation.argsJson).optJSONObject("args")?.has("allowedPackages") == true)
                        mutable.update { it.copy(appScopeDirty = false) }
                }
                refresh(includePage = false).join()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val code = code(error)
                if (code in deterministic) mutable.update { it.copy(error = code, conflict = code == "MEMORY_REVISION_CONFLICT" && it.editor != null,
                    unconfirmed = if (it.unconfirmed?.mutation == mutation) null else it.unconfirmed) }
                else {
                    mutable.update { current -> current.copy(error = "memory_result_unconfirmed",
                        unconfirmed = if (current.unconfirmed?.mutation?.hasReceipt == true && !mutation.hasReceipt) current.unconfirmed else NativeMemoryUnconfirmed(mutation)) }
                    refresh(includePage = false).join()
                }
            } finally {
                activeMutations--
                mutable.update { it.copy(busy = activeMutations > 0) }
            }
        }
    }
    private fun manageable() = state.value.runtime.ready && state.value.status?.managementAvailable == true
    private fun writable(allowEnabling: Boolean = false) = state.value.runtime.ready && state.value.status != null && (allowEnabling || state.value.status?.available == true)
        && !state.value.busy && state.value.unconfirmed == null
    private fun code(error: Exception) = (error as? NativeApiException)?.code ?: "memory_operation_failed"
}
