package io.droyagent.app.nativeui.companion.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.companion.data.*
import io.droyagent.app.nativeui.ui.DroyIcon
import io.droyagent.app.nativeui.ui.DroyIconAction

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CompanionMessagesScreen(
    state: NativeCompanionState,
    model: NativeCompanionViewModel,
    onOpenChat: (String) -> Unit,
    onOpenUserProfile: () -> Unit,
    modifier: Modifier = Modifier,
    imageLoader: CompanionImageLoader = model::loadImage,
    onAddCompanion: (() -> Unit)? = null,
) {
    val list = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    var menuId by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(modifier.fillMaxSize().testTag("native-companion-messages"),
        containerColor = MaterialTheme.colorScheme.surface, contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 80.dp).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(80.dp)) {
                        Text(stringResource(R.string.native_companion_messages), style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.align(Alignment.CenterStart))
                        if (!state.runtime.ready && state.runtime.active) Text(stringResource(R.string.native_engine_starting),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 4.dp).testTag("native-companion-starting"))
                    }
                    onAddCompanion?.let { add ->
                        FilledTonalIconButton(onClick = add, modifier = Modifier.size(48.dp).testTag("native-add-companion")) {
                            DroyIcon(R.drawable.native_schedule_add, stringResource(R.string.native_companion_creation_title))
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    CompanionAvatar(state.userProfile.name, state.userProfile.avatarUrl, imageLoader,
                        onClick = onOpenUserProfile, label = stringResource(R.string.native_companion_my_profile),
                        modifier = Modifier.testTag("native-open-user-profile"))
                }
            }
        }) { padding ->
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .testTag("native-companion-conversations"), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (!state.runtime.ready && !state.runtime.active) item(key = "runtime") {
                Box(Modifier.padding(16.dp)) { CompanionRuntime(state.runtime, model::startRuntime) }
            }
            if (state.error != null) item(key = "error") {
                CompanionError(state.error, model::dismissError, model::refresh, Modifier.padding(16.dp))
            }
            if (state.runtime.ready && !state.loading && state.conversations.isEmpty()) item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(vertical = 80.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.native_companion_no_conversations), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(state.conversations, key = { it.id }, contentType = { "conversation" }) { companion ->
                val unreadLabel = stringResource(R.string.native_companion_unread_count, companion.unreadCount)
                val status = companionStatusLabel(companion.status)
                val time = companionConversationTime(companion.lastActivityAt ?: companion.lastMessage?.createdAt)
                val draft = state.draftsByCompanion[companion.id]?.takeIf { it.canSend || it.replyTo != null }
                Row(Modifier.fillMaxWidth().combinedClickable(
                    role = Role.Button,
                    onClick = { onOpenChat(companion.id) },
                    onLongClickLabel = stringResource(R.string.native_companion_conversation_actions),
                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menuId = companion.id },
                ).heightIn(min = 80.dp).padding(horizontal = 20.dp, vertical = 8.dp)
                    .testTag("native-companion-conversation:${companion.id}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompanionAvatar(companion.name, companion.avatarUrl, imageLoader, size = 56.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(companion.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (draft != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.native_companion_draft), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary)
                            Text(companionDraftPreview(draft), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                        } else Text(status ?: companionMessagePreview(companion.lastMessage), style = MaterialTheme.typography.bodyMedium,
                            color = if (status != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (time != null) Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (companion.unreadCount > 0) Badge(Modifier.semantics { contentDescription = unreadLabel }) {
                            Text(if (companion.unreadCount > 99) "99+" else companion.unreadCount.toString())
                        } else if (companion.pinned) DroyIcon(R.drawable.native_companion_push_pin,
                            stringResource(R.string.native_companion_pinned))
                    }
                }
            }
        }
    }
    state.companions.firstOrNull { it.id == menuId }?.let { companion ->
        ModalBottomSheet(onDismissRequest = { menuId = null }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(companion.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp, 0.dp, 8.dp, 16.dp))
                TextButton(onClick = { model.setPinned(companion.id, !companion.pinned); menuId = null },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-companion-pin")) {
                    Text(stringResource(if (companion.pinned) R.string.native_companion_unpin else R.string.native_companion_pin))
                }
                TextButton(onClick = { model.markConversationRead(companion.id); menuId = null }, enabled = companion.unreadCount > 0,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("native-companion-mark-read")) {
                    Text(stringResource(R.string.native_companion_mark_read))
                }
                TextButton(onClick = { menuId = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.native_companion_cancel))
                }
            }
        }
    }
}

@Composable
private fun companionDraftPreview(draft: NativeCompanionDraft): String = when {
    draft.text.isNotBlank() -> draft.text
    draft.voice != null -> stringResource(R.string.native_companion_voice)
    draft.stickerId != null -> stringResource(R.string.native_companion_stickers)
    draft.attachments.isNotEmpty() -> draft.attachments.first().name
    draft.replyTo != null -> companionReplyPreview(draft.replyTo)
    else -> ""
}
