package io.droyagent.app.nativeui.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.droyagent.app.NativeRuntimeHost
import io.droyagent.app.R
import io.droyagent.app.nativeui.companion.data.*
import io.droyagent.app.nativeui.ui.DroyIcon
import io.droyagent.app.nativeui.ui.DroyMenuActionRow
import io.droyagent.app.nativeui.ui.DroyIconAction
import io.droyagent.app.nativeui.ui.DroyPrimaryIconAction
import io.droyagent.app.nativeui.ui.MessageEntranceTracker
import io.droyagent.app.nativeui.ui.itemIdentity
import io.droyagent.app.nativeui.media.NativeDraftAttachments
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun CompanionChatScreen(
    state: NativeCompanionState,
    model: NativeCompanionViewModel,
    onBack: () -> Unit,
    onOpenCompanionProfile: (String) -> Unit,
    onOpenUserProfile: () -> Unit,
    modifier: Modifier = Modifier,
    imageLoader: CompanionImageLoader = model::loadImage,
    onChooseAttachments: (() -> Unit)? = null,
    onOpenAttachment: ((NativeCompanionAttachment) -> Unit)? = null,
    onPlayVoice: ((NativeCompanionVoice) -> Unit)? = null,
    onOpenTask: ((NativeCompanionTask) -> Unit)? = null,
    onChooseImages: (() -> Unit)? = null,
    onCamera: (() -> Unit)? = null,
    onRetryVoiceTranscript: ((String) -> Unit)? = null,
    onConfigureModels: (() -> Unit)? = null,
    voiceButton: (@Composable () -> Unit)? = null,
    voiceStatus: (@Composable () -> Unit)? = null,
    pendingVoiceKey: String? = null,
    pendingVoiceBubble: (@Composable () -> Unit)? = null,
) {
    val companion = state.selectedCompanion
    if (companion == null) {
        Scaffold(modifier.fillMaxSize().testTag("native-companion-chat"), contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = { CompanionTopBar(stringResource(R.string.native_companion_messages), onBack) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CompanionRuntime(state.runtime, model::startRuntime)
                if (state.loading) CircularProgressIndicator() else if (state.runtime.ready) Text(stringResource(R.string.native_companion_error_profile))
                CompanionError(state.error, model::dismissError, model::refresh)
            }
        }
        return
    }
    var replyNoticeDismissed by rememberSaveable(companion.id, state.status.error, state.status.phase, state.messages.lastOrNull()?.id) { mutableStateOf(false) }
    val list = rememberLazyListState()
    var positioned by rememberSaveable(companion.id) { mutableStateOf(false) }
    var followingTail by rememberSaveable(companion.id) { mutableStateOf(true) }
    var menuId by rememberSaveable(companion.id) { mutableStateOf<String?>(null) }
    var transcriptText by rememberSaveable(companion.id) { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable(companion.id) { mutableStateOf<String?>(null) }
    var sheet by rememberSaveable(companion.id) { mutableStateOf<String?>(null) }
    var taskId by rememberSaveable(companion.id) { mutableStateOf<String?>(null) }
    var imageUrl by rememberSaveable(companion.id) { mutableStateOf<String?>(null) }
    var imageLabel by rememberSaveable(companion.id) { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    BackHandler(enabled = imeVisible) { focus.clearFocus(); keyboard?.hide() }
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.native_companion_copied)
    val copyFailed = stringResource(R.string.native_companion_copy_failed)
    val currentMessages by rememberUpdatedState(state.messages)
    val currentReady by rememberUpdatedState(state.runtime.ready)
    val currentLoading by rememberUpdatedState(state.loading)
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState = lifecycleOwner.lifecycle.currentStateAsState()
    val windowInfo = LocalWindowInfo.current
    val entrances = remember(companion.id) { MessageEntranceTracker() }
    val entering = entrances.update(state.messages.map { it.itemIdentity() } + listOfNotNull(pendingVoiceKey),
        state.messages.filter { state.sending && it.role == "user" && it.status == "pending" }.map { it.itemIdentity() }.toSet(),
        state.runtime.ready && !state.loading && lifecycleState.value.isAtLeast(Lifecycle.State.RESUMED))
    val notificationOwner = remember { Any() }
    val originals = remember(state.messages) { state.messages.associateBy { it.id } }
    val stickers = remember(state.stickers) { state.stickers.associateBy { it.id } }
    val enabledStickers = remember(companion.settings.stickerPackIds, state.stickers) {
        enabledCompanionStickers(companion.settings, state.stickers)
    }
    val readingCovered by rememberUpdatedState(transcriptText != null || imageUrl != null || sheet != null || deleteId != null ||
        menuId?.let(originals::containsKey) == true || taskId?.let(originals::get)?.task != null)
    // The notification shade can take window focus without pausing the Activity.
    val readingLatest = positioned && state.runtime.ready && !state.loading && windowInfo.isWindowFocused &&
        lifecycleState.value.isAtLeast(Lifecycle.State.RESUMED) && !readingCovered && followingTail
    DisposableEffect(notificationOwner, companion.id, readingLatest) {
        NativeRuntimeHost.presentCompanion(notificationOwner, companion.id, readingLatest)
        onDispose { NativeRuntimeHost.presentCompanion(notificationOwner, companion.id, false) }
    }
    val active = state.status.chatExecution?.let { !it.quiescent }
        ?: (state.status.phase in setOf("thinking", "typing", "waiting"))
    val status = companionStatusLabel(state.status, state.stopRequested)

    // Newest-first items with reverse layout start at the bottom before their first
    // frame. Images decoded above the viewport cannot push the reader off the tail.
    LaunchedEffect(companion.id, state.runtime.ready, state.loading) {
        if (!positioned && state.runtime.ready && !state.loading) positioned = true
    }
    LaunchedEffect(list, companion.id) {
        snapshotFlow { list.isScrollInProgress to list.canScrollBackward }.distinctUntilChanged().collect { (scrolling, backward) ->
            if (positioned && scrolling) followingTail = !backward
        }
    }
    LaunchedEffect(pendingVoiceKey) {
        if (pendingVoiceKey != null && pendingVoiceBubble != null) {
            followingTail = true
            list.scrollToItem(0)
        }
    }
    val newest = state.messages.lastOrNull()
    LaunchedEffect(newest?.id, newest?.text, newest?.status, followingTail) {
        if (positioned && followingTail && state.messages.isNotEmpty()) list.scrollToItem(0)
    }
    LaunchedEffect(list, companion.id, lifecycleOwner, windowInfo) {
        snapshotFlow {
            if (!positioned || !currentReady || currentLoading || readingCovered || !windowInfo.isWindowFocused || !lifecycleState.value.isAtLeast(Lifecycle.State.RESUMED)) null else {
                val layout = list.layoutInfo
                val viewportHeight = layout.viewportEndOffset - layout.viewportStartOffset
                val visibleIds = layout.visibleItemsInfo.filter {
                    val bottom = it.offset + it.size
                    if (it.size <= viewportHeight) it.offset >= layout.viewportStartOffset && bottom <= layout.viewportEndOffset
                    // In reverse layout the logical leading edge is the physical bottom.
                    else it.offset >= layout.viewportStartOffset && it.offset < layout.viewportEndOffset
                }.mapNotNull { (it.key as? String)?.takeIf { key -> key.startsWith("message:") }?.removePrefix("message:") }.toSet()
                currentMessages.lastOrNull { it.itemIdentity() in visibleIds && it.status !in setOf("pending", "sending", "failed") }?.id
            }
        }.distinctUntilChanged().collect { if (it != null) model.markVisibleRead(it) }
    }


    Scaffold(modifier.fillMaxSize().testTag("native-companion-chat"), containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0), snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 72.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    DroyIconAction(R.drawable.native_companion_arrow_back, stringResource(R.string.native_companion_back), onBack)
                    Row(Modifier.weight(1f).clickable(role = Role.Button,
                        onClickLabel = stringResource(R.string.native_companion_profile_of, companion.name)) { onOpenCompanionProfile(companion.id) }
                        .heightIn(min = 56.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompanionAvatar(companion.name, companion.avatarUrl, imageLoader, size = 40.dp)
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(companion.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (status != null) Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                    }
                    DroyIconAction(R.drawable.native_companion_more_vert, stringResource(R.string.native_companion_profile),
                        { onOpenCompanionProfile(companion.id) }, Modifier.testTag("native-open-companion-profile"))
                }
            }
        }, bottomBar = {
            CompanionComposer(state, model, voiceButton = voiceButton, voiceStatus = voiceStatus, onSend = { followingTail = true; model.send() },
                onOptions = { keyboard?.hide(); sheet = "compose" })
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (!state.runtime.ready) Box(Modifier.padding(16.dp)) { CompanionRuntime(state.runtime, model::startRuntime) }
            if (companionNeedsModel(state.error)) io.droyagent.app.nativeui.ui.DroyErrorNotice(
                stringResource(R.string.native_companion_configure_model), model::dismissError, onConfigureModels,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), actionIcon = R.drawable.native_ui_model, actionLabel = stringResource(R.string.native_companion_model))
            else CompanionError(state.error, model::dismissError, model::refresh, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            if (!replyNoticeDismissed && (state.status.phase == "error" || state.status.error != null)) {
                val needsModel = companionNeedsModel(state.status.error)
                val retry: (() -> Unit)? = if (needsModel) onConfigureModels
                    else if (state.runtime.ready && !active && !state.stopRequested) model::retryTurn else null
                io.droyagent.app.nativeui.ui.DroyErrorNotice(
                    stringResource(if (needsModel) R.string.native_companion_configure_model else R.string.native_companion_reply_failed),
                    { replyNoticeDismissed = true }, retry, Modifier.testTag("native-companion-regenerate"),
                    actionIcon = if (needsModel) R.drawable.native_ui_model else R.drawable.native_ui_refresh,
                    actionLabel = stringResource(if (needsModel) R.string.native_companion_model else R.string.native_companion_regenerate))
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                CompanionChatBackground(companion.chatBackgroundJson, imageLoader)
                LazyColumn(state = list, reverseLayout = true, modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { focus.clearFocus(); keyboard?.hide() }) }.testTag("native-companion-history"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (pendingVoiceKey != null && pendingVoiceBubble != null) item(key = "message:$pendingVoiceKey") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                            pendingVoiceBubble()
                            CompanionAvatar(state.userProfile.name, state.userProfile.avatarUrl, imageLoader, size = 40.dp,
                                onClick = onOpenUserProfile, label = stringResource(R.string.native_companion_my_profile))
                        }
                    }
                    items(state.messages.asReversed(), key = { "message:${it.itemIdentity()}" }, contentType = { it.type }) { message ->
                        val quoted = message.replyPreview ?: message.replyTo?.let(originals::get)?.let { NativeCompanionReplyPreview(it.id, it.text, it.type) }
                        CompanionMessageRow(message, companion, state.userProfile, quoted, message.stickerId?.let(stickers::get), imageLoader,
                            onProfile = { onOpenCompanionProfile(companion.id) }, onUserProfile = onOpenUserProfile,
                            onMenu = { keyboard?.hide(); menuId = it.id }, onRetry = { model.retryPending(message.id) },
                            onImage = { url, label -> keyboard?.hide(); imageUrl = url; imageLabel = label },
                            onAttachment = onOpenAttachment, onVoice = onPlayVoice, onTask = { keyboard?.hide(); taskId = it.id },
                            onRetryVoiceTranscript = onRetryVoiceTranscript, onTapContent = { focus.clearFocus(); keyboard?.hide() },
                            sending = state.sending || state.stopRequested || !state.runtime.ready,
                            modifier = io.droyagent.app.nativeui.ui.messageEntranceModifier(message.itemIdentity() in entering))
                    }
                    item(key = "history-start") {
                        if (state.hasMore || state.loadingOlder) FilledTonalButton(onClick = { followingTail = false; model.loadOlder() },
                            enabled = !state.loadingOlder, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            if (state.loadingOlder) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Text(stringResource(R.string.native_companion_earlier))
                        }
                    }

                }
                if (state.runtime.ready && !state.loading && state.messages.isEmpty() && pendingVoiceKey == null) Surface(
                    modifier = Modifier.align(Alignment.Center), shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text(stringResource(R.string.native_companion_no_messages),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (positioned && !followingTail && state.messages.isNotEmpty()) SmallFloatingActionButton(onClick = {
                    followingTail = true
                    scope.launch { list.animateScrollToItem(0) }
                }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp), containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    DroyIcon(R.drawable.native_ui_arrow_downward, stringResource(R.string.native_companion_latest))
                }
            }
        }
    }

    originals[menuId]?.let { message ->
        val committed = message.status !in setOf("pending", "sending", "failed")
        val copyText = message.text.ifBlank { message.voice?.transcript ?: message.transcript ?: "" }
        ModalBottomSheet(onDismissRequest = { menuId = null }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (message.type == "voice" && copyText.isNotBlank()) TextButton(onClick = {
                    transcriptText = copyText; menuId = null
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-companion-transcript")) {
                    Text(stringResource(R.string.native_companion_show_transcript))
                }
                if (copyText.isNotBlank()) TextButton(onClick = {
                    val result = try { clipboard.setText(AnnotatedString(copyText)); copied } catch (_: SecurityException) { copyFailed }
                    menuId = null; scope.launch { snackbar.showSnackbar(result) }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-companion-copy")) {
                    Text(stringResource(R.string.native_companion_copy))
                }
                TextButton(onClick = { model.setReplyTo(message); menuId = null }, enabled = committed,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-companion-reply")) {
                    Text(stringResource(R.string.native_companion_reply))
                }
                TextButton(onClick = { deleteId = message.id; menuId = null }, enabled = message.status !in setOf("pending", "sending"),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-companion-delete-message")) {
                    Text(stringResource(R.string.native_companion_delete_message), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { menuId = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.native_companion_cancel))
                }
            }
        }
    }
    transcriptText?.let { text -> AlertDialog(onDismissRequest = { transcriptText = null },
        title = { Text(stringResource(R.string.native_companion_show_transcript)) },
        text = { androidx.compose.foundation.text.selection.SelectionContainer {
            Text(text, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
        } }, confirmButton = { TextButton(onClick = { transcriptText = null }) { Text(stringResource(R.string.native_companion_close)) } }) }
    if (deleteId != null) AlertDialog(onDismissRequest = { deleteId = null },
        title = { Text(stringResource(R.string.native_companion_delete_title)) },
        confirmButton = { TextButton(onClick = { deleteId?.let(model::deleteMessage); deleteId = null },
            modifier = Modifier.heightIn(min = 48.dp).testTag("native-companion-confirm-delete")) {
            Text(stringResource(R.string.native_companion_confirm_delete), color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = { deleteId = null }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.native_companion_cancel))
        } })
    if (sheet == "compose") ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            onChooseImages?.let { choose -> DroyMenuActionRow(R.drawable.native_companion_image, stringResource(R.string.native_work_photos),
                modifier = Modifier.testTag("native-companion-choose-images")) { sheet = null; choose() } }
            onCamera?.let { capture -> DroyMenuActionRow(R.drawable.native_ui_photo_camera, stringResource(R.string.native_shell_camera),
                modifier = Modifier.testTag("native-companion-camera")) { sheet = null; capture() } }
            DroyMenuActionRow(R.drawable.native_companion_attach_file, stringResource(R.string.native_share_file),
                enabled = onChooseAttachments != null && state.runtime.ready,
                modifier = Modifier.testTag("native-companion-choose-attachments")) { sheet = null; onChooseAttachments?.invoke() }
            if (enabledStickers.isNotEmpty()) {
                Text(stringResource(R.string.native_companion_stickers), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                    items(enabledStickers, key = { it.id }) { sticker ->
                        FilledTonalButton(onClick = { model.sendSticker(sticker.id); sheet = null },
                            enabled = state.runtime.ready && !state.sending && !state.stopRequested,
                            contentPadding = PaddingValues(0.dp), shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.testTag("native-companion-send-sticker:${sticker.id}")) {
                            CompanionImage(sticker.url, sticker.label.ifBlank { stringResource(R.string.native_companion_stickers) },
                                imageLoader, Modifier.size(80.dp)) { loading ->
                                if (loading) CircularProgressIndicator(Modifier.size(20.dp)) else DroyIcon(R.drawable.native_companion_sentiment_satisfied, null)
                            }
                        }
                    }
                }
            } else {
                Text(stringResource(R.string.native_companion_sticker_packs_none_enabled),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
            }
            if (active || state.stopRequested) DroyMenuActionRow(R.drawable.native_ui_stop,
                stringResource(if (state.stopRequested) R.string.native_companion_stopping else R.string.native_companion_stop),
                enabled = state.runtime.ready && !state.stopRequested, modifier = Modifier.testTag("native-companion-stop")) {
                sheet = null; model.cancelChat()
            }
            TextButton(onClick = { sheet = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.native_companion_cancel)) }
        }
    }
    originals[taskId]?.takeIf { it.task != null }?.let { message ->
        ModalBottomSheet(onDismissRequest = { taskId = null }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            CompanionError(state.error, model::dismissError, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            CompanionTaskDetails(message, onCancel = { model.cancelTask(message.id) }, onOpenTask = onOpenTask,
                onClose = { taskId = null }, enabled = state.runtime.ready)
        }
    }
    imageUrl?.let { CompanionImageDialog(it, imageLabel, imageLoader, onClose = { imageUrl = null }) }
}

@Composable
private fun CompanionComposer(state: NativeCompanionState, model: NativeCompanionViewModel, onSend: () -> Unit, onOptions: () -> Unit, voiceButton: (@Composable () -> Unit)? = null, voiceStatus: (@Composable () -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            voiceStatus?.invoke()
            state.draft.replyTo?.let { preview ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(stringResource(R.string.native_companion_reply), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(companionReplyPreview(preview), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        }
                        DroyIconAction(R.drawable.native_ui_close, stringResource(R.string.native_companion_cancel_reply), { model.setReplyTo(null) })
                    }
                }
            }
            if (state.draft.attachments.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.draft.attachments, key = { it.id }) { attachment ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.widthIn(max = 240.dp).heightIn(min = 48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(attachment.name, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            DroyIconAction(R.drawable.native_ui_close, stringResource(R.string.native_companion_remove_attachment, attachment.name),
                                { state.selectedCompanionId?.let { model.removeAttachment(it, attachment.id) } })
                        }
                    }
                }
            }
            if (state.draft.localAttachments.isNotEmpty()) NativeDraftAttachments(state.draft.localAttachments, !state.sending,
                onRemove = { attachment -> state.selectedCompanionId?.let { model.removeLocalAttachment(it, attachment) } },
                loadAttachment = model::loadLocalAttachment)
            state.draft.voice?.let { voice ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(voice.durationMs?.let { stringResource(R.string.native_companion_voice_seconds, kotlin.math.ceil(it / 1000.0).toInt()) }
                        ?: stringResource(R.string.native_companion_voice), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    DroyIconAction(R.drawable.native_ui_close, stringResource(R.string.native_companion_remove_voice),
                        { state.selectedCompanionId?.let { model.setVoice(it, null) } })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DroyIconAction(R.drawable.native_companion_attach_file, stringResource(R.string.native_companion_attachments), onOptions,
                    enabled = state.runtime.ready, modifier = Modifier.testTag("native-companion-composer-options"))
                TextField(state.draft.text, model::setDraft, Modifier.weight(1f).heightIn(min = 56.dp, max = 160.dp).testTag("native-companion-composer"),
                    placeholder = { Text(stringResource(R.string.native_companion_enter_message)) }, maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default), shape = MaterialTheme.shapes.extraLarge,
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                voiceButton?.invoke()
                DroyPrimaryIconAction(R.drawable.native_ui_send, stringResource(R.string.native_companion_send),
                    enabled = state.runtime.ready && state.draft.canSend && !state.sending && !state.stopRequested,
                    modifier = Modifier.testTag("native-companion-send"), onClick = onSend)
            }
        }
    }
}

@Composable
private fun BoxScope.CompanionChatBackground(raw: String?, loader: CompanionImageLoader) {
    val background = remember(raw) {
        try {
            raw?.let(::JSONObject)?.let { value ->
                val url = companionBackgroundUrl(value)
                val position = value.optJSONObject("position")
                url?.let { it to BiasAlignment(
                    ((position?.optDouble("x", 50.0)?.takeIf { x -> x.isFinite() } ?: 50.0).coerceIn(0.0, 100.0).toFloat() - 50f) / 50f,
                    ((position?.optDouble("y", 50.0)?.takeIf { y -> y.isFinite() } ?: 50.0).coerceIn(0.0, 100.0).toFloat() - 50f) / 50f) }
            }
        } catch (_: JSONException) { null }
    }
    if (background != null) CompanionBackgroundImage(background.first, loader,
        Modifier.matchParentSize().testTag("native-companion-chat-background"), alignment = background.second)
}
