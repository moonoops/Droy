package io.droyagent.app.nativeui.engine

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIconAction
import java.text.NumberFormat
import java.util.Locale

/** Composer-only slash candidates. Selecting inserts text; preview uses the existing Session-bound file presenter. */
@Composable
fun NativeEngineSkillSuggestions(
    state: NativeEngineState, composerText: String, onAction: (NativeEngineAction) -> Unit,
    onInsert: (String) -> Unit, onPreviewSkill: (sessionId: String, path: String) -> Unit, modifier: Modifier = Modifier,
) {
    val match = Regex("^/([a-z0-9-]*)$").matchEntire(composerText) ?: return
    val query = match.groupValues[1]
    LaunchedEffect(state.sessionId, state.session?.agentPreset) { onAction(NativeEngineAction.LoadSkills) }
    val target = state.sessionId
    val rows = if (state.skillsSessionId == target && target != null && !state.skillsLoading && state.skillsError == null) state.skills.filter { query in it.name }
        .sortedWith(compareBy<NativeComposerSkill> { !it.name.startsWith(query) }.thenBy { it.name }) else emptyList()
    Surface(modifier.fillMaxWidth().testTag("native-engine-skill-suggestions"), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        LazyColumn(Modifier.heightIn(max = 320.dp), contentPadding = PaddingValues(8.dp)) {
            if (state.skillsLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (target == null) item { Text(stringResource(R.string.native_engine_skills_need_session), Modifier.padding(8.dp)) }
            else if (state.skillsError != null) item {
                Text(stringResource(R.string.native_engine_skills_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { onAction(NativeEngineAction.LoadSkills) }) { Text(stringResource(R.string.native_engine_refresh)) }
            } else if (!state.skillsLoading && rows.isEmpty()) item { Text(stringResource(R.string.native_engine_skills_empty), Modifier.padding(8.dp)) }
            items(rows, key = { it.name }) { skill ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onInsert("/${skill.name} ") }, enabled = state.runtime.ready && !state.busy, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("/${skill.name}", style = MaterialTheme.typography.titleSmall)
                            Text(skill.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (!skill.modelInvocable) Text(stringResource(R.string.native_engine_user_skill), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (skill.path != null && target != null) DroyIconAction(R.drawable.native_ui_description,
                        stringResource(R.string.native_engine_preview_skill, skill.name), { onPreviewSkill(target, skill.path) }, enabled = !state.busy)
                }
            }
        }
    }
}

/** May be embedded in the existing statistics sheet. Breakdown rows are independent heuristics, never added as a total. */
@Composable
fun NativeEngineContextSummary(context: NativeEngineContext?, modifier: Modifier = Modifier) {
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0] ?: Locale.ENGLISH
    val format = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 } }
    Column(modifier.testTag("native-engine-context"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.native_engine_context), style = MaterialTheme.typography.titleMedium)
        if (context == null) Text(stringResource(R.string.native_engine_context_unknown))
        else {
            ContextRow(R.string.native_engine_context_projected, context.projectedTokens, format)
            ContextRow(R.string.native_engine_context_sampled, context.pressureTokens, format)
            ContextRow(R.string.native_engine_context_window, context.contextWindow, format)
            HorizontalDivider()
            ContextRow(R.string.native_engine_context_system, context.systemTokens, format)
            ContextRow(R.string.native_engine_context_tools, context.toolsTokens, format)
            ContextRow(R.string.native_engine_context_messages, context.messageTokens, format)
            Text(stringResource(R.string.native_engine_context_reference), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun ContextRow(label: Int, value: Double?, format: NumberFormat) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value?.let { format.format(it) } ?: stringResource(R.string.native_engine_unknown), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun NativeEngineCompactControl(state: NativeEngineState, onAction: (NativeEngineAction) -> Unit, modifier: Modifier = Modifier) {
    val target = NativeEngineTarget(state.sessionId, state.draftId)
    val currentTarget by rememberUpdatedState(target)
    var confirm by remember(target) { mutableStateOf(false) }
    val operation = state.compaction
    Column(modifier.testTag("native-engine-compact"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { confirm = true }, enabled = state.runtime.ready && state.sessionId != null && !state.busy && operation?.active != true,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_engine_compact)) }
        if (operation != null) {
            Text(stringResource(when {
                state.compactCancelPending -> R.string.native_engine_compact_cancel_request
                operation.state == "starting" -> R.string.native_engine_compact_starting
                operation.state == "running" -> R.string.native_engine_compact_running
                operation.state == "cancelling" -> R.string.native_engine_compact_cancelling
                operation.state == "unknown" || operation.outcome == "unconfirmed" -> R.string.native_engine_compact_unknown
                operation.outcome == "success" -> R.string.native_engine_compact_done
                operation.outcome == "empty" -> R.string.native_engine_compact_empty
                operation.outcome == "cancelled" -> R.string.native_engine_compact_cancelled
                operation.outcome == "unavailable" -> R.string.native_engine_capability_unavailable
                else -> R.string.native_engine_compact_failed
            }), style = MaterialTheme.typography.bodyMedium)
            state.compactError?.let { EngineError(it) }
            Row {
                TextButton(onClick = { onAction(NativeEngineAction.RefreshCompact) }) { Text(stringResource(R.string.native_engine_refresh)) }
                if (operation.active) TextButton(onClick = { onAction(NativeEngineAction.CancelCompact) }, enabled = !state.compactCancelPending) { Text(stringResource(R.string.native_engine_cancel)) }
                else TextButton(onClick = { onAction(NativeEngineAction.DismissCompactReceipt) }) { Text(stringResource(R.string.native_engine_close)) }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.native_engine_compact)) }, text = {
        Text(stringResource(R.string.native_engine_compact_cost))
    }, confirmButton = { TextButton(onClick = { confirm = false; if (target.sessionId != null && currentTarget == target) onAction(NativeEngineAction.CompactAt(target)) }) { Text(stringResource(R.string.native_engine_confirm)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.native_engine_cancel)) } })
}
