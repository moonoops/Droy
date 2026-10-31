package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeEndpointPathsTest {
    @Test public void onlyProductRoutesCanReceiveThePrivateRuntimeCookie() {
        for (String path : new String[]{"/droy/native/settings", "/droy/native/speech/operations/fixture",
                "/droy/companions/companion-123/messages?before=older", "/droy/setup/native/identity-confirm",
                "/droy/voice", "/droy/pets/create", "/droy/pets/status?id=12345678-1234-1234-1234-123456789abc",
                "/droy/pets/asset?id=default&file=pet.json", "/droy/pets/asset?id=default&file=atlas_0.png"})
            assertTrue(path, NativeRuntimeHost.allowsPath(path));
    }

    @Test public void foreignOriginsAndAmbiguousPathsCannotReceiveCredentials() {
        for (String path : new String[]{null, "https://example.com/droy/native/settings", "//example.com/droy/native/settings",
                "/droy/native/../setup/complete", "/droy/native/%2e%2e/setup/complete", "/droy/native/%2e%2e%2fsetup/complete",
                "/droy/native/valid\r\nCookie: other", "/droy/native/%0a", "/droy/native/valid#fragment",
                "/droy/native\\settings", "/droy/pets/asset?id=default&file=../../secret",
                "/droy/pets/asset?id=default&file=atlas.png&extra=1", "/droy/pets/delete", "/droy/voice/other",
                "/droy/voice?operation=start", "/droy/setup/complete"})
            assertFalse(String.valueOf(path), NativeRuntimeHost.allowsPath(path));
    }
}
