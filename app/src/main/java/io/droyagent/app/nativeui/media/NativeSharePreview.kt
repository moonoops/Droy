package io.droyagent.app.nativeui.media

import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.companion.data.NativeCompanion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeSharePreview(
    state: NativeSharePreviewState,
    companions: List<NativeCompanion>,
    onTextChanged: (String) -> Unit,
    onSelectWorkbench: () -> Unit,
    onSelectCompanion: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    loadAttachment: suspend (NativeLocalAttachment) -> ByteArray,
    modifier: Modifier = Modifier,
) {
    var targetsOpen by remember { mutableStateOf(false) }
    val selectedCompanion = state.destination?.companionId?.let { id -> companions.firstOrNull { it.id == id } }
    val targetAvailable = state.destination?.workbench != null || selectedCompanion != null
    Scaffold(modifier = modifier, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        TopAppBar(title = { Text(stringResource(R.string.native_share_title)) }, navigationIcon = {
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("native-share-cancel")) { Text(stringResource(if (state.accepted) R.string.native_voice_input_done else R.string.native_media_cancel)) }
        })
    }, bottomBar = {
        Button(onClick = onConfirm, enabled = state.canSend && targetAvailable || state.accepted, modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp)
            .heightIn(min = 56.dp).testTag("native-share-confirm")) {
            if (state.sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(stringResource(if (state.accepted) R.string.native_voice_input_done else if (state.attempted) R.string.native_media_retry else R.string.native_share_send))
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.native_share_destination), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { targetsOpen = true }, enabled = state.editable, modifier = Modifier.heightIn(min = 48.dp).testTag("native-share-target")) {
                        Text(when {
                            state.destination?.companionId != null -> selectedCompanion?.name ?: stringResource(R.string.native_media_owner_unavailable)
                            state.destination?.workbench != null -> stringResource(R.string.native_share_workbench)
                            else -> stringResource(R.string.native_media_owner_unavailable)
                        }, style = MaterialTheme.typography.titleMedium)
                    }
                    DropdownMenu(expanded = targetsOpen, onDismissRequest = { targetsOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.native_share_workbench)) }, onClick = { targetsOpen = false; onSelectWorkbench() }, modifier = Modifier.testTag("native-share-target-workbench"))
                        companions.forEach { companion ->
                            DropdownMenuItem(text = { Text(companion.name) }, onClick = { targetsOpen = false; onSelectCompanion(companion.id) }, modifier = Modifier.testTag("native-share-target:${companion.id}"))
                        }
                    }
                }
            }
            OutlinedTextField(value = state.text, onValueChange = onTextChanged, readOnly = !state.editable,
                label = { Text(stringResource(R.string.native_share_message)) }, minLines = 3, modifier = Modifier.fillMaxWidth().testTag("native-share-text"))
            NativeDraftAttachments(state.attachments, state.editable, onRemoveAttachment, loadAttachment)
            if (state.loading) CircularProgressIndicator()
            state.error?.let { Text(stringResource(nativeInputErrorResource(it)), color = MaterialTheme.colorScheme.error) }
            if (state.accepted) Text(stringResource(R.string.native_share_sent), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun NativeDraftAttachments(
    attachments: List<NativeLocalAttachment>, removable: Boolean, onRemove: (String) -> Unit,
    loadAttachment: suspend (NativeLocalAttachment) -> ByteArray,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf<NativeLocalAttachment?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val images = attachments.filter { it.image }
        if (images.isNotEmpty()) androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        ) {
            items(images.size, key = { images[it].id }) { index ->
                val item = images[index]
                androidx.compose.foundation.layout.Box(Modifier.testTag("native-local-attachment:${item.id}")) {
                    Surface(onClick = { expanded = item }, shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.size(88.dp).testTag("native-preview-local-attachment:${item.id}")) {
                        LocalDraftImage(item, loadAttachment, 384, Modifier.fillMaxSize())
                    }
                    if (removable) IconButton(onClick = { onRemove(item.id) },
                        modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).size(48.dp)
                            .testTag("native-remove-local-attachment:${item.id}")) {
                        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(24.dp)) {
                            androidx.compose.foundation.layout.Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Icon(painterResource(R.drawable.native_ui_close), stringResource(R.string.native_share_remove_attachment), Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
        attachments.filterNot { it.image }.forEach { item ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.testTag("native-local-attachment:${item.id}")) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.native_ui_description), stringResource(R.string.native_share_file), Modifier.size(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(Formatter.formatShortFileSize(context, item.bytes), style = MaterialTheme.typography.bodySmall)
                    }
                    if (removable) IconButton(onClick = { onRemove(item.id) }, modifier = Modifier.testTag("native-remove-local-attachment:${item.id}")) {
                        Icon(painterResource(R.drawable.native_ui_close), stringResource(R.string.native_share_remove_attachment))
                    }
                }
            }
        }
    }
    expanded?.takeIf { selected -> attachments.any { it.id == selected.id } }?.let { item ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { expanded = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    LocalDraftImage(item, loadAttachment, 2048, Modifier.fillMaxSize().padding(16.dp), zoomable = true)
                    IconButton(onClick = { expanded = null }, modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd)
                        .padding(8.dp).testTag("native-local-preview-close")) {
                        Icon(painterResource(R.drawable.native_ui_close), stringResource(R.string.native_media_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalDraftImage(item: NativeLocalAttachment, loadAttachment: suspend (NativeLocalAttachment) -> ByteArray,
    maxDimension: Int, modifier: Modifier, zoomable: Boolean = false) {
    var failed by remember(item.importId, item.id) { mutableStateOf(false) }
    val bitmap by produceState<ImageBitmap?>(null, item.importId, item.id, maxDimension) {
        try {
            value = withContext(Dispatchers.IO) {
                val bytes = loadAttachment(item)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0)
                val options = BitmapFactory.Options().apply {
                    var sample = 1
                    while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
                    inSampleSize = sample
                }
                requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)).asImageBitmap()
            }
        } catch (error: Exception) { if (error is CancellationException) throw error; failed = true }
    }
    androidx.compose.foundation.layout.Box(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) {
        bitmap?.let {
            if (zoomable) io.droyagent.app.nativeui.tools.NativeZoomableImage(it, stringResource(R.string.native_share_image), Modifier.fillMaxSize())
            else Image(it, stringResource(R.string.native_share_image), Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        } ?: Icon(painterResource(R.drawable.native_companion_image),
            stringResource(if (failed) R.string.native_media_read_failed else R.string.native_share_image), Modifier.size(32.dp))
    }
}

fun nativeInputErrorResource(code: String): Int = when (code) {
    "native_workspace_resolving" -> R.string.native_workspace_resolving
    "native_creation_unconfirmed", "session_identity_conflict" -> R.string.native_share_creation_unconfirmed
    "native_attachment_too_many" -> R.string.native_media_too_many
    "native_attachment_too_large", "NATIVE_UPLOAD_TOO_LARGE" -> R.string.native_media_too_large
    "native_attachment_batch_too_large", "NATIVE_REQUEST_TOO_LARGE" -> R.string.native_share_batch_too_large
    "native_share_busy", "pending_send_conflict" -> R.string.native_share_busy
    "native_share_interrupted" -> R.string.native_share_interrupted
    "native_share_missing", "native_share_scope", "native_share_import_failed", "native_attachment_changed" -> R.string.native_share_unavailable
    "native_share_inbox_full" -> R.string.native_share_inbox_full
    "storage_error" -> R.string.native_media_storage_failed
    "native_media_cleanup_failed" -> R.string.native_media_cleanup_failed
    "native_share_text_too_long" -> R.string.native_share_text_too_long
    "runtime_unavailable", "NATIVE_UPLOAD_NOT_READY", "NATIVE_SERVICE_NOT_READY", "service_temporarily_unavailable" -> R.string.native_engine_unavailable
    else -> R.string.native_share_failed
}
