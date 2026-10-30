package io.droyagent.app.nativeui.standardvoice

import org.json.JSONObject

private fun voiceSource(message: JSONObject?, nonce: String): Boolean = message?.optJSONObject("source")?.let {
    it.optString("kind") == "user" && it.opt("rpcId") == nonce
} == true

internal fun standardVoiceInboxContains(values: JSONObject?, nonce: String): Boolean {
    val inbox = values?.optJSONObject("inbox") ?: return false
    return listOf("next-turn", "next-step").any { key ->
        val rows = inbox.optJSONArray(key) ?: return@any false
        (0 until rows.length()).any { voiceSource(rows.optJSONObject(it), nonce) }
    }
}

internal fun standardVoicePageContains(page: JSONObject, nonce: String): Boolean {
    val rows = page.getJSONArray("records")
    return (0 until rows.length()).any { index ->
        val event = rows.getJSONObject(index).getJSONObject("event")
        event.getString("type") == "user/message" && voiceSource(event.optJSONObject("data"), nonce)
    }
}
