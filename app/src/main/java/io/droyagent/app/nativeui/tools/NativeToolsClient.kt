package io.droyagent.app.nativeui.tools

import java.util.Base64
import io.droyagent.app.NativeRuntimeHost
import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeApiClient
import io.droyagent.app.nativeui.data.NativeLocalTransport
import io.droyagent.app.nativeui.data.NativeTransportEvent
import io.droyagent.app.nativeui.data.NativeHttpErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class NativeToolsClient : NativeToolsRepository {
    private val transport = NativeLocalTransport()
    private val base = "/droy/native/tools"
    override suspend fun sessions(): List<NativeToolsSession> = decode {
        val workspaces = objectValue(call("/droy/native/workspaces", null))
        val list = objectValue(call("/droy/native/session/list", JSONObject()))
        val managed = strings(list.getJSONArray("managedSessionIds")).toSet()
        val archived = strings(workspaces.getJSONArray("archivedSessionIds")).toSet()
        val managedSpaces = strings(workspaces.getJSONArray("managedWorkspaceIds")).toSet()
        val managedInSpaces = objects(workspaces.getJSONArray("items")).filter { it.getString("workspaceId") in managedSpaces }
            .flatMap { strings(it.getJSONArray("sessionIds")) }.toSet()
        objects(list.getJSONArray("items")).filter { it.getString("sessionId") !in archived }.map { row ->
            val id = row.getString("sessionId")
            NativeToolsSession(id, row.optJSONObject("projections")?.optJSONObject("values")?.optString("title")?.takeIf(String::isNotBlank) ?: id,
                id in managed || id in managedInSpaces || row.optString("origin") == "subagent" || row.optString("formatStatus") == "migration-required" || !row.has("cwd") || row.isNull("cwd"))
        }
    }
    override suspend fun directory(sessionId: String, path: String): NativeToolDirectory = decode {
        val value = request("files/list", target(sessionId, path))
        NativeToolDirectory(value.getString("path"), objects(value.getJSONArray("entries")).map { row ->
            NativeToolFile(row.getString("name"), row.getString("type").also { if (it !in setOf("file", "directory", "other")) malformed() }, row.longOrNull("size"))
        }, value.boolean("truncated"))
    }
    override suspend fun stat(sessionId: String, path: String): NativeToolStat = decode { stat(request("files/stat", target(sessionId, path))) }
    override suspend fun text(sessionId: String, path: String, offset: Long): NativeToolText = decode {
        val value = request("files/text", target(sessionId, path).put("range", JSONObject().put("offset", offset)))
        NativeToolText(stat(value), value.getString("text"), value.count("offset", 1), value.count("lines"), value.boolean("eof"))
    }
    override suspend fun bytes(sessionId: String, path: String): NativeToolBytes = decode {
        val value = request("files/bytes", target(sessionId, path).put("options", JSONObject()))
        if (value.getString("encoding") != "base64") malformed()
        val data = Base64.getDecoder().decode(value.getString("data"))
        if (data.size > 8 * 1024 * 1024) malformed()
        NativeToolBytes(stat(value), data, value.count("offset"), value.boolean("eof"))
    }
    override fun watch(sessionId: String, path: String): Flow<JSONObject> = events("$base/files/watch?sessionId=${encode(sessionId)}&path=${encode(path)}")
    override suspend fun content(sessionId: String, before: Long?): NativeToolContentPage = decode {
        val value = request("content", JSONObject().put("sessionId", sessionId).put("limit", 30).apply { before?.let { put("beforeSeq", it) } })
        NativeToolContentPage(objects(value.getJSONArray("entries")).map { row ->
            NativeToolContent(row.count("seq"), row.count("turn", 1), row.getString("type"), row.optJSONArray("files")?.let { files -> objects(files).map { file ->
                NativeToolDeclaration(file.getInt("index"), file.getString("path"), file.stringOrNull("description"))
            } } ?: emptyList())
        }, if (value.boolean("hasMore")) value.count("nextBefore") else null)
    }
    override suspend fun changes(sessionId: String, seq: Long): NativeToolChanges = decode {
        val value = request("changes/summary", JSONObject().put("sessionId", sessionId).put("seq", seq))
        NativeToolChanges(seq, value.count("turn", 1), objects(value.getJSONArray("files")).map { row -> NativeToolChange(
            row.getString("path"), row.getString("display"), row.count("added"), row.count("deleted"), row.optBoolean("binary"), row.optBoolean("oversized")) },
            value.count("total"), value.count("added"), value.count("deleted"))
    }
    override suspend fun diff(sessionId: String, seq: Long, index: Int): NativeToolDiff = decode {
        val value = request("changes/diff", JSONObject().put("sessionId", sessionId).put("seq", seq).put("index", index))
        val kind = value.getString("kind").also { if (it !in setOf("text", "binary", "oversized")) malformed() }
        NativeToolDiff(kind, value.getString("path"), value.getString("display"), value.optBoolean("coarse"),
            value.optJSONArray("hunks")?.let { hunks -> objects(hunks).flatMap { strings(it.getJSONArray("lines")) } } ?: emptyList())
    }
    override suspend fun terminals(sessionId: String): List<NativeToolTerminal> = decode {
        val value = call("$base/terminal/list", JSONObject().put("sessionId", sessionId)) as? JSONArray ?: malformed()
        objects(value).map(::terminal)
    }
    override suspend fun createTerminal(sessionId: String, id: String): NativeToolTerminal = decode {
        terminal(request("terminal/create", JSONObject().put("sessionId", sessionId).put("request", JSONObject().put("id", id).put("cols", 80).put("rows", 24))))
    }
    override suspend fun renameTerminal(key: NativeTerminalKey, title: String) { request("terminal/rename", key(key).put("title", title)) }
    override suspend fun closeTerminal(key: NativeTerminalKey) { request("terminal/close", key(key)) }
    override suspend fun attach(key: NativeTerminalKey): String = request("terminal/attachment", key(key)).getString("attachmentId")
    override fun terminalFrames(attachmentId: String): Flow<JSONObject> = events("$base/terminal/attachment/${encode(attachmentId)}")
    override suspend fun input(attachmentId: String, frame: JSONObject) {
        val result = request("terminal/attachment/${encode(attachmentId)}/input", frame)
        if (result.get("accepted") != true || result.getLong("sequence") != frame.getLong("sequence")) malformed()
    }
    override suspend fun detach(attachmentId: String) { request("terminal/attachment/${encode(attachmentId)}/detach", JSONObject()) }
    private fun proof(source: NativeToolSource.Attachment) = JSONObject().put("sessionId", source.sessionId).put("seq", source.seq).put("attachmentId", source.attachmentId)
    override suspend fun attachment(source: NativeToolSource.Attachment): NativeToolAttachmentInfo = decode {
        val value = request("attachments/stat", proof(source))
        if (value.getString("attachmentId") != source.attachmentId) malformed()
        NativeToolAttachmentInfo(value.getString("kind"), source.attachmentId, value.getString("name"), value.count("bytes"), value.getString("mediaType"))
    }
    override suspend fun attachmentBytes(source: NativeToolSource.Attachment): ByteArray = decode {
        val value = request("attachments/bytes", proof(source))
        if (value.getString("attachmentId") != source.attachmentId || value.getString("encoding") != "base64") malformed()
        Base64.getDecoder().decode(value.getString("data")).also { if (it.size > 8 * 1024 * 1024 || it.size.toLong() != value.count("bytes")) malformed() }
    }
    override suspend fun pdf(source: NativeToolSource): NativeToolPdf = decode {
        val value = when (source) {
            is NativeToolSource.Workspace -> request("files/pdf", target(source.sessionId, source.path))
            is NativeToolSource.Attachment -> request("attachments/pdf", proof(source))
        }
        if (value.getString("encoding") != "base64") malformed()
        val data = Base64.getDecoder().decode(value.getString("data"))
        if (data.size > 8 * 1024 * 1024 || data.size.toLong() != value.count("pdfBytes")) malformed()
        NativeToolPdf(data, strings(value.getJSONArray("missingFonts")), if (source is NativeToolSource.Workspace) stat(value) else null)
    }
    override suspend fun describeCopy(source: NativeToolSource): NativeToolCopyPlan = when (source) {
        is NativeToolSource.Workspace -> stat(source.sessionId, source.path).let { stat ->
            NativeToolCopyPlan(source, source.path.substringAfterLast('/'), stat.bytes, stat.version, stat.path)
        }
        is NativeToolSource.Attachment -> attachment(source).let { info -> NativeToolCopyPlan(source, info.name, info.bytes, info.attachmentId,
            mediaType = info.mediaType.takeIf { info.kind == "image" }, sha256 = info.attachmentId.removePrefix("sha256:").takeIf { info.attachmentId.startsWith("sha256:") && it.matches(Regex("[a-f0-9]{64}")) }) }
    }
    override suspend fun copy(plan: NativeToolCopyPlan, append: suspend (ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        val endpoint = NativeRuntimeHost.connection() ?: throw NativeApiException("runtime_unavailable")
        if (plan.bytes != null && plan.bytes > 100L * 1024 * 1024) throw NativeApiException("NATIVE_TOOLS_EXPORT_TOO_LARGE")
        when (val source = plan.source) {
            is NativeToolSource.Workspace -> {
                val guard = NativeToolCopyGuard(plan)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!endpoint.isCurrent()) throw NativeApiException("runtime_unavailable")
                    val value = request("files/bytes", target(source.sessionId, source.path).put("options", JSONObject().put("range", JSONObject().put("offset", guard.offset).put("length", NativeToolCopyGuard.WINDOW))))
                    if (value.getString("encoding") != "base64") malformed()
                    val bytes = Base64.getDecoder().decode(value.getString("data")); val stat = stat(value)
                    guard.accept(NativeToolBytes(stat, bytes, value.count("offset"), value.boolean("eof")))
                    append(bytes)
                    if (value.boolean("eof")) break
                }
                guard.finish(stat(source.sessionId, source.path))
            }
            is NativeToolSource.Attachment -> {
                val current = attachment(source)
                if (current.attachmentId != plan.version || current.bytes != plan.bytes || current.name != plan.name) throw NativeApiException("NATIVE_TOOLS_COPY_CHANGED")
                val connection = endpoint.open("$base/attachments/export?sessionId=${encode(source.sessionId)}&seq=${source.seq}&attachmentId=${encode(source.attachmentId)}")
                try {
                    val status = connection.responseCode
                    if (status !in 200..299) throw NativeApiException(NativeHttpErrors.code(status, connection.errorStream?.use {
                        String(NativeLocalTransport.boundedBytes(it, 65536), java.nio.charset.StandardCharsets.UTF_8) }))
                    var bytes = 0L
                    connection.inputStream.use { input -> val buffer = ByteArray(65536)
                        while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break
                            bytes += count; if (bytes > 100L * 1024 * 1024 || bytes > current.bytes) throw NativeApiException("NATIVE_TOOLS_EXPORT_TOO_LARGE")
                            append(buffer.copyOf(count))
                        }
                    }
                    if (bytes != current.bytes) throw NativeApiException("NATIVE_TOOLS_COPY_CHANGED")
                } finally { connection.disconnect() }
            }
        }
        if (!endpoint.isCurrent()) throw NativeApiException("runtime_unavailable")
    }

    private suspend fun request(path: String, value: JSONObject): JSONObject {
        val result = call("$base/$path", value)
        return if (result == null) JSONObject() else objectValue(result)
    }
    private suspend fun call(path: String, value: JSONObject?): Any? {
        val envelope = transport.json(path, if (value == null) "GET" else "POST", value)
        if (envelope.get("ok") !is Boolean) malformed()
        if (!envelope.getBoolean("ok")) throw NativeApiException(envelope.getJSONObject("error").getString("code"))
        return envelope.opt("result").takeUnless { it === JSONObject.NULL }
    }
    private fun events(path: String): Flow<JSONObject> = transport.events(path).transform { event ->
        if (event is NativeTransportEvent.Frame) {
            val frame = event.value
            if (frame.has("ok") && !frame.optBoolean("ok")) throw NativeApiException(frame.getJSONObject("error").getString("code"))
            emit(frame)
        }
    }
    private fun target(sessionId: String, path: String) = JSONObject().put("sessionId", sessionId).put("path", path)
    private fun key(key: NativeTerminalKey) = JSONObject().put("sessionId", key.sessionId).put("id", key.id)
    private fun stat(value: JSONObject) = NativeToolStat(value.getString("absolutePath"), value.getString("version"), value.longOrNull("bytes"))
    private fun terminal(value: JSONObject): NativeToolTerminal {
        val shell = value.getJSONObject("shell")
        return NativeToolTerminal(value.getString("id"), value.getString("title"), shell.getString("name"), shell.getString("path"),
            value.getString("cwd"), value.getInt("cols"), value.getInt("rows"), value.getString("state").also { if (it !in setOf("running", "exited", "failed")) malformed() },
            value.longOrNull("exitCode"), value.has("error"))
    }
    private suspend fun <T> decode(block: suspend () -> T): T = withContext(Dispatchers.Default) {
        try { block() } catch (error: JSONException) { throw NativeApiException("malformed_response", error) }
        catch (error: IllegalArgumentException) { throw NativeApiException("malformed_response", error) }
    }
    private fun objectValue(value: Any?): JSONObject = value as? JSONObject ?: malformed()
    private fun objects(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }
    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }
    private fun JSONObject.boolean(key: String): Boolean = get(key) as? Boolean ?: malformed()
    private fun JSONObject.count(key: String, min: Long = 0): Long {
        val value = get(key) as? Number ?: malformed(); val result = value.toLong()
        if (result < min || result.toDouble() != value.toDouble()) malformed()
        return result
    }
    private fun JSONObject.longOrNull(key: String) = if (!has(key) || isNull(key)) null else (get(key) as? Number)?.toLong() ?: malformed()
    private fun JSONObject.stringOrNull(key: String) = if (!has(key) || isNull(key)) null else getString(key)
    private fun encode(value: String) = NativeApiClient.encodeSegment(value)
    private fun malformed(): Nothing = throw NativeApiException("malformed_response")
}
