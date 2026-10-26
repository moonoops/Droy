package io.droyagent.androiduse;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class SelectedFileToolsTest {
    @Test public void exportAcceptsNamesButRejectsPathsAndControlCharacters() throws Exception {
        assertEquals("日程.txt", SelectedFileTools.exportName("日程.txt"));
        for (String invalid : new String[]{"../secret", "/file", "a\\b", "..", "", " file", "file\n"})
            assertThrows(invalid, ActionException.class, () -> SelectedFileTools.exportName(invalid));
    }

    @Test public void referencesCannotContainModelSuppliedUrisOrFilesystemPaths() {
        assertThrows(ActionException.class, () -> FileGrantStore.validateId("content://provider/document/123", "fileId"));
        assertThrows(ActionException.class, () -> FileGrantStore.validateId("/data/data/app/file", "fileId"));
        assertThrows(ActionException.class, () -> FileGrantStore.validateId(null, "fileId"));
    }

    @Test public void readsBoundedUtf8WithHonestTruncation() throws Exception {
        SelectedFileTools.TextRead read = SelectedFileTools.readUtf8(
                new ByteArrayInputStream("你好世界".getBytes(StandardCharsets.UTF_8)), 3, () -> {});
        assertEquals("你好世", read.text);
        assertTrue(read.truncated);
        SelectedFileTools.TextRead exact = SelectedFileTools.readUtf8(
                new ByteArrayInputStream("你好".getBytes(StandardCharsets.UTF_8)), 2, () -> {});
        assertEquals("你好", exact.text);
        assertFalse(exact.truncated);
    }

    @Test public void truncationDoesNotReturnHalfAnEmoji() throws Exception {
        SelectedFileTools.TextRead read = SelectedFileTools.readUtf8(
                new ByteArrayInputStream("A😀B".getBytes(StandardCharsets.UTF_8)), 2, () -> {});
        assertEquals("A", read.text);
        assertTrue(read.truncated);
    }

    @Test public void rejectsMalformedUtf8AndBinaryNul() {
        assertThrows(ActionException.class, () -> SelectedFileTools.readUtf8(
                new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28}), 20, () -> {}));
        assertThrows(ActionException.class, () -> SelectedFileTools.readUtf8(
                new ByteArrayInputStream(new byte[]{65, 0, 66}), 20, () -> {}));
    }

    @Test public void cancellationAbortsReads() {
        assertThrows(InterruptedException.class, () -> SelectedFileTools.readUtf8(
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)), 20,
                () -> { throw new InterruptedException("stopped"); }));
    }

    @Test public void binaryMimeIsNotClaimedAsReadableText() {
        assertTrue(SelectedFileTools.textMime("text/plain"));
        assertTrue(SelectedFileTools.textMime("application/json"));
        assertFalse(SelectedFileTools.textMime("application/pdf"));
        assertFalse(SelectedFileTools.textMime("image/png"));
    }
}
