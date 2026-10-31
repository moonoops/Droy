package io.droyagent.app;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import static org.junit.Assert.*;

public class NativePetStudioHostTest {
    @Test public void previewPathsCannotEscapeTheAuthenticatedAssetEndpoint() {
        String id = "a00b0000-0000-4000-8000-000000000001";
        assertEquals("/droy/pets/asset?id=" + id + "&file=pet.json", NativePetStudioHost.assetPath(id, "pet.json"));
        assertEquals("/droy/pets/asset?id=default&file=idle.png", NativePetStudioHost.assetPath("default", "idle.png"));
        for (String file : new String[]{"../pet.json", "/private/data", "a.png?key=x", "a.png#fragment", "https://example.test/image.png", "pet.js"})
            assertThrows(IllegalArgumentException.class, () -> NativePetStudioHost.assetPath(id, file));
        for (String bad : new String[]{"../default", "default&file=private", "", "https://example.test"})
            assertThrows(IllegalArgumentException.class, () -> NativePetStudioHost.assetPath(bad, "pet.json"));
    }
    @Test public void assetReadsStopAtTheirBudget() throws Exception {
        assertArrayEquals(new byte[]{1,2,3}, NativePetStudioHost.bounded(new ByteArrayInputStream(new byte[]{1,2,3}), 3));
        assertThrows(IOException.class, () -> NativePetStudioHost.bounded(new ByteArrayInputStream(new byte[4096]), 1024));
    }
    @Test public void previewSamplingKeepsPublishedAtlasPixelsWithinBudget() {
        for (long pixels : new long[]{1, 4_000_000, 8_000_000, 32_000_000}) {
            int sample = NativePetStudioHost.sampleSize(pixels);
            assertEquals(0, sample & (sample-1));
            assertTrue(pixels / (sample * sample) <= 4_000_000);
        }
    }
    @Test public void cancelledPreviewReadDoesNotKeepCopyingAssets() {
        Thread.currentThread().interrupt();
        try { assertThrows(java.io.InterruptedIOException.class, () -> NativePetStudioHost.bounded(new ByteArrayInputStream(new byte[4096]), 8192)); }
        finally { Thread.interrupted(); }
    }
}
