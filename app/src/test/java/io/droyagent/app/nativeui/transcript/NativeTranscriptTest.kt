package io.droyagent.app.nativeui.transcript

import io.droyagent.app.nativeui.data.NativeApiException
import io.droyagent.app.nativeui.data.NativeReadingPosition
import io.droyagent.app.nativeui.data.NativeRuntimeState
import io.droyagent.app.nativeui.data.NativeTransportEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeTranscriptTest {
    private val address = NativeSubagentTranscriptAddress("parent", "child")
    private val ready = NativeRuntimeState(ready = true)
    private class Gateway : NativeTranscriptGateway {
        data class Follow(val address: NativeSubagentTranscriptAddress, val budget: Int, val channel: Channel<JSONObject>)
        data class Page(val address: NativeSubagentTranscriptAddress, val through: Long, val before: Long, val limit: Int)
        val follows = mutableListOf<Follow>()
        val pages = mutableListOf<Page>()
        var detached = 0
        var pageResult: suspend (Page) -> JSONObject = { JSONObject().put("records", JSONArray()).put("hasMore", false) }
        override fun follow(address: NativeSubagentTranscriptAddress, maxMessages: Int): Flow<JSONObject> = flow {
            val channel = Channel<JSONObject>(Channel.UNLIMITED)
            follows += Follow(address, maxMessages, channel)
            try { emitAll(channel.receiveAsFlow()) } finally { detached++ }
        }
        override suspend fun page(address: NativeSubagentTranscriptAddress, throughSeq: Long, beforeSeq: Long, maxMessages: Int): JSONObject {
            val request = Page(address, throughSeq, beforeSeq, maxMessages); pages += request
            return pageResult(request)
        }
    }
    private fun message(seq: Int, text: String, assistant: Boolean = false): JSONObject {
        val row = JSONObject().put("id", "m$seq").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        return JSONObject().put("type", "event").put("event", JSONObject().put("seq", seq).put("time", seq)
            .put("type", if (assistant) "assistant/message" else "user/message").put("surfaceOp", "append")
            .put("data", if (assistant) JSONObject().put("turn", 1).put("step", 1).put("message", row) else row))
    }
    private fun snapshot(address: NativeSubagentTranscriptAddress = this.address, cursor: Int = 0, more: Boolean = false, vararg messages: JSONObject): JSONObject =
        JSONObject().put("type", "snapshot").put("header", JSONObject().put("id", address.childSessionId).put("origin", "subagent").put("parentSession", address.parentSessionId))
            .put("cursor", cursor).put("records", JSONArray(messages)).put("hasMore", more)
            .put("projections", JSONObject().put("asOfSeq", cursor).put("values", JSONObject()))
    private suspend fun settle() { repeat(4) { yield() } }

    @Test fun clientUsesOnlyTypedSubagentFollowAndPage() = runBlocking {
        val paths = mutableListOf<String>(); val bodies = mutableListOf<JSONObject>()
        val transport = object : NativeTranscriptTransport {
            override fun events(path: String): Flow<NativeTransportEvent> {
                paths += path
                return flowOf(NativeTransportEvent.Opened, NativeTransportEvent.Frame(snapshot()))
            }
            override suspend fun post(path: String, body: JSONObject): JSONObject {
                paths += path; bodies += body
                return JSONObject().put("ok", true).put("result", JSONObject().put("records", JSONArray()).put("hasMore", false))
            }
        }
        val client = NativeTranscriptClient(transport)
        val route = NativeSubagentTranscriptAddress("parent:one", "child:two")
        assertEquals(1, client.follow(route, 120).toList().size)
        client.page(route, 20, 10, 60)
        assertEquals("/droy/native/transcript/follow?parentSessionId=parent%3Aone&childSessionId=child%3Atwo&maxMessages=120", paths[0])
        assertEquals("/droy/native/session/page", paths[1])
        assertEquals(setOf("address", "throughSeq", "beforeSeq", "maxMessages"), bodies.single().keys().asSequence().toSet())
        val typed = bodies.single().getJSONObject("address")
        assertEquals(setOf("kind", "parentSessionId", "childSessionId", "mode"), typed.keys().asSequence().toSet())
        assertEquals("subagent", typed.getString("kind")); assertEquals("unknown", typed.getString("mode"))
        assertEquals("parent:one", typed.getString("parentSessionId")); assertEquals("child:two", typed.getString("childSessionId"))
    }
    @Test fun clientSurfacesStreamAndPageErrorsWithoutTryingAnotherRoute() = runBlocking {
        val paths = mutableListOf<String>()
        val failure = JSONObject().put("ok", false).put("error", JSONObject().put("code", "subagent/unauthorized"))
        val client = NativeTranscriptClient(object : NativeTranscriptTransport {
            override fun events(path: String): Flow<NativeTransportEvent> { paths += path; return flowOf(NativeTransportEvent.Frame(failure)) }
            override suspend fun post(path: String, body: JSONObject): JSONObject { paths += path; return failure }
        })
        try { client.follow(address, 60).toList(); fail("stream_accepted") } catch (error: NativeApiException) { assertEquals("subagent/unauthorized", error.code) }
        try { client.page(address, 5, 3, 60); fail("page_accepted") } catch (error: NativeApiException) { assertEquals("subagent/unauthorized", error.code) }
        assertEquals(2, paths.size)
    }
    @Test fun noObservationStartsBeforeRuntimeAndStoppingOnlyDetachesTheReader() = runBlocking {
        val gateway = Gateway(); val model = NativeTranscriptCoordinator(gateway, this, 0)
        model.open(address, "Actual title"); model.startObserving(); settle(); assertTrue(gateway.follows.isEmpty())
        model.runtime(ready); settle(); assertEquals(1, gateway.follows.size)
        gateway.follows[0].channel.send(snapshot()); settle()
        assertTrue(model.state.value.baselineReady); assertTrue(model.state.value.live)
        model.stopObserving(); settle()
        assertEquals(1, gateway.detached); assertFalse(model.state.value.live); assertFalse(model.state.value.baselineReady)
        assertTrue(gateway.pages.isEmpty())
    }
    @Test fun snapshotMustProveBothChildAndItsActualParent() = runBlocking {
        for (invalid in listOf(snapshot(NativeSubagentTranscriptAddress("other-parent", "child")), snapshot(NativeSubagentTranscriptAddress("parent", "other-child")),
            snapshot().apply { getJSONObject("header").put("origin", "session") })) {
            val gateway = Gateway(); val model = NativeTranscriptCoordinator(gateway, this, 0)
            model.runtime(ready); model.open(address, null); model.startObserving(); settle()
            gateway.follows[0].channel.send(invalid); settle()
            assertEquals("native_transcript_identity_conflict", model.state.value.error)
            assertFalse(model.state.value.baselineReady); assertFalse(model.state.value.live); assertTrue(model.state.value.messages.isEmpty())
            assertEquals(1, gateway.follows.size); model.stopObserving()
        }
    }
    @Test fun eventWithoutItsVerifiedBaselineIsRejected() = runBlocking {
        val gateway = Gateway(); val model = NativeTranscriptCoordinator(gateway, this, 0)
        model.runtime(ready); model.open(address, null); model.startObserving(); settle()
        gateway.follows[0].channel.send(message(0, "not accepted")); settle()
        assertEquals("malformed_response", model.state.value.error); assertFalse(model.state.value.baselineReady)
        assertTrue(model.state.value.messages.isEmpty()); model.stopObserving()
    }
    @Test fun usesTheRealSnapshotTitleAndSharedReducerForHistoryAndStreaming() = runBlocking {
        val gateway = Gateway(); val model = NativeTranscriptCoordinator(gateway, this, 0)
        model.runtime(ready); model.open(address, "Catalog title"); model.startObserving(); settle()
        val base = snapshot(cursor = 0, messages = arrayOf(message(0, "question")))
        base.getJSONObject("projections").getJSONObject("values").put("title", "Live title")
        val channel = gateway.follows[0].channel; channel.send(base)
        channel.send(JSONObject().put("type", "assistant-stream").put("frame", JSONObject().put("type", "start").put("attemptId", "attempt")
            .put("turn", 1).put("step", 1).put("startedAfterSeq", 0)))
        channel.send(JSONObject().put("type", "assistant-stream").put("frame", JSONObject().put("type", "chunk").put("attemptId", "attempt").put("index", 0)
            .put("chunk", JSONObject().put("type", "text-delta").put("index", 0).put("text", "Answer"))))
        settle(); delay(20)
        assertEquals("Live title", model.state.value.title); assertTrue(model.state.value.messages.last().streaming)
        channel.send(message(1, "Answer", assistant = true)); settle()
        assertEquals(listOf("question", "Answer"), model.state.value.messages.map { it.text })
        assertFalse(model.state.value.messages.last().streaming); model.stopObserving()
    }
    @Test fun streamGapReadsANewTypedBaselineWithoutDuplicatingHistory() = runBlocking {
        val gateway = Gateway(); val model = NativeTranscriptCoordinator(gateway, this, 0)
        model.runtime(ready); model.open(address, null); model.startObserving(); settle()
        gateway.follows[0].channel.send(snapshot(cursor = 0, messages = arrayOf(message(0, "zero")))); settle()
        gateway.follows[0].channel.send(message(2, "gap")); settle()
        assertEquals(2, gateway.follows.size); assertFalse(model.state.value.baselineReady)
        gateway.follows[1].channel.send(snapshot(cursor = 2, messages = arrayOf(message(0, "zero"), message(1, "one"), message(2, "two")))); settle()
        assertEquals(listOf("zero", "one", "two"), model.state.value.messages.map { it.text }); assertTrue(model.state.value.live)
        assertTrue(gateway.follows.all { it.address == address }); model.stopObserving()
    }
    @Test fun pagingUsesTheCapturedBaselineCutAndRetainsLoadedWindowOnReconnect() = runBlocking {
        val gateway = Gateway(); val model = NativeTranscriptCoordinator(gateway, this, 0)
        model.runtime(ready); model.open(address, null); model.startObserving(); settle()
        gateway.follows[0].channel.send(snapshot(cursor = 70, more = true, messages = (11..70).map { message(it, "$it") }.toTypedArray())); settle()
        gateway.pageResult = { JSONObject().put("records", JSONArray((0 until 11).map { message(it, "$it") })).put("hasMore", false) }
        model.loadOlder(); settle()
        assertEquals(Gateway.Page(address, 70, 11, 60), gateway.pages.single())
        assertEquals(71, model.state.value.messages.size); assertFalse(model.state.value.hasMore)
        model.refresh(); settle(); assertTrue(gateway.follows.last().budget >= 71)
        model.stopObserving()
    }
    @Test fun pagingCannotAppendAReplyFromAnEarlierAddressOrBaseline() = runBlocking {
        val gateway = Gateway(); val model = NativeTranscriptCoordinator(gateway, this, 0)
        val delayed = CompletableDeferred<JSONObject>()
        gateway.pageResult = { withContext(NonCancellable) { delayed.await() } }
        model.runtime(ready); model.open(address, null); model.startObserving(); settle()
        gateway.follows[0].channel.send(snapshot(cursor = 1, more = true, messages = arrayOf(message(1, "old")))); settle()
        model.loadOlder(); settle()
        val next = NativeSubagentTranscriptAddress("parent-2", "child-2")
        model.open(next, null); settle()
        gateway.follows.last().channel.send(snapshot(next, 1, messages = arrayOf(message(1, "new")))); settle()
        delayed.complete(JSONObject().put("records", JSONArray().put(message(0, "must not append"))).put("hasMore", false)); settle()
        assertEquals(next, model.state.value.address); assertEquals(listOf("new"), model.state.value.messages.map { it.text })
        model.stopObserving()
    }
    @Test fun pagingRejectsRecordsOutsideTheRequestedCutAndCanBeRetriedExplicitly() = runBlocking {
        val gateway = Gateway(); val model = NativeTranscriptCoordinator(gateway, this, 0)
        model.runtime(ready); model.open(address, null); model.startObserving(); settle()
        gateway.follows[0].channel.send(snapshot(cursor = 5, more = true, messages = arrayOf(message(5, "five")))); settle()
        gateway.pageResult = { JSONObject().put("records", JSONArray().put(message(5, "outside before"))).put("hasMore", false) }
        model.loadOlder(); settle()
        assertEquals("malformed_response", model.state.value.pagingError); assertEquals(listOf("five"), model.state.value.messages.map { it.text })
        assertTrue(model.state.value.baselineReady); assertTrue(model.state.value.live)
        gateway.pageResult = { JSONObject().put("records", JSONArray().put(message(4, "four"))).put("hasMore", false) }
        model.loadOlder(); settle(); assertNull(model.state.value.pagingError)
        assertEquals(listOf("four", "five"), model.state.value.messages.map { it.text }); model.stopObserving()
    }
    @Test fun disconnectRetainsHistoryAndRejectsAnOldPageAfterFreshBaseline() = runBlocking {
        val gateway = Gateway(); val model = NativeTranscriptCoordinator(gateway, this, 0)
        val delayed = CompletableDeferred<JSONObject>()
        gateway.pageResult = { withContext(NonCancellable) { delayed.await() } }
        model.runtime(ready); model.open(address, null); model.startObserving(); settle()
        gateway.follows[0].channel.send(snapshot(cursor = 1, more = true, messages = arrayOf(message(1, "cached")))); settle()
        model.loadOlder(); settle()
        gateway.follows[0].channel.close(NativeApiException("network_error")); settle()
        assertEquals(listOf("cached"), model.state.value.messages.map { it.text })
        assertFalse(model.state.value.baselineReady); assertFalse(model.state.value.live); assertEquals(2, gateway.follows.size)
        gateway.follows[1].channel.send(snapshot(cursor = 5, more = true, messages = arrayOf(message(5, "fresh")))); settle()
        delayed.complete(JSONObject().put("records", JSONArray().put(message(0, "stale page"))).put("hasMore", false)); settle()
        assertEquals(listOf("fresh"), model.state.value.messages.map { it.text }); assertTrue(model.state.value.baselineReady)
        assertNull(model.state.value.error); model.stopObserving()
    }
    @Test fun unexpectedReaderFailureIsVisibleAndDoesNotBecomeAnAutomaticNetworkRetry() = runBlocking {
        val gateway = Gateway(); val model = NativeTranscriptCoordinator(gateway, this, 0)
        model.runtime(ready); model.open(address, null); model.startObserving(); settle()
        gateway.follows[0].channel.close(IllegalStateException("fixture_reader_bug")); settle()
        assertEquals("native_transcript_failed", model.state.value.error)
        assertFalse(model.state.value.baselineReady); assertEquals(1, gateway.follows.size)
        model.stopObserving()
    }
    @Test fun stoppingRuntimeRetainsVerifiedHistoryWithoutRestartingOrStoppingTheTask() = runBlocking {
        val gateway = Gateway(); val model = NativeTranscriptCoordinator(gateway, this, 0)
        model.runtime(ready); model.open(address, null); model.startObserving(); settle()
        gateway.follows[0].channel.send(snapshot(messages = arrayOf(message(0, "cached")))); settle()
        model.runtime(ready.copy(ready = false)); settle()
        assertEquals(listOf("cached"), model.state.value.messages.map { it.text }); assertFalse(model.state.value.baselineReady)
        assertEquals(1, gateway.follows.size); assertEquals(1, gateway.detached)
        model.runtime(ready); settle(); assertEquals(2, gateway.follows.size); model.stopObserving()
    }
    @Test fun viewportAndLoadedBudgetStayBoundToTheirOwnAddress() = runBlocking {
        val gateway = Gateway(); val model = NativeTranscriptCoordinator(gateway, this, 0)
        model.runtime(ready); model.open(address, "One"); model.startObserving(); settle()
        val position = NativeReadingPosition("message:m0", 27, false)
        model.setReadingPosition(address, position)
        val other = NativeSubagentTranscriptAddress("other-parent", "other-child")
        model.open(other, "Two"); settle(); assertNull(model.state.value.readingPosition)
        model.open(address, "One"); settle(); assertEquals(position, model.state.value.readingPosition)
        model.stopObserving()
    }
}
