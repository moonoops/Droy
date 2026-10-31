package io.droyagent.app.nativeui.standardvoice

import io.droyagent.app.nativeui.data.NativeApiException
import org.json.JSONArray
import org.json.JSONObject

interface NativeStandardVoiceStore {
    suspend fun load(): NativeStandardVoiceJournal?
    suspend fun save(journal: NativeStandardVoiceJournal?)
}

/** Bind to a private product preference/file. The writer must durably commit before returning true. */
class NativeStandardVoiceJsonStore(
    private val read: suspend () -> String?,
    private val write: suspend (String?) -> Boolean,
) : NativeStandardVoiceStore {
    override suspend fun load(): NativeStandardVoiceJournal? {
        return try {
            val raw = read() ?: return null
            if (raw.length > 2_000_000) throw NativeApiException("native_standard_voice_storage")
            val value = JSONObject(raw)
            if (value.getInt("version") != 1) invalidVoice()
            val target = value.getJSONObject("target")
            val rows = value.getJSONArray("submissions")
            if (rows.length() > 256) invalidVoice()
            val submissions = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                NativeStandardVoiceSubmission(row.string("utteranceKey", 1100), row.string("nonce", 256),
                    row.string("text", 32768), NativeStandardVoiceDelivery.valueOf(row.string("delivery", 32)),
                    row.optJSONObject("receipt")?.let { NativeStandardVoiceReceipt(it.string("sessionId", 256), it.string("rpcId", 256)) })
            }
            NativeStandardVoiceJournal(value.string("operationId", 256), value.string("runtimeIdentity", 256),
                NativeStandardVoiceTarget(target.string("draftId", 256), target.nullString("sessionId", 256),
                    target.nullString("workspaceId", 256), target.string("draftToken", 32768, true)),
                value.nullString("captureId", 256), value.boolean("startAttempted"), value.boolean("stopRequested"),
                value.optJSONObject("wake")?.let { NativeStandardVoiceWake(it.nonnegative("eventId"),
                    NativeStandardVoiceWakePhase.valueOf(it.string("phase", 32))) }, submissions).also(::validate)
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            throw NativeApiException("native_standard_voice_storage", failure)
        }
    }

    override suspend fun save(journal: NativeStandardVoiceJournal?) {
        val raw = journal?.let { value ->
            validate(value)
            val target = value.target
            JSONObject().put("version", 1).put("operationId", value.operationId).put("runtimeIdentity", value.runtimeIdentity)
                .put("target", JSONObject().put("draftId", target.draftId).put("sessionId", target.sessionId ?: JSONObject.NULL)
                    .put("workspaceId", target.workspaceId ?: JSONObject.NULL).put("draftToken", target.draftToken))
                .put("captureId", value.captureId ?: JSONObject.NULL).put("startAttempted", value.startAttempted)
                .put("stopRequested", value.stopRequested).put("wake", value.wake?.let {
                    JSONObject().put("eventId", it.eventId).put("phase", it.phase.name)
                } ?: JSONObject.NULL)
                .put("submissions", JSONArray(value.submissions.map { row ->
                    JSONObject().put("utteranceKey", row.utteranceKey).put("nonce", row.nonce).put("text", row.text)
                        .put("delivery", row.delivery.name).put("receipt", row.receipt?.let {
                            JSONObject().put("sessionId", it.sessionId).put("rpcId", it.rpcId)
                        } ?: JSONObject.NULL)
                })).toString().also { if (it.length > 2_000_000) throw NativeApiException("native_standard_voice_storage") }
        }
        try {
            if (!write(raw)) throw NativeApiException("native_standard_voice_storage")
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            throw NativeApiException("native_standard_voice_storage", failure)
        }
    }

    private fun validate(value: NativeStandardVoiceJournal) {
        voiceOperationIdentity(value.operationId); voiceIdentity(value.runtimeIdentity); voiceIdentity(value.target.draftId)
        value.target.sessionId?.let(::voiceIdentity); value.target.workspaceId?.let(::voiceIdentity)
        value.captureId?.let(::voiceIdentity)
        if (value.target.draftToken.length > 32768 || value.submissions.size > 256 || value.wake?.eventId?.let { it <= 0 } == true) invalidVoice()
        if (value.submissions.map { it.nonce }.distinct().size != value.submissions.size ||
            value.submissions.map { it.utteranceKey }.distinct().size != value.submissions.size) invalidVoice()
        value.submissions.forEach { row ->
            voiceIdentity(row.nonce)
            if (row.utteranceKey.isBlank() || row.utteranceKey.length > 1100 || row.text.isBlank() || row.text.length > 32768) invalidVoice()
            if ((row.delivery == NativeStandardVoiceDelivery.ACCEPTED) != (row.receipt != null)) invalidVoice()
            row.receipt?.let { voiceIdentity(it.sessionId); if (it.rpcId != row.nonce) invalidVoice() }
        }
    }
}
