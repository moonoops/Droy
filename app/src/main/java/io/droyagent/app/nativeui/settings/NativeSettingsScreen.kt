package io.droyagent.app.nativeui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import kotlinx.coroutines.flow.collect
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.nestedscroll.nestedScroll
import io.droyagent.app.nativeui.ui.DroySettingsLink
import io.droyagent.app.nativeui.ui.DroySettingsLinkGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import io.droyagent.app.nativeui.ui.DroySettingsSectionTitle
import io.droyagent.app.nativeui.companion.ui.CompanionFilledField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.data.NativeModel
import io.droyagent.app.nativeui.ui.DroyIcon
import io.droyagent.app.nativeui.ui.DroyIconAction

enum class NativeSettingsFeature { GENERAL, MEMORY, VOICE, TOOLS, NOTIFICATIONS, ADVANCED, ABOUT, WEB_SEARCH, PLUGINS, PET, NOTIFICATION_SORTING }

/** Product settings use their shared Host source; navigation contains no credential values. */
@Composable
fun NativeSettingsScreen(
    state: NativeSettingsState,
    model: NativeSettingsViewModel,
    onMyProfile: () -> Unit,
    onPermissions: () -> Unit,
    onRootChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    onOpenFeature: ((NativeSettingsFeature) -> Unit)? = null,
    onModels: (() -> Unit)? = null,
) {
    NativeSettingsFlow(state, model, onMyProfile, onPermissions, onRootChanged, modifier, onOpenFeature = onOpenFeature, onModels = onModels)
}

/** The wizard and Settings share the same provider editor, receipts and key lifetime. */
@Composable
fun NativeModelSettingsScreen(
    state: NativeSettingsState,
    model: NativeSettingsViewModel,
    onBack: () -> Unit,
    onRootChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    embedded: Boolean = true,
) {
    NativeSettingsFlow(state, model, {}, {}, onRootChanged, modifier, modelsOnly = true, embedded = embedded, onModelsBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NativeSettingsFlow(
    state: NativeSettingsState,
    model: NativeSettingsViewModel,
    onMyProfile: () -> Unit,
    onPermissions: () -> Unit,
    onRootChanged: (Boolean) -> Unit,
    modifier: Modifier,
    modelsOnly: Boolean = false,
    embedded: Boolean = false,
    onModelsBack: () -> Unit = {},
    onOpenFeature: ((NativeSettingsFeature) -> Unit)? = null,
    onModels: (() -> Unit)? = null,
) {
    val homeScroll = rememberScrollState()
    val homeBar = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        snapAnimationSpec = MaterialTheme.motionScheme.fastSpatialSpec())
    var page by rememberSaveable(modelsOnly) { mutableStateOf(if (modelsOnly) "models" else "home") }
    var chooser by rememberSaveable { mutableStateOf<String?>(null) }
    var editingModel by remember { mutableStateOf<NativeSettingsModelEntry?>(null) }
    var editingModelPage by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var originalEditor by remember { mutableStateOf<NativeProviderDraft?>(null) }
    // The key draft is deliberately excluded from rememberSaveable and the ViewModel.
    var apiKey by remember(state.editorGeneration) { mutableStateOf("") }
    // The input buffer owns text, selection and IME composition. VM updates never feed old text back into it.
    val providerName = key(state.editor?.id, state.editorGeneration) { rememberTextFieldState(state.editor?.name.orEmpty()) }
    val providerBaseUrl = key(state.editor?.id, state.editorGeneration) { rememberTextFieldState(state.editor?.baseUrl.orEmpty()) }
    fun flushProviderText() {
        state.editor?.id?.let { model.editProviderInputs(it, providerName.text.toString(), providerBaseUrl.text.toString()) }
    }
    LaunchedEffect(providerName, providerBaseUrl, state.editor?.id) {
        val id = state.editor?.id ?: return@LaunchedEffect
        snapshotFlow { providerName.text.toString() to providerBaseUrl.text.toString() }.collect { (name, baseUrl) ->
            model.editProviderInputs(id, name, baseUrl)
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun closeKeyboard() { focus.clearFocus(); keyboard?.hide() }
    fun leavePage() {
        if (state.saving) return
        closeKeyboard()
        chooser = null
        if (page == "editor") { apiKey = ""; model.closeEditor(); page = "models" }
        else if (modelsOnly) onModelsBack() else page = "home"
        model.dismissError()
    }
    fun back() {
        flushProviderText()
        if (page == "editor" && (model.state.value.editor != originalEditor || apiKey.isNotEmpty())) confirmLeave = true else leavePage()
    }
    LaunchedEffect(state.editorGeneration) { originalEditor = state.editor }
    LaunchedEffect(page, editingModelPage) { closeKeyboard(); onRootChanged(!editingModelPage && page == if (modelsOnly) "models" else "home") }
    if (editingModelPage) {
        val draft = state.editor
        if (draft != null) NativeModelEditor(editingModel, draft.settingsNs, draft.useDefaultModels && editingModel != null,
            error = state.error, onSave = { if (model.saveModel(it, editingModel?.id)) editingModelPage = false }, onBack = { model.dismissError(); editingModelPage = false })
        return
    }
    if (page == "subtask-models") {
        NativeSubtaskModelSettingsScreen(state, model, onBack = { page = "models" })
        return
    }
    LaunchedEffect(state.editor?.id, state.editorGeneration) {
        if (state.editor != null) page = "editor"
        else if (page == "editor") { apiKey = ""; page = "models" }
    }
    BackHandler(enabled = modelsOnly || page != "home", onBack = ::back)
    val title = if (page == "editor" && state.editor?.custom == false) state.editor.name else stringResource(when (page) {
        "general" -> R.string.native_settings_general
        "models" -> R.string.native_settings_models
        "editor" -> if (state.editor?.isNew == true) R.string.native_settings_add_provider_title else R.string.native_settings_edit_provider_title
        else -> R.string.native_settings_title
    })
    Scaffold(modifier.fillMaxSize().then(if (page == "home") Modifier.nestedScroll(homeBar.nestedScrollConnection) else Modifier).testTag("native-settings"), containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            if (page == "home") LargeTopAppBar(title = { Text(title) },
                modifier = Modifier.testTag("native-settings-home-bar"),
                expandedHeight = 88.dp, collapsedHeight = 64.dp, scrollBehavior = homeBar,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface))
            else if (!(embedded && page == "models")) SettingsBar(title, page == "home", onBack = ::back,
                onRefresh = if (page == "editor") model::reloadEditor else model::refresh,
                refreshEnabled = state.runtime.ready && !state.saving && !state.testing && !state.loading,
                save = if (page == "editor") ({ flushProviderText(); closeKeyboard(); model.saveProvider(apiKey) }) else null,
                saveEnabled = state.runtime.ready && state.writable && !state.saving && !state.testing && !state.discovering && !state.loading,
                saving = state.saving, credentialPending = state.credentialPending, insetStatusBar = !embedded)
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (state.error != null) SettingsError(state.error, model::dismissError,
                if (page == "editor") model::reloadEditor else model::refresh)
            if (!state.runtime.ready && !state.runtime.active) SettingsRuntime(state.runtime.status, state.runtime.active, state.runtime.canStart, model::startRuntime)
            if (state.loaded && !state.writable) Text(stringResource(R.string.native_settings_read_only),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            when (page) {
                "general" -> GeneralSettings(state, model, Modifier.weight(1f))
                "models" -> ModelsSettings(state, model, onAdd = { chooser = "providers" }, onDefault = { chooser = "default" },
                    onSubtask = { page = "subtask-models" }, showSubtaskModels = false, modifier = Modifier.weight(1f), onboarding = embedded)
                "editor" -> state.editor?.let { draft -> ProviderEditor(state, draft, model, providerName, providerBaseUrl, apiKey, { apiKey = it },
                    onProtocol = { flushProviderText(); closeKeyboard(); chooser = "protocol" }, onManualModel = { flushProviderText(); closeKeyboard(); editingModel = null; editingModelPage = true },
                    onEditModel = { flushProviderText(); editingModel = it; editingModelPage = true },
                    onFetchModels = { flushProviderText(); closeKeyboard(); model.discover(apiKey) },
                    onClearKey = { chooser = "clear-key" }, modifier = Modifier.weight(1f)) }
                else -> SettingsHome(state, onMyProfile, onGeneral = {
                    if (onOpenFeature != null) onOpenFeature(NativeSettingsFeature.GENERAL) else page = "general"
                }, onModels = { if (onModels != null) onModels() else page = "models" }, onPermissions = onPermissions,
                    modifier = Modifier.weight(1f), scroll = homeScroll, onOpenFeature = onOpenFeature)
            }
        }
    }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text(stringResource(R.string.native_settings_unsaved)) },
        confirmButton = { TextButton(onClick = { confirmLeave = false; leavePage() }) { Text(stringResource(R.string.native_settings_discard)) } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.native_settings_continue_editing)) } })
    if (chooser == "providers") ProviderChooser(state.presets, onSelect = { model.openProvider(it.id); chooser = null },
        onCustom = { model.addProvider(); chooser = null }, onDismiss = { chooser = null })
    if (chooser == "default") DefaultModelChooser(state.catalog, state.defaultModel, state.error, state.saving, onSelect = { model.setDefault(it) },
        onDismiss = { chooser = null })
    LaunchedEffect(state.defaultModel, state.saving) { if (chooser == "default" && !state.saving && state.error == null) chooser = null }
    if (chooser == "protocol") state.editor?.let { draft -> ProtocolChooser(draft.protocol, !draft.custom,
        onSelect = { model.editProviderProtocol(draft.id, it); chooser = null }, onDismiss = { chooser = null }) }
    if (chooser == "clear-key") AlertDialog(onDismissRequest = { chooser = null },
        title = { Text(stringResource(R.string.native_settings_clear_key_title)) },
        text = state.editor?.explicitCredentialRef?.let { ref ->
            val count = state.configuredProviders.count { ref in it.credentialRefs }
            if (count > 1) ({ Text(stringResource(R.string.native_settings_shared_key_count, count)) }) else null
        },
        confirmButton = { TextButton(onClick = { model.clearCredential(); apiKey = ""; chooser = null }) { Text(stringResource(R.string.native_settings_confirm_clear_key)) } },
        dismissButton = { TextButton(onClick = { chooser = null }) { Text(stringResource(R.string.native_settings_cancel)) } })
    if (state.discoveryComplete) DiscoveryChooser(state.discoveredModels,
        onAdd = model::addDiscovered, onDismiss = model::dismissDiscovery)
}

@Composable
private fun SettingsBar(title: String, root: Boolean, onBack: () -> Unit, onRefresh: () -> Unit, refreshEnabled: Boolean,
    save: (() -> Unit)?, saveEnabled: Boolean, saving: Boolean, credentialPending: Boolean, insetStatusBar: Boolean = true) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().then(if (insetStatusBar) Modifier.statusBarsPadding() else Modifier).heightIn(min = if (root) 80.dp else 64.dp)
            .padding(start = if (root) 24.dp else 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!root) DroyIconAction(R.drawable.native_settings_arrow_back, stringResource(R.string.native_settings_back), onBack,
                enabled = !saving, modifier = Modifier.testTag("native-settings-back"))
            Text(title, style = if (root) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = if (root) 0.dp else 8.dp, end = 8.dp))
            if (save != null) Button(onClick = save, enabled = saveEnabled,
                modifier = Modifier.heightIn(min = 48.dp).testTag("native-settings-save-provider")) {
                Text(stringResource(if (saving) R.string.native_settings_saving else if (credentialPending) R.string.native_settings_save_key else R.string.native_settings_save))
            }
        }
    }
}

@Composable
private fun SettingsHome(state: NativeSettingsState, onMyProfile: () -> Unit, onGeneral: () -> Unit, onModels: () -> Unit,
    onPermissions: () -> Unit, modifier: Modifier, scroll: androidx.compose.foundation.ScrollState,
    onOpenFeature: ((NativeSettingsFeature) -> Unit)?) {
    Column(modifier.fillMaxWidth().verticalScroll(scroll).navigationBarsPadding()
        .testTag("native-settings-home-list").padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DroySettingsLinkGroup(items = listOf(DroySettingsLink(stringResource(R.string.native_settings_my_profile),
            R.drawable.native_settings_person, "native-settings-my-profile", onClick = onMyProfile)))
        DroySettingsLinkGroup(stringResource(R.string.native_settings_group_basic), listOf(
            DroySettingsLink(stringResource(R.string.native_settings_general), R.drawable.native_settings_tune, "native-settings-general", onClick = onGeneral),
            DroySettingsLink(stringResource(R.string.native_settings_models), R.drawable.native_ui_model, "native-settings-models", state.defaultModel?.name, onModels),
            DroySettingsLink(stringResource(R.string.native_permissions_title), R.drawable.native_engine_shield, "native-settings-permissions", onClick = onPermissions),
        ))
        onOpenFeature?.let { open ->
            DroySettingsLinkGroup(stringResource(R.string.native_settings_group_capabilities), listOf(
                DroySettingsLink(stringResource(R.string.native_settings_memory), R.drawable.native_settings_auto_stories, "native-settings-memory") { open(NativeSettingsFeature.MEMORY) },
                DroySettingsLink(stringResource(R.string.native_settings_voice), R.drawable.native_companion_mic, "native-settings-voice") { open(NativeSettingsFeature.VOICE) },
                DroySettingsLink(stringResource(R.string.native_settings_plugins), R.drawable.native_settings_hub, "native-settings-plugins") { open(NativeSettingsFeature.PLUGINS) },
            ))
            DroySettingsLinkGroup(stringResource(R.string.native_settings_group_notifications), listOf(
                DroySettingsLink(stringResource(R.string.native_settings_notifications), R.drawable.native_settings_notifications, "native-settings-notifications") { open(NativeSettingsFeature.NOTIFICATIONS) },
            ))
            DroySettingsLinkGroup(stringResource(R.string.native_settings_group_personalization), listOf(
                DroySettingsLink(stringResource(R.string.native_settings_pet), R.drawable.native_settings_pets, "native-settings-pet") { open(NativeSettingsFeature.PET) },
                DroySettingsLink(stringResource(R.string.native_settings_about), R.drawable.native_settings_info, "native-settings-about") { open(NativeSettingsFeature.ABOUT) },
            ))
        }
    }
}

@Composable
private fun GeneralSettings(state: NativeSettingsState, model: NativeSettingsViewModel, modifier: Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        NativeGeneralPreferences(state, model)
    }
}

@Composable
fun NativeGeneralPreferences(state: NativeSettingsState, model: NativeSettingsViewModel) {
    var chooseLanguage by remember { mutableStateOf(false) }
    var pendingLanguage by remember { mutableStateOf<String?>(null) }
    val languageEnabled = state.runtime.ready && state.writable && !state.saving && !state.testing
    LaunchedEffect(state.locale, state.saving, state.error, pendingLanguage) {
        if (pendingLanguage != null && !state.saving && state.error == null && state.locale == pendingLanguage) {
            chooseLanguage = false; pendingLanguage = null
        }
    }
    if (chooseLanguage) SettingsChoiceSheet(stringResource(R.string.native_settings_language), { chooseLanguage = false }, expanded = false) {
        if (state.error != null) Text(stringResource(R.string.native_settings_error_general), color = MaterialTheme.colorScheme.error)
        for ((language, label) in listOf("zh" to R.string.native_settings_chinese, "en" to R.string.native_settings_english)) {
            Surface(shape = MaterialTheme.shapes.large,
                color = if (state.locale == language) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().selectable(state.locale == language, languageEnabled, Role.RadioButton) {
                    if (state.locale == language) chooseLanguage = false
                    else { pendingLanguage = language; model.setLanguage(language) }
                }.heightIn(min = 56.dp).padding(horizontal = 12.dp).testTag("native-settings-language:$language"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected = state.locale == language, onClick = null, enabled = languageEnabled)
                    Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            PreferenceRow(stringResource(R.string.native_settings_language),
                stringResource(if (state.locale == "zh") R.string.native_settings_chinese else R.string.native_settings_english),
                { model.dismissError(); chooseLanguage = true }, languageEnabled, Modifier.testTag("native-settings-language"))
        }
        NativeAppearanceSettings()
    }
}

@Composable
private fun PreferenceRow(title: String, value: String, onClick: () -> Unit, enabled: Boolean, modifier: Modifier) {
    Row(modifier.fillMaxWidth().clickable(enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DroyIcon(R.drawable.native_settings_chevron_right, null)
    }
}

@Composable
private fun ModelsSettings(state: NativeSettingsState, model: NativeSettingsViewModel, onAdd: () -> Unit, onDefault: () -> Unit, onSubtask: () -> Unit, showSubtaskModels: Boolean, modifier: Modifier, onboarding: Boolean = false) {
    val enabled = state.runtime.ready && state.loaded && state.writable && !state.saving && !state.testing
    if (onboarding && state.loaded && state.configuredProviders.isEmpty() && state.defaultModel == null) {
        Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)
            .testTag("native-settings-onboarding-empty"), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary) {
                        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                            DroyIcon(R.drawable.native_ui_model, null, Modifier.size(36.dp))
                        }
                    }
                    Text(stringResource(R.string.native_settings_setup_add_first), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.native_settings_setup_model_hint), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Button(onClick = onAdd, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-settings-add-provider")) {
                DroyIcon(R.drawable.native_settings_add, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.native_settings_add_provider))
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(R.string.native_settings_add_provider, R.string.native_settings_choose_model,
                    R.string.native_settings_test_model).forEachIndexed { index, label ->
                    Text(stringResource(R.string.native_settings_setup_sequence, index + 1, stringResource(label)),
                        style = if (index == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                        color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }
    var testTarget by remember { mutableStateOf<NativeModel?>(null) }
    var removeTarget by remember { mutableStateOf<NativeSettingsProvider?>(null) }
    removeTarget?.let { provider ->
        AlertDialog(onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.native_settings_remove_provider_title, provider.name)) },
            text = if (state.defaultModel?.provider == provider.id) ({ Text(stringResource(R.string.native_settings_remove_default_provider)) }) else null,
            confirmButton = { TextButton(onClick = { removeTarget = null; model.removeProvider(provider.id) }, enabled = enabled,
                modifier = Modifier.testTag("native-settings-confirm-remove-provider")) { Text(stringResource(R.string.native_settings_confirm_remove_provider)) } },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text(stringResource(R.string.native_settings_cancel)) } })
    }
    testTarget?.let { target ->
        AlertDialog(onDismissRequest = { testTarget = null },
            title = { Text(stringResource(R.string.native_settings_test_model)) },
            text = { Text(stringResource(R.string.native_settings_test_fee)) },
            confirmButton = { TextButton(onClick = { testTarget = null; model.testDefaultModel() },
                enabled = enabled && state.defaultModel?.let { it.provider == target.provider && it.id == target.id } == true,
                modifier = Modifier.testTag("native-settings-confirm-test")) { Text(stringResource(R.string.native_settings_start_test)) } },
            dismissButton = { TextButton(onClick = { testTarget = null }, modifier = Modifier.testTag("native-settings-cancel-test")) {
                Text(stringResource(R.string.native_settings_cancel))
            } })
    }
    LazyColumn(modifier.fillMaxWidth().navigationBarsPadding().testTag("native-settings-providers"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "default") {
            Column {
                DroySettingsSectionTitle(stringResource(R.string.native_settings_default_model))
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth().clickable(enabled && state.catalog.isNotEmpty(), role = Role.Button, onClick = onDefault)
                            .heightIn(min = 48.dp).testTag("native-settings-default-model"), verticalAlignment = Alignment.CenterVertically) {
                            Text(state.defaultModel?.name ?: stringResource(R.string.native_settings_not_selected),
                                style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 8.dp).testTag("native-settings-model-name"))
                            DroyIcon(R.drawable.native_settings_chevron_right, stringResource(R.string.native_settings_choose_model))
                        }
                        VerificationContent(state)
                        FilledTonalButton(onClick = { testTarget = state.defaultModel }, enabled = enabled && state.defaultModel != null,
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                                contentColor = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.widthIn(min = 152.dp).heightIn(min = 48.dp).testTag("native-settings-test-model")) {
                            DroyIcon(R.drawable.native_audio_play, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (state.testing) R.string.native_settings_testing else R.string.native_settings_test_model))
                        }
                    }
                }
            }
        }
        if (showSubtaskModels) item(key = "subtask-models") {
            SettingRow(stringResource(R.string.native_settings_subtask_models), null, R.drawable.native_ui_model, onSubtask,
                enabled = enabled, modifier = Modifier.testTag("native-settings-subtask-models"))
        }
        item(key = "providers-title") {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.native_settings_providers), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Button(onClick = onAdd, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("native-settings-add-provider")) {
                    DroyIcon(R.drawable.native_settings_add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.native_settings_add_provider))
                }
            }
        }
        if (state.loaded && state.configuredProviders.isEmpty()) item(key = "empty") {
            Text(stringResource(R.string.native_settings_no_providers), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        item(key = "provider-list") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                state.configuredProviders.forEachIndexed { index, provider ->
                    key(provider.id) {
                        var more by remember { mutableStateOf(false) }
                        val status = if (provider.unavailable) stringResource(R.string.native_settings_unavailable)
                            else provider.modelCount?.let { stringResource(R.string.native_settings_model_count, it) }
                        val type = providerType(provider).takeUnless { it.equals(provider.name, ignoreCase = true) }
                        val supporting = listOfNotNull(type, status).joinToString(" · ")
                        SegmentedListItem(onClick = { model.openProvider(provider.id) }, enabled = enabled,
                            shapes = ListItemDefaults.segmentedShapes(index, state.configuredProviders.size),
                            leadingContent = { DroyIcon(R.drawable.native_ui_model, null) },
                            supportingContent = supporting.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box {
                                        DroyIconAction(R.drawable.native_companion_more_vert,
                                            stringResource(R.string.native_settings_provider_more, provider.name),
                                            { more = true }, enabled = enabled,
                                            modifier = Modifier.testTag("native-settings-provider-more:${provider.id}"))
                                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                            DropdownMenuItem(text = { Text(stringResource(R.string.native_settings_remove_provider)) },
                                                leadingIcon = { DroyIcon(R.drawable.native_companion_delete, null) },
                                                enabled = enabled, onClick = { more = false; removeTarget = provider },
                                                modifier = Modifier.testTag("native-settings-remove-provider:${provider.id}"))
                                        }
                                    }
                                    DroyIcon(R.drawable.native_settings_chevron_right, null)
                                }
                            },
                            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("native-settings-provider:${provider.id}")) {
                            Text(provider.name)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerificationContent(state: NativeSettingsState, modifier: Modifier = Modifier) {
    val receipt = state.verification?.takeIf { it.provider == state.defaultModel?.provider && it.model == state.defaultModel?.id }
    val chat = when {
        state.testing -> R.string.native_model_chat_testing
        state.verificationUnavailable -> R.string.native_model_chat_unavailable
        receipt == null -> R.string.native_model_chat_untested
        receipt.passed -> R.string.native_model_chat_passed
        else -> R.string.native_model_chat_failed
    }
    val tools = when {
        state.testing -> R.string.native_model_tools_testing
        state.verificationUnavailable -> R.string.native_model_tools_unavailable
        receipt == null || !receipt.passed -> R.string.native_model_tools_untested
        receipt.toolsPassed -> R.string.native_model_tools_passed
        else -> R.string.native_model_tools_failed
    }
    FlowRow(modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("native-settings-verification"),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(chat, tools).forEach { label ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.small) {
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEditor(state: NativeSettingsState, draft: NativeProviderDraft, model: NativeSettingsViewModel,
    name: TextFieldState, baseUrl: TextFieldState, apiKey: String, onKey: (String) -> Unit, onProtocol: () -> Unit, onManualModel: () -> Unit,
    onFetchModels: () -> Unit, onClearKey: () -> Unit, onEditModel: (NativeSettingsModelEntry) -> Unit, modifier: Modifier) {
    val enabled = state.runtime.ready && state.writable && !state.saving && !state.testing && !state.credentialPending
    val keyEnabled = state.runtime.ready && state.writable && !state.saving && !state.testing && draft.credentialWritable
    val provider = state.providers.firstOrNull { it.id == draft.id }
    val hint = provider?.defaultBaseUrl
    var replaceKey by remember(draft.id) { mutableStateOf(draft.credentialConfigured != true) }
    LazyColumn(modifier.fillMaxWidth().imePadding().navigationBarsPadding().testTag("native-settings-provider-editor"),
        contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "type") { Text(provider?.let { providerType(it) } ?: stringResource(R.string.native_settings_third_party),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        if (draft.custom) item(key = "name") {
            OutlinedTextField(state = name, enabled = enabled,
                label = { Text(stringResource(R.string.native_settings_name)) }, isError = state.error == "settings_invalid_name",
                supportingText = if (state.error == "settings_invalid_name") ({ Text(stringResource(R.string.native_settings_error_name)) }) else null, lineLimits = TextFieldLineLimits.SingleLine, shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().testTag("native-settings-provider-name"))
        }
        if (draft.custom) item(key = "protocol") {
            SettingRow(stringResource(R.string.native_settings_protocol), protocolLabel(draft.protocol), null, onProtocol,
                enabled = enabled, modifier = Modifier.testTag("native-settings-provider-protocol"))
        }
        if (draft.custom) item(key = "endpoint") {
            OutlinedTextField(state = baseUrl, enabled = enabled,
                label = { Text(stringResource(R.string.native_settings_base_url)) }, placeholder = { hint?.let { Text(it) } },
                isError = state.error == "settings_invalid_endpoint", supportingText = if (state.error == "settings_invalid_endpoint") ({ Text(stringResource(R.string.native_settings_error_endpoint)) }) else null,
                lineLimits = TextFieldLineLimits.SingleLine, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().testTag("native-settings-provider-base-url"))
        }
        if (provider?.oauthOnly == true) item(key = "oauth") {
            Text(stringResource(R.string.native_settings_existing_auth_required), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else item(key = "key") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (replaceKey || state.credentialPending) OutlinedTextField(value = apiKey, onValueChange = onKey, enabled = keyEnabled,
                    label = { Text(stringResource(R.string.native_settings_api_key)) }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true,
                    shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().testTag("native-settings-provider-key"))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (state.credentialPending) R.string.native_settings_credential_pending
                        else if (draft.credentialConfigured == true) R.string.native_settings_key_configured else R.string.native_settings_key_unconfigured),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).testTag("native-settings-credential-status"))
                    if (!replaceKey && draft.credentialConfigured == true) TextButton(onClick = { replaceKey = true }, enabled = keyEnabled) {
                        Text(stringResource(R.string.native_settings_change_key))
                    }
                    if (draft.explicitCredentialRef != null && draft.credentialConfigured == true) TextButton(onClick = onClearKey,
                        enabled = keyEnabled && !state.credentialPending, modifier = Modifier.heightIn(min = 48.dp).testTag("native-settings-clear-key")) {
                        Text(stringResource(R.string.native_settings_clear_key))
                    }
                }
            }
        }
        item(key = "models-heading") {
            Text(stringResource(R.string.native_settings_models), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        }
        if (!draft.custom) item(key = "default-models") {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().toggleable(draft.useDefaultModels, enabled, Role.Switch) { model.editProviderDefaultModels(draft.id, it) }
                    .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.native_settings_use_default_models), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = draft.useDefaultModels, onCheckedChange = null, enabled = enabled)
                }
            }
        }
        item(key = "model-actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onFetchModels, enabled = enabled && !state.discovering,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("native-settings-fetch-models")) {
                    Text(stringResource(if (state.discovering) R.string.native_settings_fetching else R.string.native_settings_fetch_models))
                }
                OutlinedButton(onClick = onManualModel, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("native-settings-manual-model")) {
                    Text(stringResource(R.string.native_settings_manual_model))
                }
            }
        }
        if (!draft.useDefaultModels && draft.models.isEmpty()) item(key = "no-models") {
            Text(stringResource(R.string.native_settings_no_models), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(model.providerModels(draft), key = { it.id }) { item ->
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(enabled = enabled, role = Role.Button) { onEditModel(item) }.padding(vertical = 8.dp)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge)
                        if (item.name != item.id) Text(item.id, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DroyIconAction(R.drawable.native_ui_close, stringResource(R.string.native_settings_remove_model, item.id), { model.removeModel(item.id) },
                        enabled = enabled, modifier = Modifier.testTag("native-settings-remove-model:${item.id}"))
                }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, supporting: String?, @DrawableRes icon: Int?, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, contained: Boolean = false) {
    Surface(modifier, shape = if (contained) MaterialTheme.shapes.extraSmall else MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        ListItem(headlineContent = { Text(title) }, supportingContent = supporting?.let { { Text(it) } },
            leadingContent = icon?.let { { DroyIcon(it, null) } },
            trailingContent = { DroyIcon(R.drawable.native_settings_chevron_right, null) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth().clickable(enabled, role = Role.Button, onClick = onClick).heightIn(min = 64.dp))
    }
}

@Composable
private fun SettingsRuntime(status: String, active: Boolean, canStart: Boolean, start: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(status.ifBlank { stringResource(R.string.native_settings_engine_not_started) }, style = MaterialTheme.typography.bodyLarge)
            if (!active && canStart) Button(onClick = start, modifier = Modifier.heightIn(min = 56.dp).testTag("native-settings-start-runtime")) {
                Text(stringResource(R.string.native_settings_start_engine))
            }
        }
    }
}

@Composable
private fun SettingsError(code: String, dismiss: () -> Unit, refresh: () -> Unit) {
    val resource = when (code) {
        "settings/conflict", "NATIVE_SETTINGS_DEFAULT_NOT_APPLIED" -> R.string.native_settings_error_conflict
        "credential/rejected" -> R.string.native_settings_error_key
        "settings_invalid_name" -> R.string.native_settings_error_name
        "settings_invalid_endpoint" -> R.string.native_settings_error_endpoint
        "settings_invalid_protocol" -> R.string.native_settings_error_protocol
        "settings_invalid_models" -> R.string.native_settings_error_models
        "settings_duplicate_model" -> R.string.native_settings_error_duplicate_model
        "settings_key_required" -> R.string.native_settings_error_key_required
        "MODEL_CREDENTIAL_REQUIRED", "MODEL_AUTH_FAILED", "MODEL_AUTHENTICATION_FAILED", "MODEL_INVALID_API_KEY", "MISSING_CREDENTIAL", "INVALID_CREDENTIAL" -> R.string.native_settings_error_auth
        "MODEL_CONFIG_CHANGED" -> R.string.native_settings_error_config_changed
        "MODEL_NOT_CONFIGURED", "INVALID_MODEL_SELECTION", "NATIVE_SETTINGS_MODEL_UNAVAILABLE" -> R.string.native_settings_error_default
        "MODEL_GREETING_FAILED", "MODEL_TEST_FAILED", "MODEL_RESPONSE_INVALID" -> R.string.native_settings_error_test
        "MODEL_TEST_BUSY" -> R.string.native_settings_error_busy
        "MODEL_QUOTA_LIMITED" -> R.string.native_settings_error_quota
        "MODEL_TEST_INTERRUPTED" -> R.string.native_settings_error_interrupted
        "settings_invalid_key" -> R.string.native_settings_error_key_format
        "llm/model-discovery-rejected", "settings_discovery_unavailable" -> R.string.native_settings_error_discovery
        "network_error", "MODEL_NETWORK_FAILED", "MODEL_TIMEOUT" -> R.string.native_settings_error_network
        "runtime_unavailable", "NATIVE_SETTINGS_NOT_READY", "NATIVE_SETTINGS_NAMESPACE_UNAVAILABLE", "SETUP_STATE_UNAVAILABLE", "service_temporarily_unavailable" -> R.string.native_settings_error_unavailable
        else -> R.string.native_settings_error_general
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("native-settings-error")) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(stringResource(resource), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_settings_close)) }
                TextButton(onClick = refresh, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_settings_refresh)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsChoiceSheet(title: String, onDismiss: () -> Unit, expanded: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().then(if (expanded) Modifier.fillMaxHeight(0.85f) else Modifier).padding(horizontal = 20.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
            content()
            Spacer(Modifier.navigationBarsPadding().height(12.dp))
        }
    }
}

@Composable
private fun ProviderChooser(providers: List<NativeSettingsProvider>, onSelect: (NativeSettingsProvider) -> Unit, onCustom: () -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = providers.filter { it.name.contains(query, true) || it.id.contains(query, true) }
    SettingsChoiceSheet(stringResource(R.string.native_settings_choose_provider), onDismiss) {
        CompanionFilledField(query, { query = it }, label = { Text(stringResource(R.string.native_settings_search_providers)) },
            leadingIcon = { DroyIcon(R.drawable.native_settings_search, null) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("native-settings-provider-search"))
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(key = "custom") {
                SegmentedListItem(onClick = onCustom,
                    shapes = ListItemDefaults.segmentedShapes(0, filtered.size + 1),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    trailingContent = { DroyIcon(R.drawable.native_settings_chevron_right, null) },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-settings-custom-provider")) {
                    Text(stringResource(R.string.native_settings_third_party))
                }
            }
            itemsIndexed(filtered, key = { _, provider -> provider.id }) { index, provider ->
                val supporting = if (provider.oauthOnly) stringResource(R.string.native_settings_existing_auth_required)
                    else provider.id.takeIf { it != provider.name }
                SegmentedListItem(onClick = { onSelect(provider) },
                    shapes = ListItemDefaults.segmentedShapes(index + 1, filtered.size + 1),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    supportingContent = supporting?.let { { Text(it) } },
                    trailingContent = { DroyIcon(R.drawable.native_settings_chevron_right, null) },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = if (supporting == null) 56.dp else 72.dp)
                        .testTag("native-settings-preset:${provider.id}")) { Text(provider.name) }
            }
        }
    }
}

@Composable
private fun DefaultModelChooser(models: List<NativeModel>, selected: NativeModel?, error: String?, saving: Boolean, onSelect: (NativeModel) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    SettingsChoiceSheet(stringResource(R.string.native_settings_default_model), onDismiss) {
        if (error != null) Text(stringResource(R.string.native_settings_error_general), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
        OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.native_settings_search_models)) },
            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large)
        LazyColumn(Modifier.weight(1f)) {
            models.filter { it.name.contains(query, true) || it.id.contains(query, true) || it.providerName.contains(query, true) }.groupBy { it.provider }.forEach { (provider, entries) ->
                item("group:$provider") { Text(entries.first().providerName, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) }
                items(entries, key = { "${it.provider}:${it.id}" }) { item ->
                    ChooserRow(item.name, item.id.takeIf { it != item.name }, selected?.let { it.provider == item.provider && it.id == item.id } == true,
                        { onSelect(item) }, Modifier.testTag("native-settings-default:${item.provider}:${item.id}"))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtocolChooser(value: String?, allowDefault: Boolean, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    val options = if (allowDefault) listOf(null) + NativeSettingsJson.protocols.toList() else NativeSettingsJson.protocols.toList()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.native_settings_protocol), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
            options.forEach { option -> ChooserRow(protocolLabel(option), null, value == option, { onSelect(option) },
                Modifier.testTag("native-settings-protocol:${option ?: "default"}")) }
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
}

@Composable
private fun protocolLabel(value: String?): String = when (value) {
    null -> stringResource(R.string.native_settings_provider_default)
    "openai-completions" -> stringResource(R.string.native_settings_openai_chat)
    "openai-responses" -> stringResource(R.string.native_settings_openai_responses)
    "anthropic-messages" -> stringResource(R.string.native_settings_claude)
    else -> value
}

@Composable
private fun ChooserRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
    Row(modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).semantics { this.selected = selected }
        .heightIn(min = 64.dp).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (selected) DroyIcon(R.drawable.native_ui_check, null)
    }
}
}

@Composable
private fun DiscoveryChooser(models: List<NativeSettingsModelEntry>, onAdd: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var selected by remember(models) { mutableStateOf(emptySet<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    SettingsChoiceSheet(stringResource(R.string.native_settings_models), onDismiss) {
        if (models.isEmpty()) Text(stringResource(R.string.native_settings_empty_discovery))
        else {
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.native_settings_search_models)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large)
            LazyColumn(Modifier.weight(1f)) {
                items(models.filter { it.name.contains(query, true) || it.id.contains(query, true) }, key = { it.id }) { item ->
                    Row(Modifier.fillMaxWidth().toggleable(item.id in selected, role = Role.Checkbox) { checked ->
                        selected = if (checked) selected + item.id else selected - item.id
                    }.heightIn(min = 64.dp).padding(vertical = 8.dp).testTag("native-settings-discovered:${item.id}"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(item.id in selected, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodyLarge)
                            if (item.name != item.id) Text(item.id, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Button(onClick = { onAdd(selected) }, enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-settings-add-discovered")) {
            Text(stringResource(R.string.native_settings_add_selected))
        }
    }
}

@Composable
private fun providerType(provider: NativeSettingsProvider): String = when {
    !provider.declared -> provider.name
    provider.protocol != null -> protocolLabel(provider.protocol)
    else -> provider.id
}
