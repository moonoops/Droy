package io.droyagent.app.nativeui.transcript

import io.droyagent.app.nativeui.data.NativeMessage
import io.droyagent.app.nativeui.data.NativeReadingPosition
import io.droyagent.app.nativeui.data.NativeRuntimeState
import org.json.JSONObject

data class NativeSubagentTranscriptAddress(val parentSessionId: String, val childSessionId: String) {
    init {
        require(parentSessionId != childSessionId)
        listOf(parentSessionId, childSessionId).forEach { require(it.length in 1..256 && it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]*"))) }
    }
    fun wire(): JSONObject = JSONObject().put("kind", "subagent").put("parentSessionId", parentSessionId)
        .put("childSessionId", childSessionId).put("mode", "unknown")
}

data class NativeTranscriptState(
    val address: NativeSubagentTranscriptAddress? = null,
    val title: String? = null,
    val runtime: NativeRuntimeState = NativeRuntimeState(),
    val messages: List<NativeMessage> = emptyList(),
    val loading: Boolean = false,
    val loadingOlder: Boolean = false,
    val hasMore: Boolean = false,
    val running: Boolean = false,
    val baselineReady: Boolean = false,
    val live: Boolean = false,
    val error: String? = null,
    val pagingError: String? = null,
    val readingPosition: NativeReadingPosition? = null,
)
