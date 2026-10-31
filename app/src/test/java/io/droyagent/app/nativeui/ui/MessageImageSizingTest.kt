package io.droyagent.app.nativeui.ui

import org.junit.Assert.*
import org.junit.Test

class MessageImageSizingTest {
    @Test fun wideAndTallImagesKeepTheirWholeAspectWithinTheChatPreview() {
        for ((width, height) in listOf(1600 to 900, 900 to 1600, 4000 to 400, 400 to 4000, 1200 to 1200)) {
            val preview = messageImageSize(width, height)
            assertTrue(preview.width <= 240f)
            assertTrue(preview.height <= 220f)
            assertEquals(width.toFloat() / height, preview.width / preview.height, .0001f)
        }
    }

    @Test fun narrowContainersAndMissingMetadataStillHaveBoundedPreviews() {
        val narrow = messageImageSize(1600, 900, maxWidth = 170f)
        assertEquals(170f, narrow.width, .001f)
        val unknown = messageImageSize(null, null)
        assertEquals(240f, unknown.width, .001f)
        assertEquals(180f, unknown.height, .001f)
    }
}
