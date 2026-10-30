package io.droyagent.app.nativeui.ui

import android.view.Window
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF302C35),
    surfaceTint = Color(0xFF4A4FC8),
    primary = Color(0xFF4A4FC8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE1E0FF),
    onPrimaryContainer = Color(0xFF393562),
    secondary = Color(0xFF5D5C72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E0FA),
    onSecondaryContainer = Color(0xFF363347),
    tertiary = Color(0xFF795369),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8EC),
    onTertiaryContainer = Color(0xFF2E1125),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surface = Color(0xFFFEF7FF),
    surfaceDim = Color(0xFFDED8E0),
    surfaceBright = Color(0xFFFEF7FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F1FA),
    surfaceContainer = Color(0xFFF1EDF4),
    surfaceContainerHigh = Color(0xFFEDE6EE),
    surfaceContainerHighest = Color(0xFFE7E0E8),
    onSurface = Color(0xFF302C35),
    onSurfaceVariant = Color(0xFF5D5764),
    outline = Color(0xFF8D8793),
    outlineVariant = Color(0xFFCBC4CF),
    inverseSurface = Color(0xFF322F35),
    inverseOnSurface = Color(0xFFF5EFF7),
    inversePrimary = Color(0xFFBFC1FF),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    background = Color(0xFF151218),
    onBackground = Color(0xFFE7E0E8),
    surfaceTint = Color(0xFFBFC1FF),
    primary = Color(0xFFBFC1FF),
    onPrimary = Color(0xFF14149A),
    primaryContainer = Color(0xFF3035AF),
    onPrimaryContainer = Color(0xFFE1E0FF),
    secondary = Color(0xFFC6C4DD),
    onSecondary = Color(0xFF2E2F42),
    secondaryContainer = Color(0xFF45455A),
    onSecondaryContainer = Color(0xFFE2E0FA),
    tertiary = Color(0xFFE9B9D3),
    onTertiary = Color(0xFF46263A),
    tertiaryContainer = Color(0xFF5F3C51),
    onTertiaryContainer = Color(0xFFFFD8EC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surface = Color(0xFF151218),
    surfaceDim = Color(0xFF151218),
    surfaceBright = Color(0xFF3B383E),
    surfaceContainerLowest = Color(0xFF0F0D13),
    surfaceContainerLow = Color(0xFF1D1B20),
    surfaceContainer = Color(0xFF211F24),
    surfaceContainerHigh = Color(0xFF2B292F),
    surfaceContainerHighest = Color(0xFF36343A),
    onSurface = Color(0xFFE7E0E8),
    onSurfaceVariant = Color(0xFFCBC4CF),
    outline = Color(0xFF948F99),
    outlineVariant = Color(0xFF49454E),
    inverseSurface = Color(0xFFE7E0E8),
    inverseOnSurface = Color(0xFF322F35),
    inversePrimary = Color(0xFF4A4FC8),
    scrim = Color(0xFF000000),
)

private val DroyTypography = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 36.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

private val DroyShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** One theme supplies standard components and Droy's custom chat surfaces. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DroyTheme(window: Window? = null, dark: Boolean? = null, content: @Composable () -> Unit) {
    val view = LocalView.current
    val context = LocalContext.current
    val preferences = remember(context.applicationContext) { context.getSharedPreferences("product", Context.MODE_PRIVATE) }
    var mode by remember(preferences) { mutableStateOf(preferences.getString("appearanceMode", "system")) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "appearanceMode") mode = prefs.getString(key, "system")
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val systemDark = isSystemInDarkTheme()
    val useDark = dark ?: when (mode) { "light" -> false; "dark" -> true; else -> systemDark }
    SideEffect {
        window?.let {
            WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = !useDark
                isAppearanceLightNavigationBars = !useDark
            }
        }
    }
    MaterialTheme(
        colorScheme = if (useDark) DarkColors else LightColors,
        motionScheme = MotionScheme.expressive(),
        shapes = DroyShapes,
        typography = DroyTypography,
        content = content,
    )
}
