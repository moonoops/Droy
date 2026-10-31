package io.droyagent.app

import android.content.Context
/** Neutral fog remains the same over other apps, independent of Droy's saved theme. */
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
    @JvmField val controlSurface: Int = 0xB0FFFFFF.toInt()

    companion object {
        @JvmStatic @Suppress("UNUSED_PARAMETER") fun from(context: Context) = PetBubbleColors(
            0xFFFFFFFF.toInt(), 0xFF27323F.toInt(), 0xFF667381.toInt(), 0xFFFDFEFF.toInt(),
            0xFF465363.toInt(), 0xFFFFFFFF.toInt(), 0x40FFFFFF, 0xFF27323F.toInt(),
            0xFFED655E.toInt(), 0x26ED655E, 0xFFB7413B.toInt())
    }
}
