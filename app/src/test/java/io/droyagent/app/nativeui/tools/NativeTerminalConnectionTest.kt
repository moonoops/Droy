package io.droyagent.app.nativeui.tools

import io.droyagent.app.nativeui.data.NativeApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeTerminalConnectionTest {
    private val key = NativeTerminalKey("work-a", "terminal-a")
    private class Repository : NativeToolsRepository {
        val frames = mutableMapOf<String, Channel<JSONObject>>()
        val inputs = mutableListOf<JSONObject>()
        val detached = mutableListOf<String>()
        var serial = 0
        var inputGate: CompletableDeferred<Unit>? = null
        var inputFailure: Exception? = null
        override suspend fun attach(key: NativeTerminalKey): String = "attachment-${++serial}".also { frames[it] = Channel(Channel.UNLIMITED) }
        override fun terminalFrames(attachmentId: String): Flow<JSONObject> = flow {
            emit(JSONObject().put("type", "native/attached").put("attachmentId", attachmentId).put("maxInputBytes", 8).put("maxBufferedBytes", 1000).put("maxCols", 240).put("maxRows", 80))
            emit(JSONObject().put("type", "snapshot").put("sequence", 10).put("screen", "\u001b[2Joriginal").put("info", info()).put("writable", true))
            for (value in frames.getValue(attachmentId)) emit(value)
        }
        override suspend fun input(attachmentId: String, frame: JSONObject) { inputs += JSONObject(frame.toString()); inputGate?.await(); inputFailure?.let { throw it } }
        override suspend fun detach(attachmentId: String) { detached += attachmentId; frames.getValue(attachmentId).close() }
        fun info() = JSONObject().put("id", "terminal-a").put("title", "Fixture shell").put("state", "running").put("cols", 80).put("rows", 24)
        suspend fun state(id: String, writable: Boolean) { frames.getValue(id).send(JSONObject().put("type", "state").put("info", info()).put("writable", writable)) }
        suspend fun ack(id: String, sequence: Long) { frames.getValue(id).send(JSONObject().put("type", "ack").put("sequence", sequence)) }
        override suspend fun sessions(): List<NativeToolsSession> = error("Unexpected")
        override suspend fun directory(sessionId: String, path: String): NativeToolDirectory = error("Unexpected")
        override suspend fun stat(sessionId: String, path: String): NativeToolStat = error("Unexpected")
        override suspend fun text(sessionId: String, path: String, offset: Long): NativeToolText = error("Unexpected")
        override suspend fun bytes(sessionId: String, path: String): NativeToolBytes = error("Unexpected")
        override suspend fun attachment(source: NativeToolSource.Attachment): NativeToolAttachmentInfo = error("Unexpected")
        override suspend fun attachmentBytes(source: NativeToolSource.Attachment): ByteArray = error("Unexpected")
        override suspend fun pdf(source: NativeToolSource): NativeToolPdf = error("Unexpected")
        override suspend fun describeCopy(source: NativeToolSource): NativeToolCopyPlan = error("Unexpected")
        override suspend fun copy(plan: NativeToolCopyPlan, append: suspend (ByteArray) -> Unit) = error("Unexpected")
        override fun watch(sessionId: String, path: String): Flow<JSONObject> = error("Unexpected")
        override suspend fun content(sessionId: String, before: Long?): NativeToolContentPage = error("Unexpected")
        override suspend fun changes(sessionId: String, seq: Long): NativeToolChanges = error("Unexpected")
        override suspend fun diff(sessionId: String, seq: Long, index: Int): NativeToolDiff = error("Unexpected")
        override suspend fun terminals(sessionId: String): List<NativeToolTerminal> = error("Unexpected")
        override suspend fun createTerminal(sessionId: String, id: String): NativeToolTerminal = error("Unexpected")
        override suspend fun renameTerminal(key: NativeTerminalKey, title: String) = error("Unexpected")
        override suspend fun closeTerminal(key: NativeTerminalKey) = error("Detach must never close a terminal")
    }
    private suspend fun eventually(read: () -> Boolean) { withTimeout(2000) { while (!read()) yield() } }
    @Test fun snapshotIsDeliveredOnceBeforeStrictlyOrderedOutputAndState() = runBlocking {
        val repo = Repository(); val connection = NativeTerminalConnection(repo); val events = mutableListOf<NativeTerminalRenderEvent>()
        val job = launch { connection.follow(key, events::add) }; eventually { connection.state.value.connected }
        assertEquals(1, events.filterIsInstance<NativeTerminalRenderEvent.Snapshot>().size)
        repo.frames.getValue("attachment-1").send(JSONObject().put("type", "output").put("sequence", 11).put("data", "中😀"))
        repo.state("attachment-1", false); eventually { !connection.state.value.writable }
        assertEquals("中😀", events.filterIsInstance<NativeTerminalRenderEvent.Output>().single().ansi.toString(Charsets.UTF_8))
        repo.frames.getValue("attachment-1").send(JSONObject().put("type", "output").put("sequence", 13).put("data", "missing frame"))
        job.join(); assertEquals("malformed_response", connection.state.value.error); assertEquals(listOf("attachment-1"), repo.detached)
    }
    @Test fun httpReceiptDoesNotReleasePendingBytesAndEpochMismatchNeverSendsInput() = runBlocking {
        val repo = Repository(); val connection = NativeTerminalConnection(repo)
        val job = launch { connection.follow(key) {} }; eventually { connection.state.value.connected }
        val epoch = connection.state.value.epoch
        connection.input(epoch - 1, "stale".toByteArray()); yield(); assertTrue(repo.inputs.isEmpty())
        connection.input(epoch, "valid".toByteArray()); eventually { repo.inputs.size == 1 }
        assertTrue(connection.state.value.pendingBytes > 0)
        repo.ack("attachment-1", 1); eventually { connection.state.value.pendingBytes == 0 }
        job.cancelAndJoin(); assertEquals(listOf("attachment-1"), repo.detached)
    }
    @Test fun stolenControlDropsUnsubmittedPasteWithoutReplayingItWhenControlReturns() = runBlocking {
        val repo = Repository().apply { inputGate = CompletableDeferred() }; val connection = NativeTerminalConnection(repo)
        val job = launch { connection.follow(key) {} }; eventually { connection.state.value.connected }
        val epoch = connection.state.value.epoch
        connection.input(epoch, "1234567890".toByteArray()); eventually { repo.inputs.size == 1 }
        repo.state("attachment-1", false); eventually { !connection.state.value.writable }
        repo.inputGate!!.complete(Unit); repo.ack("attachment-1", 1); yield()
        repo.state("attachment-1", true); eventually { connection.state.value.writable }
        connection.input(epoch, "new".toByteArray()); eventually { repo.inputs.size == 2 }
        assertEquals(listOf("12345678", "new"), repo.inputs.map { it.getString("data") })
        assertEquals(listOf(1L, 2L), repo.inputs.map { it.getLong("sequence") }); job.cancelAndJoin()
    }
    @Test fun uncertainHttpInputEndsTheAttachmentAndRequiresExplicitNewIntent() = runBlocking {
        val repo = Repository().apply { inputFailure = NativeApiException("network_error") }; val connection = NativeTerminalConnection(repo)
        val first = launch { connection.follow(key) {} }; eventually { connection.state.value.connected }
        connection.input(connection.state.value.epoch, "old".toByteArray()); first.join()
        assertEquals("network_error", connection.state.value.error); assertFalse(connection.state.value.connected)
        assertEquals(1, repo.inputs.size); assertEquals(listOf("attachment-1"), repo.detached)
        repo.inputFailure = null
        val second = launch { connection.follow(key) {} }; eventually { connection.state.value.connected }
        assertEquals(1, repo.inputs.size)
        connection.input(connection.state.value.epoch, "new".toByteArray()); eventually { repo.inputs.size == 2 }
        assertEquals(listOf("old", "new"), repo.inputs.map { it.getString("data") }); assertEquals(1L, repo.inputs.last().getLong("sequence"))
        second.cancelAndJoin()
    }
    @Test fun oldPhysicalStreamsCannotAppendToANewerEpochOrClearItsControl() = runBlocking {
        val repo = Repository(); val connection = NativeTerminalConnection(repo); val events = mutableListOf<NativeTerminalRenderEvent>()
        val first = launch { connection.follow(key, events::add) }; eventually { connection.state.value.connected }; val oldEpoch = connection.state.value.epoch
        val second = launch { connection.follow(key, events::add) }; eventually { connection.state.value.connected && connection.state.value.epoch != oldEpoch }
        val newEpoch = connection.state.value.epoch
        repo.frames.getValue("attachment-1").send(JSONObject().put("type", "output").put("sequence", 11).put("data", "old epoch"))
        repo.state("attachment-1", false); yield()
        assertTrue(connection.state.value.writable); assertFalse(events.filterIsInstance<NativeTerminalRenderEvent.Output>().any { it.ansi.toString(Charsets.UTF_8) == "old epoch" })
        first.cancelAndJoin(); assertEquals(newEpoch, connection.state.value.epoch); assertTrue(connection.state.value.writable)
        second.cancelAndJoin(); assertEquals(2, repo.detached.size)
    }
    @Test fun nonUtf8InputIsRefusedWithoutDisconnectingTheCurrentTerminal() = runBlocking {
        val repo = Repository(); val connection = NativeTerminalConnection(repo)
        val job = launch { connection.follow(key) {} }; eventually { connection.state.value.connected }
        connection.input(connection.state.value.epoch, byteArrayOf(0xff.toByte()))
        assertEquals("NATIVE_TOOLS_INPUT_ENCODING", connection.state.value.error); assertTrue(connection.state.value.connected); assertTrue(repo.inputs.isEmpty())
        connection.input(connection.state.value.epoch, "ok".toByteArray()); eventually { repo.inputs.size == 1 }
        assertNull(connection.state.value.error); assertEquals(1L, repo.inputs.single().getLong("sequence")); job.cancelAndJoin()
    }
    @Test fun rendererUnsupportedDimensionsDetachWithoutASecondResizeOrClosingTheProcess() = runBlocking {
        val repo = Repository(); val connection = NativeTerminalConnection(repo); val events = mutableListOf<NativeTerminalRenderEvent>()
        connection.follow(key) { frame ->
            events += frame
            if (frame is NativeTerminalRenderEvent.Snapshot) throw NativeApiException("NATIVE_TOOLS_TERMINAL_SIZE_UNSUPPORTED")
        }
        assertEquals("NATIVE_TOOLS_TERMINAL_SIZE_UNSUPPORTED", connection.state.value.error)
        assertEquals(1, events.filterIsInstance<NativeTerminalRenderEvent.Detached>().size)
        assertTrue(events.none { it is NativeTerminalRenderEvent.Control })
        assertEquals(listOf("attachment-1"), repo.detached)
    }
}
