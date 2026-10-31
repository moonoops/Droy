package io.droyagent.app.nativeui

import io.droyagent.app.nativeui.data.NativeSessionReducer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeSessionReducerTest {
    @Test fun attachmentOnlyMessagesRetainDurableStructuredReferencesAcrossSnapshots() {
        val event = message(1, "", requestId = "attachment-request")
        event.getJSONObject("event").getJSONObject("data").put("content", JSONArray()
            .put(JSONObject().put("type", "image").put("attachment", JSONObject().put("attachmentId", "sha256:image-fixture")
                .put("mediaType", "image/png").put("bytes", 4).put("width", 1).put("height", 1)))
            .put(JSONObject().put("type", "file").put("attachment", JSONObject().put("attachmentId", "sha256:file-fixture")
                .put("name", "report.txt").put("bytes", 12))))
        val reducer = NativeSessionReducer()
        assertTrue(reducer.accept(snapshot(1, event)))
        val row = reducer.messages().single()
        assertEquals("", row.text)
        assertEquals(listOf("image", "file"), row.attachments.map { it.kind })
        assertEquals("sha256:image-fixture", row.attachments.first().attachmentId)
        assertEquals("report.txt", row.attachments.last().name)
        assertEquals("text", row.kind)
        assertTrue(reducer.containsRequest("attachment-request"))
        assertEquals("attachment-request", row.requestId)
        reducer.accept(snapshot(1, event))
        assertEquals(1, reducer.messages().size)
        assertEquals(2, reducer.messages().single().attachments.size)
    }
    private fun message(seq: Int, text: String, type: String = "user/message", requestId: String? = null): JSONObject {
        val content = JSONObject().put("id", "m$seq").put("role", if (type == "user/message") "user" else "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        if (requestId != null) content.put("source", JSONObject().put("kind", "user").put("rpcId", requestId))
        return JSONObject().put("type", "event").put("event", JSONObject().put("type", type).put("seq", seq).put("time", seq * 100)
            .put("surfaceOp", "append").put("data", if (type == "user/message") content else JSONObject().put("turn", 1).put("step", 1).put("message", content)))
    }
    private fun snapshot(cursor: Int, vararg records: JSONObject): JSONObject = JSONObject().put("type", "snapshot").put("cursor", cursor)
        .put("header", JSONObject().put("id", "session-a")).put("hasMore", false).put("records", JSONArray(records))
        .put("projections", JSONObject().put("asOfSeq", cursor).put("values", JSONObject()))
    private fun live(frame: JSONObject) = JSONObject().put("type", "assistant-stream").put("frame", frame)

    @Test fun committedMessageReplacesBaselineAttemptWithoutDuplicate() {
        val reducer = NativeSessionReducer()
        val baseline = snapshot(1, message(1, "hello"))
        baseline.put("assistantStream", JSONObject().put("revision", 1).put("activeAttempt", JSONObject()
            .put("attemptId", "attempt-a").put("turn", 1).put("step", 1).put("startedAfterSeq", 1).put("nextIndex", 1)
            .put("stream", JSONArray().put(JSONObject().put("type", "text-chunks").put("index", 0).put("time0", 100)
                .put("texts", JSONArray().put("Answer")).put("dt", JSONArray())))))
        assertTrue(reducer.accept(baseline))
        assertTrue(reducer.messages().last().streaming)
        assertTrue(reducer.accept(message(2, "Answer", "assistant/message")))
        assertEquals(listOf("hello", "Answer"), reducer.messages().map { it.text })
        assertFalse(reducer.messages().any { it.streaming })
        assertTrue(reducer.accept(live(JSONObject().put("type", "end").put("attemptId", "attempt-a").put("revision", 2)
            .put("index", 1).put("outcome", JSONObject().put("kind", "committed").put("seq", 2).put("eventType", "assistant/message")))))
        assertEquals(2, reducer.messages().size)
    }

    @Test fun reconnectSnapshotIsReplacementAndDurableEventsAreIdempotent() {
        val reducer = NativeSessionReducer()
        reducer.accept(snapshot(1, message(1, "one", requestId = "request-a")))
        assertTrue(reducer.containsRequest("request-a"))
        assertTrue(reducer.accept(message(1, "one")))
        assertEquals(1, reducer.messages().size)
        assertFalse(reducer.accept(message(3, "gap")))
        reducer.accept(snapshot(3, message(1, "one"), message(2, "two"), message(3, "three")))
        assertEquals(listOf("one", "two", "three"), reducer.messages().map { it.text })
    }

    @Test fun streamGapRequestsFreshBaselineAndRepeatedChunkDoesNotDuplicateText() {
        val reducer = NativeSessionReducer()
        reducer.accept(snapshot(0))
        reducer.accept(live(JSONObject().put("type", "start").put("attemptId", "a").put("turn", 1).put("step", 1).put("startedAfterSeq", 0)))
        val chunk = live(JSONObject().put("type", "chunk").put("attemptId", "a").put("index", 0)
            .put("chunk", JSONObject().put("type", "text-delta").put("index", 0).put("text", "Hi")))
        assertTrue(reducer.accept(chunk)); assertTrue(reducer.accept(chunk))
        assertEquals("Hi", reducer.messages().single().text)
        chunk.getJSONObject("frame").put("index", 2)
        assertFalse(reducer.accept(chunk))
    }

    @Test fun replacementsShadowOldSurfaceAcrossPrependAndRetainStableIds() {
        val replacement = message(5, "revised").apply {
            getJSONObject("event").put("surfaceOp", JSONObject().put("op", "replace").put("startSeq", 1).put("endSeq", 2))
        }
        val reducer = NativeSessionReducer()
        reducer.accept(snapshot(5, replacement))
        reducer.prepend(JSONObject().put("hasMore", false).put("records", JSONArray().put(message(1, "old")).put(message(2, "old answer", "assistant/message"))))
        assertEquals(listOf("revised"), reducer.messages().map { it.text })
        assertEquals("m5", reducer.messages().single().id)
    }

    @Test fun missingCacheUsageIsUnknownRatherThanZero() {
        val record = message(1, "answer", "assistant/message")
        record.getJSONObject("event").getJSONObject("data").put("usage", JSONObject().put("inputTokens", 20).put("outputTokens", 5))
        val base = snapshot(1, record)
        base.getJSONObject("projections").getJSONObject("values").put("tokenUsage", JSONObject()
            .put("uncachedInputTokens", 20).put("cacheReadTokens", 0).put("cacheWriteTokens", 0).put("outputTokens", 5))
        val reducer = NativeSessionReducer(); reducer.accept(base)
        assertEquals(25L, reducer.usage()!!.totalTokens)
        assertNull(reducer.usage()!!.cacheHitRate)
        assertNull(reducer.usage()!!.cacheReadTokens)
        assertTrue(reducer.usage()!!.partial)
    }

    @Test fun staleProjectionCannotOverwriteNewerBaseline() {
        val reducer = NativeSessionReducer()
        reducer.accept(snapshot(10))
        reducer.updateProjection("title", "new", 12)
        reducer.replaceProjections(JSONObject().put("asOfSeq", 11).put("values", JSONObject().put("title", "old")))
        assertEquals("new", reducer.projections.getString("title"))
        reducer.updateProjection("title", "older", 9)
        assertEquals("new", reducer.projections.getString("title"))
    }
    @Test fun failedTurnIsNotPresentedAsSuccessfulCompletion() {
        val reducer = NativeSessionReducer()
        val failure = JSONObject().put("type", "event").put("event", JSONObject().put("type", "turn/end").put("seq", 2)
            .put("time", 200).put("data", JSONObject().put("turn", 1).put("reason", JSONObject().put("kind", "error")
                .put("error", JSONObject().put("code", "AUTHENTICATION").put("message", "Fixture failure")))))
        reducer.accept(snapshot(1, message(1, "question")))
        reducer.accept(failure)
        assertEquals("AUTHENTICATION", reducer.latestFailure()!!.second)
        assertFalse(reducer.running)
    }

    @Test fun queuedAcceptedMessageRemainsVisibleThroughAuthoritativeInbox() {
        val reducer = NativeSessionReducer()
        val queued = JSONObject().put("id", "queued-message").put("role", "user")
            .put("source", JSONObject().put("kind", "user").put("rpcId", "request-queued"))
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Queued question")))
        val base = snapshot(0)
        base.getJSONObject("projections").getJSONObject("values").put("inbox", JSONObject()
            .put("next-turn", JSONArray().put(queued)).put("next-step", JSONArray()))
        reducer.accept(base)
        assertEquals("Queued question", reducer.messages().single().text)
        assertEquals("pending", reducer.messages().single().kind)
        assertTrue(reducer.containsRequest("request-queued"))
    }

    @Test fun onlyDeclaredHumanSourcesBecomeHumanBubbles() {
        val user = message(1, "Time sampled while preparing turn <system-reminder>").apply {
            getJSONObject("event").getJSONObject("data").put("source", JSONObject().put("kind", "user"))
        }
        val rpc = message(2, "Human input").apply {
            getJSONObject("event").getJSONObject("data").put("source", JSONObject().put("kind", "user-rpc").put("rpcId", "request-human"))
        }
        val unknown = message(3, "Opaque original context").apply {
            getJSONObject("event").getJSONObject("data").put("source", JSONObject().put("kind", "future-producer"))
        }
        val reducer = NativeSessionReducer()
        reducer.accept(snapshot(4, user, rpc, unknown, message(4, "No declared source")))
        val messages = reducer.messages()
        assertEquals(listOf("text", "text", "context", "context"), messages.map { it.kind })
        assertEquals("Time sampled while preparing turn <system-reminder>", messages[0].text)
        assertEquals("request-human", messages[1].requestId)
        assertNull(messages[2].requestId)
        assertEquals("future-producer", messages[2].sourceKind)
        assertEquals("Opaque original context", messages[2].text)
    }

    @Test fun producerContextFormsRetainOriginalTextAndNoticeSummary() {
        val forms = listOf("snapshot", "instructions", "catalog", "notice", "relay", "recall", "future-form")
        val records = forms.mapIndexed { index, form ->
            message(index + 1, "Original $form\nFull details").apply {
                val source = JSONObject().put("kind", "fixture-context").put("form", form)
                if (form == "snapshot") source.put("sections", JSONArray().put(JSONObject().put("name", "clock").put("text", "Clock snapshot")))
                if (form == "notice") source.put("summary", "Changed a file")
                getJSONObject("event").getJSONObject("data").put("source", source)
            }
        }
        val reducer = NativeSessionReducer()
        reducer.accept(snapshot(records.size, *records.toTypedArray()))
        assertEquals(forms, reducer.messages().map { it.sourceForm })
        assertTrue(reducer.messages().all { it.kind == "context" })
        assertEquals(forms.map { "Original $it\nFull details" }, reducer.messages().map { it.text })
        assertEquals("Changed a file", reducer.messages()[3].summary)
        assertNull(reducer.messages()[0].summary)
    }

    @Test fun wrappedSystemAndDeveloperMessagesRemainExpandableContext() {
        val records = listOf("system", "developer").mapIndexed { index, role ->
            JSONObject().put("type", "event").put("event", JSONObject().put("type", "$role/message")
                .put("seq", index + 1).put("time", 100).put("data", JSONObject().put("turn", 1).put("step", 1)
                    .put("message", JSONObject().put("id", role).put("role", role).put("source", JSONObject().put("kind", "$role-producer"))
                        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Full $role prompt"))))))
        }
        val reducer = NativeSessionReducer()
        reducer.accept(snapshot(2, *records.toTypedArray()))
        assertEquals(listOf("context", "context"), reducer.messages().map { it.kind })
        assertEquals(listOf("Full system prompt", "Full developer prompt"), reducer.messages().map { it.text })
    }

    private fun turn(seq: Int, number: Int, start: Boolean): JSONObject = JSONObject().put("type", "event")
        .put("event", JSONObject().put("type", if (start) "turn/start" else "turn/end").put("seq", seq)
            .put("time", 100).put("data", JSONObject().put("turn", number)))
    private fun boundary(number: Int, openSeq: Int?): JSONObject = JSONObject().put("lastTurn", number)
        .put("openTurnStartSeq", openSeq ?: JSONObject.NULL)

    @Test fun staleControlIdleCannotHideTheCurrentFollowTurn() {
        val reducer = NativeSessionReducer()
        reducer.accept(snapshot(1, message(1, "queued input")))
        reducer.accept(turn(2, 1, true))
        reducer.updateProjection("droyTurnBoundary", boundary(0, null), 1)
        assertTrue(reducer.running)
        reducer.replaceProjections(JSONObject().put("asOfSeq", 1).put("values", JSONObject().put("droyTurnBoundary", boundary(0, null))))
        assertTrue(reducer.running)
        reducer.accept(turn(3, 1, false))
        assertFalse(reducer.running)
        reducer.updateProjection("droyTurnBoundary", boundary(1, 2), 2)
        assertFalse(reducer.running)
    }

    @Test fun authoritativeControlIdleEndsStreamingAndOlderFollowFramesCannotResurrectIt() {
        val reducer = NativeSessionReducer()
        reducer.accept(snapshot(0))
        reducer.accept(turn(1, 1, true))
        reducer.accept(live(JSONObject().put("type", "start").put("attemptId", "a").put("turn", 1).put("step", 1).put("startedAfterSeq", 1)))
        reducer.accept(live(JSONObject().put("type", "chunk").put("attemptId", "a").put("index", 0)
            .put("chunk", JSONObject().put("type", "text-delta").put("index", 0).put("text", "Interrupted prefix"))))
        assertTrue(reducer.messages().single().streaming)
        reducer.updateProjection("droyTurnBoundary", boundary(1, null), 3)
        assertFalse(reducer.running)
        assertEquals("Interrupted prefix", reducer.messages().single().text)
        assertFalse(reducer.messages().single().streaming)
        // A newly opened follow socket can deliver its older cut after the control socket's idle frame.
        val stale = snapshot(1, turn(1, 1, true))
        stale.getJSONObject("projections").put("values", JSONObject().put("droyTurnBoundary", boundary(1, 1)))
        reducer.accept(stale)
        assertFalse(reducer.running)
        reducer.accept(live(JSONObject().put("type", "start").put("attemptId", "stale").put("turn", 1).put("step", 1).put("startedAfterSeq", 1)))
        assertFalse(reducer.running)
    }

    @Test fun controlActiveAheadOfSnapshotAndNewTurnAfterIdleRemainActive() {
        val reducer = NativeSessionReducer()
        reducer.updateProjection("droyTurnBoundary", boundary(1, 2), 2)
        val old = snapshot(0)
        old.getJSONObject("projections").getJSONObject("values").put("droyTurnBoundary", boundary(0, null))
        reducer.accept(old)
        assertTrue(reducer.running)
        reducer.updateProjection("droyTurnBoundary", boundary(1, null), 3)
        assertFalse(reducer.running)
        reducer.accept(snapshot(3, turn(1, 1, true), message(2, "reply", "assistant/message"), turn(3, 1, false)))
        reducer.accept(turn(4, 2, true))
        assertTrue(reducer.running)
    }

    @Test fun reasoningNeverBecomesChatTextAndOriginalRecordsStayIntact() {
        val cases = listOf("Ordinary reasoning", "普通思考正文", "\n  Original text  \n", "", "<", "<sig",
            "<signature>metadata</sign", "<signature>metadata</signature>\r\nThought",
            "Text <signature>keep</signature>", "<note>Original text</note>")
        for (raw in cases) {
            val reducer = NativeSessionReducer()
            val record = reasoningMessage(1, raw)
            val original = record.toString()
            assertTrue(reducer.accept(snapshot(1, record)))
            assertTrue("Reasoning must not create a reply", reducer.messages().isEmpty())
            assertEquals("Projection must leave the original event intact", original, record.toString())
            val mixed = reasoningMessage(1, raw, "Final answer")
            val mixedOriginal = mixed.toString()
            assertTrue(reducer.accept(snapshot(1, mixed)))
            assertEquals("Final answer", reducer.messages().single().text)
            assertEquals(mixedOriginal, mixed.toString())
        }
        val literal = "<signature>ordinary answer</signature>\n<note>Quoted reasoning</note>"
        val reducer = NativeSessionReducer()
        reducer.accept(snapshot(1, message(1, literal, "assistant/message")))
        assertEquals("Ordinary text is preserved literally", literal, reducer.messages().single().text)
    }

    @Test fun toolStepsAndOlderPagesOnlyPublishExplicitDialogueAndDeliverables() {
        val steps = (1..3).map { step -> reasoningMessage(step, "Internal step $step",
            if (step == 1) "I will search for bread." else null).apply {
            val data = getJSONObject("event").getJSONObject("data").put("step", step)
            data.getJSONObject("message").getJSONArray("content").put(JSONObject().put("type", "tool-call")
                .put("id", "call-$step").put("name", "android_observe").put("arguments", "{}"))
        } }
        val final = reasoningMessage(4, "Internal final plan", "Here are the recommendations.")
        val reducer = NativeSessionReducer()
        reducer.accept(snapshot(4, final))
        reducer.prepend(JSONObject().put("hasMore", false).put("records", JSONArray(steps)))
        assertEquals(listOf("I will search for bread.", "Here are the recommendations."), reducer.messages().map { it.text })
        val delivered = reasoningMessage(5, "Internal attachment plan").apply {
            getJSONObject("event").getJSONObject("data").getJSONObject("message").getJSONArray("content")
                .put(JSONObject().put("type", "image").put("attachment", JSONObject().put("attachmentId", "fixture-deliverable")
                    .put("mediaType", "image/png").put("bytes", 4)))
        }
        assertTrue(reducer.accept(delivered))
        assertEquals("", reducer.messages().last().text)
        assertEquals("fixture-deliverable", reducer.messages().last().attachments.single().attachmentId)
    }

    @Test fun liveReasoningStaysHiddenWhileTextStreamsAndCommitKeepsRawEvent() {
        val reducer = NativeSessionReducer()
        reducer.accept(snapshot(0))
        reducer.accept(live(JSONObject().put("type", "start").put("attemptId", "signature-a")
            .put("turn", 1).put("step", 1).put("startedAfterSeq", 0)))
        val deltas = listOf("<", "signature>fixture.metadata", "</signature>\r\n", "Internal thought")
        deltas.forEachIndexed { index, text ->
            val chunk = live(JSONObject().put("type", "chunk").put("attemptId", "signature-a").put("index", index)
                .put("chunk", JSONObject().put("type", "reasoning-delta").put("index", 0).put("text", text)))
            val rawFrame = chunk.toString()
            assertTrue(reducer.accept(chunk))
            assertTrue(reducer.messages().isEmpty())
            assertEquals(rawFrame, chunk.toString())
        }
        assertTrue(reducer.accept(live(JSONObject().put("type", "chunk").put("attemptId", "signature-a").put("index", 4)
            .put("chunk", JSONObject().put("type", "text-delta").put("index", 1).put("text", "Final answer")))))
        assertEquals("Final answer", reducer.messages().single().text)
        assertTrue(reducer.messages().single().streaming)
        assertTrue(reducer.accept(live(JSONObject().put("type", "chunk").put("attemptId", "signature-a").put("index", 5)
            .put("chunk", JSONObject().put("type", "reasoning-delta").put("index", 2).put("text", "More internal thought")))))
        assertEquals("Final answer", reducer.messages().single().text)
        val committed = reasoningMessage(1, deltas.joinToString(""), "Final answer")
        val original = committed.toString()
        assertTrue(reducer.accept(committed))
        assertEquals("Final answer", reducer.messages().single().text)
        assertFalse(reducer.messages().single().streaming)
        assertEquals(original, committed.toString())
        assertTrue(reducer.accept(snapshot(1, committed)))
        assertEquals("Final answer", reducer.messages().single().text)
    }

    @Test fun reconnectBaselineSeparatesReasoningChunksFromTextChunks() {
        val reducer = NativeSessionReducer()
        val baseline = snapshot(0).put("assistantStream", JSONObject().put("revision", 1).put("activeAttempt", JSONObject()
            .put("attemptId", "signature-a").put("turn", 1).put("step", 1).put("startedAfterSeq", 0).put("nextIndex", 1)
            .put("stream", JSONArray().put(JSONObject().put("type", "reasoning-chunks").put("index", 0).put("time0", 100)
                .put("texts", JSONArray().put("<sig").put("nature>metadata</signature>\nInternal reasoning")).put("dt", JSONArray().put(1))))))
        val original = baseline.toString()
        assertTrue(reducer.accept(baseline))
        assertTrue(reducer.messages().isEmpty())
        assertTrue(reducer.accept(live(JSONObject().put("type", "chunk").put("attemptId", "signature-a").put("index", 1)
            .put("chunk", JSONObject().put("type", "reasoning-delta").put("index", 0).put("text", "恢复后的思考")))))
        assertTrue(reducer.messages().isEmpty())
        assertEquals(original, baseline.toString())
        assertTrue(reducer.accept(live(JSONObject().put("type", "chunk").put("attemptId", "signature-a").put("index", 2)
            .put("chunk", JSONObject().put("type", "text-delta").put("index", 1).put("text", "恢复后的正文")))))
        assertEquals("恢复后的正文", reducer.messages().single().text)
        assertTrue(reducer.accept(baseline))
        assertTrue("Reconnection replaces the former live text", reducer.messages().isEmpty())
        baseline.getJSONObject("assistantStream").getJSONObject("activeAttempt").getJSONArray("stream")
            .put(JSONObject().put("type", "text-chunks").put("index", 1).put("time0", 101)
                .put("texts", JSONArray().put("Reconnected ").put("answer")).put("dt", JSONArray().put(1)))
        assertTrue(reducer.accept(baseline))
        assertEquals("Reconnected answer", reducer.messages().single().text)
    }

    @Test fun completedBlocksCannotPromoteReasoningToReplyText() {
        val reducer = NativeSessionReducer()
        reducer.accept(snapshot(0))
        reducer.accept(live(JSONObject().put("type", "start").put("attemptId", "blocks")
            .put("turn", 1).put("step", 1).put("startedAfterSeq", 0)))
        val chunks = listOf(
            JSONObject().put("type", "block-start").put("index", 0).put("blockType", "reasoning"),
            JSONObject().put("type", "reasoning-delta").put("index", 0).put("text", "Internal reasoning"),
            JSONObject().put("type", "block-end").put("index", 0)
                .put("block", JSONObject().put("type", "reasoning").put("text", "Internal reasoning")),
            JSONObject().put("type", "block-end").put("index", 1)
                .put("block", JSONObject().put("type", "text").put("text", "Answer")),
        )
        chunks.forEachIndexed { index, chunk ->
            assertTrue(reducer.accept(live(JSONObject().put("type", "chunk").put("attemptId", "blocks")
                .put("index", index).put("chunk", chunk))))
            if (index < 3) assertTrue(reducer.messages().isEmpty())
            else assertEquals("Answer", reducer.messages().single().text)
        }
    }

    private fun reasoningMessage(seq: Int, reasoning: String, answer: String? = null): JSONObject =
        message(seq, "", "assistant/message").apply {
            getJSONObject("event").getJSONObject("data").getJSONObject("message").put("content", JSONArray()
                .put(JSONObject().put("type", "reasoning").put("text", reasoning)).apply {
                    if (answer != null) put(JSONObject().put("type", "text").put("text", answer))
                })
        }

}
