package io.droyagent.app.nativeui.voiceinput

import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIcon
import kotlin.math.sin

/** The same pointer owns press, upward cancellation and release, even while capture state changes. */
@Composable
fun NativeHoldToRecordButton(
    state: NativeVoiceInputState,
    onBegin: () -> Unit,
    onAction: (NativeVoiceInputAction) -> Unit,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(state)
    val begin by rememberUpdatedState(onBegin)
    val action by rememberUpdatedState(onAction)
    val authorize by rememberUpdatedState(onRequestPermissions)
    val haptics = LocalHapticFeedback.current
    val label = stringResource(R.string.native_voice_input_hold)
    fun accessibleToggle() {
        when {
            current.recording -> action(NativeVoiceInputAction.FinishHold(false))
            !current.permissions.microphone || !current.permissions.microphoneAllowed -> authorize()
            current.operationId == null && !current.busy -> begin()
        }
    }
    Surface(shape = MaterialTheme.shapes.large,
        color = if (state.recording) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = modifier.size(48.dp).testTag("native-hold-to-record").semantics {
            role = Role.Button
            contentDescription = label
            onClick(label) { accessibleToggle(); true }
        }.focusable().onKeyEvent {
            if (it.type == KeyEventType.KeyUp && it.key in setOf(Key.Enter, Key.Spacebar)) { accessibleToggle(); true } else false
        }.pointerInput(Unit) {
            val cancellationDistance = 72.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (current.operationId != null || current.busy) return@awaitEachGesture
                down.consume()
                if (!current.permissions.microphone || !current.permissions.microphoneAllowed) {
                    authorize()
                    // Permission completion never starts recording after this press has ended.
                    return@awaitEachGesture
                }
                begin()
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                var released = false
                var cancelled = false
                try {
                    while (true) {
                        val pointer = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        if (pointer == null || !pointer.pressed) {
                            action(NativeVoiceInputAction.FinishHold(cancelled)); released = true; break
                        }
                        cancelled = pointer.position.y - down.position.y < -cancellationDistance
                        action(NativeVoiceInputAction.MoveHold(cancelled))
                        pointer.consume()
                    }
                } finally { if (!released) action(NativeVoiceInputAction.FinishHold(true)) }
            }
        }) {
        Box(contentAlignment = Alignment.Center) { DroyIcon(R.drawable.native_companion_mic, null) }
    }
}

/** Audio amplitude drives this waveform; it is not an invented recording/progress percentage. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NativeVoiceInputStatus(
    state: NativeVoiceInputState,
    onAction: (NativeVoiceInputAction) -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onPreview: (() -> Unit)? = null,
    previewing: Boolean = state.previewing,
) {
    if (!state.visible || state.showPendingBubble || state.accepted || state.operationId == null && state.error == null) return
    val recording = state.recording || state.phase == NativeVoiceInputPhase.STARTING
    val level by animateFloatAsState(state.level.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f,
        MaterialTheme.motionScheme.fastEffectsSpec(), label = "hold-voice-level")
    val color = if (state.cancelGesture) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().testTag("native-inline-voice")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (recording) Canvas(Modifier.weight(1f).height(32.dp)) {
                    val path = Path()
                    val amplitude = (level * size.height * .42f)
                    for (i in 0..100) {
                        val x = size.width * i / 100f
                        val y = size.height / 2f + sin(i / 100f * Math.PI * 8).toFloat() * amplitude
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
                } else {
                    if (state.source != null) IconButton(onClick = onPreview ?: { onAction(NativeVoiceInputAction.TogglePreview) }) {
                        DroyIcon(if (previewing) R.drawable.native_ui_stop else R.drawable.native_audio_play,
                            stringResource(if (previewing) R.string.native_voice_stop_preview else R.string.native_voice_preview))
                    }
                    Text(stringResource(when (state.phase) {
                        NativeVoiceInputPhase.RECOGNIZING, NativeVoiceInputPhase.STOPPING -> R.string.native_voice_input_processing
                        NativeVoiceInputPhase.SENDING -> R.string.native_voice_input_sending
                        NativeVoiceInputPhase.SENT -> R.string.native_voice_input_sent
                        else -> R.string.native_voice_input_title
                    }), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                if (recording) Text(stringResource(if (state.cancelGesture) R.string.native_voice_input_cancel_release else R.string.native_voice_input_slide_cancel),
                    color = color, style = MaterialTheme.typography.labelMedium)
                Text(DateUtils.formatElapsedTime(state.durationMs / 1000), style = MaterialTheme.typography.labelLarge)
            }
            if (!recording && !state.accepted && !state.busy) Row {
                if (state.sendAttempted) TextButton(onClick = { onAction(NativeVoiceInputAction.RetrySend) }) { Text(stringResource(R.string.native_voice_input_retry_send)) }
                else if (state.canRetry) TextButton(onClick = { onAction(NativeVoiceInputAction.SendOriginal) }) { Text(stringResource(R.string.native_voice_input_send)) }
                if (!state.sendAttempted) TextButton(onClick = { onAction(NativeVoiceInputAction.Cancel) }) { Text(stringResource(R.string.native_voice_input_discard)) }
            }
            state.error?.let { Text(stringResource(nativeVoiceInputErrorResource(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (!state.permissions.permitted && !recording) TextButton(onClick = onOpenPermissions) { Text(stringResource(R.string.native_voice_input_permissions)) }
        }
    }
}

/** Optimistic original audio, shown as soon as release transfers capture into the send pipeline. */
@Composable
fun NativePendingVoiceBubble(
    state: NativeVoiceInputState,
    onAction: (NativeVoiceInputAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.testTag("native-pending-voice-bubble"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        io.droyagent.app.nativeui.ui.VoiceMessageContent(state.durationMs,
            onPlay = { onAction(NativeVoiceInputAction.TogglePreview) }, enabled = state.source != null, playing = state.previewing)
    }
}
