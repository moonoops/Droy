package io.droyagent.app.nativeui.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer

internal data class DroyNavigationMotion(
    val rootEnter: EnterTransition, val rootExit: ExitTransition,
    val enter: EnterTransition, val exit: ExitTransition,
    val popEnter: EnterTransition, val popExit: ExitTransition,
)

/** Scene geometry lives here; all springs and effects follow the global theme. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun droyNavigationMotion(): DroyNavigationMotion {
    val spatial = MaterialTheme.motionScheme.slowSpatialSpec<IntOffset>()
    // Opaque pages stay visible throughout navigation; unequal cross-fades exposed
    // the shell between destinations. Root tabs are peers and need no page fade.
    return DroyNavigationMotion(EnterTransition.None, ExitTransition.None,
        slideInHorizontally(spatial) { it }, slideOutHorizontally(spatial) { -it / 4 },
        slideInHorizontally(spatial) { -it / 4 }, slideOutHorizontally(spatial) { it })
}

/** Stable depths keep the departing child above its parent, including consecutive back steps. */
internal fun DroyNavigationMotion.pageTransition(forward: Boolean, targetDepth: Int): ContentTransform =
    ContentTransform(if (forward) enter else popEnter, if (forward) exit else popExit,
        targetContentZIndex = targetDepth.toFloat(), sizeTransform = null)

/** A newly sent bubble rises from the composer; restored history never replays this entrance. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun messageEntranceModifier(entering: Boolean): androidx.compose.ui.Modifier {
    val progress = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (entering) 0f else 1f) }
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val opacity = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (entering) 0f else 1f) }
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    androidx.compose.runtime.LaunchedEffect(entering) {
        if (entering) kotlinx.coroutines.coroutineScope {
            launch { progress.animateTo(1f, spec) }
            launch { opacity.animateTo(1f, effects) }
        } else { progress.snapTo(1f); opacity.snapTo(1f) }
    }
    val distance = with(LocalDensity.current) { 8.dp.toPx() }
    return androidx.compose.ui.Modifier.graphicsLayer {
        alpha = opacity.value
        translationY = distance * (1f - progress.value)
    }
}
