package io.droyagent.app.nativeui.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.droyagent.app.R

val LocalNativeProductPresentation = staticCompositionLocalOf { NativeProductPresentationState() }

@Composable
fun NativeBrandLogo(modifier: Modifier = Modifier, size: Dp = 64.dp, dark: Boolean = isSystemInDarkTheme(),
    presentation: NativeProductPresentationState = LocalNativeProductPresentation.current) {
    val colors = presentation.presentation
    val background = (if (dark) colors?.logoDarkColor else colors?.logoLightColor)?.let(::Color)
        ?: MaterialTheme.colorScheme.surfaceContainer
    Surface(modifier.size(size).testTag("native-brand-logo"), shape = MaterialTheme.shapes.large, color = background) {
        Box(Modifier.fillMaxSize().padding(size * .125f), contentAlignment = Alignment.Center) {
            presentation.logo?.let { Image(it, colors?.appName ?: stringResource(R.string.droy_app_name), Modifier.fillMaxSize()) }
        }
    }
}

@Composable
fun NativeWelcomeHero(modifier: Modifier = Modifier,
    presentation: NativeProductPresentationState = LocalNativeProductPresentation.current) {
    val values = presentation.presentation
    Column(modifier.fillMaxWidth().padding(vertical = 64.dp).testTag("native-welcome-hero"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        NativeBrandLogo(presentation = presentation)
        values?.heroBadge?.takeIf(String::isNotBlank)?.let { badge ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.testTag("native-welcome-badge")) {
                Text(badge, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(values?.welcomeOverride ?: stringResource(R.string.native_start_conversation), style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center, modifier = Modifier.testTag("native-welcome-text"))
    }
}
