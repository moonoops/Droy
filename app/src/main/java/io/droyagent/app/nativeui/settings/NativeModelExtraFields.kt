package io.droyagent.app.nativeui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import org.json.JSONObject

/** Adapter wire names remain literal; the fields are opt-in overrides, never defaults. */
@Composable
internal fun NativeModelExtraFields(namespace: String, json: String, onChanged: (String) -> Unit, onValid: (Boolean) -> Unit) {
    val value = JSONObject(json)
    if (namespace == "llm-deepseek") {
        ExtraText(value, "description", R.string.native_settings_description, onChanged)
        ExtraText(value, "imagePixelBudget", R.string.native_settings_image_pixels, onChanged, number = true, allowLow = true)
        ExtraText(value, "imageMaxBytes", R.string.native_settings_image_bytes, onChanged, number = true)
        ExtraChoice(value, "systemPromptUpdate", listOf("in-history"), onChanged)
        ExtraChoice(value, "toolUpdate", listOf("addition-only", "in-history"), onChanged)
    } else {
        var expanded by rememberSaveable { mutableStateOf(false) }
        var invalid by remember { mutableStateOf(emptySet<String>()) }
        TextButton(onClick = { if (invalid.isEmpty()) expanded = !expanded }) { Text(stringResource(R.string.native_settings_compatibility)) }
        if (expanded) {
            val compat = value.optJSONObject("compat") ?: JSONObject()
            val booleans = listOf("supportsStore", "supportsDeveloperRole", "supportsReasoningEffort", "supportsUsageInStreaming",
                "supportsFinishReason", "requiresToolResultName", "requiresAssistantAfterToolResult", "requiresThinkingAsText",
                "requiresReasoningContentOnAssistantMessages", "supportsThinkingTokenBudget", "supportsMaxOutputTokens",
                "supportsStrictMode", "supportsLongCacheRetention", "supportsEagerToolInputStreaming", "supportsCacheControlOnTools",
                "supportsTemperature", "forceAdaptiveThinking", "allowEmptySignature", "supportsStrictTools")
            var addField by remember { mutableStateOf(false) }
            fun change(key: String, next: Any?) {
                val updated = JSONObject(compat.toString())
                if (next == null) updated.remove(key) else updated.put(key, next)
                onChanged(JSONObject(value.toString()).put("compat", if (updated.length() == 0) JSONObject.NULL else updated).toString())
            }
            for (key in booleans.filter { compat.has(it) }) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(key, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(compat.optBoolean(key), { change(key, it) })
                TextButton(onClick = { change(key, null) }) { Text(stringResource(R.string.native_settings_remove)) }
            }
            Box {
                TextButton(onClick = { addField = true }) { Text(stringResource(R.string.native_settings_add_parameter)) }
                DropdownMenu(addField, { addField = false }) {
                    booleans.filterNot { compat.has(it) }.forEach { key -> DropdownMenuItem(text = { Text(key) }, onClick = { change(key, true); addField = false }) }
                }
            }
            for ((key, options) in listOf(
                "maxTokensField" to listOf("max_tokens", "max_completion_tokens"),
                "thinkingFormat" to listOf("openai", "deepseek", "openrouter", "together", "baseten", "zai", "qwen", "chat-template", "qwen-chat-template", "string-thinking", "ant-ling"),
                "thinkingTokenBudgetField" to listOf("thinking_token_budget", "thinking_budget", "thinking_budget_tokens"),
                "cacheControlFormat" to listOf("anthropic")
            )) ExtraChoice(compat, key, options, { next ->
                val updated = JSONObject(next).apply { keys().asSequence().toList().filter { isNull(it) }.forEach { remove(it) } }
                onChanged(JSONObject(value.toString()).put("compat", if (updated.length() == 0) JSONObject.NULL else updated).toString())
            })
            val priority = compat.opt("vllmPriority")?.takeUnless { it === JSONObject.NULL }?.toString().orEmpty()
            OutlinedTextField(priority, { text -> if (text.isEmpty()) change("vllmPriority", null) else text.toLongOrNull()?.let { change("vllmPriority", it) } },
                label = { Text("vllmPriority") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth())
            // These upstream fields deliberately accept structured template expressions.
            // Keep the JSON surface confined to these two expression dictionaries.
            for (key in listOf("chatTemplateKwargs", "chatTemplateArgs")) {
                var text by rememberSaveable(key) { mutableStateOf(compat.optJSONObject(key)?.toString().orEmpty()) }
                OutlinedTextField(text, { next ->
                    text = next
                    try {
                        val parsed = next.takeIf { it.isNotBlank() }?.let(::JSONObject)
                        change(key, parsed); invalid = invalid - key
                    } catch (_: org.json.JSONException) { invalid = invalid + key }
                    onValid(invalid.isEmpty())
                }, label = { Text(key) }, isError = key in invalid, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable private fun ExtraText(value: JSONObject, key: String, label: Int, changed: (String) -> Unit, number: Boolean = false, allowLow: Boolean = false) {
    val text = value.opt(key)?.takeUnless { it === JSONObject.NULL }?.toString().orEmpty()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    OutlinedTextField(text, { next ->
        val parsed: Any = if (next.isBlank()) JSONObject.NULL else if (number && !(allowLow && next == "low")) next.toLongOrNull()?.takeIf { it > 0 } ?: return@OutlinedTextField else next
        changed(JSONObject(value.toString()).put(key, parsed).toString())
    }, label = { Text(stringResource(label)) }, placeholder = { Text(stringResource(R.string.native_settings_inherit)) },
        singleLine = true, shape = MaterialTheme.shapes.large,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text), modifier = Modifier.weight(1f))
    if (allowLow) TextButton(onClick = { changed(JSONObject(value.toString()).put(key, "low").toString()) }) { Text("low") }
    }
}

@Composable private fun ExtraChoice(value: JSONObject, key: String, options: List<String>, changed: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(key, Modifier.weight(1f)); Text(value.optString(key).takeUnless { it == "null" || it.isBlank() } ?: stringResource(R.string.native_settings_inherit))
        }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.native_settings_inherit)) }, onClick = { changed(JSONObject(value.toString()).put(key, JSONObject.NULL).toString()); expanded = false })
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { changed(JSONObject(value.toString()).put(key, option).toString()); expanded = false }) }
        }
    }
}
