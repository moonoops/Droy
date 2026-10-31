package io.droyagent.app.nativeui.workspaces

import io.droyagent.app.nativeui.data.NativeApiException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeWorkspaceJsonTest {
    @Test fun ownershipMetadataRemovesCompanionRowsWithoutHidingReadOnlyUserChildren() {
        val input = JSONObject("""{"workspaces":[
            {"id":"mine","path":"/fixture/mine","title":"Mine","sessionIds":["child"],"readOnly":false},
            {"id":"friend","path":"/fixture/friend","title":"Friend","sessionIds":["private"],"readOnly":true}],
            "sessions":[{"id":"child","title":"Child","running":false,"blank":false,"readOnly":true,"workspaceId":"mine","needsMigration":false},
            {"id":"private","title":"Internal","running":true,"blank":false,"readOnly":true,"workspaceId":"friend","needsMigration":false}],
            "managedWorkspaceIds":["friend"],"managedSessionIds":["private","old-chat"],"defaultWorkspaceId":"mine",
            "pinnedSessionIds":["child","private"],"archivedSessionIds":["old-chat"],"directoryBrowsingAvailable":false}""")
        val result = NativeWorkspaceJson.catalog(input)
        assertEquals(listOf("mine"), result.workspaces.map { it.id })
        assertEquals(listOf("child"), result.sessions.map { it.id })
        assertTrue(result.sessions.single().readOnly)
        assertEquals(setOf("friend"), result.managedWorkspaceIds)
        assertEquals(setOf("private", "old-chat"), result.managedSessionIds)
        assertEquals(listOf("child"), result.pinnedSessionIds)
        assertTrue(result.archivedSessionIds.isEmpty())
        input.put("managedSessionIds", "private")
        try { NativeWorkspaceJson.catalog(input); fail("Expected malformed ownership metadata") }
        catch (error: NativeApiException) { assertEquals("malformed_response", error.code) }
    }

    @Test fun catalogKeepsLiteralNullNamesAndOpaqueIdentitiesDistinctFromJsonNull() {
        val input = JSONObject("""{"workspaces":[{"id":"null","path":"/fixture","title":"null","sessionIds":["child"],"readOnly":false}],
            "sessions":[{"id":"child","title":"null","running":false,"blank":false,"readOnly":false,"workspaceId":"null","needsMigration":false}],
            "defaultWorkspaceId":"null","pinnedSessionIds":[],"archivedSessionIds":[],"directoryBrowsingAvailable":false}""")
        val literal = NativeWorkspaceJson.catalog(input)
        assertEquals("null", literal.defaultWorkspaceId)
        assertEquals("null", literal.workspaces.single().title)
        assertEquals("null", literal.sessions.single().title)
        assertEquals("null", literal.sessions.single().workspaceId)
        input.getJSONArray("sessions").getJSONObject(0).put("title", JSONObject.NULL).put("workspaceId", JSONObject.NULL)
        val absent = NativeWorkspaceJson.catalog(input).sessions.single()
        assertNull(absent.title); assertNull(absent.workspaceId)
    }
    @Test fun receiptsKeepKnownChildAndPartialRenameWithoutInventingSuccessFromUnknown() {
        val known = NativeWorkspaceJson.receipt(JSONObject("""{"requestId":"a","kind":"session.fork","state":"complete","resultSessionId":"child","renameUnconfirmed":true}"""))
        assertEquals("child", known.resultSessionId); assertTrue(known.renameUnconfirmed)
        val unknown = NativeWorkspaceJson.receipt(JSONObject("""{"requestId":"a","kind":"session.fork","state":"unknown"}"""))
        assertEquals("unknown", unknown.state); assertNull(unknown.resultSessionId)
    }
    @Test fun jsonDoesNotCoerceBooleanFlagsOrUnknownReceiptStates() {
        for (json in listOf("""{"requestId":"a","kind":"session.fork","state":"pending"}""",
            """{"requestId":"a","kind":"session.fork","state":"complete","renameUnconfirmed":"true"}""")) {
            try { NativeWorkspaceJson.receipt(JSONObject(json)); fail("Expected strict response rejection") } catch (error: NativeApiException) { assertEquals("malformed_response", error.code) }
        }
    }
    @Test fun operationUsesTheFrozenRequestBodyAndNoUnsupportedForkFlag() {
        val value = NativeWorkspaceJson.operation(NativeWorkspaceOperation("nonce", "session.fork", "{\"sessionId\":\"original\",\"allowMigration\":false}"))
        assertEquals("original", value.getJSONObject("args").getString("sessionId"))
        assertFalse(value.getJSONObject("args").has("increaseTitle")); assertFalse(value.getJSONObject("args").has("atSeq"))
    }
}
