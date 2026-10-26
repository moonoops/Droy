package io.droyagent.app.media;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import static org.junit.Assert.*;

public final class MediaInputPolicyTest {
    @Test public void byteLimitCountsUnknownLengthStreamsAndNeverWritesOverflow() throws Exception {
        var output = new ByteArrayOutputStream();
        assertEquals(4, MediaInputPolicy.copy(new ByteArrayInputStream(new byte[]{1,2,3,4}), output, 4));
        assertArrayEquals(new byte[]{1,2,3,4}, output.toByteArray());
        output.reset();
        try { MediaInputPolicy.copy(new ByteArrayInputStream(new byte[]{1,2,3,4,5}), output, 4); fail(); }
        catch (IOException expected) { assertEquals(0, output.size()); }
    }

    @Test public void fileNamesAreDisplayOnlyAndLoseDirectorySeparators() {
        assertEquals(".._secret.txt", MediaInputPolicy.filename("../secret.txt"));
        assertEquals("a_b_c", MediaInputPolicy.filename("a\\b\nc"));
        assertEquals("attachment", MediaInputPolicy.filename(".."));
        assertEquals(160, MediaInputPolicy.filename("x".repeat(200)).length());
    }

    @Test public void incomingUrisOnlyAcceptUnambiguousContentAuthorities() {
        assertTrue(MediaInputPolicy.contentUri("content://example.provider/document/123"));
        assertFalse(MediaInputPolicy.contentUri("file:///data/data/private/file"));
        assertFalse(MediaInputPolicy.contentUri("content://user@example.provider/document/123"));
        assertFalse(MediaInputPolicy.contentUri("content://example.provider/document/123#fragment"));
        assertFalse(MediaInputPolicy.contentUri("https://example.com/file"));
    }

    @Test public void opaqueIdsCannotEscapeTheImportRoot() {
        assertEquals("c9ea4f1c-1aa0-4ca5-9921-419a753bb4a9", MediaInputPolicy.id("c9ea4f1c-1aa0-4ca5-9921-419a753bb4a9"));
        for (String value : new String[]{"../file", "C9EA4F1C-1AA0-4CA5-9921-419A753BB4A9", "1-1-1-1-1"}) {
            try { MediaInputPolicy.id(value); fail(value); } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void textAndSessionAdmissionIsBounded() {
        assertEquals("line1\nline2", MediaInputPolicy.text("line1\nline2"));
        try { MediaInputPolicy.text("x".repeat(100_001)); fail(); } catch (IllegalArgumentException expected) { }
        try { MediaInputPolicy.session("session\nother"); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals("session-123", MediaInputPolicy.session("session-123"));
    }

    @Test public void draftRecoveryOnlyAcceptsCanonicalSha256() {
        assertEquals("abcdef0123456789".repeat(4), MediaInputPolicy.draftHash("abcdef0123456789".repeat(4)));
        for (String value : new String[]{"a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), "../".repeat(22)}) {
            try { MediaInputPolicy.draftHash(value); fail(value); } catch (IllegalArgumentException expected) { }
        }
    }
}
