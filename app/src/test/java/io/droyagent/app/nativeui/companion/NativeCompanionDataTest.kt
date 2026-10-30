package io.droyagent.app.nativeui.companion

import io.droyagent.app.nativeui.companion.data.*
import io.droyagent.app.nativeui.data.NativeHttpErrors
import io.droyagent.app.nativeui.data.NativeLocalTransport
import io.droyagent.app.nativeui.data.NativeApiException
import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeCompanionDataTest {
    @Test fun consumedDraftSurvivesOutboxRestoreSoCompletionCannotClearNewIdenticalText() {
        val prepared = NativeCompanionSubmission.prepare("kaka", NativeCompanionDraft("Hello"), "2026-10-30T00:00:00Z")
        val consumed = prepared.copy(draftConsumed = true)
        val restored = NativeCompanionSubmission.restore(JSONObject(consumed.json().toString()))
        assertTrue(restored.draftConsumed)
        assertEquals(prepared.payload, restored.payload)
        assertEquals(prepared.clientId, restored.clientId)
        assertEquals(prepared.draft, restored.draft)
        val legacy = prepared.json().apply { remove("draftConsumed") }
        assertFalse(NativeCompanionSubmission.restore(legacy).draftConsumed)
    }

    @Test fun anEffectiveGlobalModelDoesNotBecomeAnExplicitCompanionOverride() {
        val wire = JSONObject().put("id", "kaka").put("name", "Kaka").put("avatarUrl", "/droy/companions/assets/kaka.png")
            .put("settings", JSONObject().put("proactiveEnabled", false).put("memoryEnabled", true).put("allowProfanity", false))
            .put("model", JSONObject().put("provider", "fixture").put("model", "global"))
            .put("modelInherited", true)
        assertTrue(NativeCompanionJson.profile(wire).modelInherited)
        assertEquals("global", NativeCompanionJson.profile(wire).model!!.id)
        wire.put("modelInherited", false)
        assertFalse(NativeCompanionJson.profile(wire).modelInherited)
    }

    private fun wireMessage(id: String, text: String = id): JSONObject = JSONObject().put("id", id).put("role", "assistant")
        .put("type", "text").put("text", text).put("status", "sent").put("createdAt", "2026-10-29T00:00:00.000Z")
    private fun message(id: String) = NativeCompanionJson.message(wireMessage(id))
    private fun page(vararg ids: String, before: String? = null, more: Boolean = before != null) = NativeCompanionPage(
        ids.map(::message), more, before, "seq:100", NativeCompanionStatus())

    @Test fun companionMessageKeepsMediaQuotesAndTaskMetadata() {
        val value = wireMessage("m1").put("type", "task").put("clientId", "stable-client")
            .put("attachments", JSONArray().put(JSONObject().put("id", "image-a").put("name", "image.png").put("mediaType", "image/png")
                .put("bytes", 12).put("url", "/droy/companions/kaka/attachments/image-a").put("width", 30).put("height", 20)))
            .put("voice", JSONObject().put("attachmentId", "audio-a").put("durationMs", 2500).put("transcriptionState", "ready")
                .put("transcript", "Original voice").put("futureField", 7))
            .put("stickerId", "test-sticker-ok").put("replyTo", "original").put("quoteReply", true)
            .put("replyPreview", JSONObject().put("id", "original").put("text", "Quoted original").put("type", "voice"))
            .put("task", JSONObject().put("id", "task-a").put("sessionId", "session-a").put("state", "cancelling")
                .put("title", "Local work").put("futureTaskField", true))
        val parsed = NativeCompanionJson.message(value)
        assertEquals(30, parsed.attachments.single().width)
        assertEquals("Original voice", parsed.voice!!.transcript)
        assertEquals(7, JSONObject(parsed.voice!!.metadataJson).getInt("futureField"))
        assertEquals("test-sticker-ok", parsed.stickerId)
        assertEquals("Quoted original", parsed.replyPreview!!.text)
        assertEquals("cancelling", parsed.task!!.state)
        assertTrue(JSONObject(parsed.task!!.metadataJson).getBoolean("futureTaskField"))
        assertEquals("stable-client", parsed.clientId)
    }

    @Test fun persistedUnresolvedSubmissionReusesIdentityContentAndAttachmentOwnership() {
        val draft = NativeCompanionDraft("A caption", listOf(NativeCompanionAttachment("image-a", "image.png", "image/png", 12,
            "/droy/companions/kaka/attachments/image-a")), NativeCompanionReplyPreview("quoted", "Quote", "text"),
            NativeCompanionVoice("audio-a", durationMs = 1000, transcriptionState = "ready", metadataJson = "{\"retained\":true}"))
        val first = NativeCompanionSubmission.prepare("kaka", draft, "2026-10-29T00:00:00Z")
        val restored = NativeCompanionSubmission.restore(JSONObject(first.copy(uncertain = true).json().toString()))
        assertEquals(first.clientId, restored.clientId)
        assertEquals(first.payload, restored.payload)
        assertTrue(NativeCompanionJson.sameSubmissionContent(draft, restored.draft))
        assertFalse(NativeCompanionJson.sameSubmissionContent(draft.copy(text = "Edited caption"), restored.draft))
        val payload = JSONObject(restored.payload)
        assertEquals("image-a", payload.getJSONArray("attachments").getJSONObject(0).getString("id"))
        assertFalse(payload.getJSONArray("attachments").getJSONObject(0).has("url"))
        assertEquals("quoted", payload.getString("replyTo"))
        assertTrue(payload.getJSONObject("voice").getBoolean("retained"))
        assertEquals("failed", restored.optimistic().status)
    }

    @Test fun refreshReplacesWholeLoadedWindowSoOldDeletionsDoNotReappear() {
        val history = NativeCompanionHistory()
        history.replace(listOf(page("m51", "m52", before = "seq:51")))
        history.prepend(page("m1", "m2", more = false))
        assertEquals(listOf("m1", "m2", "m51", "m52"), history.messages.map { it.id })
        assertTrue(history.needsEarlier(page("m52", "m53", before = "seq:52"), history.nextBefore))
        history.replace(listOf(page("m52", "m53", before = "seq:52"), page("m1", "m51", more = false)))
        assertEquals(listOf("m1", "m51", "m52", "m53"), history.messages.map { it.id })
        assertFalse(history.hasMore)
    }

    @Test fun newTailFetchesOnlyUntilPreviouslyLoadedSequenceBoundary() {
        val history = NativeCompanionHistory()
        history.replace(listOf(page("m51", before = "seq:51")))
        assertTrue(history.needsEarlier(page("m60", before = "seq:60"), "seq:51"))
        assertFalse(history.needsEarlier(page("m50", before = "seq:50"), "seq:51"))
        history.prepend(page("m1", "m51", more = false))
        assertEquals(listOf("m1", "m51"), history.messages.map { it.id })
    }

    @Test fun idlePhaseOrAcknowledgementAloneCannotConfirmStop() {
        val old = NativeCompanionJson.status(JSONObject().put("phase", "idle"))
        assertNull(old.chatExecution)
        assertTrue(companionStopPending(true, old.chatExecution))
        assertTrue(companionStopPending(true, NativeCompanionExecution(quiescent = false, cancelRequested = true)))
        assertTrue(companionStopPending(true, NativeCompanionExecution(quiescent = true, cancelRequested = false)))
        assertFalse(companionStopPending(true, NativeCompanionExecution(quiescent = true, cancelRequested = true)))
        assertTrue(companionStopPending(null, NativeCompanionExecution(quiescent = false, cancelRequested = true)))
    }

    @Test fun clearingAnOverrideIsDifferentFromLeavingModelUnchanged() {
        assertFalse(NativeCompanionJson.patch(NativeCompanionProfilePatch(name = "New name")).has("model"))
        val clear = NativeCompanionJson.patch(NativeCompanionProfilePatch(clearModel = true))
        assertTrue(clear.has("model")); assertTrue(clear.isNull("model"))
    }

    @Test fun sharedTransportPreservesCompanionMachineErrorsAndLimitsBinaryReads() {
        assertEquals("COMPANION_ATTACHMENT_SCOPE", NativeHttpErrors.code(400, """{"error":"COMPANION_ATTACHMENT_SCOPE","message":"Fixture"}"""))
        assertArrayEquals(byteArrayOf(1, 2), NativeLocalTransport.boundedBytes(ByteArrayInputStream(byteArrayOf(1, 2)), 2))
        try {
            NativeLocalTransport.boundedBytes(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 2)
            fail("Oversized media must fail without a truncated success")
        } catch (error: NativeApiException) { assertEquals("response_too_large", error.code) }
    }

    @Test fun historyConfirmationCannotBeReversedByALaterPostFailure() {
        val record = NativeCompanionSubmission.prepare("kaka", NativeCompanionDraft("Hello"), "2026-10-29T00:00:00Z")
        val history = NativeCompanionHistory()
        history.upsert(NativeCompanionJson.message(wireMessage(record.clientId).put("role", "user").put("clientId", record.clientId)))
        assertFalse(canMarkCompanionSubmissionUncertain(record, record, history.contains(record.clientId)))
        // Once its pending row was retired, a later smaller window must not resurrect it either.
        assertFalse(canMarkCompanionSubmissionUncertain(record, null, false))
        assertTrue(canMarkCompanionSubmissionUncertain(record, record, false))
        val next = NativeCompanionSubmission.prepare("kaka", NativeCompanionDraft("Next"), "2026-10-29T00:00:01Z")
        assertFalse(canMarkCompanionSubmissionUncertain(record, next, false))
    }
}
