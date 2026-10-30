package io.droyagent.app.nativeui.memory

import io.droyagent.app.nativeui.ui.DroySwitch as Switch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIconAction

@Composable
fun NativeMemoryScreen(
    state: NativeMemoryState,
    model: NativeMemoryViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPermissions: (() -> Unit)? = null,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    var discard by rememberSaveable { mutableStateOf(false) }
    var forgetting by rememberSaveable { mutableStateOf(false) }
    val feedback = state.feedback?.let { memoryMessage(it) }
    LaunchedEffect(state.page) { focus.clearFocus(); keyboard?.hide() }
    LaunchedEffect(state.feedback) {
        val code = state.feedback
        if (code != null && feedback != null) { snackbar.showSnackbar(feedback); model.consumeFeedback(code) }
    }
    fun back() {
        focus.clearFocus(); keyboard?.hide()
        when (state.page) {
            NativeMemoryPage.SETTINGS -> onBack()
            NativeMemoryPage.EDITOR -> if (state.editor?.dirty == true && state.unconfirmed == null && !state.busy) discard = true else model.leaveEditor()
            NativeMemoryPage.DETAIL -> model.page(NativeMemoryPage.RECORDS)
            NativeMemoryPage.APPS -> model.page(NativeMemoryPage.CAPTURE)
            else -> model.page(NativeMemoryPage.SETTINGS)
        }
    }
    BackHandler(enabled = state.page != NativeMemoryPage.SETTINGS) { back() }
    val title = stringResource(when (state.page) {
        NativeMemoryPage.SETTINGS -> R.string.native_memory_title
        NativeMemoryPage.CAPTURE -> R.string.native_memory_capture
        NativeMemoryPage.APPS -> R.string.native_memory_capture_scope
        NativeMemoryPage.ENDPOINTS -> R.string.native_memory_endpoints
        NativeMemoryPage.MAINTENANCE -> R.string.native_memory_maintenance
        NativeMemoryPage.RECORDS -> R.string.native_memory_manage
        NativeMemoryPage.DETAIL -> R.string.native_memory_details
        NativeMemoryPage.EDITOR -> if (state.editor?.creating == true) R.string.native_memory_new else R.string.native_memory_edit
    })
    Scaffold(modifier.fillMaxSize().testTag("native-memory"), containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0), snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DroyIconAction(R.drawable.native_companion_arrow_back, stringResource(R.string.native_memory_back), ::back)
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (state.page == NativeMemoryPage.RECORDS) DroyIconAction(R.drawable.native_schedule_add, stringResource(R.string.native_memory_new), model::create,
                        tonal = true, enabled = state.runtime.ready && state.status?.available == true && !state.busy && state.unconfirmed == null,
                        modifier = Modifier.testTag("native-memory-create"))
                    if (state.page == NativeMemoryPage.DETAIL && state.selected != null) {
                        DroyIconAction(R.drawable.native_ui_edit_square, stringResource(R.string.native_memory_edit), model::edit,
                            enabled = state.selected.editable && state.runtime.ready && state.status?.available == true && !state.busy && state.unconfirmed == null,
                            modifier = Modifier.testTag("native-memory-edit"))
                        if (state.selected.state in setOf("active", "candidate")) DroyIconAction(R.drawable.native_companion_delete,
                            stringResource(R.string.native_memory_forget), { forgetting = true },
                            enabled = state.runtime.ready && state.status?.managementAvailable == true && !state.busy && state.unconfirmed == null,
                            modifier = Modifier.testTag("native-memory-forget"))
                    }

                }
            }
        }, bottomBar = {
            if (state.page == NativeMemoryPage.EDITOR || state.page == NativeMemoryPage.APPS) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Button(onClick = { focus.clearFocus(); keyboard?.hide(); if (state.page == NativeMemoryPage.EDITOR) model.save() else model.saveApps() },
                        enabled = state.runtime.ready && !state.busy && state.unconfirmed == null && if (state.page == NativeMemoryPage.EDITOR)
                            state.status?.available == true && state.editor?.valid == true && (state.editor.creating || state.editor.dirty) && !state.conflict
                        else state.appScopeDirty && state.appScopeConsentAllowsSave && state.appAllow.size <= 128 && state.appExclude.size <= 128,
                        modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                            .padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 56.dp).testTag("native-memory-save")) {
                        Text(stringResource(if (state.busy) R.string.native_memory_saving else if (state.page == NativeMemoryPage.APPS) R.string.native_memory_save_scope else R.string.native_memory_save))
                    }
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (!state.runtime.ready && !state.runtime.active) MemoryCard(Modifier.padding(16.dp)) {
                Text(state.runtime.status.ifBlank { stringResource(if (state.runtime.active) R.string.native_engine_starting else R.string.native_engine_unavailable) })
                if (state.runtime.canStart) Button(onClick = model::startRuntime, modifier = Modifier.heightIn(min = 56.dp).testTag("native-memory-start-runtime")) {
                    Text(stringResource(R.string.native_start_engine))
                }
            }
            if (state.statusError != null) MemoryNotice(state.statusError, model::refresh)
            if (state.unconfirmed != null) MemoryUnconfirmed(state, model)
            if (state.conflict || state.error != null && state.error != "memory_result_unconfirmed") MemoryNotice(if (state.conflict) "MEMORY_REVISION_CONFLICT" else state.error!!,
                if (state.conflict) model::reloadEditor else null,
                actionText = stringResource(R.string.native_memory_reload_editor))
            when (state.page) {
                NativeMemoryPage.SETTINGS -> MemorySettings(state, model, onOpenPermissions, Modifier.weight(1f))
                NativeMemoryPage.CAPTURE -> MemoryCapture(state, model, Modifier.weight(1f))
                NativeMemoryPage.APPS -> MemoryApps(state, model, Modifier.weight(1f))
                NativeMemoryPage.ENDPOINTS -> MemoryEndpoints(state, model, Modifier.weight(1f))
                NativeMemoryPage.MAINTENANCE -> MemoryMaintenance(state, model, Modifier.weight(1f))
                NativeMemoryPage.RECORDS -> MemoryRecords(state, model, Modifier.weight(1f))
                NativeMemoryPage.DETAIL -> MemoryDetail(state, model, Modifier.weight(1f))
                NativeMemoryPage.EDITOR -> MemoryEditor(state, model, Modifier.weight(1f))
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.native_memory_discard_title)) },
        confirmButton = { TextButton(onClick = { discard = false; model.leaveEditor() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_memory_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_memory_keep_editing)) } })
    if (forgetting) AlertDialog(onDismissRequest = { forgetting = false }, title = { Text(stringResource(R.string.native_memory_forget_title)) },
        text = { Text(stringResource(R.string.native_memory_forget_scope)) },
        confirmButton = { TextButton(onClick = { forgetting = false; model.forget() }, modifier = Modifier.heightIn(min = 48.dp).testTag("native-memory-confirm-forget")) {
            Text(stringResource(R.string.native_memory_forget_confirm), color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = { forgetting = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_memory_cancel)) } })
}

@Composable
internal fun MemoryCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
internal fun MemorySwitch(title: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit, hint: String? = null, tag: String = "") {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChecked)
        .semantics(mergeDescendants = true) {}.testTag(tag).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun MemoryLink(title: String, onClick: () -> Unit, supporting: String? = null, enabled: Boolean = true, tag: String = "") {
    ListItem(headlineContent = { Text(title) }, supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { io.droyagent.app.nativeui.ui.DroyIcon(R.drawable.native_companion_chevron_right, null) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag(tag),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
}

@Composable
internal fun MemoryNotice(code: String, onAction: (() -> Unit)? = null, actionText: String = stringResource(R.string.native_memory_refresh)) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(memoryMessage(code), color = MaterialTheme.colorScheme.onErrorContainer)
            if (onAction != null) TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(actionText) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryUnconfirmed(state: NativeMemoryState, model: NativeMemoryViewModel) {
    val pending = state.unconfirmed ?: return
    MemoryCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("native-memory-unconfirmed")) {
        Text(stringResource(R.string.native_memory_unconfirmed))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = model::refresh, enabled = state.runtime.ready && !state.loading && !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_memory_refresh)) }
            if (pending.checked && pending.mutation.hasReceipt) TextButton(onClick = model::retryConfirmedIdentity,
                enabled = state.runtime.ready && !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("native-memory-retry-same")) { Text(stringResource(R.string.native_memory_retry_same)) }
            if (pending.checked && !pending.mutation.hasReceipt) TextButton(onClick = model::acknowledgeReview,
                enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_memory_review_done)) }
            if (state.editor != null && state.page != NativeMemoryPage.EDITOR) TextButton(onClick = model::resumeEditor, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_memory_resume_editor)) }
        }
    }
}

@Composable
internal fun memoryMessage(code: String): String = stringResource(when (code) {
    "memory_saved" -> R.string.native_memory_saved
    "memory_forgotten" -> R.string.native_memory_forgotten
    "memory_updated" -> R.string.native_memory_updated
    "memory_result_unconfirmed", "MEMORY_REQUEST_CANCELLED" -> R.string.native_memory_unconfirmed
    "network_error", "runtime_unavailable", "stream_disconnected" -> R.string.native_error_network
    "runtime_start_failed" -> R.string.native_engine_start_failed
    "malformed_response" -> R.string.native_error_response
    "MEMORY_REVISION_CONFLICT", "MEMORY_OPERATION_CONFLICT" -> R.string.native_memory_conflict
    "MEMORY_CURSOR_STALE", "MEMORY_INVALID_CURSOR" -> R.string.native_memory_cursor_stale
    "MEMORY_NOT_FOUND" -> R.string.native_memory_missing
    "MEMORY_READ_ONLY" -> R.string.native_memory_read_only
    "MEMORY_FEATURE_UNAVAILABLE" -> R.string.native_memory_feature_unavailable
    "MEMORY_PROTECTION_NOT_READY", "MEMORY_EXECUTION_NOT_READY", "MEMORY_HOST_CONTRACT_MISSING", "HISTORY_EXECUTION_NOT_READY", "MEMORY_REQUIRED_PLUGIN_MISSING", "MEMORY_NOT_INITIALIZED" -> R.string.native_memory_protection_wait
    "MEMORY_CAPACITY_REACHED", "MEMORY_CAPACITY", "MEMORY_STORAGE_LIMIT", "HISTORY_STORAGE_LIMIT" -> R.string.native_memory_storage_limited
    "MEMORY_ENDPOINT_REFRESH_REQUIRED", "PRIVACY_ROUTING_CHANGED" -> R.string.native_memory_endpoint_refresh
    "PRIVACY_ENDPOINT_NOT_AUTHORIZED", "PRIVACY_ENDPOINT_REVOKED" -> R.string.native_memory_endpoint_unauthorized
    "MEMORY_SOURCE_CHANGED", "MEMORY_SOURCE_FORGOTTEN", "PRIVACY_SOURCE_FORGOTTEN", "MEMORY_EXTRACTION_SOURCE_EXPIRED" -> R.string.native_memory_source_changed
    "MEMORY_EXTRACTION_BUDGET", "MEMORY_DAILY_BUDGET", "MEMORY_EXTRACTION_DAILY_BUDGET" -> R.string.native_memory_budget_limit
    "MEMORY_INVALID_INPUT", "MEMORY_INVALID_REQUEST", "MEMORY_INVALID_TIME", "MEMORY_INVALID_KIND", "HISTORY_INVALID_PACKAGES" -> R.string.native_memory_invalid
    "memory_invalid_range" -> R.string.native_memory_invalid_range
    "MEMORY_DISABLED", "MEMORY_MAINTENANCE_STOPPED" -> R.string.native_memory_maintenance_stopped
    else -> R.string.native_error_generic
})

@Composable
internal fun memoryValue(value: String): String = when (value) {
    "fact" -> stringResource(R.string.native_memory_fact)
    "preference" -> stringResource(R.string.native_memory_preference)
    "decision" -> stringResource(R.string.native_memory_decision)
    "task_summary" -> stringResource(R.string.native_memory_task_summary)
    "event" -> stringResource(R.string.native_memory_event)
    "activity" -> stringResource(R.string.native_memory_activity)
    "location" -> stringResource(R.string.native_memory_location)
    "visit" -> stringResource(R.string.native_memory_visit)
    "device" -> stringResource(R.string.native_memory_device)
    "gap" -> stringResource(R.string.native_memory_gap)
    "note" -> stringResource(R.string.native_memory_note)
    "user_statement" -> stringResource(R.string.native_memory_user_statement)
    "tool_observation" -> stringResource(R.string.native_memory_tool_observation)
    "execution_receipt" -> stringResource(R.string.native_memory_execution_receipt)
    "model_inference" -> stringResource(R.string.native_memory_model_inference)
    "user" -> stringResource(R.string.native_memory_user)
    "droy" -> stringResource(R.string.native_memory_droy)
    "droy_active" -> stringResource(R.string.native_memory_droy_active)
    "active" -> stringResource(R.string.native_memory_active)
    "candidate" -> stringResource(R.string.native_memory_candidate)
    "expired" -> stringResource(R.string.native_memory_expired)
    "deleted" -> stringResource(R.string.native_memory_deleted)
    "memory" -> stringResource(R.string.native_memory_personal)
    "derived" -> stringResource(R.string.native_memory_derived)
    "conversation" -> stringResource(R.string.native_memory_conversation)
    "session-title" -> stringResource(R.string.native_memory_session_title)
    "compaction" -> stringResource(R.string.native_memory_compaction)
    "extraction" -> stringResource(R.string.native_memory_extraction)
    "off" -> stringResource(R.string.native_memory_off)
    "paused" -> stringResource(R.string.native_memory_paused)
    "collecting" -> stringResource(R.string.native_memory_collecting)
    "storage_limited" -> stringResource(R.string.native_memory_storage_limited)
    "service_interrupted" -> stringResource(R.string.native_memory_service_interrupted)
    "waiting_execution_privacy" -> stringResource(R.string.native_memory_waiting_execution_privacy)
    "unknown" -> stringResource(R.string.native_memory_unknown)
    else -> value
}
