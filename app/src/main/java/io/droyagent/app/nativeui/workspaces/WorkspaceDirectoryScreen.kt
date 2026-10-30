package io.droyagent.app.nativeui.workspaces

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIcon

/** All navigable paths come from the authenticated Host directory picker. */
@Composable
internal fun NativeWorkspaceDirectoryContent(
    state: NativeWorkspacesState,
    onAction: (NativeWorkspacesAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val directory = state.directory
    val enabled = state.runtime.ready && !state.busy && state.uncertain == null && !state.directoryLoading
    val registered = directory != null && state.catalog?.workspaces?.any { it.path == directory.path } == true
    Column(modifier.fillMaxWidth().testTag("native-workspaces-directory")) {
        if (state.directoryLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { onAction(NativeWorkspacesAction.BrowseDirectory()) }, enabled = enabled,
                label = { Text(stringResource(R.string.native_workspaces_home)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("native-workspaces-home"))
            directory?.crumbs.orEmpty().forEach { crumb ->
                AssistChip(onClick = { onAction(NativeWorkspacesAction.BrowseDirectory(crumb.path)) }, enabled = enabled,
                    label = { Text(crumb.name) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        directory?.let { current ->
            SelectionContainer(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                Text(current.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        state.directoryError?.let { code ->
            WorkspaceNotice(code)
            TextButton(onClick = { onAction(NativeWorkspacesAction.BrowseDirectory(directory?.path)) }, enabled = state.runtime.ready && !state.directoryLoading,
                modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp).testTag("native-workspaces-directory-retry")) { Text(stringResource(R.string.native_retry)) }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("native-workspaces-directory-list"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(directory?.entries.orEmpty(), key = { it.path }) { entry ->
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button) { onAction(NativeWorkspacesAction.BrowseDirectory(entry.path)) }
                        .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp).testTag("native-workspaces-directory:${entry.path}"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        DroyIcon(R.drawable.native_ui_folder, null)
                        Text(entry.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        DroyIcon(R.drawable.native_companion_chevron_right, null)
                    }
                }
            }
            if (directory != null && directory.entries.isEmpty() && !state.directoryLoading && state.directoryError == null) item {
                Text(stringResource(R.string.native_workspaces_no_folders), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
            if (directory?.truncated == true) item {
                Text(stringResource(R.string.native_workspaces_folder_limit), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Button(onClick = { onAction(NativeWorkspacesAction.RegisterDirectory) }, enabled = enabled && directory != null && !registered,
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 56.dp).testTag("native-workspaces-register")) {
                Text(stringResource(if (registered) R.string.native_workspaces_added else R.string.native_workspaces_use_folder))
            }
        }
    }
}
