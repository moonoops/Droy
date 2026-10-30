package io.droyagent.app.nativeui.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.companion.data.NativeCompanionSettings
import io.droyagent.app.nativeui.data.NativeModel
import io.droyagent.app.nativeui.ui.DroyIcon
import io.droyagent.app.nativeui.ui.DroySettingsLink
import io.droyagent.app.nativeui.ui.DroySettingsLinkGroup
import io.droyagent.app.nativeui.ui.DroySettingsSectionTitle
import io.droyagent.app.nativeui.ui.DroySettingsToggle
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
    onSelect: (NativeModel?) -> Unit, modifier: Modifier = Modifier, showTitle: Boolean = false) {
    var query by rememberSaveable { mutableStateOf("") }
    val rows = remember(models, selected, query) {
        (listOfNotNull(selected) + models).distinctBy { it.provider to it.id }
            .filter { query.isBlank() || listOf(it.name, it.id, it.provider, it.providerName).any { label -> label.contains(query, true) } }
            .sortedWith(compareByDescending<NativeModel> { it.provider == selected?.provider && it.id == selected?.id }
                .thenBy { it.providerName }.thenBy { it.name })
    }
    Column(modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showTitle) Text(stringResource(R.string.native_companion_choose_model), style = MaterialTheme.typography.headlineSmall)
        CompanionFilledField(query, { query = it }, Modifier.fillMaxWidth().testTag("native-companion-model-search"), singleLine = true,
            label = { Text(stringResource(R.string.native_companion_search_models)) },
            leadingIcon = { DroyIcon(R.drawable.native_settings_search, null) })
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup(), contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item("default") { CompanionModelRow(stringResource(R.string.native_companion_default_model), defaultModel?.let { "${it.name} · ${it.providerName}" },
                selected == null, enabled, 0, 1, "native-companion-model-default") { onSelect(null) } }
            rows.groupBy { it.provider }.forEach { (provider, group) ->
                item("provider:$provider") { Text(group.first().providerName, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp)) }
                items(group.size, key = { "${group[it].provider}:${group[it].id}" }) { index ->
                    val model = group[index]
                    CompanionModelRow(model.name, model.id.takeIf { it != model.name }, selected?.provider == model.provider && selected?.id == model.id,
                        enabled, index, group.size, "native-companion-model-option:${model.provider}:${model.id}") { onSelect(model) }
                }
            }
            if (rows.isEmpty()) item("empty") { Text(stringResource(R.string.native_companion_no_models), Modifier.padding(vertical = 16.dp)) }
        }
    }
}

@Composable
private fun CompanionModelRow(name: String, detail: String?, selected: Boolean, enabled: Boolean,
    index: Int, count: Int, tag: String, onClick: () -> Unit) {
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    SegmentedListItem(selected = selected, onClick = onClick, enabled = enabled,
        shapes = shapes,
        colors = ListItemDefaults.segmentedColors(),
        leadingContent = { DroyIcon(R.drawable.native_settings_hub, null) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = { RadioButton(selected, null, enabled = enabled) },
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = if (detail == null) 56.dp else 72.dp).testTag(tag),
    ) { Text(name, style = MaterialTheme.typography.bodyLarge) }
}

@Composable
internal fun CompanionPersonalityPage(initial: String, preset: String, enabled: Boolean, onSave: (String, String, String) -> Unit, onClose: () -> Unit,
    errorContent: @Composable () -> Unit = {}, initialBehavior: String = "", initialReply: String = "",
    presetBehavior: String = "", presetReply: String = "", customBehavior: String = "", customReply: String = "") {
    var text by rememberSaveable(initial) { mutableStateOf(initial.ifBlank { preset }) }
    var behavior by rememberSaveable(initialBehavior) { mutableStateOf(initialBehavior) }
    var reply by rememberSaveable(initialReply) { mutableStateOf(initialReply) }
    val original = initial.ifBlank { preset }
    val custom = text.isNotBlank() && text != preset
    val behaviorDefault = if (custom) customBehavior else presetBehavior
    val replyDefault = if (custom) customReply else presetReply
    val changed = text != original || behavior != initialBehavior || reply != initialReply
    val tooLong = listOf(text, behavior, reply).any { it.toByteArray().size > 16384 }
    CompanionEditorScaffold(stringResource(R.string.native_companion_personality), changed, enabled && !tooLong,
        { onSave(if (text == preset) "" else text, if (behavior == behaviorDefault) "" else behavior, if (reply == replyDefault) "" else reply) },
        onClose, "native-companion-personality-save", errorContent) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.native_companion_persona_help), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            CompanionPersonaField(stringResource(R.string.native_companion_persona_identity), text, { text = it },
                enabled, "native-companion-personality-input", minLines = 4)
            CompanionPersonaField(stringResource(R.string.native_companion_persona_behavior), behavior.ifBlank { behaviorDefault }, { behavior = it },
                enabled, "native-companion-behavior-input", minLines = 3)
            CompanionPersonaField(stringResource(R.string.native_companion_persona_reply), reply.ifBlank { replyDefault }, { reply = it },
                enabled, "native-companion-reply-style-input", minLines = 3)
            if (tooLong) Text(stringResource(R.string.native_companion_creation_personality_limit), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { text = preset; behavior = ""; reply = "" }, enabled = enabled && (text != preset || behavior.isNotBlank() || reply.isNotBlank()),
                modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.native_companion_preset_personality))
            }
        }
    }
}

@Composable
private fun CompanionPersonaField(label: String, value: String, onValueChange: (String) -> Unit,
    enabled: Boolean, tag: String, minLines: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DroySettingsSectionTitle(label)
        CompanionFilledField(value, onValueChange,
            Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = label },
            enabled = enabled, minLines = minLines, isError = value.toByteArray().size > 16384)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DroySettingsSectionTitle(stringResource(R.string.native_companion_communication))
                DroySettingsToggle(stringResource(R.string.native_companion_proactive), R.drawable.native_settings_notifications,
                    proactive, enabled, 0, 3, Modifier.testTag("native-companion-proactive")) { proactive = it }
                DroySettingsToggle(stringResource(R.string.native_companion_memory), R.drawable.native_settings_neurology,
                    memory, enabled, 1, 3, Modifier.testTag("native-companion-memory")) { memory = it }
                DroySettingsToggle(stringResource(R.string.native_companion_civil_language), R.drawable.native_settings_person,
                    civil, enabled, 2, 3, Modifier.testTag("native-companion-civil")) { civil = it }
            }
            Column(Modifier.animateContentSize(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DroySettingsToggle(stringResource(R.string.native_companion_quiet_hours), R.drawable.native_settings_notifications,
                    quiet, enabled, 0, 1, Modifier.testTag("native-companion-quiet-enabled")) { quiet = it }
                if (quiet) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    val leadingShapes = ButtonGroupDefaults.connectedLeadingButtonShapes()
                    val trailingShapes = ButtonGroupDefaults.connectedTrailingButtonShapes()
                    FilledTonalButton(onClick = { editTime = "start" }, enabled = enabled,
                        shapes = ButtonDefaults.shapes(shape = leadingShapes.shape, pressedShape = leadingShapes.pressedShape),
                        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 88.dp).testTag("native-companion-quiet-start"),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.native_companion_start_time), style = MaterialTheme.typography.labelMedium)
                            Text(start, style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                    FilledTonalButton(onClick = { editTime = "end" }, enabled = enabled,
                        shapes = ButtonDefaults.shapes(shape = trailingShapes.shape, pressedShape = trailingShapes.pressedShape),
                        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 88.dp).testTag("native-companion-quiet-end"),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.native_companion_end_time), style = MaterialTheme.typography.labelMedium)
                            Text(end, style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }
            }
            DroySettingsLinkGroup(enabled = enabled, items = listOf(
                DroySettingsLink(stringResource(R.string.native_companion_background), R.drawable.native_companion_image,
                    "native-companion-background", onClick = onBackground)))
        }
    }
    editTime?.let { field -> CompanionTimeDialog(if (field == "start") start else end, field == "start", { editTime = null }) {
        if (field == "start") start = it else end = it; editTime = null
    } }
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
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
                Button(onClick = { onApply(selection) }, enabled = enabled, shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("native-background-apply")) {
                    Text(stringResource(R.string.native_companion_creation_apply))
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("preview") {
                val previewUrl = when (value.optString("type")) {
                    "preset" -> value.optString("id").takeIf { it in setOf("01", "02", "03", "04", "05") }
                        ?.let { "/droy/companions/assets/wallpaper-$it.webp" }
                    "upload" -> value.optString("url").takeIf { it.isNotBlank() }
                    else -> null
                }
                val position = value.optJSONObject("position")
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.4f).testTag("native-background-preview")) {
                    Box {
                        Crossfade(targetState = previewUrl, animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                            label = "backgroundPreview", modifier = Modifier.fillMaxSize()) { url ->
                            if (url != null) CompanionImage(url, null, loader, Modifier.fillMaxSize(), alignment = BiasAlignment(
                                ((position?.optDouble("x", 50.0) ?: 50.0) / 50.0 - 1.0).toFloat(),
                                ((position?.optDouble("y", 50.0) ?: 50.0) / 50.0 - 1.0).toFloat())) { }
                        }
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
                            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Text(stringResource(R.string.native_companion_background_sample_in), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(Modifier.height(16.dp))
                            Surface(modifier = Modifier.align(Alignment.End), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                                Text(stringResource(R.string.native_companion_background_sample_out), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            item("choices-heading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.native_companion_background_options), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilledTonalButton(onClick = { onChoose?.invoke { url -> select(JSONObject().put("type", "upload").put("url", url).put("position", JSONObject().put("x", 50).put("y", 50)).toString()) } },
                        enabled = enabled && onChoose != null, shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("native-creation-background-upload")) {
                        DroyIcon(R.drawable.native_companion_image, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.native_companion_custom_background))
                    }
                }
            }
            item("choices") {
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val choices = listOf("default", "01", "02", "03", "04", "05") + if (custom != null) listOf("upload") else emptyList()
                    choices.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { id ->
                                val selected = when (id) { "default", "upload" -> value.optString("type") == id; else -> value.optString("type") == "preset" && value.optString("id") == id }
                                val url = when (id) { "default" -> null; "upload" -> JSONObject(requireNotNull(custom)).getString("url"); else -> "/droy/companions/assets/wallpaper-$id.webp" }
                                val title = when (id) { "default" -> stringResource(R.string.native_companion_default_background); "upload" -> stringResource(R.string.native_companion_custom_background); else -> stringResource(R.string.native_companion_creation_background_preset, id.toInt()) }
                                val interactions = remember(id) { MutableInteractionSource() }
                                val pressed by interactions.collectIsPressedAsState()
                                val corner by animateDpAsState(if (pressed) 24.dp else 16.dp,
                                    animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(), label = "backgroundChoiceShape")
                                Column(Modifier.weight(1f).testTag("native-background-option-$id")
                                    .selectable(selected, interactionSource = interactions, indication = null,
                                        enabled = enabled, role = Role.RadioButton) {
                                        selection = when (id) { "default" -> "{\"type\":\"default\"}"; "upload" -> requireNotNull(custom); else -> JSONObject().put("type", "preset").put("id", id).put("url", url).toString() }
                                    }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Surface(shape = RoundedCornerShape(corner), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border = BorderStroke(if (selected) 2.dp else 1.dp,
                                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier.fillMaxWidth().aspectRatio(1.35f)) {
                                        Box(Modifier.fillMaxSize().indication(interactions, LocalIndication.current)) {
                                            if (url != null) {
                                                val position = if (id == "upload") JSONObject(requireNotNull(custom)).optJSONObject("position") else null
                                                CompanionImage(url, null, loader, Modifier.fillMaxSize(), alignment = BiasAlignment(
                                                    ((position?.optDouble("x", 50.0) ?: 50.0) / 50.0 - 1.0).toFloat(),
                                                    ((position?.optDouble("y", 50.0) ?: 50.0) / 50.0 - 1.0).toFloat())) { }
                                            }
                                            if (selected) Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primary) {
                                                DroyIcon(R.drawable.native_ui_check, null, Modifier.padding(3.dp).size(18.dp))
                                            }
                                        }
                                    }
                                    Text(title, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            if (value.optString("type") == "upload") item("position") {
                for ((axis, label) in listOf("x" to R.string.native_companion_creation_horizontal, "y" to R.string.native_companion_creation_vertical)) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                    val positionValue = value.optJSONObject("position")?.optDouble(axis, 50.0)?.toFloat() ?: 50f
                    val positionSlider = remember(axis) { SliderState(positionValue, trackRange = 0f..100f) }
                    positionSlider.value = positionValue
                    Slider(state = positionSlider, onValueChange = { number ->
                        val next = JSONObject(selection); val position = next.optJSONObject("position") ?: JSONObject().put("x", 50).put("y", 50)
                        next.put("position", position.put(axis, number.toDouble())); select(next.toString())
                    }, enabled = enabled)
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.native_companion_discard_title)) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.native_companion_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.native_companion_keep_editing)) } })
}
