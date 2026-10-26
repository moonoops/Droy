package io.droyagent.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class AttachmentImageCodecTest {
    @Test public void acceptsOnlyCanonicalUuidFileNames() {
        assertEquals("72a14d65-7d7e-4527-b06c-88580c21afbe",
            AttachmentImageCodec.validatedId("72a14d65-7d7e-4527-b06c-88580c21afbe"));
        for (String invalid : new String[] { "../private", "", "1-1-1-1-1", "72A14D65-7D7E-4527-B06C-88580C21AFBE", "72a14d65-7d7e-4527-b06c-88580c21afbe.png" }) {
            assertThrows(IllegalArgumentException.class, () -> AttachmentImageCodec.validatedId(invalid));
        }
    }

    @Test public void preservesSmallImagesAndAspectRatio() {
        assertArrayEquals(new int[] { 300, 200 }, AttachmentImageCodec.targetDimensions(300, 200, 4_194_304, 8192));
        assertArrayEquals(new int[] { 2048, 2048 }, AttachmentImageCodec.targetDimensions(8000, 8000, 4_194_304, 8192));
        assertArrayEquals(new int[] { 1024, 256 }, AttachmentImageCodec.targetDimensions(8000, 2000, 4_194_304, 1024));
    }

    @Test public void extremeAspectRatioStillHonorsPixelLimit() {
        int[] result = AttachmentImageCodec.targetDimensions(8192, 1, 4, 8192);
        assertArrayEquals(new int[] { 4, 1 }, result);
        assertThrows(IllegalArgumentException.class, () -> AttachmentImageCodec.targetDimensions(0, 1, 4, 8192));
    }
}
