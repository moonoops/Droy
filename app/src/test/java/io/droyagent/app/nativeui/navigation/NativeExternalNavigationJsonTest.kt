package io.droyagent.app.nativeui.navigation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeExternalNavigationJsonTest {
    @Test fun aLegacyWorkLinkKeepsItsSourceCorrelatorsWhileOpeningTheCompanionChat() {
        val row = JSONObject().put("kind", "companion-chat").put("companionId", "kaka")
            .put("chatSessionId", "current-chat").put("sourceSessionId", "internal-work")
            .put("sourceTaskId", "internal-task").put("petRequestId", "old-question")
        val result = NativeExternalNavigationJson.resolved(row) as NativeExternalResolvedTarget.CompanionChat
        assertEquals("current-chat", result.chatSessionId)
        assertEquals("internal-work", result.sourceSessionId)
        assertEquals("internal-task", result.sourceTaskId)
        assertEquals("old-question", result.petRequestId)
        assertEquals(NativeExternalNavigationTarget.CompanionChat("kaka"), result.surface)
    }

    private fun session(owner: String = "workbench") = JSONObject().put("kind", "engine-session").put("session", JSONObject()
        .put("id", "child").put("workspaceId", JSONObject.NULL).put("owner", owner).put("readOnly", owner != "workbench")
        .put("archived", false).put("address", JSONObject().put("kind", "session").put("sessionId", "child")))
    @Test fun ordinaryForkDoesNotBecomeASubagentWithoutATypedAddress() {
        val value = NativeExternalNavigationJson.resolved(session()) as NativeExternalResolvedTarget.EngineSession
        assertEquals(NativeExternalSessionOwner.WORKBENCH, value.session.owner); assertNull(value.session.parentSessionId)
    }
    @Test fun subagentKeepsItsExactParentAndReadOnlyOwnership() {
        val row = session("subagent")
        row.getJSONObject("session").put("address", JSONObject().put("kind", "subagent").put("parentSessionId", "parent").put("childSessionId", "child").put("mode", "unknown"))
        val value = NativeExternalNavigationJson.resolved(row) as NativeExternalResolvedTarget.EngineSession
        assertEquals("parent", value.session.parentSessionId); assertTrue(value.session.readOnly)
    }
    @Test fun childWithoutTheTypedParentCannotOpenAsAnOrdinarySession() {
        try { NativeExternalNavigationJson.resolved(session("subagent")); fail("untyped_child") } catch (_: IllegalArgumentException) { }
    }
    @Test fun mismatchedTypedChildIdentityIsRejected() {
        val row = session("subagent")
        row.getJSONObject("session").put("address", JSONObject().put("kind", "subagent").put("parentSessionId", "parent").put("childSessionId", "other").put("mode", "unknown"))
        try { NativeExternalNavigationJson.resolved(row); fail("wrong_child") } catch (_: IllegalArgumentException) { }
    }
    @Test fun anEmptyCompanionChatDoesNotInventASession() {
        val result = NativeExternalNavigationJson.resolved(JSONObject().put("kind", "companion-chat").put("companionId", "kaka").put("chatSessionId", JSONObject.NULL)) as NativeExternalResolvedTarget.CompanionChat
        assertNull(result.chatSessionId); assertEquals("kaka", result.companionId)
    }
    @Test fun petRequestRemainsOpaqueAlongsideTheActualCompanionTask() {
        val row = session("companion").put("kind", "companion-task").put("petRequestId", "pet-question")
        row.getJSONObject("session").put("companionId", "kaka").put("taskId", "message-task")
        val result = NativeExternalNavigationJson.resolved(row) as NativeExternalResolvedTarget.CompanionTask
        assertEquals("message-task", result.session.taskId); assertEquals("pet-question", result.petRequestId)
        assertTrue(result.session.readOnly)
    }
}
