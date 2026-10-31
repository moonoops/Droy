package io.droyagent.app.nativeui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.droyagent.app.nativeui.companion.ui.CompanionFilledField
import io.droyagent.app.nativeui.ui.droyNavigationMotion
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.pageTransition
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    var permissionsRoot by rememberSaveable { mutableStateOf(true) }
    val pageStates = rememberSaveableStateHolder()
    val navigationMotion = droyNavigationMotion()
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
    val childPage = modelEditing || route?.step == 5 && !permissionsRoot
    BackHandler(enabled = !childPage) { if (!state.busy && !settingsState.saving) previous() }
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
        else -> R.string.native_setup_welcome
    })
    val progress by animateFloatAsState((step ?: 1) / 5f,
        MaterialTheme.motionScheme.fastEffectsSpec(), label = "setupProgress")
    Scaffold(modifier.fillMaxSize().testTag("native-onboarding"), containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0), snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                    if (!childPage) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (step != null && step > 1) DroyIconAction(R.drawable.native_setup_arrow_back,
                                stringResource(R.string.native_setup_back), ::previous,
                                enabled = !state.busy && !settingsState.saving, modifier = Modifier.testTag("native-setup-back"))
                            Text(heading, style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f).padding(horizontal = 16.dp))
                            if (step != null) {
                                val description = stringResource(R.string.native_setup_step_a11y, step, 5)
                                Text(stringResource(R.string.native_setup_step, step, 5), style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = description })
                            }
                        }
                        if (step != null) LinearProgressIndicator(progress = { progress },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
                    }
                }
            }
        }, bottomBar = {
            if (route != null && !childPage) {
                val nextEnabled = !state.busy && when (step) {
                    1 -> true
                    2 -> route.environment.ready
                    3 -> route.environment.ready && state.liveFresh && state.live?.passed("model") == true && state.live?.passed("modelTools") == true
                        && !settingsState.testing && !settingsState.saving && !settingsState.discovering
                    4 -> route.environment.ready && !companionState.profileSaving && !mediaBusy &&
                        NativeSetupJson.validName(state.draft.name.trim(), false) && state.draft.avatarUrl?.let(NativeSetupJson::validAvatar) == true &&
                        NativeSetupJson.validBio(state.draft.bio.orEmpty().trim())
                    5 -> route.permissionPolicy != null && !permissionsState.queue.active && !permissionsState.root.running
                    else -> false
                }
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Button(onClick = {
                        closeKeyboard(); leavePermissions()
                        if (step == 5) model.complete() else model.next(requireNotNull(step), companionModel::saveUserProfileForSetup)
                    }, enabled = nextEnabled, shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                            .padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 56.dp)
                            .testTag(if (step == 5) "native-setup-finish" else "native-setup-next")) {
                        Text(stringResource(when {
                            state.busy && step == 4 -> R.string.native_setup_saving
                            state.busy -> R.string.native_setup_checking
                            step == 1 -> R.string.native_setup_begin
                            step == 5 -> R.string.native_setup_finish
                            else -> R.string.native_setup_next
                        }))
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
                AnimatedContent(targetState = route.step, modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(), transitionSpec = {
                    navigationMotion.pageTransition(targetState > initialState, targetState)
                }, label = "setupStep") { destination ->
                    pageStates.SaveableStateProvider(destination) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                            when (destination) {
                                1 -> SetupWelcome(Modifier.fillMaxSize())
                                2 -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { SetupEnvironment(state, model, compact = false) }
                                3 -> NativeModelSettingsScreen(settingsState, settingsModel, onBack = ::previous,
                                    onRootChanged = { modelsRoot = it }, modifier = Modifier.fillMaxSize(), embedded = true)
                                4 -> SetupIdentity(state, model, companionModel, onChooseAvatar, mediaBusy, Modifier.fillMaxSize())
                                5 -> NativePermissionsScreen(permissionsState, onAction = { action ->
                                    onPermissionAction(if (action is NativePermissionAction.SelectPolicy) action.copy(initialSetup = true) else action)
                                }, onBack = ::previous, modifier = Modifier.fillMaxSize(), embedded = true,
                                    onRootChanged = { permissionsRoot = it })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupWelcome(modifier: Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Surface(shape = RoundedCornerShape(topStart = 64.dp, topEnd = 64.dp, bottomStart = 32.dp, bottomEnd = 32.dp),
            color = MaterialTheme.colorScheme.primaryContainer) {
            Image(painterResource(R.drawable.droy_companion), contentDescription = null,
                modifier = Modifier.size(184.dp).padding(24.dp))
        }
        Text(stringResource(R.string.native_setup_brand), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SetupEnvironment(state: NativeOnboardingState, model: NativeOnboardingViewModel, compact: Boolean) {
    val environment = state.route?.environment ?: return
    Surface(shape = MaterialTheme.shapes.extraLarge,
        color = if (environment.ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(if (compact) 16.dp else 24.dp).testTag("native-setup-environment")) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (environment.ready) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primary) {
                    Box(Modifier.size(if (compact) 48.dp else 72.dp), contentAlignment = Alignment.Center) {
                        DroyIcon(R.drawable.native_ui_check, null, Modifier.size(32.dp))
                    }
                }
                Text(stringResource(R.string.native_setup_ready), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.native_setup_ready_next), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer, textAlign = TextAlign.Center)
            } else {
                Text(environment.status.ifBlank { stringResource(if (environment.active) R.string.native_engine_starting else R.string.native_engine_unavailable) },
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                if (environment.canStart) Button(onClick = model::startRuntime, shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-setup-start-runtime")) {
                    Text(stringResource(R.string.native_setup_start_runtime))
                } else if (environment.active) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
            FilledTonalButton(onClick = { chooseAvatar?.invoke(null, model::avatar) }, shapes = ButtonDefaults.shapes(), enabled = avatarEnabled && chooseAvatar != null && state.route?.environment?.ready == true,
                modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
                    DroyIcon(R.drawable.native_ui_photo_camera, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.native_setup_avatar))
                }
        }
        val tooLong = name.codePointCount(0, name.length) > 80 || name.toByteArray(Charsets.UTF_8).size > 320
        CompanionFilledField(name, model::name, Modifier.fillMaxWidth().testTag("native-setup-name"), enabled = enabled, singleLine = true,
            label = { Text(stringResource(R.string.native_setup_name)) }, isError = tooLong,
            supportingText = if (tooLong) ({ Text(stringResource(R.string.native_setup_name_long)) }) else null)
        val bio = state.draft.bio.orEmpty()
        val bioTooLong = bio.codePointCount(0, bio.length) > 2000
        CompanionFilledField(bio, model::bio, Modifier.fillMaxWidth().testTag("native-setup-bio"),
            enabled = enabled, label = { Text(stringResource(R.string.native_setup_bio)) }, minLines = 3, maxLines = 6,
            isError = bioTooLong,
            supportingText = if (bioTooLong) ({ Text(stringResource(R.string.native_companion_user_bio_too_long)) }) else null)
    }
}

@Composable
internal fun SetupError(code: String, onRetry: () -> Unit) {
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
