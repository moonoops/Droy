package io.droyagent.app.nativeui.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIconAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun NativeToolsScreen(state: NativeToolsState, model: NativeToolsViewModel, onBack: () -> Unit,
    initialSessionId: String? = null, modifier: Modifier = Modifier, focusedContent: Boolean = false) {
    NativeToolsFileActions(model)
    DisposableEffect(model) { model.startObserving(initialSessionId); onDispose { model.stopObserving() } }
    val back = { if (focusedContent || !model.back()) onBack() }
    BackHandler(onBack = back)
    val title = state.browser?.title?.takeIf { focusedContent && state.page == NativeToolsPage.BROWSER && it.isNotBlank() } ?: stringResource(when (state.page) {
        NativeToolsPage.HOME -> R.string.native_tools_title
        NativeToolsPage.FILES -> R.string.native_tools_files
        NativeToolsPage.CONTENT -> R.string.native_tools_content
        NativeToolsPage.CHANGES -> R.string.native_tools_changes
        NativeToolsPage.PREVIEW -> R.string.native_tools_files
        NativeToolsPage.BROWSER -> R.string.native_tools_browser
        NativeToolsPage.TERMINALS, NativeToolsPage.TERMINAL -> R.string.native_tools_terminals
    })
    Scaffold(modifier.fillMaxSize().testTag("native-tools"), containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DroyIconAction(R.drawable.native_schedule_arrow_back, stringResource(R.string.native_tools_back), back)
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    if (state.page !in setOf(NativeToolsPage.BROWSER, NativeToolsPage.TERMINAL)) DroyIconAction(R.drawable.native_ui_refresh,
                        stringResource(R.string.native_tools_refresh), model::refresh, enabled = state.runtime.ready && !state.loading)
                }
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (!state.runtime.ready && state.page != NativeToolsPage.BROWSER) {
                ToolsNotice(state.runtime.status.ifBlank { stringResource(R.string.native_engine_unavailable) })
                if (state.runtime.canStart) Button(model::startRuntime, modifier = Modifier.padding(horizontal = 24.dp).heightIn(min = 56.dp)) { Text(stringResource(R.string.native_start_engine)) }
            }
            if (state.error != null) ToolsNotice(toolsError(state.error))
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.page !in setOf(NativeToolsPage.BROWSER, NativeToolsPage.TERMINAL, NativeToolsPage.PREVIEW, NativeToolsPage.CHANGES)) SessionSelector(state, model::selectSession)
            when (state.page) {
                NativeToolsPage.HOME -> ToolsHome(state, model, Modifier.weight(1f))
                NativeToolsPage.FILES -> ToolFiles(state, model, Modifier.weight(1f))
                NativeToolsPage.CONTENT -> ToolContent(state, model, Modifier.weight(1f))
                NativeToolsPage.CHANGES -> ToolChanges(state, model, Modifier.weight(1f))
                NativeToolsPage.PREVIEW -> ToolPreview(state, model, Modifier.weight(1f))
                NativeToolsPage.TERMINALS -> ToolTerminals(state, model, Modifier.weight(1f))
                NativeToolsPage.TERMINAL -> NativeTerminalScreen(state, model, Modifier.weight(1f))
                NativeToolsPage.BROWSER -> NativeToolsBrowser(state, model, Modifier.weight(1f), focusedContent)
            }
        }
    }
}

@Composable
private fun SessionSelector(state: NativeToolsState, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedCard(onClick = { expanded = true }, enabled = state.runtime.ready && state.sessions.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.native_tools_session), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.selectedSession?.title ?: stringResource(if (state.sessions.isEmpty()) R.string.native_tools_no_sessions else R.string.native_tools_select_session),
                    style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (state.selectedSession?.readOnly == true) Text(stringResource(R.string.native_tools_read_only), style = MaterialTheme.typography.labelMedium)
            }
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            state.sessions.forEach { session -> DropdownMenuItem(text = { Text(session.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                onClick = { expanded = false; select(session.id) }) }
        }
    }
}

@Composable
private fun ToolsHome(state: NativeToolsState, model: NativeToolsViewModel, modifier: Modifier) {
    LazyColumn(modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(listOf(NativeToolsPage.FILES to R.string.native_tools_files, NativeToolsPage.CONTENT to R.string.native_tools_content,
            NativeToolsPage.BROWSER to R.string.native_tools_browser, NativeToolsPage.TERMINALS to R.string.native_tools_terminals)) { (page, title) ->
            FilledTonalButton({ model.open(page) }, enabled = page == NativeToolsPage.BROWSER || state.runtime.ready && state.selectedSessionId != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("native-tools-${page.name.lowercase()}"), shape = MaterialTheme.shapes.extraLarge) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth().padding(8.dp))
            }
        }
    }
}

@Composable
private fun ToolFiles(state: NativeToolsState, model: NativeToolsViewModel, modifier: Modifier) {
    val directory = state.directory
    LazyColumn(modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(16.dp)) {
        if (directory != null) {
            item {
                SelectionContainer { Text(directory.path.ifEmpty { stringResource(R.string.native_tools_root) }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp)) }
                if (directory.path.isNotEmpty()) TextButton({ model.openDirectory(NativeToolsPaths.parent(directory.path)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_tools_parent)) }
                if (directory.truncated) ToolsNotice(stringResource(R.string.native_tools_truncated))
                if (state.watchAvailable == false) Text(stringResource(R.string.native_tools_watch_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (directory.entries.isEmpty()) item { EmptyTools(R.string.native_tools_empty_directory) }
            items(directory.entries, key = NativeToolFile::name) { file ->
                ListItem(headlineContent = { Text(file.name) }, supportingContent = file.size?.let { size -> { Text(stringResource(R.string.native_tools_bytes, size)) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = file.type in setOf("file", "directory")) {
                        val path = NativeToolsPaths.child(directory.path, file.name)
                        if (file.type == "directory") model.openDirectory(path) else model.openFile(path)
                    })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun ToolContent(state: NativeToolsState, model: NativeToolsViewModel, modifier: Modifier) {
    LazyColumn(modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.content.isEmpty() && !state.loading) item { EmptyTools(R.string.native_tools_empty_content) }
        items(state.content, key = NativeToolContent::seq) { entry ->
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text(stringResource(R.string.native_tools_turn, entry.turn), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entry.type == "workspace/changes") TextButton({ model.openChanges(entry.seq) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_tools_changes)) }
                    entry.files.forEach { file -> TextButton({ model.openFile(file.path) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
                            file.description?.takeIf(String::isNotEmpty)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    } }
                }
            }
        }
        if (state.contentNextBefore != null) item { OutlinedButton({ model.loadContent(more = true) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.native_tools_load_more)) } }
    }
}

@Composable
private fun ToolChanges(state: NativeToolsState, model: NativeToolsViewModel, modifier: Modifier) {
    val diff = state.diff
    if (diff != null) {
        Column(modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            SelectionContainer { Text(diff.display, style = MaterialTheme.typography.titleMedium) }
            if (diff.kind != "text") EmptyTools(if (diff.kind == "binary") R.string.native_tools_binary else R.string.native_tools_oversized)
            else {
                if (diff.coarse) Text(stringResource(R.string.native_tools_coarse), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (diff.lines.isEmpty()) EmptyTools(R.string.native_tools_unchanged)
                SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(Modifier.fillMaxSize()) { itemsIndexed(diff.lines) { _, line -> Text(line, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(),
                        color = when { line.startsWith('+') -> MaterialTheme.colorScheme.primary; line.startsWith('-') -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurface }) } }
                }
            }
        }; return
    }
    val changes = state.changes
    LazyColumn(modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(16.dp)) {
        if (changes != null) {
            item {
                Text(stringResource(R.string.native_tools_change_total, changes.total), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.native_tools_change_counts, changes.added, changes.deleted), modifier = Modifier.padding(vertical = 8.dp))
                if (changes.files.size < changes.total) ToolsNotice(stringResource(R.string.native_tools_truncated))
            }
            itemsIndexed(changes.files) { index, file -> ListItem(headlineContent = { Text(file.display) },
                supportingContent = { Text(if (file.binary) stringResource(R.string.native_tools_binary) else if (file.oversized) stringResource(R.string.native_tools_oversized)
                    else stringResource(R.string.native_tools_change_counts, file.added, file.deleted)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { model.openDiff(index) }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolPreview(state: NativeToolsState, model: NativeToolsViewModel, modifier: Modifier) {
    val preview = state.preview
    Column(modifier.fillMaxWidth().navigationBarsPadding()) {
        if (state.fileSource != null) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ model.exportFile(saving = false) }, enabled = state.runtime.ready && !state.exporting,
                modifier = Modifier.heightIn(min = 48.dp).testTag("native-tools-open-with")) { Text(stringResource(R.string.native_tools_open_with)) }
            OutlinedButton({ model.exportFile(saving = true) }, enabled = state.runtime.ready && !state.exporting,
                modifier = Modifier.heightIn(min = 48.dp).testTag("native-tools-save-copy")) { Text(stringResource(R.string.native_tools_save_copy)) }
        }
        if (state.exporting) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.native_tools_copying), modifier = Modifier.padding(horizontal = 16.dp)) }
        if (state.fileFeedback == "saved") Text(stringResource(R.string.native_tools_copy_saved), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
        if (preview != null) {
            SelectionContainer { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(preview.path, style = MaterialTheme.typography.titleMedium)
                preview.stat?.bytes?.let { Text(stringResource(R.string.native_tools_bytes, it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } }
            when (preview.kind) {
                "loading" -> if (!state.loading) EmptyTools(R.string.native_tools_unsupported)
                "text" -> {
                    Text(stringResource(R.string.native_tools_line_offset, preview.offset), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp))
                    SelectionContainer(Modifier.weight(1f).fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                        Text(preview.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ model.previewPage(false) }, enabled = preview.offset > 1 && !state.loading, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_tools_previous)) }
                        OutlinedButton({ model.previewPage(true) }, enabled = !preview.eof && !state.loading, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_tools_next)) }
                    }
                }
                "html" -> NativeRestrictedHtml(preview.text, Modifier.weight(1f).fillMaxWidth())
                "pdf" -> NativeToolsPdfPreview(preview, Modifier.weight(1f).fillMaxWidth())
                "image" -> {
                    val image by produceState<PreviewImage>(PreviewImage.Loading, preview.data) {
                        value = PreviewImage.Loading
                        value = withContext(Dispatchers.Default) { preview.data?.let(::decodePreviewImage)?.let { PreviewImage.Ready(it) } ?: PreviewImage.Unsupported }
                    }
                    when (val value = image) {
                        PreviewImage.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        PreviewImage.Unsupported -> EmptyTools(R.string.native_tools_unsupported)
                        is PreviewImage.Ready -> NativeZoomableImage(value.bitmap.asImageBitmap(), preview.path, Modifier.weight(1f).fillMaxWidth())
                    }
                }
                else -> EmptyTools(R.string.native_tools_unsupported)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolTerminals(state: NativeToolsState, model: NativeToolsViewModel, modifier: Modifier) {
    var renaming by remember { mutableStateOf<NativeToolTerminal?>(null) }
    var closing by remember { mutableStateOf<NativeToolTerminal?>(null) }
    var name by remember { mutableStateOf("") }
    val enabled = state.runtime.ready && state.selectedSession?.readOnly == false && !state.terminalCreating
    LazyColumn(modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Button(model::createTerminal, enabled = enabled, modifier = Modifier.heightIn(min = 56.dp).testTag("native-tools-new-terminal")) { Text(stringResource(if (state.terminalCreateUnconfirmed) R.string.native_tools_retry_creation else R.string.native_tools_new_terminal)) }
            if (state.terminalCreateUnconfirmed) ToolsNotice(stringResource(R.string.native_tools_creation_unconfirmed))
        }
        if (state.terminals.isEmpty() && !state.loading) item { EmptyTools(R.string.native_tools_empty_terminals) }
        items(state.terminals, key = NativeToolTerminal::id) { terminal ->
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    TextButton({ model.openTerminal(terminal.id) }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(terminal.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth()) }
                    Text(terminal.shellName + " · " + terminalStatus(terminal.state), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer { Text(terminal.cwd, style = MaterialTheme.typography.bodySmall) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ renaming = terminal; name = terminal.title }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_tools_rename)) }
                        TextButton({ closing = terminal }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_tools_close_terminal), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    renaming?.let { selected -> AlertDialog(onDismissRequest = { renaming = null }, title = { Text(stringResource(R.string.native_tools_rename)) },
        text = { OutlinedTextField(name, { if (it.length <= 120) name = it }, label = { Text(stringResource(R.string.native_tools_name)) }, singleLine = true) },
        confirmButton = { TextButton({ renaming = null; model.renameTerminal(selected.id, name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.native_tools_save)) } },
        dismissButton = { TextButton({ renaming = null }) { Text(stringResource(R.string.native_tools_cancel)) } }) }
    closing?.let { selected -> AlertDialog(onDismissRequest = { closing = null }, title = { Text(stringResource(R.string.native_tools_close_terminal_title)) },
        text = { Text(stringResource(R.string.native_tools_close_terminal_scope)) },
        confirmButton = { TextButton({ closing = null; model.closeTerminal(selected.id) }) { Text(stringResource(R.string.native_tools_confirm_close), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ closing = null }) { Text(stringResource(R.string.native_tools_cancel)) } }) }
}

@Composable internal fun ToolsNotice(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}
@Composable private fun EmptyTools(resource: Int) { Text(stringResource(resource), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp)) }
@Composable internal fun terminalStatus(value: String): String = stringResource(when (value) { "running" -> R.string.native_tools_running; "exited" -> R.string.native_tools_exited; else -> R.string.native_tools_failed })
@Composable internal fun toolsError(code: String): String = stringResource(when (code) {
    "NATIVE_TOOLS_DIFF_UNAVAILABLE" -> R.string.native_tools_error_diff
    "NATIVE_TOOLS_TARGET_UNAVAILABLE", "COMPANION_SESSION_MANAGED", "terminal/control-unavailable" -> R.string.native_tools_error_readonly
    "NATIVE_TOOLS_TOO_LARGE", "workspace-file/too-large", "response_too_large" -> R.string.native_tools_error_size
    "workspace-file/outside-workspace", "remote-access/denied" -> R.string.native_tools_error_scope
    "workspace-file/not-found", "NATIVE_TOOLS_SESSION_NOT_FOUND", "terminal/unavailable" -> R.string.native_tools_error_not_found
    "native_file_changed", "NATIVE_TOOLS_COPY_CHANGED", "ATTACHMENT_CORRUPT" -> R.string.native_tools_error_changed
    "NATIVE_TOOLS_ATTACHMENT_SCOPE" -> R.string.native_tools_error_attachment
    "NATIVE_TOOLS_EXPORT_TOO_LARGE" -> R.string.native_tools_error_export_size
    "native_file_no_viewer" -> R.string.native_tools_error_viewer
    "NATIVE_TOOLS_SAVE_INCOMPLETE" -> R.string.native_tools_error_save_incomplete
    "native_file_copy_failed", "NATIVE_TOOLS_CACHE_FAILED", "NATIVE_TOOLS_SAVE_FAILED" -> R.string.native_tools_error_copy
    "NATIVE_TOOLS_INPUT_PENDING", "gateway/uplink-overflow" -> R.string.native_tools_error_pending
    "NATIVE_TOOLS_INPUT_TOO_LARGE", "terminal/input-too-large", "terminal/clipboard-too-large" -> R.string.native_tools_error_input_size
    "terminal/clipboard-unavailable" -> R.string.native_tools_error_clipboard
    "NATIVE_TOOLS_INPUT_ENCODING" -> R.string.native_tools_error_input_encoding
    "NATIVE_TOOLS_TERMINAL_SIZE_UNSUPPORTED" -> R.string.native_tools_error_terminal_size
    "NATIVE_TOOLS_INPUT_SEQUENCE", "NATIVE_TOOLS_ATTACHMENT_CLOSED", "stream_disconnected" -> R.string.native_tools_error_terminal
    "NATIVE_BROWSER_FAILED", "BROWSER_TIMEOUT", "BROWSER_SESSION_LOST", "INVALID_BROWSER_OPERATION", "BROWSER_UNAVAILABLE" -> R.string.native_tools_error_browser
    else -> R.string.native_tools_error
})

private fun decodePreviewImage(bytes: ByteArray): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth.toLong() * bounds.outHeight > 20_000_000) return null
    val sample = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = 1
        while (bounds.outWidth / inSampleSize > 2048 || bounds.outHeight / inSampleSize > 2048) inSampleSize *= 2
    }
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sample)
}
private sealed interface PreviewImage {
    data object Loading : PreviewImage
    data object Unsupported : PreviewImage
    data class Ready(val bitmap: android.graphics.Bitmap) : PreviewImage
}
