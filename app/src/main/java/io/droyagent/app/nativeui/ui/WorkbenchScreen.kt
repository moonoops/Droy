package io.droyagent.app.nativeui.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.activity.compose.BackHandler
import io.droyagent.app.nativeui.engine.NativeEnginePermissionControl
import io.droyagent.app.nativeui.engine.NativeEngineAction
import io.droyagent.app.nativeui.engine.NativeEngineTarget
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.droyagent.app.R
import io.droyagent.app.nativeui.data.*
import io.droyagent.app.nativeui.engine.NativeEngineViewModel
import io.droyagent.app.nativeui.engine.NativeEngineSessionSelector
import io.droyagent.app.nativeui.engine.NativeEngineSkillSuggestions
import io.droyagent.app.nativeui.engine.NativeEngineContextSummary
import io.droyagent.app.nativeui.engine.NativeEngineCompactControl
import io.droyagent.app.nativeui.media.NativeDraftAttachments
import io.droyagent.app.nativeui.media.NativeLocalAttachment
import io.droyagent.app.nativeui.media.nativeInputErrorResource
import io.droyagent.app.nativeui.richtext.NativeRichText
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.text.NumberFormat
import io.droyagent.app.nativeui.presentation.LocalNativeProductPresentation
import io.droyagent.app.nativeui.presentation.NativeWelcomeHero

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkbenchScreen(state: NativeWorkbenchState, model: NativeWorkbenchViewModel, onSchedules: () -> Unit, onBack: (() -> Unit)? = null,
    onChooseAttachments: ((Boolean) -> Unit)? = null, onVoice: (() -> Unit)? = null,
    voiceVisible: Boolean = false, voicePanel: (@Composable () -> Unit)? = null,
    onOpenAttachment: ((NativeMessage, NativeMessageAttachment) -> Unit)? = null, onOpenLink: (String) -> Unit = {},
    engineModel: NativeEngineViewModel? = null, onPreviewSkill: (String, String) -> Unit = { _, _ -> },
    onCamera: (() -> Unit)? = null, onSpaces: (() -> Unit)? = null, onDraftOpened: () -> Unit = {},
    onStandardVoice: (() -> Unit)? = null,
    voiceButton: (@Composable () -> Unit)? = null, voiceStatus: (@Composable () -> Unit)? = null,
    onLoadAttachment: (suspend (NativeMessage, NativeMessageAttachment) -> ByteArray)? = null,
    onConfigureModels: (() -> Unit)? = null,
    pendingVoiceId: String? = null, pendingVoiceBubble: (@Composable () -> Unit)? = null,
) {
    NativeTaskPresentation(state.selectedSessionId)
    val presentation = LocalNativeProductPresentation.current.presentation
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val reading = rememberWorkbenchReadingSize()
    val imeVisible = WindowInsets.isImeVisible
    BackHandler(enabled = imeVisible && sheet == null) { keyboard?.hide(); focus.clearFocus() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val copied = stringResource(R.string.native_copied)
    val copyFailed = stringResource(R.string.native_copy_failed)
    val pageStates = rememberSaveableStateHolder()
    var discardDraftId by rememberSaveable { mutableStateOf<String?>(null) }
    var preparingGoal by remember { mutableStateOf(false) }
    var compactCommandTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val engineState = engineModel?.state?.collectAsStateWithLifecycle()?.value
    val engineTargetMatches = engineState != null && engineState.sessionId == state.selectedSessionId
        && (state.selectedSessionId != null || engineState.draftId == state.draftId)
    LaunchedEffect(state.selectedSessionId, state.running) {
        if (!state.running && state.selectedSessionId != null && engineModel?.state?.value?.sessionId == state.selectedSessionId)
            engineModel.refreshSession()
    }
    val commandNeedsSession = stringResource(R.string.native_engine_skills_need_session)
    val pendingConflict = stringResource(R.string.native_error_conflict)
    val defaultDraftConflict = stringResource(R.string.native_default_workspace_draft_conflict)
    LaunchedEffect(state.workspaceNotice) {
        if (state.workspaceNotice == "native_default_workspace_draft_conflict") {
            snackbar.showSnackbar(defaultDraftConflict)
            model.consumeWorkspaceNotice()
        }
    }
    fun presentSheet(name: String) { keyboard?.hide(); sheet = name }
    fun requestNew() {
        val initial = model.state.value
        if (initial.sending || initial.pendingRequestId != null) {
            scope.launch { snackbar.showSnackbar(pendingConflict) }
            return
        }
        if (initial.selectedSessionId != null) {
            if (model.openDraft(initial.selectedWorkspaceId)) onDraftOpened()
            return
        }
        if (initial.draft.isNotBlank() || initial.draftAttachments.isNotEmpty()) discardDraftId = initial.draftId
        else {
            model.createSession()
            if (model.state.value.draftId != initial.draftId) onDraftOpened()
        }
    }
    fun submit() {
        if (engineModel != null && state.draft.trim() == "/compact") {
            keyboard?.hide()
            val target = engineModel.state.value.sessionId
            if (target == null || target != state.selectedSessionId) scope.launch { snackbar.showSnackbar(commandNeedsSession) }
            else compactCommandTarget = target
        } else model.send()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("native-workbench"),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            WorkbenchBar(
                title = state.sessions.firstOrNull { onBack != null && it.id == state.selectedSessionId }
                    ?.title?.takeIf(String::isNotBlank) ?: presentation?.workbenchTitleOverride ?: stringResource(R.string.native_workbench),
                workspaceTitle = if (!state.workspaceResolved) stringResource(R.string.native_workspace_resolving)
                    else state.workspaces.firstOrNull { it.id == state.selectedWorkspaceId }?.title
                    ?: stringResource(if (state.runtime.ready && state.workspaces.isNotEmpty() && state.selectedWorkspaceId == null)
                        R.string.native_ungrouped else R.string.native_default_space),
                ready = state.runtime.ready,
                processing = state.running || state.sending, preparing = !state.runtime.ready && state.runtime.active,
                readOnly = state.workspaces.any { it.id == state.selectedWorkspaceId && it.readOnly } || state.readOnly && !state.archived,
                onUsage = { model.refreshUsage(); if (engineTargetMatches) engineModel?.refreshSession(); presentSheet("usage") },
                onNew = { requestNew() },
                canCreate = !state.sending && state.pendingRequestId == null
                    && (state.selectedSessionId == null || state.sessions.any { it.id == state.selectedSessionId }),
                onBack = onBack,
                onSchedules = { keyboard?.hide(); onSchedules() },
                onSpaces = onSpaces, onMenu = { presentSheet("menu") },
            )
        },
        bottomBar = {
            Column {
                state.error?.let { error ->
                    if (error in setOf("session/model-unavailable", "NATIVE_MODEL_UNAVAILABLE", "MISSING_CREDENTIAL", "MODEL_NOT_CONFIGURED") && onConfigureModels != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.native_work_model_setup_required), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onConfigureModels) { Text(stringResource(R.string.native_work_configure_model)) }
                            DroyIconAction(R.drawable.native_ui_close, stringResource(R.string.native_dismiss), model::dismissError)
                        }
                    } else ErrorContent(error, model::refresh, model::dismissError)
                }
                engineState?.error?.let { Text(stringResource(R.string.native_engine_failed),
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
                WorkbenchComposer(state, onDraft = model::setDraft, onSend = ::submit, onStop = model::stop,
                    onModel = { presentSheet("models") }, onAttachments = { presentSheet("attachments") },
                    onVoice = onVoice, removeAttachment = model::removeDraftAttachment, loadAttachment = model::loadLocalAttachment,
                    onReasoning = { presentSheet("reasoning") }, onContext = { model.refreshUsage(); presentSheet("usage") },
                    context = engineState?.session?.takeIf { engineTargetMatches }?.context,
                    voiceButton = voiceButton, voiceStatus = voiceStatus,
                    permissionControl = engineState?.let { current -> {
                        NativeEnginePermissionControl(current, engineModel!!::onAction,
                            interactionEnabled = engineTargetMatches && !state.readOnly && !state.sending && state.pendingRequestId == null)
                    } })
                if (voiceVisible && voicePanel != null && voiceStatus == null) Box(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) { voicePanel() }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        pageStates.SaveableStateProvider(state.selectedSessionId ?: "new-session") {
            WorkbenchReadingTheme(reading.value) { WorkbenchHistory(
                state = state, pendingVoiceId = pendingVoiceId, pendingVoiceBubble = pendingVoiceBubble,
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                onSpaces = onSpaces?.let { show -> { keyboard?.hide(); show() } },
                onDecisions = { presentSheet("decisions") },
                onStart = model::startRuntime,
                onRetry = model::refresh,
                onDismissError = model::dismissError,
                onLoadOlder = model::loadOlder,
                onReadingPosition = { position -> model.setReadingPosition(state.selectedSessionId, position) },
                onRetrySend = model::retryPending,
                onOpenAttachment = onOpenAttachment, onOpenLink = onOpenLink, onLoadAttachment = onLoadAttachment,
                onDismissKeyboard = { keyboard?.hide(); focus.clearFocus() },
                onCopy = { text ->
                    val result = try { clipboard.setText(AnnotatedString(text)); copied } catch (_: SecurityException) { copyFailed }
                    scope.launch { snackbar.showSnackbar(result) }
                },
            ) }
        }
    }
    if (discardDraftId != null) AlertDialog(onDismissRequest = { discardDraftId = null }, title = { Text(stringResource(R.string.native_discard_work_draft)) },
        confirmButton = { TextButton(onClick = {
            val target = discardDraftId
            discardDraftId = null
            val current = model.state.value
            if (current.draftId == target && current.selectedSessionId == null && !current.sending && current.pendingRequestId == null) {
                model.createSession()
                if (model.state.value.draftId != target) onDraftOpened()
            }
        }, modifier = Modifier.testTag("native-confirm-discard-work-draft")) { Text(stringResource(R.string.native_companion_discard)) } },
        dismissButton = { TextButton(onClick = { discardDraftId = null }) { Text(stringResource(R.string.native_companion_cancel)) } })
    if (compactCommandTarget != null) AlertDialog(onDismissRequest = { compactCommandTarget = null },
        title = { Text(stringResource(R.string.native_engine_compact)) }, text = { Text(stringResource(R.string.native_engine_compact_cost)) },
        confirmButton = { TextButton(onClick = {
            val target = compactCommandTarget
            compactCommandTarget = null
            val current = model.state.value
            val engine = engineModel?.state?.value
            if (engine != null && target != null && engine.sessionId == target && current.selectedSessionId == target && engine.runtime.ready && !engine.busy
                && engine.compaction?.active != true && !current.readOnly && !current.sending && current.pendingRequestId == null) {
                if (engineModel?.handleCompactCommand(current.draft) == true) model.setDraft("")
                presentSheet("compact")
            }
        }, enabled = engineState != null && engineState.sessionId == compactCommandTarget && engineState.sessionId == state.selectedSessionId
            && engineState.runtime.ready && !engineState.busy && engineState.compaction?.active != true && !state.readOnly
            && !state.sending && state.pendingRequestId == null,
            modifier = Modifier.testTag("native-confirm-compact-command")) { Text(stringResource(R.string.native_engine_confirm)) } },
        dismissButton = { TextButton(onClick = { compactCommandTarget = null }) { Text(stringResource(R.string.native_engine_cancel)) } })
    if (sheet == "attachments") ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onCamera != null) DroyMenuActionRow(R.drawable.native_ui_photo_camera, stringResource(R.string.native_work_camera)) {
                sheet = null; onCamera()
            }
            if (onChooseAttachments != null) {
                DroyMenuActionRow(R.drawable.native_companion_image, stringResource(R.string.native_work_photos)) { sheet = null; onChooseAttachments(true) }
                DroyMenuActionRow(R.drawable.native_companion_attach_file, stringResource(R.string.native_share_file)) { sheet = null; onChooseAttachments(false) }
            }
            if (engineModel != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                DroyMenuActionRow(R.drawable.native_ui_compact, stringResource(R.string.native_engine_compact),
                    enabled = engineTargetMatches && state.selectedSessionId != null && !state.readOnly) { sheet = "compact" }
                DroyMenuActionRow(R.drawable.native_ui_goal, stringResource(R.string.native_goal_title),
                    enabled = engineTargetMatches && state.runtime.ready && state.workspaceResolved && !state.readOnly && !state.sending && !preparingGoal) {
                        preparingGoal = true
                        scope.launch {
                            try {
                                val id = model.ensureSessionForGoal()
                                if (model.state.value.selectedSessionId == id) {
                                    engineModel.bindSession(id)
                                    engineModel.bindDraft(null)
                                    engineModel.refreshSession()
                                    sheet = "goal"
                                }
                            } catch (error: Exception) {
                                if (error is kotlinx.coroutines.CancellationException) throw error
                                sheet = null // The owning workbench service exposes preparation failures above the composer.
                            } finally { preparingGoal = false }
                        }
                    }
            }
        }
    }
    if (sheet == "usage") {
        ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            UsageContent(state.usage, state.loading, onClose = { sheet = null }, extra = engineState?.let { current -> {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                NativeEngineContextSummary(current.session?.takeIf { engineTargetMatches }?.context)

            } })
        }
    }
    if (sheet == "decisions") {
        ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            DecisionContent(state.decisions, state.sessions, state.selectedSessionId, state.answeringDecisionId,
                model::answerApproval, model::answerQuestion, onClose = { keyboard?.hide(); sheet = null })
        }
    }
    if (sheet == "models") {
        ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            ModelContent(state, onSelect = { model.selectModel(it); sheet = null }, onClose = { sheet = null })
        }
    }
    if (sheet == "menu") WorkbenchMenu(
        onDismiss = { sheet = null }, onTaskType = { sheet = "task-type" },
        onSpaces = { sheet = null; onSpaces?.invoke() }, onFont = { sheet = "font" },
        onUsage = { model.refreshUsage(); sheet = "usage" }, onSchedules = { sheet = null; onSchedules() },
        onVoice = onStandardVoice?.let { { sheet = null; it() } })
    if (sheet == "goal" && engineState != null) WorkbenchGoalSheet(engineState, onDismiss = { sheet = null }, onAction = engineModel!!::onAction)
    if (sheet == "font") WorkbenchFontSheet(reading.value, reading::set, onDismiss = { sheet = null })
    if (sheet == "reasoning") WorkbenchReasoningSheet(state.selectedModel, onDismiss = { sheet = null },
        onChoose = { effort -> state.selectedModel?.let { model.selectModel(it.copy(reasoningEffort = effort)) }; sheet = null })
    if (sheet == "task-type") WorkbenchTaskTypeSheet(engineState, onDismiss = { sheet = null }, onChoose = { id ->
        engineModel?.onAction(NativeEngineAction.SessionPreset(id, NativeEngineTarget(state.selectedSessionId, state.draftId)))
        sheet = null
    })
    if (sheet == "compact" && engineState != null) ModalBottomSheet(onDismissRequest = { sheet = null }) {
        NativeEngineCompactControl(engineState.copy(busy = engineState.busy || state.readOnly || state.sending || state.pendingRequestId != null),
            engineModel!!::onAction, Modifier.navigationBarsPadding().padding(24.dp))
    }

}

@Composable
private fun WorkbenchBar(title: String, workspaceTitle: String, ready: Boolean, processing: Boolean, preparing: Boolean, readOnly: Boolean, onUsage: () -> Unit, onNew: () -> Unit,
    onBack: (() -> Unit)?, onSchedules: () -> Unit, canCreate: Boolean, onSpaces: (() -> Unit)?, onMenu: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = if (onBack == null) 24.dp else 8.dp, end = 8.dp).heightIn(min = 80.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) DroyIconAction(R.drawable.native_companion_arrow_back, stringResource(R.string.native_companion_back), onBack)
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(title, style = if (onBack == null) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("native-workbench-title"))
                Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onSpaces?.invoke() }, enabled = onSpaces != null,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.weight(1f, false).height(32.dp).testTag("native-space-switch")) {
                        DroyIcon(R.drawable.native_ui_folder, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(workspaceTitle, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, false))
                        DroyIcon(R.drawable.native_ui_expand_more, stringResource(R.string.native_spaces_chats), Modifier.size(16.dp))
                    }
                    if (processing || preparing) Text(stringResource(if (preparing) R.string.native_engine_starting else R.string.native_processing),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp).testTag("native-processing-status").semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
            DroyIconAction(R.drawable.native_ui_edit_square, stringResource(R.string.native_new_session), onNew,
                Modifier.testTag("native-new-session"), enabled = ready && !readOnly && canCreate)
            DroyIconAction(R.drawable.native_companion_more_vert, stringResource(R.string.native_workbench_menu), onMenu,
                Modifier.testTag("native-workbench-menu"))
        }
    }
}

@Composable
private fun WorkbenchComposer(state: NativeWorkbenchState, onDraft: (String) -> Unit, onSend: () -> Unit, onStop: () -> Unit, onModel: () -> Unit,
    onAttachments: (() -> Unit)?, onVoice: (() -> Unit)?, removeAttachment: (String) -> Unit,
    loadAttachment: suspend (NativeLocalAttachment) -> ByteArray,
    onReasoning: () -> Unit, onContext: () -> Unit, context: io.droyagent.app.nativeui.engine.NativeEngineContext?,
    permissionControl: (@Composable () -> Unit)? = null,
    voiceButton: (@Composable () -> Unit)? = null, voiceStatus: (@Composable () -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (state.readOnly) Text(stringResource(if (state.archived) R.string.native_workspaces_archived else R.string.native_workspaces_read_only),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            if (state.draftAttachments.isNotEmpty()) Box(Modifier.heightIn(max = 96.dp).verticalScroll(rememberScrollState())) {
                NativeDraftAttachments(state.draftAttachments, !state.readOnly && !state.sending && state.pendingRequestId == null, removeAttachment, loadAttachment)
            }
            voiceStatus?.invoke()
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onModel, enabled = state.runtime.ready && !state.modelChanging && !state.readOnly,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f).height(32.dp).testTag("native-model")) {
                    DroyIcon(R.drawable.native_ui_model, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(state.selectedModel?.name ?: stringResource(R.string.native_choose_model), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onReasoning, enabled = state.runtime.ready && !state.readOnly && !state.modelChanging && state.selectedModel?.reasoningEfforts?.isNotEmpty() == true,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.height(32.dp).testTag("native-reasoning")) {
                    DroyIcon(R.drawable.native_settings_neurology, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(workbenchEffortLabel(state.selectedModel?.let { it.reasoningEffort ?: it.defaultReasoningEffort }), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                permissionControl?.invoke()
                WorkbenchContextRing(context, onContext)
            }
            val hasContent = state.draft.isNotBlank() || state.draftAttachments.isNotEmpty()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
                onAttachments?.let { DroyIconAction(R.drawable.native_schedule_add, stringResource(R.string.native_companion_attachments), it,
                    modifier = Modifier.testTag("native-composer-attachments"), enabled = !state.readOnly && !state.sending) }
                TextField(value = state.draft, onValueChange = onDraft, enabled = !state.readOnly,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp, max = 144.dp).testTag("native-composer"),
                    placeholder = { Text(stringResource(R.string.native_enter_task)) }, shape = MaterialTheme.shapes.extraLarge,
                    maxLines = 4, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                if (voiceButton != null) voiceButton() else onVoice?.let { DroyIconAction(R.drawable.native_companion_mic,
                    stringResource(R.string.native_voice_input_title), it, enabled = !state.readOnly && !state.sending) }
                val stopping = state.running || state.sending
                DroyPrimaryIconAction(if (stopping) R.drawable.native_ui_stop else R.drawable.native_ui_send,
                    stringResource(if (stopping) R.string.native_stop else R.string.native_send),
                    state.runtime.ready && state.workspaceResolved && !state.readOnly && (stopping || hasContent && !state.sending && state.pendingRequestId == null),
                    Modifier.testTag(if (stopping) "native-stop" else "native-send"), if (stopping) onStop else onSend)
            }
        }
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WorkbenchHistory(
    state: NativeWorkbenchState,
    modifier: Modifier,
    onSpaces: (() -> Unit)?,
    onDecisions: () -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onLoadOlder: () -> Unit,
    onReadingPosition: (NativeReadingPosition) -> Unit,
    onRetrySend: () -> Unit,
    onCopy: (String) -> Unit,
    onOpenAttachment: ((NativeMessage, NativeMessageAttachment) -> Unit)?,
    onOpenLink: (String) -> Unit,
    onLoadAttachment: (suspend (NativeMessage, NativeMessageAttachment) -> ByteArray)?,
    onDismissKeyboard: () -> Unit,
    pendingVoiceId: String?, pendingVoiceBubble: (@Composable () -> Unit)?,
) {
    val audioOwner = remember(state.selectedSessionId) { mutableStateOf<String?>(null) }
    val visibleMessages = remember(state.messages) { state.messages.filter(::workbenchMessageVisible) }
    val initialPosition = state.readingPosition
    val prefixCount = (if (!state.runtime.ready && !state.runtime.active) 1 else 0) + (if (state.hasMore) 1 else 0)
    val initialIndex = if (initialPosition?.followingTail != false) (visibleMessages.size + prefixCount).coerceAtLeast(0)
        else visibleMessages.indexOfFirst { it.id == initialPosition.key }.takeIf { it >= 0 }?.plus(prefixCount) ?: 0
    val list = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex,
        initialFirstVisibleItemScrollOffset = initialPosition?.takeIf { !it.followingTail }?.offset ?: 0)
    var followingTail by rememberSaveable { mutableStateOf(true) }
    var restored by remember { mutableStateOf(false) }
    var positioning by remember { mutableStateOf(false) }
    val currentState by rememberUpdatedState(state)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val entrances = remember(state.draftId) { MessageEntranceTracker() }
    val entering = entrances.update(visibleMessages.map { it.itemIdentity() } + listOfNotNull(pendingVoiceId?.let { "request:$it" }),
        visibleMessages.filter { state.sending && it.kind == "pending" && !it.failed &&
            it.itemIdentity() == "request:${state.pendingRequestId}" }.map { it.itemIdentity() }.toSet(),
        state.runtime.ready && !state.loading && lifecycleState.isAtLeast(Lifecycle.State.RESUMED))
    val savePosition by rememberUpdatedState(onReadingPosition)
    val scope = rememberCoroutineScope()
    fun readingPosition(): NativeReadingPosition? = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }?.let { item ->
        (item.key as? String)?.let { key -> NativeReadingPosition(
            currentState.messages.firstOrNull { it.itemIdentity() == key }?.id ?: key, list.firstVisibleItemScrollOffset, followingTail) }
    }
    LaunchedEffect(state.loading, visibleMessages.isNotEmpty(), restored) {
        if (!restored && !state.loading && visibleMessages.isNotEmpty()) {
            val position = state.readingPosition
            followingTail = position?.followingTail ?: true
            if (position != null && !followingTail) {
                val message = visibleMessages.indexOfFirst { it.id == position.key }
                val prefix = (if (!state.runtime.ready && !state.runtime.active) 1 else 0) + (if (state.hasMore) 1 else 0)
                val index = if (message >= 0) prefix + message else if (position.key == "earlier" && state.hasMore) prefix - 1 else 0
                positioning = true
                try { list.scrollToItem(index, position.offset) } finally { positioning = false }
            }
            restored = true
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { if (restored && !currentState.loading) readingPosition() else null }
            .distinctUntilChanged().debounce(200).collect { position -> if (position != null) savePosition(position) }
    }
    LaunchedEffect(state.historyCursor, state.unreadSessionIds, restored, followingTail, lifecycleState) {
        // Navigation can finish after the list settles. Submit another rendered receipt
        // when this entry resumes; the ViewModel still checks session visibility and sequence.
        if (!lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        withFrameNanos { }
        if (restored && !currentState.loading && followingTail && !list.canScrollForward)
            readingPosition()?.let(savePosition)
    }
    DisposableEffect(list) { onDispose { if (restored) readingPosition()?.let(savePosition) } }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.distinctUntilChanged().collect { (scrolling, forward) ->
            if (scrolling && !positioning) followingTail = !forward
        }
    }
    // A new outgoing request explicitly resumes the conversation at its tail, even
    // when the user was reading older messages. Receiving a reply alone must not.
    var observedRequest by remember { mutableStateOf(state.pendingRequestId) }
    LaunchedEffect(state.pendingRequestId, state.sending) {
        val request = state.pendingRequestId
        if (state.sending && request != null && request != observedRequest) followingTail = true
        observedRequest = request
    }
    LaunchedEffect(pendingVoiceId) { if (pendingVoiceId != null) followingTail = true }
    val currentPendingVoice by rememberUpdatedState(pendingVoiceId)
    LaunchedEffect(list) {
        snapshotFlow {
            val latest = currentState.messages.lastOrNull { workbenchMessageVisible(it) }
            listOf(restored, followingTail, latest?.id, latest?.text, currentPendingVoice,
                list.layoutInfo.viewportEndOffset, list.layoutInfo.totalItemsCount)
        }.distinctUntilChanged().collect {
            if (restored && followingTail && list.layoutInfo.totalItemsCount > 0 && !list.isScrollInProgress) {
                positioning = true
                try {
                    withFrameNanos { }
                    list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
                } finally { positioning = false }
            }
        }
    }
    Column(modifier) {
        val unreadElsewhere = state.sessions.count { it.id != state.selectedSessionId && it.id in state.unreadSessionIds && !it.readOnly }
        if (unreadElsewhere > 0 && onSpaces != null) TextButton(onClick = onSpaces,
            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("native-workbench-unread-results")) {
            DroyIcon(R.drawable.native_ui_chat_bubble, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.native_workbench_unread_results, unreadElsewhere))
        }
        if (state.decisions.isNotEmpty()) {
            FilledTonalButton(onClick = onDecisions,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)
                    .heightIn(min = 56.dp).testTag("native-pending-decisions")
                    .semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(stringResource(R.string.native_pending_count, state.decisions.size), modifier = Modifier.weight(1f))
                DroyIcon(R.drawable.native_ui_expand_more, null)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().dismissKeyboardOnUnconsumedTap(onDismissKeyboard).testTag("native-history"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!state.runtime.ready && !state.runtime.active) item(key = "runtime") {
                    RuntimeContent(state, onStart)
                }
                if (state.hasMore) item(key = "earlier") {
                    TextButton(onClick = onLoadOlder, enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-load-older")) {
                        Text(stringResource(R.string.native_load_older))
                    }
                }
                if (state.runtime.ready && !state.loading && visibleMessages.isEmpty() && pendingVoiceId == null) item(key = "empty") {
                    NativeWelcomeHero()
                }
                items(visibleMessages, key = { it.itemIdentity() }, contentType = { it.kind }) { message ->
                    CompositionLocalProvider(LocalWorkbenchAudioOwner provides audioOwner) {
                    NativeMessageCard(message, onCopy, onOpenLink, onOpenAttachment,
                        onRetry = onRetrySend.takeUnless { state.readOnly }, onLoadAttachment = onLoadAttachment,
                        entering = message.itemIdentity() in entering, onClick = onDismissKeyboard,
                        modifier = Modifier)
                    }
                }
                if (pendingVoiceId != null && pendingVoiceBubble != null) item(key = "request:$pendingVoiceId") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { pendingVoiceBubble() }
                }
                item(key = "tail") { Spacer(Modifier.height(1.dp)) }

            }
            if (!followingTail && visibleMessages.isNotEmpty()) {
                SmallFloatingActionButton(onClick = {
                    followingTail = true
                    scope.launch {
                        positioning = true
                        try { val last = list.layoutInfo.totalItemsCount - 1; if (last >= 0) list.animateScrollToItem(last) }
                        finally { positioning = false }
                    }
                }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    DroyIcon(R.drawable.native_ui_arrow_downward, stringResource(R.string.native_new_messages))
                }
            }
        }
    }
}

@Composable
private fun RuntimeContent(state: NativeWorkbenchState, onStart: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(state.runtime.status.ifBlank {
                stringResourceFallback(state.runtime.active)
            }, style = MaterialTheme.typography.bodyLarge)
            if (state.runtime.canStart) {
                Button(onClick = onStart, modifier = Modifier.heightIn(min = 56.dp).testTag("native-start-runtime")) {
                    Text(stringResource(R.string.native_start_engine))
                }
            } else if (state.runtime.active) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun stringResourceFallback(active: Boolean): String = stringResource(
    if (active) R.string.native_engine_starting else R.string.native_engine_unavailable)

@Composable
private fun ErrorContent(code: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val message = when (code) {
        "runtime_unavailable" -> R.string.native_engine_unavailable
        "network_error", "stream_disconnected" -> R.string.native_error_network
        "malformed_response" -> R.string.native_error_response
        "stream_gap" -> R.string.native_error_gap
        "response_too_large" -> R.string.native_error_large
        "pending_send_conflict" -> R.string.native_error_conflict
        "storage_error" -> R.string.native_error_storage
        "session_identity_conflict" -> R.string.native_error_identity
        "session/not-found" -> R.string.native_conversation_unavailable
        "workspace/session-archived" -> R.string.native_workspaces_restore_first
        "session/model-unavailable", "NATIVE_MODEL_UNAVAILABLE" -> R.string.native_model_unavailable
        "NATIVE_DECISION_ENDED" -> R.string.native_decision_ended
        "NATIVE_DECISION_PENDING" -> R.string.native_answer_waiting
        "NATIVE_DECISION_CONTEXT_REQUIRED" -> R.string.native_context_required
        "COMPANION_SESSION_MANAGED", "COMPANION_WORKSPACE_MANAGED" -> R.string.native_companion_work
        "runtime_start_failed" -> R.string.native_engine_start_failed
        "service_temporarily_unavailable" -> R.string.native_service_unavailable
        else -> if (code.startsWith("native_")) nativeInputErrorResource(code) else R.string.native_error_generic
    }
    DroyErrorNotice(stringResource(message), onDismiss, onRetry, Modifier.testTag("native-error"))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NativeMessageCard(message: NativeMessage, onCopy: (String) -> Unit, onOpenLink: (String) -> Unit,
    onOpenAttachment: ((NativeMessage, NativeMessageAttachment) -> Unit)? = null, onRetry: (() -> Unit)? = null,
    onLoadAttachment: (suspend (NativeMessage, NativeMessageAttachment) -> ByteArray)? = null,
    modifier: Modifier = Modifier,
    entering: Boolean = false,
    onClick: () -> Unit = {},
) {
    if (!workbenchMessageVisible(message)) return
    val user = message.role == "user"
    val process = message.kind == "context" || message.kind == "reasoning" || message.toolName != null || message.kind.startsWith("tool")
    var actions by remember(message.id) { mutableStateOf(false) }
    var transcript by rememberSaveable(message.id) { mutableStateOf(false) }
    val voice = message.attachments.size == 1 && message.attachments.first().isPlayableAudio()
    Column(modifier.then(messageEntranceModifier(entering)).fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = MaterialTheme.shapes.large,
            color = if (user) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (user) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("native-message-bubble:${message.id}").widthIn(max = if (user) 340.dp else 560.dp).combinedClickable(onClick = onClick, onLongClickLabel = stringResource(R.string.native_copy_reply), onLongClick = { actions = true })) {
            Column(Modifier.padding(horizontal = if (voice) 0.dp else 16.dp, vertical = if (voice) 0.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!process && !voice && message.text.isNotEmpty()) {
                    if (user) SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
                    else NativeRichText(message.text, onCopy, onOpenLink)
                }
                MessageAttachments(message, onOpenAttachment, onLoadAttachment)
            }
        }
        }
        if (message.failed && onRetry != null) TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.native_retry), color = MaterialTheme.colorScheme.error)
        }
        if (voice && message.text.isNotBlank()) {
            if (transcript) Text(message.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
        }
        Box {
            DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                if (voice && message.text.isNotBlank()) DropdownMenuItem(text = { Text(stringResource(R.string.native_work_transcript)) },
                    leadingIcon = { DroyIcon(R.drawable.native_ui_description, null) },
                    onClick = { actions = false; transcript = !transcript })
                if (message.text.isNotBlank()) DropdownMenuItem(text = { Text(stringResource(R.string.native_copy_reply)) },
                    leadingIcon = { DroyIcon(R.drawable.native_ui_content_copy, null) },
                    onClick = { actions = false; onCopy(message.text) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpandableMessage(message: NativeMessage, onOpenAttachment: ((NativeMessage, NativeMessageAttachment) -> Unit)?) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val context = message.kind == "context"
    val title = if (context) stringResource(when (message.sourceForm) {
        "instructions" -> R.string.native_context_instructions
        "catalog" -> R.string.native_context_catalog
        "snapshot" -> R.string.native_context_snapshot
        "notice" -> R.string.native_context_notice
        "relay" -> R.string.native_context_relay
        "recall" -> R.string.native_context_recall
        else -> R.string.native_context
    }) else message.toolName ?: stringResource(if (message.kind == "reasoning") R.string.native_reasoning else R.string.native_tool)
    Surface(shape = if (context) MaterialTheme.shapes.medium else MaterialTheme.shapes.large,
        color = if (context) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer,
        modifier = if (context) Modifier.testTag("native-context") else Modifier) {
        Column(Modifier.fillMaxWidth().animateContentSize(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec())) {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }
                .heightIn(min = if (context) 48.dp else 56.dp)
                .padding(horizontal = if (context) 12.dp else 16.dp, vertical = if (context) 8.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DroyIcon(R.drawable.native_ui_description, null)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (context && !message.summary.isNullOrBlank()) Text(message.summary,
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DroyIcon(if (expanded) R.drawable.native_ui_expand_less else R.drawable.native_ui_expand_more,
                    stringResource(if (expanded) R.string.native_collapse else R.string.native_expand))
            }
            if (expanded) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (message.text.isNotEmpty()) SelectionContainer {
                    Text(message.text, style = MaterialTheme.typography.bodyMedium,
                        modifier = if (context) Modifier.testTag("native-context-body") else Modifier)
                }
                MessageAttachments(message, onOpenAttachment)
            }
        }
    }
}

@Composable
private fun UsageContent(usage: NativeUsage?, loading: Boolean, onClose: () -> Unit, extra: (@Composable () -> Unit)? = null) {
    val locale = LocalContext.current.resources.configuration.locales[0]
    val number = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val decimal = remember(locale) { NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 } }
    val unavailable = stringResource(R.string.native_not_reported)
    fun value(v: Long?) = v?.let(number::format) ?: "—"
    Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).navigationBarsPadding()) {
        // Keep the title outside the nested scrolling body: dragging this header
        // always moves the sheet, while the metrics scroll only after expansion.
        Text(stringResource(R.string.native_usage), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth().testTag("native-usage-drag-region").padding(start = 24.dp, end = 24.dp, bottom = 16.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        if (usage != null) {
        Text(stringResource(R.string.native_session_usage), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.native_total_tokens), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value(usage?.totalTokens), style = MaterialTheme.typography.displayMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UsageMetric(usage?.tokensPerSecond?.let(decimal::format) ?: "—", stringResource(R.string.native_token_speed), Modifier.weight(1f))
            val percent = usage?.cacheHitRate?.let { NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 1 }.format(it) } ?: "—"
            UsageMetric(percent, stringResource(R.string.native_cache_hit), Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        UsageRow(stringResource(R.string.native_input_tokens), value(usage?.inputTokens))
        UsageRow(stringResource(R.string.native_output_tokens), value(usage?.outputTokens))
        UsageRow(stringResource(R.string.native_cache_read), usage?.cacheReadTokens?.let(number::format) ?: if (usage != null) unavailable else "—")
        UsageRow(stringResource(R.string.native_first_delta), usage?.firstTokenMs?.let { stringResource(R.string.native_seconds_value, it / 1000) } ?: if (usage != null) unavailable else "—")
        if (usage?.partial == true) Text(stringResource(R.string.native_partial_usage), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        }
        if (usage == null && !loading) Text(stringResource(R.string.native_usage_empty), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
        extra?.invoke()
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).padding(bottom = 8.dp)) { Text(stringResource(R.string.native_close)) }
        }
    }
}

@Composable
private fun UsageMetric(value: String, label: String, modifier: Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}


@Composable
private fun ModelContent(state: NativeWorkbenchState, onSelect: (NativeModel) -> Unit, onClose: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    val choices = remember(state.models, state.selectedModel, search) {
        state.models.filter { search.isBlank() || it.name.contains(search, true) || it.id.contains(search, true) || it.providerName.contains(search, true) }
            .sortedWith(compareByDescending<NativeModel> { it.id == state.selectedModel?.id && it.provider == state.selectedModel?.provider }
                .thenBy { it.providerName }.thenBy { it.name })
    }
    LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().imePadding(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
        item { Text(stringResource(R.string.native_choose_model), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp)) }
        item { OutlinedTextField(search, { search = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            label = { Text(stringResource(R.string.native_work_search_models)) }) }
        items(choices, key = { "${it.provider}:${it.id}" }) { item ->
            val selected = state.selectedModel?.let { it.provider == item.provider && it.id == item.id } == true
            ListItem(headlineContent = { Text(item.name) }, supportingContent = { Text(item.providerName) },
                trailingContent = { if (selected) DroyIcon(R.drawable.native_ui_check, stringResource(R.string.native_current)) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth().clickable(enabled = !state.modelChanging && !state.readOnly) { onSelect(item) }.heightIn(min = 64.dp))
        }
        item { TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.native_close)) } }
    }
}
