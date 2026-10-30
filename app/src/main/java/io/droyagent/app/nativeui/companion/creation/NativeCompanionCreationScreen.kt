package io.droyagent.app.nativeui.companion.creation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.companion.ui.CompanionAvatar
import io.droyagent.app.nativeui.companion.ui.CompanionModelSelection
import io.droyagent.app.nativeui.ui.DroyIcon
import io.droyagent.app.nativeui.ui.DroyIconAction
import io.droyagent.app.nativeui.ui.droyNavigationMotion

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NativeCompanionCreationScreen(
    state: NativeCreationState,
    model: NativeCompanionCreationViewModel,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    onChooseImage: (NativeCreationImageKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    var switchTemplate by remember { mutableStateOf<NativeCreationTemplate?>(null) }
    var exitDraft by rememberSaveable { mutableStateOf(false) }
    var cancel by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val motion = droyNavigationMotion()
    val editorStates = rememberSaveableStateHolder()
    val createdCallback by rememberUpdatedState(onCreated)
    val currentEditor = state.editor
    LaunchedEffect(state.createdId, state.busy) {
        val id = state.createdId
        if (id != null && !state.busy) { createdCallback(id); model.acknowledgeCreated(id) }
    }
    LaunchedEffect(state.page) { focus.clearFocus(); keyboard?.hide() }
    fun back() {
        if (imeVisible) { focus.clearFocus(); keyboard?.hide(); return }
        focus.clearFocus(); keyboard?.hide()
        when (state.page) {
            NativeCreationPage.TEMPLATES -> onBack()
            NativeCreationPage.EDITOR -> {
                if (!state.canEdit || state.image != null) return
                if (state.editor?.dirty == true || state.journal?.draftId != null) exitDraft = true
                else model.leaveEditor(false) { }
            }
            NativeCreationPage.BACKGROUND -> model.page(NativeCreationPage.INTERACTION)
            else -> model.page(NativeCreationPage.EDITOR)
        }
    }
    BackHandler(enabled = state.page != NativeCreationPage.TEMPLATES) { back() }
    val title = stringResource(when (state.page) {
        NativeCreationPage.TEMPLATES -> R.string.native_companion_creation_templates
        NativeCreationPage.EDITOR -> R.string.native_companion_creation_title
        NativeCreationPage.PERSONALITY -> R.string.native_companion_personality
        NativeCreationPage.MODEL -> R.string.native_companion_creation_title
        NativeCreationPage.INTERACTION -> R.string.native_companion_interaction
        NativeCreationPage.BACKGROUND -> R.string.native_companion_background
    })
    Scaffold(modifier.fillMaxSize().testTag("native-companion-creation"), containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            if (state.page !in setOf(NativeCreationPage.PERSONALITY, NativeCreationPage.INTERACTION, NativeCreationPage.BACKGROUND)) Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DroyIconAction(R.drawable.native_companion_arrow_back, stringResource(R.string.native_companion_back), ::back)
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (state.page == NativeCreationPage.EDITOR) TextButton(onClick = ::back, enabled = state.canEdit && state.image == null) {
                        Text(stringResource(R.string.native_companion_cancel))
                    }
                }
            }
        }, bottomBar = {
            if (state.page == NativeCreationPage.EDITOR) Surface(color = MaterialTheme.colorScheme.surface) {
                Button(onClick = { focus.clearFocus(); keyboard?.hide(); model.create() }, enabled = state.canCreate,
                    modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                        .padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 56.dp).testTag("native-creation-create")) {
                    Text(stringResource(if (state.busy && state.creating) R.string.native_companion_creation_creating else R.string.native_companion_creation_create))
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (!state.runtime.ready) CreationCard(Modifier.padding(16.dp)) {
                Text(state.runtime.status.ifBlank { stringResource(if (state.runtime.active) R.string.native_engine_starting else R.string.native_engine_unavailable) })
                if (state.runtime.canStart) Button(onClick = model::startRuntime, modifier = Modifier.heightIn(min = 56.dp).testTag("native-creation-start-runtime")) { Text(stringResource(R.string.native_start_engine)) }
                else if (state.runtime.active) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.restoreError) CreationError("creation_restore_error", model::refresh)
            else if (state.error != null && state.error != "creation_unconfirmed" && state.error != "COMPANION_DRAFT_CLOSED") CreationError(state.error, model::dismissError)
            if (state.creating && state.createdId == null && !state.busy) CreationPending(state, model) { cancel = true }
            if (state.draftState == "cancelled") CreationCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.native_companion_creation_draft_closed))
                TextButton(onClick = model::resetClosed, enabled = state.checked && !state.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_companion_creation_edit_again)) }
            }
            if (state.image != null || state.imageError != null) CreationImageStatus(state, model)
            if (state.busy || !state.restored) LinearProgressIndicator(Modifier.fillMaxWidth())
            AnimatedContent(targetState = if (state.page == NativeCreationPage.MODEL) NativeCreationPage.EDITOR else state.page, modifier = Modifier.weight(1f).fillMaxWidth(), label = "companionCreationPage",
                transitionSpec = {
                    val back = targetState.ordinal < initialState.ordinal
                    ((if (back) motion.popEnter else motion.enter) togetherWith (if (back) motion.popExit else motion.exit)).using(null)
                }) { page -> when (page) {
                NativeCreationPage.TEMPLATES -> CreationTemplates(state, model, Modifier.fillMaxSize()) { template ->
                    if (currentEditor != null && currentEditor.dirty && currentEditor.template.id != template.id) switchTemplate = template else model.selectTemplate(template)
                }
                NativeCreationPage.EDITOR -> CreationEditor(state, model, onChooseImage, Modifier.fillMaxSize())
                NativeCreationPage.PERSONALITY -> CreationPersonality(state, model, Modifier.fillMaxSize())
                NativeCreationPage.MODEL -> CreationModels(state, model, Modifier.fillMaxSize())
                NativeCreationPage.INTERACTION -> editorStates.SaveableStateProvider("interaction:${state.journal?.requestId}") { CreationInteraction(state, model, Modifier.fillMaxSize()) { editorStates.removeState("interaction:${state.journal?.requestId}") } }
                NativeCreationPage.BACKGROUND -> CreationBackground(state, model, onChooseImage, Modifier.fillMaxSize())
            } }
        }
    }
    if (state.page == NativeCreationPage.MODEL && currentEditor != null) ModalBottomSheet(
        onDismissRequest = { model.page(NativeCreationPage.EDITOR) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Text(stringResource(R.string.native_companion_model), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        state.modelsError?.let { CreationError(it, model::refresh, refresh = true) }
        CompanionModelSelection(state.models, currentEditor.model, state.defaultModel, state.canEdit,
            { model.edit(currentEditor.copy(model = it)); model.page(NativeCreationPage.EDITOR) }, Modifier.fillMaxWidth().heightIn(max = 640.dp).navigationBarsPadding())
    }
    if (exitDraft) AlertDialog(onDismissRequest = { exitDraft = false },
        title = { Text(stringResource(R.string.native_companion_creation_save_draft_title)) },
        confirmButton = { TextButton(onClick = { model.leaveEditor(true) { exitDraft = false } }, enabled = !state.busy) {
            Text(stringResource(R.string.native_companion_creation_save_draft))
        } }, dismissButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { model.leaveEditor(false) { exitDraft = false } }, enabled = !state.busy) {
                    Text(stringResource(R.string.native_companion_creation_discard_draft))
                }
                TextButton(onClick = { exitDraft = false }, enabled = !state.busy) { Text(stringResource(R.string.native_companion_keep_editing)) }
            }
        })
    switchTemplate?.let { template -> AlertDialog(onDismissRequest = { switchTemplate = null }, title = { Text(stringResource(R.string.native_companion_creation_switch_title)) },
        text = { Text(stringResource(R.string.native_companion_creation_switch_scope)) }, confirmButton = {
            TextButton(onClick = { switchTemplate = null; model.selectTemplate(template) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_companion_creation_switch)) }
        }, dismissButton = { TextButton(onClick = { switchTemplate = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_companion_cancel)) } }) }
    if (cancel) AlertDialog(onDismissRequest = { cancel = false }, title = { Text(stringResource(R.string.native_companion_creation_cancel_title)) },
        text = { Text(stringResource(R.string.native_companion_creation_cancel_scope)) },
        confirmButton = { TextButton(onClick = { cancel = false; model.cancelCreation() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_companion_creation_cancel_creation)) } },
        dismissButton = { TextButton(onClick = { cancel = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_companion_cancel)) } })
}

@Composable
internal fun CreationCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}
@Composable
internal fun CreationError(code: String, action: (() -> Unit)? = null, refresh: Boolean = false) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(16.dp)) {
            Text(creationMessage(code), color = MaterialTheme.colorScheme.onErrorContainer)
            if (action != null) TextButton(onClick = action, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(if (refresh || code == "creation_restore_error") R.string.native_refresh else R.string.native_dismiss)) }
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreationPending(state: NativeCreationState, model: NativeCompanionCreationViewModel, cancel: () -> Unit) {
    CreationCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("native-creation-unconfirmed")) {
        Text(stringResource(R.string.native_companion_creation_unconfirmed))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = model::refresh, enabled = state.runtime.ready && !state.busy && !state.checking, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_companion_creation_check)) }
            if (state.checked && state.draftState == "open") {
                if (state.journal?.pendingCancel != true) Button(onClick = model::retryCreate, enabled = state.runtime.ready && !state.busy && !state.checking,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("native-creation-retry")) { Text(stringResource(R.string.native_companion_creation_retry_create)) }
                TextButton(onClick = cancel, enabled = state.runtime.ready && !state.busy && !state.checking, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.native_companion_creation_cancel_creation))
                }
            }
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreationImageStatus(state: NativeCreationState, model: NativeCompanionCreationViewModel) {
    CreationCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("native-creation-image-state")) {
        val image = state.image
        if (image != null) Text(stringResource(when (image.phase) {
            NativeCreationImagePhase.PICKING -> R.string.native_companion_creation_image_selecting
            NativeCreationImagePhase.IMPORTING -> R.string.native_companion_creation_image_importing
            NativeCreationImagePhase.UPLOADING -> R.string.native_companion_creation_image_uploading
            NativeCreationImagePhase.INTERRUPTED -> R.string.native_companion_creation_image_interrupted
            NativeCreationImagePhase.UPLOADED -> R.string.native_companion_creation_image_uploaded
        }))
        state.imageError?.let { Text(creationMessage(it), color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (image != null && image.phase in setOf(NativeCreationImagePhase.INTERRUPTED, NativeCreationImagePhase.UPLOADED)) TextButton(onClick = model::retryImage,
                enabled = (state.runtime.ready || image.phase == NativeCreationImagePhase.UPLOADED) && !state.busy && !state.restoreError, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (image.phase == NativeCreationImagePhase.UPLOADED) R.string.native_retry else R.string.native_companion_creation_image_retry)) }
            if (image != null && !state.busy && image.phase !in setOf(NativeCreationImagePhase.IMPORTING, NativeCreationImagePhase.UPLOADING)) TextButton(onClick = model::discardImage, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.native_companion_creation_image_discard))
            }
        }
    }
}
@Composable
private fun CreationTemplates(state: NativeCreationState, model: NativeCompanionCreationViewModel, modifier: Modifier, choose: (NativeCreationTemplate) -> Unit) {
    LazyColumn(modifier.fillMaxWidth().navigationBarsPadding().testTag("native-creation-templates"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.catalogError != null) item("error") { CreationError(state.catalogError, model::refresh, refresh = true) }
        if (state.editor != null) item("resume") { FilledTonalButton(onClick = { model.page(NativeCreationPage.EDITOR) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-creation-resume")) { Text(stringResource(R.string.native_companion_creation_resume)) } }
        items(state.templates, key = { it.id }) { template ->
            CreationCard(Modifier.clickable(enabled = state.runtime.ready && state.canEdit && state.image == null, role = Role.Button) { choose(template) }.testTag("native-creation-template-${template.id}")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CompanionAvatar(template.name, template.avatarUrl, model::loadImage, size = 56.dp)
                    Text(template.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    DroyIcon(R.drawable.native_companion_chevron_right, null)
                }
            }
        }
        if (state.catalogLoaded && !state.loading && state.catalogError == null && state.templates.isEmpty()) item("empty") { Text(stringResource(R.string.native_companion_creation_no_templates)) }
    }
}

@Composable
internal fun creationMessage(code: String): String = stringResource(when (code) {
    "COMPANION_NAME_CONFLICT" -> R.string.native_companion_creation_name_conflict
    "COMPANION_INVALID_INPUT", "COMPANION_INVALID_ID" -> R.string.native_companion_creation_invalid_details
    "creation_restore_error" -> R.string.native_companion_creation_restore_error
    "creation_unconfirmed", "COMPANION_CREATION_CONFLICT" -> R.string.native_companion_creation_unconfirmed
    "COMPANION_DRAFT_CLOSED" -> R.string.native_companion_creation_draft_closed
    "COMPANION_DRAFT_NOT_FOUND" -> R.string.native_companion_creation_draft_unknown
    "creation_media_busy" -> R.string.native_companion_creation_media_busy
    "creation_image_interrupted" -> R.string.native_companion_creation_image_interrupted
    "creation_image_unavailable", "native_attachment_changed", "COMPANION_IMAGE_REQUIRED", "INVALID_IMAGE", "UNSUPPORTED_IMAGE_TYPE" -> R.string.native_companion_creation_image_unavailable
    "COMPANION_ATTACHMENT_SCOPE" -> R.string.native_companion_creation_image_scope
    "creation_picker_unavailable" -> R.string.native_companion_creation_image_pick_unavailable
    "creation_choose_images_again" -> R.string.native_companion_creation_image_reselect
    "network_error", "runtime_unavailable" -> R.string.native_companion_error_network
    "runtime_start_failed" -> R.string.native_engine_start_failed
    "storage_error" -> R.string.native_companion_error_storage
    "malformed_response" -> R.string.native_companion_error_response
    "COMPANION_MODEL_REQUIRED" -> R.string.native_companion_error_model
    "COMPANION_TEMPLATE_NOT_FOUND" -> R.string.native_companion_creation_not_ready
    else -> R.string.native_companion_error_generic
})
