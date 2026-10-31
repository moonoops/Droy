package io.droyagent.app.nativeui.workspaces

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import io.droyagent.app.R
import io.droyagent.app.nativeui.data.NativeSession
import io.droyagent.app.nativeui.data.NativeWorkbenchState
import io.droyagent.app.nativeui.data.NativeWorkspace
import io.droyagent.app.nativeui.data.spaceGroups
import io.droyagent.app.nativeui.ui.DroyIcon
import io.droyagent.app.nativeui.ui.DroyIconAction

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NativeWorkspaceCatalogContent(
    state: NativeWorkspacesState,
    currentWorkspaceId: String?,
    currentSessionId: String?,
    onAction: (NativeWorkspacesAction) -> Unit,
    onOpenSession: (String) -> Unit,
    onNewInSpace: (String?) -> Unit,
    onRemoveWorkspace: (NativeWorkspaceRow) -> Unit,
    onForkSession: (String) -> Unit,
    onCopySession: (String) -> Unit,
    modifier: Modifier = Modifier,
    unreadSessionIds: Set<String> = emptySet(),
) {
    val catalog = state.catalog
    val list = rememberLazyListState()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val placement = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    var expanded by rememberSaveable { mutableStateOf(arrayListOf(currentWorkspaceId ?: "ungrouped")) }
    val enabled = state.runtime.ready && !state.busy && state.uncertain == null
    val archived = remember(catalog) { catalog?.archivedSessionIds.orEmpty().toSet() }
    val sessionRows = remember(catalog, state.showArchived) { catalog?.sessions.orEmpty().filter { (it.id in archived) == state.showArchived } }
    val sessionsById = remember(sessionRows) { sessionRows.associateBy { it.id } }
    val ungrouped = stringResource(R.string.native_ungrouped)
    val groups = remember(catalog, sessionRows, ungrouped) { NativeWorkbenchState(
            workspaces = catalog?.workspaces.orEmpty().map { NativeWorkspace(it.id, it.title, it.sessionIds, it.readOnly) },
            sessions = sessionRows.map { NativeSession(it.id, it.title.orEmpty(), it.running, it.workspaceId, it.readOnly) },
            defaultWorkspaceId = catalog?.defaultWorkspaceId,
        ).spaceGroups(ungrouped)
    }
    val visibleGroups = if (catalog == null) emptyList() else if (state.showArchived) groups.filter { it.sessions.isNotEmpty() } else groups
    var expandedUnread by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    LaunchedEffect(groups, unreadSessionIds) {
        val knownUnread = groups.flatMap { it.sessions }.filter { it.id in unreadSessionIds && !it.readOnly }.map { it.id }.toSet()
        val newUnread = knownUnread - expandedUnread.toSet()
        if (newUnread.isNotEmpty()) {
            expanded = ArrayList((expanded + groups.filter { group -> group.sessions.any { it.id in newUnread && !it.readOnly } }
                .map { it.id ?: "ungrouped" }).distinct())
            expandedUnread = ArrayList(knownUnread)
        }
    }
    val custom = remember(catalog) { catalog?.workspaces.orEmpty().filter { !it.readOnly && it.id != catalog?.defaultWorkspaceId } }
    val pinned = remember(catalog) { catalog?.pinnedSessionIds.orEmpty().toSet() }
    LazyColumn(modifier.fillMaxWidth().testTag("native-spaces-chats"), state = list,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
        if (!state.loading && catalog == null) item(key = "catalog-empty") {
            Text(stringResource(R.string.native_workspaces_not_loaded), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 24.dp))
        }
        if (state.showArchived && visibleGroups.isEmpty() && catalog != null && !state.loading) item(key = "no-archived") {
            Text(stringResource(R.string.native_workspaces_no_archived), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 32.dp))
        }
        visibleGroups.forEach { group ->
            val key = group.id ?: "ungrouped"
            val opened = key in expanded
            val row = catalog?.workspaces?.firstOrNull { it.id == group.id }
            val positions = row?.sessionIds?.withIndex()?.associate { it.value to it.index }.orEmpty()
            val orderedSessions = group.sessions.sortedWith(compareBy<NativeSession> { it.id !in pinned }
                .thenBy { positions[it.id] ?: Int.MAX_VALUE })
            item(key = "space:$key") {
                WorkspaceGroupHeader(group.title, current = group.id == currentWorkspaceId, managed = group.readOnly,
                    expanded = opened, count = group.sessions.size,
                    unread = group.sessions.count { it.id in unreadSessionIds && !it.readOnly },
                    onToggle = { expanded = ArrayList(expanded).apply { if (!remove(key)) add(key) } },
                    onNew = if (!group.readOnly && !state.showArchived) { { onNewInSpace(group.id) } } else null,
                    enabled = enabled, workspace = row, customOrder = custom, onAction = onAction,
                    onRemove = onRemoveWorkspace, modifier = Modifier.animateItem(effects, placement, effects)
                        .padding(top = 12.dp).testTag("native-space:$key"))
            }
            if (opened && orderedSessions.isEmpty()) item(key = "space-empty:$key") {
                Surface(modifier = Modifier.animateItem(effects, placement, effects), color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)) {
                    Text(stringResource(R.string.native_no_conversations), color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().padding(start = 52.dp, end = 16.dp, top = 8.dp, bottom = 20.dp))
                }
            }
            if (opened) items(orderedSessions, key = { "space:$key/session:${it.id}" }) { session ->
                val detail = sessionsById.getValue(session.id)
                WorkspaceSessionRow(detail, selected = detail.id == currentSessionId, pinned = detail.id in pinned,
                    archived = detail.id in archived, enabled = enabled, last = detail.id == orderedSessions.last().id,
                    unread = detail.id in unreadSessionIds && !detail.readOnly,
                    onOpen = { onOpenSession(detail.id) }, onAction = onAction, onFork = { onForkSession(detail.id) },
                    onCopy = { onCopySession(detail.id) }, modifier = Modifier.animateItem(effects, placement, effects))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceGroupHeader(
    title: String, current: Boolean, managed: Boolean, expanded: Boolean, count: Int, unread: Int,
    onToggle: () -> Unit, onNew: (() -> Unit)?, enabled: Boolean,
    workspace: NativeWorkspaceRow?, customOrder: List<NativeWorkspaceRow>,
    onAction: (NativeWorkspacesAction) -> Unit, onRemove: (NativeWorkspaceRow) -> Unit,
    modifier: Modifier,
) {
    var menu by remember(workspace?.id) { mutableStateOf(false) }
    val expansionLabel = stringResource(if (expanded) R.string.native_collapse else R.string.native_expand)
    val expansionState = stringResource(if (expanded) R.string.native_workspaces_expanded else R.string.native_workspaces_collapsed)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = modifier) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = expansionLabel, onClick = onToggle).semantics { stateDescription = expansionState }
            .heightIn(min = 64.dp).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DroyIcon(R.drawable.native_ui_folder, null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (unread > 0) Text(stringResource(R.string.native_workbench_unread_results, unread),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (current) Text(stringResource(R.string.native_current), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (managed) Text(stringResource(R.string.native_workspaces_companion_space), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(pluralStringResource(R.plurals.native_workspaces_count, count, count), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            onNew?.let { create -> DroyIconAction(R.drawable.native_ui_edit_square, stringResource(R.string.native_new_in_space, title), create,
                enabled = enabled, modifier = Modifier.testTag("native-space-new:${workspace?.id ?: "ungrouped"}")) }
            if (workspace != null && !managed) Box {
                DroyIconAction(R.drawable.native_companion_more_vert, stringResource(R.string.native_workspaces_more, title), { menu = true },
                    modifier = Modifier.testTag("native-workspace-menu:${workspace.id}"))
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(leadingIcon = { DroyIcon(R.drawable.native_ui_edit_square, null) }, text = { Text(stringResource(R.string.native_workspaces_rename)) }, enabled = enabled,
                        onClick = { menu = false; onAction(NativeWorkspacesAction.BeginWorkspaceRename(workspace.id)) })
                    val index = customOrder.indexOfFirst { it.id == workspace.id }
                    if (index > 0) DropdownMenuItem(leadingIcon = { DroyIcon(R.drawable.native_ui_expand_less, null) }, text = { Text(stringResource(R.string.native_workspaces_move_up)) }, enabled = enabled,
                        onClick = { menu = false; onAction(NativeWorkspacesAction.MoveWorkspace(workspace.id, customOrder[index - 1].id)) })
                    if (index >= 0 && index < customOrder.lastIndex) DropdownMenuItem(leadingIcon = { DroyIcon(R.drawable.native_ui_expand_more, null) }, text = { Text(stringResource(R.string.native_workspaces_move_down)) }, enabled = enabled,
                        onClick = { menu = false; onAction(NativeWorkspacesAction.MoveWorkspace(workspace.id, customOrder.getOrNull(index + 2)?.id)) })
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    DropdownMenuItem(leadingIcon = { DroyIcon(R.drawable.native_companion_delete, null) }, text = { Text(stringResource(R.string.native_workspaces_remove)) }, enabled = enabled,
                        onClick = { menu = false; onRemove(workspace) })
                }
            }
            DroyIcon(if (expanded) R.drawable.native_ui_expand_less else R.drawable.native_ui_expand_more, null)
        }
    }
}

@Composable
private fun WorkspaceSessionRow(
    session: NativeWorkspaceSession, selected: Boolean, pinned: Boolean, archived: Boolean, enabled: Boolean, last: Boolean, unread: Boolean,
    onOpen: () -> Unit, onAction: (NativeWorkspacesAction) -> Unit, onFork: () -> Unit, onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember(session.id) { mutableStateOf(false) }
    val title = session.title?.takeIf(String::isNotBlank) ?: stringResource(R.string.native_conversation)
    Surface(modifier = modifier.padding(start = 16.dp, top = 2.dp), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().clickable(enabled = !archived, role = Role.Button, onClick = onOpen)
            .heightIn(min = 56.dp).testTag("native-space-session:${session.id}").padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DroyIcon(if (pinned) R.drawable.native_companion_push_pin else R.drawable.native_ui_chat_bubble, null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (session.readOnly || session.running || archived) Text(stringResource(when {
                    archived -> R.string.native_workspaces_archived
                    session.readOnly -> R.string.native_workspaces_read_only
                    else -> R.string.native_processing
                }), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (unread) {
                val unreadLabel = stringResource(R.string.native_workbench_unread_reply)
                Badge(Modifier.testTag("native-space-unread:${session.id}").semantics { stateDescription = unreadLabel },
                    containerColor = MaterialTheme.colorScheme.primary)
            }
            if (selected) DroyIcon(R.drawable.native_ui_check, stringResource(R.string.native_current))
            if (archived && !session.readOnly) DroyIconAction(R.drawable.native_ui_refresh, stringResource(R.string.native_workspaces_restore),
                { onAction(NativeWorkspacesAction.Restore(session.id)) }, enabled = enabled,
                modifier = Modifier.testTag("native-session-restore:${session.id}"))
            Box {
                DroyIconAction(R.drawable.native_companion_more_vert, stringResource(R.string.native_workspaces_more, title), { menu = true },
                    modifier = Modifier.testTag("native-session-menu:${session.id}"))
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    if (!session.readOnly) {
                        if (archived) DropdownMenuItem(leadingIcon = { DroyIcon(R.drawable.native_ui_refresh, null) }, text = { Text(stringResource(R.string.native_workspaces_restore)) }, enabled = enabled,
                            onClick = { menu = false; onAction(NativeWorkspacesAction.Restore(session.id)) })
                        else {
                            DropdownMenuItem(leadingIcon = { DroyIcon(R.drawable.native_companion_push_pin, null) }, text = { Text(stringResource(if (pinned) R.string.native_workspaces_unpin else R.string.native_workspaces_pin)) }, enabled = enabled,
                                onClick = { menu = false; onAction(NativeWorkspacesAction.Pin(session.id, !pinned)) })
                            DropdownMenuItem(leadingIcon = { DroyIcon(R.drawable.native_ui_edit_square, null) }, text = { Text(stringResource(R.string.native_workspaces_rename_session)) }, enabled = enabled,
                                onClick = { menu = false; onAction(NativeWorkspacesAction.BeginSessionRename(session.id)) })
                            DropdownMenuItem(leadingIcon = { DroyIcon(R.drawable.native_ui_content_copy, null) }, text = { Text(stringResource(R.string.native_workspaces_fork)) }, enabled = enabled,
                                onClick = { menu = false; onFork() })
                            DropdownMenuItem(leadingIcon = { DroyIcon(R.drawable.native_ui_folder, null) }, text = { Text(stringResource(R.string.native_workspaces_archive)) }, enabled = enabled,
                                onClick = { menu = false; onAction(NativeWorkspacesAction.Archive(session.id)) })
                        }
                    }
                    DropdownMenuItem(leadingIcon = { DroyIcon(R.drawable.native_ui_content_copy, null) }, text = { Text(stringResource(R.string.native_workspaces_copy_id)) }, onClick = { menu = false; onCopy() })
                }
            }
        }
    }
}
