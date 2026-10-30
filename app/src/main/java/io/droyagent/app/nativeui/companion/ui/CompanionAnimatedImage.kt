package io.droyagent.app.nativeui.companion.ui

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageView
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.droyagent.app.nativeui.companion.maintenance.NativeCompanionWriteBarrier
import java.io.IOException
import java.nio.ByteBuffer
import java.util.WeakHashMap
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CompanionGifData(val bytes: ByteArray, val size: IntSize)

internal fun companionIsGif(bytes: ByteArray): Boolean = bytes.size >= 6 &&
    bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() &&
    bytes[3] == '8'.code.toByte() && bytes[4] in byteArrayOf('7'.code.toByte(), '9'.code.toByte()) && bytes[5] == 'a'.code.toByte()

/** A malformed GIF must not silently fall back to a still first-frame decode. */
internal fun companionGifSize(bytes: ByteArray): IntSize {
    require(companionIsGif(bytes) && bytes.size >= 13 && bytes.size <= 8 * 1024 * 1024)
    val width = (bytes[6].toInt() and 255) or ((bytes[7].toInt() and 255) shl 8)
    val height = (bytes[8].toInt() and 255) or ((bytes[9].toInt() and 255) shl 8)
    require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_000_000)
    return IntSize(width, height)
}

internal fun companionGifTargetSize(size: IntSize, maximumDimension: Int, cropTargetPixels: Int?, cropTargetSize: IntSize?): IntSize {
    val sample = cropTargetSize?.let { companionImageCropSampleSize(size.width, size.height, it.width, it.height) }
        ?: companionImageSampleSize(size.width, size.height, maximumDimension, cropTargetPixels)
    return IntSize((size.width / sample).coerceAtLeast(1), (size.height / sample).coerceAtLeast(1))
}

internal fun companionGifShouldPlay(attached: Boolean, shown: Boolean, focused: Boolean,
    lifecycleStarted: Boolean, visible: Boolean, admitted: Boolean): Boolean =
    attached && shown && focused && lifecycleStarted && visible && admitted

private object CompanionGifViews {
    val views = WeakHashMap<CompanionGifImageView, Unit>()
    fun add(view: CompanionGifImageView) = synchronized(views) { views[view] = Unit }
    fun remove(view: CompanionGifImageView) = synchronized(views) { views.remove(view) }
    fun clear() {
        val pending = synchronized(views) { views.keys.toList() }
        val generation = NativeCompanionWriteBarrier.imageGeneration
        val stop = Runnable { pending.forEach { it.retire(generation) } }
        if (Looper.myLooper() == Looper.getMainLooper()) stop.run() else Handler(Looper.getMainLooper()).post(stop)
    }
}

internal fun clearCompanionAnimatedImages() = CompanionGifViews.clear()

@Composable
internal fun CompanionAnimatedImage(data: CompanionGifData, label: String?, modifier: Modifier,
    contentScale: ContentScale, alignment: Alignment, maximumDimension: Int, cropTargetPixels: Int?,
    cropTargetSize: IntSize?, fallback: @Composable (Boolean) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val direction = LocalLayoutDirection.current
    val target = remember(data, maximumDimension, cropTargetPixels, cropTargetSize) {
        companionGifTargetSize(data.size, maximumDimension, cropTargetPixels, cropTargetSize)
    }
    val access by NativeCompanionWriteBarrier.imageAccess.collectAsState()
    val generation = access.generation
    var loading by remember(data, target, access) { mutableStateOf(true) }
    var decoded by remember(data, target, access) { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(data, target, access) {
        decoded = withContext(Dispatchers.IO) {
            if (NativeCompanionWriteBarrier.frozen || generation != NativeCompanionWriteBarrier.imageGeneration) return@withContext null
            val drawable = try {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(data.bytes))) { decoder, _, _ ->
                    decoder.setTargetSize(target.width, target.height)
                }
            } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }
            if (NativeCompanionWriteBarrier.frozen || generation != NativeCompanionWriteBarrier.imageGeneration) null else drawable
        }
        loading = false
    }
    DisposableEffect(decoded) {
        val drawable = decoded
        onDispose { (drawable as? AnimatedImageDrawable)?.stop(); drawable?.callback = null }
    }
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        if (decoded == null) fallback(loading)
        else AndroidView(factory = { CompanionGifImageView(it) },
            modifier = Modifier.matchParentSize().then(if (label == null) Modifier else Modifier.semantics { contentDescription = label }),
            update = { it.bind(decoded, lifecycle, generation, contentScale, alignment, direction) },
            onRelease = { it.release() })
    }
}

/** Each image owns its decoder/playhead; the cache contains only immutable encoded bytes. */
internal class CompanionGifImageView(context: Context) : ImageView(context), ViewTreeObserver.OnPreDrawListener {
    private var image: Drawable? = null
    private var ownerLifecycle: Lifecycle? = null
    private var generation = -1L
    private var scale: ContentScale = ContentScale.Fit
    private var alignment: Alignment = Alignment.Center
    private var direction: LayoutDirection = LayoutDirection.Ltr
    private val visibleRect = Rect()
    private val observer = LifecycleEventObserver { _, _ -> synchronizePlayback() }
    private var observingDraws = false

    init { scaleType = ScaleType.MATRIX; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun bind(value: Drawable?, lifecycle: Lifecycle, generation: Long, scale: ContentScale,
        alignment: Alignment, direction: LayoutDirection) {
        if (NativeCompanionWriteBarrier.frozen || generation != NativeCompanionWriteBarrier.imageGeneration) { release(); return }
        if (image !== value) {
            (image as? AnimatedImageDrawable)?.stop(); image?.callback = null
            image = value
            (image as? AnimatedImageDrawable)?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            setImageDrawable(image)
        }
        if (ownerLifecycle !== lifecycle) {
            ownerLifecycle?.removeObserver(observer)
            ownerLifecycle = lifecycle
            lifecycle.addObserver(observer)
        }
        this.generation = generation; this.scale = scale; this.alignment = alignment; this.direction = direction
        observeDraws()
        updateMatrix(); synchronizePlayback()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ownerLifecycle?.addObserver(observer)
        image?.let(::setImageDrawable)
        observeDraws()
        synchronizePlayback()
    }

    override fun onDetachedFromWindow() {
        pause()
        ownerLifecycle?.removeObserver(observer)
        stopObservingDraws()
        super.onDetachedFromWindow()
    }

    override fun onPreDraw(): Boolean { synchronizePlayback(); return true }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); synchronizePlayback() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); synchronizePlayback() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); updateMatrix(); synchronizePlayback() }

    private fun updateMatrix() {
        val value = image ?: return
        if (width <= 0 || height <= 0 || value.intrinsicWidth <= 0 || value.intrinsicHeight <= 0) return
        val factor = scale.computeScaleFactor(Size(value.intrinsicWidth.toFloat(), value.intrinsicHeight.toFloat()), Size(width.toFloat(), height.toFloat()))
        val scaled = IntSize((value.intrinsicWidth * factor.scaleX).roundToInt(), (value.intrinsicHeight * factor.scaleY).roundToInt())
        val offset = alignment.align(scaled, IntSize(width, height), direction)
        imageMatrix = Matrix().apply { setScale(factor.scaleX, factor.scaleY); postTranslate(offset.x.toFloat(), offset.y.toFloat()) }
    }

    private fun synchronizePlayback() {
        val animated = image as? AnimatedImageDrawable ?: return
        val visible = width > 0 && height > 0 && alpha > 0 && getGlobalVisibleRect(visibleRect) && !visibleRect.isEmpty
        val allowed = companionGifShouldPlay(isAttachedToWindow, isShown && windowVisibility == View.VISIBLE, hasWindowFocus(),
            ownerLifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true, visible,
            !NativeCompanionWriteBarrier.frozen && generation == NativeCompanionWriteBarrier.imageGeneration)
        if (allowed) {
            if (animated.callback !== this) animated.callback = this
            if (!animated.isRunning) animated.start()
        } else pause()
    }

    private fun pause() { (image as? AnimatedImageDrawable)?.stop(); image?.callback = null }

    private fun observeDraws() {
        if (!isAttachedToWindow || observingDraws) return
        CompanionGifViews.add(this)
        viewTreeObserver.addOnPreDrawListener(this)
        observingDraws = true
    }

    private fun stopObservingDraws() {
        if (observingDraws && viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(this)
        observingDraws = false
        CompanionGifViews.remove(this)
    }

    fun retire(currentGeneration: Long) { if (generation < currentGeneration) release() }

    fun release() {
        pause()
        ownerLifecycle?.removeObserver(observer); ownerLifecycle = null
        stopObservingDraws()
        setImageDrawable(null); image = null
    }
}
