package io.droyagent.app.nativeui.transcript

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeReadingPosition
import io.droyagent.app.nativeui.data.NativeRuntimeState
import io.droyagent.app.nativeui.data.NativeSessionReducer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

internal class NativeTranscriptCoordinator(
    private val gateway: NativeTranscriptGateway,
    private val scope: CoroutineScope,
    private val reconnectDelayMs: Long = 1000,
) {
    private val mutable = MutableStateFlow(NativeTranscriptState())
    val state = mutable.asStateFlow()
    private val reducer = NativeSessionReducer()
    private var observing = false
    private var epoch = 0L
    private var baseline = 0L
    private var following: Job? = null
    private var paging: Job? = null
    private var rendering: Job? = null
    private val budgets = mutableMapOf<NativeSubagentTranscriptAddress, Int>()
    private val positions = mutableMapOf<NativeSubagentTranscriptAddress, NativeReadingPosition>()

    fun open(address: NativeSubagentTranscriptAddress, title: String?) {
        if (mutable.value.address == address) {
            if (!mutable.value.baselineReady && !title.isNullOrBlank()) mutable.update { it.copy(title = title) }
            return
        }
        invalidate()
        reducer.reset()
        mutable.value = NativeTranscriptState(address = address, title = title?.takeIf(String::isNotBlank), runtime = mutable.value.runtime,
            readingPosition = positions[address])
        connect()
    }
    fun runtime(value: NativeRuntimeState) {
        val ready = mutable.value.runtime.ready
        mutable.update { it.copy(runtime = value) }
        if (!value.ready) {
            if (following != null) invalidate()
            mutable.update { it.copy(loading = false, live = false, baselineReady = false, loadingOlder = false) }
        } else if (!ready) connect()
    }
    fun startObserving() { if (!observing) { observing = true; connect() } }
    fun stopObserving() {
        observing = false; invalidate()
        mutable.update { it.copy(loading = false, loadingOlder = false, live = false, baselineReady = false) }
    }
    fun refresh() { invalidate(); connect() }
    fun setReadingPosition(address: NativeSubagentTranscriptAddress, position: NativeReadingPosition) {
        if (position.offset < 0 || position.key.isBlank()) return
        positions[address] = position
        if (mutable.value.address == address) mutable.update { it.copy(readingPosition = position) }
    }

    private fun invalidate() {
        epoch++; baseline++
        following?.cancel(); following = null
        paging?.cancel(); paging = null
        rendering?.cancel(); rendering = null
    }
    private fun connect() {
        val address = mutable.value.address ?: return
        if (!observing || !mutable.value.runtime.ready || following?.isActive == true) return
        val current = epoch
        mutable.update { it.copy(loading = true, loadingOlder = false, baselineReady = false, live = false, error = null) }
        following = scope.launch {
            while (isActive && observing && current == epoch && mutable.value.address == address && mutable.value.runtime.ready) {
                var receivedBaseline = false
                try {
                    gateway.follow(address, budgets[address] ?: 60).collect { frame ->
                        if (current != epoch || mutable.value.address != address) return@collect
                        val snapshot = frame.getString("type") == "snapshot"
                        if (snapshot) {
                            verifyBaseline(address, frame)
                            baseline++; paging?.cancel(); paging = null
                        } else if (!receivedBaseline) throw NativeApiException("malformed_response")
                        if (!reducer.accept(frame)) throw NativeApiException("stream_gap")
                        if (snapshot) receivedBaseline = true
                        val title = frame.takeIf { snapshot }?.getJSONObject("projections")?.getJSONObject("values")?.opt("title") as? String
                        mutable.update { it.copy(loading = false, loadingOlder = if (snapshot) false else it.loadingOlder,
                            baselineReady = true, live = true, error = null,
                            pagingError = if (snapshot) null else it.pagingError,
                            title = title?.takeIf(String::isNotBlank) ?: it.title) }
                        if (frame.optString("type") == "assistant-stream" && frame.getJSONObject("frame").optString("type") == "chunk") {
                            if (rendering?.isActive != true) rendering = scope.launch { delay(16); if (current == epoch) publish(address) }
                        } else {
                            rendering?.cancel(); rendering = null; publish(address)
                        }
                    }
                    throw NativeApiException("stream_disconnected")
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (current != epoch || mutable.value.address != address) return@launch
                    val code = code(error)
                    baseline++; paging?.cancel(); paging = null; rendering?.cancel(); rendering = null
                    if (code in reconnectable) publish(address)
                    mutable.update { it.copy(loading = false, loadingOlder = false, baselineReady = false, live = false, error = code) }
                    if (code !in reconnectable) break
                    delay(reconnectDelayMs)
                    mutable.update { it.copy(loading = true) }
                }
            }
        }
    }
    fun loadOlder() {
        val currentState = mutable.value
        val address = currentState.address ?: return
        val before = reducer.oldestSeq() ?: return
        if (!observing || !currentState.baselineReady || !currentState.hasMore || paging?.isActive == true || before == 0L) return
        val currentEpoch = epoch; val currentBaseline = baseline; val through = reducer.cursor
        mutable.update { it.copy(loadingOlder = true, pagingError = null) }
        paging = scope.launch {
            try {
                val page = gateway.page(address, through, before, 60)
                if (currentEpoch != epoch || currentBaseline != baseline || !mutable.value.baselineReady || mutable.value.address != address) return@launch
                verifyPage(page, through, before)
                reducer.prepend(page); publish(address)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (currentEpoch == epoch && currentBaseline == baseline) mutable.update { it.copy(pagingError = code(error)) }
            } finally {
                if (currentEpoch == epoch && currentBaseline == baseline) mutable.update { it.copy(loadingOlder = false) }
            }
        }
    }

    private fun publish(address: NativeSubagentTranscriptAddress) {
        val messages = reducer.messages()
        budgets[address] = maxOf(budgets[address] ?: 60, messages.size + 20)
        mutable.update { it.copy(messages = messages, running = reducer.running, hasMore = reducer.hasMore) }
    }
    private fun verifyBaseline(address: NativeSubagentTranscriptAddress, frame: JSONObject) {
        val header = frame.getJSONObject("header")
        if (header.getString("id") != address.childSessionId || header.optString("origin") != "subagent" || header.optString("parentSession") != address.parentSessionId)
            throw NativeApiException("native_transcript_identity_conflict")
    }
    private fun verifyPage(page: JSONObject, through: Long, before: Long) {
        val rows = page.getJSONArray("records")
        var previous = -1L
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val seq = row.getJSONObject("event").getLong("seq")
            if (row.getString("type") != "event" || seq < 0 || seq <= previous || seq >= before || seq > through) throw NativeApiException("malformed_response")
            previous = seq
        }
        if (rows.length() == 0 && page.getBoolean("hasMore")) throw NativeApiException("malformed_response")
    }
    private fun code(error: Exception): String = when (error) {
        is NativeApiException -> error.code
        is org.json.JSONException, is IllegalArgumentException -> "malformed_response"
        is java.io.IOException -> "network_error"
        else -> "native_transcript_failed"
    }
    private val reconnectable = setOf("network_error", "stream_disconnected", "stream_gap", "runtime_unavailable", "service_temporarily_unavailable")
}
