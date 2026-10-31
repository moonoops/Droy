package io.droyagent.app.nativeui.permissions

enum class NativeAuthorizationPolicy(val id: String) {
    BATCH("batch"), ON_FIRST_USE("on_first_use");
    companion object { fun fromId(id: String?): NativeAuthorizationPolicy? = entries.firstOrNull { it.id == id } }
}

enum class NativePermissionOutcome(val id: String) {
    REQUESTING("requesting"), GRANTED("granted"), NOT_GRANTED("not_granted"), SKIPPED("skipped");
    companion object { fun fromId(id: String?): NativePermissionOutcome? = entries.firstOrNull { it.id == id } }
}

data class NativePermissionRequestRecord(val firstRequestedAt: Long, val updatedAt: Long, val outcome: NativePermissionOutcome)

/** Identifies the blocked request; it contains no action arguments and grants no execution authority. */
data class NativePendingPermissionRequest(
    val id: String,
    val taskId: String?,
    val callId: String?,
    val capabilityKeys: Set<String>,
    val permissionIds: Set<String>,
    val createdAt: Long,
)

enum class NativePermissionGrant { GRANTED, NOT_REQUESTED, NOT_GRANTED, SETTINGS_REQUIRED, UNAVAILABLE }

data class NativeSystemPermission(val id: String, val grant: NativePermissionGrant)

data class NativeProductPermission(
    val id: String, val preference: String, val label: String, val section: String,
    val enabled: Boolean,
)

data class NativePermissionQueue(
    val active: Boolean = false, val current: String? = null, val pending: List<String> = emptyList(),
    val total: Int = 0, val processed: Int = 0, val error: String? = null,
)

data class NativeAccessibilityPermission(val enabled: Boolean = false, val connected: Boolean = false)

data class NativeRootPermission(
    val optedIn: Boolean = false, val executablePresent: Boolean = false,
    val running: Boolean = false, val verified: Boolean = false, val error: String? = null,
)

data class NativeShizukuPermission(
    val installed: Boolean = false, val running: Boolean = false, val authorized: Boolean = false,
    val requiresRootOptIn: Boolean = false,
)

data class NativeBackgroundPermission(
    val enabled: Boolean = false, val running: Boolean = false,
    val runtimePermissionGranted: Boolean = false, val appNotificationsEnabled: Boolean = false,
    val runtimeChannelEnabled: Boolean? = null,
    val reviewed: Boolean = false,
)

data class NativeEnergyPermission(val exempt: Boolean? = null, val decision: String? = null)

data class NativePermissionsState(
    val fresh: Boolean = false, val loading: Boolean = false, val error: String? = null,
    val policy: NativeAuthorizationPolicy? = null,
    val products: List<NativeProductPermission> = emptyList(),
    val system: List<NativeSystemPermission> = emptyList(),
    val queue: NativePermissionQueue = NativePermissionQueue(),
    val accessibility: NativeAccessibilityPermission = NativeAccessibilityPermission(),
    val root: NativeRootPermission = NativeRootPermission(),
    val shizuku: NativeShizukuPermission = NativeShizukuPermission(),
    val background: NativeBackgroundPermission = NativeBackgroundPermission(),
    val energy: NativeEnergyPermission = NativeEnergyPermission(),
    val toolsPaused: Boolean = true,
    val pendingRequests: List<NativePendingPermissionRequest> = emptyList(),
    val batchIndependent: List<String> = emptyList(),
)

sealed interface NativePermissionAction {
    data object Refresh : NativePermissionAction
    data object DismissError : NativePermissionAction
    data class SelectPolicy(val policy: NativeAuthorizationPolicy, val initialSetup: Boolean = false) : NativePermissionAction
    data class ApprovePending(val id: String) : NativePermissionAction
    data class DeclinePending(val id: String) : NativePermissionAction
    data object SetUpOrdinary : NativePermissionAction
    data class ConfirmIndependent(val id: String) : NativePermissionAction
    data class SkipIndependent(val id: String) : NativePermissionAction
    data class SetCapabilityGroup(val preferences: Set<String>, val enabled: Boolean) : NativePermissionAction
    data class SetCapability(val preference: String, val enabled: Boolean) : NativePermissionAction
    data object GrantVoiceCapabilities : NativePermissionAction
    data class RequestSystem(val id: String) : NativePermissionAction
    data object ConfirmAccessibility : NativePermissionAction
    data object ConfirmRoot : NativePermissionAction
    data object DisableRoot : NativePermissionAction
    data object ConfirmShizuku : NativePermissionAction
    data object OpenShizuku : NativePermissionAction
    data object CancelQueue : NativePermissionAction
    data class SetBackgroundEnabled(val enabled: Boolean) : NativePermissionAction
    data object ReviewBackgroundLater : NativePermissionAction
    data object RequestRuntimeNotifications : NativePermissionAction
    data object OpenRuntimeNotificationSettings : NativePermissionAction
    data object OpenEnergySettings : NativePermissionAction
    data class ReviewEnergy(val later: Boolean) : NativePermissionAction
    data class SetToolsPaused(val paused: Boolean) : NativePermissionAction
}

internal object NativePermissionPlans {
    val independent = setOf("accessibility", "root", "shizuku", "capability:allowRoot")
    fun ordinaryProductChoices(keys: Set<String>): Map<String, Boolean> = keys.filterNot { it == "allowRoot" }.associateWith { true }
}

internal object NativePermissionFacts {
    fun grant(actualGranted: Boolean, requestedBefore: Boolean, settingsRequired: Boolean): NativePermissionGrant = when {
        actualGranted -> NativePermissionGrant.GRANTED
        settingsRequired -> NativePermissionGrant.SETTINGS_REQUIRED
        requestedBefore -> NativePermissionGrant.NOT_GRANTED
        else -> NativePermissionGrant.NOT_REQUESTED
    }
}
