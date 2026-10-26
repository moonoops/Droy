package io.droyagent.app;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public final class TtsModelsTest {
    private Path directory;

    @Before public void createDirectory() throws Exception { directory = Files.createTempDirectory("tts-download-test"); }
    @After public void removeDirectory() throws Exception {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @Test public void publishesOnlyCompleteVerifiedBytesAndReportsProgress() throws Exception {
        byte[] content = "public test speech model".repeat(30000).getBytes(StandardCharsets.UTF_8);
        File target = directory.resolve("model.onnx").toFile();
        AtomicLong progress = new AtomicLong();
        TtsModels.publish(new ByteArrayInputStream(content), target, content.length, hash(content), () -> false, count -> {
            assertFalse("Final path appeared before verification", target.exists());
            assertTrue(count > progress.getAndSet(count));
        });
        assertEquals(content.length, progress.get());
        assertArrayEquals(content, Files.readAllBytes(target.toPath()));
        assertFalse(directory.resolve("model.onnx.part").toFile().exists());
    }

    @Test public void truncatedOversizedAndWrongHashDownloadsNeverReplaceExistingFile() throws Exception {
        byte[] original = "existing valid file".getBytes(StandardCharsets.UTF_8);
        byte[] download = "new invalid file".getBytes(StandardCharsets.UTF_8);
        File target = directory.resolve("model.onnx").toFile();
        Files.write(target.toPath(), original);
        for (long expectedSize : new long[]{download.length - 1, download.length, download.length + 1}) {
            assertThrows(IOException.class, () -> TtsModels.publish(new ByteArrayInputStream(download), target,
                expectedSize, hash(original), () -> false, count -> {}));
            assertArrayEquals(original, Files.readAllBytes(target.toPath()));
            assertFalse(directory.resolve("model.onnx.part").toFile().exists());
        }
    }

    @Test public void cancellationRemovesPartialAndDoesNotPublish() throws Exception {
        byte[] content = new byte[400000];
        AtomicBoolean cancelled = new AtomicBoolean();
        File target = directory.resolve("model.onnx").toFile();
        assertThrows(CancellationException.class, () -> TtsModels.publish(new ByteArrayInputStream(content), target,
            content.length, hash(content), cancelled::get, count -> cancelled.set(true)));
        assertFalse(target.exists());
        assertFalse(directory.resolve("model.onnx.part").toFile().exists());
    }

    @Test public void listingNeverDownloadsAndUnverifiedFilesCannotBeUsed() throws Exception {
        byte[] content = "public fixture".getBytes(StandardCharsets.UTF_8);
        TtsModels models = new TtsModels(directory.toFile(), manifest("model.onnx", content));
        JSONObject state = models.status().getJSONArray("models").getJSONObject(0);
        assertEquals("not_downloaded", state.getString("state"));
        assertFalse(state.getBoolean("ready"));
        assertEquals(content.length, state.getLong("totalBytes"));
        try (var paths = Files.list(directory)) { assertEquals(0, paths.count()); }
        Path installed = directory.resolve("melo-test"); Files.createDirectories(installed);
        Files.write(installed.resolve("model.onnx"), content);
        assertThrows(IllegalStateException.class, () -> models.requireReady("melo"));
        assertThrows(IllegalArgumentException.class, () -> models.download("unknown"));
    }

    @Test public void rejectsEscapingNamesAndNonHttpsSources() throws Exception {
        byte[] content = "fixture".getBytes(StandardCharsets.UTF_8);
        for (String name : new String[]{"../model.onnx", "/model.onnx", ".."})
            assertThrows(IOException.class, () -> new TtsModels(directory.toFile(), manifest(name, content)));
        String source = new String(manifest("model.onnx", content), StandardCharsets.UTF_8).replace("https://", "http://");
        assertThrows(IOException.class, () -> new TtsModels(directory.toFile(), source.getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void verifiedInstallationSurvivesRestartButMissingFileInvalidatesReadiness() throws Exception {
        byte[] content = "public fixture".getBytes(StandardCharsets.UTF_8);
        byte[] manifest = manifest("model.onnx", content);
        JSONObject specification = new JSONObject(new String(manifest, StandardCharsets.UTF_8)).getJSONArray("models").getJSONObject(0);
        Path installed = directory.resolve("melo-test"); Files.createDirectories(installed);
        Files.write(installed.resolve("model.onnx"), content);
        Files.write(installed.resolve(".verified"), hash(specification.toString().getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        TtsModels models = new TtsModels(directory.toFile(), manifest);
        assertTrue(models.status().getJSONArray("models").getJSONObject(0).getBoolean("ready"));
        assertEquals(installed.toFile(), models.requireReady("melo"));
        Files.delete(installed.resolve("model.onnx"));
        assertFalse(models.status().getJSONArray("models").getJSONObject(0).getBoolean("ready"));
        assertThrows(IllegalStateException.class, () -> models.requireReady("melo"));
    }

    private static byte[] manifest(String name, byte[] content) throws Exception {
        JSONObject asset = new JSONObject().put("name", name).put("bytes", content.length).put("sha256", hash(content))
            .put("url", "https://example.invalid/model.onnx");
        JSONObject model = new JSONObject().put("provider", "melo").put("name", "MeloTTS 中文")
            .put("directory", "melo-test").put("version", "fixture").put("files", new JSONArray().put(asset));
        return new JSONObject().put("models", new JSONArray().put(model)).toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x", value));
        return result.toString();
    }
}
