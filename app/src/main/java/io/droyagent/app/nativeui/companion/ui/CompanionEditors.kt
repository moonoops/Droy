package io.droyagent.app.nativeui.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.companion.data.NativeCompanionSettings
import io.droyagent.app.nativeui.data.NativeModel
import io.droyagent.app.nativeui.ui.DroySwitch as Switch
import io.droyagent.app.nativeui.ui.DroyIcon
import org.json.JSONObject
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompanionEditorScaffold(title: String, dirty: Boolean, enabled: Boolean, onSave: () -> Unit, onClose: () -> Unit,
    tag: String, errorContent: @Composable () -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    var discard by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    fun back() { if (imeVisible) keyboard?.hide() else if (dirty) discard = true else onClose() }
    BackHandler { back() }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        Column {
            CompanionTopBar(title, ::back) {
                TextButton(onClick = onSave, enabled = enabled && dirty, modifier = Modifier.testTag(tag)) { Text(stringResource(R.string.native_companion_save)) }
            }
            errorContent()
        }
    }, content = content)
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.native_companion_discard_title)) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.native_companion_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.native_companion_keep_editing)) } })
}

/** The fixed search field stays visible while long provider groups scroll. */
@Composable
fun CompanionModelSelection(models: List<NativeModel>, selected: NativeModel?, defaultModel: NativeModel?, enabled: Boolean,
    onSelect: (NativeModel?) -> Unit, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    val rows = remember(models, selected, query) {
        (listOfNotNull(selected) + models).distinctBy { it.provider to it.id }
            .filter { query.isBlank() || listOf(it.name, it.id, it.provider, it.providerName).any { label -> label.contains(query, true) } }
            .sortedWith(compareByDescending<NativeModel> { it.provider == selected?.provider && it.id == selected?.id }
                .thenBy { it.providerName }.thenBy { it.name })
    }
    Column(modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("native-companion-model-search"), singleLine = true,
            label = { Text(stringResource(R.string.native_companion_search_models)) }, shape = MaterialTheme.shapes.medium)
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 16.dp)) {
            item("default") { CompanionModelRow(stringResource(R.string.native_companion_default_model), defaultModel?.let { "${it.name} · ${it.providerName}" },
                selected == null, enabled) { onSelect(null) } }
            rows.groupBy { it.provider }.forEach { (provider, group) ->
                item("provider:$provider") { Text(group.first().providerName, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                items(group, key = { "${it.provider}:${it.id}" }) { model ->
                    CompanionModelRow(model.name, model.id.takeIf { it != model.name }, selected?.provider == model.provider && selected?.id == model.id, enabled) { onSelect(model) }
                }
            }
            if (rows.isEmpty()) item("empty") { Text(stringResource(R.string.native_companion_no_models), Modifier.padding(vertical = 16.dp)) }
        }
    }
}

@Composable
private fun CompanionModelRow(name: String, detail: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)) {
        ListItem(headlineContent = { Text(name) }, supportingContent = detail?.let { { Text(it) } },
            trailingContent = { RadioButton(selected, null, enabled = enabled) },
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
    }
}

@Composable
internal fun CompanionPersonalityPage(initial: String, preset: String, enabled: Boolean, onSave: (String) -> Unit, onClose: () -> Unit, errorContent: @Composable () -> Unit = {}) {
    var text by rememberSaveable(initial) { mutableStateOf(initial.ifBlank { preset }) }
    val original = initial.ifBlank { preset }
    val changed = text != original
    CompanionEditorScaffold(stringResource(R.string.native_companion_personality), changed, enabled && text.toByteArray().size <= 16384,
        { onSave(if (text == preset) "" else text) }, onClose, "native-companion-personality-save", errorContent) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().weight(1f).testTag("native-companion-personality-input"),
                enabled = enabled, isError = text.toByteArray().size > 16384, shape = MaterialTheme.shapes.medium)
            if (text.toByteArray().size > 16384) Text(stringResource(R.string.native_companion_creation_personality_limit), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { text = preset }, enabled = enabled && text != preset) { Text(stringResource(R.string.native_companion_preset_personality)) }
        }
    }
}

@Composable
internal fun CompanionInteractionPage(initial: NativeCompanionSettings, enabled: Boolean, onSave: (NativeCompanionSettings) -> Unit,
    onClose: () -> Unit, onBackground: () -> Unit, errorContent: @Composable () -> Unit = {}) {
    val originalQuiet = remember(initial.quietHoursJson) { initial.quietHoursJson?.let(::JSONObject) }
    var proactive by rememberSaveable { mutableStateOf(initial.proactiveEnabled) }
    var memory by rememberSaveable { mutableStateOf(initial.memoryEnabled) }
    var civil by rememberSaveable { mutableStateOf(!initial.allowProfanity) }
    var quiet by rememberSaveable { mutableStateOf(originalQuiet?.optBoolean("enabled", false) ?: false) }
    var start by rememberSaveable { mutableStateOf(originalQuiet?.optString("start", "22:00") ?: "22:00") }
    var end by rememberSaveable { mutableStateOf(originalQuiet?.optString("end", "09:00") ?: "09:00") }
    var editTime by rememberSaveable { mutableStateOf<String?>(null) }
    val dirty = proactive != initial.proactiveEnabled || memory != initial.memoryEnabled || civil == initial.allowProfanity ||
        quiet != (originalQuiet?.optBoolean("enabled", false) ?: false) || start != (originalQuiet?.optString("start", "22:00") ?: "22:00") || end != (originalQuiet?.optString("end", "09:00") ?: "09:00")
    CompanionEditorScaffold(stringResource(R.string.native_companion_interaction), dirty, enabled, {
        val times = originalQuiet?.let { JSONObject(it.toString()) } ?: JSONObject()
        onSave(initial.copy(proactiveEnabled = proactive, memoryEnabled = memory, allowProfanity = !civil,
            quietHoursJson = times.put("enabled", quiet).put("start", start).put("end", end).toString()))
    }, onClose, "native-companion-interaction-save", errorContent) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) { Column {
                CompanionSettingSwitch(stringResource(R.string.native_companion_proactive), proactive, enabled) { proactive = it }
                CompanionSettingSwitch(stringResource(R.string.native_companion_memory), memory, enabled) { memory = it }
                CompanionSettingSwitch(stringResource(R.string.native_companion_civil_language), civil, enabled) { civil = it }
            } }
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) { Column {
                CompanionSettingSwitch(stringResource(R.string.native_companion_quiet_hours), quiet, enabled) { quiet = it }
                if (quiet) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { editTime = "start" }, enabled = enabled, modifier = Modifier.weight(1f).testTag("native-companion-quiet-start")) { Text(start) }
                    Text("–")
                    TextButton(onClick = { editTime = "end" }, enabled = enabled, modifier = Modifier.weight(1f).testTag("native-companion-quiet-end")) { Text(end) }
                }
            } }
            ListItem(headlineContent = { Text(stringResource(R.string.native_companion_background)) }, trailingContent = { DroyIcon(R.drawable.native_companion_chevron_right, null) },
                modifier = Modifier.clickable(enabled = enabled, onClick = onBackground).testTag("native-companion-background"))
        }
    }
    editTime?.let { field -> CompanionTimeDialog(if (field == "start") start else end, field == "start", { editTime = null }) {
        if (field == "start") start = it else end = it; editTime = null
    } }
}

@Composable
internal fun CompanionSettingSwitch(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(title) }, trailingContent = { Switch(checked, null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompanionTimeDialog(value: String, start: Boolean, onClose: () -> Unit, onApply: (String) -> Unit) {
    var keyboard by rememberSaveable { mutableStateOf(false) }
    val parts = value.split(':')
    val picker = rememberTimePickerState(parts[0].toInt(), parts[1].toInt(), is24Hour = true)
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(if (start) R.string.native_companion_start_time else R.string.native_companion_end_time)) },
        text = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (keyboard) TimeInput(picker) else TimePicker(picker)
            TextButton(onClick = { keyboard = !keyboard }) { Text(stringResource(if (keyboard) R.string.native_companion_time_dial else R.string.native_companion_time_keyboard)) }
        } }, confirmButton = { TextButton(onClick = { onApply(String.format(Locale.ROOT, "%02d:%02d", picker.hour, picker.minute)) }) { Text(stringResource(R.string.native_companion_save)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.native_companion_cancel)) } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompanionBackgroundPage(initial: String?, loader: CompanionImageLoader, enabled: Boolean,
    onChoose: (((String) -> Unit) -> Unit)?, onApply: (String) -> Unit, onClose: () -> Unit, incomingSelection: String? = null, onIncomingConsumed: () -> Unit = {}, errorContent: @Composable () -> Unit = {}) {
    val original = remember { initial ?: "{\"type\":\"default\"}" }
    var selection by rememberSaveable { mutableStateOf(initial ?: original) }
    var custom by rememberSaveable { mutableStateOf(initial?.takeIf { JSONObject(it).optString("type") == "upload" }) }
    fun select(next: String) { selection = next; if (JSONObject(next).optString("type") == "upload") custom = next }
    val consumeIncoming by rememberUpdatedState(onIncomingConsumed)
    LaunchedEffect(incomingSelection) { if (incomingSelection != null) { select(incomingSelection); consumeIncoming() } }
    var discard by rememberSaveable { mutableStateOf(false) }
    val value = remember(selection) { JSONObject(selection) }
    val backgroundLabel = stringResource(R.string.native_companion_background)
    fun close() { if (selection != original) discard = true else onClose() }
    BackHandler { close() }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { Column { CompanionTopBar(backgroundLabel, ::close); errorContent() } }, bottomBar = {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { onChoose?.invoke { url -> select(JSONObject().put("type", "upload").put("url", url).put("position", JSONObject().put("x", 50).put("y", 50)).toString()) } },
                    enabled = enabled && onChoose != null, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("native-creation-background-upload")) {
                    Text(stringResource(R.string.native_companion_custom_background))
                }
                Button(onClick = { onApply(selection) }, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("native-background-apply")) {
                    Text(stringResource(R.string.native_companion_creation_apply))
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("choices") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 2) {
                    val choices = listOf("default") + listOf("01", "02", "03", "04", "05") + if (custom != null) listOf("upload") else emptyList()
                    choices.forEach { id ->
                        val selected = when (id) { "default", "upload" -> value.optString("type") == id; else -> value.optString("type") == "preset" && value.optString("id") == id }
                        val url = when (id) { "default" -> null; "upload" -> JSONObject(requireNotNull(custom)).getString("url"); else -> "/droy/companions/assets/wallpaper-$id.webp" }
                        val title = when (id) { "default" -> stringResource(R.string.native_companion_default_background); "upload" -> stringResource(R.string.native_companion_custom_background); else -> stringResource(R.string.native_companion_creation_background_preset, id.toInt()) }
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.weight(1f).selectable(selected, enabled = enabled, role = Role.RadioButton) {
                                selection = when (id) { "default" -> "{\"type\":\"default\"}"; "upload" -> requireNotNull(custom); else -> JSONObject().put("type", "preset").put("id", id).put("url", url).toString() }
                            }) {
                            Column {
                                Box(Modifier.fillMaxWidth().aspectRatio(0.58f)) {
                                    if (url != null) {
                                        val position = if (id == "upload") JSONObject(requireNotNull(custom)).optJSONObject("position") else null
                                        CompanionImage(url, null, loader, Modifier.fillMaxSize(), alignment = BiasAlignment(
                                            ((position?.optDouble("x", 50.0) ?: 50.0) / 50.0 - 1.0).toFloat(),
                                            ((position?.optDouble("y", 50.0) ?: 50.0) / 50.0 - 1.0).toFloat())) { }
                                    }
                                    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.Center) {
                                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) { Text(stringResource(R.string.native_companion_background_sample_in), Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) }
                                        Spacer(Modifier.height(8.dp))
                                        Surface(modifier = Modifier.align(Alignment.End), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) { Text(stringResource(R.string.native_companion_background_sample_out), Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) }
                                    }
                                    if (selected) Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                                        DroyIcon(R.drawable.native_ui_check, stringResource(R.string.native_companion_background_selected), Modifier.padding(4.dp))
                                    }
                                }
                                Text(title, Modifier.padding(horizontal = 12.dp, vertical = 12.dp), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
            if (value.optString("type") == "upload") item("position") {
                for ((axis, label) in listOf("x" to R.string.native_companion_creation_horizontal, "y" to R.string.native_companion_creation_vertical)) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                    Slider(value.optJSONObject("position")?.optDouble(axis, 50.0)?.toFloat() ?: 50f, { number ->
                        val next = JSONObject(selection); val position = next.optJSONObject("position") ?: JSONObject().put("x", 50).put("y", 50)
                        next.put("position", position.put(axis, number.toDouble())); select(next.toString())
                    }, enabled = enabled, valueRange = 0f..100f)
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.native_companion_discard_title)) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.native_companion_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.native_companion_keep_editing)) } })
}
