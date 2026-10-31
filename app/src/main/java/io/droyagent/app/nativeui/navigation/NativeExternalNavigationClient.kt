package io.droyagent.app.nativeui.navigation

import android.content.Context
import io.droyagent.app.NativeExternalNavigationHost
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeHttpErrors
import io.droyagent.app.nativeui.data.NativeLocalTransport
import io.droyagent.app.nativeui.media.NativeShareInbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Only verified DTOs leave this reader. It cannot send a message, create a tab or consume a wake event. */
class NativeExternalNavigationClient(
    context: Context,
    private val claimedWake: (Long) -> Boolean = { false },
) : NativeExternalNavigationGateway {
    private val application = context.applicationContext
    private val transport = NativeLocalTransport()

    override suspend fun resolve(target: NativeExternalNavigationTarget): NativeExternalNavigationResolution = try {
        when (target) {
            is NativeExternalNavigationTarget.EngineSession -> remote(JSONObject().put("kind", "engine-session").put("sessionId", target.sessionId)
                .also { target.petRequestId?.let { id -> it.put("petRequestId", id) } })
            is NativeExternalNavigationTarget.CompanionChat -> remote(JSONObject().put("kind", "companion-chat").put("companionId", target.companionId))
            is NativeExternalNavigationTarget.CompanionTask -> remote(JSONObject().put("kind", "companion-task").put("companionId", target.companionId)
                .put("taskId", target.taskId).put("sessionId", target.sessionId).also { target.petRequestId?.let { id -> it.put("petRequestId", id) } })
            is NativeExternalNavigationTarget.BrowserTab -> withContext(Dispatchers.IO) {
                if (NativeExternalNavigationHost.browserExists(application, target.browserSessionId)) ready(NativeExternalResolvedTarget.BrowserTab(target.browserSessionId))
                else missing()
            }
            is NativeExternalNavigationTarget.AudioAuthorization -> withContext(Dispatchers.IO) {
                if (NativeExternalNavigationHost.pendingAudio(application, target.captureId)) ready(NativeExternalResolvedTarget.AudioAuthorization(target.captureId))
                else NativeExternalNavigationResolution.Missing(NativeExternalNavigationFailure.REQUEST_ENDED)
            }
            is NativeExternalNavigationTarget.VoiceWake -> {
                if (claimedWake(target.eventId) || NativeExternalNavigationHost.wakeAvailable(application, target.eventId)) ready(NativeExternalResolvedTarget.VoiceWake(target.eventId))
                else NativeExternalNavigationResolution.Missing(NativeExternalNavigationFailure.REQUEST_ENDED)
            }
            is NativeExternalNavigationTarget.ImportedMedia -> withContext(Dispatchers.IO) {
                val pending = NativeShareInbox.pending(application)
                if (target.importId in pending) ready(NativeExternalResolvedTarget.ImportedMedia(target.importId, pending.first())) else missing()
            }
            NativeExternalNavigationTarget.NotificationManagement -> ready(NativeExternalResolvedTarget.NotificationManagement)
            is NativeExternalNavigationTarget.Root -> ready(NativeExternalResolvedTarget.Root(target.page))
        }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (error !is NativeApiException) throw error
        when (error.code) {
            "NATIVE_NAVIGATION_NOT_FOUND" -> missing()
            "NATIVE_NAVIGATION_ACCESS_DENIED" -> NativeExternalNavigationResolution.Missing(NativeExternalNavigationFailure.ACCESS_DENIED)
            "NATIVE_NAVIGATION_BAD_REQUEST", "NATIVE_NAVIGATION_TARGET_MISMATCH" -> NativeExternalNavigationResolution.Missing(NativeExternalNavigationFailure.INVALID_RESOLUTION)
            "NATIVE_NAVIGATION_SUBAGENT_UNPROVEN", "NATIVE_NAVIGATION_FORMAT_UNSUPPORTED" -> NativeExternalNavigationResolution.Missing(NativeExternalNavigationFailure.UNSUPPORTED)
            "NATIVE_NAVIGATION_NOT_READY", "NATIVE_NAVIGATION_MIGRATION_REQUIRED" -> NativeExternalNavigationResolution.Wait(NativeExternalNavigationBlocker.TARGET_NOT_READY)
            "network_error", "runtime_unavailable", "NATIVE_NAVIGATION_CANCELLED" -> NativeExternalNavigationResolution.Wait(NativeExternalNavigationBlocker.DISCONNECTED)
            else -> NativeExternalNavigationResolution.Wait(NativeExternalNavigationBlocker.RESOLVER_FAILED)
        }
    }

    private suspend fun remote(input: JSONObject): NativeExternalNavigationResolution {
        val response = transport.json("/droy/native/navigation/resolve", "POST", input)
        if (!response.optBoolean("ok")) throw NativeApiException(NativeHttpErrors.code(200, response.toString()))
        return ready(NativeExternalNavigationJson.resolved(response.getJSONObject("result")))
    }
    private fun ready(value: NativeExternalResolvedTarget) = NativeExternalNavigationResolution.Ready(value)
    private fun missing() = NativeExternalNavigationResolution.Missing(NativeExternalNavigationFailure.NOT_FOUND)
}

object NativeExternalNavigationJson {
    fun resolved(row: JSONObject): NativeExternalResolvedTarget = when (row.getString("kind")) {
        "engine-session" -> NativeExternalResolvedTarget.EngineSession(session(row.getJSONObject("session")), row.optional("petRequestId"))
        "companion-task" -> NativeExternalResolvedTarget.CompanionTask(session(row.getJSONObject("session")), row.optional("petRequestId"))
        "companion-chat" -> NativeExternalResolvedTarget.CompanionChat(row.getString("companionId"), row.optional("chatSessionId"),
            row.optional("sourceSessionId"), row.optional("sourceTaskId"), row.optional("petRequestId"))
        else -> throw NativeApiException("malformed_response")
    }
    private fun session(row: JSONObject): NativeExternalVerifiedSession {
        val id = row.getString("id")
        val owner = when (row.getString("owner")) {
            "workbench" -> NativeExternalSessionOwner.WORKBENCH
            "companion" -> NativeExternalSessionOwner.COMPANION
            "subagent" -> NativeExternalSessionOwner.SUBAGENT
            else -> throw NativeApiException("malformed_response")
        }
        val address = row.getJSONObject("address")
        val parent = if (owner == NativeExternalSessionOwner.SUBAGENT) {
            require(address.getString("kind") == "subagent" && address.getString("childSessionId") == id && address.getString("mode") == "unknown")
            address.getString("parentSessionId")
        } else {
            require(address.getString("kind") == "session" && address.getString("sessionId") == id)
            null
        }
        return NativeExternalVerifiedSession(id, row.optional("workspaceId"), owner, row.getBoolean("readOnly"), row.getBoolean("archived"),
            row.optional("companionId"), row.optional("taskId"), parent)
    }
    private fun JSONObject.optional(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
}
