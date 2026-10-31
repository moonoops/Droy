package io.droyagent.app.nativeui.navigation

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

interface NativeExternalNavigationStore {
    suspend fun load(): NativeExternalNavigationRequest?
    suspend fun save(request: NativeExternalNavigationRequest?)
}
class NativeExternalNavigationStorageException : IllegalStateException("native_external_navigation_storage")

/** A private preference/file binding. write must return true only after a durable commit; null removes this one journal. */
class NativeExternalNavigationJsonStore(
    private val read: suspend () -> String?,
    private val write: suspend (String?) -> Boolean,
) : NativeExternalNavigationStore {
    override suspend fun load(): NativeExternalNavigationRequest? = try {
        read()?.let(::decode)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        throw NativeExternalNavigationStorageException()
    }
    override suspend fun save(request: NativeExternalNavigationRequest?) {
        try {
            if (!write(request?.let(::encode))) throw NativeExternalNavigationStorageException()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw NativeExternalNavigationStorageException()
        }
    }

    companion object {
        private const val LIMIT = 4096
        private fun quote(value: String) = "\"$value\""
        private fun nullable(value: String?) = value?.let(::quote) ?: "null"
        internal fun encode(request: NativeExternalNavigationRequest): String {
            val body = when (val target = request.target) {
                is NativeExternalNavigationTarget.EngineSession -> "\"kind\":\"engineSession\",\"sessionId\":${quote(target.sessionId)},\"petRequestId\":${nullable(target.petRequestId)}"
                is NativeExternalNavigationTarget.CompanionChat -> "\"kind\":\"companionChat\",\"companionId\":${quote(target.companionId)}"
                is NativeExternalNavigationTarget.CompanionTask -> "\"kind\":\"companionTask\",\"companionId\":${quote(target.companionId)},\"taskId\":${quote(target.taskId)},\"sessionId\":${quote(target.sessionId)},\"petRequestId\":${nullable(target.petRequestId)}"
                is NativeExternalNavigationTarget.BrowserTab -> "\"kind\":\"browserTab\",\"browserSessionId\":${quote(target.browserSessionId)}"
                NativeExternalNavigationTarget.NotificationManagement -> "\"kind\":\"notificationManagement\""
                is NativeExternalNavigationTarget.VoiceWake -> "\"kind\":\"voiceWake\",\"eventId\":${target.eventId}"
                is NativeExternalNavigationTarget.AudioAuthorization -> "\"kind\":\"audioAuthorization\",\"captureId\":${quote(target.captureId)}"
                is NativeExternalNavigationTarget.ImportedMedia -> "\"kind\":\"importedMedia\",\"importId\":${quote(target.importId)}"
                is NativeExternalNavigationTarget.Root -> "\"kind\":\"root\",\"page\":${quote(target.page.wire)}"
            }
            return "{\"version\":1,\"requestId\":${quote(request.id)},\"target\":{$body}}".also { require(it.length <= LIMIT) }
        }
        internal fun decode(raw: String): NativeExternalNavigationRequest {
            require(raw.length <= LIMIT)
            val value = JSONObject(raw)
            value.only("version", "requestId", "target")
            require(value.integer("version") == 1L)
            val row = value.getJSONObject("target")
            val target = when (row.string("kind")) {
                "engineSession" -> {
                    row.only("kind", "sessionId", "petRequestId")
                    NativeExternalNavigationTarget.EngineSession(row.string("sessionId"), row.nullString("petRequestId"))
                }
                "companionChat" -> {
                    row.only("kind", "companionId")
                    NativeExternalNavigationTarget.CompanionChat(row.string("companionId"))
                }
                "companionTask" -> {
                    row.only("kind", "companionId", "taskId", "sessionId", "petRequestId")
                    NativeExternalNavigationTarget.CompanionTask(row.string("companionId"), row.string("taskId"), row.string("sessionId"), row.nullString("petRequestId"))
                }
                "browserTab" -> { row.only("kind", "browserSessionId"); NativeExternalNavigationTarget.BrowserTab(row.string("browserSessionId")) }
                "notificationManagement" -> { row.only("kind"); NativeExternalNavigationTarget.NotificationManagement }
                "voiceWake" -> { row.only("kind", "eventId"); NativeExternalNavigationTarget.VoiceWake(row.integer("eventId")) }
                "audioAuthorization" -> { row.only("kind", "captureId"); NativeExternalNavigationTarget.AudioAuthorization(row.string("captureId")) }
                "importedMedia" -> { row.only("kind", "importId"); NativeExternalNavigationTarget.ImportedMedia(row.string("importId")) }
                "root" -> {
                    row.only("kind", "page")
                    NativeExternalNavigationTarget.Root(NativeExternalRoot.entries.first { it.wire == row.string("page") })
                }
                else -> throw IllegalArgumentException()
            }
            val request = NativeExternalNavigationRequest(value.string("requestId"), target)
            // Journals are written only by encode. Canonical comparison rejects duplicate keys,
            // coercions and trailing data consistently on both Android and JVM JSON implementations.
            require(encode(request) == raw)
            return request
        }
        private fun JSONObject.only(vararg names: String) { require(keys().asSequence().toSet() == names.toSet()) }
        private fun JSONObject.string(name: String): String = (get(name) as? String) ?: throw IllegalArgumentException()
        private fun JSONObject.nullString(name: String): String? = if (get(name) == JSONObject.NULL) null else string(name)
        private fun JSONObject.integer(name: String): Long {
            val value = get(name)
            require(value is Long || value is Int)
            return (value as Number).toLong()
        }
    }
}
