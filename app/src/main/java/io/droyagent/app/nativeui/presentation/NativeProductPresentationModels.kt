package io.droyagent.app.nativeui.presentation

import androidx.compose.ui.graphics.ImageBitmap
import io.droyagent.app.NativeProductPresentationHost

data class NativeProductPresentation(
    val appName: String,
    val workbenchTitleOverride: String?,
    val welcomeOverride: String?,
    val heroBadge: String,
    val composerHintOverride: String?,
    val logoLightColor: Int?,
    val logoDarkColor: Int?,
    val logoAsset: String,
) {
    internal companion object {
        fun from(value: NativeProductPresentationHost.Snapshot) = NativeProductPresentation(value.appName, value.workbenchTitleOverride,
            value.welcomeOverride, value.heroBadge, value.composerHintOverride, value.logoLightColor, value.logoDarkColor, value.logoAsset)
    }
}

data class NativeProductPresentationState(
    val presentation: NativeProductPresentation? = null,
    val logo: ImageBitmap? = null,
    val loading: Boolean = true,
    val error: NativeProductPresentationHost.Error? = null,
)
