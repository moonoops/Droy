package io.droyagent.app.nativeui.data

import org.json.JSONObject

/** Reconnect snapshots replace outstanding decisions; a displayed card is never consent. */
class NativeDecisionReducer {
    private val outstanding = linkedMapOf<String, NativeDecision>()
    private val acceptedAnswers = mutableSetOf<String>()

    fun accept(frame: JSONObject) {
        when (frame.getString("type")) {
            "snapshot" -> {
                outstanding.clear()
                frame.getJSONArray("decisions").objects().forEach { upsert(it) }
                acceptedAnswers.retainAll(outstanding.keys)
            }
            "upsert" -> upsert(frame.getJSONObject("decision"))
            "remove" -> remove(frame.getString("id"))
            else -> throw NativeApiException("malformed_response")
        }
    }

    fun answersAccepted(id: String) { acceptedAnswers += id }
    fun remove(id: String) { outstanding.remove(id); acceptedAnswers.remove(id) }
    fun decisions(): List<NativeDecision> = outstanding.values.map {
        if (it.id in acceptedAnswers) it.copy(answerPending = true) else it
    }
    fun find(id: String): NativeDecision? = decisions().firstOrNull { it.id == id }
    fun clear() { outstanding.clear(); acceptedAnswers.clear() }

    private fun upsert(value: JSONObject) {
        val questions = value.optJSONArray("questions")?.objects()?.map { question ->
            NativeQuestion(
                id = question.getString("id"), question = question.getString("question"),
                header = question.optionalString("header"), detail = question.optionalString("detail"),
                multiSelect = question.optBoolean("multiSelect"),
                options = question.optJSONArray("options")?.objects()?.map {
                    NativeQuestionOption(it.getString("label"), it.optionalString("description"))
                } ?: emptyList(),
            )
        } ?: emptyList()
        val kind = value.getString("kind")
        require(kind == "approval" || kind == "question") { "invalid_decision_kind" }
        val display = value.optJSONObject("displayReason")
        val decision = NativeDecision(
            id = value.getString("id"), kind = kind, sessionId = value.getString("sessionId"),
            callId = value.optionalString("callId"), toolName = value.optionalString("toolName"),
            reason = value.optionalString("reason"), displayReasonZh = display?.optionalString("zh") ?: display?.optionalString("zh-CN"),
            displayReasonEn = display?.optionalString("en"), questions = questions,
            state = value.optString("state", "open"),
            expiresAt = if (value.has("expiresAt") && !value.isNull("expiresAt")) value.getLong("expiresAt") else null,
            answerPending = value.optBoolean("answerPending"),
            canApprove = value.optBoolean("canApprove"), toolArguments = value.optionalString("toolArguments"),
        )
        outstanding[decision.id] = decision
    }
}

private fun JSONObject.optionalString(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null
