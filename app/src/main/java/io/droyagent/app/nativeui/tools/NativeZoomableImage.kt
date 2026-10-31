package io.droyagent.app.nativeui.tools

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import io.droyagent.app.R

@Composable
internal fun NativeZoomableImage(bitmap: ImageBitmap, description: String, modifier: Modifier = Modifier) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    fun bounded(value: Offset, zoom: Float): Offset {
        if (viewport.width == 0 || viewport.height == 0) return Offset.Zero
        val fit = minOf(viewport.width.toFloat() / bitmap.width, viewport.height.toFloat() / bitmap.height)
        val x = ((bitmap.width * fit * zoom - viewport.width) / 2).coerceAtLeast(0f)
        val y = ((bitmap.height * fit * zoom - viewport.height) / 2).coerceAtLeast(0f)
        return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    }
    fun zoom(value: Float) { scale = value.coerceIn(1f, 5f); offset = bounded(offset, scale) }
    val zoomIn = stringResource(R.string.native_work_zoom_in)
    val zoomOut = stringResource(R.string.native_work_zoom_out)
    val reset = stringResource(R.string.native_work_zoom_reset)
    Box(modifier.clipToBounds().onSizeChanged { viewport = it; offset = bounded(offset, scale) }
        .semantics { customActions = listOf(
            CustomAccessibilityAction(zoomIn) { zoom(scale + 1f); true },
            CustomAccessibilityAction(zoomOut) { zoom(scale - 1f); true },
            CustomAccessibilityAction(reset) { zoom(1f); true }) }
        .pointerInput(bitmap) {
            detectTransformGestures { centroid, pan, change, _ ->
                val next = (scale * change).coerceIn(1f, 5f)
                val ratio = next / scale
                val center = Offset(viewport.width / 2f, viewport.height / 2f)
                offset = bounded(offset * ratio + (centroid - center) * (1 - ratio) + pan, next)
                scale = next
            }
        }.pointerInput(bitmap) {
            detectTapGestures(onDoubleTap = { point ->
                if (scale > 1f) zoom(1f)
                else {
                    scale = 3f
                    offset = bounded((Offset(viewport.width / 2f, viewport.height / 2f) - point) * scale, scale)
                }
            })
        }) {
        Image(bitmap, description, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
        })
    }
}
