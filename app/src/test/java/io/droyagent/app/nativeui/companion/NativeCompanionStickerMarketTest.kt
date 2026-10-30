package io.droyagent.app.nativeui.companion

import io.droyagent.app.nativeui.companion.data.*
import io.droyagent.app.nativeui.companion.maintenance.NativeCompanionWriteBarrier
import io.droyagent.app.nativeui.data.NativeApiException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class NativeCompanionStickerMarketTest {
    private val originalFrozen = NativeCompanionWriteBarrier.frozen
    private val pack = NativeCompanionMarketPack("fictional-market", "原始图包名", "Original source description", 12, 0, "CC0")
    private val receipt = NativeCompanionMarketReceipt(NativeCompanionStickerPack("pack-fictional", "原始图包名", 9), 3)

    @After fun restoreBarrier() { NativeCompanionWriteBarrier.frozen = originalFrozen }

    @Test fun wireParsingPreservesSourceTextAndReportsOnlyConfirmedImageCounts() {
        val rows = NativeCompanionStickerMarketJson.packs(JSONObject().put("packs", JSONArray().put(JSONObject()
            .put("id", pack.id).put("name", pack.name).put("description", pack.description).put("count", 0).put("bytes", 0).put("license", "personal"))))
        assertEquals("原始图包名", rows.single().name)
        assertEquals("Original source description", rows.single().description)
        assertEquals(0, rows.single().count)
        assertEquals(0L, rows.single().bytes)
        val parsed = NativeCompanionStickerMarketJson.receipt(receiptWire())
        assertEquals(9, parsed.pack.count)
        assertEquals(3, parsed.skippedAnimated)
        assertCode("malformed_response") { NativeCompanionStickerMarketJson.receipt(receiptWire().put("installed", 12)) }
        assertCode("malformed_response") { NativeCompanionStickerMarketJson.receipt(receiptWire().put("skippedAnimated", -1)) }
        assertCode("malformed_response") { NativeCompanionStickerMarketJson.receipt(receiptWire().put("pack", JSONObject()
            .put("id", "pack-fictional").put("name", "Empty").put("count", 0))) }
        assertCode("malformed_response") { NativeCompanionStickerMarketJson.packs(JSONObject().put("packs", JSONArray().put(JSONObject()
            .put("id", "bad").put("name", "Bad").put("count", -1)))) }
    }

    @Test fun failedDownloadsRetryForTheCapturedCompanionAndNotifyOnlyAfterSuccess() = runBlocking {
        NativeCompanionWriteBarrier.frozen = false
        var fail = true
        var refreshed = 0
        val owners = mutableListOf<Pair<String, String>>()
        val api = object : NativeCompanionStickerMarketApi {
            override suspend fun search(query: String) = listOf(pack)
            override suspend fun install(companionId: String, packId: String): NativeCompanionMarketReceipt {
                owners += companionId to packId
                if (fail) throw NativeApiException("COMPANION_STICKER_ONLINE_CHECKSUM")
                return receipt
            }
        }
        val controller = NativeCompanionStickerMarketController(this, "captured-owner", api) { refreshed++ }
        controller.search(); yield()
        assertTrue(controller.state.value.loaded)
        assertEquals(listOf(pack), controller.state.value.packs)
        controller.install(pack); yield()
        assertEquals("COMPANION_STICKER_ONLINE_CHECKSUM", controller.state.value.error)
        assertNull(controller.state.value.lastReceipt)
        assertEquals(0, refreshed)
        fail = false
        controller.retry(); yield()
        assertEquals(2, owners.size)
        assertTrue(owners.all { it == ("captured-owner" to pack.id) })
        assertEquals(receipt, controller.state.value.lastReceipt)
        assertEquals(1, refreshed)
        controller.close()
        yield()
        assertTrue(currentCoroutineContext()[Job]!!.children.none())
    }

    @Test fun freezingAdmissionRejectsDownloadsAndClosingCancelsThePendingWriter() = runBlocking {
        var calls = 0
        var refreshed = 0
        val pending = CompletableDeferred<NativeCompanionMarketReceipt>()
        val api = object : NativeCompanionStickerMarketApi {
            override suspend fun search(query: String) = emptyList<NativeCompanionMarketPack>()
            override suspend fun install(companionId: String, packId: String): NativeCompanionMarketReceipt { calls++; return pending.await() }
        }
        val controller = NativeCompanionStickerMarketController(this, "owner", api) { refreshed++ }
        NativeCompanionWriteBarrier.frozen = true
        controller.install(pack); yield(); assertEquals(0, calls)
        NativeCompanionWriteBarrier.frozen = false
        controller.install(pack); yield(); assertEquals(1, calls)
        assertEquals(pack.id, controller.state.value.installingId)
        controller.close(); yield()
        pending.complete(receipt); yield()
        assertEquals(0, refreshed)
        assertNull(controller.state.value.lastReceipt)
        assertTrue(currentCoroutineContext()[Job]!!.children.none())
    }

    @Test fun cancellingARequestDisconnectsTheAuthenticatedLocalConnectionImmediately() = runBlocking {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val disconnected = AtomicBoolean()
        val connection = object : TestConnection() {
            override fun getResponseCode(): Int {
                entered.countDown()
                released.await(5, TimeUnit.SECONDS)
                throw IOException("Cancelled fictional local request")
            }
            override fun disconnect() { disconnected.set(true); released.countDown() }
        }
        val job = launch { exchangeStickerMarketJson({ connection }, { true }, "GET", null) }
        yield()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(disconnected.get())
        assertEquals(240_000, connection.readTimeout)
    }

    @Test fun requestsRejectStaleHostsAndSurfaceTimeouts() = runBlocking {
        val stale = object : TestConnection() {
            override fun getResponseCode() = 200
            override fun getInputStream() = ByteArrayInputStream("{\"packs\":[]}".toByteArray())
        }
        try { exchangeStickerMarketJson({ stale }, { false }, "GET", null); fail("Stale host must fail") }
        catch (error: NativeApiException) { assertEquals("runtime_unavailable", error.code) }
        val timeout = object : TestConnection() { override fun getResponseCode(): Int = throw SocketTimeoutException("Fictional timeout") }
        try { exchangeStickerMarketJson({ timeout }, { true }, "GET", null); fail("Timeout must fail") }
        catch (error: NativeApiException) { assertEquals("COMPANION_STICKER_ONLINE_TIMEOUT", error.code) }
    }

    private fun receiptWire() = JSONObject().put("pack", JSONObject().put("id", receipt.pack.id).put("name", receipt.pack.name)
        .put("count", receipt.pack.count).put("builtin", false)).put("installed", receipt.pack.count).put("skippedAnimated", receipt.skippedAnimated)

    private fun assertCode(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: NativeApiException) { assertEquals(code, error.code) }
    }
    private abstract class TestConnection : HttpURLConnection(URL("http://127.0.0.1/fictional-sticker-test")) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
    }
}
