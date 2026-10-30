package io.droyagent.app.nativeui.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.data.*

@Composable
fun DecisionContent(
    decisions: List<NativeDecision>, sessions: List<NativeSession>, sessionId: String?, answeringId: String?,
    onApproval: (String, Boolean) -> Unit, onQuestion: (String, List<NativeQuestionAnswer>) -> Unit, onClose: () -> Unit,
) {
    var selectedId by rememberSaveable {
        mutableStateOf(decisions.firstOrNull { it.sessionId == sessionId }?.id ?: decisions.singleOrNull()?.id)
    }
    val decision = decisions.firstOrNull { it.id == selectedId }
    LaunchedEffect(decisions.map { it.id }, selectedId) {
        // A withdrawn request must not silently turn into approval of another task.
        if (decisions.isEmpty() || selectedId != null && decision == null) onClose()
    }
    if (selectedId == null) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
            item { Text(stringResource(R.string.native_pending_requests), style = MaterialTheme.typography.headlineSmall) }
            items(decisions, key = { it.id }) { item ->
                ListItem(
                    headlineContent = { Text(stringResource(if (item.kind == "approval") R.string.native_approval else R.string.native_question)) },
                    supportingContent = { Text(sessions.firstOrNull { it.id == item.sessionId }?.title ?: item.sessionId) },
                    modifier = Modifier.clickable { selectedId = item.id }.heightIn(min = 64.dp),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                )
            }
            item { TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.native_close)) } }
        }
        return
    }
    if (decision == null) return
    val busy = decision.answerPending || answeringId == decision.id
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
        .padding(horizontal = 24.dp).testTag("native-decision"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(if (decision.kind == "approval") R.string.native_approval else R.string.native_question), style = MaterialTheme.typography.headlineSmall)
        Text(sessions.firstOrNull { it.id == decision.sessionId }?.title ?: decision.sessionId,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (decision.kind == "approval") {
            ApprovalContent(decision, busy, onApproval)
        } else {
            QuestionContent(decision, busy, onQuestion)
        }
        if (busy) Text(stringResource(if (decision.answerPending) R.string.native_answer_waiting else R.string.native_submitting),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).padding(bottom = 8.dp)) {
            Text(stringResource(R.string.native_close))
        }
    }
}

@Composable
private fun ApprovalContent(decision: NativeDecision, busy: Boolean, answer: (String, Boolean) -> Unit) {
    val language = LocalContext.current.resources.configuration.locales[0].language
    val reason = (if (language == "zh") decision.displayReasonZh else decision.displayReasonEn) ?: decision.reason
    decision.toolName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
    if (!reason.isNullOrBlank()) SelectionContainer { Text(reason, style = MaterialTheme.typography.bodyLarge) }
    if (!decision.toolArguments.isNullOrBlank()) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            SelectionContainer {
                Text(decision.toolArguments, modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
    if (!decision.canApprove) Text(stringResource(R.string.native_context_required), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onClick = { answer(decision.id, false) }, enabled = !busy,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("native-reject")) {
            Text(stringResource(R.string.native_reject))
        }
        Button(onClick = { answer(decision.id, true) }, enabled = !busy && decision.canApprove,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("native-allow-once")) {
            Text(stringResource(R.string.native_allow_once))
        }
    }
}

@Composable
private fun QuestionContent(decision: NativeDecision, busy: Boolean, answer: (String, List<NativeQuestionAnswer>) -> Unit) {
    val answers = remember(decision.id) { mutableStateMapOf<String, NativeQuestionAnswer>() }
    decision.questions.forEach { question ->
        key(decision.id, question.id) { QuestionInput(question, !busy) { answers[question.id] = it } }
    }
    val complete = decision.questions.isNotEmpty() && decision.questions.all { question ->
        answers[question.id]?.let { it.selected.isNotEmpty() || !it.custom.isNullOrBlank() } == true
    }
    Button(onClick = { answer(decision.id, decision.questions.map { answers.getValue(it.id) }) }, enabled = complete && !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-submit-answer")) {
        Text(stringResource(R.string.native_submit_answer))
    }
}

@Composable
private fun QuestionInput(question: NativeQuestion, enabled: Boolean, onAnswer: (NativeQuestionAnswer) -> Unit) {
    var selected by rememberSaveable(question.id) { mutableStateOf(arrayListOf<String>()) }
    var custom by rememberSaveable(question.id) { mutableStateOf("") }
    val known = question.options.map { it.label }.toSet()
    val current = selected.filter { it in known }
    LaunchedEffect(current, custom) { onAnswer(NativeQuestionAnswer(question.id, current, custom.trim().takeIf { it.isNotEmpty() })) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        question.header?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        Text(question.question, style = MaterialTheme.typography.titleMedium)
        question.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            SelectionContainer { Text(detail, style = MaterialTheme.typography.bodyLarge) }
        }
        question.options.forEach { option ->
            val chosen = option.label in current
            val toggle = {
                selected = if (question.multiSelect) ArrayList(if (chosen) current - option.label else current + option.label)
                    else arrayListOf(option.label)
                if (!question.multiSelect) custom = ""
            }
            val selection = if (question.multiSelect) Modifier.toggleable(chosen, enabled, Role.Checkbox) { toggle() }
                else Modifier.selectable(chosen, enabled, Role.RadioButton) { toggle() }
            Surface(shape = MaterialTheme.shapes.large,
                color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().then(selection).heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (question.multiSelect) Checkbox(checked = chosen, onCheckedChange = null, enabled = enabled)
                    else RadioButton(selected = chosen, onClick = null, enabled = enabled)
                    Column(Modifier.weight(1f)) {
                        Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        option.description?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        OutlinedTextField(value = custom, onValueChange = { value ->
            custom = value
            if (!question.multiSelect && value.isNotBlank()) selected = arrayListOf()
        }, enabled = enabled, label = { Text(stringResource(if (question.options.isEmpty()) R.string.native_your_answer else R.string.native_custom_answer)) },
            shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(), maxLines = 8)
    }
}
