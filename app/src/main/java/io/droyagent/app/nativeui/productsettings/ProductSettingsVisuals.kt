package io.droyagent.app.nativeui.productsettings

import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** PackageManager is the source of installed app art; missing packages retain a neutral icon. */
@Composable
internal fun InstalledApplicationIcon(packageName: String, size: Dp = 40.dp) {
    val context = LocalContext.current
    val pixels = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, packageName, pixels, context) {
        value = withContext(Dispatchers.IO) {
            try { context.packageManager.getApplicationIcon(packageName).toBitmap(pixels, pixels).asImageBitmap() }
            catch (_: PackageManager.NameNotFoundException) { null }
        }
    }
    val image = bitmap
    if (image != null) Image(image, contentDescription = null, modifier = Modifier.size(size))
    else DroyIcon(R.drawable.native_settings_hub, null, modifier = Modifier.size(size))
}

/** Rounded filled fields use a complete focus outline instead of the default bottom indicator. */
@Composable
internal fun ProductFilledField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null, enabled: Boolean = true, singleLine: Boolean = false,
    isError: Boolean = false, leadingIcon: @Composable (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.large
    TextField(value, onValueChange, modifier = modifier.onFocusChanged { focused = it.isFocused }
        .border(2.dp, when { isError -> MaterialTheme.colorScheme.error; focused -> MaterialTheme.colorScheme.primary; else -> Color.Transparent }, shape),
        label = label, enabled = enabled, singleLine = singleLine, isError = isError,
        leadingIcon = leadingIcon, shape = shape, colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent))
}
