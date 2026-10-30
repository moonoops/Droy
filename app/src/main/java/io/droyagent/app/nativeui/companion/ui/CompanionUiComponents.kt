package io.droyagent.app.nativeui.companion.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import android.text.format.DateUtils
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.companion.data.*
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeRuntimeState
import io.droyagent.app.nativeui.ui.DroyIcon
import io.droyagent.app.nativeui.ui.DroyIconAction
import java.io.IOException
import java.text.DateFormat
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

typealias CompanionImageLoader = suspend (String) -> ByteArray
typealias CompanionAvatarPicker = (String?, (String) -> Unit) -> Unit

private object CompanionImages {
    val cache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
}

/** Rows share avatars; serialize only identical in-flight decodes, never unrelated images. */
private class CompanionImageLoad {
    val mutex = Mutex()
    var users = 0
}
private val companionImageLoads = mutableMapOf<String, CompanionImageLoad>()
private suspend fun <T> withCompanionImageLoad(key: String, action: suspend () -> T): T {
    val entry = synchronized(companionImageLoads) {
        companionImageLoads.getOrPut(key, ::CompanionImageLoad).also { it.users++ }
    }
    return try { entry.mutex.withLock { action() } }
    finally { synchronized(companionImageLoads) {
        if (--entry.users == 0) companionImageLoads.remove(key)
    } }
}

internal fun clearCompanionImageCache() {
    synchronized(CompanionImages) {
        io.droyagent.app.nativeui.companion.maintenance.NativeCompanionWriteBarrier.imageGeneration++
        CompanionImages.cache.evictAll()
    }
}

internal fun cachedCompanionImageSize(url: String): Pair<Int, Int>? =
    CompanionImages.cache.get("$url@1024@null")?.let { it.width to it.height }

private data class ImageResult(val bitmap: ImageBitmap? = null, val loading: Boolean = true)

@Composable
internal fun CompanionImage(
    url: String,
    label: String?,
    loader: CompanionImageLoader,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maximumDimension: Int = 1024,
    alignment: Alignment = Alignment.Center,
    cropTargetPixels: Int? = null,
    onImageSize: ((Int, Int) -> Unit)? = null,
    fallback: @Composable (Boolean) -> Unit,
) {
    val currentLoader by rememberUpdatedState(loader)
    val currentImageSize by rememberUpdatedState(onImageSize)
    val key = "$url@$maximumDimension@$cropTargetPixels"
    val cached = remember(key) { CompanionImages.cache.get(key)?.asImageBitmap() }
    val result by produceState(ImageResult(cached, cached == null), key) {
        if (cached != null) return@produceState
        val generation = io.droyagent.app.nativeui.companion.maintenance.NativeCompanionWriteBarrier.imageGeneration
        if (io.droyagent.app.nativeui.companion.maintenance.NativeCompanionWriteBarrier.frozen) return@produceState
        value = withContext(Dispatchers.IO) { withCompanionImageLoad(key) {
            CompanionImages.cache.get(key)?.let { return@withCompanionImageLoad ImageResult(it.asImageBitmap(), loading = false) }
            val bitmap = try {
                val bytes = currentLoader(url)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth !in 1..32768 || bounds.outHeight !in 1..32768) null else {
                    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
                    options.inSampleSize = companionImageSampleSize(bounds.outWidth, bounds.outHeight, maximumDimension, cropTargetPixels)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { decoded ->
                        val orientation = try { ByteArrayInputStream(bytes).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }
                        catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
                        val matrix = Matrix().apply {
                            when (orientation) {
                                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                            }
                        }
                        val oriented = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                        if (oriented !== decoded) decoded.recycle()
                        synchronized(CompanionImages) {
                            if (!io.droyagent.app.nativeui.companion.maintenance.NativeCompanionWriteBarrier.frozen &&
                                generation == io.droyagent.app.nativeui.companion.maintenance.NativeCompanionWriteBarrier.imageGeneration)
                                CompanionImages.cache.put(key, oriented)
                        }
                        oriented
                    }
                }
            } catch (_: NativeApiException) {
                null
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            ImageResult(bitmap?.asImageBitmap(), loading = false)
        } }
    }
    if (onImageSize != null) LaunchedEffect(key, result.bitmap) {
        result.bitmap?.let { currentImageSize?.invoke(it.width, it.height) }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        result.bitmap?.let { Image(it, label, Modifier.matchParentSize(), contentScale = contentScale, alignment = alignment) }
            ?: fallback(result.loading)
    }
}

@Composable
internal fun CompanionAvatar(
    name: String,
    url: String,
    loader: CompanionImageLoader,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    onClick: (() -> Unit)? = null,
    label: String? = null,
    enabled: Boolean = true,
) {
    val targetPixels = with(LocalDensity.current) { size.roundToPx() }
    val avatarLabel = label ?: stringResource(R.string.native_companion_avatar_of, name)
    val initial = remember(name) { if (name.isEmpty()) null else name.substring(0, name.offsetByCodePoints(0, 1)) }
    val action = if (onClick == null) Modifier else Modifier.clickable(enabled = enabled, role = Role.Button, onClickLabel = avatarLabel, onClick = onClick)
    Box(modifier.size(size.coerceAtLeast(48.dp)).then(action).semantics { contentDescription = avatarLabel }, contentAlignment = Alignment.Center) {
        CompanionImage(url, null, loader,
            Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), maximumDimension = 384, cropTargetPixels = targetPixels) {
            if (initial == null) DroyIcon(R.drawable.native_companion_person, null)
            else Text(initial, color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = if (size >= 80.dp) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
internal fun CompanionTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            DroyIconAction(R.drawable.native_companion_arrow_back, stringResource(R.string.native_companion_back), onBack)
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            actions()
        }
    }
}

@Composable
internal fun CompanionRuntime(runtime: NativeRuntimeState, onStart: () -> Unit) {
    if (runtime.ready || runtime.active) return
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(runtime.status.ifBlank { stringResource(if (runtime.active) R.string.native_engine_starting else R.string.native_engine_unavailable) })
            if (runtime.canStart) Button(onClick = onStart, modifier = Modifier.heightIn(min = 56.dp).testTag("native-companion-start-runtime")) {
                Text(stringResource(R.string.native_start_engine))
            }
        }
    }
}

internal fun companionNeedsModel(code: String?): Boolean = code in setOf("MISSING_CREDENTIAL", "MODEL_NOT_CONFIGURED", "INVALID_MODEL_SELECTION", "COMPANION_MODEL_REQUIRED")

@Composable
internal fun CompanionError(code: String?, onDismiss: () -> Unit, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    if (code == null) return
    val message = when {
        code in setOf("network_error", "stream_disconnected", "NATIVE_SERVICE_NOT_READY") -> R.string.native_companion_error_network
        code == "storage_error" -> R.string.native_companion_error_storage
        code == "malformed_response" -> R.string.native_companion_error_response
        code == "pending_send_conflict" || code == "COMPANION_MESSAGE_CONFLICT" -> R.string.native_companion_error_conflict
        code == "companion_stop_pending" -> R.string.native_companion_stop_pending
        code == "COMPANION_STICKER_PACK_TOO_LARGE" -> R.string.native_companion_sticker_pack_too_large
        code == "COMPANION_STICKER_PACK_UNSUPPORTED" -> R.string.native_companion_sticker_pack_unsupported
        code == "COMPANION_STICKER_PACK_INVALID" -> R.string.native_companion_sticker_pack_invalid
        code.contains("SETUP") -> R.string.native_companion_setup_required
        companionNeedsModel(code) -> R.string.native_companion_configure_model
        code.contains("ATTACHMENT") || code.contains("UPLOAD") -> R.string.native_companion_error_attachment
        code.contains("NAME") -> R.string.native_companion_error_name
        code == "COMPANION_NOT_FOUND" -> R.string.native_companion_error_profile
        code == "runtime_start_failed" -> R.string.native_engine_start_failed
        code == "runtime_unavailable" -> R.string.native_engine_unavailable
        else -> R.string.native_companion_error_generic
    }
    io.droyagent.app.nativeui.ui.DroyErrorNotice(stringResource(message), onDismiss, onRetry,
        modifier.testTag("native-companion-error"))
}

@Composable
internal fun companionMessagePreview(message: NativeCompanionMessage?): String = when {
    message == null -> ""
    message.type == "voice" -> stringResource(R.string.native_companion_voice)
    message.type == "sticker" -> stringResource(R.string.native_companion_stickers)
    message.type == "task" -> message.task?.title ?: stringResource(R.string.native_companion_task)
    message.text.isNotBlank() -> message.text
    message.type == "image" || message.attachments.any { it.mediaType.startsWith("image/") } -> stringResource(R.string.native_companion_image)
    message.attachments.isNotEmpty() || message.type == "file" -> stringResource(R.string.native_companion_attachments)
    else -> stringResource(R.string.native_companion_unknown_message)
}

@Composable
internal fun companionStatusLabel(status: NativeCompanionStatus, stopping: Boolean = false): String? = when {
    stopping -> stringResource(R.string.native_companion_stopping)
    status.phase == "typing" || status.phase == "thinking" -> stringResource(R.string.native_companion_typing)
    status.phase == "waiting" -> stringResource(R.string.native_companion_waiting)
    status.phase == "error" -> stringResource(R.string.native_companion_reply_failed)
    status.chatExecution?.quiescent == false -> stringResource(R.string.native_companion_working)
    else -> null
}

@Composable
internal fun companionTime(value: String?, dateOnly: Boolean = false): String? {
    val locale = LocalContext.current.resources.configuration.locales[0]
    return remember(value, locale, dateOnly) {
        if (value.isNullOrEmpty()) null else try {
            val format = if (dateOnly) DateFormat.getDateInstance(DateFormat.SHORT, locale) else DateFormat.getTimeInstance(DateFormat.SHORT, locale)
            format.format(Date.from(Instant.parse(value)))
        } catch (_: DateTimeParseException) { null }
    }
}

@Composable
internal fun companionConversationTime(value: String?): String? {
    val locale = LocalContext.current.resources.configuration.locales[0]
    return remember(value, locale) {
        if (value.isNullOrEmpty()) null else try {
            val date = Date.from(Instant.parse(value))
            val format = if (DateUtils.isToday(date.time)) DateFormat.getTimeInstance(DateFormat.SHORT, locale)
                else DateFormat.getDateInstance(DateFormat.SHORT, locale)
            format.format(date)
        } catch (_: DateTimeParseException) { null }
    }
}

@Composable
internal fun companionTaskState(value: String): String = stringResource(when (value) {
    "queued" -> R.string.native_companion_task_queued
    "running" -> R.string.native_companion_task_running
    "completed" -> R.string.native_companion_task_completed
    "failed" -> R.string.native_companion_task_failed
    "cancelled" -> R.string.native_companion_task_cancelled
    "needs_input" -> R.string.native_companion_task_needs_input
    "cancelling" -> R.string.native_companion_task_cancelling
    else -> R.string.native_companion_task
})
