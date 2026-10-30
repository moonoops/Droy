package io.droyagent.app.nativeui.ui

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.droyagent.app.R
import io.droyagent.app.nativeui.data.NativeMessage
import io.droyagent.app.nativeui.data.NativeMessageAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** DSH file references preserve the filename, but do not carry a MIME type. Playback validates the bytes. */
internal fun NativeMessageAttachment.isPlayableAudio(): Boolean = kind == "audio" || mediaType?.startsWith("audio/") == true ||
    kind == "file" && name?.substringAfterLast('.', "")?.lowercase(java.util.Locale.ROOT)?.let {
        it in setOf("wav", "mp3", "m4a", "aac", "ogg", "opus", "flac", "amr", "3gp")
    } == true

internal val LocalWorkbenchAudioOwner = compositionLocalOf<MutableState<String?>?> { null }

internal fun NativeMessage.canOpenAttachment(attachment: NativeMessageAttachment): Boolean =
    !streaming && seq != null && seq >= 0 && !attachment.attachmentId.isNullOrBlank()

@Composable
internal fun MessageAttachments(message: NativeMessage, onOpen: ((NativeMessage, NativeMessageAttachment) -> Unit)?,
    load: (suspend (NativeMessage, NativeMessageAttachment) -> ByteArray)? = null) {
    if (message.attachments.isEmpty()) return
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        message.attachments.forEachIndexed { index, attachment ->
            key(message.id, attachment.attachmentId ?: index) {
                val image = attachment.kind == "image" || attachment.mediaType?.startsWith("image/") == true
                val audio = attachment.isPlayableAudio()
                val name = attachment.name?.takeIf { it.isNotBlank() && !image && !audio }
                    ?: stringResource(if (image) R.string.native_companion_image else if (audio) R.string.native_work_audio else R.string.native_share_file)
                val committed = message.canOpenAttachment(attachment)
                if (audio && load != null) {
                    WorkbenchAudio(message, attachment, load)
                } else {
                    val currentLoad by rememberUpdatedState(load)
                    var previewFailed by remember(message.id, attachment.attachmentId) { mutableStateOf(false) }
                    val thumbnail by produceState<ImageBitmap?>(null, message.id, attachment.attachmentId, committed) {
                        if (image && committed && currentLoad != null) {
                            try {
                                val bytes = currentLoad!!(message, attachment)
                                value = withContext(Dispatchers.Default) {
                                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                                        val options = BitmapFactory.Options()
                                        while (maxOf(bounds.outWidth, bounds.outHeight) / (options.inSampleSize.coerceAtLeast(1) * 2) >= 768)
                                            options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
                                    }
                                }
                            previewFailed = value == null
                            } catch (error: Exception) { if (error is CancellationException) throw error; previewFailed = true }
                        }
                    }
                    if (thumbnail != null) BoxWithConstraints(Modifier.widthIn(max = 240.dp)) {
                        val preview = messageImageSize(thumbnail!!.width, thumbnail!!.height, maxWidth.value)
                        Surface(onClick = { onOpen?.invoke(message, attachment) }, enabled = committed && onOpen != null,
                            color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.size(preview.width.dp, preview.height.dp)
                                .testTag("native-message-attachment:${message.id}:$index")) {
                            Image(thumbnail!!, name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        }
                    } else OutlinedCard(onClick = { onOpen?.invoke(message, attachment) }, enabled = committed && onOpen != null,
                        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .testTag("native-message-attachment:${message.id}:$index")) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            DroyIcon(if (image) R.drawable.native_companion_image else R.drawable.native_companion_attach_file, null)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(name, style = MaterialTheme.typography.bodyMedium)
                                if (previewFailed) Text(stringResource(R.string.native_work_preview_failed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                attachment.bytes?.takeIf { it >= 0 }?.let { Text(Formatter.formatShortFileSize(context, it),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            if (committed && onOpen != null) DroyIcon(R.drawable.native_companion_chevron_right, stringResource(R.string.native_companion_open_attachment, name))
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun WorkbenchAudio(message: NativeMessage, attachment: NativeMessageAttachment,
    load: suspend (NativeMessage, NativeMessageAttachment) -> ByteArray) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val owner = LocalWorkbenchAudioOwner.current
    val playbackId = message.id + ":" + attachment.attachmentId
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var loading by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { if (playing) player?.pause(); playing = false } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player?.release(); file?.delete() }
    }
    LaunchedEffect(owner?.value) {
        if (playing && owner != null && owner.value != playbackId) { player?.pause(); playing = false }
    }
    LaunchedEffect(playing) { while (playing) { position = player?.currentPosition ?: 0; delay(250) } }
    suspend fun prepare() {
        loading = true; failed = false
        var temporary: File? = null
        var prepared: MediaPlayer? = null
        try {
            val bytes = load(message, attachment)
            temporary = File.createTempFile("work-audio-", ".media", context.cacheDir)
            withContext(Dispatchers.IO) { temporary!!.writeBytes(bytes) }
            prepared = MediaPlayer().apply {
                setDataSource(temporary!!.absolutePath)
                setOnCompletionListener { playing = false; position = duration }
                setOnErrorListener { broken, _, _ ->
                    playing = false; failed = true; player = null
                    broken.release(); file?.delete(); file = null
                    true
                }
            }
            withContext(Dispatchers.IO) { prepared!!.prepare() }
            player = prepared; file = temporary; duration = prepared!!.duration
        } catch (error: Exception) {
            prepared?.release(); temporary?.delete(); player = null; file = null
            if (error is CancellationException) throw error
            failed = true
        } finally { loading = false }
    }
    fun toggle() {
        if (loading) return
        scope.launch {
            if (player == null) prepare()
            val existing = player ?: return@launch
            if (playing) existing.pause() else {
                if (position >= duration) { existing.seekTo(0); position = 0 }
                owner?.value = playbackId
                existing.start()
            }
            playing = !playing
        }
    }
    Surface(shape = MaterialTheme.shapes.large,
        color = if (message.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (message.role == "user") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface) {
        VoiceMessageContent(attachment.durationMs, onPlay = ::toggle, enabled = !loading,
            playing = playing, failed = failed, labelDurationMs = attachment.durationMs ?: duration.toLong().takeIf { it > 0 })
    }
}
