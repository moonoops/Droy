package io.droyagent.app;

import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

public class HistoryDataTest {
    @Test public void clearingHistoryPreservesConfigurationAndDoesNotFollowLinks() throws Exception {
        Path root = Files.createTempDirectory("droy-history-test");
        try {
            Path home = Files.createDirectories(root.resolve("home"));
            Path settings = Files.createDirectories(home.resolve("profiles/mobile"));
            Files.write(settings.resolve("cordis.patch.yml"), "fixture-model-setting".getBytes());
            Files.write(home.resolve("credentials.json"), "fictional-credential".getBytes());
            Files.createDirectories(home.resolve("sessions"));
            Files.write(home.resolve("sessions/fixture.jsonl"), "fixture-history".getBytes());
            Files.createDirectories(home.resolve("storages"));
            Files.write(home.resolve("storages/workspace.json"), "fixture-history".getBytes());
            Path preserved = Files.createDirectories(root.resolve("outside"));
            Files.write(preserved.resolve("keep"), new byte[]{1});
            Files.createSymbolicLink(home.resolve("attachments"), preserved);
            HistoryData.clear(home);
            assertFalse(Files.exists(home.resolve("sessions")));
            assertFalse(Files.exists(home.resolve("storages/workspace.json")));
            assertTrue(Files.exists(settings.resolve("cordis.patch.yml")));
            assertTrue(Files.exists(home.resolve("credentials.json")));
            assertTrue(Files.exists(preserved.resolve("keep")));
        } finally { HistoryData.deleteTree(root); }
    }
}
