package io.droyagent.app.nativeui.standardvoice

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeStandardVoiceBindingsTest {
    @Test fun tokenCoversTextAndAttachmentBoundaries() {
        assertEquals(standardVoiceDraftToken("draft", "[]"), standardVoiceDraftToken("draft", "[]"))
        assertNotEquals(standardVoiceDraftToken("a", "bc"), standardVoiceDraftToken("ab", "c"))
        assertNotEquals(standardVoiceDraftToken("draft", "[]"), standardVoiceDraftToken("draft", "[{}]"))
        assertNotEquals(standardVoiceDraftToken("draft", "[]"), standardVoiceDraftToken("草稿", "[]"))
    }

    @Test fun onlyActualUserSourceCanProveAReceipt() {
        fun page(kind: String, source: String) = JSONObject("""{"records":[{"event":{"type":"$kind","data":{"source":$source}}}]}""")
        assertTrue(standardVoicePageContains(page("user/message", """{"kind":"user","rpcId":"nonce"}"""), "nonce"))
        assertFalse(standardVoicePageContains(page("assistant/message", """{"kind":"user","rpcId":"nonce"}"""), "nonce"))
        assertFalse(standardVoicePageContains(page("user/message", """{"kind":"runtime-context","rpcId":"nonce"}"""), "nonce"))
        assertFalse(standardVoicePageContains(page("user/message", """{"kind":"user","rpcId":"other"}"""), "nonce"))
        assertFalse(standardVoicePageContains(JSONObject("""{"hasMore":true,"records":[]}"""), "nonce"))
    }

    @Test fun bothDurableQueueLanesCanProveAcceptance() {
        for (lane in listOf("next-turn", "next-step")) {
            val values = JSONObject("""{"inbox":{"$lane":[{"source":{"kind":"user","rpcId":"n"}}]}}""")
            assertTrue(standardVoiceInboxContains(values, "n"))
            assertFalse(standardVoiceInboxContains(values, "missing"))
        }
        assertFalse(standardVoiceInboxContains(null, "n"))
    }
}
