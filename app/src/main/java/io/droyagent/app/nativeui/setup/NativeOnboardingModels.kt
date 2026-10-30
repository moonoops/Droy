package io.droyagent.app.nativeui.setup

import io.droyagent.app.nativeui.companion.data.NativeUserProfile
import io.droyagent.app.nativeui.data.NativeRuntimeState
import io.droyagent.app.nativeui.permissions.NativeAuthorizationPolicy

data class NativeSetupRoute(
    val completed: Boolean,
    val required: Boolean,
    val step: Int,
    val legacyStep: Int,
    val source: String,
    val schemaVersion: Int,
    val environment: NativeRuntimeState,
    val permissionPolicy: NativeAuthorizationPolicy?,
    val acknowledgements: Map<String, Boolean>,
)

data class NativeSetupCheck(val id: String, val required: Boolean, val passed: Boolean)

data class NativeSetupLive(
    val route: NativeSetupRoute,
    val checks: List<NativeSetupCheck>,
    val canComplete: Boolean,
    val identitySaved: Boolean,
    val identityConfirmed: Boolean,
    val profile: NativeUserProfile,
    val provider: String?,
    val model: String?,
) {
    fun passed(id: String): Boolean = checks.firstOrNull { it.id == id }?.passed == true
}

/** Only non-secret identity fields are persisted; provider forms own their ephemeral key. */
data class NativeSetupIdentityDraft(val name: String = "", val avatarUrl: String? = null, val edited: Boolean = false, val bio: String? = null)

data class NativeOnboardingState(
    val route: NativeSetupRoute? = null,
    val localLoading: Boolean = true,
    val localError: String? = null,
    val live: NativeSetupLive? = null,
    val liveFresh: Boolean = false,
    val checking: Boolean = false,
    val statusError: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val draft: NativeSetupIdentityDraft = NativeSetupIdentityDraft(),
    val draftLoaded: Boolean = false,
) {
    val requiresSetup: Boolean? get() = route?.required
}
