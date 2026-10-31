package io.droyagent.app.nativeui.data

import io.droyagent.app.NativeRuntimeHost
import java.io.BufferedReader
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import org.json.JSONObject

class NativeApiException(val code: String, cause: Throwable? = null) : Exception(code, cause)

/** Only the Host connection sees authentication. No credential enters presentation state. */
class NativeApiClient {
    private val transport = NativeLocalTransport()
    suspend fun post(method: String, body: JSONObject = JSONObject()): Any? = request("session/$method", body)
    suspend fun get(path: String): Any? = request(path, null)
    suspend fun uploadFile(sessionId: String, name: String, bytes: Long, open: () -> InputStream): JSONObject {
        val envelope = transport.upload("/droy/native/uploads/files/${encodeSegment(sessionId)}?name=${encodeSegment(name)}", bytes, open)
        if (!envelope.optBoolean("ok")) throw NativeApiException(NativeHttpErrors.code(200, envelope.toString()))
        return envelope.getJSONObject("result")
    }

    private suspend fun request(path: String, body: JSONObject?): Any? {
        val envelope = transport.json("/droy/native/$path", if (body == null) "GET" else "POST", body)
        if (!envelope.optBoolean("ok")) throw NativeApiException(NativeHttpErrors.code(200, envelope.toString()))
        return envelope.opt("result").takeUnless { it === JSONObject.NULL }
    }

    fun follow(sessionId: String, maxMessages: Int = 60): Flow<JSONObject> = stream("session/${encodeSegment(sessionId)}/follow?maxMessages=$maxMessages")
    fun control(): Flow<JSONObject> = stream("session/control")
    fun decisions(): Flow<JSONObject> = stream("decisions")
    suspend fun answerDecision(id: String, body: JSONObject): Any? = request("decisions/${encodeSegment(id)}/answer", body)

    private fun stream(path: String): Flow<JSONObject> = transport.events("/droy/native/$path").transform { event ->
        if (event is NativeTransportEvent.Frame) {
            val frame = event.value
            if (frame.has("ok") && !frame.optBoolean("ok")) throw NativeApiException(NativeHttpErrors.code(200, frame.toString()))
            emit(frame)
        }
    }

    companion object {
        fun encodeSegment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
}

sealed interface NativeTransportEvent {
    data object Opened : NativeTransportEvent
    data class Frame(val value: JSONObject) : NativeTransportEvent
}

/** Shared authenticated local transport. Product clients retain their own wire contracts. */
class NativeLocalTransport {
    suspend fun upload(path: String, bytes: Long, open: () -> InputStream): JSONObject = withContext(Dispatchers.IO) {
        if (bytes !in 1..MEDIA_LIMIT.toLong()) throw NativeApiException("native_attachment_too_large")
        val endpoint = NativeRuntimeHost.connection() ?: throw NativeApiException("runtime_unavailable")
        val connection = endpoint.open(path)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setFixedLengthStreamingMode(bytes)
            open().use { input -> connection.outputStream.use { output ->
                val buffer = ByteArray(64 * 1024)
                var written = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    written += count
                    if (written > bytes) throw NativeApiException("native_attachment_changed")
                    output.write(buffer, 0, count)
                }
                if (written != bytes) throw NativeApiException("native_attachment_changed")
            } }
            val status = connection.responseCode
            if (status !in 200..299) throw NativeApiException(NativeHttpErrors.code(status,
                connection.errorStream?.use { String(boundedBytes(it, JSON_LIMIT), StandardCharsets.UTF_8) }))
            val result = connection.inputStream.use { JSONObject(String(boundedBytes(it, JSON_LIMIT), StandardCharsets.UTF_8)) }
            currentCoroutineContext().ensureActive()
            if (!endpoint.isCurrent()) throw NativeApiException("runtime_unavailable")
            result
        } catch (error: NativeApiException) { throw error }
        catch (error: java.io.IOException) { currentCoroutineContext().ensureActive(); throw NativeApiException("network_error", error) }
        catch (error: org.json.JSONException) { throw NativeApiException("malformed_response", error) }
        finally { connection.disconnect() }
    }

    suspend fun json(path: String, method: String = "GET", body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val bytes = exchange(path, method, body, JSON_LIMIT)
        try { JSONObject(String(bytes, StandardCharsets.UTF_8)) }
        catch (error: org.json.JSONException) { throw NativeApiException("malformed_response", error) }
    }

    suspend fun bytes(path: String, limit: Int = MEDIA_LIMIT): ByteArray = exchange(path, "GET", null, limit)

    private suspend fun exchange(path: String, method: String, body: JSONObject?, limit: Int): ByteArray = withContext(Dispatchers.IO) {
        val endpoint = NativeRuntimeHost.connection() ?: throw NativeApiException("runtime_unavailable")
        val connection = endpoint.open(path)
        try {
            connection.requestMethod = method
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val payload = body.toString().toByteArray(StandardCharsets.UTF_8)
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
            }
            currentCoroutineContext().ensureActive()
            val status = connection.responseCode
            if (status !in 200..299) throw NativeApiException(NativeHttpErrors.code(status,
                connection.errorStream?.use { String(boundedBytes(it, JSON_LIMIT), StandardCharsets.UTF_8) }))
            val result = connection.inputStream.use { boundedBytes(it, limit) }
            currentCoroutineContext().ensureActive()
            if (!endpoint.isCurrent()) throw NativeApiException("runtime_unavailable")
            result
        } catch (error: NativeApiException) { throw error }
        catch (error: java.io.IOException) {
            currentCoroutineContext().ensureActive()
            throw NativeApiException("network_error", error)
        } finally { connection.disconnect() }
    }

    fun events(path: String): Flow<NativeTransportEvent> = callbackFlow {
        val endpoint = NativeRuntimeHost.connection()
        if (endpoint == null) { close(NativeApiException("runtime_unavailable")); return@callbackFlow }
        val activeConnection = AtomicReference<HttpURLConnection?>()
        val closed = AtomicBoolean(false)
        val worker = Thread({
            try {
                val connection = endpoint.open(path)
                activeConnection.set(connection)
                if (closed.get()) return@Thread
                connection.readTimeout = 45_000
                connection.setRequestProperty("Accept", "text/event-stream")
                val status = connection.responseCode
                if (status != 200) throw NativeApiException(NativeHttpErrors.code(status,
                    connection.errorStream?.use { String(boundedBytes(it, JSON_LIMIT), StandardCharsets.UTF_8) }))
                if (connection.contentType?.startsWith("text/event-stream") != true) throw NativeApiException("malformed_response")
                if (trySendBlocking(NativeTransportEvent.Opened).isFailure) throw InterruptedException()
                connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    NativeSseReader.read(reader) { data ->
                        if (!endpoint.isCurrent()) throw NativeApiException("runtime_unavailable")
                        val frame = try { JSONObject(data) } catch (error: org.json.JSONException) {
                            throw NativeApiException("malformed_response", error)
                        }
                        if (trySendBlocking(NativeTransportEvent.Frame(frame)).isFailure) throw InterruptedException()
                    }
                }
                close(NativeApiException("stream_disconnected"))
            } catch (_: InterruptedException) { close() }
            catch (error: Exception) {
                if (!closed.get()) close(if (error is NativeApiException) error else NativeApiException("network_error", error))
            } finally { activeConnection.getAndSet(null)?.disconnect() }
        }, "droy-local-events").apply { isDaemon = true }
        worker.start()
        awaitClose {
            closed.set(true)
            activeConnection.getAndSet(null)?.disconnect()
            worker.interrupt()
        }
    }

    companion object {
        private const val JSON_LIMIT = 16 * 1024 * 1024
        private const val MEDIA_LIMIT = 20 * 1024 * 1024
        internal fun boundedBytes(stream: InputStream, limit: Int): ByteArray {
            require(limit in 1..MEDIA_LIMIT)
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                total += read
                if (total > limit) throw NativeApiException("response_too_large")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}

/** SSE framing is independent of JSON; heartbeats and unknown fields carry no state. */
internal object NativeSseReader {
    fun read(reader: BufferedReader, onData: (String) -> Unit) {
        val data = StringBuilder()
        var size = 0
        while (true) {
            val line = boundedLine(reader) ?: return
            if (line.isEmpty()) {
                if (data.isNotEmpty()) onData(data.dropLast(1).toString())
                data.setLength(0); size = 0
            } else if (line.startsWith("data:")) {
                val value = line.substring(5).removePrefix(" ")
                size += value.length + 1
                if (size > MAX_FRAME_CHARS) throw NativeApiException("response_too_large")
                data.append(value).append('\n')
            }
        }
    }

    private const val MAX_FRAME_CHARS = 16 * 1024 * 1024
    private fun boundedLine(reader: BufferedReader): String? {
        val line = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c == -1) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().removeSuffix("\r")
            if (line.length == MAX_FRAME_CHARS) throw NativeApiException("response_too_large")
            line.append(c.toChar())
        }
    }
}


internal object NativeHttpErrors {
    fun code(status: Int, text: String?): String {
        if (status == 401) return "runtime_unavailable"
        if (text != null) {
            val envelope = try { JSONObject(text) } catch (_: org.json.JSONException) { null }
            val code = envelope?.optJSONObject("error")?.optString("code")
                ?: (envelope?.opt("error") as? String)
            if (!code.isNullOrBlank()) return code
            val setupCode = envelope?.optString("code")
            if (setupCode != null && (setupCode.matches(Regex("(?:SETUP|MODEL|NATIVE_SETUP)_[A-Z0-9_]{1,100}"))
                    || setupCode == "INVALID_NATIVE_SETUP_REQUEST")) return setupCode
        }
        return when (status) {
            502, 503, 504 -> "service_temporarily_unavailable"
            else -> "request_failed"
        }
    }
}
