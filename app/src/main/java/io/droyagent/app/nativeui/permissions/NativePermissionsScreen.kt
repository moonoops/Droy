package io.droyagent.app.nativeui.permissions

import io.droyagent.app.nativeui.ui.DroySwitch as Switch
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import io.droyagent.app.nativeui.productsettings.NativeOperationHintSettings
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Shape
import io.droyagent.app.nativeui.ui.DroySettingsSectionTitle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIcon
import io.droyagent.app.nativeui.ui.DroyIconAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativePermissionRequestSheet(
    state: NativePermissionsState,
    request: NativePendingPermissionRequest,
    onAction: (NativePermissionAction) -> Unit,
    sourceLabel: String? = null,
) {
    ModalBottomSheet(onDismissRequest = { onAction(NativePermissionAction.DeclinePending(request.id)) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)
            .testTag("native-permission-request"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.native_permissions_pending), style = MaterialTheme.typography.headlineSmall)
            sourceLabel?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val labels = request.capabilityKeys.mapNotNull { key -> state.products.firstOrNull { it.preference == key }?.label } +
                request.permissionIds.map { stringResource(permissionTitle(it)) }
            Text(labels.distinct().joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
            state.error?.let { PermissionError(it, onAction) }
            if (state.queue.active || state.queue.error != null) QueueCard(state.queue, onAction)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onAction(NativePermissionAction.DeclinePending(request.id)) },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("native-permissions-decline:${request.id}")) {
                    Text(stringResource(R.string.native_permissions_skip))
                }
                Button(onClick = { onAction(NativePermissionAction.ApprovePending(request.id)) },
                    enabled = state.fresh && !state.queue.active && !state.root.running,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("native-permissions-approve:${request.id}")) {
                    Text(stringResource(R.string.native_permissions_allow))
                }
            }
        }
    }
}

@Composable
fun NativePermissionsScreen(
    state: NativePermissionsState,
    onAction: (NativePermissionAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    onMemoryAccess: (() -> Unit)? = null,
    onRootChanged: (Boolean) -> Unit = {},
) {
    var confirmation by rememberSaveable { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val pageStates = rememberSaveableStateHolder()
    val rootChanged by rememberUpdatedState(onRootChanged)
    LaunchedEffect(page) { rootChanged(page == null) }
    DisposableEffect(Unit) { onDispose { rootChanged(true) } }
    val idle = state.fresh && !state.queue.active && !state.root.running
    val back = {
        if (page != null) page = if (page in setOf("system", "products")) null else "products" else {
            if (state.queue.active || state.pendingRequests.isNotEmpty() || state.batchIndependent.isNotEmpty()) onAction(NativePermissionAction.CancelQueue)
            onBack()
        }
    }
    BackHandler(enabled = page != null || state.queue.active || state.pendingRequests.isNotEmpty() || state.batchIndependent.isNotEmpty(), onBack = back)
    LaunchedEffect(state.fresh, state.queue.active, state.root.running, state.batchIndependent, confirmation) {
        if (idle && confirmation == null && state.batchIndependent.isNotEmpty()) confirmation = state.batchIndependent.first()
    }
    Scaffold(modifier.fillMaxSize().testTag("native-permissions"), containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            if (!embedded || page != null) Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().then(if (embedded) Modifier else Modifier.statusBarsPadding()).heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DroyIconAction(R.drawable.native_settings_arrow_back, stringResource(R.string.native_permissions_back), back,
                        modifier = Modifier.testTag("native-permissions-back"))
                    Text(page?.takeUnless { it in setOf("system", "products") } ?: stringResource(when(page) { "system" -> R.string.native_permissions_system_permissions; "products" -> R.string.native_permissions_product_capabilities; else -> R.string.native_permissions_title }), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(start = 8.dp))

                }
            }
        }) { padding ->
        val destination = page
        pageStates.SaveableStateProvider(destination ?: "permissions-root") {
        // The saveable holder restores state but reuses nodes. Give each list its own identity so
        // an outgoing list cannot remeasure the next destination's items and clamp its saved offset.
        key(destination) {
        val listState = rememberLazyListState()
        // A recreated coordinator loads its catalog asynchronously. Measuring the restored list
        // against that initial empty catalog would clamp its saved index before the rows arrive.
        if (!state.fresh && state.system.isEmpty() && state.products.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                state.error?.let { PermissionError(it, onAction) }
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).navigationBarsPadding()
            .testTag("native-permissions-content"), state = listState,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.error != null) item(key = "error") { PermissionError(state.error, onAction) }
            if (state.queue.active || state.queue.error != null) item(key = "queue") { QueueCard(state.queue, onAction) }
            items(state.pendingRequests, key = { "pending:${it.id}" }) { request ->
                PermissionSection(stringResource(R.string.native_permissions_pending)) {
                    val labels = request.capabilityKeys.mapNotNull { key -> state.products.firstOrNull { it.preference == key }?.label } +
                        request.permissionIds.map { stringResource(permissionTitle(it)) }
                    Text(labels.distinct().joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onAction(NativePermissionAction.DeclinePending(request.id)) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("native-permissions-decline:${request.id}")) {
                            Text(stringResource(R.string.native_permissions_skip))
                        }
                        Button(onClick = { onAction(NativePermissionAction.ApprovePending(request.id)) }, enabled = idle,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("native-permissions-approve:${request.id}")) {
                            Text(stringResource(R.string.native_permissions_allow))
                        }
                    }
                }
            }
            if (destination == null) {
            item(key = "policy") {
                Column {
                    DroySettingsSectionTitle(stringResource(R.string.native_permissions_authorization))
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val stacked = maxWidth < 320.dp || LocalDensity.current.fontScale > 1.3f
                        val width = if (stacked) maxWidth else (maxWidth - 8.dp) / 2
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { confirmation = "batch" }, enabled = idle,
                                modifier = Modifier.width(width).heightIn(min = 48.dp).testTag("native-permissions-set-up-all")
                                    .semantics { selected = state.policy == NativeAuthorizationPolicy.BATCH }) {
                                if (state.policy == NativeAuthorizationPolicy.BATCH) {
                                    DroyIcon(R.drawable.native_ui_check, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(stringResource(R.string.native_permissions_set_up_all))
                            }
                            val firstUse = state.policy == NativeAuthorizationPolicy.ON_FIRST_USE
                            FilledTonalButton(onClick = { onAction(NativePermissionAction.SelectPolicy(NativeAuthorizationPolicy.ON_FIRST_USE)) }, enabled = idle,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (firstUse) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = if (firstUse) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer),
                                modifier = Modifier.width(width).heightIn(min = 48.dp).testTag("native-permissions-first-use")
                                    .semantics { selected = firstUse }) {
                                if (firstUse) {
                                    DroyIcon(R.drawable.native_ui_check, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(stringResource(R.string.native_permissions_ask_first_use))
                            }
                        }
                    }
                }
            }
            item(key = "separate-title") { SectionTitle(R.string.native_permissions_separate_access) }
            item(key = "independent-access") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            run {
                val label = when { !state.fresh -> R.string.native_permissions_unknown
                    state.accessibility.connected -> R.string.native_permissions_connected
                    state.accessibility.enabled -> R.string.native_permissions_waiting_connection
                    else -> R.string.native_permissions_not_enabled }
                AccessCard(stringResource(R.string.native_permissions_accessibility), stringResource(label),
                    action = stringResource(R.string.native_permissions_settings), enabled = idle,
                    onClick = { confirmation = "accessibility" }, tag = "native-permissions-accessibility",
                    shape = ListItemDefaults.segmentedShapes(0, 3).shape)
            }
            run {
                val label = when {
                    !state.fresh -> R.string.native_permissions_unknown
                    state.root.running -> R.string.native_permissions_verifying
                    !state.root.optedIn -> R.string.native_permissions_not_enabled
                    !state.root.executablePresent -> R.string.native_permissions_no_root
                    state.root.verified -> R.string.native_permissions_verified
                    else -> R.string.native_permissions_not_verified
                }
                Surface(shape = ListItemDefaults.segmentedShapes(1, 3).shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.native_permissions_root), style = MaterialTheme.typography.bodyLarge)
                                PermissionStatus(stringResource(label))
                            }
                            TextButton(onClick = { confirmation = "root" }, enabled = idle,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("native-permissions-root")) {
                                Text(stringResource(if (state.root.optedIn) R.string.native_permissions_verify else R.string.native_permissions_enable))
                            }
                            if (state.root.optedIn) TextButton(onClick = { onAction(NativePermissionAction.DisableRoot) },
                                enabled = state.fresh && !state.root.running,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("native-permissions-disable-root")) {
                                Text(stringResource(R.string.native_permissions_disable))
                            }
                        }
                        if (state.root.optedIn && state.root.error != null) Text(state.root.error,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
            run {
                val label = when {
                    !state.fresh -> R.string.native_permissions_unknown
                    !state.shizuku.installed -> R.string.native_permissions_not_installed
                    !state.shizuku.running -> R.string.native_permissions_not_running
                    state.shizuku.requiresRootOptIn -> R.string.native_permissions_root_opt_in_required
                    state.shizuku.authorized -> R.string.native_permissions_granted
                    else -> R.string.native_permissions_not_granted
                }
                val open = !state.shizuku.running || state.shizuku.authorized
                AccessCard(stringResource(R.string.native_permissions_shizuku), stringResource(label),
                    action = stringResource(if (open) R.string.native_permissions_open_shizuku else R.string.native_permissions_authorize),
                    enabled = idle && state.shizuku.installed,
                    onClick = { if (open) onAction(NativePermissionAction.OpenShizuku) else confirmation = "shizuku" }, tag = "native-permissions-shizuku",
                    shape = ListItemDefaults.segmentedShapes(2, 3).shape)
            }
            }
            }
            item(key = "background-title") { SectionTitle(R.string.native_permissions_background_battery) }
            item(key = "background") {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).heightIn(min = 64.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.native_permissions_background_runtime), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(if (!state.fresh) R.string.native_permissions_unknown else if (state.background.running) R.string.native_permissions_running else R.string.native_permissions_not_running),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.background.enabled, onCheckedChange = { enabled ->
                            if (enabled) confirmation = "background" else onAction(NativePermissionAction.SetBackgroundEnabled(false))
                        }, enabled = state.fresh, modifier = Modifier.testTag("native-permissions-background"))
                    }
                }
            }

            if (state.background.enabled && (!state.background.appNotificationsEnabled || !state.background.runtimePermissionGranted || state.background.runtimeChannelEnabled == false)) item("runtime-notification-repair") {
                TextButton(onClick = { onAction(NativePermissionAction.RequestRuntimeNotifications) }) { Text(stringResource(R.string.native_permissions_runtime_notification)) }
            }
            item(key = "energy") {
                AccessCard(stringResource(R.string.native_permissions_battery), stringResource(if (!state.fresh) R.string.native_permissions_unknown else when (state.energy.exempt) {
                    true -> R.string.native_permissions_battery_exempt; false -> R.string.native_permissions_battery_not_exempt; null -> R.string.native_permissions_unknown
                }), stringResource(R.string.native_permissions_settings), state.fresh,
                    { onAction(NativePermissionAction.OpenEnergySettings) }, "native-permissions-energy-settings")
            }

            item("system-link") { PermissionNavigation(stringResource(R.string.native_permissions_system_permissions)) { page = "system" } }
            item("products-link") { PermissionNavigation(stringResource(R.string.native_permissions_product_capabilities)) { page = "products" } }
            onMemoryAccess?.let { open -> item("memory-link") { PermissionNavigation(stringResource(R.string.native_memory_endpoints), action = open) } }
            item("operation-hints") { NativeOperationHintSettings() }
            }
            if (destination == "system") {
            items(state.system, key = { "system:${it.id}" }) { permission ->
                val granted = permission.grant == NativePermissionGrant.GRANTED
                AccessCard(stringResource(permissionTitle(permission.id)), stringResource(if (state.fresh) grantLabel(permission.grant) else R.string.native_permissions_unknown),
                    stringResource(if (granted) R.string.native_permissions_manage else R.string.native_permissions_authorize), idle,
                    { onAction(NativePermissionAction.RequestSystem(permission.id)) }, "native-permissions-system:${permission.id}")
            }
            }
            if (destination == "products") {
            item(key = "operations") {
                PermissionSection(stringResource(R.string.native_permissions_phone_operations)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (state.toolsPaused) R.string.native_permissions_off else R.string.native_permissions_on),
                            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(checked = !state.toolsPaused, onCheckedChange = { onAction(NativePermissionAction.SetToolsPaused(!it)) },
                            enabled = state.fresh, modifier = Modifier.testTag("native-permissions-operations"))
                    }
                }
            }
            item("product-groups") {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        state.products.groupBy { it.section }.forEach { (section, _) ->
                            PermissionNavigation(section, action = { page = section }, contained = true)
                        }
                    }
                }
            }
            }
            val choices = state.products.filter { it.section == destination }
            if (choices.isNotEmpty()) {
                item("group-actions") { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { onAction(NativePermissionAction.SetCapabilityGroup(choices.map { it.preference }.toSet(), true)) }, enabled = idle) { Text(stringResource(R.string.native_permissions_select_all)) }
                    TextButton(onClick = { onAction(NativePermissionAction.SetCapabilityGroup(choices.map { it.preference }.toSet(), false)) }, enabled = idle) { Text(stringResource(R.string.native_permissions_clear_all)) }
                } }
                items(choices, key = { "product:${it.preference}" }) { choice ->
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(choice.enabled, enabled = idle, role = Role.Checkbox,
                            onValueChange = { onAction(NativePermissionAction.SetCapability(choice.preference, it)) }).padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Checkbox(choice.enabled, null, enabled = idle, modifier = Modifier.testTag("native-permissions-product:${choice.preference}"))
                            Text(choice.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        }
        }
    }
    confirmation?.let { kind ->
        val title = when (kind) {
            "batch" -> R.string.native_permissions_confirm_batch
            "accessibility" -> R.string.native_permissions_confirm_accessibility
            "root" -> R.string.native_permissions_confirm_root
            "shizuku" -> R.string.native_permissions_confirm_shizuku
            "battery" -> R.string.native_permissions_battery
            else -> R.string.native_permissions_confirm_background
        }
        AlertDialog(onDismissRequest = { if (kind in state.batchIndependent) onAction(NativePermissionAction.SkipIndependent(kind)); confirmation = null }, title = { Text(stringResource(title)) },
            confirmButton = { TextButton(onClick = {
                val action = when (kind) {
                    "batch" -> NativePermissionAction.SetUpOrdinary
                    "accessibility" -> NativePermissionAction.ConfirmAccessibility
                    "root" -> NativePermissionAction.ConfirmRoot
                    "shizuku" -> NativePermissionAction.ConfirmShizuku
                    "battery" -> NativePermissionAction.OpenEnergySettings
                    else -> NativePermissionAction.SetBackgroundEnabled(true)
                }
                confirmation = null; onAction(action)
            }, modifier = Modifier.testTag("native-permissions-confirm:$kind")) { Text(stringResource(R.string.native_permissions_continue)) } },
            dismissButton = { TextButton(onClick = { if (kind in state.batchIndependent) onAction(NativePermissionAction.SkipIndependent(kind)); confirmation = null }) { Text(stringResource(R.string.native_permissions_cancel)) } })
    }
}

@Composable
private fun SectionTitle(@StringRes label: Int) {
    DroySettingsSectionTitle(stringResource(label))
}

@Composable
private fun PermissionSection(title: String, status: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

@Composable
private fun AccessCard(title: String, status: String, action: String, enabled: Boolean, onClick: () -> Unit, tag: String, shape: Shape = MaterialTheme.shapes.large) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 64.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                PermissionStatus(status)
            }
            TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) { Text(action) }
        }
    }
}

@Composable
private fun PermissionStatus(status: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(status, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun FactRow(@StringRes title: Int, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QueueCard(state: NativePermissionQueue, onAction: (NativePermissionAction) -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.testTag("native-permissions-queue")) {
        Column(Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.active) {
                Text(stringResource(R.string.native_permissions_queue), style = MaterialTheme.typography.titleMedium)
                state.current?.let { Text(stringResource(permissionTitle(it)), style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                LinearProgressIndicator(progress = { if (state.total > 0) state.processed.toFloat() / state.total else 0f }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.native_permissions_queue_progress, state.processed, state.total), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { onAction(NativePermissionAction.CancelQueue) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-permissions-cancel-queue")) {
                    Text(stringResource(R.string.native_permissions_stop_queue))
                }
            }
            state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        }
    }
}

@Composable
private fun PermissionError(code: String, onAction: (NativePermissionAction) -> Unit) {
    val text = when (code) {
        "permission_state_failed" -> R.string.native_permissions_error_state
        "permission_policy_not_saved" -> R.string.native_permissions_error_save
        "permission_queue_busy" -> R.string.native_permissions_error_busy
        "permission_activity_not_ready" -> R.string.native_permissions_error_activity
        "permission_shizuku_not_running" -> R.string.native_permissions_error_shizuku
        "permission_pending_expired" -> R.string.native_permissions_error_expired
        else -> R.string.native_permissions_error_action
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.testTag("native-permissions-error")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onAction(NativePermissionAction.DismissError) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_permissions_close)) }
                TextButton(onClick = { onAction(NativePermissionAction.Refresh) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_permissions_refresh)) }
            }
        }
    }
}

@StringRes
private fun permissionTitle(id: String): Int = when (id) {
    "notifications" -> R.string.native_permissions_runtime_notification
    "calendar" -> R.string.native_permissions_calendar
    "contacts" -> R.string.native_permissions_contacts
    "microphone" -> R.string.native_permissions_microphone
    "camera" -> R.string.native_permissions_camera
    "location", "locationPrecise", "wifiScan" -> R.string.native_permissions_location
    "bluetooth" -> R.string.native_permissions_bluetooth
    "nearbyWifi" -> R.string.native_permissions_nearby_wifi
    "phoneState" -> R.string.native_permissions_phone_state
    "activityRecognition" -> R.string.native_permissions_activity_recognition
    "bodySensors" -> R.string.native_permissions_body_sensors
    "notificationAccess" -> R.string.native_permissions_notification_access
    "usage" -> R.string.native_permissions_usage
    "brightness" -> R.string.native_permissions_brightness
    "petOverlay" -> R.string.native_permissions_overlay
    "accessibility" -> R.string.native_permissions_accessibility
    "root" -> R.string.native_permissions_root
    "shizuku" -> R.string.native_permissions_shizuku
    else -> R.string.native_permissions_system_permission
}

@StringRes
private fun grantLabel(value: NativePermissionGrant): Int = when (value) {
    NativePermissionGrant.GRANTED -> R.string.native_permissions_granted
    NativePermissionGrant.NOT_REQUESTED -> R.string.native_permissions_not_requested
    NativePermissionGrant.NOT_GRANTED -> R.string.native_permissions_not_granted
    NativePermissionGrant.SETTINGS_REQUIRED -> R.string.native_permissions_settings_required
    NativePermissionGrant.UNAVAILABLE -> R.string.native_permissions_unavailable
}

@Composable private fun PermissionNavigation(title: String, contained: Boolean = false, action: () -> Unit) {
    val row: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = action).heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                DroyIcon(R.drawable.native_settings_chevron_right, null)
            }
        }
    }
    if (contained) row() else Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) { row() }
}
