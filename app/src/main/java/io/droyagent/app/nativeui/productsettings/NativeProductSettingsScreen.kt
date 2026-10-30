package io.droyagent.app.nativeui.productsettings

import io.droyagent.app.nativeui.ui.DroySwitch as Switch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.droyagent.app.NativeProductSettingsHost
import io.droyagent.app.NativePersonalizationReset
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIcon
import io.droyagent.app.nativeui.ui.DroyIconAction
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class FieldEditor(val label: Int, val value: String, val save: suspend (String) -> Unit)
private data class Confirmation(val message: Int, val action: suspend () -> Unit)

/** Host owns mutations. The general slot reuses the existing DSH language/theme source. */
@Composable
fun NativeProductSettingsScreen(
    host: NativeProductSettingsHost,
    actions: ProductSettingsActions,
    initialSection: ProductSettingsSection = ProductSettingsSection.General,
    modifier: Modifier = Modifier,
    generalPreferences: @Composable () -> Unit = {},
    onReady: (Boolean) -> Unit = {},
    presentation: ProductSettingsPresentationState = remember(host) { ProductSettingsPresentationState() },
) {
    val controller = remember(host) { ProductSettingsController(host) }
    val section = initialSection
    val scope = rememberCoroutineScope()
    var snapshot by remember(section, presentation) { mutableStateOf(presentation.current(section)) }
    var pets by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var catalogError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var refreshJob by remember { mutableStateOf<Job?>(null) }
    var failed by remember { mutableStateOf(false) }
    var failureMessage by remember { mutableIntStateOf(R.string.nps_error) }
    var editor by remember { mutableStateOf<FieldEditor?>(null) }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    var diagnosticTaps by rememberSaveable { mutableIntStateOf(0) }
    var selectedMode by remember { mutableStateOf<String?>(null) }
    var notificationPage by rememberSaveable { mutableStateOf<String?>(null) }
    var textRuleEditor by remember { mutableStateOf(false) }
    var search by rememberSaveable(section) { mutableStateOf("") }
    val reportReady by rememberUpdatedState(onReady)
    LaunchedEffect(snapshot, busy, failed) { reportReady(snapshot != null && !busy && !failed) }
    DisposableEffect(section) { onDispose { reportReady(false) } }
    val clipboard = LocalClipboardManager.current
    suspend fun refresh() {
        snapshot = controller.snapshot(section).also { presentation.remember(section, it) }
        if (section == ProductSettingsSection.Pet) {
            try { pets = controller.pets(); catalogError = false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { pets = listOf(JSONObject().put("id", "default").put("name", "Droy").put("status", "ready")); catalogError = true }
        }
    }
    fun run(keepEditors: Boolean = false, action: suspend () -> Unit = {}) {
        if (busy) return
        refreshJob?.cancel(); refreshJob = null
        busy = true
        if (!keepEditors || confirmation == null) failed = false
        scope.launch {
            try { action(); refresh(); if (!keepEditors) { editor = null; confirmation = null } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed = true; failureMessage = personalizationFailure(error) }
            finally { busy = false }
        }
    }
    fun refreshPage() {
        if (busy || refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            try { refresh(); failed = false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed = true; failureMessage = personalizationFailure(error) }
        }
    }
    LaunchedEffect(section) { refreshPage() }
    LaunchedEffect(actions.resetPersonalizationReady) { if (section == ProductSettingsSection.Diagnostics) refreshPage() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, section) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshPage() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val back = { if (notificationPage != null) notificationPage = null else actions.onBack() }
    BackHandler(enabled = editor == null && confirmation == null && !textRuleEditor) { back() }
    val state = snapshot
    // Start and stop return before the runtime reaches its final state.
    LaunchedEffect(lifecycle, section, state?.optBoolean("runtimePresent"), state?.optBoolean("runtimeReady")) {
        if (section == ProductSettingsSection.Diagnostics && state?.optBoolean("runtimePresent") == true && !state.optBoolean("runtimeReady")) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    delay(750)
                    if (!busy) {
                        try { snapshot = controller.snapshot(section).also { presentation.remember(section, it) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true; failureMessage = R.string.nps_error; break }
                    }
                }
            }
        }
    }
    val title = when (section) {
        ProductSettingsSection.General -> R.string.nps_general
        ProductSettingsSection.Notifications -> when (notificationPage) { "apps" -> R.string.nps_app_management; "history" -> R.string.nps_archive; else -> R.string.nps_notifications }
        ProductSettingsSection.Proactive -> R.string.nps_proactive
        ProductSettingsSection.Pet -> R.string.nps_pet
        ProductSettingsSection.Diagnostics -> R.string.nps_diagnostics
    }
    Scaffold(modifier.fillMaxSize().testTag("native-product-settings:${section.wire}"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0), containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DroyIconAction(R.drawable.native_settings_arrow_back, stringResource(R.string.nps_back), back,
                        modifier = Modifier.testTag("native-product-back"))
                    Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                }
            }
        }) { padding ->
    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag(
        if (state != null && !busy && !failed) "native-product-body:${section.wire}" else "native-product-pending:${section.wire}")) {
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().imePadding().testTag("native-product-list:${section.wire}"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (failed) item {
            Text(stringResource(failureMessage), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("native-product-error:${section.wire}"))
            TextButton(onClick = { run() }, enabled = !busy) { Text(stringResource(R.string.nps_retry)) }
        }
        if (state != null) when (section) {
            ProductSettingsSection.General -> {
                item { generalPreferences() }
                item { Toggle(R.string.nps_return, state.optBoolean("returnToDroyOnCompletion"), busy) { run { controller.display("returnToDroyOnCompletion", it) } } }
            }
            ProductSettingsSection.Notifications -> {
                when (notificationPage) {
                    "apps" -> {
                        item {
                            Row {
                                TextButton(onClick = { run { controller.notifications(JSONObject().put("operation", "setApps").put("enabled", true).put("packageNames", JSONArray(state.optJSONArray("apps").rows().map { it.getString("packageName") }))) } }, enabled = !busy) { Text(stringResource(R.string.nps_all_on)) }
                                TextButton(onClick = { run { controller.notifications(JSONObject().put("operation", "setApps").put("enabled", false).put("packageNames", JSONArray(state.optJSONArray("apps").rows().map { it.getString("packageName") }))) } }, enabled = !busy) { Text(stringResource(R.string.nps_all_off)) }
                            }
                            OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.nps_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        items(state.optJSONArray("apps").rows().filter { it.optString("label").contains(search, true) || it.optString("packageName").contains(search, true) }, key = { it.getString("packageName") }) { app ->
                            val managed = app.optString("mode") == "managed"
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(managed, !busy, Role.Switch) { value -> run {
                                controller.notifications(JSONObject().put("operation", "setAppMode").put("packageName", app.getString("packageName")).put("mode", if (value) "managed" else "allow"))
                            } }, verticalAlignment = Alignment.CenterVertically) {
                                Text(app.optString("label"), Modifier.weight(1f).padding(end = 12.dp))
                                Switch(managed, null, enabled = !busy)
                            }
                        }
                    }
                    "history" -> {
                        if (state.optJSONArray("archive").rows().isEmpty()) item { Text(stringResource(R.string.nps_empty)) }
                        items(state.optJSONArray("archive").rows()) { notice ->
                            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(notice.optString("title"), style = MaterialTheme.typography.titleSmall)
                                    Text(notice.optString("text"), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    else -> {
                        item { Toggle(R.string.nps_enabled, state.optBoolean("enabled"), busy) { enabled ->
                            if (!enabled) run { controller.notifications(JSONObject().put("operation", "configure").put("enabled", false)) }
                            else if (state.optBoolean("requiresReview")) textRuleEditor = true
                            else confirmation = Confirmation(R.string.nps_cloud_hint) { controller.notifications(JSONObject().put("operation", "configure").put("enabled", true)) }
                        } }
                        if (state.optBoolean("requiresReview")) item { Text(stringResource(R.string.nps_review_rules), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (state.optBoolean("enabled") && (!state.optBoolean("permissionGranted") || !state.optBoolean("connected"))) item {
                            TextButton(onClick = actions.onPermissions) { Text(stringResource(R.string.nps_permissions)) }
                        }
                        item {
                            ProductGroup {
                                EditRow(R.string.nps_app_management, "", busy) { notificationPage = "apps" }
                                EditRow(R.string.nps_rules, "", busy) { textRuleEditor = true }
                                EditRow(R.string.nps_archive, "", busy) { notificationPage = "history" }
                            }
                        }
                    }
                }
            }
            ProductSettingsSection.Proactive -> {
                item {
                    ProductGroup {
                        Toggle(R.string.nps_message_notifications, state.optBoolean("messagesEnabled"), busy, contained = true) {
                            run { controller.proactive(JSONObject().put("messagesEnabled", it)) }
                        }
                        Toggle(R.string.nps_task_notifications, state.optBoolean("tasksEnabled"), busy, contained = true) {
                            run { controller.proactive(JSONObject().put("tasksEnabled", it)) }
                        }
                    }
                }
            }
            ProductSettingsSection.Pet -> {
                item {
                    ProductGroup {
                        Toggle(R.string.nps_pet_enabled, state.optBoolean("petEnabled"), busy, contained = true) { run { controller.display("petEnabled", it) } }
                        key(state.optString("petId")) {
                            AndroidView(factory = { host.createPetPreview() }, modifier = Modifier.fillMaxWidth().height(176.dp)
                                .padding(horizontal = 16.dp, vertical = 8.dp).testTag("native-product-pet-preview"))
                        }
                    }
                }
                item {
                    ProductGroup {
                        for ((key, label) in listOf("petExternalDisplay" to R.string.nps_external, "petInternalDisplay" to R.string.nps_internal)) {
                            val mode = state.optString(key)
                            val text = stringResource(when (mode) { "always" -> R.string.nps_always; "adaptive" -> R.string.nps_adaptive; else -> R.string.nps_off })
                            EditRow(label, text, busy || !state.optBoolean("petEnabled")) { selectedMode = key }
                        }
                    }
                }
                if (state.optBoolean("petEnabled") && state.optString("petExternalDisplay") != "off" && !state.optBoolean("petPermissionGranted")) item {
                    TextButton(onClick = actions.onOverlayPermission) { Text(stringResource(R.string.nps_overlay)) }
                }
                item {
                    Text(stringResource(R.string.nps_artwork), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    if (catalogError) {
                        Text(stringResource(R.string.nps_catalog_error))
                        TextButton(onClick = { run() }, enabled = !busy) { Text(stringResource(R.string.nps_retry)) }
                    }
                }
                items(pets, key = { it.getString("id") }) { pet ->
                    val selected = state.optString("petId") == pet.optString("id")
                    val enabled = !busy && pet.optString("status") == "ready"
                    Surface(shape = MaterialTheme.shapes.large,
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(selected, enabled, Role.RadioButton) {
                            run { controller.selectPet(pet.getString("id")) }
                        }.testTag("native-product-artwork:${pet.getString("id")}").padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            DroyIcon(R.drawable.native_settings_pets, null)
                            Text(pet.optString("name"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            RadioButton(selected, onClick = null, enabled = enabled)
                        }
                    }
                }
            }
            ProductSettingsSection.Diagnostics -> {
                item {
                    Text("Droy", style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.nps_about_description), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.optString("droyVersion", stringResource(R.string.nps_unavailable)),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { diagnosticTaps = (diagnosticTaps + 1).coerceAtMost(7) }
                            .padding(vertical = 12.dp).testTag("native-product-version"))
                    if (diagnosticTaps >= 7) {
                    val unavailable = stringResource(R.string.nps_unavailable)
                    val phase = when (state.optString("phase")) {
                        "thinking" -> R.string.nps_phase_thinking
                        "executing" -> R.string.nps_phase_executing
                        "verifying" -> R.string.nps_phase_verifying
                        "waiting" -> R.string.nps_phase_waiting
                        "waiting_input" -> R.string.nps_phase_input
                        "blocked" -> R.string.nps_phase_blocked
                        "completed" -> R.string.nps_phase_completed
                        "interrupted" -> R.string.nps_phase_interrupted
                        else -> R.string.nps_phase_none
                    }
                    val noCodes = stringResource(R.string.nps_no_codes)
                    val summary = listOf("Droy ${state.optString("droyVersion", unavailable)}", "DSH ${state.optString("dshVersion", unavailable)}",
                        stringResource(R.string.nps_adapter) + " ${state.optString("mobileVersion", unavailable)}",
                        "Android ${state.optString("androidRelease", unavailable)} / API ${state.getInt("androidApi")}",
                        stringResource(R.string.nps_runtime_present) + ": " + stringResource(if (state.optBoolean("runtimePresent")) R.string.nps_true else R.string.nps_false),
                        stringResource(R.string.nps_ready) + ": " + stringResource(if (state.optBoolean("runtimeReady")) R.string.nps_true else R.string.nps_false),
                        stringResource(R.string.nps_paused) + ": " + stringResource(if (state.optBoolean("toolsPaused")) R.string.nps_true else R.string.nps_false),
                        stringResource(R.string.nps_phase) + ": " + stringResource(phase),
                        stringResource(R.string.nps_phase_active) + ": " + stringResource(if (state.optBoolean("phaseActive")) R.string.nps_true else R.string.nps_false),
                        stringResource(R.string.nps_diagnostic_codes) + ": " + state.optJSONArray("notices").rows()
                            .joinToString(", ") { "${it.getString("code")} × ${it.getInt("count")}" }.ifEmpty { noCodes }
                    ).joinToString("\n")
                    Text(summary, modifier = Modifier.testTag("native-product-diagnostics-text"))
                    TextButton(onClick = { clipboard.setText(AnnotatedString(summary)) }, modifier = Modifier.testTag("native-product-diagnostics-copy")) { Text(stringResource(R.string.nps_copy)) }
                    }
                }
            }
        }
    }
    }
    }
    selectedMode?.let { key ->
        PetModeSheet(state?.optString(key).orEmpty(), busy, { selectedMode = null }) { value ->
            run { controller.display(key, value) }; selectedMode = null
        }
    }
    if (textRuleEditor && state != null) NotificationTextRuleEditor(state.optString("ruleText"),
        save = { value -> controller.notifications(JSONObject().put("operation", "configure").put("ruleText", value)) },
        reset = { controller.notifications(JSONObject().put("operation", "configure").put("resetRule", true)).optString("ruleText") },
        onDismiss = { textRuleEditor = false; run() })
    editor?.let { field ->
        var draft by remember(field) { mutableStateOf(field.value) }
        AlertDialog(onDismissRequest = { if (!busy) editor = null }, title = { Text(stringResource(field.label)) },
            text = { Column { OutlinedTextField(draft, { draft = it }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("native-product-editor-input")); if (failed) Text(stringResource(R.string.nps_error), color = MaterialTheme.colorScheme.error) } },
            confirmButton = { TextButton(onClick = { run { field.save(draft) } }, enabled = !busy, modifier = Modifier.testTag("native-product-editor-save")) { Text(stringResource(R.string.nps_save)) } },
            dismissButton = { TextButton(onClick = { editor = null }, enabled = !busy) { Text(stringResource(R.string.nps_cancel)) } })
    }
    confirmation?.let { dialog ->
        AlertDialog(onDismissRequest = { if (!busy) confirmation = null }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(dialog.message))
                if (failed) Text(stringResource(if (dialog.message == R.string.nps_reset_confirm) failureMessage else R.string.nps_error), color = MaterialTheme.colorScheme.error)
            }
        },
            confirmButton = { TextButton(onClick = { run { dialog.action() } }, enabled = !busy && (dialog.message != R.string.nps_reset_confirm || actions.resetPersonalizationReady)) { Text(stringResource(R.string.nps_continue)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !busy) { Text(stringResource(R.string.nps_cancel)) } })
    }
}

private fun personalizationFailure(error: Exception): Int = when ((error as? NativePersonalizationReset.Failure)?.stage) {
    NativePersonalizationReset.Stage.NOT_READY -> R.string.nps_reset_needs_engine
    NativePersonalizationReset.Stage.ENGINE_CONFLICT -> R.string.nps_reset_theme_conflict
    NativePersonalizationReset.Stage.ENGINE_UNCONFIRMED -> R.string.nps_reset_theme_unconfirmed
    NativePersonalizationReset.Stage.LOCAL_UNCONFIRMED -> R.string.nps_reset_local_unconfirmed
    NativePersonalizationReset.Stage.PRESENTATION_UNCONFIRMED -> R.string.nps_reset_display_unconfirmed
    null -> R.string.nps_error
}

@Composable private fun ProductGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()) { Column(content = content) }
}

@Composable private fun EditRow(label: Int, value: String, busy: Boolean, edit: () -> Unit) {
    val tag = LocalContext.current.resources.getResourceEntryName(label)
    Row(Modifier.fillMaxWidth().testTag("native-product-field:$tag")
        .clickable(enabled = !busy, role = Role.Button, onClick = edit).heightIn(min = 64.dp)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
            color = if (busy) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            DroyIcon(R.drawable.native_settings_chevron_right, null)
        }
    }
}
@Composable private fun Toggle(label: Int, checked: Boolean, busy: Boolean, contained: Boolean = false, changed: (Boolean) -> Unit) {
    val tag = LocalContext.current.resources.getResourceEntryName(label)
    val row: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().testTag("native-product-switch:$tag").heightIn(min = 64.dp)
            .toggleable(value = checked, enabled = !busy, role = Role.Switch, onValueChange = changed)
            .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge)
            Switch(checked, onCheckedChange = null, enabled = !busy)
        }
    }
    if (contained) row() else ProductGroup { row() }
}
@Composable private fun Choices(selected: String, choices: List<Pair<String, Int>>, busy: Boolean, changed: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((value, label) in choices) {
            val chosen = selected == value
            Surface(shape = MaterialTheme.shapes.large,
                color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                contentColor = if (chosen) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(selected = chosen, enabled = !busy, role = Role.RadioButton, onClick = { changed(value) })
                    .testTag("native-product-choice:$value").padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    RadioButton(chosen, null, enabled = !busy)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PetModeSheet(selected: String, busy: Boolean, dismiss: () -> Unit, changed: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Choices(selected, listOf("always" to R.string.nps_always, "adaptive" to R.string.nps_adaptive, "off" to R.string.nps_off), busy, changed)
        }
    }
}
