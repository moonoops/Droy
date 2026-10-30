package io.droyagent.app.nativeui.workspaces

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIconAction
import kotlinx.coroutines.launch

@Composable
fun NativeWorkspacesScreen(
    state: NativeWorkspacesState,
    model: NativeWorkspacesViewModel,
    currentWorkspaceId: String?,
    currentSessionId: String?,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onNewInSpace: (String?) -> Unit,
    hasWorkspaceDraft: (String) -> Boolean,
    onOpenWorkspaceDraft: (String) -> Unit,
    modifier: Modifier = Modifier,
    unreadSessionIds: Set<String> = emptySet(),
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val copied = stringResource(R.string.native_copied)
    val copyFailed = stringResource(R.string.native_copy_failed)
    var archiveMenu by rememberSaveable { mutableStateOf(false) }
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeTitle by rememberSaveable { mutableStateOf("") }
    var draftBlockerId by rememberSaveable { mutableStateOf<String?>(null) }
    var forkId by rememberSaveable { mutableStateOf<String?>(null) }
    var discardEditorKey by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val editor = state.editor
    val editorKey = editor?.let { "${it.kind}:${it.targetId}" }
    val enabled = state.runtime.ready && !state.busy && state.uncertain == null
    fun action(value: NativeWorkspacesAction) { focus.clearFocus(); keyboard?.hide(); model.onAction(value) }
    fun writableWorkspace(id: String): NativeWorkspaceRow? = model.state.value.catalog?.workspaces?.firstOrNull { it.id == id && !it.readOnly }
    fun requestRemove(id: String) {
        val current = model.state.value
        if (!current.runtime.ready || current.busy || current.uncertain != null) return
        val workspace = writableWorkspace(id) ?: return
        if (hasWorkspaceDraft(id)) {
            removeId = null
            draftBlockerId = id
        } else {
            removeId = id
            removeTitle = workspace.title
        }
    }
    fun goBack() {
        focus.clearFocus(); keyboard?.hide()
        when {
            state.uncertain != null || state.busy -> onBack()
            editor != null && editor.text != editor.original -> discardEditorKey = editorKey
            editor != null -> action(NativeWorkspacesAction.CloseEditor)
            state.directoryOpen -> action(NativeWorkspacesAction.CloseDirectory)
            else -> onBack()
        }
    }
    BackHandler(enabled = state.editor != null || state.directoryOpen) { goBack() }
    LaunchedEffect(editorKey, state.directoryOpen) { focus.clearFocus(); keyboard?.hide() }
    LaunchedEffect(state.catalog, removeId, draftBlockerId) {
        val catalog = state.catalog ?: return@LaunchedEffect
        if (removeId != null && catalog.workspaces.none { it.id == removeId && !it.readOnly }) removeId = null
        if (draftBlockerId != null && catalog.workspaces.none { it.id == draftBlockerId && !it.readOnly }) draftBlockerId = null
    }
    LaunchedEffect(state.createdSessionId, state.catalog, state.loading, resumed) {
        val id = state.createdSessionId
        if (id != null && resumed && !state.loading && state.catalog?.sessions?.any { it.id == id } == true) {
            model.onAction(NativeWorkspacesAction.ConsumeCreatedSession(id))
            onOpenSession(id)
        }
    }
    val title = stringResource(if (state.directoryOpen) R.string.native_workspaces_choose_folder else R.string.native_spaces_chats)
    Scaffold(modifier.fillMaxSize().testTag("native-workspaces"), contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.surface, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                DroyIconAction(R.drawable.native_companion_arrow_back, stringResource(R.string.native_companion_back), ::goBack)
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                if (state.directoryOpen) DroyIconAction(R.drawable.native_schedule_add, stringResource(R.string.native_workspaces_new_folder),
                    { action(NativeWorkspacesAction.BeginDirectoryCreate) }, enabled = enabled && state.directory != null && !state.directoryLoading,
                    modifier = Modifier.testTag("native-workspaces-new-folder"), tonal = true)
                else {
                    Box {
                        DroyIconAction(R.drawable.native_companion_more_vert, stringResource(R.string.native_workbench_menu), { archiveMenu = true })
                        DropdownMenu(archiveMenu, onDismissRequest = { archiveMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.native_workspaces_archived)) },
                                trailingIcon = { if (state.showArchived) io.droyagent.app.nativeui.ui.DroyIcon(R.drawable.native_ui_check, null) },
                                onClick = { archiveMenu = false; action(NativeWorkspacesAction.Archived(!state.showArchived)) },
                                modifier = Modifier.testTag("native-workspaces-archived"))
                        }
                    }
                    DroyIconAction(R.drawable.native_schedule_add, stringResource(R.string.native_workspaces_add),
                        { action(NativeWorkspacesAction.BeginWorkspaceCreate) }, enabled = enabled,
                        modifier = Modifier.testTag("native-workspaces-add"), tonal = true)
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (!state.runtime.ready) Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.native_engine_unavailable), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (state.runtime.canStart) TextButton(onClick = model::startRuntime, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_start_engine)) }
            }
            if (state.uncertain != null) WorkspaceUncertainNotice(state, model::onAction)
            else if (state.error != null && editor == null) WorkspaceNotice(state.error, { action(NativeWorkspacesAction.DismissError) })
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("native-workspaces-loading"))
            if (state.directoryOpen) NativeWorkspaceDirectoryContent(state, model::onAction, Modifier.weight(1f))
            else NativeWorkspaceCatalogContent(state, currentWorkspaceId, currentSessionId,
                unreadSessionIds = unreadSessionIds,
                onAction = model::onAction, onOpenSession = { id -> keyboard?.hide(); onOpenSession(id) },
                onNewInSpace = { id -> keyboard?.hide(); onNewInSpace(id) },
                onRemoveWorkspace = { row -> requestRemove(row.id) },
                onForkSession = { forkId = it }, onCopySession = { id ->
                    val result = try { clipboard.setText(AnnotatedString(id)); copied } catch (_: SecurityException) { copyFailed }
                    scope.launch { snackbar.showSnackbar(result) }
                }, modifier = Modifier.weight(1f))
        }
    }
    if (editor != null && state.uncertain == null) AlertDialog(onDismissRequest = ::goBack, title = {
        Text(stringResource(when (editor.kind) {
            NativeWorkspaceEditorKind.SESSION_TITLE -> R.string.native_workspaces_rename_session
            NativeWorkspaceEditorKind.WORKSPACE_TITLE -> R.string.native_workspaces_rename
            NativeWorkspaceEditorKind.WORKSPACE_NAME -> R.string.native_workspaces_add
            NativeWorkspaceEditorKind.DIRECTORY_NAME -> R.string.native_workspaces_new_folder
        }))
    }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(editor.text, { model.onAction(NativeWorkspacesAction.EditText(it)) },
                enabled = enabled, singleLine = true, label = { Text(stringResource(R.string.native_workspaces_name)) },
                modifier = Modifier.fillMaxWidth().testTag("native-workspaces-editor-text"))
            if (editor.kind == NativeWorkspaceEditorKind.WORKSPACE_NAME && state.catalog?.directoryBrowsingAvailable == true) {
                TextButton(onClick = { action(NativeWorkspacesAction.CloseEditor); action(NativeWorkspacesAction.BrowseDirectory()) }, enabled = enabled) {
                    Text(stringResource(R.string.native_workspaces_existing_folder))
                }
            }
            if (state.error != null) Text(workspaceErrorText(state.error), color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { action(NativeWorkspacesAction.SaveEditor) }, enabled = enabled && editor.text.isNotBlank(),
        modifier = Modifier.heightIn(min = 48.dp).testTag("native-workspaces-editor-save")) { Text(stringResource(R.string.native_workspaces_save)) } },
        dismissButton = { TextButton(onClick = ::goBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_companion_cancel)) } })
    if (discardEditorKey != null) AlertDialog(onDismissRequest = { discardEditorKey = null }, title = { Text(stringResource(R.string.native_workspaces_discard)) },
        confirmButton = { TextButton(onClick = {
            val target = discardEditorKey; discardEditorKey = null
            if (target == editorKey && !state.busy && state.uncertain == null) action(NativeWorkspacesAction.CloseEditor)
        }) { Text(stringResource(R.string.native_companion_discard)) } }, dismissButton = {
            TextButton(onClick = { discardEditorKey = null }) { Text(stringResource(R.string.native_companion_cancel)) }
        })
    if (removeId != null) AlertDialog(onDismissRequest = { removeId = null },
        title = { Text(stringResource(R.string.native_workspaces_remove_title, removeTitle)) },
        text = { Text(stringResource(R.string.native_workspaces_remove_scope)) }, confirmButton = {
            TextButton(onClick = {
                val id = removeId; removeId = null
                val current = model.state.value
                if (id != null && current.runtime.ready && !current.busy && current.uncertain == null && writableWorkspace(id) != null) {
                    if (hasWorkspaceDraft(id)) draftBlockerId = id
                    else action(NativeWorkspacesAction.RemoveWorkspace(id))
                }
            }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("native-workspaces-confirm-remove")) { Text(stringResource(R.string.native_workspaces_remove)) }
        }, dismissButton = { TextButton(onClick = { removeId = null }) { Text(stringResource(R.string.native_companion_cancel)) } })
    val blockedWorkspaceId = draftBlockerId
    if (blockedWorkspaceId != null) AlertDialog(onDismissRequest = { draftBlockerId = null },
        modifier = Modifier.testTag("native-workspaces-draft-blocker"),
        title = { Text(stringResource(R.string.native_workspaces_draft_first)) }, confirmButton = {
            TextButton(onClick = {
                if (draftBlockerId != blockedWorkspaceId) return@TextButton
                draftBlockerId = null
                val current = model.state.value
                if (!current.busy && current.uncertain == null && writableWorkspace(blockedWorkspaceId) != null && hasWorkspaceDraft(blockedWorkspaceId)) {
                    focus.clearFocus(); keyboard?.hide()
                    onOpenWorkspaceDraft(blockedWorkspaceId)
                }
            }, enabled = !state.busy && state.uncertain == null && state.catalog?.workspaces?.any { it.id == blockedWorkspaceId && !it.readOnly } == true,
                modifier = Modifier.heightIn(min = 48.dp).testTag("native-workspaces-open-draft")) { Text(stringResource(R.string.native_workspaces_open_draft)) }
        }, dismissButton = { TextButton(onClick = { draftBlockerId = null }) { Text(stringResource(R.string.native_companion_cancel)) } })
    val pendingForkId = forkId
    if (pendingForkId != null) AlertDialog(onDismissRequest = { forkId = null }, title = { Text(stringResource(R.string.native_workspaces_fork)) },
        text = { SessionConfirmationContent(state, pendingForkId, R.string.native_workspaces_fork_scope) }, confirmButton = {
            TextButton(onClick = {
                val id = forkId; forkId = null
                if (id != null && enabled && state.catalog?.sessions?.any { it.id == id && !it.readOnly } == true) action(NativeWorkspacesAction.Fork(id))
            }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("native-workspaces-confirm-fork")) { Text(stringResource(R.string.native_workspaces_fork)) }
        }, dismissButton = { TextButton(onClick = { forkId = null }) { Text(stringResource(R.string.native_companion_cancel)) } })
    state.archiveActiveSessionId?.let { id ->
        AlertDialog(onDismissRequest = { action(NativeWorkspacesAction.DismissArchiveActive) }, title = { Text(stringResource(R.string.native_workspaces_stop_archive)) },
            text = { SessionConfirmationContent(state, id, R.string.native_workspaces_active_scope) }, confirmButton = {
                TextButton(onClick = { action(NativeWorkspacesAction.Archive(id, stopActivity = true)) }, enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("native-workspaces-confirm-stop-archive")) { Text(stringResource(R.string.native_workspaces_stop_archive)) }
            }, dismissButton = { TextButton(onClick = { action(NativeWorkspacesAction.DismissArchiveActive) }) { Text(stringResource(R.string.native_companion_cancel)) } })
    }
    state.forkMigrationSessionId?.let { id ->
        AlertDialog(onDismissRequest = { action(NativeWorkspacesAction.DismissForkMigration) }, title = { Text(stringResource(R.string.native_workspaces_migrate_fork)) },
            text = { SessionConfirmationContent(state, id, R.string.native_workspaces_migration_scope) }, confirmButton = {
                TextButton(onClick = { action(NativeWorkspacesAction.Fork(id, allowMigration = true)) }, enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("native-workspaces-confirm-migration")) { Text(stringResource(R.string.native_workspaces_continue)) }
            }, dismissButton = { TextButton(onClick = { action(NativeWorkspacesAction.DismissForkMigration) }) { Text(stringResource(R.string.native_companion_cancel)) } })
    }
}

@Composable
private fun SessionConfirmationContent(state: NativeWorkspacesState, id: String, message: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(state.catalog?.sessions?.firstOrNull { it.id == id }?.title?.takeIf(String::isNotBlank) ?: id,
            style = MaterialTheme.typography.titleMedium)
        Text(stringResource(message))
    }
}

@Composable
private fun WorkspaceUncertainNotice(state: NativeWorkspacesState, action: (NativeWorkspacesAction) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("native-workspaces-uncertain")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.native_workspaces_unknown), color = MaterialTheme.colorScheme.onErrorContainer)
            Text(stringResource(R.string.native_workspaces_review_scope), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = { action(NativeWorkspacesAction.CheckUncertain) }, enabled = state.runtime.ready && !state.busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("native-workspaces-check-result")) { Text(stringResource(R.string.native_workspaces_check_result)) }
            if (state.uncertain?.checked == true) TextButton(onClick = { action(NativeWorkspacesAction.AcknowledgeUncertain) }, enabled = !state.busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("native-workspaces-acknowledge")) { Text(stringResource(R.string.native_workspaces_reviewed)) }
        }
    }
}

@Composable
internal fun WorkspaceNotice(code: String, onDismiss: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(workspaceErrorText(code), color = MaterialTheme.colorScheme.onErrorContainer)
            onDismiss?.let { dismiss -> TextButton(onClick = dismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text(stringResource(R.string.native_dismiss)) } }
        }
    }
}

@Composable
internal fun workspaceErrorText(code: String): String = stringResource(when (code) {
    "runtime_unavailable", "service_temporarily_unavailable", "NATIVE_WORKSPACES_NOT_READY" -> R.string.native_service_unavailable
    "COMPANION_SESSION_MANAGED", "COMPANION_WORKSPACE_MANAGED", "NATIVE_WORKSPACES_SUBAGENT" -> R.string.native_workspaces_read_only
    "NATIVE_WORKSPACES_REQUEST_CONFLICT", "workspace/move-invalid" -> R.string.native_workspaces_changed
    "NATIVE_WORKSPACES_STORAGE" -> R.string.native_workspaces_storage
    "NATIVE_WORKSPACES_UNCONFIRMED" -> R.string.native_workspaces_unknown
    "NATIVE_WORKSPACES_BUSY", "session/agent-busy", "workspace/session-active" -> R.string.native_workspaces_busy
    "workspace/name-conflict" -> R.string.native_workspaces_name_exists
    "session/title-invalid" -> R.string.native_workspaces_name_invalid
    "workspace/not-found", "workspace/session-not-found", "session/not-found" -> R.string.native_workspaces_item_unavailable
    "workspace/session-archived" -> R.string.native_workspaces_restore_first
    "session/fork-unavailable" -> R.string.native_workspaces_fork_unavailable
    "session/migration-required" -> R.string.native_workspaces_migration_scope
    "directory-picker/exists" -> R.string.native_workspaces_folder_exists
    "directory-picker/unavailable" -> R.string.native_workspaces_folder_unavailable
    else -> R.string.native_workspaces_failed
})
