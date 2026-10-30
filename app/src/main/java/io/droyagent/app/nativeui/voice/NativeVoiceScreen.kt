package io.droyagent.app.nativeui.voice

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIcon
import io.droyagent.app.nativeui.ui.DroySwitch as Switch
import io.droyagent.app.nativeui.ui.DroyIconAction

@Composable
fun NativeVoiceScreen(state: NativeVoiceState, onAction: (NativeVoiceAction) -> Unit, onBack: () -> Unit,
    onPermissions: () -> Unit, modifier: Modifier = Modifier) {
    var credential by remember { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    LaunchedEffect(state.credentialVersion) { credential = "" }
    val dirty = when (state.page) {
        NativeVoicePage.READOUT -> state.ttsDraft != state.snapshot?.tts || credential.isNotBlank()
        NativeVoicePage.WAKE -> state.wakeDraft != (state.snapshot?.actualWake ?: state.snapshot?.savedWake)
        else -> false
    }
    val leave = { if (state.page == NativeVoicePage.HOME) onBack() else onAction(NativeVoiceAction.Open(NativeVoicePage.HOME)) }
    val back = { if (dirty) discard = true else leave() }
    BackHandler(enabled = state.page != NativeVoicePage.HOME, onBack = back)
    val ready = state.runtime.ready && state.fresh && !state.saving
    Scaffold(modifier.fillMaxSize().testTag("native-voice"), containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                DroyIconAction(R.drawable.native_settings_arrow_back, stringResource(R.string.native_voice_back), back)
                Text(stringResource(when (state.page) { NativeVoicePage.HOME -> R.string.native_voice_title
                    NativeVoicePage.READOUT -> R.string.native_voice_readout; NativeVoicePage.WAKE -> R.string.native_voice_wake }),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                if (state.page != NativeVoicePage.HOME) TextButton(onClick = {
                    onAction(if (state.page == NativeVoicePage.READOUT) NativeVoiceAction.SaveTts(credential) else NativeVoiceAction.SaveWake)
                }, enabled = ready && dirty && (state.page != NativeVoicePage.READOUT || state.ttsReady),
                    modifier = Modifier.testTag(if (state.page == NativeVoicePage.READOUT) "native-voice-save-tts" else "native-voice-save-wake")) {
                    Text(stringResource(R.string.native_voice_save))
                }
            }
        }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).navigationBarsPadding().imePadding()
            .testTag("native-voice-content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!state.runtime.ready && !state.runtime.active) item {
                Text(stringResource(R.string.native_voice_runtime_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.runtime.canStart) TextButton(onClick = { onAction(NativeVoiceAction.StartRuntime) }) {
                    Text(stringResource(R.string.native_voice_retry_prepare))
                }
            }
            state.error?.let { code -> item { VoiceError(code, onAction) } }
            when (state.page) {
                NativeVoicePage.HOME -> {
                    item { VoiceSection(stringResource(R.string.native_voice_input)) {
                        Fact(R.string.native_voice_asr, stringResource(progressLabel(if (state.fresh) state.snapshot?.asr else null)))
                        val permission = state.permissions
                        if (permission.known && (!permission.microphoneProduct || !permission.transcriptionProduct || !permission.microphoneSystem || permission.paused)) {
                            TextButton(onClick = onPermissions, modifier = Modifier.testTag("native-voice-permissions")) { Text(stringResource(R.string.native_voice_permissions)) }
                        }
                    } }
                    item { Menu(R.string.native_voice_readout, state.snapshot?.tts?.provider?.let { stringResource(providerLabel(it)) },
                        { onAction(NativeVoiceAction.Open(NativeVoicePage.READOUT)) }, "native-voice-open-readout") }
                    item { Menu(R.string.native_voice_wake, if (state.snapshot?.actualWake?.enabled == true) stringResource(wakeLabel(state)) else null,
                        { onAction(NativeVoiceAction.Open(NativeVoicePage.WAKE)) }, "native-voice-open-wake") }
                }
                NativeVoicePage.READOUT -> item { Readout(state, ready, onAction, credential) { credential = it } }
                NativeVoicePage.WAKE -> item { WakeSettings(state, ready, onAction, onPermissions) }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.native_voice_unsaved)) },
        confirmButton = { TextButton(onClick = { discard = false; credential = ""; onAction(NativeVoiceAction.DiscardEdits); leave() }) { Text(stringResource(R.string.native_voice_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.native_voice_continue_editing)) } })
}

@Composable
private fun Readout(state: NativeVoiceState, enabled: Boolean, onAction: (NativeVoiceAction) -> Unit, credential: String, onCredential: (String) -> Unit) {
    val draft = state.ttsDraft ?: return
    var clear by remember { mutableStateOf(false) }
    val resource = state.snapshot?.resources?.firstOrNull { it.provider == draft.provider }?.progress
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Choice(stringResource(R.string.native_voice_provider), stringResource(providerLabel(draft.provider)), enabled,
                    listOf("system", "melo", "qwen3", "openai").map { it to stringResource(providerLabel(it)) }, draft.provider) { provider ->
                    onCredential("")
                    onAction(NativeVoiceAction.SelectProvider(provider))
                }
            }
            if (resource != null && !resource.ready) {
                val downloading = resource.state in setOf("downloading", "verifying")
                TextButton(onClick = { onAction(if (downloading) NativeVoiceAction.CancelDownload(draft.provider) else NativeVoiceAction.Download(draft.provider)) },
                    enabled = enabled, modifier = Modifier.testTag("native-voice-resource")) {
                    Text(stringResource(if (downloading) R.string.native_voice_cancel_download else R.string.native_voice_download))
                }
            }
        }
        if (resource != null && !resource.ready) Progress(if (state.fresh) resource else null)
        if (draft.provider == "system" && state.snapshot?.systemReady != true) Text(stringResource(R.string.native_voice_system_not_ready), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (draft.provider == "openai") {
            VoiceField(R.string.native_voice_url, draft.url, enabled) { onAction(NativeVoiceAction.EditTts(draft.copy(url = it))) }
            VoiceField(R.string.native_voice_model, draft.model, enabled) { onAction(NativeVoiceAction.EditTts(draft.copy(model = it))) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { VoiceField(R.string.native_voice_voice_id, draft.voiceId, enabled) { onAction(NativeVoiceAction.EditTts(draft.copy(voiceId = it))) } }
                PreviewButton(state, enabled, onAction)
            }
            OutlinedTextField(credential, onCredential, enabled = enabled, singleLine = true,
                label = { Text(stringResource(R.string.native_voice_api_key)) }, visualTransformation = PasswordVisualTransformation(),
                placeholder = { Text(stringResource(if (state.snapshot?.hasKey == true) R.string.native_voice_key_saved else R.string.native_voice_key_empty)) },
                modifier = Modifier.fillMaxWidth().testTag("native-voice-key"))
            if (state.snapshot?.hasKey == true) TextButton(onClick = { clear = true }, enabled = enabled) { Text(stringResource(R.string.native_voice_clear_key)) }
        } else {
            val voices = state.snapshot?.voices.orEmpty().filter { it.provider == draft.provider }
            val options = (if (voices.size == 1) emptyList() else listOf("" to stringResource(R.string.native_voice_default_voice))) + voices.map { row ->
                val locale = row.language?.let(java.util.Locale::forLanguageTag)
                val language = locale?.getDisplayLanguage(java.util.Locale.getDefault()).orEmpty()
                val duplicate = voices.count { it.name == row.name } > 1
                row.voiceId to listOfNotNull(row.name, language.takeIf { it.isNotBlank() }, row.voiceId.takeIf { duplicate },
                    stringResource(R.string.native_voice_network_voice).takeIf { row.networkRequired == true }).distinct().joinToString(" · ")
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Choice(stringResource(R.string.native_voice_voice), options.firstOrNull { it.first == draft.voiceId }?.second
                        ?: options.singleOrNull()?.second ?: stringResource(R.string.native_voice_default_voice), enabled, options, draft.voiceId) {
                        onAction(NativeVoiceAction.EditTts(draft.copy(voiceId = it)))
                    }
                }
                PreviewButton(state, enabled, onAction)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.native_voice_rate), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { onAction(NativeVoiceAction.EditTts(draft.copy(rate = 1.0))) }, enabled = enabled && draft.provider != "qwen3") {
                Text(stringResource(R.string.native_voice_rate_value, draft.rate))
            }
        }
        val rateSlider = remember { SliderState(draft.rate.toFloat(), trackRange = 0.5f..2f) }
        rateSlider.value = draft.rate.toFloat()
        Slider(state = rateSlider, onValueChange = { onAction(NativeVoiceAction.EditTts(draft.copy(rate = it.toDouble()))) },
            enabled = enabled && draft.provider != "qwen3", modifier = Modifier.testTag("native-voice-rate"))
        if (state.credentialRetry) TextButton(onClick = { onAction(NativeVoiceAction.RetryCredential(credential)) }, enabled = enabled,
            modifier = Modifier.testTag("native-voice-retry-key")) { Text(stringResource(R.string.native_voice_retry_key)) }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text(stringResource(R.string.native_voice_confirm_clear)) },
        confirmButton = { TextButton(onClick = { clear = false; onAction(NativeVoiceAction.ClearCredential) }) { Text(stringResource(R.string.native_voice_clear_key)) } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text(stringResource(R.string.native_voice_cancel)) } })
}

@Composable
private fun PreviewButton(state: NativeVoiceState, enabled: Boolean, onAction: (NativeVoiceAction) -> Unit) {
    FilledTonalIconButton(onClick = { onAction(if (state.ownPreview) NativeVoiceAction.StopPreview else NativeVoiceAction.Preview) },
        enabled = state.runtime.ready && !state.saving && (state.ownPreview || enabled && state.ttsReady && state.snapshot?.previewAvailable == true),
        modifier = Modifier.padding(start = 8.dp).size(48.dp).testTag("native-voice-preview")) {
        DroyIcon(if (state.ownPreview) R.drawable.native_ui_stop else R.drawable.native_companion_play_arrow,
            stringResource(if (state.ownPreview) R.string.native_voice_stop_preview else R.string.native_voice_preview))
    }
}

@Composable
private fun WakeSettings(state: NativeVoiceState, enabled: Boolean, onAction: (NativeVoiceAction) -> Unit, onPermissions: () -> Unit) {
    val draft = state.wakeDraft ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        VoiceSwitch(stringResource(R.string.native_voice_enable_wake), state.snapshot?.actualWake?.enabled == true, enabled && state.snapshot?.wakeAvailable == true,
            { onAction(NativeVoiceAction.SetWakeEnabled(it)) }, "native-voice-wake-enabled")
        if (state.snapshot?.actualWake?.enabled == true || state.snapshot?.wakeFailed == true) {
            Text(stringResource(wakeLabel(state)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.snapshot?.wakeState == "awaiting_user") TextButton(onClick = onPermissions) { Text(stringResource(R.string.native_voice_permissions)) }
            if (state.snapshot?.wakePreparation?.failed == true) TextButton(onClick = { onAction(NativeVoiceAction.PrepareWake) }, enabled = enabled) { Text(stringResource(R.string.native_voice_retry_prepare)) }
        }
        Choice(stringResource(R.string.native_voice_timing), stringResource(if (draft.timing == "foreground") R.string.native_voice_foreground else R.string.native_voice_screen_on), enabled,
            listOf("screen_on" to stringResource(R.string.native_voice_screen_on), "foreground" to stringResource(R.string.native_voice_foreground)), draft.timing) {
            onAction(NativeVoiceAction.EditWake(draft.copy(timing = it)))
        }
        VoiceField(R.string.native_voice_chinese, draft.chinese, enabled) { onAction(NativeVoiceAction.EditWake(draft.copy(chinese = it))) }
        VoiceField(R.string.native_voice_english, draft.english, enabled) { onAction(NativeVoiceAction.EditWake(draft.copy(english = it))) }
        if (state.wakeSyncRetry) TextButton(onClick = { onAction(NativeVoiceAction.RetryWakeSync) }, enabled = enabled,
            modifier = Modifier.testTag("native-voice-retry-wake")) { Text(stringResource(R.string.native_voice_retry_sync)) }
    }
}

@Composable
private fun VoiceSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium); content()
        }
    }
}
@Composable
private fun Menu(@StringRes title: Int, status: String?, onClick: () -> Unit, tag: String) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(20.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            DroyIcon(R.drawable.native_settings_chevron_right, null)
        }
    }
}
@Composable
private fun VoiceSwitch(label: String, value: Boolean, enabled: Boolean, change: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(value, change, enabled = enabled, modifier = Modifier.testTag(tag))
    }
}
@Composable private fun Fact(@StringRes label: Int, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable private fun VoiceField(@StringRes label: Int, value: String, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(stringResource(label)) }, enabled = enabled, modifier = Modifier.fillMaxWidth(), maxLines = 3)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Choice(label: String, selected: String, enabled: Boolean, choices: List<Pair<String, String>>, selectedId: String? = null, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Surface(onClick = { expanded = true }, enabled = enabled && choices.size > 1,
        color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selected, style = MaterialTheme.typography.bodyLarge)
            }
            if (choices.size > 1) DroyIcon(R.drawable.native_ui_expand_more, null)
        }
    }
    if (expanded) ModalBottomSheet(onDismissRequest = { expanded = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
            if (choices.size > 8) OutlinedTextField(query, { query = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.native_voice_search)) }, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(choices.filter { it.second.contains(query, true) }, key = { it.first }) { (id, name) ->
                    val checked = if (selectedId != null) selectedId == id else selected == name
                    Surface(onClick = { expanded = false; query = ""; change(id) }, shape = MaterialTheme.shapes.large,
                        color = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Row(Modifier.padding(horizontal = 12.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(checked, null); Text(name, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun Progress(value: NativeVoiceProgress?) {
    Text(stringResource(progressLabel(value)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (value?.available == true && value.state in setOf("preparing", "downloading", "verifying", "loading", "checking")) {
        if (value.totalBytes > 0) {
            LinearProgressIndicator(progress = { (value.downloadedBytes.toFloat() / value.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.native_voice_bytes, size(value.downloadedBytes), size(value.totalBytes)), style = MaterialTheme.typography.labelMedium)
        } else LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
@Composable private fun size(bytes: Long) = stringResource(R.string.native_voice_megabytes, bytes / 1048576.0)
@Composable private fun permissionLabel(known: Boolean, granted: Boolean) = stringResource(if (!known) R.string.native_voice_unknown else if (granted) R.string.native_voice_granted else R.string.native_voice_not_granted)
@StringRes private fun providerLabel(provider: String) = when (provider) { "melo" -> R.string.native_voice_melo; "qwen3" -> R.string.native_voice_qwen3; "openai" -> R.string.native_voice_custom; else -> R.string.native_voice_system }
@StringRes private fun progressLabel(progress: NativeVoiceProgress?): Int = when {
    progress?.available != true -> R.string.native_voice_unknown
    progress.failed -> R.string.native_voice_failed
    progress.ready -> R.string.native_voice_ready
    progress.state == "downloading" -> R.string.native_voice_downloading
    progress.state in setOf("verifying", "checking") -> R.string.native_voice_verifying
    progress.state in setOf("preparing", "loading", "waking") -> R.string.native_voice_preparing
    else -> R.string.native_voice_not_ready
}
@StringRes private fun wakeLabel(state: NativeVoiceState): Int {
    val value = state.snapshot ?: return R.string.native_voice_unknown
    return when {
        !state.fresh || !state.runtime.ready || !value.wakeAvailable -> R.string.native_voice_unknown
        value.wakeFailed -> R.string.native_voice_failed
        value.actualWake?.enabled != true -> R.string.native_voice_off
        value.wakeState == "awaiting_user" -> R.string.native_voice_awaiting_permission
        value.wakeState == "preparing" -> R.string.native_voice_preparing
        value.voiceClaimed || value.pausedForVoice -> R.string.native_voice_voice_busy
        value.suspended -> R.string.native_voice_suspended
        !value.screenAvailable -> R.string.native_voice_locked
        value.wakeState == "listening" -> R.string.native_voice_listening
        else -> R.string.native_voice_paused
    }
}
@Composable private fun VoiceError(code: String, onAction: (NativeVoiceAction) -> Unit) {
    val label = when (code) {
        "NATIVE_VOICE_BAD_REQUEST", "NATIVE_VOICE_INVALID" -> R.string.native_voice_error_settings
        "NATIVE_VOICE_PERMISSION" -> R.string.native_voice_error_permission
        "NATIVE_VOICE_RESOURCE" -> R.string.native_voice_error_resource
        "NATIVE_VOICE_BUSY" -> R.string.native_voice_error_busy
        "voice_credential_retry" -> R.string.native_voice_error_key
        "voice_credential_required" -> R.string.native_voice_error_key_required
        "NATIVE_VOICE_CREDENTIAL_NOT_CONFIRMED" -> R.string.native_voice_error_credential
        "NATIVE_VOICE_WAKE_NOT_SAVED" -> R.string.native_voice_error_wake_sync
        "NATIVE_VOICE_WAKE_CONFLICT" -> R.string.native_voice_error_wake_conflict
        "NATIVE_VOICE_CANCELLED" -> R.string.native_voice_error_cancelled
        "NATIVE_VOICE_NOT_READY" -> R.string.native_voice_runtime_unavailable
        "voice_stop_failed" -> R.string.native_voice_error_stop
        else -> R.string.native_voice_error
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(label), color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = { onAction(NativeVoiceAction.DismissError) }, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text(stringResource(R.string.native_voice_close)) }
        }
    }
}
