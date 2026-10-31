package io.droyagent.app.nativeui.tools

import io.droyagent.app.nativeui.data.NativeApiException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.json.JSONObject

internal data class NativeTerminalCommand(val sequence: Long, val data: String? = null, val cols: Int? = null, val rows: Int? = null) {
    fun value(): JSONObject = JSONObject().put("type", if (data != null) "write" else "resize").put("sequence", sequence).apply {
        if (data != null) put("data", data) else { put("cols", cols); put("rows", rows) }
    }
    val bytes: Int get() = value().toString().toByteArray(StandardCharsets.UTF_8).size
}

/** Credits live until a provider ACK; the HTTP receipt never retires them. */
internal class NativeTerminalInputBuffer(private val maxInputBytes: Int, private val maxBufferedBytes: Int) {
    init { require(maxInputBytes > 0 && maxBufferedBytes > 0) }
    private val pending = linkedMapOf<Long, NativeTerminalCommand>()
    private var allocated = 0L
    private var submitted = 0L
    private var acknowledged = 0L
    val bytes: Int get() = pending.values.sumOf { it.bytes }

    fun write(input: ByteArray): List<NativeTerminalCommand> {
        if (input.size > maxBufferedBytes) throw NativeApiException("NATIVE_TOOLS_INPUT_TOO_LARGE")
        val text = try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input)).toString() }
        catch (error: java.nio.charset.CharacterCodingException) { throw NativeApiException("NATIVE_TOOLS_INPUT_ENCODING", error) }
        if (text.isEmpty()) return emptyList()
        val chunks = mutableListOf<String>()
        val part = java.lang.StringBuilder(); var size = 0; var position = 0
        while (position < text.length) {
            val cp = text.codePointAt(position)
            val length = when { cp <= 0x7f -> 1; cp <= 0x7ff -> 2; cp <= 0xffff -> 3; else -> 4 }
            if (length > maxInputBytes) throw NativeApiException("NATIVE_TOOLS_INPUT_TOO_LARGE")
            if (size + length > maxInputBytes) { chunks += part.toString(); part.setLength(0); size = 0 }
            part.appendCodePoint(cp); size += length; position += Character.charCount(cp)
        }
        if (part.isNotEmpty()) chunks += part.toString()
        return reserve(chunks.mapIndexed { index, data -> NativeTerminalCommand(allocated + index + 1, data = data) })
    }
    fun resize(cols: Int, rows: Int): List<NativeTerminalCommand> {
        if (cols !in 2..16384 || rows !in 1..16384) throw NativeApiException("NATIVE_TOOLS_BAD_REQUEST")
        return reserve(listOf(NativeTerminalCommand(allocated + 1, cols = cols, rows = rows)))
    }
    private fun reserve(commands: List<NativeTerminalCommand>): List<NativeTerminalCommand> {
        val size = commands.sumOf { it.bytes.toLong() }
        if (size > maxBufferedBytes) throw NativeApiException("NATIVE_TOOLS_INPUT_TOO_LARGE")
        if (size + bytes > maxBufferedBytes) throw NativeApiException("NATIVE_TOOLS_INPUT_PENDING")
        commands.forEach { pending[it.sequence] = it }; allocated = commands.lastOrNull()?.sequence ?: allocated
        return commands
    }
    fun current(command: NativeTerminalCommand): Boolean = pending[command.sequence] === command
    fun submit(command: NativeTerminalCommand) {
        if (!current(command) || command.sequence != submitted + 1) throw NativeApiException("NATIVE_TOOLS_INPUT_SEQUENCE")
        submitted = command.sequence
    }
    fun ack(sequence: Long) {
        if (sequence != acknowledged + 1 || sequence > submitted || pending.remove(sequence) == null) throw NativeApiException("NATIVE_TOOLS_INPUT_SEQUENCE")
        acknowledged = sequence
    }
    fun dropUnsubmitted() {
        pending.keys.filter { it > submitted }.forEach { pending.remove(it) }; allocated = submitted
    }
}
