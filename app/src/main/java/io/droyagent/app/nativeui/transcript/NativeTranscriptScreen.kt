package io.droyagent.app.nativeui.transcript

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.data.NativeMessage
import io.droyagent.app.nativeui.data.NativeMessageAttachment
import io.droyagent.app.nativeui.data.NativeReadingPosition
import io.droyagent.app.nativeui.ui.DroyIcon
import io.droyagent.app.nativeui.ui.DroyIconAction
import io.droyagent.app.nativeui.ui.NativeMessageCard
import io.droyagent.app.nativeui.ui.WorkbenchReadingTheme
import io.droyagent.app.nativeui.ui.rememberWorkbenchReadingSize
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun NativeTranscriptScreen(
    state: NativeTranscriptState,
    model: NativeTranscriptViewModel,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenAttachment: ((NativeSubagentTranscriptAddress, NativeMessage, NativeMessageAttachment) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val reading = rememberWorkbenchReadingSize()
    val copied = stringResource(R.string.native_copied)
    val copyFailed = stringResource(R.string.native_copy_failed)
    Scaffold(modifier.fillMaxSize().testTag("native-transcript"), contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.surface, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                DroyIconAction(R.drawable.native_companion_arrow_back, stringResource(R.string.native_companion_back), onBack)
                Text(state.title ?: stringResource(R.string.native_transcript_title), Modifier.weight(1f).padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                DroyIconAction(R.drawable.native_ui_refresh, stringResource(R.string.native_refresh), model::refresh,
                    enabled = state.runtime.ready && !state.loading, modifier = Modifier.testTag("native-transcript-refresh"))
            }
        }, bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.native_transcript_read_only), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).testTag("native-transcript-readonly"))
                    if (state.running && state.live) Text(stringResource(R.string.native_processing), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }) { padding ->
        val address = state.address
        if (address != null) key(address.parentSessionId, address.childSessionId) {
            WorkbenchReadingTheme(reading.value) { TranscriptHistory(state, onLoadOlder = model::loadOlder, onRetry = model::refresh,
                onReading = { model.setReadingPosition(address, it) },
                onCopy = { text ->
                    val message = try { clipboard.setText(AnnotatedString(text)); copied } catch (_: SecurityException) { copyFailed }
                    scope.launch { snackbar.showSnackbar(message) }
                }, onOpenLink = onOpenLink,
                onOpenAttachment = onOpenAttachment?.let { open -> { message, attachment -> open(address, message, attachment) } },
                modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, kotlinx.coroutines.FlowPreview::class)
@Composable
private fun TranscriptHistory(
    state: NativeTranscriptState, onLoadOlder: () -> Unit, onRetry: () -> Unit,
    onReading: (NativeReadingPosition) -> Unit, onCopy: (String) -> Unit, onOpenLink: (String) -> Unit,
    onOpenAttachment: ((NativeMessage, NativeMessageAttachment) -> Unit)?, modifier: Modifier,
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var followingTail by rememberSaveable { mutableStateOf(state.readingPosition?.followingTail ?: true) }
    var anchor by rememberSaveable { mutableStateOf(state.readingPosition?.key) }
    var anchorOffset by rememberSaveable { mutableIntStateOf(state.readingPosition?.offset ?: 0) }
    var restored by remember { mutableStateOf(false) }
    var positioning by remember { mutableStateOf(false) }
    val currentState by rememberUpdatedState(state)
    val save by rememberUpdatedState(onReading)
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    fun position(): NativeReadingPosition? {
        // Layout can retain a preceding row for spacing; the scroll offset belongs to the actual first item.
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull {
            it.index == list.firstVisibleItemIndex && (it.key as? String)?.startsWith("message:") == true
        } ?: return null
        return NativeReadingPosition(item.key as String, list.firstVisibleItemScrollOffset, followingTail)
    }
    LaunchedEffect(state.baselineReady, state.loadingOlder, state.messages.size, state.pagingError) {
        if (restored || !state.baselineReady) return@LaunchedEffect
        val index = state.messages.indexOfFirst { "message:${it.id}" == anchor }
        if (!followingTail && anchor != null && index < 0 && state.hasMore) {
            if (!state.loadingOlder && state.pagingError == null) onLoadOlder()
            return@LaunchedEffect
        }
        positioning = true
        try {
            withFrameNanos { }
            val prefix = (if (state.error != null || !state.runtime.ready) 1 else 0) +
                (if (state.pagingError != null) 1 else 0) + (if (state.hasMore) 1 else 0)
            if (!followingTail && index >= 0) list.scrollToItem(prefix + index, anchorOffset)
            else if (followingTail && list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
            restored = true
        } finally { positioning = false }
    }
    LaunchedEffect(list) {
        snapshotFlow { if (restored) position() else null }.distinctUntilChanged().debounce(200).collect { value ->
            if (value != null) { anchor = value.key; anchorOffset = value.offset; save(value) }
        }
    }
    DisposableEffect(list) { onDispose { if (restored) position()?.let(save) } }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.distinctUntilChanged().collect { (scrolling, forward) ->
            if (scrolling && !positioning) followingTail = !forward
        }
    }
    val newest = state.messages.lastOrNull()
    LaunchedEffect(newest?.id, newest?.text, state.messages.size, restored, MaterialTheme.typography.bodyLarge.fontSize) {
        if (restored && followingTail && state.messages.isNotEmpty()) {
            positioning = true
            try {
                withFrameNanos { }
                val index = list.layoutInfo.totalItemsCount - 1
                if (index >= 0) list.scrollToItem(index)
            } finally { positioning = false }
        }
    }
    Box(modifier) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("native-transcript-history"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.error != null || !state.runtime.ready) item(key = "connection-error") {
                TranscriptError(state.error ?: "runtime_unavailable", onRetry, enabled = state.runtime.ready && !state.loading)
            }
            if (state.pagingError != null) item(key = "paging-error") { TranscriptError(state.pagingError, onLoadOlder, enabled = state.baselineReady && !state.loadingOlder) }
            if (state.hasMore) item(key = "earlier") {
                TextButton(onClick = onLoadOlder, enabled = state.baselineReady && !state.loadingOlder,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-transcript-load-older")) {
                    Text(stringResource(if (state.loadingOlder) R.string.native_loading else R.string.native_load_older))
                }
            }
            if (state.loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (!state.loading && state.baselineReady && state.messages.isEmpty()) item(key = "empty") {
                Text(stringResource(R.string.native_transcript_empty), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 32.dp))
            }
            items(state.messages, key = { "message:${it.id}" }, contentType = { it.kind }) { message ->
                Box(Modifier.animateItem(effects, spatial, effects)) { NativeMessageCard(message, onCopy, onOpenLink, onOpenAttachment) }
            }
        }
        if (!followingTail && currentState.messages.isNotEmpty()) SmallFloatingActionButton(onClick = {
            followingTail = true
            scope.launch {
                positioning = true
                try { if (list.layoutInfo.totalItemsCount > 0) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1) }
                finally { positioning = false }
            }
        }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), containerColor = MaterialTheme.colorScheme.secondaryContainer) {
            DroyIcon(R.drawable.native_ui_arrow_downward, stringResource(R.string.native_new_messages))
        }
    }
}

@Composable
private fun TranscriptError(code: String, retry: () -> Unit, enabled: Boolean) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.testTag("native-transcript-error")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(when (code) {
                "subagent/not-found", "session/not-found" -> R.string.native_transcript_unavailable
                "subagent/unauthorized", "native_transcript_identity_conflict" -> R.string.native_transcript_identity
                "runtime_unavailable", "service_temporarily_unavailable", "NATIVE_SERVICE_NOT_READY" -> R.string.native_engine_unavailable
                "network_error", "stream_disconnected", "stream_gap" -> R.string.native_error_network
                else -> R.string.native_transcript_failed
            }), color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = retry, enabled = enabled, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text(stringResource(R.string.native_retry)) }
        }
    }
}
