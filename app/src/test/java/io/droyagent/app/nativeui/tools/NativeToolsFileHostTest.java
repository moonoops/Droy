package io.droyagent.app;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class NativeToolsFileHostTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File cache() throws IOException { return temporary.newFolder(); }
    private static String hash(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    @Test public void onlyCompleteVerifiedCopiesBecomeReadableAndSameNamesStayIndependent() throws Exception {
        File cache = cache(), first, second; byte[] bytes = "Fictional 内容".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (NativeToolsFileHost.Transfer transfer = NativeToolsFileHost.begin(cache, "../report.txt", bytes.length)) {
            transfer.append(java.util.Arrays.copyOfRange(bytes, 0, 4)); transfer.append(java.util.Arrays.copyOfRange(bytes, 4, bytes.length)); first = transfer.commit(hash(bytes));
        }
        try (NativeToolsFileHost.Transfer transfer = NativeToolsFileHost.begin(cache, "../report.txt", bytes.length)) { transfer.append(bytes); second = transfer.commit(hash(bytes)); }
        assertArrayEquals(bytes, Files.readAllBytes(first.toPath())); assertArrayEquals(bytes, Files.readAllBytes(second.toPath()));
        assertNotEquals(first.getParentFile(), second.getParentFile()); assertEquals(".._report.txt", first.getName());
        assertEquals(new File(cache, "native-tools-exports").getCanonicalFile(), first.getParentFile().getParentFile());
    }
    @Test public void truncatedCorruptAndOversizedCopiesLeaveNoReadablePartial() throws Exception {
        File cache = cache();
        try (NativeToolsFileHost.Transfer transfer = NativeToolsFileHost.begin(cache, "truncated.bin", 3)) { transfer.append(new byte[] {1, 2}); assertThrows(IOException.class, () -> transfer.commit(null)); }
        try (NativeToolsFileHost.Transfer transfer = NativeToolsFileHost.begin(cache, "corrupt.bin", 3)) { transfer.append(new byte[] {1, 2, 3}); assertThrows(IOException.class, () -> transfer.commit(hash(new byte[] {1, 2, 4}))); }
        try (NativeToolsFileHost.Transfer transfer = NativeToolsFileHost.begin(cache, "overflow.bin", 3)) { assertThrows(IOException.class, () -> transfer.append(new byte[4])); }
        assertEquals(0, new File(cache, "native-tools-exports").list().length);
    }
    @Test public void unknownSizeStillHasTheActualStreamCapAndEmptyFilesRemainValid() throws Exception {
        File cache = cache();
        assertThrows(IOException.class, () -> NativeToolsFileHost.begin(cache, "too-big", NativeToolsFileHost.MAX_BYTES + 1));
        try (NativeToolsFileHost.Transfer transfer = NativeToolsFileHost.begin(cache, "unknown", -1)) {
            assertThrows(IOException.class, () -> transfer.append(new byte[(int) NativeToolsFileHost.MAX_BYTES + 1]));
        }
        try (NativeToolsFileHost.Transfer transfer = NativeToolsFileHost.begin(cache, "empty", 0)) { assertEquals(0, transfer.commit(hash(new byte[0])).length()); }
    }
    @Test public void abandoningATransferRemovesOnlyItsOwnIncompleteCopy() throws Exception {
        File cache = cache(), shared = new File(cache, "shared-attachment"); Files.write(shared.toPath(), "keep".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try (NativeToolsFileHost.Transfer transfer = NativeToolsFileHost.begin(cache, "abandoned", 2)) { transfer.append(new byte[] {1}); }
        assertEquals("keep", new String(Files.readAllBytes(shared.toPath()), java.nio.charset.StandardCharsets.UTF_8)); assertEquals(0, new File(cache, "native-tools-exports").list().length);
    }
    @Test public void expiryNeverTraversesAnOutsideDirectorySymlink() throws Exception {
        File cache = cache(), outside = temporary.newFolder(); Path keep = new File(outside, "keep").toPath(); Files.write(keep, "keep".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File root = new File(cache, "native-tools-exports"); assertTrue(root.mkdir());
        Path link = new File(root, UUID.randomUUID().toString()).toPath(); Files.createSymbolicLink(link, outside.toPath());
        outside.setLastModified(System.currentTimeMillis() - 48L * 60 * 60 * 1000);
        try (NativeToolsFileHost.Transfer ignored = NativeToolsFileHost.begin(cache, "current", 0)) { assertEquals("keep", new String(Files.readAllBytes(keep), java.nio.charset.StandardCharsets.UTF_8)); }
        assertTrue(Files.isSymbolicLink(link));
    }
}
