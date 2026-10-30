package io.droyagent.app.nativeui.companion.ui

import androidx.compose.foundation.border
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun CompanionFilledField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    isError: Boolean = false,
    leadingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    TextField(value, onValueChange,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .border(2.dp, when {
                isError -> MaterialTheme.colorScheme.error
                focused -> MaterialTheme.colorScheme.primary
                else -> Color.Transparent
            }, shape),
        label = label, enabled = enabled, singleLine = singleLine, isError = isError,
        leadingIcon = leadingIcon, supportingText = supportingText, minLines = minLines, maxLines = maxLines,
        shape = shape, colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent, errorIndicatorColor = Color.Transparent))
}
