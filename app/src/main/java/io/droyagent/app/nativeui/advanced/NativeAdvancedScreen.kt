package io.droyagent.app.nativeui.advanced

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIconAction
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

@Composable
fun NativeAdvancedScreen(state: NativeAdvancedState, model: NativeAdvancedViewModel, onBack: () -> Unit, onOpenUrl: (String) -> Unit, requestedSection: String? = null) {
    require(requestedSection == null || requestedSection in setOf("agent-loop", "bash-sandbox"))
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var page by rememberSaveable { mutableStateOf("home") }
    var spec by rememberSaveable { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmationBody by remember { mutableIntStateOf(R.string.native_advanced_confirm_body) }
    var confirmationDetails by remember { mutableStateOf(emptyList<String>()) }
    var credentialRef by remember { mutableStateOf<String?>(null) }
    var inlineSection by remember { mutableStateOf<AdvancedSection?>(null) }
    var registryMenu by remember { mutableStateOf(false) }
    var selectedBuilds by remember(state.installId, state.pendingBuilds) { mutableStateOf(emptySet<String>()) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // Secret input never enters saved state, the ViewModel, or a navigation argument.
    var credential by remember { mutableStateOf("") }
    fun back() { if (state.busy) return; if (requestedSection != null) { model.closeSection(); onBack(); return }; if (state.draft != null) model.closeSection() else if (page != "home") page = "home" else onBack() }
    LaunchedEffect(requestedSection, state.sections) {
        if (requestedSection != null && state.draft?.section?.id != requestedSection) model.openSection(requestedSection)
    }
    BackHandler(enabled = requestedSection != null || state.draft != null || page != "home", onBack = ::back)
    fun confirm(body: Int = R.string.native_advanced_confirm_body, details: List<String> = emptyList(), action: () -> Unit) {
        confirmationBody = body; confirmationDetails = details; confirmation = action
    }
    LaunchedEffect(page, state.draft?.section?.id) {
        focusManager.clearFocus(force = true); keyboard?.hide()
        credentialRef = null; inlineSection = null; credential = ""
    }
    LaunchedEffect(page, lifecycleState) {
        if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        if (page == "plugins") model.loadPlugins()
        if (page == "account") { while (true) { model.loadAccount(); delay(2500) } }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.surface, contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { Surface { Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            DroyIconAction(R.drawable.native_settings_arrow_back, stringResource(R.string.native_advanced_back), ::back, enabled = !state.busy)
            Text(if (requestedSection != null) sectionTitle(requestedSection) else stringResource(when { state.draft != null -> R.string.native_advanced_configuration; page == "plugins" -> R.string.native_advanced_plugins; page == "account" -> R.string.native_advanced_account; else -> R.string.native_advanced_title }), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(12.dp).testTag("native-advanced-page:${if (state.draft != null) "configuration" else page}"))
            if (requestedSection == null) DroyIconAction(R.drawable.native_ui_refresh, stringResource(R.string.native_advanced_refresh), { when { state.draft != null -> model.reloadDraft(); page == "plugins" -> model.loadPlugins(); page == "account" -> model.loadAccount(); else -> model.refresh() } }, enabled = !state.busy)
            if (requestedSection != null) TextButton(onClick = model::save,
                enabled = state.ready && state.writable && !state.busy && state.draft?.let { it.edits.isNotEmpty() || it.resets.isNotEmpty() } == true,
                modifier = Modifier.testTag("native-advanced-save")) { Text(stringResource(R.string.native_advanced_save)) }
        } } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().navigationBarsPadding().testTag("native-advanced"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!state.ready) item { Text(stringResource(R.string.native_advanced_runtime)) }
            state.error?.let { code -> item {
                Text(stringResource(when (code) { "settings/conflict", "NATIVE_ADVANCED_INLINE_CHANGED" -> R.string.native_advanced_conflict; "NATIVE_ADVANCED_STALE_APPROVAL" -> R.string.native_advanced_stale_approval; "NATIVE_ADVANCED_RUNTIME_CHANGED" -> R.string.native_advanced_runtime_changed; "NATIVE_ADVANCED_ACCOUNT_TASKS" -> R.string.native_advanced_account_tasks; "NATIVE_ADVANCED_INVALID" -> R.string.native_advanced_invalid; else -> R.string.native_advanced_failed }), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = model::dismissError) { Text(stringResource(R.string.native_advanced_dismiss)) }
                if (requestedSection != null && code == "settings/conflict") TextButton(onClick = model::reloadDraft, enabled = !state.busy) {
                    Text(stringResource(R.string.native_advanced_reload_draft))
                }
            } }
            state.receipt?.let { receipt -> item { Text(stringResource(receiptText(receipt)), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            state.inlineReceipt?.let { receipt -> item {
                Text(stringResource(inlineReceiptText(receipt)), color = if (receipt.status == "completed") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            } }
            if (state.operationDetails.isNotEmpty()) item { Text(stringResource(R.string.native_advanced_operation_details)); state.operationDetails.forEach { Text(it) } }
            val draft = state.draft?.takeIf { requestedSection == null || it.section.id == requestedSection }
            if (draft != null) {
                if (requestedSection == null) item { Text(sectionTitle(draft.section.id), style = MaterialTheme.typography.titleMedium) }
                items(if (requestedSection == null) draft.section.fields else approvedAdvancedFields(draft.section), key = { it.key }) { field ->
                    AdvancedFieldEditor(field, draft.edits[field.key] ?: field.value, field.key in draft.resets, state.ready && state.writable && !state.busy,
                        onEdit = { model.edit(field.key, it) }, onReset = { model.reset(field.key) }, onCredential = { ref -> credentialRef = ref; credential = "" },
                        compact = requestedSection != null, inline = draft.section.inlineCredential.takeIf { field.path == listOf("apiKey") },
                        onMigrateInline = { inlineSection = draft.section; credential = "" },
                        onClearInline = { confirm(R.string.native_advanced_inline_clear_body) { model.changeInlineCredential(draft.section.id, draft.section.revision) } })
                }
                if (requestedSection == null) item {
                    Button(onClick = model::save, enabled = state.ready && state.writable && !state.busy && (draft.edits.isNotEmpty() || draft.resets.isNotEmpty()), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-advanced-save")) { Text(stringResource(R.string.native_advanced_save)) }
                    OutlinedButton(onClick = model::reloadDraft, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.native_advanced_reload_draft)) }
                }
            } else if (requestedSection != null) {
                if (state.ready && !state.busy) item { Text(stringResource(R.string.native_advanced_no_sections)) }
            } else when (page) {
                "home" -> {
                    item { Text(stringResource(R.string.native_advanced_configuration), style = MaterialTheme.typography.titleMedium) }
                    if (state.sections.isEmpty()) item { Text(stringResource(R.string.native_advanced_no_sections)) }
                    items(state.sections, key = { it.id }) { section ->
                        OutlinedButton(onClick = { model.openSection(section.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(sectionTitle(section.id)) }
                    }
                    if (state.pluginsAvailable) item { FilledTonalButton(onClick = { page = "plugins" }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.native_advanced_plugins)) } }
                    if (state.accountAvailable) item { FilledTonalButton(onClick = { page = "account" }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.native_advanced_account)) } }
                }
                "plugins" -> {
                    item {
                        Text(stringResource(R.string.native_advanced_compatibility), style = MaterialTheme.typography.bodyMedium)
                        Box {
                            OutlinedButton(onClick = { registryMenu = true }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(registryLabel(state.selectedRegistry))
                            }
                            DropdownMenu(expanded = registryMenu, onDismissRequest = { registryMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.native_advanced_registry_configured)) }, onClick = { registryMenu = false; model.selectRegistry("") })
                                DropdownMenuItem(text = { Text(stringResource(R.string.native_advanced_registry_pnpm)) }, onClick = { registryMenu = false; model.selectRegistry("pnpm") })
                                state.registries?.choices?.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { registryMenu = false; model.selectRegistry(choice) }) }
                            }
                        }
                        OutlinedTextField(spec, { spec = it }, label = { Text(stringResource(R.string.native_advanced_package)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy)
                        OutlinedButton(onClick = { model.inspect(spec) }, enabled = state.ready && !state.busy && spec.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_advanced_inspect)) }
                        state.inspection?.let { Text(it) }
                        Button(onClick = { confirm { model.install(spec) } }, enabled = state.ready && !state.busy && (state.installId == null || state.installState == "settled") && state.installAllowed && state.inspectedSpec == spec.trim() && state.inspectedRegistry == state.selectedRegistry, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_advanced_install)) }
                        if (state.installId != null) {
                            Text(stringResource(when (state.installState) { "running" -> R.string.native_advanced_install_running; "settled" -> R.string.native_advanced_install_settled; "unknown" -> R.string.native_advanced_install_unknown; else -> R.string.native_advanced_failed }))
                            TextButton(onClick = model::refreshInstall) { Text(stringResource(R.string.native_advanced_check_status)) }
                            if (state.installState == "running") OutlinedButton(onClick = model::cancelInstall, enabled = !state.busy) { Text(stringResource(R.string.native_advanced_cancel_install)) }
                        }
                        if (state.pendingBuilds.isNotEmpty()) {
                            Text(stringResource(R.string.native_advanced_pending_scripts), style = MaterialTheme.typography.titleSmall)
                            state.pendingBuilds.forEach { name -> Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Checkbox(name in selectedBuilds, { checked -> selectedBuilds = if (checked) selectedBuilds + name else selectedBuilds - name }, enabled = !state.busy && state.installState == "settled")
                                Text(name, modifier = Modifier.weight(1f))
                            } }
                            OutlinedButton(onClick = { val approved = selectedBuilds.toSet(); confirm(R.string.native_advanced_scripts_risk, approved.sorted()) { model.approveBuilds(approved) } }, enabled = !state.busy && state.installState == "settled" && selectedBuilds.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_advanced_approve_scripts)) }
                        }
                        Text(stringResource(R.string.native_advanced_bundles), style = MaterialTheme.typography.titleMedium)
                    }
                    items(state.bundles, key = { "bundle:${it.name}" }) { bundle ->
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(bundle.name, style = MaterialTheme.typography.titleSmall); bundle.version?.let { Text(it) }
                            Row { Text(stringResource(R.string.native_advanced_enabled), Modifier.weight(1f)); Switch(bundle.enabled, { enabled -> confirm { model.enable("bundle", bundle.name, enabled) } }, enabled = state.ready && !state.busy && bundle.writable) }
                            if (bundle.removable) TextButton(onClick = { confirm { model.remove(bundle.name) } }, enabled = !state.busy) { Text(stringResource(R.string.native_advanced_remove)) }
                        } }
                    }
                    item {
                        Text(stringResource(R.string.native_advanced_version_exemptions), style = MaterialTheme.typography.titleMedium)
                        state.runtimeVersion?.let { Text(stringResource(R.string.native_advanced_runtime_version, it)) }
                        if (state.exemptionWarnings > 0) Text(stringResource(R.string.native_advanced_exemption_warning), color = MaterialTheme.colorScheme.error)
                    }
                    val candidates = (state.bundles.flatMap { it.incompatible } + state.incompatible).distinctBy { it.packageVersion to it.runtimeVersion }
                    items(candidates, key = { "incompatible:${it.packageVersion}:${it.runtimeVersion}" }) { candidate ->
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(candidate.packageVersion, style = MaterialTheme.typography.titleSmall)
                            candidate.requirements.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            val alreadyAllowed = state.versionExemptions.any { it.packageVersion == candidate.packageVersion && it.runtimeVersion == candidate.runtimeVersion }
                            if (alreadyAllowed) Text(stringResource(R.string.native_advanced_exemption_active))
                            else OutlinedButton(onClick = { confirm(R.string.native_advanced_version_risk, listOf(candidate.packageVersion, candidate.runtimeVersion)) { model.setVersionExemption(candidate.packageVersion, candidate.runtimeVersion, true, true) } }, enabled = state.ready && !state.busy && state.runtimeVersion == candidate.runtimeVersion, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_advanced_allow_version)) }
                        } }
                    }
                    items(state.versionExemptions, key = { "exempt:${it.packageVersion}:${it.runtimeVersion}" }) { exemption ->
                        Column {
                            Text(exemption.packageVersion, style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.native_advanced_runtime_version, exemption.runtimeVersion))
                            TextButton(onClick = { confirm(details = listOf(exemption.packageVersion, exemption.runtimeVersion)) { model.setVersionExemption(exemption.packageVersion, exemption.runtimeVersion, false, false) } }, enabled = state.ready && !state.busy) { Text(stringResource(R.string.native_advanced_revoke_version)) }
                        }
                    }
                    item { Text(stringResource(R.string.native_advanced_loaded_plugins), style = MaterialTheme.typography.titleMedium) }
                    items(state.plugins, key = { "entry:${it.id}" }) { plugin -> PluginCard(plugin, state.ready && !state.busy) { enabled -> confirm { model.enable("plugin", plugin.id, enabled) } } }
                    for (preset in state.presets) {
                        item(key = "preset:${preset.id}") { Text(preset.name, style = MaterialTheme.typography.titleMedium) }
                        items(preset.rows) { PluginCard(it, false) {} }
                    }
                }
                "account" -> state.account?.let { account ->
                    item {
                        Text(stringResource(if (account.status == "credential-stored") R.string.native_advanced_account_stored else R.string.native_advanced_signed_out), style = MaterialTheme.typography.titleMedium)
                        if (account.status == "credential-stored") {
                            account.name?.let { Text(it) }; account.contact?.let { Text(it) }; account.balances.forEach { Text(it) }
                            if (account.detailsFailed) Text(stringResource(R.string.native_advanced_details_failed), color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = model::accountDetails, enabled = !state.busy) { Text(stringResource(R.string.native_advanced_details)) }
                            if (account.running) Text(stringResource(R.string.native_advanced_account_tasks))
                            OutlinedButton(onClick = { confirm(action = model::signOut) }, enabled = !state.busy && !account.running) { Text(stringResource(R.string.native_advanced_sign_out)) }
                        } else Button(onClick = model::signIn, enabled = !state.busy) { Text(stringResource(R.string.native_advanced_sign_in)) }
                        account.phase?.let { phase -> Text(stringResource(accountPhase(phase))) }
                        account.authorizeUrl?.let { url -> OutlinedButton(onClick = { onOpenUrl(url) }) { Text(stringResource(R.string.native_advanced_open_sign_in)) } }
                        if (account.attemptId != null && account.phase in setOf("initializing", "waiting-browser", "exchanging", "committing")) TextButton(onClick = model::cancelSignIn, enabled = !state.busy) { Text(stringResource(R.string.native_advanced_cancel_sign_in)) }
                    }
                }
            }
        }
    }
    if (confirmation != null) AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(stringResource(R.string.native_advanced_confirm)) }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(stringResource(confirmationBody)); confirmationDetails.forEach { Text(it) } } }, confirmButton = { TextButton(onClick = { val action = confirmation; confirmation = null; action?.invoke() }) { Text(stringResource(R.string.native_advanced_continue)) } }, dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.native_advanced_cancel)) } })
    credentialRef?.let { ref -> AlertDialog(onDismissRequest = { credentialRef = null; credential = "" }, title = { Text(stringResource(R.string.native_advanced_credential)) }, text = {
        Column { Text(ref); OutlinedTextField(credential, { credential = it }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), label = { Text(stringResource(R.string.native_advanced_new_key)) }, singleLine = true)
            TextButton(onClick = { model.credential(ref, null) }) { Text(stringResource(R.string.native_advanced_check_status)) }
            TextButton(onClick = { confirm { model.credential(ref, null, true) }; credentialRef = null; credential = "" }) { Text(stringResource(R.string.native_advanced_clear_key)) } }
    }, confirmButton = { TextButton(onClick = { model.credential(ref, credential); credential = ""; credentialRef = null }, enabled = credential.isNotBlank() && !state.busy) { Text(stringResource(R.string.native_advanced_save)) } }, dismissButton = { TextButton(onClick = { credential = ""; credentialRef = null }) { Text(stringResource(R.string.native_advanced_cancel)) } }) }
    inlineSection?.let { section -> AlertDialog(onDismissRequest = { inlineSection = null; credential = "" }, title = { Text(stringResource(R.string.native_advanced_migrate_inline)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.native_advanced_inline_migrate_body))
            Text(section.inlineCredential?.ref.orEmpty())
            OutlinedTextField(credential, { credential = it }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), label = { Text(stringResource(R.string.native_advanced_new_key)) }, singleLine = true)
        }
    }, confirmButton = { TextButton(onClick = { model.changeInlineCredential(section.id, section.revision, credential); credential = ""; inlineSection = null }, enabled = credential.isNotBlank() && !state.busy) { Text(stringResource(R.string.native_advanced_save_migrate)) } }, dismissButton = { TextButton(onClick = { inlineSection = null; credential = "" }) { Text(stringResource(R.string.native_advanced_cancel)) } }) }
}

@Composable
private fun AdvancedFieldEditor(field: AdvancedField, value: String, reset: Boolean, enabled: Boolean, onEdit: (String) -> Unit, onReset: () -> Unit, onCredential: (String) -> Unit, inline: AdvancedInlineCredential?, onMigrateInline: () -> Unit, onClearInline: () -> Unit, compact: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(fieldTitle(field), style = MaterialTheme.typography.titleSmall)
        if (reset) Text(stringResource(R.string.native_advanced_reset_pending))
        when (field.kind) {
            "boolean" -> Row { Text(stringResource(R.string.native_advanced_enabled), Modifier.weight(1f)); Switch(value == "true", { onEdit(it.toString()) }, enabled = enabled) }
            "enum" -> field.options.forEach { option -> OutlinedButton(onClick = { onEdit(option) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text((if (option == value) "✓ " else "") + option) } }
            "number", "string", "array" -> { OutlinedTextField(value, onEdit, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("native-advanced-field:${field.path.joinToString(".")}"), shape = MaterialTheme.shapes.medium, keyboardOptions = KeyboardOptions(keyboardType = if (field.kind == "number") KeyboardType.Number else KeyboardType.Text), singleLine = field.kind != "array"); if (field.kind == "array") Text(stringResource(R.string.native_advanced_array_hint), style = MaterialTheme.typography.bodySmall) }
            "secret" -> if (inline == null) Text(stringResource(R.string.native_advanced_secret_unsupported)) else {
                Text(stringResource(if (inline.configured) R.string.native_advanced_inline_priority else R.string.native_advanced_credential_empty))
                if (inline.configured) {
                    OutlinedButton(onClick = onMigrateInline, enabled = enabled) { Text(stringResource(R.string.native_advanced_migrate_inline)) }
                    TextButton(onClick = onClearInline, enabled = enabled) { Text(stringResource(R.string.native_advanced_clear_inline)) }
                }
            }
            else -> Text(stringResource(R.string.native_advanced_unsupported))
        }
        field.credentialRef?.let { ref -> OutlinedButton(onClick = { onCredential(ref) }, enabled = enabled && value == ref) { Text(stringResource(R.string.native_advanced_credential)) } }
        if (compact) when (field.path.singleOrNull()) {
            "timeoutMs" -> Text(stringResource(R.string.native_advanced_timeout_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            "maxOutputBytes" -> Text(stringResource(R.string.native_advanced_output_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            "maxParallelToolCalls" -> Text(stringResource(R.string.native_advanced_parallel_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!compact && field.kind !in setOf("secret", "unsupported")) TextButton(onClick = onReset, enabled = enabled) { Text(stringResource(R.string.native_advanced_reset_field)) }
    } }
}
@Composable
private fun PluginCard(plugin: AdvancedPlugin, enabled: Boolean, onEnabled: (Boolean) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(plugin.name, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(if (plugin.webUi) R.string.native_advanced_web_plugin else R.string.native_advanced_unassessed_plugin), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(when (plugin.phase) { "active" -> R.string.native_advanced_plugin_active; "failed" -> R.string.native_advanced_plugin_failed; "loading", "pending" -> R.string.native_advanced_plugin_loading; else -> R.string.native_advanced_plugin_inactive }))
        if (plugin.conditional) Text(stringResource(R.string.native_advanced_conditional))
        Row { Text(stringResource(R.string.native_advanced_enabled), Modifier.weight(1f)); Switch(plugin.enabled, onEnabled, enabled = enabled && plugin.writable) }
    } }
}
@Composable private fun sectionTitle(id: String): String = stringResource(when(id) {
    "bash-sandbox" -> R.string.native_settings_terminal; "pwsh-sandbox" -> R.string.native_advanced_powershell; "agent-loop" -> R.string.native_advanced_loop; "subagent" -> R.string.native_advanced_subagent; "subagent-model-selection-settings" -> R.string.native_advanced_subagent_models;
    "web" -> R.string.native_advanced_web; "web-search-deepseek" -> R.string.native_advanced_search; "web-deepseek-api" -> R.string.native_advanced_web_api; else -> R.string.native_advanced_logs
})
private fun receiptText(value: String): Int = when(value) {
    "saved", "applied" -> R.string.native_advanced_saved; "review-draft" -> R.string.native_advanced_review_draft; "restart-required" -> R.string.native_advanced_restart; "cancelled" -> R.string.native_advanced_cancelled;
    "credential-set" -> R.string.native_advanced_credential_set; "credential-empty" -> R.string.native_advanced_credential_empty; "signed-out" -> R.string.native_advanced_signed_out; "overridden" -> R.string.native_advanced_overridden;
    "too-late" -> R.string.native_advanced_too_late; "not-running" -> R.string.native_advanced_not_running
    "credential-shadowed" -> R.string.native_advanced_credential_shadowed
    "credential-cleared-shadowed" -> R.string.native_advanced_credential_cleared_shadowed
    "saved-failed" -> R.string.native_advanced_saved_failed
    else -> R.string.native_advanced_failed
}
@Composable private fun registryLabel(choice: String): String = when (choice) {
    "" -> stringResource(R.string.native_advanced_registry_configured)
    "pnpm" -> stringResource(R.string.native_advanced_registry_pnpm)
    else -> choice
}
private fun inlineReceiptText(receipt: AdvancedInlineReceipt): Int = when {
    receipt.status == "completed" && receipt.credential == "committed" -> R.string.native_advanced_inline_migrated
    receipt.status == "completed" -> R.string.native_advanced_inline_cleared
    receipt.status == "overridden" -> R.string.native_advanced_inline_overridden
    receipt.credential == "rejected" -> R.string.native_advanced_inline_key_rejected
    receipt.credential == "unknown" -> R.string.native_advanced_inline_key_unknown
    receipt.credential == "committed" && receipt.status == "conflict" -> R.string.native_advanced_inline_key_saved_conflict
    receipt.credential == "committed" && receipt.inline == "unknown" -> R.string.native_advanced_inline_key_saved_unknown
    receipt.credential == "committed" -> R.string.native_advanced_inline_key_saved
    receipt.status == "conflict" -> R.string.native_advanced_conflict
    else -> R.string.native_advanced_inline_clear_unknown
}
private fun accountPhase(value: String): Int = when(value) { "waiting-browser" -> R.string.native_advanced_login_wait; "succeeded" -> R.string.native_advanced_login_done; "cancelled" -> R.string.native_advanced_cancelled; "expired", "failed" -> R.string.native_advanced_login_failed; else -> R.string.native_advanced_login_busy }

@Composable private fun fieldTitle(field: AdvancedField): String {
    val id = when (field.path.lastOrNull()) {
        "enabled" -> R.string.native_advanced_enabled
        "cwd" -> R.string.native_advanced_field_cwd
        "timeoutMs" -> R.string.native_advanced_field_timeout
        "maxTimeoutMs" -> R.string.native_advanced_field_max_timeout
        "maxOutputBytes" -> R.string.native_advanced_field_output
        "maxSpillBytes" -> R.string.native_advanced_field_spill
        "graceMs" -> R.string.native_advanced_field_grace
        "pwshPath" -> R.string.native_advanced_field_pwsh
        "maxParallelToolCalls" -> R.string.native_advanced_field_parallel
        "agents" -> R.string.native_advanced_field_agents
        "maxDepth" -> R.string.native_advanced_field_depth
        "maxActiveSubagents" -> R.string.native_advanced_field_active
        "allowedModels" -> R.string.native_advanced_field_models
        "searchProvider" -> R.string.native_advanced_field_search
        "fetchProvider" -> R.string.native_advanced_field_fetch
        "apiKey" -> R.string.native_advanced_field_secret
        "apiKeyEnv" -> R.string.native_advanced_field_ref
        "baseURL" -> R.string.native_advanced_field_url
        "model" -> R.string.native_advanced_field_model
        "apiVersion" -> R.string.native_advanced_field_version
        "maxTokens" -> R.string.native_advanced_field_tokens
        "maxUses" -> R.string.native_advanced_field_uses
        "count" -> R.string.native_advanced_field_count
        "sendOriginalQuestion" -> R.string.native_advanced_field_question
        "maxBytes" -> R.string.native_advanced_field_bytes
        else -> null
    }
    return id?.let { stringResource(it) } ?: stringResource(R.string.native_advanced_field_unknown, field.label)
}

internal fun approvedAdvancedFields(section: AdvancedSection): List<AdvancedField> {
    val allowed = when (section.id) {
        "bash-sandbox" -> setOf("timeoutMs", "maxOutputBytes")
        "agent-loop" -> setOf("maxParallelToolCalls")
        else -> emptySet()
    }
    return section.fields.filter { it.path.size == 1 && it.path[0] in allowed }
}
