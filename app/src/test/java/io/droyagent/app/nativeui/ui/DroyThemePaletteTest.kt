package io.droyagent.app.nativeui.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class DroyThemePaletteTest {
    @Test
    fun presetTextRolesRemainReadableInBothModes() {
        for (palette in listOf("purple", "teal", "amber", "rose")) {
            for (dark in listOf(false, true)) {
                val colors = droyPresetColorScheme(palette, dark)
                val roles = listOf(
                    colors.onPrimary to colors.primary,
                    colors.onPrimaryContainer to colors.primaryContainer,
                    colors.onSecondary to colors.secondary,
                    colors.onSecondaryContainer to colors.secondaryContainer,
                    colors.onTertiary to colors.tertiary,
                    colors.onTertiaryContainer to colors.tertiaryContainer,
                    colors.onError to colors.error,
                    colors.onErrorContainer to colors.errorContainer,
                    colors.onBackground to colors.background,
                    colors.onSurface to colors.surface,
                    colors.onSurface to colors.surfaceContainer,
                    colors.onSurfaceVariant to colors.surfaceContainerLow,
                    colors.onSurfaceVariant to colors.surfaceContainerHighest,
                    colors.inverseOnSurface to colors.inverseSurface,
                )
                roles.forEachIndexed { index, (foreground, background) ->
                    val contrast = contrast(foreground, background)
                    assertTrue("$palette dark=$dark role=$index contrast=$contrast", contrast >= 4.5)
                }
            }
        }
    }

    @Test
    fun unknownPreferenceRetainsBrandColors() {
        for (dark in listOf(false, true)) {
            assertEquals(droyPresetColorScheme("purple", dark), droyPresetColorScheme("unknown", dark))
        }
    }

    private fun contrast(foreground: Color, background: Color): Double {
        fun luminance(color: Color): Double {
            fun linear(component: Float): Double = component.toDouble().let {
                if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
        }
        val a = luminance(foreground)
        val b = luminance(background)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }
}
