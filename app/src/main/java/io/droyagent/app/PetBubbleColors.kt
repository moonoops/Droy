package io.droyagent.app

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import io.droyagent.app.nativeui.ui.droyPresetColorScheme

/** The overlay uses the same saved appearance and color roles as the native app. */
class PetBubbleColors private constructor(
    @JvmField val surface: Int,
    @JvmField val onSurface: Int,
    @JvmField val onSurfaceVariant: Int,
    @JvmField val outline: Int,
    @JvmField val primary: Int,
    @JvmField val onPrimary: Int,
    @JvmField val secondaryContainer: Int,
    @JvmField val onSecondaryContainer: Int,
    @JvmField val error: Int,
    @JvmField val errorContainer: Int,
    @JvmField val onErrorContainer: Int,
) {
    companion object {
        @JvmStatic fun from(context: Context): PetBubbleColors {
            val preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE)
            val dark = when (preferences.getString("appearanceMode", "system")) {
                "light" -> false
                "dark" -> true
                else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            }
            val colors = if (preferences.getString("colorSource", "preset") == "dynamic" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else droyPresetColorScheme(preferences.getString("themePalette", "purple") ?: "purple", dark)
            return PetBubbleColors(colors.surfaceContainerHigh.toArgb(), colors.onSurface.toArgb(),
                colors.onSurfaceVariant.toArgb(), colors.outlineVariant.toArgb(), colors.primary.toArgb(),
                colors.onPrimary.toArgb(), colors.secondaryContainer.toArgb(), colors.onSecondaryContainer.toArgb(),
                colors.error.toArgb(), colors.errorContainer.toArgb(), colors.onErrorContainer.toArgb())
        }
    }
}
