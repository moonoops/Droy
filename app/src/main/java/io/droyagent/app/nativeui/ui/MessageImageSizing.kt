package io.droyagent.app.nativeui.ui

internal data class MessageImageSize(val width: Float, val height: Float)

/** Scale the whole image into a chat-sized preview without changing its aspect ratio. */
internal fun messageImageSize(width: Int?, height: Int?, maxWidth: Float = 240f, maxHeight: Float = 220f): MessageImageSize {
    val ratio = if (width != null && height != null && width > 0 && height > 0) width.toFloat() / height else 4f / 3f
    val previewWidth = minOf(maxWidth, maxHeight * ratio)
    return MessageImageSize(previewWidth, previewWidth / ratio)
}
