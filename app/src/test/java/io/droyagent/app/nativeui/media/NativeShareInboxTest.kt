package io.droyagent.app.nativeui.media

import io.droyagent.app.nativeui.data.NativeApiException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeShareInboxTest {
    private fun id() = UUID.randomUUID().toString()

    @Test fun distinctSharesStayInFifoOrderAndDuplicateDeliveryNeverMovesTheHead() {
        val first = id(); val second = id()
        val queue = NativeShareInbox.appended(NativeShareInbox.appended(emptyList(), first), second)
        assertEquals(listOf(first, second), NativeShareInbox.appended(queue, first))
        assertEquals(queue, NativeShareInbox.parsePending(JSONArray(queue).toString()))
    }

    @Test fun queueCapacityDoesNotRejectAnAlreadyDurableIdOrEvictOtherShares() {
        val full = List(32) { id() }
        assertEquals(full, NativeShareInbox.appended(full, full.last()))
        assertEquals("native_share_inbox_full", assertThrows(NativeApiException::class.java) { NativeShareInbox.appended(full, id()) }.code)
    }

    @Test fun corruptQueueIsNotReinterpretedAsAnEmptyInbox() {
        val same = id()
        for (raw in listOf("broken", "[1]", "[\"not-a-uuid\"]", JSONArray(listOf(same, same)).toString(), JSONArray(List(33) { id() }).toString())) {
            assertEquals("storage_error", assertThrows(NativeApiException::class.java) { NativeShareInbox.parsePending(raw) }.code)
        }
    }

    @Test fun onlyAnActualUnboundWorkShareMayBeQueuedOrDeletedByAShareDisposition() {
        val share = JSONObject().put("source", "share").put("consumer", "work")
        NativeShareInbox.requireAdmittedShare(share)
        val wrong = listOf(
            JSONObject(share.toString()).put("source", "native-picker"),
            JSONObject(share.toString()).put("source", "native-voice"),
            JSONObject(share.toString()).put("consumer", "companion"),
            JSONObject().put("source", "share"),
            JSONObject(share.toString()).put("targetSessionId", "work-session"),
            JSONObject(share.toString()).put("targetSessionId", JSONObject.NULL),
        )
        wrong.forEach { manifest ->
            assertEquals("native_share_scope", assertThrows(NativeApiException::class.java) { NativeShareInbox.requireAdmittedShare(manifest) }.code)
        }
    }
}
