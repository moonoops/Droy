package io.droyagent.app.nativeui.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap

/** The journal keeps DSH's seqs. Transient attempts never acquire a durable seq. */
class NativeSessionReducer {
    private val journal = TreeMap<Long, JSONObject>()
    private var active: Attempt? = null
    private var finishedAttempt: String? = null
    private var projectionSeq: Long = -1
    private val projectionKeySeqs = mutableMapOf<String, Long>()
    private var activitySeq: Long = -1
    private var closedThroughTurn: Int = 0
    var cursor: Long = -1
        private set
    var hasMore: Boolean = false
        private set
    var running: Boolean = false
        private set
    var projections: JSONObject = JSONObject()
        private set

    private data class Attempt(
        val id: String,
        var nextIndex: Int,
        val blocks: TreeMap<Int, JSONObject> = TreeMap(),
        val textBuffers: TreeMap<Int, StringBuilder> = TreeMap(),
        var committedSeq: Long? = null,
        var live: Boolean = true,
    )

    fun reset() {
        journal.clear(); active = null; finishedAttempt = null
        cursor = -1; hasMore = false; running = false; projections = JSONObject()
        activeTurn = null; activeStep = null; projectionSeq = -1; projectionKeySeqs.clear()
        activitySeq = -1; closedThroughTurn = 0
    }

    /** Returns false on an unfillable stream gap; the caller must open a fresh baseline. */
    fun accept(frame: JSONObject): Boolean {
        when (frame.getString("type")) {
            "snapshot" -> {
                journal.clear()
                frame.getJSONArray("records").objects().forEach { addEvent(it.getJSONObject("event")) }
                cursor = frame.getLong("cursor")
                hasMore = frame.getBoolean("hasMore")
                active = null; finishedAttempt = null; activeTurn = null; activeStep = null
                replaceProjections(frame.getJSONObject("projections"))
                journal.values.lastOrNull { it.optString("type") in setOf("turn/start", "turn/end") }?.let { event ->
                    observeTurn(event.optString("type") == "turn/start", event.getLong("seq"), event.getJSONObject("data").getInt("turn"))
                }
                frame.optJSONObject("assistantStream")?.optJSONObject("activeAttempt")?.let {
                    active = Attempt(it.getString("attemptId"), it.getInt("nextIndex"))
                    activeTurn = it.getInt("turn"); activeStep = it.getInt("step")
                    it.getJSONArray("stream").objects().forEach { record -> applyCompact(active!!, record) }
                    observeAttempt(it.getLong("startedAfterSeq"), activeTurn!!)
                }
            }
            "event" -> {
                val event = frame.getJSONObject("event")
                val seq = event.getLong("seq")
                if (seq <= cursor) return true
                if (seq != cursor + 1) return false
                addEvent(event); cursor = seq
                when (event.getString("type")) {
                    "turn/start" -> observeTurn(true, seq, event.getJSONObject("data").getInt("turn"))
                    "turn/end" -> {
                        observeTurn(false, seq, event.getJSONObject("data").getInt("turn"))
                        if (activeTurn == event.getJSONObject("data").getInt("turn")) active = null
                    }
                    "assistant/message", "assistant/attempt" -> {
                        // The persisted message replaces the same turn/step's transient attempt.
                        active?.let { attempt ->
                            val data = event.getJSONObject("data")
                            if (data.optString("attemptId") == attempt.id ||
                                attempt.committedSeq == seq || matchingTurnStep(event)) {
                                active = null
                            }
                        }
                    }
                    "step/end" -> if (active?.committedSeq != null) active = null
                }
            }
            "assistant-stream" -> {
                val f = frame.getJSONObject("frame")
                val id = f.getString("attemptId")
                when (f.getString("type")) {
                    "start" -> {
                        if (id == finishedAttempt) return true
                        if (active != null && active!!.id != id) return false
                        active = Attempt(id, 0)
                        activeTurn = f.getInt("turn"); activeStep = f.getInt("step")
                        observeAttempt(f.getLong("startedAfterSeq"), activeTurn!!)
                    }
                    "chunk" -> {
                        val attempt = active ?: return id == finishedAttempt
                        if (attempt.id != id) return false
                        val index = f.getInt("index")
                        if (index < attempt.nextIndex) return true
                        if (index != attempt.nextIndex) return false
                        applyChunk(attempt, f.getJSONObject("chunk")); attempt.nextIndex++
                    }
                    "end" -> {
                        if (id == finishedAttempt) return true
                        val attempt = active ?: run { finishedAttempt = id; return true }
                        if (attempt.id != id || f.getInt("index") != attempt.nextIndex) return false
                        val outcome = f.getJSONObject("outcome")
                        finishedAttempt = id
                        if (outcome.getString("kind") == "abandoned") active = null
                        else {
                            val seq = outcome.getLong("seq")
                            attempt.live = false
                            if (journal.containsKey(seq)) active = null else attempt.committedSeq = seq
                        }
                    }
                }
            }
            else -> return false
        }
        return true
    }

    private var activeTurn: Int? = null
    private var activeStep: Int? = null
    private fun matchingTurnStep(event: JSONObject): Boolean {
        val data = event.optJSONObject("data") ?: return false
        return activeTurn != null && activeStep != null &&
            data.optInt("turn", -1) == activeTurn && data.optInt("step", -1) == activeStep
    }

    fun prepend(page: JSONObject) {
        page.getJSONArray("records").objects().forEach { addEvent(it.getJSONObject("event")) }
        hasMore = page.getBoolean("hasMore")
    }

    fun latestFailure(): Pair<Long, String>? {
        val boundary = journal.values.lastOrNull { it.optString("type") in setOf("turn/start", "turn/end") } ?: return null
        if (boundary.optString("type") != "turn/end") return null
        val reason = boundary.getJSONObject("data").optJSONObject("reason") ?: return null
        return when (reason.optString("kind")) {
            "error" -> boundary.getLong("seq") to (reason.optJSONObject("error")?.optString("code")?.takeIf(String::isNotBlank) ?: "turn_error")
            "interrupted" -> boundary.getLong("seq") to "turn_interrupted"
            else -> null
        }
    }

    fun oldestSeq(): Long? = journal.firstKeyOrNull()
    fun containsRequest(requestId: String): Boolean = journal.values.any {
        it.getString("type") == "user/message" &&
            it.getJSONObject("data").optJSONObject("source")?.optString("rpcId") == requestId
    } || projections.optJSONObject("inbox")?.let { inbox ->
        listOf("next-turn", "next-step").any { key -> inbox.optJSONArray(key)?.objects()?.any {
            it.optJSONObject("source")?.optString("rpcId") == requestId
        } == true }
    } == true

    fun messages(): List<NativeMessage> {
        val visible = TreeMap<Long, NativeMessage>()
        journal.values.forEach { event ->
            val seq = event.getLong("seq")
            val replacement = event.optJSONObject("surfaceOp")
            if (replacement?.optString("op") == "replace") {
                visible.keys.filter { it >= replacement.getLong("startSeq") && it <= replacement.getLong("endSeq") }
                    .forEach(visible::remove)
            }
            eventMessage(event)?.let { visible[seq] = it }
        }
        val result = visible.values.toMutableList()
        projections.optJSONObject("inbox")?.let { inbox ->
            listOf("next-turn", "next-step").forEach { key ->
                inbox.optJSONArray(key)?.objects()?.forEach { queued ->
                    val id = queued.getString("id")
                    if (result.none { it.id == id }) {
                        message(queued, -1, "user")?.let {
                            result += it.copy(seq = null, kind = if (it.kind == "context") "context" else "pending")
                        }
                    }
                }
            }
        }
        active?.let { attempt ->
            val text = contentText(attempt.blocks.map { (index, block) ->
                val value = attempt.textBuffers[index]?.toString()
                if (value == null) block else JSONObject().put("type", block.optString("type")).put("text", value)
            })
            if (text.isNotEmpty()) result += NativeMessage(
                id = "attempt:${attempt.id}", role = "assistant", text = text, streaming = attempt.live,
            )
        }
        return result
    }

    fun usage(): NativeUsage? {
        val token = projections.optJSONObject("tokenUsage") ?: return null
        val rawUsages = journal.values.mapNotNull { e ->
            when (e.getString("type")) {
                "assistant/message" -> e.optJSONObject("data")?.optJSONObject("usage")
                "compaction/summary" -> e.optJSONObject("data")?.optJSONObject("usage")
                else -> null
            }
        }
        val input = token.count("uncachedInputTokens")?.let { uncached ->
            val read = token.count("cacheReadTokens") ?: return@let null
            val write = token.count("cacheWriteTokens") ?: return@let null
            sumCounts(uncached, read, write)
        }
        val output = token.count("outputTokens")
        if ((input ?: 0) == 0L && (output ?: 0) == 0L && rawUsages.isEmpty()) return null
        val summary = projections.optJSONObject("droyCompactionUsage")
        val summaryBuckets = summary?.optJSONObject("totals")
        val summaryInput = summaryBuckets?.let { sumCounts(
            it.count("uncachedInputTokens") ?: return@let null,
            it.count("cacheReadTokens") ?: return@let null,
            it.count("cacheWriteTokens") ?: return@let null,
        ) } ?: 0L
        val summaryOutput = summaryBuckets?.count("outputTokens") ?: 0L
        val allInput = input?.let { sumCounts(it, summaryInput) }
        val allOutput = output?.let { sumCounts(it, summaryOutput) }
        val completeCache = !hasMore && rawUsages.isNotEmpty() &&
            rawUsages.all { it.has("cacheReadTokens") && !it.isNull("cacheReadTokens") } &&
            (summary?.optLong("missingUsageCount", 0) ?: 0) == 0L
        val read = if (completeCache) sumCounts(token.count("cacheReadTokens") ?: 0,
            summaryBuckets?.count("cacheReadTokens") ?: 0) else null
        val stats = projections.optJSONObject("sessionStats")
        val decodeMs = stats?.optDouble("decodeMs", 0.0) ?: 0.0
        val decodeTokens = stats?.optDouble("decodeTokens", 0.0) ?: 0.0
        val ttftSteps = stats?.optDouble("ttftSteps", 0.0) ?: 0.0
        return NativeUsage(
            totalTokens = if (allInput != null && allOutput != null) sumCounts(allInput, allOutput) else null,
            inputTokens = allInput, outputTokens = allOutput, cacheReadTokens = read,
            cacheHitRate = if (allInput != null && allInput > 0 && read != null) read.toDouble() / allInput else null,
            tokensPerSecond = if (decodeMs > 0 && decodeTokens.isFinite()) decodeTokens * 1000 / decodeMs else null,
            firstTokenMs = if (ttftSteps > 0) stats!!.optDouble("ttftMs") / ttftSteps else null,
            partial = !completeCache || (summary?.optLong("missingUsageCount", 0) ?: 0) > 0,
        )
    }

    fun replaceProjections(value: JSONObject) {
        val seq = value.getLong("asOfSeq")
        if (seq < projectionSeq) return
        val values = value.getJSONObject("values")
        val keys = values.keys().asSequence().toList()
        keys.forEach { key ->
            if (seq > maxOf(projectionSeq, projectionKeySeqs[key] ?: -1)) {
                projections.put(key, values.get(key)); projectionKeySeqs[key] = seq
                if (key == "droyTurnBoundary") observeBoundary(values.getJSONObject(key), seq)
            }
        }
        projections.keys().asSequence().toList().filter { it !in keys && (projectionKeySeqs[it] ?: projectionSeq) <= seq }
            .forEach { projections.remove(it); projectionKeySeqs.remove(it) }
        projectionSeq = seq
    }

    fun updateProjection(key: String, value: Any, seq: Long) {
        if (seq <= maxOf(projectionSeq, projectionKeySeqs[key] ?: -1)) return
        projections.put(key, value); projectionKeySeqs[key] = seq
        if (key == "droyTurnBoundary") observeBoundary(value as? JSONObject ?: throw NativeApiException("malformed_response"), seq)
    }

    private fun observeBoundary(value: JSONObject, seq: Long) {
        observeTurn(value.has("openTurnStartSeq") && !value.isNull("openTurnStartSeq"), seq, value.getInt("lastTurn"))
    }

    private fun observeTurn(isRunning: Boolean, seq: Long, turn: Int) {
        // Follow events and control values share the Session sequence space, but arrive on separate sockets.
        if (seq < activitySeq) return
        activitySeq = seq
        running = isRunning
        if (!isRunning) {
            closedThroughTurn = maxOf(closedThroughTurn, turn)
            if (activeTurn != null && activeTurn!! <= turn) active?.live = false
        }
    }

    private fun observeAttempt(startedAfterSeq: Long, turn: Int) {
        if (turn <= closedThroughTurn) { active?.live = false; return }
        if (startedAfterSeq >= activitySeq) {
            activitySeq = startedAfterSeq
            running = true
        }
    }

    private fun addEvent(event: JSONObject) {
        val seq = event.getLong("seq")
        require(seq >= 0) { "invalid_seq" }
        journal[seq] = event
    }

    private fun eventMessage(event: JSONObject): NativeMessage? {
        val seq = event.getLong("seq")
        val type = event.getString("type")
        val data = event.optJSONObject("data") ?: return null
        return when (type) {
            "user/message" -> message(data, seq, "user")
            "system/message" -> message(data.getJSONObject("message"), seq, "system")
            "developer/message" -> message(data.getJSONObject("message"), seq, "developer")
            "assistant/message" -> message(data.getJSONObject("message"), seq, "assistant")
            "tool/call" -> NativeMessage("event:$seq", "tool", data.optString("arguments"), seq,
                "tool_call", toolName = data.getString("name"))
            "tool/result" -> message(data.getJSONObject("message"), seq, "tool")?.copy(
                kind = "tool_result", failed = data.getJSONObject("message").optBoolean("isError"),
            )
            else -> null
        }
    }

    private fun message(message: JSONObject, seq: Long, role: String): NativeMessage? {
        val content = message.optJSONArray("content")?.objects() ?: emptyList()
        val text = contentText(content)
        val attachments = content.mapNotNull { block ->
            val kind = block.optString("type")
            if (kind != "image" && kind != "file") null else block.optJSONObject("attachment")?.let { ref ->
                NativeMessageAttachment(kind, ref.getString("attachmentId"), ref.optString("name").takeIf(String::isNotBlank),
                    ref.optString("mediaType").takeIf(String::isNotBlank), ref.getLong("bytes"))
            }
        }
        if (text.isEmpty() && attachments.isEmpty()) return null
        val source = message.optJSONObject("source")
        val sourceKind = source?.optionalSourceString("kind")
        val context = role == "system" || role == "developer" || role == "user" && sourceKind !in setOf("user", "user-rpc")
        return NativeMessage(message.optString("id", "event:$seq"), role, text, seq,
            kind = if (context) "context" else "text", sourceKind = sourceKind,
            sourceForm = source?.optionalSourceString("form"), summary = source?.optionalSourceString("summary"), attachments = attachments,
            requestId = source?.optionalSourceString("rpcId").takeIf { role == "user" && sourceKind in setOf("user", "user-rpc") })
    }

    private fun contentText(blocks: List<JSONObject>): String = blocks.mapNotNull { block ->
        when (block.optString("type")) {
            "text" -> block.optString("text")
            "reasoning" -> reasoningDisplayText(block.optString("text")).takeIf(String::isNotEmpty)
            else -> null
        }
    }.joinToString("\n\n")

    // Match DSH's reasoning display projection without changing journal or stream data.
    private fun reasoningDisplayText(text: String): String {
        if (!text.startsWith('<')) return text
        val opening = "<signature>"
        if (opening.startsWith(text)) return ""
        if (!text.startsWith(opening)) return text
        val closing = "</signature>"
        val end = text.indexOf(closing, opening.length)
        if (end == -1) return ""
        var start = end + closing.length
        while (start < text.length) {
            if (text[start] == '\n') start++
            else if (text[start] == '\r' && text.getOrNull(start + 1) == '\n') start += 2
            else break
        }
        val display = text.substring(start)
        // DSH hides a processed, blank row using JavaScript trim's whitespace set.
        return display.takeUnless { value -> value.all {
            it in "\t\n\u000B\u000C\r \u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF"
        } } ?: ""
    }

    private fun applyCompact(attempt: Attempt, record: JSONObject) {
        when (record.getString("type")) {
            "text-chunks", "reasoning-chunks" -> {
                val index = record.getInt("index")
                val type = if (record.getString("type") == "text-chunks") "text-delta" else "reasoning-delta"
                val texts = record.getJSONArray("texts")
                for (i in 0 until texts.length()) applyChunk(attempt, JSONObject().put("type", type).put("index", index).put("text", texts.getString(i)))
            }
            "chunk" -> applyChunk(attempt, record.getJSONObject("chunk"))
            "tool-call-chunks" -> Unit
        }
    }

    private fun applyChunk(attempt: Attempt, chunk: JSONObject) {
        when (chunk.getString("type")) {
            "block-start" -> {
                val index = chunk.getInt("index")
                attempt.blocks[index] = JSONObject().put("type", chunk.getString("blockType")).put("text", "")
                attempt.textBuffers.remove(index)
            }
            "text-delta", "reasoning-delta" -> {
                val index = chunk.getInt("index")
                val block = attempt.blocks.getOrPut(index) { JSONObject().put("type", if (chunk.getString("type") == "text-delta") "text" else "reasoning").put("text", "") }
                attempt.textBuffers.getOrPut(index) { StringBuilder(block.optString("text")) }.append(chunk.getString("text"))
            }
            "block-end" -> {
                val index = chunk.getInt("index")
                attempt.blocks[index] = chunk.getJSONObject("block"); attempt.textBuffers.remove(index)
            }
        }
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun <K, V> TreeMap<K, V>.firstKeyOrNull(): K? = if (isEmpty()) null else firstKey()
private fun JSONObject.count(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key)
    if (value !is Number) return null
    val number = value.toDouble()
    if (!number.isFinite() || number < 0 || number % 1 != 0.0 || number > 9_007_199_254_740_991.0) return null
    return number.toLong()
}
private fun JSONObject.optionalSourceString(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null
private fun sumCounts(vararg values: Long): Long? {
    val sum = values.fold(0L) { total, value -> Math.addExact(total, value) }
    return sum.takeIf { it <= 9_007_199_254_740_991L }
}
