package io.droyagent.app.nativeui.companion.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.droyagent.app.R
import io.droyagent.app.nativeui.companion.data.*
import io.droyagent.app.nativeui.ui.DroyIcon
import io.droyagent.app.nativeui.ui.DroyIconAction
import kotlin.math.ceil

@Composable
internal fun CompanionMessageRow(
    message: NativeCompanionMessage,
    companion: NativeCompanion,
    userProfile: NativeUserProfile,
    quoted: NativeCompanionReplyPreview?,
    sticker: NativeCompanionSticker?,
    loader: CompanionImageLoader,
    onProfile: () -> Unit,
    onUserProfile: () -> Unit,
    onMenu: (NativeCompanionMessage) -> Unit,
    onRetry: () -> Unit,
    onImage: (String, String) -> Unit,
    onAttachment: ((NativeCompanionAttachment) -> Unit)?,
    onVoice: ((NativeCompanionVoice) -> Unit)?,
    sending: Boolean,
    onRetryVoiceTranscript: ((String) -> Unit)? = null,
    onTapContent: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (message.type == "task") return
    val user = message.role == "user"
    val haptics = LocalHapticFeedback.current
    val currentMenu by rememberUpdatedState(onMenu)
    val currentTap by rememberUpdatedState(onTapContent)
    val actionsLabel = stringResource(R.string.native_companion_actions)
    val retryLabel = stringResource(R.string.native_companion_send_again)
    val you = userProfile.name.ifBlank { stringResource(R.string.native_companion_you) }
    val time = companionTime(message.createdAt)
    val inlineTime = message.type == "text" && message.attachments.isEmpty() && !time.isNullOrBlank() && message.status !in setOf("pending", "sending")
    Row(modifier.fillMaxWidth().testTag("native-companion-message:${message.id}")
        .pointerInput(message.id) {
            detectTapGestures(onTap = { currentTap() }, onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); currentMenu(message) })
        }.semantics {
            customActions = listOf(CustomAccessibilityAction(actionsLabel) { currentMenu(message); true })
        }, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!user) CompanionAvatar(companion.name, companion.avatarUrl, loader, size = 40.dp,
            onClick = onProfile, label = stringResource(R.string.native_companion_profile_of, companion.name))
        if (user) Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.weight(1f, fill = false), shape = MaterialTheme.shapes.large,
                color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                contentColor = if (user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) {
                Column(Modifier.widthIn(max = 400.dp).padding(horizontal = if (message.type == "voice") 0.dp else 14.dp, vertical = if (message.type == "voice") 0.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (message.replyTo != null && (user || message.quoteReply)) {
                        Surface(shape = MaterialTheme.shapes.small,
                            color = if (user) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(stringResource(R.string.native_companion_reply), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(quoted?.text?.takeIf { it.isNotBlank() } ?: quoted?.let { companionReplyPreview(it) }
                                    ?: stringResource(R.string.native_companion_quoted_unavailable),
                                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    when (message.type) {
                        "voice" -> CompanionVoiceContent(message, onVoice, time)
                        "sticker" -> {
                            if (sticker != null) {
                                FilledTonalButton(onClick = { onImage(sticker.url, sticker.label) },
                                    shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(0.dp)) {
                                    CompanionImage(sticker.url, sticker.label.ifBlank { stringResource(R.string.native_companion_stickers) },
                                        loader, Modifier.size(144.dp), ContentScale.Fit) { loading ->
                                        if (loading) CircularProgressIndicator(Modifier.size(24.dp))
                                        else Text(stringResource(R.string.native_companion_image_unavailable))
                                    }
                                }
                            } else Text(stringResource(R.string.native_companion_stickers))
                        }
                        "text", "image", "file" -> if (message.text.isNotEmpty()) {
                            if (inlineTime) Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(message.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                                Text(time!!, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else Text(message.text, style = MaterialTheme.typography.bodyLarge)
                        }
                        else -> {
                            Text(stringResource(R.string.native_companion_unknown_message), style = MaterialTheme.typography.labelMedium)
                            if (message.text.isNotEmpty()) Text(message.text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    if (message.type == "image" && message.attachments.isEmpty() && message.text.isEmpty())
                        DroyIcon(R.drawable.native_companion_image, stringResource(R.string.native_companion_image), Modifier.size(48.dp))
                    message.attachments.filterNot { message.type == "voice" && it.id == message.voice?.attachmentId }.forEach { attachment ->
                        CompanionAttachmentContent(attachment, loader, onImage, onAttachment)
                    }
                    if (!inlineTime && message.type != "voice") Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (time != null) Text(time, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (message.status in setOf("pending", "sending")) Text(stringResource(R.string.native_companion_sending),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (user && message.status == "failed") IconButton(onClick = onRetry, enabled = !sending,
                    modifier = Modifier.testTag("native-companion-retry-send")) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.error) {
                        DroyIcon(R.drawable.native_ui_warning, retryLabel)
                    }
                }
            }
            }
        }
        if (user) CompanionAvatar(you, userProfile.avatarUrl, loader, size = 40.dp, onClick = onUserProfile,
            label = stringResource(R.string.native_companion_my_profile))
        if (!user) Spacer(Modifier.width(8.dp))
    }
}

@Composable
internal fun companionReplyPreview(preview: NativeCompanionReplyPreview): String = preview.text.ifBlank {
    stringResource(when (preview.type) {
        "voice" -> R.string.native_companion_voice
        "sticker" -> R.string.native_companion_stickers
        "task" -> R.string.native_companion_task
        "image" -> R.string.native_companion_image
        "file" -> R.string.native_companion_attachments
        else -> R.string.native_companion_unknown_message
    })
}

@Composable
private fun CompanionAttachmentContent(
    attachment: NativeCompanionAttachment,
    loader: CompanionImageLoader,
    onImage: (String, String) -> Unit,
    onAttachment: ((NativeCompanionAttachment) -> Unit)?,
) {
    if (attachment.mediaType.startsWith("image/")) {
        var decodedSize by remember(attachment.url) { mutableStateOf(cachedCompanionImageSize(attachment.url)?.let { androidx.compose.ui.unit.IntSize(it.first, it.second) }) }
        BoxWithConstraints(Modifier.widthIn(max = 240.dp)) {
            val preview = io.droyagent.app.nativeui.ui.messageImageSize(
                decodedSize?.width ?: attachment.width, decodedSize?.height ?: attachment.height, maxWidth.value)
            Surface(onClick = { onImage(attachment.url, attachment.name) }, shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.size(preview.width.dp, preview.height.dp).testTag("native-companion-image:${attachment.id}")) {
                CompanionImage(attachment.url, attachment.name, loader, Modifier.fillMaxSize(), ContentScale.Fit,
                    onImageSize = { width, height -> decodedSize = androidx.compose.ui.unit.IntSize(width, height) }) { loading ->
                    if (loading) CircularProgressIndicator(Modifier.size(24.dp)) else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        DroyIcon(R.drawable.native_companion_image, null)
                        Text(stringResource(R.string.native_companion_image_unavailable), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    } else {
        OutlinedButton(onClick = { onAttachment?.invoke(attachment) }, enabled = onAttachment != null,
            shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            contentPadding = PaddingValues(12.dp)) {
            DroyIcon(R.drawable.native_ui_description, null)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(attachment.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (attachment.bytes >= 1024 * 1024) stringResource(R.string.native_companion_size_mb, attachment.bytes.toDouble() / (1024 * 1024))
                    else stringResource(R.string.native_companion_size_kb, ceil(attachment.bytes.coerceAtLeast(0) / 1024.0).toLong()),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun CompanionVoiceContent(message: NativeCompanionMessage, onVoice: ((NativeCompanionVoice) -> Unit)?, time: String?) {
    val voice = message.voice
    io.droyagent.app.nativeui.ui.VoiceMessageContent(voice?.durationMs,
        onPlay = { if (voice != null) onVoice?.invoke(voice) }, enabled = onVoice != null && voice?.url != null, time = time)

}

@Composable
internal fun CompanionImageDialog(url: String, label: String, loader: CompanionImageLoader, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize().testTag("native-companion-image-preview")) {
                CompanionImage(url, label, loader, Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 80.dp),
                    contentScale = ContentScale.Fit, maximumDimension = 2048) { loading ->
                    if (loading) CircularProgressIndicator() else Text(stringResource(R.string.native_companion_image_unavailable))
                }
                DroyIconAction(R.drawable.native_ui_close, stringResource(R.string.native_companion_close), onClose,
                    Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp))
            }
        }
    }
}
