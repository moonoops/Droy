package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VoicePcmTest {
    @Test public void meterUsesSignedLittleEndianSamplesAndSilenceIsZero() {
        assertEquals(0, VoicePcm.level(new byte[0]), 0);
        assertEquals(0, VoicePcm.level(new byte[960]), 0);
        assertEquals(0.5, VoicePcm.level(new byte[]{0,64,0,-64}), 0.000001);
        assertEquals(1, VoicePcm.level(new byte[]{0,-128}), 0);
    }

    @Test public void playbackAcceptsProviderRatesButRejectsBrokenOrUnboundedChunks() {
        VoicePcm.requirePlayback(new byte[4800], 24000);
        VoicePcm.requirePlayback(new byte[48000], 48000);
        assertThrows(IllegalArgumentException.class, () -> VoicePcm.requirePlayback(new byte[1], 24000));
        assertThrows(IllegalArgumentException.class, () -> VoicePcm.requirePlayback(new byte[0], 24000));
        assertThrows(IllegalArgumentException.class, () -> VoicePcm.requirePlayback(new byte[96002], 24000));
        assertThrows(IllegalArgumentException.class, () -> VoicePcm.requirePlayback(new byte[100], 0));
        assertThrows(IllegalArgumentException.class, () -> VoicePcm.level(new byte[3]));
    }
}
