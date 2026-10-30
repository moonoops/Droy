package io.droyagent.app.nativeui.companion.data

import io.droyagent.app.NativeRuntimeHost
import io.droyagent.app.nativeui.data.NativeApiClient
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeHttpErrors
import io.droyagent.app.nativeui.data.NativeLocalTransport
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

internal data class NativeCompanionMarketPack(
    val id: String, val name: String, val description: String,
    val count: Int, val bytes: Long, val license: String,
)

internal data class NativeCompanionMarketReceipt(
    val pack: NativeCompanionStickerPack, val skippedAnimated: Int,
)

internal interface NativeCompanionStickerMarketApi {
    suspend fun search(query: String): List<NativeCompanionMarketPack>
    suspend fun install(companionId: String, packId: String): NativeCompanionMarketReceipt
}

internal object NativeCompanionStickerMarketJson {
    fun packs(value: JSONObject): List<NativeCompanionMarketPack> = parse {
        value.getJSONArray("packs").objects().map { row ->
            val count = row.getInt("count")
            val bytes = row.optLong("bytes", 0)
            if (count < 0 || bytes < 0) malformed()
            NativeCompanionMarketPack(required(row, "id"), required(row, "name"), row.optString("description"),
                count, bytes, row.optString("license"))
        }.also { rows -> if (rows.map { it.id }.distinct().size != rows.size) malformed() }
    }

    fun receipt(value: JSONObject): NativeCompanionMarketReceipt = parse {
        val pack = NativeCompanionJson.stickerPack(value.getJSONObject("pack"))
        val skipped = value.optInt("skippedAnimated", 0)
        if (pack.id.isBlank() || pack.name.isBlank() || pack.count <= 0 || skipped < 0 || pack.builtin) malformed()
        if (value.has("installed") && value.getInt("installed") != pack.count) malformed()
        NativeCompanionMarketReceipt(pack, skipped)
    }

    private fun required(value: JSONObject, key: String): String = value.getString(key).also { if (it.isBlank()) malformed() }
    private fun malformed(): Nothing = throw NativeApiException("malformed_response")
    private inline fun <T> parse(block: () -> T): T = try { block() }
        catch (error: org.json.JSONException) { throw NativeApiException("malformed_response", error) }
}

internal class NativeCompanionStickerMarketClient : NativeCompanionStickerMarketApi {
    override suspend fun search(query: String): List<NativeCompanionMarketPack> = NativeCompanionStickerMarketJson.packs(
        request("/droy/companions/sticker-market?query=${NativeApiClient.encodeSegment(query)}"))

    override suspend fun install(companionId: String, packId: String): NativeCompanionMarketReceipt = NativeCompanionStickerMarketJson.receipt(
        request("/droy/companions/${NativeApiClient.encodeSegment(companionId)}/sticker-market", "POST", JSONObject().put("packId", packId)))

    private suspend fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val endpoint = NativeRuntimeHost.connection() ?: throw NativeApiException("runtime_unavailable")
        return exchangeStickerMarketJson({ endpoint.open(path) }, endpoint::isCurrent, method, body)
    }
}

/** Disconnecting the local request also cancels the Host's remote download through its response-close signal. */
internal suspend fun exchangeStickerMarketJson(open: () -> HttpURLConnection, isCurrent: () -> Boolean,
    method: String, body: JSONObject?): JSONObject = suspendCancellableCoroutine { continuation ->
    val active = AtomicReference<HttpURLConnection?>()
    val worker = Thread({
        try {
            val connection = open()
            active.set(connection)
            if (!continuation.isActive) return@Thread
            connection.readTimeout = 240_000
            connection.requestMethod = method
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val payload = body.toString().toByteArray(StandardCharsets.UTF_8)
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw NativeApiException(NativeHttpErrors.code(status,
                connection.errorStream?.use { String(NativeLocalTransport.boundedBytes(it, 1024 * 1024), StandardCharsets.UTF_8) }))
            val value = connection.inputStream.use {
                JSONObject(String(NativeLocalTransport.boundedBytes(it, 1024 * 1024), StandardCharsets.UTF_8))
            }
            if (!isCurrent()) throw NativeApiException("runtime_unavailable")
            if (continuation.isActive) continuation.resume(value)
        } catch (error: Exception) {
            val failure = when (error) {
                is NativeApiException -> error
                is SocketTimeoutException -> NativeApiException("COMPANION_STICKER_ONLINE_TIMEOUT", error)
                is java.io.IOException -> NativeApiException("network_error", error)
                is org.json.JSONException -> NativeApiException("malformed_response", error)
                else -> error
            }
            if (continuation.isActive) continuation.resumeWithException(failure)
        } finally { active.getAndSet(null)?.disconnect() }
    }, "droy-sticker-market").apply { isDaemon = true }
    continuation.invokeOnCancellation { active.get()?.disconnect(); worker.interrupt() }
    worker.start()
}
