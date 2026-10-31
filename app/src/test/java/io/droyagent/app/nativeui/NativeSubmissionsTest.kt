package io.droyagent.app.nativeui

import io.droyagent.app.nativeui.data.NativeSubmissions
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class NativeSubmissionsTest {
    private fun record(status: String) = JSONObject().put("sessionId", "session-a")
        .put("requestId", "request-a").put("text", "Hello").put("status", status)

    @Test fun voiceDurationAndIdentitySurvivePendingAcceptanceAndHistoryProjection() {
        val audio = record("accepted").put("voiceDurationMs", 3_200L).put("attachments", JSONArray().put(JSONObject()
            .put("importId", "11111111-1111-4111-8111-111111111111").put("id", "22222222-2222-4222-8222-222222222222")
            .put("name", "Audio-20261030-120000.wav").put("mediaType", "audio/wav").put("bytes", 102444)))
        val ledger = NativeSubmissions(); ledger.accept(audio)
        val pending = ledger.messages("session-a") { false }.single()
        assertEquals("request-a", pending.requestId)
        assertEquals(3_200L, pending.attachments.single().durationMs)
        val persisted = io.droyagent.app.nativeui.data.NativeMessage("journal-a", "user", "", requestId = "request-a",
            attachments = listOf(io.droyagent.app.nativeui.data.NativeMessageAttachment("file", "file-a", "Audio-20261030-120000.wav", bytes = 102444)))
        val displayed = NativeSubmissions.withVoiceDuration(persisted, NativeSubmissions.voiceDuration(JSONObject(audio.toString())))
        assertEquals("audio", displayed.attachments.single().kind)
        assertEquals(3_200L, displayed.attachments.single().durationMs)
        assertEquals(persisted, NativeSubmissions.withVoiceDuration(persisted, null))
        assertEquals(persisted, NativeSubmissions.withVoiceDuration(persisted, 60_001))
        assertNull(NativeSubmissions.voiceDuration(audio.put("voiceDurationMs", -1)))
    }

    @Test fun trustedAcceptanceNeverBlocksLaterSendEvenWhenMessageLeftHistoryWindow() {
        val accepted = record("accepted")
        assertFalse(NativeSubmissions.blocksNewSubmission(accepted))
        val ledger = NativeSubmissions()
        ledger.accept(accepted)
        assertEquals(1, ledger.messages("session-a") { false }.size)
        ledger.rebaseline("session-a")
        assertTrue(ledger.messages("session-a") { false }.isEmpty())
        assertFalse(NativeSubmissions.blocksNewSubmission(accepted))
    }

    @Test fun uncertainAdmissionKeepsItsOriginalRequestIdentityForExplicitRetry() {
        val unresolved = record("uncertain")
        assertTrue(NativeSubmissions.blocksNewSubmission(unresolved))
        assertEquals("request-a", unresolved.getString("requestId"))
    }

    @Test fun durableOrInboxCorrelationRetiresOnlyItsOptimisticOccurrence() {
        val ledger = NativeSubmissions()
        ledger.accept(record("accepted"))
        assertTrue(ledger.messages("session-a") { it == "request-a" }.isEmpty())
        assertTrue(ledger.messages("session-a") { false }.isEmpty())
    }

    @Test fun uncertainCreationRequiresTheExactOriginalSessionAndWorkspace() {
        val pending = record("uncertain").put("workspaceId", "space-a")
        val list = JSONObject().put("items", JSONArray().put(JSONObject().put("sessionId", "session-a")))
            .put("managedSessionIds", JSONArray())
        val spaces = JSONObject().put("archivedSessionIds", JSONArray()).put("managedWorkspaceIds", JSONArray())
            .put("items", JSONArray().put(JSONObject().put("workspaceId", "space-a").put("sessionIds", JSONArray().put("session-a"))))
        assertTrue(NativeSubmissions.confirmsCreatedSession(pending, list, spaces))
        spaces.getJSONArray("items").getJSONObject(0).put("sessionIds", JSONArray().put("different-session"))
        assertFalse(NativeSubmissions.confirmsCreatedSession(pending, list, spaces))
        assertEquals("session-a", pending.getString("sessionId"))
        assertEquals("request-a", pending.getString("requestId"))
        spaces.getJSONArray("items").getJSONObject(0).put("sessionIds", JSONArray().put("session-a"))
        spaces.put("archivedSessionIds", JSONArray().put("session-a"))
        assertFalse(NativeSubmissions.confirmsCreatedSession(pending, list, spaces))
        spaces.put("archivedSessionIds", JSONArray())
        list.put("managedSessionIds", JSONArray().put("session-a"))
        assertFalse(NativeSubmissions.confirmsCreatedSession(pending, list, spaces))
    }

    @Test fun absentCreationIsNeverConfirmedAndAbandonmentDoesNotCountAsAnAcceptance() {
        val pending = record("uncertain")
        val list = JSONObject().put("items", JSONArray()).put("managedSessionIds", JSONArray())
        val spaces = JSONObject().put("items", JSONArray()).put("archivedSessionIds", JSONArray()).put("managedWorkspaceIds", JSONArray())
        assertFalse(NativeSubmissions.confirmsCreatedSession(pending, list, spaces))
        assertFalse(NativeSubmissions.blocksNewSubmission(pending.put("status", "abandoned")))
        assertEquals("request-a", pending.getString("requestId"))
    }

    @Test fun subagentCannotConfirmAnUncertainTopLevelCreationWhileAnOrdinaryForkCan() {
        val row = JSONObject().put("sessionId", "session-a").put("parentSessionId", "parent-a")
        val list = JSONObject().put("items", JSONArray().put(row)).put("managedSessionIds", JSONArray())
        val spaces = JSONObject().put("items", JSONArray()).put("archivedSessionIds", JSONArray()).put("managedWorkspaceIds", JSONArray())
        assertTrue(NativeSubmissions.confirmsCreatedSession(record("uncertain"), list, spaces))
        row.put("origin", "subagent")
        assertFalse(NativeSubmissions.confirmsCreatedSession(record("uncertain"), list, spaces))
    }

    @Test fun acceptedAttachmentOnlySubmissionKeepsItsOptimisticIdentityAndFileMetadata() {
        val local = io.droyagent.app.nativeui.media.NativeLocalAttachment(java.util.UUID.randomUUID().toString(),
            java.util.UUID.randomUUID().toString(), "fixture.txt", "text/plain", 4)
        val value = record("accepted").put("text", "").put("attachments", JSONArray().put(local.json()))
        val ledger = NativeSubmissions(); ledger.accept(value)
        val row = ledger.messages("session-a") { false }.single()
        assertEquals("request:request-a", row.id)
        assertEquals("", row.text)
        assertEquals(local, row.attachments.single().local)
        assertEquals("file", row.attachments.single().kind)
    }
}
