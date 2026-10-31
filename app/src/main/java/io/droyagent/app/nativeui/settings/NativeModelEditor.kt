package io.droyagent.app.nativeui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIconAction
import org.json.JSONObject

/** Changes stay in the provider draft until that provider is saved. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativeModelEditor(initial: NativeSettingsModelEntry?, namespace: String, builtin: Boolean,
    error: String? = null, onSave: (NativeSettingsModelEntry) -> Unit, onBack: () -> Unit) {
    val original = initial ?: NativeSettingsModelEntry("")
    var id by rememberSaveable { mutableStateOf(original.id) }
    var name by rememberSaveable { mutableStateOf(original.name) }
    var context by rememberSaveable { mutableStateOf(original.contextWindow?.toString().orEmpty()) }
    var output by rememberSaveable { mutableStateOf(original.maxTokens?.toString().orEmpty()) }
    var modalities by rememberSaveable { mutableStateOf(original.inputModalities) }
    var reasoningMode by rememberSaveable { mutableStateOf(if (original.reasoningJson == null) "inherit" else if (original.reasoningJson == "false") "disabled" else "custom") }
    var reasoning by rememberSaveable { mutableStateOf(original.reasoningJson?.takeUnless { it == "false" } ?: "{}") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val originalExtra = remember(initial) { original.extraJson ?: JSONObject().apply {
        val source = original.savedJson?.let(::JSONObject) ?: JSONObject()
        for (key in listOf("compat", "description", "imagePixelBudget", "imageMaxBytes", "systemPromptUpdate", "toolUpdate")) if (source.has(key)) put(key, source.opt(key))
    }.toString() }
    var extra by rememberSaveable { mutableStateOf(originalExtra) }
    var extraValid by remember { mutableStateOf(true) }
    var confirmBack by remember { mutableStateOf(false) }
    val dirty = extra != originalExtra || id != original.id || name != original.name || context != original.contextWindow?.toString().orEmpty() ||
        output != original.maxTokens?.toString().orEmpty() || modalities != original.inputModalities ||
        (when (reasoningMode) { "inherit" -> null; "disabled" -> "false"; else -> reasoning }) != original.reasoningJson
    fun leave() { if (dirty) confirmBack = true else onBack() }
    val levels = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
    val entries = JSONObject(reasoning)
    val valid = extraValid && id.isNotBlank() && id.none { it.isWhitespace() || it.isISOControl() } &&
        (context.isBlank() || context.toLongOrNull()?.let { it > 0 } == true) &&
        (output.isBlank() || output.toLongOrNull()?.let { it > 0 } == true) &&
        (reasoningMode != "custom" || entries.length() > 0 && entries.keys().asSequence().all { it == "off" || entries.optString(it).isNotBlank() })
    BackHandler(onBack = ::leave)
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            DroyIconAction(R.drawable.native_settings_arrow_back, stringResource(R.string.native_settings_back), ::leave,
                modifier = Modifier.testTag("native-settings-model-back"))
            Text(stringResource(if (initial == null) R.string.native_settings_add_model_title else R.string.native_settings_edit_model),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onSave(original.copy(id = id.trim(), name = name.trim().ifBlank { id.trim() },
                contextWindow = context.toLongOrNull(), maxTokens = output.toLongOrNull(), inputModalities = modalities,
                extraJson = extra, reasoningJson = when (reasoningMode) { "inherit" -> null; "disabled" -> "false"; else -> reasoning })) }, enabled = valid,
                modifier = Modifier.testTag("native-settings-save-model")) { Text(stringResource(R.string.native_settings_save)) }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (error != null) Text(stringResource(if (error == "settings_duplicate_model") R.string.native_settings_error_duplicate_model else R.string.native_settings_error_models), color = MaterialTheme.colorScheme.error)
            OutlinedTextField(id, { id = it }, enabled = !builtin, label = { Text(stringResource(R.string.native_settings_model_id)) },
                singleLine = true, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().testTag("native-settings-manual-model-id"))
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.native_settings_display_name)) },
                singleLine = true, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { advanced = !advanced }) { Text(stringResource(R.string.native_settings_model_advanced)) }
            if (advanced) {
                ModelNumberField(context, { context = it }, R.string.native_settings_context_window)
                ModelNumberField(output, { output = it }, R.string.native_settings_max_output)
                Text(stringResource(R.string.native_settings_input_types), style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.native_settings_inherit), Modifier.weight(1f))
                    Switch(modalities == null, { modalities = if (it) null else listOf("text") })
                }
                if (modalities != null) for ((value, label) in listOf("text" to R.string.native_settings_modality_text, "image" to R.string.native_settings_modality_image)) {
                    Row(Modifier.fillMaxWidth().toggleable(value in modalities.orEmpty(), role = Role.Checkbox) { checked ->
                        modalities = if (checked) modalities.orEmpty() + value else modalities.orEmpty() - value
                    }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(value in modalities.orEmpty(), null); Text(stringResource(label))
                    }
                }
                if (namespace == "llm-pi-ai") {
                    Text(stringResource(R.string.native_settings_reasoning), style = MaterialTheme.typography.titleMedium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("inherit" to R.string.native_settings_inherit, "disabled" to R.string.native_settings_disabled, "custom" to R.string.native_settings_custom).forEachIndexed { index, (value, label) ->
                            SegmentedButton(reasoningMode == value, { reasoningMode = value }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(stringResource(label)) }
                        }
                    }
                    if (reasoningMode == "custom") {
                        for (level in levels.filter { entries.has(it) }) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(entries.optString(level).takeUnless { entries.isNull(level) }.orEmpty(), { value ->
                                    reasoning = JSONObject(reasoning).put(level, if (level == "off" && value.isBlank()) JSONObject.NULL else value).toString()
                                }, label = { Text(reasoningLevelName(level)) }, placeholder = { Text(stringResource(if (level == "off") R.string.native_settings_omit_parameter else R.string.native_settings_api_value)) },
                                    singleLine = true, shape = MaterialTheme.shapes.large, modifier = Modifier.weight(1f))
                                DroyIconAction(R.drawable.native_ui_close, stringResource(R.string.native_settings_remove_effort), {
                                    reasoning = JSONObject(reasoning).apply { remove(level) }.toString()
                                })
                            }
                        }
                        var chooseLevel by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { chooseLevel = true }, enabled = levels.any { !entries.has(it) }) { Text(stringResource(R.string.native_settings_add_effort)) }
                            DropdownMenu(chooseLevel, { chooseLevel = false }) {
                                levels.filterNot { entries.has(it) }.forEach { level -> DropdownMenuItem(text = { Text(reasoningLevelName(level)) }, onClick = {
                                    reasoning = JSONObject(reasoning).put(level, if (level == "off") JSONObject.NULL else level).toString(); chooseLevel = false
                                }) }
                            }
                        }
                    }
                }
                NativeModelExtraFields(namespace, extra, { extra = it }, { extraValid = it })
            }
        }
    }
    if (confirmBack) AlertDialog(onDismissRequest = { confirmBack = false }, title = { Text(stringResource(R.string.native_settings_unsaved)) },
        confirmButton = { TextButton(onClick = onBack) { Text(stringResource(R.string.native_settings_discard)) } },
        dismissButton = { TextButton(onClick = { confirmBack = false }) { Text(stringResource(R.string.native_settings_continue_editing)) } })
}

@Composable private fun ModelNumberField(value: String, changed: (String) -> Unit, label: Int) {
    OutlinedTextField(value, changed, label = { Text(stringResource(label)) }, placeholder = { Text(stringResource(R.string.native_settings_inherit)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth())
}

@Composable internal fun reasoningLevelName(value: String): String = stringResource(when (value) {
    "off" -> R.string.native_settings_effort_off; "minimal" -> R.string.native_settings_effort_minimal
    "low" -> R.string.native_settings_effort_low; "medium" -> R.string.native_settings_effort_medium
    "high" -> R.string.native_settings_effort_high; "xhigh" -> R.string.native_settings_effort_xhigh
    else -> R.string.native_settings_effort_max
})
