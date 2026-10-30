package io.droyagent.app.nativeui.memory

import io.droyagent.app.nativeui.data.NativeRuntimeState

enum class NativeMemoryPage { SETTINGS, CAPTURE, APPS, ENDPOINTS, MAINTENANCE, RECORDS, DETAIL, EDITOR }
enum class NativeMemoryGroup { MEMORY, ACTIVITY, PLACES }

data class NativeMemorySettings(val enabled: Boolean, val recallEnabled: Boolean, val automaticExtraction: Boolean)
data class NativeMemoryCaptureConfig(
    val enabled: Boolean, val paused: Boolean, val activityEnabled: Boolean, val pageTextEnabled: Boolean,
    val deviceStateEnabled: Boolean, val locationEnabled: Boolean,
    val allowedPackages: List<String>, val excludedPackages: List<String>, val allApplications: Boolean = false,
)
data class NativeMemoryCapture(
    val state: String, val configuration: NativeMemoryCaptureConfig, val locationPermission: Boolean,
    val backgroundLocationPermission: Boolean, val activityState: String?, val locationState: String?, val failure: String?,
)
data class NativeMemoryHeld(val id: String, val sourceId: String?, val revision: String?, val code: String?)
data class NativeMemoryMaintenance(
    val pending: Long, val held: Long, val busy: Boolean, val stopped: Boolean,
    val heldSources: List<NativeMemoryHeld>, val calls: Long?, val tokens: Long?, val lastCode: String?,
)
data class NativeMemoryStatus(
    val available: Boolean, val managementAvailable: Boolean, val gateReady: Boolean, val gateReason: String,
    val rootAuthorized: Boolean?, val settings: NativeMemorySettings, val capture: NativeMemoryCapture?,
    val maintenanceAvailable: Boolean, val maintenance: NativeMemoryMaintenance?,
    val counts: Map<String, Long>, val storageLimited: Boolean?, val bytes: Long?,
)
data class NativeMemoryEndpoint(
    val id: String, val purpose: String, val label: String, val model: String, val url: String,
    val authorized: Boolean, val categories: List<String>, val configVersion: String?,
) { val key get() = "$id:$purpose" }
data class NativeMemoryApp(val packageName: String, val label: String)
data class NativeMemoryApps(val items: List<NativeMemoryApp>, val truncated: Boolean)
data class NativeMemoryDependency(val recordId: String, val revisionId: String)
data class NativeMemoryRecord(
    val recordId: String, val revisionId: String, val kind: String, val state: String,
    val payloadJson: String?, val occurredAt: String, val recordedAt: String?,
    val assertion: String, val actor: String, val retentionMode: String, val expiresAt: String?,
    val sourceRefs: List<String>, val lineageRefs: List<String>, val derivedFrom: List<NativeMemoryDependency>,
    val categories: List<String>, val validFrom: String?, val validUntil: String?,
    val text: String?, val eventType: String?, val appLabel: String?, val appId: String?,
    val placeName: String?, val city: String?, val latitude: Double?, val longitude: Double?,
    val accuracyMeters: Double?, val stale: Boolean?, val isMock: Boolean?, val approximate: Boolean?,
) {
    val editable get() = state in setOf("active", "candidate") && payloadJson != null && (kind in setOf("fact", "preference", "decision", "task_summary") || kind == "event" && (eventType in setOf("location", "visit") || text != null))
    val location get() = eventType in setOf("location", "visit")
}
data class NativeMemoryCoverage(val gaps: Long, val unresolvedLocations: Long, val mockLocations: Long, val continuousObservationVerified: Boolean)
data class NativeMemoryQueryResult(
    val items: List<NativeMemoryRecord>, val total: Long, val nextCursor: String?, val revision: Long,
    val truncated: Boolean, val coverage: NativeMemoryCoverage,
)
data class NativeMemoryFilter(val group: NativeMemoryGroup = NativeMemoryGroup.MEMORY, val text: String = "", val from: String = "", val to: String = "")
data class NativeMemoryDraft(
    val original: NativeMemoryRecord? = null, val kind: String = "fact", val text: String = "", val place: String = "", val city: String = "",
) {
    val creating get() = original == null
    val dirty get() = original == null && text.isNotEmpty() || original != null && (text != original.text.orEmpty() || place != original.placeName.orEmpty() || city != original.city.orEmpty())
    val valid get() = if (original?.location == true) place.toByteArray().size <= 512 && city.toByteArray().size <= 512 else text.isNotBlank() && text.toByteArray().size <= 16384
}
data class NativeMemoryMutation(val operation: String, val argsJson: String, val hasReceipt: Boolean = false)
data class NativeMemoryUnconfirmed(val mutation: NativeMemoryMutation, val checked: Boolean = false)
data class NativeMemoryState(
    val runtime: NativeRuntimeState = NativeRuntimeState(), val page: NativeMemoryPage = NativeMemoryPage.SETTINGS,
    val status: NativeMemoryStatus? = null, val loading: Boolean = false, val busy: Boolean = false,
    val error: String? = null, val statusError: String? = null, val feedback: String? = null,
    val filter: NativeMemoryFilter = NativeMemoryFilter(), val records: List<NativeMemoryRecord> = emptyList(),
    val query: NativeMemoryQueryResult? = null, val recordsLoaded: Boolean = false, val recordsLoading: Boolean = false,
    val recordsError: String? = null, val selected: NativeMemoryRecord? = null, val detailLoading: Boolean = false,
    val editor: NativeMemoryDraft? = null, val conflict: Boolean = false,
    val endpoints: List<NativeMemoryEndpoint> = emptyList(), val endpointsLoaded: Boolean = false, val endpointsLoading: Boolean = false,
    val endpointsError: String? = null, val apps: List<NativeMemoryApp> = emptyList(), val appsLoaded: Boolean = false,
    val appsLoading: Boolean = false, val appsError: String? = null, val appsTruncated: Boolean = false,
    val appAllow: List<String> = emptyList(), val appExclude: List<String> = emptyList(), val appScopeDirty: Boolean = false,
    val unconfirmed: NativeMemoryUnconfirmed? = null,
)

internal val NativeMemoryState.appScopeReducesConsent: Boolean
    get() = status?.capture?.configuration?.let { prior ->
        prior.allowedPackages.containsAll(appAllow) && appExclude.containsAll(prior.excludedPackages)
    } == true

internal val NativeMemoryState.appScopeConsentAllowsSave: Boolean
    get() = status?.available == true || appScopeReducesConsent
