package io.droyagent.app.nativeui.companion

import io.droyagent.app.nativeui.companion.ui.companionImageSampleSize
import org.junit.Assert.*
import org.junit.Test

class CompanionImageSamplingTest {
    @Test fun portraitAvatarPreservesEnoughPixelsAfterSquareCrop() {
        val sample = companionImageSampleSize(2160, 3840, 384, 336)
        assertEquals(4, sample)
        assertTrue(2160 / sample >= 336)
    }
    @Test fun smallSourceIsNotFurtherReducedForLargeAvatar() {
        assertEquals(1, companionImageSampleSize(128, 128, 384, 336))
    }
    @Test fun fullImagePreviewUsesItsLongEdgeLimit() {
        assertEquals(2, companionImageSampleSize(2160, 3840, 2048))
    }
}
