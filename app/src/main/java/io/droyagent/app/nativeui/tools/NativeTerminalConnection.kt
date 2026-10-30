package io.droyagent.app.nativeui.tools

import io.droyagent.app.nativeui.data.NativeApiException
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class NativeTerminalConnectionState(val epoch: Long = 0, val connected: Boolean = false, val writable: Boolean = false,
    val pendingBytes: Int = 0, val error: String? = null, val title: String = "", val processState: String = "", val cols: Int = 80, val rows: Int = 24)
sealed interface NativeTerminalRenderEvent {
    val epoch: Long
    data class Snapshot(override val epoch: Long, val ansi: ByteArray, val cols: Int, val rows: Int, val writable: Boolean) : NativeTerminalRenderEvent
    data class Output(override val epoch: Long, val ansi: ByteArray) : NativeTerminalRenderEvent
    data class Control(override val epoch: Long, val writable: Boolean, val cols: Int, val rows: Int) : NativeTerminalRenderEvent
    data class Detached(override val epoch: Long) : NativeTerminalRenderEvent
}

/** One UI collector owns one physical attachment and never carries input into another. */
internal class NativeTerminalConnection(private val repository: NativeToolsRepository) {
    private val mutable = MutableStateFlow(NativeTerminalConnectionState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var buffer: NativeTerminalInputBuffer? = null
    private var queue: Channel<NativeTerminalCommand>? = null
    private var maxCols = 0
    private var maxRows = 0
    fun interactionError(code: String) { report(code) }

    fun input(epoch: Long, bytes: ByteArray) = enqueue(epoch) { it.write(bytes) }
    fun resize(epoch: Long, cols: Int, rows: Int) {
        if (cols == state.value.cols && rows == state.value.rows) return
        if (maxCols > 0 && cols > maxCols || maxRows > 0 && rows > maxRows) { report("NATIVE_TOOLS_BAD_REQUEST"); return }
        enqueue(epoch) { it.resize(cols, rows) }
    }
    private fun enqueue(epoch: Long, create: (NativeTerminalInputBuffer) -> List<NativeTerminalCommand>) {
        if (epoch != generation || !state.value.connected || !state.value.writable) return
        val current = buffer ?: return
        try {
            val commands = create(current)
            commands.forEach { if (queue?.trySend(it)?.isSuccess != true) throw NativeApiException("NATIVE_TOOLS_ATTACHMENT_CLOSED") }
            mutable.update { it.copy(pendingBytes = current.bytes, error = null) }
        } catch (error: NativeApiException) { report(error.code) }
    }
    private fun report(code: String) { mutable.update { it.copy(error = code) } }
    private fun loseControl() {
        buffer?.dropUnsubmitted()
        while (queue?.tryReceive()?.isSuccess == true) { /* Unsent input is discarded, never requeued when control returns. */ }
        mutable.update { it.copy(writable = false, pendingBytes = buffer?.bytes ?: 0) }
    }
    suspend fun follow(key: NativeTerminalKey, render: (NativeTerminalRenderEvent) -> Unit) = coroutineScope {
        val epoch = ++generation
        val outgoing = Channel<NativeTerminalCommand>(Channel.UNLIMITED)
        queue?.close(); queue = outgoing; buffer = null
        mutable.value = NativeTerminalConnectionState(epoch = epoch)
        var attachment: String? = null
        var baseline = false
        var outputSequence = -1L
        var opened = false
        var sender: kotlinx.coroutines.Job? = null
        try {
            val id = repository.attach(key); attachment = id
            if (epoch != generation) return@coroutineScope
            repository.terminalFrames(id).collect { frame ->
                if (epoch != generation) return@collect
                when (frame.getString("type")) {
                    "native/attached" -> {
                        if (opened || frame.getString("attachmentId") != id) throw NativeApiException("malformed_response")
                        opened = true
                        val maxInput = frame.getInt("maxInputBytes")
                        val maxBuffered = frame.getInt("maxBufferedBytes")
                        if (maxInput !in 1..(256 * 1024) || maxBuffered !in 1..(256 * 1024)) throw NativeApiException("malformed_response")
                        maxCols = frame.getInt("maxCols"); maxRows = frame.getInt("maxRows")
                        if (maxCols !in 2..16384 || maxRows !in 1..16384) throw NativeApiException("malformed_response")
                        val current = NativeTerminalInputBuffer(maxInput, maxBuffered); buffer = current
                        sender = launch {
                            try { for (command in outgoing) {
                                if (epoch != generation) break
                                if (!current.current(command)) continue
                                if (!state.value.writable) { loseControl(); continue }
                                current.submit(command)
                                repository.input(id, command.value())
                            } } catch (error: CancellationException) { throw error }
                            catch (error: Exception) {
                                report((error as? NativeApiException)?.code ?: "network_error")
                                this@coroutineScope.cancel(CancellationException("Terminal input result unconfirmed", error))
                            }
                        }
                    }
                    "snapshot" -> {
                        if (!opened || baseline) throw NativeApiException("malformed_response")
                        outputSequence = frame.sequence()
                        val info = frame.getJSONObject("info"); info.verify(key)
                        val writable = frame.get("writable") as? Boolean ?: throw NativeApiException("malformed_response")
                        baseline = true
                        mutable.update { it.copy(connected = true, writable = writable, title = info.getString("title"), processState = info.getString("state"), cols = info.getInt("cols"), rows = info.getInt("rows"), error = null) }
                        render(NativeTerminalRenderEvent.Snapshot(epoch, frame.getString("screen").toByteArray(StandardCharsets.UTF_8), info.getInt("cols"), info.getInt("rows"), writable))
                    }
                    "output" -> {
                        if (!baseline || frame.sequence() != outputSequence + 1) throw NativeApiException("malformed_response")
                        outputSequence++
                        render(NativeTerminalRenderEvent.Output(epoch, frame.getString("data").toByteArray(StandardCharsets.UTF_8)))
                    }
                    "state" -> {
                        if (!baseline) throw NativeApiException("malformed_response")
                        val info = frame.getJSONObject("info"); info.verify(key)
                        val writable = frame.get("writable") as? Boolean ?: throw NativeApiException("malformed_response")
                        if (!writable) loseControl()
                        mutable.update { it.copy(writable = writable, title = info.getString("title"), processState = info.getString("state"), cols = info.getInt("cols"), rows = info.getInt("rows")) }
                        render(NativeTerminalRenderEvent.Control(epoch, writable, info.getInt("cols"), info.getInt("rows")))
                    }
                    "ack" -> {
                        if (!baseline) throw NativeApiException("malformed_response")
                        buffer?.ack(frame.sequence()) ?: throw NativeApiException("malformed_response")
                        if (frame.has("refusal")) {
                            if (frame.getString("refusal") !in setOf("read-only", "not-running")) throw NativeApiException("malformed_response")
                            loseControl(); render(NativeTerminalRenderEvent.Control(epoch, false, state.value.cols, state.value.rows))
                        }
                        mutable.update { it.copy(pendingBytes = buffer?.bytes ?: 0) }
                    }
                    else -> throw NativeApiException("malformed_response")
                }
            }
            if (epoch == generation) report("stream_disconnected")
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (epoch == generation) report((error as? NativeApiException)?.code ?: "network_error") }
        finally {
            outgoing.close(); sender?.cancel()
            try {
                if (epoch == generation) {
                    loseControl(); buffer = null; queue = null
                    mutable.update { it.copy(connected = false, writable = false, pendingBytes = 0) }
                    render(NativeTerminalRenderEvent.Detached(epoch))
                }
            } finally {
                attachment?.let { id -> withContext(NonCancellable) { withTimeoutOrNull(5000) { try { repository.detach(id) } catch (_: Exception) { /* A lost attachment has no replay path. */ } } } }
            }
        }
    }
    private fun JSONObject.sequence(): Long {
        val value = get("sequence") as? Number ?: throw NativeApiException("malformed_response")
        val number = value.toLong()
        if (number < 0 || value.toDouble() != number.toDouble()) throw NativeApiException("malformed_response")
        return number
    }
    private fun JSONObject.verify(key: NativeTerminalKey) {
        if (getString("id") != key.id || getInt("cols") !in 2..16384 || getInt("rows") !in 1..16384
            || getString("state") !in setOf("running", "exited", "failed")) throw NativeApiException("malformed_response")
    }
}
