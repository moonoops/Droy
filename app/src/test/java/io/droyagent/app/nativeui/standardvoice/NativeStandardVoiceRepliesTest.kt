package io.droyagent.app.nativeui.standardvoice

import io.droyagent.app.nativeui.data.NativeApiException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeStandardVoiceRepliesTest {
    private class Wire {
        val model = NativeStandardVoiceReplies("session").apply { register("voice-A"); register("voice-B") }
        var seq = 0L; var revision = 0L; var step = 1; var attempt = 0
        init { baseline(); event("turn/start", JSONObject().put("turn", 1)); newStep() }
        fun baseline(records: JSONArray = JSONArray()): List<NativeStandardVoiceReplyEvent> = model.accept(JSONObject()
            .put("type", "snapshot").put("header", JSONObject().put("id", "session")).put("cursor", seq).put("records", records)
            .put("assistantStream", JSONObject().put("revision", revision)))
        fun event(type: String, data: JSONObject, surface: String = "append"): List<NativeStandardVoiceReplyEvent> = model.accept(JSONObject()
            .put("type", "event").put("event", JSONObject().put("type", type).put("seq", ++seq).put("data", data).put("surfaceOp", surface)))
        fun newStep() { event("step/start", JSONObject().put("turn", 1).put("step", step)) }
        fun user(nonce: String?, kind: String = "user") { event("user/message", JSONObject().put("source", JSONObject().put("kind", kind).apply {
            nonce?.let { put("rpcId", it) }
        })) }
        fun frame(value: JSONObject) = model.accept(JSONObject().put("type", "assistant-stream").put("frame", value.put("revision", ++revision)))
        fun start() { attempt++; frame(JSONObject().put("type", "start").put("attemptId", "a$attempt").put("turn", 1).put("step", step).put("startedAfterSeq", seq)) }
        fun settle(type: String = "assistant/message", interrupted: Boolean = false, surface: String = "append"): List<NativeStandardVoiceReplyEvent> {
            val blocks = JSONArray().put(JSONObject().put("type", "reasoning").put("text", "private thinking"))
                .put(JSONObject().put("type", "text").put("text", "Actual reply。"))
                .put(JSONObject().put("type", "tool-call").put("text", "private arguments"))
            assertTrue(event(type, JSONObject().put("turn", 1).put("step", step).put("interrupted", interrupted)
                .put("message", JSONObject().put("content", blocks)), surface).isEmpty())
            return frame(JSONObject().put("type", "end").put("attemptId", "a$attempt").put("index", 0)
                .put("outcome", JSONObject().put("kind", "committed").put("seq", seq).put("eventType", type)))
        }
    }

    @Test fun onlySettledPlainTextFromTheOwnedInputBatchIsPublishedOnce() {
        val f = Wire(); f.user("voice-A"); f.start()
        val events = f.settle()
        assertEquals("voice-A", (events[0] as NativeStandardVoiceReplyEvent.Bound).rpcId)
        assertEquals("Actual reply。", (events[1] as NativeStandardVoiceReplyEvent.Text).text)
        assertTrue((events[1] as NativeStandardVoiceReplyEvent.Text).final)
    }

    @Test fun actualSdkEnvironmentSkillCatalogAndTimeSnapshotsDoNotReplaceTheHumanRpcOwner() {
        val f = Wire(); f.user("voice-A")
        for (source in listOf("runtime-context", "skill-catalog", "time-context")) f.user(null, source)
        f.start(); val events = f.settle()
        assertEquals("voice-A", (events.first() as NativeStandardVoiceReplyEvent.Bound).rpcId)
        assertEquals("Actual reply。", (events.last() as NativeStandardVoiceReplyEvent.Text).text)
    }

    @Test fun aLaterKeyboardStepInTheSameTurnDoesNotInheritVoiceOwnership() {
        val f = Wire(); f.user("voice-A"); f.start(); assertEquals(2, f.settle().size)
        f.step++; f.newStep(); f.user("keyboard"); f.start(); assertTrue(f.settle().isEmpty())
    }

    @Test fun mixedInputsOrMissingOrUnknownSourcesCannotGrantVoiceOwnership() {
        for (kind in listOf("keyboard", "missing", "unknown", "two-voice")) {
            val f = Wire(); f.user("voice-A")
            when (kind) { "keyboard" -> f.user("keyboard"); "missing" -> f.user(null); "unknown" -> f.user("voice-A", "untrusted"); else -> f.user("voice-B") }
            f.start(); assertTrue(kind, f.settle().isEmpty())
        }
    }

    @Test fun retryDoesNotReadTheFailedAttemptButRetainsTheOriginalInputBatch() {
        val f = Wire(); f.user("voice-A"); f.start(); assertTrue(f.settle("assistant/attempt").isEmpty())
        f.start(); assertEquals(2, f.settle().size)
    }

    @Test fun toolContinuationCanInheritButANewVoiceStepGetsItsOwnReplyKey() {
        val f = Wire(); f.user("voice-A"); f.start(); f.settle()
        f.step++; f.newStep(); f.user(null, "runtime-context"); f.start()
        assertEquals("voice-A", (f.settle().first() as NativeStandardVoiceReplyEvent.Bound).rpcId)
        f.step++; f.newStep(); f.user("voice-B"); f.start()
        val bound = f.settle().first() as NativeStandardVoiceReplyEvent.Bound
        assertEquals("voice-B", bound.rpcId); assertEquals("1/3", bound.turnId)
    }

    @Test fun interruptedReplacedAndAbandonedResponsesNeverBecomeReadout() {
        for (mode in listOf("interrupted", "replace", "abandoned")) {
            val f = Wire(); f.user("voice-A"); f.start()
            val result = when (mode) {
                "interrupted" -> f.settle(interrupted = true)
                "replace" -> f.settle(surface = "replace")
                else -> f.frame(JSONObject().put("type", "end").put("attemptId", "a1").put("index", 0).put("outcome", JSONObject().put("kind", "abandoned")))
            }
            assertTrue(mode, result.isEmpty())
        }
    }

    @Test fun reconnectBaselineNeverReadsHistoryOrGivesItsOldInputOwnershipToANewAttempt() {
        val f = Wire(); f.user("voice-A"); f.start(); f.settle()
        val records = JSONArray().put(JSONObject().put("event", JSONObject().put("type", "step/start").put("data", JSONObject().put("turn", 1).put("step", 1))))
        assertTrue(f.baseline(records).isEmpty()); f.start(); assertTrue(f.settle().isEmpty())
    }

    @Test fun aMissingTransientFrameRequiresARebaseline() {
        val f = Wire(); f.user("voice-A"); f.start()
        try {
            f.frame(JSONObject().put("type", "chunk").put("attemptId", "a1").put("index", 1).put("chunk", JSONObject()))
            fail("A gap must not publish any readout")
        } catch (failure: NativeApiException) { assertEquals("stream_gap", failure.code) }
    }
}
