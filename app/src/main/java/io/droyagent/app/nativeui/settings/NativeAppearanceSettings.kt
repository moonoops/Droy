package io.droyagent.app.nativeui.settings

import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIcon
import io.droyagent.app.nativeui.ui.DroySettingsSectionTitle
import io.droyagent.app.nativeui.ui.droyPresetColorScheme

@Composable
internal fun NativeAppearanceSettings() {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("product", 0) }
    var mode by remember { mutableStateOf(preferences.getString("appearanceMode", "system") ?: "system") }
    var source by remember { mutableStateOf(preferences.getString("colorSource", "preset") ?: "preset") }
    var palette by remember { mutableStateOf(preferences.getString("themePalette", "purple") ?: "purple") }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            when (key) {
                "appearanceMode" -> mode = prefs.getString(key, "system") ?: "system"
                "colorSource" -> source = prefs.getString(key, "preset") ?: "preset"
                "themePalette" -> palette = prefs.getString(key, "purple") ?: "purple"
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    Column(Modifier.fillMaxWidth().testTag("native-settings-appearance"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DroySettingsSectionTitle(stringResource(R.string.native_settings_appearance))
        ThemePreview()
        AppearanceChoices(mode, listOf("light" to R.string.native_theme_light, "dark" to R.string.native_theme_dark,
            "system" to R.string.native_theme_system), "appearance-mode") {
            preferences.edit().putString("appearanceMode", it).apply()
        }
        DroySettingsSectionTitle(stringResource(R.string.native_theme_color_source))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AppearanceChoices(source, listOf("preset" to R.string.native_theme_preset, "dynamic" to R.string.native_theme_dynamic), "color-source") {
                preferences.edit().putString("colorSource", it).apply()
            }
        }
        // Keep the row's space stable when changing sources; choosing a preset also selects its source.
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("purple" to R.string.native_theme_purple, "teal" to R.string.native_theme_teal,
                "amber" to R.string.native_theme_amber, "rose" to R.string.native_theme_rose).forEach { (value, label) ->
                val colors = droyPresetColorScheme(value, false)
                val selected = palette == value && (source != "dynamic" || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    ToggleButton(checked = selected, onCheckedChange = {
                        preferences.edit().putString("themePalette", value).putString("colorSource", "preset").apply()
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("theme-palette:$value")
                        .semantics { role = Role.RadioButton; this.selected = selected; contentDescription = context.getString(label) },
                        colors = ToggleButtonDefaults.colors(
                            containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer,
                            checkedContainerColor = colors.primary, checkedContentColor = colors.onPrimary)) {
                        if (selected) DroyIcon(R.drawable.native_ui_check, null, Modifier.clearAndSetSemantics {})
                        else Spacer(Modifier.size(24.dp))
                    }
                    Text(stringResource(label), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppearanceChoices(selected: String, choices: List<Pair<String, Int>>, tag: String, select: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        choices.forEachIndexed { index, (value, label) ->
            ToggleButton(checked = selected == value, onCheckedChange = { select(value) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("$tag:$value")
                    .semantics { role = Role.RadioButton; this.selected = selected == value },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    choices.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
                Text(stringResource(label), textAlign = TextAlign.Center)
            }
        }
    }
}

/** A small decorative preview uses the same live roles as the application. */
@Composable
private fun ThemePreview() {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer, shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().height(88.dp).clearAndSetSemantics {}) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = colors.primary, shape = MaterialTheme.shapes.large, modifier = Modifier.size(48.dp)) {}
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = colors.onSurfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.width(64.dp).height(6.dp)) {}
                Surface(color = colors.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(.85f).height(24.dp)) {}
            }
            Surface(color = colors.tertiaryContainer, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.size(40.dp)) {}
        }
    }
}
