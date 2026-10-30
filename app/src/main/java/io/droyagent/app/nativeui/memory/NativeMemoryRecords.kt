package io.droyagent.app.nativeui.memory

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryRecords(state: NativeMemoryState, model: NativeMemoryViewModel, modifier: Modifier) {
    var text by rememberSaveable { mutableStateOf(state.filter.text) }
    var dateFrom by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun search() { focus.clearFocus(); keyboard?.hide(); model.filter(state.filter.copy(text = text)) }
    LazyColumn(modifier.fillMaxWidth().navigationBarsPadding().imePadding().testTag("native-memory-records"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("groups") { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NativeMemoryGroup.entries.forEach { group -> FilterChip(state.filter.group == group, { model.filter(state.filter.copy(group = group, text = text)) },
                label = { Text(stringResource(when (group) { NativeMemoryGroup.MEMORY -> R.string.native_memory_personal; NativeMemoryGroup.ACTIVITY -> R.string.native_memory_activity; NativeMemoryGroup.PLACES -> R.string.native_memory_places })) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("native-memory-group-${group.name.lowercase()}")) }
        } }
        item("search") { OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.native_memory_search)) },
            modifier = Modifier.fillMaxWidth().testTag("native-memory-search"), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
            trailingIcon = { TextButton(onClick = ::search, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_memory_search_action)) } }) }
        item("dates") { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { dateFrom = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.native_memory_field_value, stringResource(R.string.native_memory_from), memoryFilterDate(state.filter.from, false))) }
            OutlinedButton(onClick = { dateFrom = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.native_memory_field_value, stringResource(R.string.native_memory_to), memoryFilterDate(state.filter.to, true))) }
            if (state.filter.from.isNotEmpty() || state.filter.to.isNotEmpty()) TextButton(onClick = { model.filter(state.filter.copy(from = "", to = "")) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.native_memory_clear_dates)) }
        } }
        if (state.status?.managementAvailable == false) item("blocked") { Text(stringResource(R.string.native_memory_management_wait)) }
        if (state.recordsError != null) item("error") { MemoryNotice(state.recordsError, model::refresh) }
        state.query?.let { result ->
            item("summary") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.native_memory_records_count, result.total), style = MaterialTheme.typography.titleSmall)
                if (result.coverage.gaps > 0 || result.coverage.unresolvedLocations > 0 || result.coverage.mockLocations > 0) Text(
                    stringResource(R.string.native_memory_coverage, result.coverage.gaps, result.coverage.unresolvedLocations, result.coverage.mockLocations),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
        if (state.recordsLoaded && !state.recordsLoading && state.recordsError == null && state.records.isEmpty()) item("empty") { Text(stringResource(R.string.native_memory_no_records)) }
        items(state.records, key = { it.recordId }) { record ->
            MemoryCard(Modifier.clickable(role = Role.Button) { model.open(record.recordId) }.testTag("native-memory-record-${record.recordId}")) {
                Text(memoryValue(record.eventType ?: record.kind), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(memoryRecordTitle(record), style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text(memoryTime(record.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (record.state == "candidate") Text(stringResource(R.string.native_memory_candidate), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (state.query?.nextCursor != null) item("more") { TextButton(onClick = model::loadMore, enabled = !state.recordsLoading && state.runtime.ready,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-memory-load-more")) { Text(stringResource(R.string.native_memory_load_more)) } }
    }
    dateFrom?.let { from ->
        val initial = runCatching { Instant.parse(if (from) state.filter.from else state.filter.to).atZone(ZoneId.systemDefault()).toLocalDate().let { if (from) it else it.minusDays(1) }.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(onDismissRequest = { dateFrom = null }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let { selected ->
                    val day = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate()
                    val value = (if (from) day else day.plusDays(1)).atStartOfDay(ZoneId.systemDefault()).toInstant().toString()
                    model.filter(if (from) state.filter.copy(from = value) else state.filter.copy(to = value))
                }
                dateFrom = null
            }, enabled = picker.selectedDateMillis != null, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_memory_save)) }
        }, dismissButton = { TextButton(onClick = { dateFrom = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_memory_cancel)) } }) {
            DatePicker(picker, showModeToggle = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MemoryDetail(state: NativeMemoryState, model: NativeMemoryViewModel, modifier: Modifier) {
    if (state.status?.managementAvailable != true) {
        Text(stringResource(R.string.native_memory_management_wait), modifier = modifier.padding(24.dp)); return
    }
    val record = state.selected
    var sources by rememberSaveable(record?.recordId) { mutableStateOf(false) }
    var raw by rememberSaveable(record?.recordId) { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxWidth().navigationBarsPadding().testTag("native-memory-detail"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (record != null) {
            item("content") { MemoryCard {
                Text(memoryValue(record.eventType ?: record.kind), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                SelectionContainer { Text(memoryRecordTitle(record), style = MaterialTheme.typography.bodyLarge) }
                if (record.location) {
                    if (record.city != null && record.city != record.placeName) Text(record.city)
                    if (record.latitude != null && record.longitude != null) MemoryMetadata(stringResource(R.string.native_memory_coordinates), "${record.latitude}, ${record.longitude}")
                    record.accuracyMeters?.let { Text(stringResource(R.string.native_memory_accuracy, it), style = MaterialTheme.typography.bodyMedium) }
                    if (record.stale == true) Text(stringResource(R.string.native_memory_stale_location), color = MaterialTheme.colorScheme.error)
                    if (record.isMock == true) Text(stringResource(R.string.native_memory_mock_location), color = MaterialTheme.colorScheme.error)
                    if (record.approximate == true) Text(stringResource(R.string.native_memory_approximate_location))
                }
                if (record.payloadJson != null) {
                    Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
                        TextButton(onClick = { raw = !raw }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(if (raw) R.string.native_memory_hide_details else R.string.native_memory_details_more)) }
                        if (raw) SelectionContainer { Text(remember(record.payloadJson) { JSONObject(record.payloadJson).toString(2) }, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            } }
            item("metadata") { MemoryCard {
                MemoryMetadata(stringResource(R.string.native_memory_occurred), memoryTime(record.occurredAt))
                record.recordedAt?.let { MemoryMetadata(stringResource(R.string.native_memory_recorded), memoryTime(it)) }
                MemoryMetadata(stringResource(R.string.native_memory_assertion), memoryValue(record.assertion))
                MemoryMetadata(stringResource(R.string.native_memory_actor), memoryValue(record.actor))
                MemoryMetadata(stringResource(R.string.native_memory_state), memoryValue(record.state))
                MemoryMetadata(stringResource(R.string.native_memory_retention), if (record.retentionMode == "manual") stringResource(R.string.native_memory_manual)
                    else record.expiresAt?.let { stringResource(R.string.native_memory_expires, memoryTime(it)) } ?: stringResource(R.string.native_memory_unknown))
                if (record.categories.isNotEmpty()) {
                    Text(stringResource(R.string.native_memory_categories), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        record.categories.forEach { category -> Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(memoryValue(category), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
                        } }
                    }
                }
            } }
            item("sources") { MemoryCard {
                Text(stringResource(R.string.native_memory_sources), style = MaterialTheme.typography.titleMedium)
                Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
                    TextButton(onClick = { sources = !sources }, modifier = Modifier.heightIn(min = 48.dp).testTag("native-memory-toggle-sources")) {
                        Text(stringResource(if (sources) R.string.native_memory_hide_sources else R.string.native_memory_expand_sources))
                    }
                    if (sources) {
                        if (record.sourceRefs.isEmpty() && record.lineageRefs.isEmpty() && record.derivedFrom.isEmpty()) Text(stringResource(R.string.native_memory_no_sources))
                        if (record.sourceRefs.isNotEmpty()) {
                            Text(stringResource(R.string.native_memory_source_refs), style = MaterialTheme.typography.labelLarge)
                            record.sourceRefs.forEach { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) } }
                        }
                        if (record.lineageRefs.isNotEmpty()) {
                            Text(stringResource(R.string.native_memory_lineage_refs), style = MaterialTheme.typography.labelLarge)
                            record.lineageRefs.forEach { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) } }
                        }
                        record.derivedFrom.forEach { source -> MemoryLink(source.recordId, { model.open(source.recordId) }, supporting = source.revisionId) }
                    }
                }
            } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryEditor(state: NativeMemoryState, model: NativeMemoryViewModel, modifier: Modifier) {
    if (state.status?.managementAvailable != true) {
        Text(stringResource(R.string.native_memory_management_wait), modifier = modifier.padding(24.dp)); return
    }
    val draft = state.editor ?: return
    val enabled = !state.busy && state.unconfirmed == null
    Column(modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("native-memory-editor"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (draft.creating) {
            Text(stringResource(R.string.native_memory_kind), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("fact", "preference", "decision", "task_summary").forEach { kind -> FilterChip(draft.kind == kind,
                    onClick = { model.draft(draft.copy(kind = kind)) }, label = { Text(memoryValue(kind)) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) }
            }
        }
        if (draft.original?.location == true) {
            OutlinedTextField(draft.place, { model.draft(draft.copy(place = it)) }, label = { Text(stringResource(R.string.native_memory_place)) },
                enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("native-memory-place"), singleLine = true)
            OutlinedTextField(draft.city, { model.draft(draft.copy(city = it)) }, label = { Text(stringResource(R.string.native_memory_city)) },
                enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("native-memory-city"), singleLine = true)
        } else OutlinedTextField(draft.text, { model.draft(draft.copy(text = it)) }, label = { Text(stringResource(R.string.native_memory_content)) },
            enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp).testTag("native-memory-content"), minLines = 5)
        if (!draft.valid && (draft.text.isNotEmpty() || draft.place.isNotEmpty() || draft.city.isNotEmpty())) Text(stringResource(R.string.native_memory_invalid), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun MemoryMetadata(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun memoryRecordTitle(record: NativeMemoryRecord): String = when {
    record.payloadJson == null -> memoryValue(record.state)
    record.text != null -> record.text
    record.location -> record.placeName ?: record.city ?: stringResource(R.string.native_memory_place_unnamed)
    record.eventType == "activity" -> record.appLabel ?: record.appId ?: memoryValue(record.eventType)
    else -> memoryValue(record.eventType ?: record.kind)
}

@Composable
private fun memoryTime(value: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(value, locale) { runCatching { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).format(Instant.parse(value).atZone(ZoneId.systemDefault())) }.getOrDefault(value) }
}

@Composable
private fun memoryFilterDate(value: String, end: Boolean): String {
    if (value.isEmpty()) return stringResource(R.string.native_memory_all_dates)
    val locale = LocalConfiguration.current.locales[0]
    return remember(value, end, locale) { runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate().let { if (end) it.minusDays(1) else it }
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)) }.getOrDefault(value) }
}
