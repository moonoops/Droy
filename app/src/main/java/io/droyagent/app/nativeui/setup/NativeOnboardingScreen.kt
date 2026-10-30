package io.droyagent.app.nativeui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.companion.data.NativeCompanionState
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.companion.ui.CompanionAvatar
import io.droyagent.app.nativeui.companion.ui.CompanionAvatarPicker
import io.droyagent.app.nativeui.permissions.NativePermissionAction
import io.droyagent.app.nativeui.permissions.NativePermissionsScreen
import io.droyagent.app.nativeui.permissions.NativePermissionsState
import io.droyagent.app.nativeui.settings.NativeModelSettingsScreen
import io.droyagent.app.nativeui.settings.NativeSettingsState
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import io.droyagent.app.nativeui.ui.DroyIcon
import io.droyagent.app.nativeui.ui.DroyIconAction

@Composable
fun NativeOnboardingScreen(
    state: NativeOnboardingState,
    model: NativeOnboardingViewModel,
    settingsState: NativeSettingsState,
    settingsModel: NativeSettingsViewModel,
    companionState: NativeCompanionState,
    companionModel: NativeCompanionViewModel,
    permissionsState: NativePermissionsState,
    onPermissionAction: (NativePermissionAction) -> Unit,
    onPermissionScreenVisible: (Boolean) -> Unit,
    onCompleted: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    onChooseAvatar: CompanionAvatarPicker? = null,
    uploadedAvatarUrl: String? = null,
    onAvatarConsumed: () -> Unit = {},
    mediaError: String? = null,
    onRetryMedia: (() -> Unit)? = null,
    onDismissMediaError: () -> Unit = {},
    mediaBusy: Boolean = false,
) {
    val route = state.route
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var modelsRoot by rememberSaveable { mutableStateOf(true) }
    val consumeAvatar by rememberUpdatedState(onAvatarConsumed)
    val permissionVisibility by rememberUpdatedState(onPermissionScreenVisible)
    val snackbar = remember { SnackbarHostState() }
    val retryMedia by rememberUpdatedState(onRetryMedia)
    val dismissMedia by rememberUpdatedState(onDismissMediaError)
    val retryLabel = stringResource(R.string.native_retry)
    fun closeKeyboard() { focus.clearFocus(); keyboard?.hide() }
    fun leavePermissions() { if (route?.step == 5) onPermissionAction(NativePermissionAction.CancelQueue) }
    fun previous() {
        closeKeyboard(); leavePermissions()
        if (route == null || route.step == 1) onExit() else model.previous(route.step)
    }
    DisposableEffect(route?.required == true && route.step == 5) {
        permissionVisibility(route?.required == true && route.step == 5)
        onDispose { permissionVisibility(false) }
    }
    LaunchedEffect(route?.completed) { if (route?.completed == true) { closeKeyboard(); onCompleted() } }
    LaunchedEffect(route?.step, route?.required, uploadedAvatarUrl) {
        if (route?.required == true && route.step == 4 && !uploadedAvatarUrl.isNullOrBlank()) { model.avatar(uploadedAvatarUrl); consumeAvatar() }
    }
    LaunchedEffect(route?.step) {
        closeKeyboard()
        if (route?.required == true && route.step >= 3) model.refresh()
    }
    LaunchedEffect(mediaError) {
        val error = mediaError ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(error, actionLabel = if (retryMedia != null) retryLabel else null, withDismissAction = true)
        dismissMedia()
        if (result == SnackbarResult.ActionPerformed) retryMedia?.invoke()
    }
    val modelEditing = route?.step == 3 && (!modelsRoot || settingsState.editor != null)
    BackHandler(enabled = !modelEditing) { if (!state.busy && !settingsState.saving) previous() }
    if (route?.completed == true) {
        Box(modifier.fillMaxSize().testTag("native-setup-completed"))
        return
    }
    val step = route?.step
    val heading = stringResource(when (step) {
        2 -> R.string.native_setup_environment
        3 -> R.string.native_setup_models
        4 -> R.string.native_setup_identity
        5 -> R.string.native_setup_permissions
        6 -> R.string.native_setup_checks
        else -> R.string.native_setup_welcome
    })
    Scaffold(modifier.fillMaxSize().testTag("native-onboarding"), containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0), snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = if (modelEditing) 40.dp else 64.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (!modelEditing) DroyIconAction(R.drawable.native_setup_arrow_back, stringResource(R.string.native_setup_back), ::previous,
                            enabled = !state.busy && !settingsState.saving, modifier = Modifier.testTag("native-setup-back"))
                        if (!modelEditing) Text(heading, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        else Spacer(Modifier.weight(1f))
                        if (step != null) {
                            val description = stringResource(R.string.native_setup_step_a11y, step, 6)
                            Text(stringResource(R.string.native_setup_step, step, 6), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = description })
                        }
                    }
                    if (step != null) LinearProgressIndicator(progress = { step / 6f }, modifier = Modifier.fillMaxWidth())
                }
            }
        }, bottomBar = {
            if (route != null && !modelEditing) {
                val nextEnabled = !state.busy && when (step) {
                    1 -> true
                    2 -> route.environment.ready
                    3 -> route.environment.ready && state.liveFresh && state.live?.passed("model") == true && state.live?.passed("modelTools") == true
                        && !settingsState.testing && !settingsState.saving && !settingsState.discovering
                    4 -> route.environment.ready && !companionState.profileSaving && !mediaBusy &&
                        NativeSetupJson.validName(state.draft.name.trim(), false) && state.draft.avatarUrl?.let(NativeSetupJson::validAvatar) == true
                    5 -> route.permissionPolicy != null
                    6 -> state.liveFresh && state.live?.canComplete == true && !state.checking
                    else -> false
                }
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                        .padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (step != 1) OutlinedButton(onClick = ::previous, enabled = !state.busy && !settingsState.saving,
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("native-setup-previous")) { Text(stringResource(R.string.native_setup_previous)) }
                        Button(onClick = {
                            closeKeyboard(); leavePermissions()
                            if (step == 6) model.complete() else model.next(requireNotNull(step), companionModel::saveUserProfileForSetup)
                        }, enabled = nextEnabled,
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag(if (step == 6) "native-setup-finish" else "native-setup-next")) {
                            Text(stringResource(when {
                                state.busy && step == 4 -> R.string.native_setup_saving
                                state.busy -> R.string.native_setup_checking
                                step == 1 -> R.string.native_setup_begin
                                step == 6 -> R.string.native_setup_finish
                                else -> R.string.native_setup_next
                            }))
                        }
                    }
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (state.localError != null) SetupError(state.localError, model::refresh)
            if (state.error != null) SetupError(state.error, model::refresh)
            if (state.statusError != null && state.statusError != state.error) SetupError(state.statusError, model::refresh)
            if (route == null) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (state.localLoading) CircularProgressIndicator()
                else Text(stringResource(R.string.native_setup_error_state), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (!route.environment.ready && step != 1 && step != 2) SetupEnvironment(state, model, compact = true)
                when (step) {
                    1 -> SetupWelcome(Modifier.weight(1f))
                    2 -> Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) { SetupEnvironment(state, model, compact = false) }
                    3 -> NativeModelSettingsScreen(settingsState, settingsModel, onBack = ::previous,
                        onRootChanged = { modelsRoot = it }, modifier = Modifier.weight(1f), embedded = true)
                    4 -> SetupIdentity(state, model, companionModel, onChooseAvatar, mediaBusy, Modifier.weight(1f))
                    5 -> NativePermissionsScreen(permissionsState, onAction = { action ->
                        onPermissionAction(if (action is NativePermissionAction.SelectPolicy) action.copy(initialSetup = true) else action)
                    }, onBack = ::previous, modifier = Modifier.weight(1f), embedded = true)
                    6 -> SetupChecks(state, model, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SetupWelcome(modifier: Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(32.dp)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) { DroyIcon(R.drawable.native_setup_waving_hand, null, Modifier.size(48.dp)) }
        }
        Text(stringResource(R.string.native_setup_brand), style = MaterialTheme.typography.displayLarge)
    }
}

@Composable
private fun SetupEnvironment(state: NativeOnboardingState, model: NativeOnboardingViewModel, compact: Boolean) {
    val environment = state.route?.environment ?: return
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(if (compact) 16.dp else 24.dp).testTag("native-setup-environment")) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.native_setup_environment), style = MaterialTheme.typography.titleMedium)
            Text(environment.status.ifBlank { stringResource(if (environment.active) R.string.native_engine_starting else R.string.native_engine_unavailable) },
                style = MaterialTheme.typography.bodyLarge)
            if (environment.ready) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DroyIcon(R.drawable.native_ui_check, null)
                Text(stringResource(R.string.native_setup_ready), color = MaterialTheme.colorScheme.primary)
            } else if (environment.canStart) Button(onClick = model::startRuntime, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-setup-start-runtime")) {
                Text(stringResource(R.string.native_setup_start_runtime))
            } else if (environment.active) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SetupIdentity(state: NativeOnboardingState, model: NativeOnboardingViewModel, companionModel: NativeCompanionViewModel,
    chooseAvatar: CompanionAvatarPicker?, mediaBusy: Boolean, modifier: Modifier) {
    val name = state.draft.name
    val url = state.draft.avatarUrl
    val enabled = !state.busy
    val avatarEnabled = enabled && !mediaBusy
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (url != null) CompanionAvatar(name, url, companionModel::loadImage, size = 96.dp,
                onClick = { chooseAvatar?.invoke(null, model::avatar) }, label = stringResource(R.string.native_setup_avatar),
                enabled = avatarEnabled && chooseAvatar != null && state.route?.environment?.ready == true,
                modifier = Modifier.testTag("native-setup-avatar"))
            else Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) { DroyIcon(R.drawable.native_setup_person, null) }
            }
            TextButton(onClick = { chooseAvatar?.invoke(null, model::avatar) }, enabled = avatarEnabled && chooseAvatar != null && state.route?.environment?.ready == true,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_setup_avatar)) }
        }
        val tooLong = name.codePointCount(0, name.length) > 80 || name.toByteArray(Charsets.UTF_8).size > 320
        OutlinedTextField(name, model::name, Modifier.fillMaxWidth().testTag("native-setup-name"), enabled = enabled, singleLine = true,
            label = { Text(stringResource(R.string.native_setup_name)) }, isError = tooLong,
            supportingText = if (tooLong) ({ Text(stringResource(R.string.native_setup_name_long)) }) else null,
            shape = MaterialTheme.shapes.medium)
    }
}

@Composable
private fun SetupChecks(state: NativeOnboardingState, model: NativeOnboardingViewModel, modifier: Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
        for (id in listOf("runtime", "model", "modelTools", "identity", "permissionPolicy")) {
            val check = state.live?.checks?.firstOrNull { it.id == id }
            val passed = if (state.liveFresh) check?.passed else null
            val label = stringResource(when (id) {
                "runtime" -> R.string.native_setup_check_runtime
                "model" -> R.string.native_setup_check_model
                "modelTools" -> R.string.native_setup_check_model_tools
                "identity" -> R.string.native_setup_check_identity
                else -> R.string.native_setup_check_policy
            })
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(16.dp).testTag("native-setup-check:$id"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (passed != null) DroyIcon(if (passed) R.drawable.native_ui_check else R.drawable.native_ui_close, null)
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(when {
                            passed == null -> R.string.native_setup_unknown
                            passed -> R.string.native_setup_passed
                            check?.required == false -> R.string.native_setup_optional
                            else -> R.string.native_setup_incomplete
                        }), style = MaterialTheme.typography.bodyMedium,
                            color = if (passed == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        OutlinedButton(onClick = model::refresh, enabled = !state.busy && !state.checking,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-setup-refresh-checks")) { Text(stringResource(R.string.native_setup_refresh)) }
    }
}

@Composable
private fun SetupError(code: String, onRetry: () -> Unit) {
    val text = stringResource(when (code) {
        "storage_error" -> R.string.native_setup_error_storage
        "setup_state_unavailable", "SETUP_STATE_UNAVAILABLE", "NATIVE_SETUP_STATE_UNAVAILABLE" -> R.string.native_setup_error_state
        "malformed_response", "INVALID_NATIVE_SETUP_REQUEST" -> R.string.native_setup_error_response
        "network_error", "runtime_unavailable", "service_temporarily_unavailable" -> R.string.native_setup_error_network
        "setup_environment_required" -> R.string.native_setup_not_ready
        "setup_model_required", "MODEL_CONFIG_CHANGED", "MODEL_NOT_CONFIGURED" -> R.string.native_setup_error_model
        "setup_policy_required" -> R.string.native_setup_error_policy
        "setup_identity_invalid", "NATIVE_SETUP_IDENTITY_REQUIRED" -> R.string.native_setup_error_identity
        "NATIVE_SETUP_IDENTITY_CHANGED" -> R.string.native_setup_error_identity_changed
        "NATIVE_SETUP_IDENTITY_UNAVAILABLE" -> R.string.native_setup_error_identity_save
        "NATIVE_SETUP_NOT_READY", "SETUP_NOT_READY" -> R.string.native_setup_error_incomplete
        "COMPANION_BUSY" -> R.string.native_setup_error_busy
        "setup_result_unconfirmed" -> R.string.native_setup_error_uncertain
        "runtime_start_failed" -> R.string.native_engine_start_failed
        else -> R.string.native_setup_error_generic
    })
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).testTag("native-setup-error")) {
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text(stringResource(R.string.native_setup_retry)) }
        }
    }
}
