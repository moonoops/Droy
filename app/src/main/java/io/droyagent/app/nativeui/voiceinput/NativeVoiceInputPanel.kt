package io.droyagent.app.nativeui.voiceinput

import android.text.format.DateUtils
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.droyagent.app.R

/** A docked input panel inherits the app's color, typography and reduced-motion behavior. */
@Composable
fun NativeVoiceInputPanel(
    state: NativeVoiceInputState,
    onAction: (NativeVoiceInputAction) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(phaseTitle(state.phase)), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { onAction(NativeVoiceInputAction.Leave) }) { Text(stringResource(R.string.native_voice_input_keyboard)) }
        }
        when {
            state.accepted -> Button(onClick = { onAction(NativeVoiceInputAction.Done) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.native_voice_input_done)) }
            state.recording || state.phase == NativeVoiceInputPhase.STARTING -> HoldControl(state, onAction)
            state.busy -> {
                CircularProgressIndicator()
                if (!state.sendAttempted) TextButton(onClick = { onAction(NativeVoiceInputAction.Cancel) }) { Text(stringResource(R.string.native_voice_input_cancel)) }
            }
            state.sendAttempted -> {
                if (state.durationMs > 0) Text(DateUtils.formatElapsedTime(state.durationMs / 1000), style = MaterialTheme.typography.labelLarge)
                if (state.transcript.isNotBlank()) Text(state.transcript, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { onAction(NativeVoiceInputAction.RetrySend) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.native_voice_input_retry_send)) }
                if (!state.permissions.runtimeReady) Button(onClick = { onAction(NativeVoiceInputAction.StartRuntime) }) { Text(stringResource(R.string.native_voice_input_start_engine)) }
            }
            state.operationId != null -> {
                if (state.fileReady) {
                    Text(DateUtils.formatElapsedTime(state.durationMs / 1000), style = MaterialTheme.typography.labelLarge)
                    if (state.transcript.isNotBlank()) {
                        Text(state.transcript, style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = { onAction(NativeVoiceInputAction.SendRecognized) }, enabled = state.canRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.native_voice_input_send)) }
                    }
                    OutlinedButton(onClick = { onAction(NativeVoiceInputAction.SendOriginal) }, enabled = state.canRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (state.target?.companionId != null) R.string.native_voice_input_send_original else R.string.native_voice_input_send_file))
                    }
                }
                TextButton(onClick = { onAction(NativeVoiceInputAction.Cancel) }) { Text(stringResource(R.string.native_voice_input_discard)) }
                when {
                    !state.permissions.runtimeReady -> Button(onClick = { onAction(NativeVoiceInputAction.StartRuntime) }) { Text(stringResource(R.string.native_voice_input_start_engine)) }
                    state.permissions.paused -> TextButton(onClick = onOpenPermissions) { Text(stringResource(R.string.native_voice_input_permissions)) }
                    !state.permissions.transcriptionAllowed -> TextButton(onClick = onRequestPermissions) { Text(stringResource(R.string.native_voice_input_authorize)) }
                    state.catalog?.ready != true -> TextButton(onClick = onOpenVoiceSettings) { Text(stringResource(R.string.native_voice_input_voice_settings)) }
                }
            }
            state.permissions.paused -> {
                Text(stringResource(R.string.native_voice_input_paused))
                Button(onClick = onOpenPermissions) { Text(stringResource(R.string.native_voice_input_permissions)) }
            }
            !state.permissions.runtimeReady -> Button(onClick = { onAction(NativeVoiceInputAction.StartRuntime) }) { Text(stringResource(R.string.native_voice_input_start_engine)) }
            !state.permissions.microphone || !state.permissions.microphoneAllowed -> {
                Text(stringResource(R.string.native_voice_input_permission))
                Button(onClick = onRequestPermissions) { Text(stringResource(R.string.native_voice_input_authorize)) }
            }
            else -> HoldControl(state, onAction)
        }
        state.error?.let { Text(stringResource(nativeVoiceInputErrorResource(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun HoldControl(state: NativeVoiceInputState, onAction: (NativeVoiceInputAction) -> Unit) {
    val current by rememberUpdatedState(state)
    val action by rememberUpdatedState(onAction)
    val haptics = LocalHapticFeedback.current
    val label = stringResource(if (state.cancelGesture) R.string.native_voice_input_cancel_release else if (state.recording) R.string.native_voice_input_release else R.string.native_voice_input_hold)
    Surface(shape = MaterialTheme.shapes.large,
        color = if (state.cancelGesture) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if (state.cancelGesture) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).semantics {
            role = Role.Button
            onClick(label) {
                if (current.operationId != null) action(NativeVoiceInputAction.FinishHold(false)) else action(NativeVoiceInputAction.BeginHold)
                true
            }
        }.focusable().onKeyEvent { event ->
            if (event.type == KeyEventType.KeyUp && event.key in setOf(Key.Enter, Key.Spacebar)) {
                if (current.operationId != null) action(NativeVoiceInputAction.FinishHold(false)) else action(NativeVoiceInputAction.BeginHold)
                true
            } else false
        }.pointerInput(Unit) {
            val threshold = 88.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!current.canRecord) return@awaitEachGesture
                down.consume(); action(NativeVoiceInputAction.BeginHold); haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                var ended = false; var cancel = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        if (pointer == null || !pointer.pressed) {
                            action(NativeVoiceInputAction.FinishHold(cancel)); ended = true; break
                        }
                        cancel = pointer.position.y - down.position.y < -threshold
                        action(NativeVoiceInputAction.MoveHold(cancel)); pointer.consume()
                    }
                } finally { if (!ended) action(NativeVoiceInputAction.FinishHold(true)) }
            }
        }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (state.recording) {
                LinearProgressIndicator(progress = { state.level.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(DateUtils.formatElapsedTime(state.durationMs / 1000), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.native_voice_input_slide_cancel), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun phaseTitle(phase: NativeVoiceInputPhase): Int = when (phase) {
    NativeVoiceInputPhase.STARTING -> R.string.native_voice_input_starting
    NativeVoiceInputPhase.RECORDING -> R.string.native_voice_input_recording
    NativeVoiceInputPhase.STOPPING, NativeVoiceInputPhase.CANCELLING -> R.string.native_voice_input_stopping
    NativeVoiceInputPhase.RECOGNIZING -> R.string.native_voice_input_recognizing
    NativeVoiceInputPhase.SENDING -> R.string.native_voice_input_sending
    NativeVoiceInputPhase.SENT -> R.string.native_voice_input_sent
    else -> R.string.native_voice_input_title
}
fun nativeVoiceInputErrorResource(code: String): Int = when (code) {
    "native_voice_permission" -> R.string.native_voice_input_permission
    "native_voice_paused" -> R.string.native_voice_input_paused
    "native_voice_pending" -> R.string.native_voice_input_pending
    "native_voice_storage", "storage_error" -> R.string.native_voice_input_storage
    "native_voice_owner" -> R.string.native_voice_input_owner
    "native_voice_limit", "NATIVE_SPEECH_TOO_LARGE", "native_attachment_batch_too_large" -> R.string.native_voice_input_limit
    "native_voice_too_short" -> R.string.native_voice_input_too_short
    "native_voice_playback" -> R.string.native_voice_input_playback_failed
    "native_voice_no_speech" -> R.string.native_voice_input_no_speech
    "native_voice_transcript_limit" -> R.string.native_voice_input_transcript_limit
    "native_voice_foreground" -> R.string.native_voice_input_foreground
    "native_voice_stop_unconfirmed", "native_voice_not_settled" -> R.string.native_voice_input_stop_unconfirmed
    "native_voice_send_unconfirmed", "pending_send_conflict", "native_creation_unconfirmed" -> R.string.native_voice_input_send_unconfirmed
    "native_voice_interrupted", "NATIVE_SPEECH_OPERATION_UNKNOWN", "NATIVE_SPEECH_RESULT_EXPIRED" -> R.string.native_voice_input_interrupted
    "runtime_unavailable", "NATIVE_SPEECH_NOT_READY" -> R.string.native_engine_unavailable
    else -> R.string.native_voice_input_failed
}
