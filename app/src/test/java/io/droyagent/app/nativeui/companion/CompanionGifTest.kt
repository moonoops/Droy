package io.droyagent.app.nativeui.companion

import androidx.compose.ui.unit.IntSize
import io.droyagent.app.nativeui.companion.ui.companionGifShouldPlay
import io.droyagent.app.nativeui.companion.ui.companionGifSize
import io.droyagent.app.nativeui.companion.ui.companionGifTargetSize
import io.droyagent.app.nativeui.companion.ui.companionIsGif
import io.droyagent.app.nativeui.companion.maintenance.NativeCompanionWriteBarrier
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class CompanionGifTest {
    @Test fun detectionUsesOriginalBytesRatherThanTheUrlExtension() {
        val bytes = header(320, 240)
        assertTrue(companionIsGif(bytes))
        assertEquals(IntSize(320, 240), companionGifSize(bytes))
        assertTrue(companionIsGif(header(1, 1, "GIF87a")))
        assertFalse(companionIsGif("GIF90a".toByteArray()))
        assertFalse(companionIsGif(byteArrayOf(137.toByte(), 80, 78, 71)))
        assertFalse(companionIsGif(byteArrayOf()))
    }

    @Test fun malformedAndExcessiveGifDimensionsDoNotBecomeFirstFrameStillImages() {
        for (bytes in listOf("GIF89a".toByteArray(), header(0, 240), header(320, 0), header(8193, 1), header(8192, 8192))) {
            try { companionGifSize(bytes); fail("Invalid GIF bounds must be rejected") }
            catch (_: IllegalArgumentException) {}
        }
        val oversized = ByteArray(8 * 1024 * 1024 + 1)
        header(1, 1).copyInto(oversized)
        try { companionGifSize(oversized); fail("Oversized GIF must be rejected") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun animationSamplingKeepsAvatarCropAndPreviewAspectRatioContracts() {
        assertEquals(IntSize(540, 960), companionGifTargetSize(IntSize(2160, 3840), 384, 336, null))
        assertEquals(IntSize(1080, 1920), companionGifTargetSize(IntSize(2160, 3840), 2048, null, null))
        assertEquals(IntSize(1080, 2400), companionGifTargetSize(IntSize(2160, 4800), 1024, null, IntSize(1080, 2100)))
        assertEquals(IntSize(32, 32), companionGifTargetSize(IntSize(32, 32), 1024, null, null))
    }

    @Test fun hiddenStoppedDetachedOrRetiredImagesCannotKeepAnimating() {
        assertTrue(companionGifShouldPlay(true, true, true, true, true, true))
        assertFalse(companionGifShouldPlay(false, true, true, true, true, true))
        assertFalse(companionGifShouldPlay(true, false, true, true, true, true))
        assertFalse(companionGifShouldPlay(true, true, false, true, true, true))
        assertFalse(companionGifShouldPlay(true, true, true, false, true, true))
        assertFalse(companionGifShouldPlay(true, true, true, true, false, true))
        assertFalse(companionGifShouldPlay(true, true, true, true, true, false))
    }

    @Test fun freezingAndCacheRetirementNotifyExistingImageOwnersWithoutChangingTheirUrl() = runBlocking {
        val before = NativeCompanionWriteBarrier.imageAccess.value
        val seen = mutableListOf<io.droyagent.app.nativeui.companion.maintenance.NativeCompanionImageAccess>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { NativeCompanionWriteBarrier.imageAccess.take(3).toList(seen) }
        try {
            NativeCompanionWriteBarrier.frozen = !before.frozen
            yield()
            NativeCompanionWriteBarrier.imageGeneration = before.generation + 1
            yield()
            observer.join()
            assertEquals(before, seen[0])
            assertEquals(before.copy(frozen = !before.frozen), seen[1])
            assertEquals(before.copy(frozen = !before.frozen, generation = before.generation + 1), seen[2])
        } finally {
            observer.cancel()
            NativeCompanionWriteBarrier.frozen = before.frozen
            NativeCompanionWriteBarrier.imageGeneration = before.generation
        }
    }

    private fun header(width: Int, height: Int, signature: String = "GIF89a") = ByteArray(13).also {
        signature.toByteArray().copyInto(it)
        it[6] = width.toByte(); it[7] = (width ushr 8).toByte()
        it[8] = height.toByte(); it[9] = (height ushr 8).toByte()
    }
}
