package io.droyagent.app.nativeui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIcon
import io.droyagent.app.nativeui.ui.DroyIconAction
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeSchedulesScreen(
    state: NativeSchedulesState,
    model: NativeSchedulesViewModel,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
    onCreateConversation: (() -> Unit)? = null,
) {
    var discard by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(state.page) { focus.clearFocus(); keyboard?.hide() }
    val selected = state.selected
    val feedback = state.feedback?.let { scheduleMessage(it) }
    LaunchedEffect(state.feedback) {
        val code = state.feedback
        if (code != null && feedback != null) { snackbar.showSnackbar(feedback); model.consumeFeedback(code) }
    }
    fun goBack() {
        when (state.page) {
            NativeSchedulePage.CATALOG -> onBack()
            NativeSchedulePage.DETAIL -> model.catalogPage()
            NativeSchedulePage.HISTORY -> model.detailPage()
            NativeSchedulePage.EDITOR -> when {
                state.uncertain != null -> model.reviewCatalog()
                state.saving -> model.catalogPage()
                state.editor?.dirty == true -> discard = true
                else -> model.leaveEditor()
            }
        }
    }
    BackHandler { goBack() }
    val title = when (state.page) {
        NativeSchedulePage.CATALOG -> stringResource(R.string.native_schedule_title)
        NativeSchedulePage.EDITOR -> stringResource(if (state.editor?.creating == true) R.string.native_schedule_new else R.string.native_schedule_edit)
        NativeSchedulePage.HISTORY -> stringResource(R.string.native_schedule_history)
        NativeSchedulePage.DETAIL -> state.selected?.record?.title ?: stringResource(R.string.native_schedule_title)
    }
    Scaffold(modifier.fillMaxSize().testTag("native-schedules"), containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0), snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DroyIconAction(R.drawable.native_schedule_arrow_back, stringResource(R.string.native_schedule_back), ::goBack)
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (state.page == NativeSchedulePage.CATALOG) DroyIconAction(R.drawable.native_schedule_add,
                        stringResource(R.string.native_schedule_new), { onCreateConversation?.invoke() },
                        enabled = onCreateConversation != null,
                        tonal = false, modifier = Modifier.testTag("native-schedule-create"))
                    if (state.page == NativeSchedulePage.DETAIL && selected != null) {
                        DroyIconAction(R.drawable.native_ui_edit_square, stringResource(R.string.native_schedule_edit), model::beginEdit,
                            enabled = state.runtime.ready && selected.status == "active" && !state.saving && state.uncertain == null,
                            modifier = Modifier.testTag("native-schedule-edit"))
                        DroyIconAction(R.drawable.native_schedule_delete, stringResource(R.string.native_schedule_delete), { delete = true },
                            enabled = state.runtime.ready && !state.saving && state.uncertain == null,
                            modifier = Modifier.testTag("native-schedule-delete"))
                    }

                }
            }
        }, bottomBar = {
            if (state.page == NativeSchedulePage.EDITOR) {
                val draft = state.editor
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Button(onClick = model::save,
                        enabled = state.runtime.ready && !state.saving && state.uncertain == null && draft != null && (draft.creating || draft.dirty)
                            && draft.title.isNotBlank() && draft.title.trim().length <= 120 && draft.prompt.isNotBlank() && draft.sessionId != null
                            && state.error !in setOf("schedule_conflict", "schedule_ended", "schedule_not_found"),
                        modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                            .padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 56.dp).testTag("native-schedule-save")) {
                        Text(stringResource(if (state.saving) R.string.native_schedule_saving else R.string.native_schedule_save))
                    }
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (!state.runtime.ready) ScheduleRuntime(state, model::startRuntime)
            if (state.uncertain != null) UncertainNotice(state, model)
            if (state.error != null && state.error != "schedule_result_unconfirmed") ScheduleNotice(state.error,
                onRefresh = if (state.page == NativeSchedulePage.EDITOR && state.error in setOf("schedule_conflict", "schedule_ended", "schedule_not_found")) model::reloadEditor else null,
                refreshText = stringResource(R.string.native_schedule_reload_editor))
            if (state.catalogError != null) ScheduleNotice(state.catalogError, model::refresh)
            when (state.page) {
                NativeSchedulePage.CATALOG -> ScheduleCatalog(state, model, Modifier.weight(1f))
                NativeSchedulePage.DETAIL -> ScheduleDetail(state, model, onOpenSession, Modifier.weight(1f))
                NativeSchedulePage.EDITOR -> state.editor?.let { ScheduleEditor(it, state, model, Modifier.weight(1f)) }
                NativeSchedulePage.HISTORY -> ScheduleHistory(state, model, Modifier.weight(1f))
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.native_schedule_discard_title)) },
        confirmButton = { TextButton(onClick = { discard = false; model.leaveEditor() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_schedule_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_schedule_keep_editing)) } })
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text(stringResource(R.string.native_schedule_delete_title)) },
        text = { Text(stringResource(R.string.native_schedule_delete_scope)) },
        confirmButton = { TextButton(onClick = { delete = false; model.delete() }, modifier = Modifier.heightIn(min = 48.dp).testTag("native-schedule-confirm-delete")) {
            Text(stringResource(R.string.native_schedule_confirm_delete), color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = { delete = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_schedule_cancel)) } })
}

@Composable
private fun ScheduleRuntime(state: NativeSchedulesState, onStart: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.padding(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(state.runtime.status.ifBlank { stringResource(if (state.runtime.active) R.string.native_engine_starting else R.string.native_engine_unavailable) })
            if (state.runtime.canStart) Button(onClick = onStart, modifier = Modifier.heightIn(min = 56.dp).testTag("native-schedule-start-runtime")) {
                Text(stringResource(R.string.native_start_engine))
            } else if (state.runtime.active) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UncertainNotice(state: NativeSchedulesState, model: NativeSchedulesViewModel) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.native_schedule_uncertain), color = MaterialTheme.colorScheme.onErrorContainer)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = model::refresh, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_schedule_refresh)) }
                if (state.page != NativeSchedulePage.CATALOG) TextButton(onClick = model::reviewCatalog, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_schedule_review)) }
                if (state.uncertain?.operation == NativeScheduleOperation.UPDATE && state.uncertain.checked && state.page == NativeSchedulePage.EDITOR)
                    TextButton(onClick = model::reloadEditor, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_schedule_reload_editor)) }
                if (state.uncertain?.checked == true && state.page == NativeSchedulePage.CATALOG) TextButton(onClick = model::acknowledgeReview,
                    enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp).testTag("native-schedule-review-done")) { Text(stringResource(R.string.native_schedule_review_done)) }
            }
        }
    }
}

@Composable
private fun ScheduleCatalog(state: NativeSchedulesState, model: NativeSchedulesViewModel, modifier: Modifier) {
    LazyColumn(modifier.fillMaxWidth().navigationBarsPadding().testTag("native-schedule-catalog"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.loading && !state.catalogLoaded) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.targetsError != null) item(key = "targets-error") { ScheduleNotice(state.targetsError, model::refreshTargets) }
        if (!state.loading && state.catalogError == null && state.entries.isEmpty()) item(key = "empty") {
            Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(if (state.catalogLoaded) R.string.native_schedule_empty else R.string.native_schedule_not_loaded), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(state.entries.sortedWith(compareBy({ it.record.scheduledAt }, { it.record.id })), key = { "${it.sessionId}:${it.record.id}" }) { entry ->
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { model.open(entry.key) }.testTag("native-schedule:${entry.record.id}")) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.record.title, style = MaterialTheme.typography.titleMedium)
                    Text(scheduleRule(entry.record), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(scheduleInstant(entry.record.scheduledAt, entry.record.timeZone), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (entry.status == "active") R.string.native_schedule_active else R.string.native_schedule_inactive),
                            style = MaterialTheme.typography.labelMedium, color = if (entry.status == "active") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(state.sessionTitles[entry.sessionId] ?: entry.sessionId, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleDetail(state: NativeSchedulesState, model: NativeSchedulesViewModel, onOpenSession: (String) -> Unit, modifier: Modifier) {
    val entry = state.selected
    if (entry == null) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (state.loading) CircularProgressIndicator() else Text(stringResource(if (state.catalogLoaded) R.string.native_schedule_not_found else R.string.native_schedule_not_loaded))
        }
        return
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(stringResource(if (entry.status == "active") R.string.native_schedule_active else R.string.native_schedule_inactive),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(entry.record.title, style = MaterialTheme.typography.headlineSmall)
        ScheduleField(stringResource(R.string.native_schedule_instruction), entry.record.prompt, selectable = true)
        ScheduleField(stringResource(R.string.native_schedule_rule), scheduleRule(entry.record))
        entry.record.timeZone?.let { ScheduleField(stringResource(R.string.native_schedule_zone), it) }
        ScheduleField(stringResource(if (entry.status == "active") R.string.native_schedule_next_delivery else R.string.native_schedule_scheduled_at),
            scheduleInstant(entry.record.scheduledAt, entry.record.timeZone))
        entry.lastDelivery?.let { ScheduleField(stringResource(R.string.native_schedule_last_delivery), scheduleInstant(it.deliveredAt, entry.record.timeZone)) }
        OutlinedButton(onClick = { onOpenSession(entry.sessionId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-schedule-open-session")) {
            Text(state.sessionTitles[entry.sessionId] ?: stringResource(R.string.native_schedule_open_session))
        }
        Button(onClick = model::historyPage, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-schedule-history")) {
            Text(stringResource(R.string.native_schedule_history))
        }
    }
}

@Composable
private fun ScheduleHistory(state: NativeSchedulesState, model: NativeSchedulesViewModel, modifier: Modifier) {
    val history = state.history
    LazyColumn(modifier.fillMaxWidth().navigationBarsPadding().testTag("native-schedule-history-list"),
        contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (history.error != null) item(key = "error") { ScheduleNotice(history.error, model::refreshHistory) }
        if (history.loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (history.loaded && history.error == null && history.records.isEmpty()) item(key = "empty") { Text(stringResource(R.string.native_schedule_history_empty)) }
        items(history.records, key = { it.messageId }) { delivery ->
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScheduleField(stringResource(R.string.native_schedule_delivered_at), scheduleInstant(delivery.deliveredAt, state.selected?.record?.timeZone))
                    ScheduleField(stringResource(R.string.native_schedule_scheduled_at), scheduleInstant(delivery.scheduledAt, state.selected?.record?.timeZone))
                    SelectionContainer { Text(delivery.prompt ?: stringResource(R.string.native_schedule_prompt_missing)) }
                }
            }
        }
        if (history.nextBefore != null) item(key = "older") {
            TextButton(onClick = model::loadMoreHistory, enabled = !history.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-schedule-history-older")) {
                Text(stringResource(R.string.native_schedule_load_older))
            }
        }
        if (history.loaded) item(key = "retention") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (history.earlierRecordsUnavailable) Text(stringResource(R.string.native_schedule_history_unavailable), style = MaterialTheme.typography.bodyMedium)
                if (history.earlierRecordsPruned) Text(stringResource(R.string.native_schedule_history_pruned), style = MaterialTheme.typography.bodyMedium)
                if (history.retentionDays != null && history.retentionRecords != null) Text(stringResource(R.string.native_schedule_retention, history.retentionDays, history.retentionRecords),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScheduleEditor(draft: NativeScheduleDraft, state: NativeSchedulesState, model: NativeSchedulesViewModel, modifier: Modifier) {
    var picker by rememberSaveable(draft.expected?.id, draft.creating) { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val zones = remember(query) { ZoneId.getAvailableZoneIds().filter { (it == "UTC" || it.contains('/')) && it.contains(query, true) }.sorted() }
    val enabled = !state.saving && state.uncertain == null
    fun timing(value: NativeScheduleTiming) { model.updateDraft(draft.copy(timing = value)) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (draft.creating) OutlinedButton(onClick = { model.refreshTargets(); picker = "target" }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-schedule-target")) {
            Text(state.targets.firstOrNull { it.id == draft.sessionId }?.title ?: stringResource(R.string.native_schedule_target), Modifier.weight(1f))
            DroyIcon(R.drawable.native_ui_expand_more, null)
        } else ScheduleField(stringResource(R.string.native_schedule_target), state.sessionTitles[draft.sessionId] ?: draft.sessionId.orEmpty())
        OutlinedTextField(draft.title, { model.updateDraft(draft.copy(title = it)) }, Modifier.fillMaxWidth().testTag("native-schedule-name"),
            label = { Text(stringResource(R.string.native_schedule_name)) }, enabled = enabled, singleLine = true,
            isError = draft.title.trim().length > 120, shape = MaterialTheme.shapes.medium,
            supportingText = if (draft.title.trim().length > 120) ({ Text(stringResource(R.string.native_schedule_name_long)) }) else null)
        OutlinedTextField(draft.prompt, { model.updateDraft(draft.copy(prompt = it)) }, Modifier.fillMaxWidth().heightIn(min = 144.dp, max = 320.dp).testTag("native-schedule-prompt"),
            label = { Text(stringResource(R.string.native_schedule_instruction)) }, enabled = enabled, maxLines = 10, shape = MaterialTheme.shapes.medium)
        OutlinedButton(onClick = { picker = "rule" }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-schedule-rule")) {
            Text(scheduleKind(draft.timing.kind), Modifier.weight(1f)); DroyIcon(R.drawable.native_ui_expand_more, null)
        }
        when (draft.timing.kind) {
            "after", "every" -> OutlinedTextField(draft.timing.seconds, { timing(draft.timing.copy(seconds = it)) },
                Modifier.fillMaxWidth().testTag("native-schedule-seconds"), enabled = enabled, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(stringResource(if (draft.timing.kind == "after") R.string.native_schedule_delay else R.string.native_schedule_interval)) },
                shape = MaterialTheme.shapes.medium)
            "at", "daily", "weekly" -> {
                if (draft.timing.kind == "at") OutlinedButton(onClick = { picker = "date" }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-schedule-date")) {
                    Text(stringResource(R.string.native_schedule_date), Modifier.weight(1f)); Text(draft.timing.date)
                }
                OutlinedTextField(draft.timing.time, { timing(draft.timing.copy(time = it)) }, Modifier.fillMaxWidth().testTag("native-schedule-time"),
                    enabled = enabled, singleLine = true, label = { Text(stringResource(R.string.native_schedule_time)) },
                    placeholder = { Text(stringResource(R.string.native_schedule_time_hint)) }, trailingIcon = {
                        DroyIconAction(R.drawable.native_schedule_calendar_month, stringResource(R.string.native_schedule_time), { picker = "time" }, enabled = enabled)
                    }, shape = MaterialTheme.shapes.medium)
                if (draft.timing.kind == "weekly") {
                    Text(stringResource(R.string.native_schedule_weekdays), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..7).forEach { day -> FilterChip(selected = day in draft.timing.weekdays,
                            onClick = { timing(draft.timing.copy(weekdays = (if (day in draft.timing.weekdays) draft.timing.weekdays - day else draft.timing.weekdays + day).sorted())) },
                            enabled = enabled, label = { Text(scheduleWeekday(day)) }, modifier = Modifier.heightIn(min = 48.dp)) }
                    }
                }
            }
            "cron" -> OutlinedTextField(draft.timing.expression, { timing(draft.timing.copy(expression = it)) }, Modifier.fillMaxWidth().testTag("native-schedule-cron"),
                enabled = enabled, label = { Text(stringResource(R.string.native_schedule_expression)) }, maxLines = 3,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), shape = MaterialTheme.shapes.medium)
        }
        if (draft.timing.kind !in setOf("after", "every")) OutlinedTextField(draft.timing.timeZone,
            { timing(draft.timing.copy(timeZone = it)) }, Modifier.fillMaxWidth().testTag("native-schedule-zone"), enabled = enabled, singleLine = true,
            label = { Text(stringResource(R.string.native_schedule_zone)) }, trailingIcon = {
                DroyIconAction(R.drawable.native_ui_expand_more, stringResource(R.string.native_schedule_zone), { query = ""; picker = "zone" }, enabled = enabled)
            }, shape = MaterialTheme.shapes.medium)
        Spacer(Modifier.height(8.dp))
    }
    if (picker == "date") {
        val millis = try { LocalDate.parse(draft.timing.date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() } catch (_: DateTimeException) { null }
        val date = rememberDatePickerState(initialSelectedDateMillis = millis, yearRange = 1..9999)
        DatePickerDialog(onDismissRequest = { picker = null }, confirmButton = {
            TextButton(onClick = { date.selectedDateMillis?.let { timing(draft.timing.copy(date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())) }; picker = null },
                enabled = date.selectedDateMillis != null) { Text(stringResource(R.string.native_schedule_select)) }
        }, dismissButton = { TextButton(onClick = { picker = null }) { Text(stringResource(R.string.native_schedule_cancel)) } }) { DatePicker(date) }
    }
    if (picker == "time") {
        val original = try { LocalTime.parse(draft.timing.time) } catch (_: DateTimeException) { LocalTime.of(9, 0) }
        val time = rememberTimePickerState(initialHour = original.hour, initialMinute = original.minute, is24Hour = true)
        AlertDialog(onDismissRequest = { picker = null }, title = { Text(stringResource(R.string.native_schedule_time)) }, text = { TimeInput(time) },
            confirmButton = { TextButton(onClick = {
                val selected = if (time.hour == original.hour && time.minute == original.minute) draft.timing.time
                    else String.format(Locale.ROOT, "%02d:%02d:00.000", time.hour, time.minute)
                timing(draft.timing.copy(time = selected)); picker = null
            }) { Text(stringResource(R.string.native_schedule_select)) } }, dismissButton = { TextButton(onClick = { picker = null }) { Text(stringResource(R.string.native_schedule_cancel)) } })
    }
    if (picker in setOf("target", "rule", "zone")) ModalBottomSheet(onDismissRequest = { picker = null },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).padding(horizontal = 24.dp)) {
            if (picker == "zone") OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.native_schedule_search_zone)) }, shape = MaterialTheme.shapes.medium)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(vertical = 16.dp)) {
                when (picker) {
                    "target" -> {
                        if (state.targetsLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        if (state.targetsError != null) item { ScheduleNotice(state.targetsError, model::refreshTargets) }
                        items(state.targets, key = { it.id }) { target ->
                            PickerRow(target.title, target.workspaceTitle, draft.sessionId == target.id) {
                                model.updateDraft(draft.copy(sessionId = target.id)); picker = null
                            }
                        }
                        if (state.targetsLoaded && state.targets.isEmpty() && state.targetsError == null) item { Text(stringResource(R.string.native_schedule_no_targets)) }
                    }
                    "rule" -> items(if (draft.creating) listOf("at", "after", "every", "daily", "weekly", "cron") else listOf("at", "every", "daily", "weekly", "cron")) { kind ->
                        PickerRow(scheduleKind(kind), null, draft.timing.kind == kind) {
                            timing(draft.timing.copy(kind = kind)); picker = null
                        }
                    }
                    "zone" -> items(zones, key = { it }) { zone ->
                        PickerRow(zone, null, draft.timing.timeZone == zone) { timing(draft.timing.copy(timeZone = zone)); picker = null }
                    }
                }
            }
            TextButton(onClick = { picker = null }, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text(stringResource(R.string.native_schedule_close)) }
        }
    }
}

@Composable
private fun PickerRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = if (subtitle != null) ({ Text(subtitle) }) else null,
        trailingContent = { if (selected) DroyIcon(R.drawable.native_ui_check, null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).heightIn(min = 64.dp))
}

@Composable
private fun ScheduleField(label: String, value: String, selectable: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (selectable) SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) } else Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ScheduleNotice(code: String, onRefresh: (() -> Unit)? = null, refreshText: String = stringResource(R.string.native_schedule_refresh)) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(scheduleMessage(code), color = MaterialTheme.colorScheme.onErrorContainer)
            if (onRefresh != null) TextButton(onClick = onRefresh, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text(refreshText) }
        }
    }
}

@Composable
private fun scheduleMessage(code: String): String = stringResource(when (code) {
    "schedule_saved" -> R.string.native_schedule_saved
    "schedule_deleted" -> R.string.native_schedule_deleted
    "schedule_unchanged" -> R.string.native_schedule_unchanged
    "schedule_reloaded" -> R.string.native_schedule_reloaded
    "schedule_conflict" -> R.string.native_schedule_conflict
    "schedule_ended" -> R.string.native_schedule_ended
    "schedule_not_found" -> R.string.native_schedule_not_found
    "delivery_cursor_not_found" -> R.string.native_schedule_cursor_missing
    "invalid_prompt" -> R.string.native_schedule_error_prompt
    "invalid_time_zone" -> R.string.native_schedule_error_zone
    "invalid_rule", "invalid_selector", "NATIVE_SCHEDULES_BAD_REQUEST" -> R.string.native_schedule_error_rule
    "not_future" -> R.string.native_schedule_error_future
    "time_out_of_range" -> R.string.native_schedule_error_range
    "frequency_too_high" -> R.string.native_schedule_error_frequency
    "subagent_session", "COMPANION_SESSION_MANAGED", "NATIVE_SCHEDULES_TARGET_UNAVAILABLE" -> R.string.native_schedule_managed
    "schedule_target_required", "NATIVE_SCHEDULES_SESSION_NOT_FOUND" -> R.string.native_schedule_error_target
    "schedule_no_targets" -> R.string.native_schedule_no_targets
    "schedule_targets_unavailable" -> R.string.native_schedule_targets_unavailable
    "NATIVE_SCHEDULES_NOT_READY", "service_temporarily_unavailable" -> R.string.native_schedule_error_unavailable
    "NATIVE_SCHEDULES_TOO_LARGE", "response_too_large" -> R.string.native_schedule_error_large
    "network_error", "runtime_unavailable" -> R.string.native_schedule_error_network
    "malformed_response" -> R.string.native_schedule_error_response
    "runtime_start_failed" -> R.string.native_engine_start_failed
    "schedule_result_unconfirmed", "NATIVE_SCHEDULES_CANCELLED", "NATIVE_SCHEDULES_FAILED" -> R.string.native_schedule_uncertain
    else -> R.string.native_schedule_error_generic
})

@Composable
private fun scheduleKind(value: String): String = stringResource(when (value) {
    "after" -> R.string.native_schedule_after
    "every" -> R.string.native_schedule_every
    "daily" -> R.string.native_schedule_daily
    "weekly" -> R.string.native_schedule_weekly
    "cron" -> R.string.native_schedule_cron
    else -> R.string.native_schedule_at
})

@Composable
private fun scheduleWeekday(day: Int): String = stringResource(when (day) {
    1 -> R.string.native_schedule_weekday1
    2 -> R.string.native_schedule_weekday2
    3 -> R.string.native_schedule_weekday3
    4 -> R.string.native_schedule_weekday4
    5 -> R.string.native_schedule_weekday5
    6 -> R.string.native_schedule_weekday6
    else -> R.string.native_schedule_weekday7
})

@Composable
private fun scheduleRule(record: NativeScheduleRecord): String = when (record.kind) {
    "after" -> stringResource(R.string.native_schedule_after_seconds, requireNotNull(record.afterSeconds).toString())
    "every" -> stringResource(R.string.native_schedule_seconds, requireNotNull(record.everySeconds).toString())
    "daily" -> stringResource(R.string.native_schedule_daily_time, requireNotNull(record.time))
    "weekly" -> stringResource(R.string.native_schedule_weekly_time,
        record.weekdays.map { scheduleWeekday(it) }.joinToString(stringResource(R.string.native_schedule_weekday_join)), requireNotNull(record.time))
    "cron" -> requireNotNull(record.expression)
    else -> scheduleKind(record.kind)
}

@Composable
private fun scheduleInstant(value: String, zone: String?): String {
    val locale = LocalContext.current.resources.configuration.locales[0]
    val actualZone = try { zone?.let(ZoneId::of) ?: ZoneId.systemDefault() } catch (_: DateTimeException) { ZoneOffset.UTC }
    val text = remember(value, actualZone, locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(actualZone).format(Instant.parse(value))
    }
    return if (zone != null && actualZone == ZoneOffset.UTC && zone != "UTC" && zone != "Z") stringResource(R.string.native_schedule_utc, text) else text
}
