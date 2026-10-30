package io.droyagent.app.nativeui.schedule

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeRuntimeState
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI-thread coordinator; generations protect navigation and committed receipts from old reads. */
internal class NativeSchedulesCoordinator(
    private val repository: NativeSchedulesRepository,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> String = { ZoneId.systemDefault().id },
) {
    private val mutable = MutableStateFlow(NativeSchedulesState())
    val state: StateFlow<NativeSchedulesState> = mutable.asStateFlow()
    private var catalogGeneration = 0L
    private var targetsGeneration = 0L
    private var historyGeneration = 0L

    fun runtime(value: NativeRuntimeState) { mutable.update { it.copy(runtime = value) } }
    fun report(code: String) { mutable.update { it.copy(error = code) } }
    fun dismissError() { mutable.update { it.copy(error = null, feedback = null) } }
    fun consumeFeedback(value: String) { mutable.update { if (it.feedback == value) it.copy(feedback = null) else it } }

    fun refresh(rebaseEditor: Boolean = false): Job {
        if (state.value.saving) return scope.launch { }
        val generation = ++catalogGeneration
        mutable.update { it.copy(loading = true) }
        return scope.launch {
            try {
                val rows = repository.catalog()
                if (generation != catalogGeneration) return@launch
                mutable.update { current ->
                    var next = current.copy(entries = rows, catalogLoaded = true, catalogError = null, loading = false)
                    if (rebaseEditor && current.page == NativeSchedulePage.EDITOR) {
                        val editor = current.editor
                        val key = editor?.let { draft -> draft.expected?.let { NativeScheduleKey(requireNotNull(draft.sessionId), it.id) } }
                        val fresh = rows.firstOrNull { it.key == key }
                        next = when {
                            editor == null || key == null -> next
                            fresh == null -> next.copy(error = "schedule_not_found")
                            fresh.status != "active" -> next.copy(error = "schedule_ended")
                            else -> next.copy(editor = NativeScheduleEdits.rebase(editor, fresh, zone()), error = null, uncertain = null, feedback = "schedule_reloaded")
                        }
                    }
                    reconcile(next)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == catalogGeneration) mutable.update { it.copy(loading = false, catalogError = code(error)) }
            }
        }
    }

    fun refreshTargets(): Job {
        val generation = ++targetsGeneration
        mutable.update { it.copy(targetsLoading = true) }
        return scope.launch {
            try {
                val targets = repository.targets()
                if (generation == targetsGeneration) mutable.update { it.copy(targets = targets.eligible, sessionTitles = targets.titles,
                    targetsLoaded = true, targetsLoading = false, targetsError = null) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == targetsGeneration) mutable.update { it.copy(targetsLoading = false, targetsError = code(error)) }
            }
        }
    }

    fun open(key: NativeScheduleKey) {
        if (state.value.entries.none { it.key == key }) { report("schedule_not_found"); return }
        historyGeneration++
        mutable.update { it.copy(page = NativeSchedulePage.DETAIL, selectedKey = key, history = NativeScheduleHistory(), error = null, feedback = null) }
    }

    fun beginEdit() {
        val current = state.value
        val selected = current.selected ?: return
        if (current.saving || current.uncertain != null) return
        if (selected.status != "active") { report("schedule_ended"); return }
        mutable.update { it.copy(page = NativeSchedulePage.EDITOR, editor = NativeScheduleEdits.edit(selected, zone()), error = null, feedback = null) }
    }

    fun beginCreate() {
        val current = state.value
        if (current.saving || current.uncertain != null) return
        if (!current.targetsLoaded || current.targetsError != null) { report(current.targetsError ?: "schedule_targets_unavailable"); refreshTargets(); return }
        if (current.targets.isEmpty()) { report("schedule_no_targets"); return }
        mutable.update { it.copy(page = NativeSchedulePage.EDITOR, selectedKey = null,
            editor = NativeScheduleEdits.create(current.targets.singleOrNull()?.id, now(), zone()), error = null, feedback = null) }
    }

    fun edit(transform: (NativeScheduleDraft) -> NativeScheduleDraft) {
        if (state.value.saving || state.value.uncertain != null) return
        mutable.update { it.copy(editor = it.editor?.let(transform), feedback = null) }
    }

    fun leaveEditor() {
        if (state.value.saving) return
        mutable.update { it.copy(page = if (it.selectedKey == null) NativeSchedulePage.CATALOG else NativeSchedulePage.DETAIL,
            editor = if (it.uncertain == null) null else it.editor, error = if (it.uncertain == null) null else it.error) }
    }

    fun catalogPage() { mutable.update { it.copy(page = NativeSchedulePage.CATALOG, feedback = null) } }
    fun historyPage() {
        if (state.value.selectedKey == null) return
        mutable.update { it.copy(page = NativeSchedulePage.HISTORY) }
        if (!state.value.history.loaded) history(reset = true)
    }
    fun detailPage() { mutable.update { it.copy(page = NativeSchedulePage.DETAIL) } }

    fun history(reset: Boolean): Job {
        val current = state.value
        val key = current.selectedKey ?: return scope.launch { }
        if (current.history.loading) return scope.launch { }
        val before = if (reset) null else current.history.nextBefore ?: return scope.launch { }
        val generation = ++historyGeneration
        mutable.update { it.copy(history = it.history.copy(loading = true, error = null)) }
        return scope.launch {
            try {
                val result = repository.history(key, before)
                if (generation != historyGeneration || state.value.selectedKey != key) return@launch
                mutable.update { latest -> when (result) {
                    is NativeScheduleHistoryResult.Rejected -> latest.copy(history = latest.history.copy(loading = false, error = result.code))
                    is NativeScheduleHistoryResult.Page -> {
                        if (before != null && result.value.nextBefore == before) latest.copy(history = latest.history.copy(loading = false, error = "malformed_response"))
                        else latest.copy(history = result.value.copy(records = if (reset) result.value.records
                            else (latest.history.records + result.value.records).distinctBy { it.messageId }))
                    }
                } }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == historyGeneration && state.value.selectedKey == key) mutable.update { it.copy(history = it.history.copy(loading = false, error = code(error))) }
            }
        }
    }

    fun save(): Job {
        val current = state.value
        val draft = current.editor ?: return scope.launch { }
        if (current.saving || current.uncertain != null) return scope.launch { }
        if (current.error in setOf("schedule_conflict", "schedule_ended", "schedule_not_found")) return scope.launch { }
        val request = try {
            NativeScheduleEdits.validate(draft, now())
            if (draft.creating) {
                if (current.targetsError != null || current.targets.none { it.id == draft.sessionId }) throw NativeApiException("schedule_target_required")
                NativeScheduleJson.createValue(draft)
                null
            } else NativeScheduleEdits.update(draft)?.also { NativeScheduleJson.updateValue(it) }
        } catch (error: NativeApiException) { report(error.code); return scope.launch { } }
        if (!draft.creating && request == null) {
            mutable.update { it.copy(page = NativeSchedulePage.DETAIL, editor = null, error = null, feedback = "schedule_unchanged") }
            return scope.launch { }
        }
        catalogGeneration++
        mutable.update { it.copy(saving = true, loading = false, error = null, feedback = null) }
        return scope.launch {
            try {
                if (draft.creating) {
                    val record = repository.create(draft)
                    committed(draft, record, updated = true)
                } else when (val result = repository.update(requireNotNull(request))) {
                    is NativeScheduleUpdateResult.Record -> committed(draft, result.record, result.updated)
                    is NativeScheduleUpdateResult.Rejected -> rejected(NativeScheduleOperation.UPDATE, draft, result.code)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                rejected(if (draft.creating) NativeScheduleOperation.CREATE else NativeScheduleOperation.UPDATE, draft, code(error))
            }
        }
    }

    fun delete(): Job {
        val current = state.value
        val key = current.selectedKey ?: return scope.launch { }
        if (current.saving || current.uncertain != null) return scope.launch { }
        catalogGeneration++
        mutable.update { it.copy(saving = true, loading = false, error = null, feedback = null) }
        return scope.launch {
            try {
                val result = repository.delete(key)
                if (state.value.selectedKey == key) historyGeneration++
                mutable.update {
                    val selected = it.selectedKey == key
                    it.copy(entries = it.entries.filterNot { row -> row.key == key }, selectedKey = if (selected) null else it.selectedKey,
                        page = if (selected) NativeSchedulePage.CATALOG else it.page, history = if (selected) NativeScheduleHistory() else it.history, saving = false,
                        error = if (result.deleted) null else result.code, feedback = if (result.deleted) "schedule_deleted" else null)
                }
                refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val errorCode = code(error)
                if (safeRejection(errorCode)) mutable.update { it.copy(saving = false, error = errorCode) }
                else {
                    mutable.update { it.copy(saving = false, error = "schedule_result_unconfirmed",
                        uncertain = NativeScheduleUncertain(NativeScheduleOperation.DELETE, key, null)) }
                    refresh()
                }
            }
        }
    }

    fun reviewCatalog() { mutable.update { it.copy(page = NativeSchedulePage.CATALOG) }; refresh() }
    fun acknowledgeReview() {
        if (state.value.uncertain?.checked != true || state.value.loading) return
        mutable.update { it.copy(uncertain = null, error = null, editor = null, page = NativeSchedulePage.CATALOG) }
    }

    private fun committed(draft: NativeScheduleDraft, record: NativeScheduleRecord, updated: Boolean) {
        val key = NativeScheduleKey(requireNotNull(draft.sessionId), record.id)
        val existing = state.value.entries.firstOrNull { it.key == key }
        val entry = existing?.copy(record = record) ?: NativeScheduleEntry(record, key.sessionId, "active")
        mutable.update {
            val ownsEditor = it.editor == draft
            val presenting = ownsEditor && it.page == NativeSchedulePage.EDITOR
            it.copy(entries = it.entries.filterNot { row -> row.key == key } + entry,
                selectedKey = if (presenting) key else it.selectedKey, page = if (presenting) NativeSchedulePage.DETAIL else it.page,
                editor = if (ownsEditor) null else it.editor, saving = false,
                uncertain = null, error = null, feedback = if (updated) "schedule_saved" else "schedule_unchanged")
        }
        refresh()
    }

    private fun rejected(operation: NativeScheduleOperation, draft: NativeScheduleDraft, error: String) {
        if (safeRejection(error)) {
            mutable.update { it.copy(saving = false, error = error) }
            if (error in setOf("schedule_conflict", "schedule_ended", "schedule_not_found")) refresh()
        } else {
            val key = draft.expected?.let { NativeScheduleKey(requireNotNull(draft.sessionId), it.id) }
            mutable.update { it.copy(saving = false, error = "schedule_result_unconfirmed", uncertain = NativeScheduleUncertain(operation, key, draft)) }
            refresh()
        }
    }

    private fun reconcile(value: NativeSchedulesState): NativeSchedulesState {
        val uncertain = value.uncertain ?: return value
        val current = value.entries.firstOrNull { it.key == uncertain.key }
        return when {
            uncertain.operation == NativeScheduleOperation.DELETE && current == null -> value.copy(
                page = if (value.selectedKey == uncertain.key) NativeSchedulePage.CATALOG else value.page,
                selectedKey = value.selectedKey.takeUnless { it == uncertain.key }, uncertain = null,
                history = if (value.selectedKey == uncertain.key) NativeScheduleHistory() else value.history, error = null, feedback = "schedule_deleted")
            uncertain.operation == NativeScheduleOperation.UPDATE && current != null && uncertain.draft?.let { NativeScheduleEdits.matches(it, current.record) } == true ->
                value.copy(page = if (value.page == NativeSchedulePage.EDITOR) NativeSchedulePage.DETAIL else value.page,
                    editor = if (value.editor == uncertain.draft) null else value.editor, uncertain = null, error = null, feedback = "schedule_saved")
            else -> value.copy(uncertain = uncertain.copy(checked = true))
        }
    }

    private fun safeRejection(value: String): Boolean = value in setOf("invalid_prompt", "invalid_selector", "invalid_rule", "invalid_time_zone", "not_future",
        "time_out_of_range", "frequency_too_high", "subagent_session", "schedule_conflict", "schedule_ended", "schedule_not_found",
        "NATIVE_SCHEDULES_BAD_REQUEST", "NATIVE_SCHEDULES_TOO_LARGE", "NATIVE_SCHEDULES_NOT_READY", "NATIVE_SCHEDULES_SESSION_NOT_FOUND",
        "NATIVE_SCHEDULES_TARGET_UNAVAILABLE", "COMPANION_SESSION_MANAGED")
    private fun code(error: Exception): String = (error as? NativeApiException)?.code ?: "request_failed"
}
