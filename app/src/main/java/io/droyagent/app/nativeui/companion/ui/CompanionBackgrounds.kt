package io.droyagent.app.nativeui.companion.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import io.droyagent.app.R
import io.droyagent.app.nativeui.media.NativeAvatarResult
import org.json.JSONObject

internal data class CompanionBackgroundPreset(val id: String, @param:StringRes val label: Int)

internal val companionBackgroundPresets = listOf(
    CompanionBackgroundPreset("01", R.string.native_companion_background_paper_light),
    CompanionBackgroundPreset("02", R.string.native_companion_background_paper_dark),
    CompanionBackgroundPreset("03", R.string.native_companion_background_mist_light),
    CompanionBackgroundPreset("04", R.string.native_companion_background_mist_dark),
    CompanionBackgroundPreset("05", R.string.native_companion_background_leaves_light),
    CompanionBackgroundPreset("06", R.string.native_companion_background_leaves_dark),
    CompanionBackgroundPreset("07", R.string.native_companion_background_lines_light),
    CompanionBackgroundPreset("08", R.string.native_companion_background_lines_dark),
)

internal fun companionBackgroundPresetUrl(id: String, thumbnail: Boolean = false): String? =
    id.takeIf { candidate -> companionBackgroundPresets.any { it.id == candidate } }
        ?.let { "/droy/companions/assets/wallpaper-$it${if (thumbnail) "-thumb" else ""}.webp" }

internal fun companionBackgroundUrl(value: JSONObject): String? = when (value.optString("type")) {
    "preset" -> companionBackgroundPresetUrl(value.optString("id"))
    "upload" -> value.optString("url").takeIf(String::isNotBlank)
    else -> null
}

internal fun companionUploadedBackground(result: NativeAvatarResult): String = JSONObject()
    .put("type", "upload").put("id", result.attachmentId).put("url", result.url)
    .put("position", JSONObject().put("x", 50).put("y", 50)).toString()

@Composable
internal fun CompanionBackgroundImage(url: String, loader: CompanionImageLoader, modifier: Modifier,
    alignment: Alignment = Alignment.Center) {
    BoxWithConstraints(modifier) {
        CompanionImage(url, null, loader, Modifier.fillMaxSize(), alignment = alignment,
            cropTargetSize = IntSize(constraints.maxWidth.coerceAtLeast(1), constraints.maxHeight.coerceAtLeast(1))) { }
    }
}
