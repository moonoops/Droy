package io.droyagent.app.nativeui.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.droyagent.app.R
import io.droyagent.app.nativeui.data.NativeMessage
import io.droyagent.app.nativeui.data.NativeModel
import io.droyagent.app.nativeui.engine.NativeEngineContext
import io.droyagent.app.nativeui.engine.NativeEngineState

internal fun workbenchMessageVisible(message: NativeMessage): Boolean = when {
    message.kind == "context" || message.kind == "reasoning" -> message.attachments.isNotEmpty()
    message.toolName != null || message.kind.startsWith("tool") -> message.attachments.isNotEmpty()
    else -> true
}

/** Observe unhandled taps after links, selection and scrolling have had first refusal. */
internal fun Modifier.dismissKeyboardOnUnconsumedTap(onDismiss: () -> Unit): Modifier = composed {
    val current by rememberUpdatedState(onDismiss)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            var handled = down.isConsumed
            val origin = down.position
            var movement = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                handled = handled || change.isConsumed
                movement = movement || (change.position - origin).getDistance() > viewConfiguration.touchSlop
                if (!change.pressed && !handled && !movement && change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) current()
            } while (event.changes.any { it.pressed })
        }
    }
}

internal class WorkbenchReadingSize(private val preferences: SharedPreferences) {
    var value by mutableIntStateOf(preferences.getInt("workbenchTextSize", 16).coerceIn(14, 22))
        private set
    fun reload() { value = preferences.getInt("workbenchTextSize", 16).coerceIn(14, 22) }
    fun set(size: Int) {
        require(size in 14..22)
        value = size
        preferences.edit().putInt("workbenchTextSize", size).apply()
    }
}

@Composable internal fun rememberWorkbenchReadingSize(): WorkbenchReadingSize {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("droy_native_reading", Context.MODE_PRIVATE) }
    val size = remember(prefs) { WorkbenchReadingSize(prefs) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "workbenchTextSize") size.reload() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return size
}

@Composable internal fun WorkbenchReadingTheme(size: Int, content: @Composable () -> Unit) {
    val ratio = size / 16f
    val base = MaterialTheme.typography
    fun androidx.compose.ui.text.TextStyle.scaled() = copy(fontSize = fontSize * ratio, lineHeight = lineHeight * ratio)
    MaterialTheme(typography = base.copy(bodyLarge = base.bodyLarge.scaled(), bodyMedium = base.bodyMedium.scaled(),
        bodySmall = base.bodySmall.scaled(), titleLarge = base.titleLarge.scaled(), titleMedium = base.titleMedium.scaled(),
        titleSmall = base.titleSmall.scaled(), headlineLarge = base.headlineLarge.scaled(), headlineMedium = base.headlineMedium.scaled(),
        headlineSmall = base.headlineSmall.scaled()), content = content)
}

@Composable internal fun workbenchEffortLabel(effort: String?): String = when(effort) {
    "off", "none" -> stringResource(R.string.native_effort_off)
    "minimal" -> stringResource(R.string.native_effort_minimal)
    "low" -> stringResource(R.string.native_effort_low)
    "medium" -> stringResource(R.string.native_effort_medium)
    "high" -> stringResource(R.string.native_effort_high)
    "xhigh", "max" -> stringResource(R.string.native_effort_max)
    null -> stringResource(R.string.native_effort_default)
    else -> effort
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun WorkbenchReasoningSheet(model: NativeModel?, onDismiss: () -> Unit, onChoose: (String?) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.native_reasoning_strength), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))
            val options: List<String?> = listOf(null) + model?.reasoningEfforts.orEmpty()
            options.forEach { effort ->
                ListItem(headlineContent = { Text(workbenchEffortLabel(effort)) }, leadingContent = { RadioButton(effort == model?.reasoningEffort, null) },
                    modifier = Modifier.clickable { onChoose(effort) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun WorkbenchMenu(onDismiss: () -> Unit, onTaskType: () -> Unit, onSpaces: () -> Unit, onFont: () -> Unit,
    onUsage: () -> Unit, onSchedules: () -> Unit, onVoice: (() -> Unit)?) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            DroyMenuActionRow(R.drawable.native_settings_hub, stringResource(R.string.native_task_type), onClick = onTaskType)
            DroyMenuActionRow(R.drawable.native_ui_folder, stringResource(R.string.native_spaces_chats), onClick = onSpaces)
            DroyMenuActionRow(R.drawable.native_ui_description, stringResource(R.string.native_workbench_font), onClick = onFont)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DroyMenuActionRow(R.drawable.native_ui_bar_chart, stringResource(R.string.native_usage), onClick = onUsage)
            DroyMenuActionRow(R.drawable.native_schedule_calendar_month, stringResource(R.string.native_schedule_title), onClick = onSchedules)
            if (onVoice != null) DroyMenuActionRow(R.drawable.native_companion_mic, stringResource(R.string.nsv_title), onClick = onVoice)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun WorkbenchTaskTypeSheet(state: NativeEngineState?, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    val catalog = state?.catalog
    val preset = catalog?.presets?.firstOrNull { it.id == catalog.defaultPreset }
    val enabled = state != null && state.runtime.ready && state.fresh && !state.busy && preset != null && !preset.broken &&
        (state.sessionId == null || state.session?.presetSelectable == true)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
            Text(stringResource(R.string.native_task_type), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))
            ListItem(headlineContent = { Text(stringResource(R.string.native_task_default)) },
                leadingContent = { RadioButton(state?.let { (it.session?.agentPreset ?: it.draftSelection?.agentPresetId ?: it.catalog?.defaultPreset) == preset?.id } == true, null) },
                modifier = Modifier.clickable(enabled = enabled) { preset?.let { onChoose(it.id) } })
        }
    }
}

@Composable internal fun WorkbenchContextRing(context: NativeEngineContext?, onClick: () -> Unit) {
    val used = context?.pressureTokens ?: context?.projectedTokens
    val capacity = context?.contextWindow
    val ratio = if (used != null && capacity != null && capacity > 0) (used / capacity).toFloat().coerceIn(0f, 1f) else null
    val label = if (ratio == null) stringResource(R.string.native_context_unknown) else stringResource(R.string.native_context_percent, (ratio * 100).toInt())
    val track = MaterialTheme.colorScheme.secondaryContainer
    val foreground = MaterialTheme.colorScheme.primary
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).testTag("native-context-control").semantics { contentDescription = label },
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
        Canvas(Modifier.size(24.dp)) {
            val stroke = 3.dp.toPx()
            drawCircle(track, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
            if (ratio != null) drawArc(foreground, -90f, 360f * ratio, false,
                topLeft = Offset(stroke / 2, stroke / 2), size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round))
            else drawCircle(foreground, radius = 2.dp.toPx())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun WorkbenchFontSheet(size: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    val sizeLabel = stringResource(R.string.native_font_size_value)
    var selected by remember { mutableIntStateOf(size) }
    var editing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf(size.toString()) }
    ModalBottomSheet(onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.native_workbench_font), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { input = selected.toString(); editing = !editing }, modifier = Modifier.testTag("native-font-value")) {
                    Text(selected.toString(), style = MaterialTheme.typography.titleMedium)
                }
            }
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp)) {
                Box(Modifier.padding(20.dp), contentAlignment = Alignment.CenterStart) {
                    Text(stringResource(R.string.native_font_preview), fontSize = selected.sp, lineHeight = (selected * 1.5f).sp)
                }
            }
            val slider = remember { SliderState(selected.toFloat(), steps = 7, trackRange = 14f..22f) }
            slider.value = selected.toFloat()
            Slider(state = slider, onValueChange = { selected = kotlin.math.round(it).toInt() },
                modifier = Modifier.fillMaxWidth().testTag("native-font-slider")
                    .semantics { contentDescription = sizeLabel })
            if (editing) OutlinedTextField(input, { value ->
                if (value.length <= 2 && value.all(Char::isDigit)) {
                    input = value
                    value.toIntOrNull()?.takeIf { it in 14..22 }?.let { selected = it }
                }
            }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.native_font_size_value)) },
                isError = input.toIntOrNull()?.let { it in 14..22 } != true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { selected = 16; input = "16" }) { Text(stringResource(R.string.native_font_reset)) }
                Button(onClick = { onSave(selected); onDismiss() }, enabled = !editing || input.toIntOrNull()?.let { it in 14..22 } == true) {
                    Text(stringResource(R.string.native_font_apply))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun WorkbenchGoalSheet(state: NativeEngineState, onDismiss: () -> Unit, onAction: (io.droyagent.app.nativeui.engine.NativeEngineAction) -> Unit) {
    val goal = state.session?.goal
    var objective by remember(state.sessionId, goal?.id) { mutableStateOf("") }
    val target = io.droyagent.app.nativeui.engine.NativeEngineTarget(state.sessionId, state.draftId)
    val enabled = state.runtime.ready && !state.busy && state.session?.fresh == true && !state.sessionLoading
    fun change(action: String, text: String? = null) { onAction(io.droyagent.app.nativeui.engine.NativeEngineAction.Goal(action, text, target, goal)) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.native_goal_title), style = MaterialTheme.typography.titleLarge)
            if (goal != null) {
                Text(goal.objective, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(when(goal.phase) {
                    "active" -> if (goal.activation == "armed") R.string.native_goal_active else R.string.native_goal_paused
                    "paused" -> R.string.native_goal_paused
                    "blocked" -> R.string.native_goal_blocked
                    else -> R.string.native_goal_complete
                }), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.native_goal_rounds, goal.roundsStarted, goal.maxGoalRounds), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (goal.phase == "active" && goal.activation == "armed") FilledTonalButton(onClick = { change("pause") }, enabled = enabled) { Text(stringResource(R.string.native_work_pause)) }
                    if (goal.phase in listOf("paused", "blocked") || goal.phase == "active" && goal.activation != "armed") FilledTonalButton(onClick = { change("resume") }, enabled = enabled) { Text(stringResource(R.string.native_goal_resume)) }
                    TextButton(onClick = { change("clear") }, enabled = enabled) { Text(stringResource(R.string.native_goal_clear)) }
                }
            }
            if (goal == null || goal.phase == "complete") {
                OutlinedTextField(objective, { if (it.length <= 16000) objective = it }, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                    label = { Text(stringResource(R.string.native_goal_objective)) }, minLines = 3, maxLines = 7)
                Button(onClick = { change("create", objective.trim()) }, enabled = enabled && objective.isNotBlank(), modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.native_goal_start))
                }
            }
            state.error?.let { Text(stringResource(R.string.native_engine_failed), color = MaterialTheme.colorScheme.error) }
        }
    }
}
