package io.droyagent.app.nativeui.engine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIconAction

@Composable
fun NativeEngineSettingsScreen(
    state: NativeEngineState,
    onAction: (NativeEngineAction) -> Unit,
    onBack: () -> Unit,
    section: NativeEngineSettingsSection = NativeEngineSettingsSection.PRESETS,
    onCreatePreset: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val document = state.document
    var risk by remember { mutableStateOf<NativeEngineAction?>(null) }
    BackHandler(enabled = document != null) { onAction(NativeEngineAction.CloseDocument) }
    Scaffold(modifier.fillMaxSize().testTag("native-engine-settings"), containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { Surface {
            Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                DroyIconAction(R.drawable.native_settings_arrow_back, stringResource(R.string.native_engine_back), {
                    if (document != null) onAction(NativeEngineAction.CloseDocument) else onBack()
                })
                Text(stringResource(if (section == NativeEngineSettingsSection.PRESETS) R.string.native_engine_presets else R.string.native_engine_permissions),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                DroyIconAction(R.drawable.native_ui_refresh, stringResource(R.string.native_engine_refresh), { onAction(NativeEngineAction.Refresh) }, enabled = !state.busy)
            }
        } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().navigationBarsPadding(),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading || state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!state.runtime.ready) item { Text(stringResource(R.string.native_engine_not_ready)) }
            state.error?.let { item { EngineError(it) } }
            if (state.receipt) item { Text(stringResource(R.string.native_engine_applied), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (document != null) item {
                Text(document.id, style = MaterialTheme.typography.titleMedium)
                Text(document.content, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("native-engine-preset-document"))
            } else if (section == NativeEngineSettingsSection.PRESETS) {
                val catalog = state.catalog
                if (catalog?.presetsAvailable == false) item { Text(stringResource(R.string.native_engine_capability_unavailable)) }
                items(catalog?.presets ?: emptyList(), key = { it.id }) { preset ->
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(presetName(preset), style = MaterialTheme.typography.titleMedium)
                            presetDescription(preset)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            if (preset.broken) Text(stringResource(R.string.native_engine_preset_broken), color = MaterialTheme.colorScheme.error)
                            if (preset.isDefault) Text(stringResource(R.string.native_engine_new_default), style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onAction(NativeEngineAction.ViewPreset(preset.id)) }, enabled = state.runtime.ready && !state.busy) { Text(stringResource(R.string.native_engine_details)) }
                                if (!preset.isDefault) TextButton(onClick = { onAction(NativeEngineAction.DefaultPreset(preset.id)) },
                                    enabled = state.runtime.ready && state.fresh && !state.busy && !preset.broken && catalog?.presetSetting?.writable == true) { Text(stringResource(R.string.native_engine_make_default)) }
                            }
                        }
                    }
                }
                if (catalog?.presets?.any { it.id == "cordis" && !it.broken } == true && onCreatePreset != null) item {
                    FilledTonalButton(onClick = { onCreatePreset("cordis") }, enabled = state.runtime.ready && !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-engine-create-preset")) { Text(stringResource(R.string.native_engine_create)) }
                }
                if (catalog?.presetsAvailable == true && catalog.presetSetting == null) item { Text(stringResource(R.string.native_engine_default_unavailable)) }
            } else {
                val catalog = state.catalog
                item { Text(stringResource(R.string.native_engine_permission_scope), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (catalog?.permissionsAvailable == false) item { Text(stringResource(R.string.native_engine_capability_unavailable)) }
                items(catalog?.defaultPermissions ?: emptyList(), key = { it.id }) { permission ->
                    PermissionChoice(permission, catalog?.defaultPermission == permission.id,
                        enabled = state.runtime.ready && state.fresh && !state.busy && catalog?.permissionSetting?.writable == true) {
                        val action = NativeEngineAction.DefaultPermission(permission.id)
                        if (permission.needsRiskConfirmation) risk = action else onAction(action)
                    }
                }
                if (catalog?.permissionsAvailable == true && catalog.permissionSetting == null) item { Text(stringResource(R.string.native_engine_default_unavailable)) }
            }
        }
    }
    EngineRiskDialog(risk, state.busy, onDismiss = { risk = null }, onAction = { risk = null; onAction(it) })
}

/** Session-local choices; a blank composer stages them for its bound native draft. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun NativeEngineSessionSelector(state: NativeEngineState, onAction: (NativeEngineAction) -> Unit, modifier: Modifier = Modifier, interactionEnabled: Boolean = true) {
    val target = NativeEngineTarget(state.sessionId, state.draftId)
    val currentTarget by rememberUpdatedState(target)
    var picker by remember(target, interactionEnabled) { mutableStateOf<NativeEngineSettingsSection?>(null) }
    var risk by remember(target, interactionEnabled) { mutableStateOf<NativeEngineAction?>(null) }
    val catalog = state.catalog
    val targetReady = state.sessionId == null && state.draftId != null || state.session?.fresh == true
    val enabled = interactionEnabled && state.runtime.ready && state.fresh && !state.busy && !state.sessionLoading && targetReady
    val presetId = if (state.sessionId == null) state.draftSelection?.agentPresetId ?: catalog?.defaultPreset else state.session?.agentPreset
    val permissionId = if (state.sessionId == null) state.draftSelection?.permissionPresetId ?: catalog?.defaultPermission else state.session?.permission
    Column(modifier.testTag("native-engine-session-controls")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AssistChip(onClick = { picker = NativeEngineSettingsSection.PRESETS; onAction(NativeEngineAction.RefreshSession) },
                enabled = enabled && catalog?.presetsAvailable == true && (state.sessionId == null || state.session?.presetSelectable == true),
                modifier = Modifier.heightIn(min = 48.dp),
                label = { Text(catalog?.presets?.find { it.id == presetId }?.let { presetName(it) } ?: presetId ?: stringResource(R.string.native_engine_presets)) })
            AssistChip(onClick = { picker = NativeEngineSettingsSection.PERMISSIONS; onAction(NativeEngineAction.RefreshSession) },
                enabled = enabled && catalog?.permissionsAvailable == true && (state.sessionId == null || state.session?.permissionSelectable == true),
                modifier = Modifier.heightIn(min = 48.dp),
                label = { Text(catalog?.permissions?.find { it.id == permissionId }?.let { permissionName(it) } ?: if (permissionId == "custom") stringResource(R.string.native_engine_custom) else permissionId ?: stringResource(R.string.native_engine_permissions)) })
        }
        if (state.session?.needsMigration == true) Text(stringResource(R.string.native_engine_needs_migration), style = MaterialTheme.typography.bodySmall)
        state.error?.let { EngineError(it) }
    }
    picker?.let { section ->
        AlertDialog(onDismissRequest = { picker = null }, title = { Text(stringResource(if (section == NativeEngineSettingsSection.PRESETS) R.string.native_engine_presets else R.string.native_engine_permissions)) }, text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.sessionLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (state.sessionId == null) item { Text(stringResource(R.string.native_engine_new_conversation), style = MaterialTheme.typography.labelLarge) }
                if (section == NativeEngineSettingsSection.PRESETS) items(catalog?.presets ?: emptyList(), key = { it.id }) { preset ->
                    OutlinedButton(onClick = { picker = null; if (interactionEnabled && currentTarget == target) onAction(NativeEngineAction.SessionPreset(preset.id, target)) }, enabled = enabled && !preset.broken, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(presetName(preset)) }
                } else {
                    item { Text(stringResource(R.string.native_engine_permission_scope), style = MaterialTheme.typography.bodySmall) }
                    items(catalog?.permissions ?: emptyList(), key = { it.id }) { permission ->
                        PermissionChoice(permission, permission.id == permissionId, enabled) {
                            picker = null
                            val action = NativeEngineAction.SessionPermission(permission.id, expectedTarget = target)
                            if (interactionEnabled && currentTarget == target) { if (permission.needsRiskConfirmation) risk = action else onAction(action) }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { picker = null }) { Text(stringResource(R.string.native_engine_close)) } })
    }
    EngineRiskDialog(risk, state.busy || !interactionEnabled, onDismiss = { risk = null }, onAction = {
        risk = null
        if (interactionEnabled && (it as? NativeEngineAction.SessionPermission)?.expectedTarget == currentTarget) onAction(it)
    })
}

@Composable private fun PermissionChoice(value: NativeDshPermission, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick = null, enabled = enabled)
            Column { Text(permissionName(value), style = MaterialTheme.typography.titleSmall); value.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}
@Composable private fun EngineRiskDialog(action: NativeEngineAction?, busy: Boolean, onDismiss: () -> Unit, onAction: (NativeEngineAction) -> Unit) {
    if (action == null) return
    var acknowledged by remember(action) { mutableStateOf(false) }
    val id = when (action) { is NativeEngineAction.DefaultPermission -> action.id; is NativeEngineAction.SessionPermission -> action.id; else -> return }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.native_engine_permission_confirm)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(if (id == "auto") R.string.native_engine_auto_risk else R.string.native_engine_full_risk))
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(acknowledged, { acknowledged = it }); Text(stringResource(R.string.native_engine_acknowledge)) }
        }
    }, confirmButton = { TextButton(onClick = { onAction(when (action) { is NativeEngineAction.DefaultPermission -> action.copy(acceptRisk = true); is NativeEngineAction.SessionPermission -> action.copy(acceptRisk = true); else -> action }) }, enabled = acknowledged && !busy) { Text(stringResource(if (id == "danger-full-access") R.string.native_engine_enable_full else R.string.native_engine_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.native_engine_cancel)) } })
}

@Composable internal fun EngineError(code: String) { Text(stringResource(when (code) {
    "settings/conflict" -> R.string.native_engine_conflict
    "agent-preset/locked" -> R.string.native_engine_locked
    "NATIVE_ENGINE_SAVE_UNCONFIRMED" -> R.string.native_engine_save_unconfirmed
    "NATIVE_ENGINE_DRAFT_STORAGE" -> R.string.native_engine_draft_storage
    "NATIVE_ENGINE_DRAFT_CHANGED" -> R.string.native_engine_draft_changed
    "NATIVE_ENGINE_BUSY" -> R.string.native_engine_busy
    "NATIVE_ENGINE_UNAVAILABLE", "runtime_unavailable" -> R.string.native_engine_capability_unavailable
    "NATIVE_ENGINE_MANAGED" -> R.string.native_engine_managed
    else -> R.string.native_engine_failed
}), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

@Composable internal fun presetName(value: NativeAgentPreset): String = value.name ?: when (value.id) {
    "standard" -> stringResource(R.string.native_engine_standard); "cordis" -> stringResource(R.string.native_engine_creator)
    "ptc" -> stringResource(R.string.native_engine_ptc); "minimal" -> stringResource(R.string.native_engine_minimal); else -> value.id
}
@Composable private fun presetDescription(value: NativeAgentPreset): String? = if (value.name == null) when (value.id) {
    "standard" -> stringResource(R.string.native_engine_standard_description); "cordis" -> stringResource(R.string.native_engine_creator_description); else -> value.description
} else value.description
@Composable internal fun permissionName(value: NativeDshPermission): String = if (value.id == "auto") stringResource(R.string.native_engine_auto) else if (value.name != value.id) value.name else when (value.id) {
    "read-only" -> stringResource(R.string.native_engine_read_only); "workspace-write" -> stringResource(R.string.native_engine_workspace_write)
    "danger-full-access" -> stringResource(R.string.native_engine_full_access); "auto" -> stringResource(R.string.native_engine_auto); else -> value.name
}

/** Compact execution permission selector. The same target guard also applies after confirmation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeEnginePermissionControl(state: NativeEngineState, onAction: (NativeEngineAction) -> Unit, interactionEnabled: Boolean = true) {
    val target = NativeEngineTarget(state.sessionId, state.draftId)
    val currentTarget by rememberUpdatedState(target)
    var open by remember(target, interactionEnabled) { mutableStateOf(false) }
    var risk by remember(target, interactionEnabled) { mutableStateOf<NativeEngineAction?>(null) }
    val catalog = state.catalog
    val resolvedPermission = if (state.sessionId == null) state.draftSelection?.permissionPresetId ?: catalog?.defaultPermission else state.session?.permission
    // A draft materializes into a session with the same identity. Keep its label while
    // the session projection refreshes; freshness still gates every mutation below.
    var previousPermission by remember(state.draftId ?: state.sessionId) { mutableStateOf<String?>(null) }
    SideEffect { if (resolvedPermission != null) previousPermission = resolvedPermission }
    val permissionId = resolvedPermission ?: previousPermission
    val ready = state.sessionId == null && state.draftId != null || state.session?.fresh == true
    val enabled = interactionEnabled && state.runtime.ready && state.fresh && !state.busy && !state.sessionLoading && ready &&
        catalog?.permissionsAvailable == true && (state.sessionId == null || state.session?.permissionSelectable == true)
    val label = when(permissionId) {
        "read-only" -> stringResource(R.string.native_engine_permission_read_short)
        "workspace-write" -> stringResource(R.string.native_engine_permission_workspace_short)
        "danger-full-access" -> stringResource(R.string.native_engine_permission_full_short)
        else -> stringResource(R.string.native_engine_permissions)
    }
    TextButton(onClick = { open = true; onAction(NativeEngineAction.RefreshSession) }, enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant, disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.height(32.dp).testTag("native-permission")) {
        io.droyagent.app.nativeui.ui.DroyIcon(R.drawable.native_engine_shield, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
            Text(stringResource(R.string.native_engine_permissions), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))
            catalog?.permissions.orEmpty().filter { it.id in setOf("read-only", "workspace-write", "danger-full-access") }.forEach { permission ->
                ListItem(headlineContent = { Text(permissionName(permission)) },
                    leadingContent = { RadioButton(permission.id == permissionId, null, enabled = enabled) },
                    modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) {
                        open = false
                        val action = NativeEngineAction.SessionPermission(permission.id, expectedTarget = target)
                        if (currentTarget == target && interactionEnabled) {
                            if (permission.needsRiskConfirmation) risk = action else onAction(action)
                        }
                    })
            }
        }
    }
    EngineRiskDialog(risk, state.busy || !interactionEnabled, onDismiss = { risk = null }, onAction = {
        risk = null
        if (interactionEnabled && (it as? NativeEngineAction.SessionPermission)?.expectedTarget == currentTarget) onAction(it)
    })
}
