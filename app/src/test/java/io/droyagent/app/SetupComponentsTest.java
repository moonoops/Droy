package io.droyagent.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SetupComponentsTest {
    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    @Test public void allowsOnlyHttpsOfficialReleaseHosts() throws Exception {
        for (String address : new String[]{
                "https://github.com/RikkaApps/Shizuku/releases/download/v-test/fixture.apk",
                "https://release-assets.githubusercontent.com/github-production-release-asset/fixture?token=fixture",
                "https://objects.githubusercontent.com/fixture", "https://github-releases.githubusercontent.com/fixture"})
            SetupComponents.validateDownloadUrl(address);
        for (String address : new String[]{
                "http://github.com/RikkaApps/Shizuku/fixture.apk", "https://github.com.evil.example/fixture.apk",
                "https://evil.example/github.com/fixture.apk", "https://github.com@evil.example/fixture.apk",
                "https://user@github.com/fixture.apk", "https://github.com:8443/fixture.apk",
                "file:///cache/fixture.apk", "https://github.com/fixture.apk#other", "not a url"})
            assertThrows(address, IOException.class, () -> SetupComponents.validateDownloadUrl(address));
    }

    @Test public void verifiesBytesBeforeReportingCompletion() throws Exception {
        byte[] content = "public synthetic component fixture".repeat(3000).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicLong progress = new AtomicLong();
        assertEquals(content.length, SetupComponents.copyVerified(new ByteArrayInputStream(content), output,
            content.length, sha256(content), () -> true, progress::set));
        assertEquals(content.length, progress.get());
        assertArrayEquals(content, output.toByteArray());
    }

    @Test public void refusesTruncatedOversizedOrSubstitutedData() throws Exception {
        byte[] content = "synthetic component".getBytes(StandardCharsets.UTF_8);
        String digest = sha256(content);
        assertThrows(IOException.class, () -> SetupComponents.copyVerified(new ByteArrayInputStream(content),
            new ByteArrayOutputStream(), content.length + 1, digest, () -> true, bytes -> {}));
        assertThrows(IOException.class, () -> SetupComponents.copyVerified(new ByteArrayInputStream(content),
            new ByteArrayOutputStream(), content.length - 1, digest, () -> true, bytes -> {}));
        byte[] substituted = content.clone(); substituted[0] ^= 1;
        assertThrows(IOException.class, () -> SetupComponents.copyVerified(new ByteArrayInputStream(substituted),
            new ByteArrayOutputStream(), content.length, digest, () -> true, bytes -> {}));
        assertThrows(IOException.class, () -> SetupComponents.copyVerified(new ByteArrayInputStream(content),
            new ByteArrayOutputStream(), 33L * 1024 * 1024, digest, () -> true, bytes -> {}));
    }

    @Test public void cancellationDoesNotProduceACompletedCopy() throws Exception {
        byte[] content = "synthetic cancellation fixture".repeat(4000).getBytes(StandardCharsets.UTF_8);
        AtomicBoolean active = new AtomicBoolean(true);
        String digest = sha256(content);
        assertThrows(IOException.class, () -> SetupComponents.copyVerified(new ByteArrayInputStream(content),
            new ByteArrayOutputStream(), content.length, digest, active::get, bytes -> active.set(false)));
        assertThrows(IOException.class, () -> SetupComponents.copyVerified(new ByteArrayInputStream(content),
            new ByteArrayOutputStream(), content.length, digest, () -> false, bytes -> fail("Progress after cancellation")));
    }

    @Test public void requiresThePinnedSoleSigningCertificate() {
        String official = "a".repeat(64), other = "b".repeat(64);
        assertTrue(SetupComponents.signingMatches(official, official));
        assertFalse(SetupComponents.signingMatches(official, other));
        assertFalse(SetupComponents.signingMatches(official, official, other));
        assertFalse(SetupComponents.signingMatches(official));
        assertFalse(SetupComponents.signingMatches(official, (String) null));
        assertFalse(SetupComponents.signingMatches("invalid", "invalid"));
    }
}
