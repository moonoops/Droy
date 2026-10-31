package io.droyagent.app.nativeui.standardvoice

import io.droyagent.app.nativeui.data.NativeApiException
import org.json.JSONObject

/**
 * DSH has no assistant-to-rpc field. Freeze the input batch at each attempt, and publish only its
 * successful committed text. Failed/retried attempts and reconnect history never become speech.
 */
class NativeStandardVoiceReplies(private val sessionId: String) {
    private val nonces = mutableSetOf<String>()
    private val published = mutableSetOf<Long>()
    private var cursor = -1L
    private var revision = 0L
    private var turn = 0
    private var step = 0
    private var owner: String? = null
    private val batch = mutableSetOf<String>()
    private var unknownInput = false
    private var attempt: Attempt? = null
    private var settlement: JSONObject? = null
    private data class Attempt(val id: String, val turn: Int, val step: Int, val after: Long, val nonce: String?, var next: Int = 0)

    fun register(nonce: String) { voiceIdentity(nonce); nonces += nonce }

    fun accept(frame: JSONObject): List<NativeStandardVoiceReplyEvent> {
        when (frame.getString("type")) {
            "snapshot" -> {
                if (frame.getJSONObject("header").getString("id") != sessionId) gap()
                cursor = frame.getLong("cursor"); revision = frame.optJSONObject("assistantStream")?.optLong("revision") ?: 0L
                turn = 0; step = 0; owner = null; batch.clear(); unknownInput = false; attempt = null; settlement = null
                // Recover coordinates only. Historical inputs and any compact active prefix grant no speech ownership.
                val rows = frame.getJSONArray("records")
                for (i in 0 until rows.length()) {
                    val event = rows.getJSONObject(i).getJSONObject("event")
                    when (event.getString("type")) {
                        "turn/start" -> { turn = event.getJSONObject("data").getInt("turn"); step = 0 }
                        "step/start" -> { turn = event.getJSONObject("data").getInt("turn"); step = event.getJSONObject("data").getInt("step") }
                        "turn/end" -> { turn = 0; step = 0 }
                    }
                }
            }
            "event" -> {
                val event = frame.getJSONObject("event"); val seq = event.getLong("seq")
                if (seq <= cursor) return emptyList()
                if (seq != cursor + 1) gap()
                cursor = seq
                val data = event.optJSONObject("data") ?: JSONObject()
                when (event.getString("type")) {
                    "turn/start" -> { turn = data.getInt("turn"); step = 0; owner = null; batch.clear(); unknownInput = false }
                    "step/start" -> {
                        val nextTurn = data.getInt("turn")
                        if (nextTurn != turn) owner = null
                        turn = nextTurn; step = data.getInt("step"); batch.clear(); unknownInput = false
                    }
                    "user/message" -> {
                        if (event.opt("surfaceOp") != "append" || turn <= 0 || step <= 0) { owner = null; unknownInput = true }
                        else when (data.optJSONObject("source")?.optString("kind")) {
                            // These installed SDK producers describe environment/catalog state, not a new human request.
                            "runtime-context", "skill-catalog", "time-context" -> Unit
                            "user" -> {
                                val rpc = data.getJSONObject("source").opt("rpcId") as? String
                                if (rpc == null) unknownInput = true else batch += rpc
                                owner = if (!unknownInput && batch.size == 1) batch.single().takeIf { it in nonces } else null
                            }
                            else -> { owner = null; unknownInput = true }
                        }
                    }
                    "assistant/message", "assistant/attempt" -> attempt?.let {
                        if (seq > it.after && data.getInt("turn") == it.turn && data.getInt("step") == it.step) settlement = event
                    }
                    "turn/end" -> { owner = null; turn = 0; step = 0; batch.clear(); unknownInput = false }
                }
            }
            "assistant-stream" -> {
                val value = frame.getJSONObject("frame")
                val rev = value.getLong("revision")
                if (rev <= revision && rev != 1L) return emptyList()
                if (rev != revision + 1) gap()
                revision = rev
                when (value.getString("type")) {
                    "start" -> {
                        if (attempt != null) gap()
                        val t = value.getInt("turn"); val s = value.getInt("step"); val after = value.getLong("startedAfterSeq")
                        if (after > cursor) gap()
                        attempt = Attempt(value.getString("attemptId"), t, s, after,
                            owner.takeIf { t == turn && s == step && !unknownInput })
                        settlement = null
                    }
                    "chunk" -> {
                        val current = attempt ?: return emptyList() // A reconnect prefix never grants ownership.
                        if (value.getString("attemptId") != current.id || value.getInt("index") != current.next) gap()
                        current.next++
                    }
                    "end" -> {
                        val current = attempt ?: return emptyList()
                        if (value.getString("attemptId") != current.id || value.getInt("index") != current.next) gap()
                        val event = settlement; attempt = null; settlement = null
                        val outcome = value.getJSONObject("outcome")
                        if (outcome.getString("kind") == "abandoned") return emptyList()
                        if (event == null || event.getLong("seq") != outcome.getLong("seq") || event.getString("type") != outcome.getString("eventType")) gap()
                        if (current.nonce == null || event.getString("type") != "assistant/message"
                            || event.opt("surfaceOp") != "append" || event.getJSONObject("data").optBoolean("interrupted")) return emptyList()
                        val seq = event.getLong("seq")
                        if (!published.add(seq)) return emptyList()
                        val blocks = event.getJSONObject("data").getJSONObject("message").getJSONArray("content")
                        val text = (0 until blocks.length()).mapNotNull { index -> blocks.getJSONObject(index).let {
                            (it.opt("text") as? String).takeIf { _ -> it.optString("type") == "text" }
                        } }.joinToString("\n")
                        if (text.isBlank()) return emptyList()
                        val key = "${current.turn}/${current.step}"
                        return listOf(NativeStandardVoiceReplyEvent.Bound(sessionId, key, current.nonce, true),
                            NativeStandardVoiceReplyEvent.Text(sessionId, key, current.id, "seq:$seq", text, true, true))
                    }
                }
            }
            else -> gap()
        }
        return emptyList()
    }

    private fun gap(): Nothing = throw NativeApiException("stream_gap")
}
