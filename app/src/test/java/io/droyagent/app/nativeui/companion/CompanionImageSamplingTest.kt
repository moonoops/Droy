package io.droyagent.app.nativeui.companion

import io.droyagent.app.nativeui.companion.ui.companionImageSampleSize
import io.droyagent.app.nativeui.companion.ui.companionImageCropSampleSize
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
    @Test fun chatWallpaperPreservesNativePixelsWhenSmallerThanViewport() {
        assertEquals(1, companionImageCropSampleSize(841, 1870, 1080, 2100))
        assertEquals(1, companionImageCropSampleSize(841, 1870, 750, 1668))
    }
    @Test fun wallpaperDoesNotHalvePixelsJustToStayBelowALongEdgeLimit() {
        assertEquals(1, companionImageCropSampleSize(1080, 2400, 1080, 2100))
        assertEquals(1, companionImageCropSampleSize(1440, 3200, 1080, 2100))
    }
    @Test fun largeUploadIsReducedOnlyWhileBothCropDimensionsRemainSufficient() {
        val sample = companionImageCropSampleSize(4000, 4000, 1080, 2100)
        assertEquals(1, sample)
        assertTrue(4000 / sample >= 2100)
        assertEquals(2, companionImageCropSampleSize(2160, 4800, 1080, 2100))
        assertEquals(4, companionImageCropSampleSize(8192, 8192, 300, 2000))
    }
}
