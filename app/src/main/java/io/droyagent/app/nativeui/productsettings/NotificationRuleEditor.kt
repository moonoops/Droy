package io.droyagent.app.nativeui.productsettings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.droyagent.app.NativeProductSettingsHost
import io.droyagent.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class ConditionDraft(val any: String = "", val all: String = "", val action: String = "retain", val source: JSONObject? = null)

/** Keep the original identity and revision for the whole edit; a failed save never replaces the draft. */
@Composable
internal fun NotificationRuleEditor(
    original: JSONObject?,
    expectedRevision: Long,
    save: suspend (JSONObject) -> Unit,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    var packageName by remember { mutableStateOf(original?.optString("packageName") ?: "*") }
    var instruction by remember { mutableStateOf(original?.optString("instruction") ?: "") }
    var action by remember { mutableStateOf(original?.optString("defaultAction") ?: "analyze") }
    var repeat by remember { mutableStateOf(original?.optInt("repeatMinutes")?.toString() ?: "0") }
    var clear by remember { mutableStateOf(original?.optInt("clearAfterHours")?.toString() ?: "0") }
    var conditions by remember { mutableStateOf(original?.optJSONArray("entries").rows().map {
        fun terms(key: String): String { val values = it.optJSONArray(key) ?: JSONArray(); return (0 until values.length()).joinToString("\n") { i -> values.getString(i) } }
        ConditionDraft(terms("containsAny"), terms("containsAll"), it.getString("action"), it)
    }) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        modifier = Modifier.testTag("native-product-rule-editor"),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (original == null) R.string.nps_add_rule else R.string.nps_edit_rule)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(modifier = Modifier.testTag("native-product-rule-package"), value = packageName, onValueChange = { packageName = it }, label = { Text(stringResource(R.string.nps_rule_package)) }, enabled = !busy)
                OutlinedTextField(modifier = Modifier.testTag("native-product-rule-instruction"), value = instruction, onValueChange = { instruction = it }, label = { Text(stringResource(R.string.nps_rule_instruction)) }, minLines = 3, enabled = !busy)
                Text(stringResource(R.string.nps_rule_default))
                RuleAction(action, true, busy, "default") { action = it }
                OutlinedTextField(modifier = Modifier.testTag("native-product-rule-repeat"), value = repeat, onValueChange = { repeat = it }, label = { Text(stringResource(R.string.nps_rule_repeat)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !busy)
                OutlinedTextField(modifier = Modifier.testTag("native-product-rule-clear"), value = clear, onValueChange = { clear = it }, label = { Text(stringResource(R.string.nps_rule_clear)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !busy)
                Text(stringResource(R.string.nps_rule_conditions))
                Text(stringResource(R.string.nps_rule_terms_hint), style = MaterialTheme.typography.bodySmall)
                conditions.forEachIndexed { index, condition ->
                    OutlinedCard {
                        Column(Modifier.padding(12.dp)) {
                            OutlinedTextField(value = condition.any, modifier = Modifier.testTag("native-product-rule-any:$index"), onValueChange = { value -> conditions = conditions.mapIndexed { i, row -> if (i == index) row.copy(any = value) else row } }, label = { Text(stringResource(R.string.nps_rule_any)) }, enabled = !busy)
                            OutlinedTextField(value = condition.all, modifier = Modifier.testTag("native-product-rule-all:$index"), onValueChange = { value -> conditions = conditions.mapIndexed { i, row -> if (i == index) row.copy(all = value) else row } }, label = { Text(stringResource(R.string.nps_rule_all)) }, enabled = !busy)
                            RuleAction(condition.action, false, busy, "condition:$index") { value -> conditions = conditions.mapIndexed { i, row -> if (i == index) row.copy(action = value) else row } }
                            TextButton(onClick = { conditions = conditions.filterIndexed { i, _ -> i != index } }, enabled = !busy) { Text(stringResource(R.string.nps_delete)) }
                        }
                    }
                }
                TextButton(onClick = { conditions = conditions + ConditionDraft() }, enabled = !busy && conditions.size < 32, modifier = Modifier.testTag("native-product-rule-add-condition")) { Text(stringResource(R.string.nps_rule_add_condition)) }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(modifier = Modifier.testTag("native-product-rule-save"), enabled = !busy, onClick = {
            busy = true; error = null
            scope.launch {
                try {
                    fun terms(row: ConditionDraft, value: String, key: String): JSONArray {
                        val originalTerms = row.source?.optJSONArray(key)
                        if (originalTerms != null && (0 until originalTerms.length()).joinToString("\n") { originalTerms.getString(it) } == value)
                            return JSONArray(originalTerms.toString())
                        return JSONArray(value.lines().map { it.trim() }.filter { it.isNotEmpty() })
                    }
                    val rule = JSONObject().put("packageName", packageName.trim()).put("instruction", instruction)
                        .put("defaultAction", action).put("repeatMinutes", repeat.toInt()).put("clearAfterHours", clear.toInt())
                        .put("entries", JSONArray(conditions.map { JSONObject().put("action", it.action).put("containsAny", terms(it, it.any, "containsAny")).put("containsAll", terms(it, it.all, "containsAll")) }))
                    original?.getString("id")?.let { rule.put("id", it) }
                    save(JSONObject().put("operation", "upsertRule").put("rule", rule).put("expectedRevision", expectedRevision))
                    onSaved()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: NativeProductSettingsHost.RuleConflictException) { error = R.string.nps_rule_conflict }
                catch (_: Exception) { error = R.string.nps_rule_error }
                finally { busy = false }
            }
        }) { Text(stringResource(R.string.nps_save)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.nps_cancel)) } },
    )
}

@Composable private fun RuleAction(value: String, analyze: Boolean, busy: Boolean, tag: String, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val choices = listOf("analyze" to R.string.nps_rule_analyze, "retain" to R.string.nps_rule_retain,
        "clear" to R.string.nps_rule_remove, "collect" to R.string.nps_rule_collect, "notify" to R.string.nps_rule_notify).filter { analyze || it.first != "analyze" }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = !busy, modifier = Modifier.testTag("native-product-rule-action:$tag")) { Text(stringResource(choices.first { it.first == value }.second)) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (key, label) -> DropdownMenuItem(modifier = Modifier.testTag("native-product-rule-action:$tag:$key"), text = { Text(stringResource(label)) }, onClick = { change(key); expanded = false }) }
        }
    }
}
