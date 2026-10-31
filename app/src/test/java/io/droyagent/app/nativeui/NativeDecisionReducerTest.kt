package io.droyagent.app.nativeui

import io.droyagent.app.nativeui.data.NativeDecisionReducer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeDecisionReducerTest {
    private fun approval(id: String) = JSONObject().put("id", id).put("kind", "approval")
        .put("sessionId", "session-a").put("toolName", "bash").put("reason", "Run a command")
    private fun snapshot(vararg decisions: JSONObject) = JSONObject().put("type", "snapshot")
        .put("decisions", JSONArray(decisions))

    @Test fun openingDoesNotAnswerAndAcceptedAnswerRemainsPendingUntilHostRemoval() {
        val reducer = NativeDecisionReducer()
        reducer.accept(snapshot(approval("a")))
        assertFalse(reducer.decisions().single().answerPending)
        reducer.answersAccepted("a")
        assertTrue(reducer.decisions().single().answerPending)
        reducer.accept(snapshot(approval("a")))
        assertTrue(reducer.decisions().single().answerPending)
        reducer.accept(JSONObject().put("type", "remove").put("id", "a").put("reason", "cancelled"))
        assertTrue(reducer.decisions().isEmpty())
    }

    @Test fun reconnectReplacesOnlyCurrentOutstandingQuestionsAndPreservesQuestionIds() {
        val reducer = NativeDecisionReducer()
        reducer.accept(snapshot(approval("old")))
        val question = JSONObject().put("id", "new").put("kind", "question").put("sessionId", "session-b")
            .put("state", "continued").put("questions", JSONArray().put(JSONObject().put("id", "q1")
                .put("question", "Choose a time").put("multiSelect", true).put("options", JSONArray()
                    .put(JSONObject().put("label", "Morning").put("description", "Before noon")))))
        reducer.accept(snapshot(question))
        val actual = reducer.decisions().single()
        assertEquals("new", actual.id)
        assertEquals("q1", actual.questions.single().id)
        assertTrue(actual.questions.single().multiSelect)
        assertEquals("Morning", actual.questions.single().options.single().label)
        assertEquals("session-b", actual.sessionId)
        assertNull(reducer.find("old"))
    }

    @Test fun hostQueuedAnswerAndLocalizedReasonArePreserved() {
        val reducer = NativeDecisionReducer()
        val value = approval("a").put("answerPending", true).put("displayReason", JSONObject().put("zh", "执行命令").put("en", "Run command"))
        reducer.accept(JSONObject().put("type", "upsert").put("decision", value))
        val actual = reducer.decisions().single()
        assertTrue(actual.answerPending)
        assertEquals("执行命令", actual.displayReasonZh)
        assertEquals("Run command", actual.displayReasonEn)
    }
}
