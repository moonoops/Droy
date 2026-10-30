package io.droyagent.app.nativeui.navigation

import java.util.UUID

internal fun externalIdentity(value: String, maximum: Int = 256) {
    require(value.length in 1..maximum && value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]*"))) { "invalid_external_identity" }
}
internal fun externalImportIdentity(value: String) {
    require(UUID.fromString(value).toString() == value) { "invalid_external_import_identity" }
}

enum class NativeExternalRoot(val wire: String) { MESSAGES("messages"), WORKBENCH("workbench"), SETTINGS("settings") }

sealed interface NativeExternalNavigationTarget {
    data class EngineSession(val sessionId: String, val petRequestId: String? = null) : NativeExternalNavigationTarget {
        init { externalIdentity(sessionId); petRequestId?.let(::externalIdentity) }
    }
    data class CompanionChat(val companionId: String) : NativeExternalNavigationTarget {
        init { externalIdentity(companionId, 160) }
    }
    /** Called with an actual NativeCompanionTask row, not inferred from new Intent extras. */
    data class CompanionTask(val companionId: String, val taskId: String, val sessionId: String, val petRequestId: String? = null) : NativeExternalNavigationTarget {
        init { externalIdentity(companionId, 160); externalIdentity(taskId); externalIdentity(sessionId); petRequestId?.let(::externalIdentity) }
    }
    data class BrowserTab(val browserSessionId: String) : NativeExternalNavigationTarget { init { externalIdentity(browserSessionId) } }
    data object NotificationManagement : NativeExternalNavigationTarget
    data class VoiceWake(val eventId: Long) : NativeExternalNavigationTarget { init { require(eventId > 0) { "invalid_external_wake" } } }
    data class AudioAuthorization(val captureId: String) : NativeExternalNavigationTarget { init { externalIdentity(captureId) } }
    data class ImportedMedia(val importId: String) : NativeExternalNavigationTarget { init { externalImportIdentity(importId) } }
    data class Root(val page: NativeExternalRoot) : NativeExternalNavigationTarget
}

enum class NativeExternalParseFailure { MALFORMED, AMBIGUOUS }
sealed interface NativeExternalNavigationInput {
    data object None : NativeExternalNavigationInput
    data class Target(val target: NativeExternalNavigationTarget) : NativeExternalNavigationInput
    data class Invalid(val reason: NativeExternalParseFailure) : NativeExternalNavigationInput
}

/** The Activity supplies only scalar extras; no Bundle, URI grants, content, or launch flags enter this layer. */
object NativeExternalNavigationParser {
    fun parse(extras: Map<String, Any?>): NativeExternalNavigationInput {
        return try {
        fun text(key: String): String? {
            if (!extras.containsKey(key) || extras[key] == null) return null
            return (extras[key] as? String ?: throw IllegalArgumentException()).also { externalIdentity(it) }
        }
        val candidates = mutableListOf<NativeExternalNavigationTarget>()
        val session = text("sessionId"); val petTask = text("petTaskId"); val request = text("petRequestId")
        if (session != null && petTask != null && session != petTask) return NativeExternalNavigationInput.Invalid(NativeExternalParseFailure.AMBIGUOUS)
        val engine = session ?: petTask
        if (request != null && engine == null) throw IllegalArgumentException()
        if (engine != null) candidates += NativeExternalNavigationTarget.EngineSession(engine, request)
        text("companionId")?.let { candidates += NativeExternalNavigationTarget.CompanionChat(it) }
        text("browserSessionId")?.let { candidates += NativeExternalNavigationTarget.BrowserTab(it) }
        text("audioCaptureId")?.let { candidates += NativeExternalNavigationTarget.AudioAuthorization(it) }
        text("mediaImportId")?.let { candidates += NativeExternalNavigationTarget.ImportedMedia(it) }
        if (extras.containsKey("notificationManagement")) {
            val value = extras["notificationManagement"] as? Boolean ?: throw IllegalArgumentException()
            if (value) candidates += NativeExternalNavigationTarget.NotificationManagement
        }
        if (extras.containsKey("voiceWakeEventId") || extras.containsKey("voiceWakeTarget")) {
            val event = extras["voiceWakeEventId"]
            require(event is Long || event is Int)
            require(extras["voiceWakeTarget"] == "standard")
            candidates += NativeExternalNavigationTarget.VoiceWake((event as Number).toLong())
        }
        text("destination")?.let { destination ->
            candidates += NativeExternalNavigationTarget.Root(NativeExternalRoot.entries.firstOrNull { it.wire == destination } ?: throw IllegalArgumentException())
        }
        when (candidates.size) {
            0 -> NativeExternalNavigationInput.None
            1 -> NativeExternalNavigationInput.Target(candidates.single())
            else -> NativeExternalNavigationInput.Invalid(NativeExternalParseFailure.AMBIGUOUS)
        }
        } catch (_: IllegalArgumentException) { NativeExternalNavigationInput.Invalid(NativeExternalParseFailure.MALFORMED) }
    }
}

data class NativeExternalNavigationRequest(val id: String, val target: NativeExternalNavigationTarget) {
    init { externalIdentity(id, 160) }
}
enum class NativeExternalSessionOwner { WORKBENCH, COMPANION, SUBAGENT }
data class NativeExternalVerifiedSession(val id: String, val workspaceId: String?, val owner: NativeExternalSessionOwner,
    val readOnly: Boolean, val archived: Boolean, val companionId: String? = null, val taskId: String? = null,
    val parentSessionId: String? = null) {
    init {
        externalIdentity(id); workspaceId?.let(::externalIdentity); companionId?.let { externalIdentity(it, 160) }; taskId?.let(::externalIdentity)
        require((owner == NativeExternalSessionOwner.COMPANION) == (companionId != null))
        require(taskId == null || owner == NativeExternalSessionOwner.COMPANION)
        require(owner == NativeExternalSessionOwner.WORKBENCH || readOnly)
        require((owner == NativeExternalSessionOwner.SUBAGENT) == (parentSessionId != null))
        parentSessionId?.let { externalIdentity(it); require(it != id) }
    }
}

/** Constructed by trusted product readers after verifying existence and ownership. Nothing here creates or consumes a resource. */
sealed interface NativeExternalResolvedTarget {
    val surface: NativeExternalNavigationTarget
    data class EngineSession(val session: NativeExternalVerifiedSession, val petRequestId: String? = null) : NativeExternalResolvedTarget {
        override val surface = NativeExternalNavigationTarget.EngineSession(session.id, petRequestId)
    }
    data class CompanionChat(val companionId: String, val chatSessionId: String? = null) : NativeExternalResolvedTarget {
        init { chatSessionId?.let(::externalIdentity) }
        override val surface = NativeExternalNavigationTarget.CompanionChat(companionId)
    }
    data class CompanionTask(val session: NativeExternalVerifiedSession, val petRequestId: String? = null) : NativeExternalResolvedTarget {
        init { require(session.owner == NativeExternalSessionOwner.COMPANION && session.taskId != null) }
        override val surface = NativeExternalNavigationTarget.CompanionTask(session.companionId!!, session.taskId!!, session.id, petRequestId)
    }
    data class BrowserTab(val browserSessionId: String) : NativeExternalResolvedTarget {
        override val surface = NativeExternalNavigationTarget.BrowserTab(browserSessionId)
    }
    data object NotificationManagement : NativeExternalResolvedTarget { override val surface = NativeExternalNavigationTarget.NotificationManagement }
    data class VoiceWake(val eventId: Long) : NativeExternalResolvedTarget { override val surface = NativeExternalNavigationTarget.VoiceWake(eventId) }
    data class AudioAuthorization(val captureId: String) : NativeExternalResolvedTarget { override val surface = NativeExternalNavigationTarget.AudioAuthorization(captureId) }
    data class ImportedMedia(val requestedImportId: String, val previewImportId: String) : NativeExternalResolvedTarget {
        init { externalImportIdentity(requestedImportId) }
        override val surface = NativeExternalNavigationTarget.ImportedMedia(previewImportId)
    }
    data class Root(val page: NativeExternalRoot) : NativeExternalResolvedTarget { override val surface = NativeExternalNavigationTarget.Root(page) }
}

data class NativeExternalNavigationConditions(val foreground: Boolean = false, val setupComplete: Boolean = false,
    val runtimeReady: Boolean = false, val permissionFlowIdle: Boolean = false)
enum class NativeExternalNavigationBlocker { FOREGROUND, SETUP, RUNTIME, PERMISSION_FLOW, DISCONNECTED, TARGET_NOT_READY, RESOLVER_FAILED, STORAGE }
enum class NativeExternalNavigationFailure { NOT_FOUND, REQUEST_ENDED, ACCESS_DENIED, UNSUPPORTED, INVALID_RESOLUTION }
sealed interface NativeExternalNavigationResolution {
    data class Ready(val target: NativeExternalResolvedTarget) : NativeExternalNavigationResolution
    data class Wait(val reason: NativeExternalNavigationBlocker) : NativeExternalNavigationResolution
    data class Missing(val reason: NativeExternalNavigationFailure) : NativeExternalNavigationResolution
}
fun interface NativeExternalNavigationGateway {
    suspend fun resolve(target: NativeExternalNavigationTarget): NativeExternalNavigationResolution
}
data class NativeExternalNavigationOpened(val surface: NativeExternalNavigationTarget, val dataReady: Boolean, val resumed: Boolean)
enum class NativeExternalNavigationPhase { EMPTY, PERSISTING, WAITING, RESOLVING, READY, FAILED }
data class NativeExternalNavigationState(val restored: Boolean = false, val request: NativeExternalNavigationRequest? = null,
    val phase: NativeExternalNavigationPhase = NativeExternalNavigationPhase.EMPTY, val blocker: NativeExternalNavigationBlocker? = null,
    val resolved: NativeExternalResolvedTarget? = null, val failure: NativeExternalNavigationFailure? = null,
    val storageError: Boolean = false)
