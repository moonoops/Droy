package io.droyagent.app.nativeui.standardvoice

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIconAction
import io.droyagent.app.nativeui.ui.DroySwitch as Switch

sealed interface NativeStandardVoiceAction {
    data object Back : NativeStandardVoiceAction
    data object Start : NativeStandardVoiceAction
    data class SetMuted(val muted: Boolean) : NativeStandardVoiceAction
    data class SetReadAloud(val enabled: Boolean) : NativeStandardVoiceAction
    data object StopReadout : NativeStandardVoiceAction
    data object End : NativeStandardVoiceAction
    data object OpenConversation : NativeStandardVoiceAction
    data object RequestPermission : NativeStandardVoiceAction
    data object Reconcile : NativeStandardVoiceAction
    data object ResumeWake : NativeStandardVoiceAction
    data class Retry(val nonce: String) : NativeStandardVoiceAction
    data class InsertDraft(val nonce: String) : NativeStandardVoiceAction
    data class Discard(val nonce: String) : NativeStandardVoiceAction
    data class Copy(val nonce: String) : NativeStandardVoiceAction
}

/** Buttons only dispatch host actions; composition never starts or authorizes capture. */
@Composable
fun NativeStandardVoiceScreen(
    state: NativeStandardVoiceState,
    targetLabel: String,
    onAction: (NativeStandardVoiceAction) -> Unit,
    modifier: Modifier = Modifier,
    canInsertRetained: Boolean = false,
) {
    val controls = standardVoiceUiControls(state)
    val hasUnknown = state.pending.any { it.delivery in setOf(NativeStandardVoiceDelivery.UNKNOWN, NativeStandardVoiceDelivery.SUBMITTING) }
    val error = state.error ?: "native_standard_voice_disabled".takeIf { state.config?.enabled == false }
    BackHandler { onAction(NativeStandardVoiceAction.Back) }
    Scaffold(modifier.fillMaxSize().testTag("native-standard-voice"),
        containerColor = MaterialTheme.colorScheme.surface, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    DroyIconAction(R.drawable.native_settings_arrow_back, stringResource(R.string.nsv_back),
                        { onAction(NativeStandardVoiceAction.Back) }, Modifier.testTag("native-standard-voice-back"))
                    Text(stringResource(R.string.nsv_title), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(start = 8.dp, end = 16.dp))
                }
            }
        }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).navigationBarsPadding().imePadding()
            .testTag("native-standard-voice-body"), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "status") {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (targetLabel.isNotBlank()) Text(targetLabel, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("native-standard-voice-target"))
                        Text(stringResource(standardVoiceStatusResource(state)), style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.testTag("native-standard-voice-status").semantics { liveRegion = LiveRegionMode.Polite })
                        VoiceAmplitude(state)
                        if (controls.showStart) Button(onClick = { onAction(NativeStandardVoiceAction.Start) }, enabled = controls.canStart,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-standard-voice-start")) {
                            Text(stringResource(R.string.nsv_start))
                        }
                        if (controls.showMicrophone) FilledTonalButton(
                            onClick = { onAction(NativeStandardVoiceAction.SetMuted(!state.userMuted)) },
                            enabled = if (state.userMuted) controls.canResumeMicrophone else controls.canPauseMicrophone,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-standard-voice-microphone")) {
                            Text(stringResource(if (state.userMuted) R.string.nsv_resume_mic else R.string.nsv_pause_mic))
                        }
                    }
                }
            }
            error?.let { code -> item(key = "error") {
                Text(stringResource(standardVoiceErrorResource(code)), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 8.dp).testTag("native-standard-voice-error").semantics { liveRegion = LiveRegionMode.Polite })
            } }
            if (controls.canRequestPermission) item(key = "permission") {
                Button(onClick = { onAction(NativeStandardVoiceAction.RequestPermission) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-standard-voice-permission")) {
                    Text(stringResource(R.string.nsv_permission))
                }
            }
            if (controls.canResumeWake) item(key = "resume-wake") {
                FilledTonalButton(onClick = { onAction(NativeStandardVoiceAction.ResumeWake) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-standard-voice-resume-wake")) {
                    Text(stringResource(R.string.nsv_resume_wake))
                }
            }
            if (controls.needsReconcile && !hasUnknown) item(key = "reconcile") {
                ReconcileButton(onAction, "native-standard-voice-reconcile")
            }
            item(key = "readout") {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(state.readAloud, enabled = controls.canReadAloud,
                            role = Role.Switch, onValueChange = { onAction(NativeStandardVoiceAction.SetReadAloud(it)) })
                            .testTag("native-standard-voice-read-aloud"), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.nsv_read_aloud), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Switch(checked = state.readAloud, onCheckedChange = null, enabled = controls.canReadAloud)
                        }
                        if (controls.showStopReadout) TextButton(onClick = { onAction(NativeStandardVoiceAction.StopReadout) },
                            enabled = controls.canStopReadout,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-standard-voice-stop-readout")) {
                            Text(stringResource(R.string.nsv_stop_readout))
                        }
                    }
                }
            }
            if (controls.showEnd) item(key = "end") {
                OutlinedButton(onClick = { onAction(NativeStandardVoiceAction.End) }, enabled = controls.canEnd,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-standard-voice-end")) {
                    Text(stringResource(R.string.nsv_end))
                }
            }
            if (state.journal?.submissions?.any { it.receipt != null } == true) item(key = "conversation") {
                TextButton(onClick = { onAction(NativeStandardVoiceAction.OpenConversation) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-standard-voice-conversation")) {
                    Text(stringResource(R.string.nsv_open_conversation))
                }
            }
            if (state.partialTranscript.isNotBlank()) item(key = "transcript") {
                VoiceTextCard(R.string.nsv_transcript, state.partialTranscript, "native-standard-voice-transcript")
            }
            if (state.reply.isNotBlank()) item(key = "reply") {
                VoiceTextCard(R.string.nsv_reply, state.reply, "native-standard-voice-reply")
            }
            items(state.pending, key = { it.nonce }) { row ->
                RetainedTranscript(row, canInsertRetained, onAction)
            }

        }
    }
}

/** Embedded beneath the current conversation; its controls retain the existing session ownership rules. */
@Composable
fun NativeStandardVoicePanel(
    state: NativeStandardVoiceState,
    onAction: (NativeStandardVoiceAction) -> Unit,
    modifier: Modifier = Modifier,
    canInsertRetained: Boolean = false,
) {
    val controls = standardVoiceUiControls(state)
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth().testTag("native-standard-voice-panel")) {
        LazyColumn(Modifier.heightIn(max = 360.dp), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(standardVoiceStatusResource(state)), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    DroyIconAction(R.drawable.native_ui_close, stringResource(R.string.nsv_end), { onAction(NativeStandardVoiceAction.Back) }, Modifier.testTag("native-standard-voice-back"))
                }
                VoiceAmplitude(state)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (controls.showStart) FilledTonalButton(onClick = { onAction(NativeStandardVoiceAction.Start) }, enabled = controls.canStart, modifier = Modifier.testTag("native-standard-voice-start")) { Text(stringResource(R.string.nsv_start)) }
                    if (controls.showMicrophone) FilledTonalButton(onClick = { onAction(NativeStandardVoiceAction.SetMuted(!state.userMuted)) },
                        enabled = if (state.userMuted) controls.canResumeMicrophone else controls.canPauseMicrophone) {
                        Text(stringResource(if (state.userMuted) R.string.nsv_resume_mic else R.string.nsv_pause_mic))
                    }
                    if (controls.showEnd) TextButton(onClick = { onAction(NativeStandardVoiceAction.End) }, enabled = controls.canEnd) { Text(stringResource(R.string.nsv_end)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.nsv_read_aloud), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(state.readAloud, { onAction(NativeStandardVoiceAction.SetReadAloud(it)) }, enabled = controls.canReadAloud)
                }
                if (controls.showStopReadout) TextButton(onClick = { onAction(NativeStandardVoiceAction.StopReadout) }, enabled = controls.canStopReadout) { Text(stringResource(R.string.nsv_stop_readout)) }
            }
            state.error?.let { code -> item { Text(stringResource(standardVoiceErrorResource(code)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("native-standard-voice-error")) } }
            if (controls.canRequestPermission) item { TextButton(onClick = { onAction(NativeStandardVoiceAction.RequestPermission) }) { Text(stringResource(R.string.nsv_permission)) } }
            if (controls.canResumeWake) item { TextButton(onClick = { onAction(NativeStandardVoiceAction.ResumeWake) }) { Text(stringResource(R.string.nsv_resume_wake)) } }
            if (controls.needsReconcile) item { ReconcileButton(onAction, "native-standard-voice-reconcile") }
            if (state.partialTranscript.isNotBlank()) item { Text(state.partialTranscript, style = MaterialTheme.typography.bodyMedium) }
            items(state.pending, key = { it.nonce }) { RetainedTranscript(it, canInsertRetained, onAction) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VoiceAmplitude(state: NativeStandardVoiceState) {
    val native = state.native
    val listening = standardVoiceUiControls(state).showMicrophone && native?.state == "recording" && !native.muted && !state.userMuted && !state.automaticMuted &&
        state.phase == NativeStandardVoicePhase.ACTIVE && !state.recovered
    val actual = if (listening) native?.level?.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f else 0f
    val level by animateFloatAsState(actual, MaterialTheme.motionScheme.fastEffectsSpec(), label = "standard-voice-level")
    val label = stringResource(R.string.nsv_input_level)
    Row(Modifier.fillMaxWidth().height(56.dp).testTag("native-standard-voice-level").semantics {
        contentDescription = label; progressBarRangeInfo = ProgressBarRangeInfo(actual, 0f..1f)
    }, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        repeat(9) { index ->
            val scale = 1f - kotlin.math.abs(index - 4) / 6f
            Box(Modifier.width(8.dp).height((4f + level.coerceIn(0f, 1f) * 48f * scale).dp)
                .background(MaterialTheme.colorScheme.onPrimaryContainer, CircleShape))
        }
    }
}

@Composable
private fun VoiceTextCard(@StringRes title: Int, text: String, tag: String) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(tag)) }
        }
    }
}

@Composable
private fun RetainedTranscript(row: NativeStandardVoiceSubmission, canInsert: Boolean, onAction: (NativeStandardVoiceAction) -> Unit) {
    val actions = standardVoiceRetainedActions(row.delivery, canInsert)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.testTag("native-standard-voice-retained:${row.nonce}")) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(when (row.delivery) {
                NativeStandardVoiceDelivery.UNKNOWN -> R.string.nsv_send_unconfirmed
                NativeStandardVoiceDelivery.SUBMITTING -> R.string.nsv_sending
                NativeStandardVoiceDelivery.NOT_ACCEPTED -> R.string.nsv_not_sent
                else -> R.string.nsv_retained
            }), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            SelectionContainer { Text(row.text, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("native-standard-voice-retained-text:${row.nonce}")) }
            if (actions.reconcile) ReconcileButton(onAction, "native-standard-voice-reconcile:${row.nonce}")
            if (actions.copy) TextButton(onClick = { onAction(NativeStandardVoiceAction.Copy(row.nonce)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-standard-voice-copy:${row.nonce}")) {
                Text(stringResource(R.string.nsv_copy))
            }
            if (actions.retry) OutlinedButton(onClick = { onAction(NativeStandardVoiceAction.Retry(row.nonce)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-standard-voice-retry:${row.nonce}")) {
                Text(stringResource(if (row.delivery == NativeStandardVoiceDelivery.READY) R.string.nsv_send else R.string.nsv_retry))
            }
            if (actions.insert) TextButton(onClick = { onAction(NativeStandardVoiceAction.InsertDraft(row.nonce)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-standard-voice-insert:${row.nonce}")) {
                Text(stringResource(R.string.nsv_insert))
            }
            if (actions.discard) TextButton(onClick = { onAction(NativeStandardVoiceAction.Discard(row.nonce)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-standard-voice-discard:${row.nonce}")) {
                Text(stringResource(R.string.nsv_discard))
            }
        }
    }
}

@Composable
private fun ReconcileButton(onAction: (NativeStandardVoiceAction) -> Unit, tag: String) {
    OutlinedButton(onClick = { onAction(NativeStandardVoiceAction.Reconcile) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)) { Text(stringResource(R.string.nsv_reconcile)) }
}

internal data class StandardVoiceUiControls(
    val showStart: Boolean, val canStart: Boolean, val showMicrophone: Boolean,
    val canPauseMicrophone: Boolean, val canResumeMicrophone: Boolean,
    val canReadAloud: Boolean, val showStopReadout: Boolean, val canStopReadout: Boolean,
    val canRequestPermission: Boolean, val canResumeWake: Boolean,
    val needsReconcile: Boolean, val showEnd: Boolean, val canEnd: Boolean,
)

internal fun standardVoiceUiControls(state: NativeStandardVoiceState): StandardVoiceUiControls {
    val journal = state.journal
    val native = state.native
    val idle = state.phase in setOf(NativeStandardVoicePhase.IDLE, NativeStandardVoicePhase.ENDED)
    val foreign = state.phase == NativeStandardVoicePhase.FOREIGN
    val stopping = state.phase == NativeStandardVoicePhase.STOPPING || journal?.stopRequested == true
    val owned = journal != null && native != null && journal.captureId != null && native.captureId == journal.captureId &&
        native.voiceOperationId == journal.operationId && native.sessionId == journal.target.captureSessionId
    val interactive = owned && native?.active == true && !foreign && !stopping && state.phase != NativeStandardVoicePhase.UNCONFIRMED
    val microphone = interactive && !state.recovered && native?.needsPermission == null && native?.state in setOf("recording", "starting")
    val output = state.outputPending > 0 || native?.speaking == true || state.automaticMuted || state.outputUnconfirmed
    val unresolvedWake = journal?.let { !it.startAttempted && !it.stopRequested && it.wake != null && it.wake.phase != NativeStandardVoiceWakePhase.REJECTED } == true
    val showEnd = journal != null && !idle && !foreign
    return StandardVoiceUiControls(idle, idle && state.pending.isEmpty() && !unresolvedWake && state.config?.enabled != false,
        microphone, microphone, microphone && state.pending.isEmpty() && !state.outputUnconfirmed,
        interactive && !state.recovered, output && !idle, interactive && output,
        interactive && native?.state == "awaiting_user" && native.needsPermission == "microphone",
        unresolvedWake && journal?.wake?.phase == NativeStandardVoiceWakePhase.CONSUMED && !foreign,
        state.error != null || state.outputUnconfirmed || state.phase in setOf(NativeStandardVoicePhase.RECOVERED,
            NativeStandardVoicePhase.UNCONFIRMED, NativeStandardVoicePhase.STOPPING, NativeStandardVoicePhase.FOREIGN), showEnd,
        showEnd && (state.phase != NativeStandardVoicePhase.STOPPING || state.error != null))
}

internal data class StandardVoiceRetainedActions(val reconcile: Boolean, val copy: Boolean, val retry: Boolean, val insert: Boolean, val discard: Boolean)
internal fun standardVoiceRetainedActions(delivery: NativeStandardVoiceDelivery, canInsert: Boolean): StandardVoiceRetainedActions = when (delivery) {
    NativeStandardVoiceDelivery.UNKNOWN, NativeStandardVoiceDelivery.SUBMITTING -> StandardVoiceRetainedActions(true, true, false, false, false)
    NativeStandardVoiceDelivery.READY, NativeStandardVoiceDelivery.NOT_ACCEPTED -> StandardVoiceRetainedActions(false, false, true, canInsert, true)
    NativeStandardVoiceDelivery.ACCEPTED, NativeStandardVoiceDelivery.DISCARDED -> StandardVoiceRetainedActions(false, false, false, false, false)
}

@StringRes private fun standardVoiceStatusResource(state: NativeStandardVoiceState): Int = when (state.phase) {
    NativeStandardVoicePhase.IDLE -> R.string.nsv_not_started
    NativeStandardVoicePhase.STARTING -> R.string.nsv_starting
    NativeStandardVoicePhase.AWAITING_PERMISSION -> R.string.nsv_permission_needed
    NativeStandardVoicePhase.ACTIVE -> when {
        !standardVoiceUiControls(state).canReadAloud -> R.string.nsv_status_unconfirmed
        state.outputUnconfirmed -> R.string.nsv_readout_unconfirmed
        state.native?.speaking == true -> R.string.nsv_reading
        state.userMuted || state.automaticMuted || state.native?.muted == true -> R.string.nsv_mic_paused
        state.native?.state == "finalizing" -> R.string.nsv_finishing_capture
        state.native?.state == "recording" -> R.string.nsv_listening
        else -> R.string.nsv_status_unconfirmed
    }
    NativeStandardVoicePhase.REVIEW -> if (state.pending.isNotEmpty()) R.string.nsv_review_text else R.string.nsv_paused
    NativeStandardVoicePhase.RECOVERED -> R.string.nsv_needs_review
    NativeStandardVoicePhase.FOREIGN -> R.string.nsv_session_changed
    NativeStandardVoicePhase.UNCONFIRMED -> R.string.nsv_status_unconfirmed
    NativeStandardVoicePhase.STOPPING -> R.string.nsv_ending
    NativeStandardVoicePhase.ENDED -> R.string.nsv_ended
}

@StringRes private fun standardVoiceErrorResource(code: String): Int = when (code) {
    "native_workspace_resolving" -> R.string.native_workspace_resolving
    "VOICE_OWNER_MISMATCH", "VOICE_OPERATION_REQUIRED", "native_standard_voice_owner" -> R.string.nsv_session_changed
    "network_error", "runtime_unavailable", "NATIVE_ENGINE_UNAVAILABLE" -> R.string.nsv_service_unavailable
    "native_standard_voice_draft_changed" -> R.string.nsv_draft_changed
    "native_standard_voice_review_required" -> R.string.nsv_review_text
    "native_standard_voice_submission_unconfirmed", "native_standard_voice_receipt_mismatch" -> R.string.nsv_send_unconfirmed
    "native_standard_voice_start_unconfirmed", "native_standard_voice_stop_unconfirmed" -> R.string.nsv_status_unconfirmed
    "native_standard_voice_output_unconfirmed" -> R.string.nsv_readout_unconfirmed
    "native_standard_voice_wake_expired", "native_standard_voice_wake_replayed", "native_standard_voice_wake_unconfirmed" -> R.string.nsv_wake_expired
    "native_standard_voice_runtime_changed", "native_standard_voice_interrupted" -> R.string.nsv_interrupted
    "native_standard_voice_disabled", "ENTRY_DISABLED" -> R.string.nsv_disabled
    "native_standard_voice_screen_unavailable" -> R.string.nsv_unlock
    "native_standard_voice_storage", "storage_error" -> R.string.nsv_storage_failed
    "pending_send_conflict" -> R.string.nsv_send_conflict
    "COMPANION_SESSION_MANAGED", "COMPANION_WORKSPACE_MANAGED", "workspace/session-archived" -> R.string.nsv_read_only
    "native_standard_voice_transcript_limit", "native_standard_voice_reply_limit", "native_standard_voice_output_limit" -> R.string.nsv_limit
    "TTS_PRIVATE_SESSION", "TTS_REPLY_SESSION_MISMATCH" -> R.string.nsv_voice_unavailable
    "TTS_MODEL_NOT_READY" -> R.string.nsv_voice_not_ready
    "TTS_REJECTED", "TTS_EMPTY", "INVALID_SPEECH", "native_standard_voice_output_cancelled" -> R.string.nsv_readout_failed
    "PERMISSION_DENIED", "REJECTED" -> R.string.nsv_denied
    "IDENTITY_REQUIRED" -> R.string.nsv_setup_required
    "CANCELLED" -> R.string.nsv_cancelled
    else -> R.string.nsv_failed
}
