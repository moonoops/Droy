package io.droyagent.app.nativeui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIconAction
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeWebSearchSettingsScreen(state: NativeSettingsState, model: NativeSettingsViewModel, onBack: () -> Unit) {
    var browserNetwork by rememberSaveable { mutableStateOf(false) }
    if (browserNetwork) NativeBrowserNetworkSettingsScreen(onBack = { browserNetwork = false })
    else NativeWebSearchSettingsContent(state, model, onBack, configureFetch = false, onBrowserNetwork = { browserNetwork = true })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativeWebSearchSettingsContent(state: NativeSettingsState, model: NativeSettingsViewModel, onBack: () -> Unit, configureFetch: Boolean,
    onBrowserNetwork: (() -> Unit)? = null) {
    var fetchPage by remember { mutableStateOf(false) }
    if (fetchPage) {
        NativeWebSearchSettingsContent(state, model, onBack = { fetchPage = false }, configureFetch = true)
        return
    }
    val routingSnapshot = state.featureSettings["web"]
    val routing = routingSnapshot?.let(::JSONObject)?.getJSONObject("value")
    val initialDirect = routing?.optString("searchProvider") == "deepseek-search-api"
    var direct by remember(routingSnapshot) { mutableStateOf(initialDirect || configureFetch) }
    val messagesSnapshot = state.featureSettings["web-search-deepseek"]
    val apiSnapshot = state.featureSettings["web-deepseek-api"]
    val messages = remember(messagesSnapshot) { messagesSnapshot?.let(::JSONObject)?.getJSONObject("value") ?: JSONObject() }
    val api = remember(apiSnapshot) { apiSnapshot?.let(::JSONObject)?.getJSONObject("value") ?: JSONObject() }
    var messagesValues by remember(messagesSnapshot) { mutableStateOf(messages.toString()) }
    var apiValues by remember(apiSnapshot) { mutableStateOf(api.toString()) }
    var apiKey by remember { mutableStateOf("") }
    LaunchedEffect(state.featureSaveReceipt) { apiKey = "" }
    var chooser by remember { mutableStateOf(false) }
    val namespace = if (direct) "web-deepseek-api" else "web-search-deepseek"
    val snapshot = if (direct) apiSnapshot else messagesSnapshot
    val values = if (direct) apiValues else messagesValues
    val dirty = messagesValues != messages.toString() || apiValues != api.toString() || apiKey.isNotEmpty() || (!configureFetch && direct != initialDirect)
    FeatureSettingsLayout(if (configureFetch) R.string.native_settings_web_reading else R.string.native_settings_web_search, state, model, onBack, dirty,
        canSave = snapshot != null && routingSnapshot != null, onSave = {
            val updates = linkedMapOf<String, JSONObject>()
            fun selectedFields(json: String, fields: List<String>): JSONObject = JSONObject().apply {
                val input = JSONObject(json)
                fields.forEach { if (input.has(it)) put(it, input.opt(it)) }
            }
            if (messagesSnapshot != null) updates["web-search-deepseek"] = selectedFields(messagesValues, listOf("baseURL", "maxUses"))
            if (apiSnapshot != null) updates["web-deepseek-api"] = selectedFields(apiValues, listOf("baseURL", "count"))
            if (!configureFetch && direct != initialDirect) updates["web"] = JSONObject()
                .put("searchProvider", if (direct) "deepseek-search-api" else "deepseek-official")
                .put("fetchProvider", if (direct) "deepseek-fetch-api" else "http")
            model.saveFeatures(updates, namespace, apiKey)
        }) { modifier ->
        Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(onClick = { if (!configureFetch) chooser = true }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                ListItem(headlineContent = { Text(stringResource(if (configureFetch) R.string.native_settings_web_reading else R.string.native_settings_search_service)) },
                    supportingContent = { Text("DeepSeek") },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
            }
            if (!configureFetch) Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.native_settings_new_web_api), Modifier.weight(1f))
                Switch(direct, { direct = it }, enabled = apiSnapshot != null && messagesSnapshot != null && !state.saving,
                    modifier = Modifier.testTag("native-search-new-api"))
            }
            if (snapshot == null) Text(stringResource(R.string.native_settings_error_unavailable)) else {
                OutlinedTextField(apiKey, { apiKey = it }, label = { Text(stringResource(R.string.native_settings_api_key)) },
                    placeholder = { Text(stringResource(if (JSONObject(snapshot).optBoolean("credentialConfigured")) R.string.native_settings_keep_key else R.string.native_settings_key_unconfigured)) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth())
                SearchField(values, "baseURL", if (direct) R.string.native_settings_base_url else R.string.native_settings_messages_base_url) {
                    if (direct) apiValues = it else messagesValues = it
                }
                SearchField(values, if (direct) "count" else "maxUses", if (direct) R.string.native_settings_search_count else R.string.native_settings_search_uses, true) {
                    if (direct) apiValues = it else messagesValues = it
                }
            }
            if (onBrowserNetwork != null) {
                HorizontalDivider()
                Surface(onClick = onBrowserNetwork, enabled = !state.saving && !dirty,
                    shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().testTag("native-settings-browser-network")) {
                    ListItem(headlineContent = { Text(stringResource(R.string.native_settings_browser_network)) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
                }
            }
        }
    }
    if (chooser) ModalBottomSheet(onDismissRequest = { chooser = false }) {
        Text(stringResource(R.string.native_settings_search_service), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(24.dp))
        ListItem(headlineContent = { Text("DeepSeek") }, leadingContent = { RadioButton(true, onClick = { chooser = false }) })
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
}

@Composable private fun SearchField(json: String, field: String, label: Int, numeric: Boolean = false, changed: (String) -> Unit) {
    val value = JSONObject(json).opt(field)?.takeUnless { it === JSONObject.NULL }?.toString().orEmpty()
    OutlinedTextField(value, { text ->
        if (!numeric || text.all(Char::isDigit)) changed(JSONObject(json).put(field,
            if (text.isBlank()) JSONObject.NULL else if (numeric) text.toLongOrNull() ?: return@OutlinedTextField else text).toString())
    }, label = { Text(stringResource(label)) }, singleLine = true, shape = MaterialTheme.shapes.large,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text), modifier = Modifier.fillMaxWidth())
}

@Composable
fun NativeSubtaskModelSettingsScreen(state: NativeSettingsState, model: NativeSettingsViewModel, onBack: () -> Unit) {
    val namespace = "subagent-model-selection-settings"
    val limitsSnapshot = state.featureSettings["subagent"]
    val limits = remember(limitsSnapshot) { limitsSnapshot?.let(::JSONObject)?.getJSONObject("value") ?: JSONObject() }
    var maxDepth by remember(limitsSnapshot) { mutableStateOf(limits.optLong("maxDepth", 1).toString()) }
    var maxActive by remember(limitsSnapshot) { mutableStateOf(limits.optLong("maxActiveSubagents", 8).toString()) }
    val limitsDirty = maxDepth != limits.optLong("maxDepth", 1).toString() || maxActive != limits.optLong("maxActiveSubagents", 8).toString()
    val limitsValid = maxDepth.toLongOrNull()?.let { it in 0..9007199254740991L } == true && maxActive.toLongOrNull()?.let { it in 1..9007199254740991L } == true
    val snapshot = state.featureSettings[namespace]
    val current = remember(snapshot) { snapshot?.let(::JSONObject)?.getJSONObject("value") ?: JSONObject() }
    var enabled by remember(snapshot) { mutableStateOf(current.optBoolean("enabled")) }
    val original = remember(snapshot) { current.optJSONArray("allowedModels")?.objects()?.map { it.getString("provider") to it.getString("model") }?.toSet().orEmpty() }
    var selected by remember(snapshot) { mutableStateOf(original) }
    var query by rememberSaveable { mutableStateOf("") }
    FeatureSettingsLayout(R.string.native_settings_subtask_models, state, model, onBack,
        enabled != current.optBoolean("enabled") || selected != original || limitsDirty, snapshot != null && limitsSnapshot != null && limitsValid && (!enabled || selected.isNotEmpty()),
        onSave = { model.saveFeatures(linkedMapOf("subagent" to JSONObject().put("maxDepth", maxDepth.toLong()).put("maxActiveSubagents", maxActive.toLong()), namespace to JSONObject().put("enabled", enabled).put("allowedModels", JSONArray(selected.map {
            JSONObject().put("provider", it.first).put("model", it.second)
        })))) }) { modifier ->
        LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(maxDepth, { if (it.all(Char::isDigit)) maxDepth = it }, label = { Text(stringResource(R.string.native_settings_subagent_depth)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().testTag("native-subagent-depth"))
            }
            item {
                OutlinedTextField(maxActive, { if (it.all(Char::isDigit)) maxActive = it }, label = { Text(stringResource(R.string.native_settings_subagent_concurrency)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().testTag("native-subagent-concurrency"))
            }
            item {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.native_settings_allow_subtask_models), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(enabled, { enabled = it }, enabled = snapshot != null)
                }
                Text(stringResource(if (enabled) R.string.native_settings_new_conversations else R.string.native_settings_follow_conversation),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (enabled) {
                item { OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.native_settings_search_models)) },
                    shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) }
                val groups = state.catalog.filter { it.name.contains(query, true) || it.id.contains(query, true) || it.providerName.contains(query, true) }.groupBy { it.provider }
                groups.forEach { (provider, models) ->
                    item("group:$provider") { Text(models.first().providerName, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 12.dp)) }
                    items(models, key = { "${it.provider}:${it.id}" }) { item ->
                        val key = item.provider to item.id
                        Row(Modifier.fillMaxWidth().toggleable(key in selected, role = Role.Checkbox) { checked ->
                            selected = if (checked) selected + key else selected - key
                        }.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(key in selected, null)
                            Column { Text(item.name); if (item.name != item.id) Text(item.id, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                val missing = selected.filter { route -> state.catalog.none { it.provider == route.first && it.id == route.second } }
                items(missing) { route -> Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(true, { selected = selected - route }); Text("${route.first} / ${route.second}", Modifier.weight(1f))
                    Text(stringResource(R.string.native_settings_unavailable), style = MaterialTheme.typography.labelSmall)
                } }
            }
        }
    }
}

@Composable
private fun FeatureSettingsLayout(title: Int, state: NativeSettingsState, model: NativeSettingsViewModel, onBack: () -> Unit,
    dirty: Boolean, canSave: Boolean, onSave: () -> Unit, content: @Composable (Modifier) -> Unit) {
    var confirmLeave by remember { mutableStateOf(false) }
    fun back() { if (dirty) confirmLeave = true else onBack() }
    BackHandler(onBack = ::back)
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            DroyIconAction(R.drawable.native_settings_arrow_back, stringResource(R.string.native_settings_back), ::back, enabled = !state.saving)
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onSave, enabled = dirty && canSave && state.writable && !state.saving) { Text(stringResource(R.string.native_settings_save)) }
        }
    }) { padding -> Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().navigationBarsPadding()) {
        state.error?.let { Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.native_settings_error_general), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            TextButton(onClick = model::dismissError) { Text(stringResource(R.string.native_settings_close)) }
        } }
        content(Modifier.fillMaxWidth().weight(1f))
    } }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text(stringResource(R.string.native_settings_unsaved)) },
        confirmButton = { TextButton(onClick = onBack) { Text(stringResource(R.string.native_settings_discard)) } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.native_settings_continue_editing)) } })
}
