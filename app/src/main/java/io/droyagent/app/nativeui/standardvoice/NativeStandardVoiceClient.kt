package io.droyagent.app.nativeui.standardvoice

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeLocalTransport
import org.json.JSONObject

fun interface NativeStandardVoiceExchange {
    suspend fun json(path: String, method: String, body: JSONObject?): JSONObject
}

/** Uses the existing voice route. GET's placeholder state is deliberately never parsed as native status. */
class NativeStandardVoiceClient(
    private val exchange: NativeStandardVoiceExchange = NativeStandardVoiceExchange { path, method, body ->
        NativeLocalTransport().json(path, method, body)
    },
) : NativeStandardVoiceGateway {
    private suspend fun call(operation: String? = null, fields: JSONObject = JSONObject()): JSONObject {
        val envelope = exchange.json("/droy/voice", if (operation == null) "GET" else "POST",
            operation?.let { fields.put("operation", it) })
        if (!envelope.boolean("ok")) {
            val code = envelope.getJSONObject("error").string("code", 128)
            if (!code.matches(Regex("[A-Za-z0-9_./:-]{1,128}"))) invalidVoice()
            throw NativeApiException(code)
        }
        return envelope.getJSONObject("result")
    }

    override suspend fun config(): NativeStandardVoiceConfig = voiceJson {
        val config = call().getJSONObject("config")
        val tone = config.getJSONObject("tts")
        val provider = tone.string("provider", 32)
        if (provider !in setOf("system", "melo", "qwen3", "openai")) invalidVoice()
        val rate = tone.get("rate") as? Number ?: invalidVoice()
        if (!rate.toDouble().isFinite() || rate.toDouble() !in 0.5..2.0) invalidVoice()
        NativeStandardVoiceConfig(config.getJSONObject("entries").boolean("standard"), NativeStandardVoiceTone(
            provider, tone.string("voiceId", 256, true), rate.toDouble(), tone.string("url", 2048, true),
            tone.string("model", 256, true)))
    }

    override suspend fun status() = voiceJson { native(call("status")) }
    override suspend fun start(voiceOperationId: String, sessionId: String, wakeEventId: Long?) = voiceJson {
        voiceIdentity(sessionId)
        val body = owner(voiceOperationId).put("mode", "standard").put("sessionId", sessionId).put("language", "auto")
        wakeEventId?.let { if (it <= 0) invalidVoice(); body.put("wakeEventId", it) }
        native(call("start", body))
    }
    override suspend fun poll(voiceOperationId: String, cursor: Long, transcriptCursor: Int, captureId: String?, transcriptId: String?) = voiceJson {
        if (cursor < 0 || transcriptCursor < 0) invalidVoice()
        val request = owner(voiceOperationId).put("cursor", cursor).put("transcriptCursor", transcriptCursor)
        captureId?.let { voiceIdentity(it); request.put("captureId", it) }
        transcriptId?.let { voiceIdentity(it); request.put("transcriptId", it) }
        val value = call("poll", request)
        val rows = value.getJSONArray("segments")
        if (rows.length() > 256) invalidVoice()
        val segments = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val id = row.string("id", 512)
            NativeStandardVoiceSegment(id, row.nullString("utteranceId", 512) ?: id,
                row.string("text", 32768, true), row.boolean("final"),
                if (row.has("endOfUtterance")) row.boolean("endOfUtterance") else null)
        }
        NativeStandardVoicePoll(native(value), value.nonnegative("nextCursor"),
            value.nonnegative("nextTranscriptCursor").also { if (it > Int.MAX_VALUE) invalidVoice() }.toInt(), segments)
    }
    override suspend fun mute(voiceOperationId: String, muted: Boolean) = voiceJson { native(call("mute", owner(voiceOperationId).put("muted", muted))) }
    override suspend fun speak(voiceOperationId: String, text: String, sessionId: String, replyId: String, utteranceId: String, tone: NativeStandardVoiceTone) {
        voiceIdentity(sessionId); voiceIdentity(replyId); voiceIdentity(utteranceId)
        if (text.isBlank() || text.length > 4000) invalidVoice()
        voiceJson {
            val value = call("speak", owner(voiceOperationId).put("text", text).put("sessionId", sessionId).put("replyId", replyId)
                .put("utteranceId", utteranceId).put("provider", tone.provider).put("voiceId", tone.voiceId)
                .put("rate", tone.rate).put("url", tone.url).put("model", tone.model))
            if (value.opt("cancelled") == true) throw NativeApiException("native_standard_voice_output_cancelled")
        }
    }
    override suspend fun stopSpeaking(voiceOperationId: String) { voiceJson { call("stop_speaking", owner(voiceOperationId)); Unit } }
    override suspend fun stop(voiceOperationId: String) { voiceJson { call("stop", owner(voiceOperationId)); Unit } }
    override suspend fun wakeStatus() = voiceJson {
        val value = call("wake.status")
        val timing = value.string("timing", 32)
        if (timing !in setOf("foreground", "screen_on") || value.string("target", 32) != "standard") invalidVoice()
        NativeStandardVoiceWakeState(value.boolean("enabled"), timing, value.boolean("screenAvailable"),
            value.boolean("suspended"), value.boolean("voiceClaimed"), value.nonnegative("eventId"),
            value.nonnegative("startingEventId"), value.ownerId("voiceOperationId"), value.ownerId("startingVoiceOperationId"))
    }
    override suspend fun consumeWake(voiceOperationId: String, eventId: Long): Boolean = voiceJson {
        if (eventId <= 0) invalidVoice()
        val value = call("wake.consume", owner(voiceOperationId).put("eventId", eventId))
        if (value.string("target", 32) != "standard") invalidVoice()
        value.boolean("consumed").also { consumed ->
            if (consumed && (value.nonnegative("startingEventId") != eventId || value.ownerId("startingVoiceOperationId") != voiceOperationId ||
                    value.ownerId("voiceOperationId")?.let { it != voiceOperationId } == true)) invalidVoice()
        }
    }
    override suspend fun claimWake(voiceOperationId: String) { voiceJson { call("wake.claim", owner(voiceOperationId)); Unit } }
    override suspend fun releaseWake(voiceOperationId: String) { voiceJson { call("wake.release", owner(voiceOperationId)); Unit } }

    private fun owner(value: String): JSONObject { voiceOperationIdentity(value); return JSONObject().put("voiceOperationId", value) }

    private fun native(value: JSONObject): NativeStandardVoiceNativeState {
        val state = value.string("state", 32)
        if (state !in setOf("idle", "awaiting_user", "starting", "recording", "finalizing", "ended", "interrupted", "failed", "completed")) invalidVoice()
        val mode = value.string("mode", 32)
        if (mode != "standard") invalidVoice()
        val level = value.get("level") as? Number ?: invalidVoice()
        if (!level.toDouble().isFinite() || level.toDouble() !in 0.0..1.0) invalidVoice()
        return NativeStandardVoiceNativeState(state, mode, value.nullString("sessionId", 256),
            value.nullString("captureId", 256), value.nullString("transcriptId", 256), value.boolean("muted"),
            value.boolean("speaking"), value.nullString("needsPermission", 64), level.toFloat(),
            state in setOf("failed", "interrupted") || !value.nullString("error", 32768).isNullOrBlank() ||
                !value.nullString("speechError", 32768).isNullOrBlank(), value.ownerId("voiceOperationId"))
    }
}

internal fun invalidVoice(): Nothing = throw NativeApiException("native_standard_voice_invalid")
internal fun voiceIdentity(value: String) { if (value.isBlank() || value.length > 256 || value.any(Char::isISOControl)) invalidVoice() }
internal fun voiceOperationIdentity(value: String) {
    try { if (java.util.UUID.fromString(value).toString() != value) invalidVoice() }
    catch (_: IllegalArgumentException) { invalidVoice() }
}
internal fun JSONObject.ownerId(key: String): String? = nullString(key, 256)?.also(::voiceOperationIdentity)
internal fun JSONObject.string(key: String, limit: Int, empty: Boolean = false): String {
    val value = get(key) as? String ?: invalidVoice()
    if (value.length > limit || !empty && value.isBlank()) invalidVoice()
    return value
}
internal fun JSONObject.nullString(key: String, limit: Int): String? =
    if (!has(key) || isNull(key)) null else string(key, limit, true).takeIf(String::isNotEmpty)
internal fun JSONObject.boolean(key: String): Boolean = get(key) as? Boolean ?: invalidVoice()
internal fun JSONObject.nonnegative(key: String): Long {
    val value = get(key) as? Number ?: invalidVoice()
    val result = value.toLong()
    if (result < 0 || result.toDouble() != value.toDouble()) invalidVoice()
    return result
}
internal suspend fun <T> voiceJson(block: suspend () -> T): T = try { block() }
catch (failure: org.json.JSONException) { throw NativeApiException("native_standard_voice_invalid", failure) }
