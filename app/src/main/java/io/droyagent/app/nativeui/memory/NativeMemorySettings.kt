package io.droyagent.app.nativeui.memory

import io.droyagent.app.nativeui.ui.DroySwitch as Switch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroySettingsToggle
import io.droyagent.app.nativeui.ui.DroySettingsLink
import io.droyagent.app.nativeui.ui.DroySettingsLinkGroup
import io.droyagent.app.nativeui.ui.DroySettingsSectionTitle

@Composable
internal fun MemorySettings(state: NativeMemoryState, model: NativeMemoryViewModel, onPermissions: (() -> Unit)?, modifier: Modifier) {
    val status = state.status
    LazyColumn(modifier.fillMaxWidth().navigationBarsPadding().testTag("native-memory-settings"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (status != null) {
            item("master") {
                DroySettingsToggle(stringResource(R.string.native_memory_master), R.drawable.native_settings_auto_stories,
                    status.settings.enabled, state.runtime.ready && !state.busy && state.unconfirmed == null, 0, 1,
                    Modifier.testTag("native-memory-master"), emphasized = true) { model.settings("enabled", it) }
            }
            if (status.settings.enabled && !status.gateReady) item("gate") {
                Column {
                    Text(memoryMessage(status.gateReason), color = MaterialTheme.colorScheme.error)
                    onPermissions?.let { TextButton(onClick = it) { Text(stringResource(R.string.native_permissions_title)) } }
                }
            }
            if (status.storageLimited == true) item("storage") {
                Text(stringResource(R.string.native_memory_storage_limited), color = MaterialTheme.colorScheme.error)
            }
            item("automatic") {
                DroySettingsToggle(stringResource(R.string.native_memory_automatic), R.drawable.native_settings_tune,
                    status.settings.automaticExtraction, state.runtime.ready && !state.busy && state.unconfirmed == null, 0, 1) {
                    model.settings("automaticExtraction", it)
                }
            }
            status.maintenance?.lastCode?.takeUnless { it in setOf("MEMORY_DISABLED", "MEMORY_MAINTENANCE_STOPPED") }?.let { code ->
                item("maintenance") { Text(memoryMessage(code), color = MaterialTheme.colorScheme.error) }
            }
        }
        item("capture") {
            DroySettingsLinkGroup(stringResource(R.string.native_memory_record_group), listOf(
                DroySettingsLink(stringResource(R.string.native_memory_capture), R.drawable.native_settings_auto_stories,
                    "native-memory-open-capture") { model.page(NativeMemoryPage.CAPTURE) }
            ))
        }
    }
}

@Composable
internal fun MemoryCapture(state: NativeMemoryState, model: NativeMemoryViewModel, modifier: Modifier) {
    val capture = state.status?.capture
    val config = capture?.configuration
    var consent by rememberSaveable { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxWidth().navigationBarsPadding().testTag("native-memory-capture"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (capture != null && config != null) {
            item("master") {
                DroySettingsToggle(stringResource(R.string.native_memory_capture), R.drawable.native_settings_auto_stories,
                    config.enabled && !config.paused, state.runtime.ready && !state.busy && state.unconfirmed == null, 0, 1,
                    Modifier.testTag("native-memory-capture-master"), emphasized = true) {
                    if (it && !config.allApplications) consent = true else model.capture("enabled", it)
                }
            }
            if (!capture.failure.isNullOrBlank()) item("failure") { Text(memoryMessage(capture.failure), color = MaterialTheme.colorScheme.error) }
            if (config.enabled && !config.allApplications) item("scope") {
                TextButton(onClick = { consent = true }) { Text(stringResource(R.string.native_memory_update_scope)) }
            }
            item("sources") {
                Column {
                    DroySettingsSectionTitle(stringResource(R.string.native_memory_record_content))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        listOf(Triple("activityEnabled", config.activityEnabled, R.string.native_memory_activity_source),
                            Triple("pageTextEnabled", config.pageTextEnabled, R.string.native_memory_page_text),
                            Triple("deviceStateEnabled", config.deviceStateEnabled, R.string.native_memory_device_source),
                            Triple("locationEnabled", config.locationEnabled, R.string.native_memory_location_source)).forEachIndexed { index, (key, checked, title) ->
                            val enabled = config.enabled && !config.paused && state.runtime.ready && !state.busy && state.unconfirmed == null
                            val shapes = ListItemDefaults.segmentedShapes(index, 4)
                            SegmentedListItem(checked = checked, onCheckedChange = { model.captureWithPermission(key, it) }, enabled = enabled,
                                shapes = shapes.copy(selectedShape = shapes.shape),
                                colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                trailingContent = { Checkbox(checked, null, enabled = enabled) },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-memory-source-$key")) {
                                Text(stringResource(title))
                            }
                        }
                    }
                    if (config.enabled && (config.activityEnabled || config.pageTextEnabled) && capture.activityState == "waiting_permission")
                        TextButton(onClick = { model.openPermission("accessibility") }) { Text(stringResource(R.string.native_memory_accessibility)) }
                    if (config.enabled && config.locationEnabled && !capture.locationPermission)
                        TextButton(onClick = { model.openPermission("location") }) { Text(stringResource(R.string.native_memory_location_permission)) }
                }
            }
        } else if (state.status != null) item("unavailable") { Text(stringResource(R.string.native_service_unavailable)) }
    }
    if (consent) AlertDialog(onDismissRequest = { consent = false }, title = { Text(stringResource(R.string.native_memory_capture)) },
        text = { Text(stringResource(R.string.native_memory_all_apps_consent)) },
        confirmButton = { TextButton(onClick = { consent = false; model.enableAllApplicationCapture() }) { Text(stringResource(R.string.native_memory_enable_capture)) } },
        dismissButton = { TextButton(onClick = { consent = false }) { Text(stringResource(R.string.native_memory_cancel)) } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryApps(state: NativeMemoryState, model: NativeMemoryViewModel, modifier: Modifier) {
    var search by rememberSaveable { mutableStateOf("") }
    val apps = (state.apps + (state.appAllow + state.appExclude).filter { id -> state.apps.none { it.packageName == id } }.distinct().map { NativeMemoryApp(it, it) })
        .filter { search.isBlank() || it.label.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true) }
    LazyColumn(modifier.fillMaxWidth().imePadding().testTag("native-memory-apps"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("search") { OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.native_memory_apps_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item("summary") { Text(stringResource(R.string.native_memory_scope_count, state.appAllow.size, state.appExclude.size), style = MaterialTheme.typography.titleMedium) }
        if (state.appAllow.size > 128 || state.appExclude.size > 128) item("limit") { Text(stringResource(R.string.native_memory_scope_limit), color = MaterialTheme.colorScheme.error) }
        if (state.appsError != null) item("error") { MemoryNotice(state.appsError, model::refresh) }
        if (state.appsTruncated) item("truncated") { Text(stringResource(R.string.native_memory_apps_truncated), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.appsLoaded && state.appsError == null && apps.isEmpty() && search.isBlank()) item("empty") { Text(stringResource(R.string.native_memory_apps_empty)) }
        items(apps, key = { it.packageName }) { app -> MemoryCard {
            Text(app.label, style = MaterialTheme.typography.titleMedium)
            if (app.label != app.packageName) Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val selected = when (app.packageName) { in state.appExclude -> 2; in state.appAllow -> 1; else -> 0 }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.native_memory_ignore_app, R.string.native_memory_allow_app, R.string.native_memory_exclude_app).forEachIndexed { index, label ->
                    FilterChip(selected == index, { model.appScope(app.packageName, index) }, label = { Text(stringResource(label)) },
                        enabled = !state.busy && state.unconfirmed == null, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
        } }
        item("scope-note") { Text(stringResource(R.string.native_memory_scope_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun MemoryEndpoints(state: NativeMemoryState, model: NativeMemoryViewModel, modifier: Modifier) {
    LazyColumn(modifier.fillMaxWidth().navigationBarsPadding().testTag("native-memory-endpoints"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("scope") { Text(stringResource(R.string.native_memory_endpoint_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (state.endpointsError != null) item("error") { MemoryNotice(state.endpointsError, model::refresh) }
        if (state.endpointsLoaded && state.endpointsError == null && state.endpoints.none { it.authorized }) item("empty") { Text(stringResource(R.string.native_memory_no_endpoints)) }
        items(state.endpoints.filter { it.authorized }, key = { it.key }) { endpoint -> MemoryEndpoint(endpoint, state, model) }
    }
}

@Composable
private fun MemoryEndpoint(endpoint: NativeMemoryEndpoint, state: NativeMemoryState, model: NativeMemoryViewModel) {
    MemoryCard(Modifier.testTag("native-memory-endpoint-${endpoint.key}")) {
        Text(endpoint.label, style = MaterialTheme.typography.titleMedium)
        SelectionContainer { Text(endpoint.url, style = MaterialTheme.typography.bodyMedium) }
        Text(stringResource(when (endpoint.purpose) {
            "compaction" -> R.string.native_memory_access_compaction
            "extraction" -> R.string.native_memory_access_extraction
            "session-title" -> R.string.native_memory_access_title_generation
            else -> R.string.native_memory_access_conversation
        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(endpoint.categories.map { memoryValue(it) }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { model.revoke(endpoint) }, enabled = state.runtime.ready && !state.busy,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_memory_revoke)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryMaintenance(state: NativeMemoryState, model: NativeMemoryViewModel, modifier: Modifier) {
    val maintenance = state.status?.maintenance
    LazyColumn(modifier.fillMaxWidth().navigationBarsPadding().testTag("native-memory-maintenance"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (maintenance != null) {
            item("progress") { MemoryCard {
                Text(stringResource(R.string.native_memory_maintenance_counts, maintenance.pending, maintenance.held), style = MaterialTheme.typography.titleMedium)
                if (maintenance.busy) Text(stringResource(R.string.native_memory_processing), color = MaterialTheme.colorScheme.primary)
                if (maintenance.calls != null && maintenance.tokens != null) Text(stringResource(R.string.native_memory_maintenance_budget, maintenance.calls, maintenance.tokens), style = MaterialTheme.typography.bodyMedium)
                maintenance.lastCode?.takeUnless { it in setOf("MEMORY_DISABLED", "MEMORY_MAINTENANCE_STOPPED") }?.let { Text(memoryMessage(it), color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.native_memory_maintenance_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            if (maintenance.heldSources.isEmpty()) item("empty") { Text(stringResource(R.string.native_memory_maintenance_idle)) }
            items(maintenance.heldSources.size, key = { maintenance.heldSources[it].id }) { index ->
                val held = maintenance.heldSources[index]
                MemoryCard {
                    Text(stringResource(R.string.native_memory_held_segment, index + 1), style = MaterialTheme.typography.titleMedium)
                    held.code?.let { Text(memoryMessage(it)) }
                    held.sourceId?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { model.maintenance(held.id, true) }, enabled = state.runtime.ready && !state.busy && state.unconfirmed == null && state.status?.available == true && state.status.maintenanceAvailable,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_memory_retry_segment)) }
                        OutlinedButton(onClick = { model.maintenance(held.id, false) }, enabled = state.runtime.ready && !state.busy && state.unconfirmed == null && state.status?.managementAvailable == true && state.status.maintenanceAvailable,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_memory_discard_segment)) }
                    }
                }
            }
        } else if (state.status != null) item("unavailable") { Text(stringResource(R.string.native_service_unavailable)) }
    }
}

@Composable
private fun memoryCaptureState(value: String): String = when (value) {
    "observing" -> stringResource(R.string.native_memory_collecting)
    "waiting_permission" -> stringResource(R.string.native_memory_permission_required)
    "waiting_capture" -> stringResource(R.string.native_memory_off)
    "starting" -> stringResource(R.string.native_loading)
    "system_restricted" -> stringResource(R.string.native_service_unavailable)
    else -> memoryValue(value)
}
