package io.droyagent.app.nativeui.tools

import io.droyagent.app.nativeui.data.NativeApiException
import org.junit.Assert.*
import org.junit.Test

class NativeTerminalInputTest {
    private fun failure(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (error: NativeApiException) { assertEquals(code, error.code) }
    }
    @Test fun utf8ChunksKeepCjkEmojiAndControlCharactersExact() {
        val input = "中😀\r\n\u001b[Aabc"
        val buffer = NativeTerminalInputBuffer(4, 1000)
        val commands = buffer.write(input.toByteArray())
        assertEquals(input, commands.joinToString("") { it.data!! })
        assertTrue(commands.all { it.data!!.toByteArray().size <= 4 })
        assertEquals((1L..commands.size.toLong()).toList(), commands.map { it.sequence })
    }
    @Test fun nativeByteProtocolsAreRejectedWithoutLossyReplacementOrSequenceConsumption() {
        val buffer = NativeTerminalInputBuffer(16, 1000)
        failure("NATIVE_TOOLS_INPUT_ENCODING") { buffer.write(byteArrayOf(27, 91, 77, 32, 0x80.toByte(), 32)) }
        assertEquals(0, buffer.bytes)
        assertEquals(1L, buffer.write("valid".toByteArray()).single().sequence)
    }
    @Test fun pasteAdmissionIsAtomicAndIncludesEncodedJsonBytes() {
        val buffer = NativeTerminalInputBuffer(2, 80)
        failure("NATIVE_TOOLS_INPUT_TOO_LARGE") { buffer.write("ab中文".toByteArray()) }
        failure("NATIVE_TOOLS_INPUT_TOO_LARGE") { buffer.write("\u0000".repeat(20).toByteArray()) }
        assertEquals(0, buffer.bytes)
        assertEquals(1L, buffer.write("ok".toByteArray()).single().sequence)
    }
    @Test fun creditsRemainUntilAnOrderedProviderAck() {
        val buffer = NativeTerminalInputBuffer(32, 100)
        val first = buffer.write("a".repeat(30).toByteArray()).single(); buffer.submit(first)
        val reserved = buffer.bytes
        assertTrue(reserved > 30)
        failure("NATIVE_TOOLS_INPUT_PENDING") { buffer.write("next".toByteArray()) }
        assertEquals(reserved, buffer.bytes)
        buffer.ack(1); assertEquals(0, buffer.bytes)
        assertEquals(2L, buffer.write("next".toByteArray()).single().sequence)
    }
    @Test fun ackBeforeSubmissionAndAckGapsCannotReleaseCapacity() {
        val buffer = NativeTerminalInputBuffer(32, 1000)
        val first = buffer.write("one".toByteArray()).single()
        failure("NATIVE_TOOLS_INPUT_SEQUENCE") { buffer.ack(1) }
        assertEquals(first.bytes, buffer.bytes)
        buffer.submit(first)
        failure("NATIVE_TOOLS_INPUT_SEQUENCE") { buffer.ack(2) }
        buffer.ack(1)
        failure("NATIVE_TOOLS_INPUT_SEQUENCE") { buffer.ack(1) }
    }
    @Test fun losingControlDiscardsOnlyUnsubmittedFramesAndDoesNotLeaveASequenceGap() {
        val buffer = NativeTerminalInputBuffer(32, 1000)
        val first = buffer.write("already submitted".toByteArray()).single(); buffer.submit(first)
        val old = buffer.write("never submitted".toByteArray()).single()
        buffer.dropUnsubmitted()
        assertFalse(buffer.current(old)); assertTrue(buffer.current(first))
        val corrected = buffer.write("new intent".toByteArray()).single()
        assertEquals(2L, corrected.sequence); assertFalse(buffer.current(old)); assertTrue(buffer.current(corrected))
        buffer.submit(corrected); buffer.ack(1); buffer.ack(2); assertEquals(0, buffer.bytes)
    }
    @Test fun resizeSharesTheExactInputOrder() {
        val buffer = NativeTerminalInputBuffer(32, 1000)
        val first = buffer.resize(100, 30).single(); buffer.submit(first)
        val second = buffer.write("echo fixture\r".toByteArray()).single(); buffer.submit(second)
        assertEquals(1L, first.sequence); assertEquals("resize", first.value().getString("type"))
        assertEquals(2L, second.sequence); buffer.ack(1); buffer.ack(2)
        failure("NATIVE_TOOLS_BAD_REQUEST") { buffer.resize(1, 20) }
    }
    @Test fun directoryNavigationCannotInjectAnotherPathComponent() {
        assertEquals("nested/report.md", NativeToolsPaths.child("nested", "report.md"))
        assertEquals("", NativeToolsPaths.parent("nested")); assertEquals("nested", NativeToolsPaths.parent("nested/deeper"))
        for (name in listOf("..", ".", "nested/other", "")) assertThrows(IllegalArgumentException::class.java) { NativeToolsPaths.child("", name) }
        assertEquals("html", NativeToolsPaths.kind("Report.HTML")); assertEquals("image", NativeToolsPaths.kind("plot.png"))
    }
}
