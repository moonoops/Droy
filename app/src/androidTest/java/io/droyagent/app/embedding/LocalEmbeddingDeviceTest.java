package io.droyagent.app.embedding;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class LocalEmbeddingDeviceTest {
    @Test public void pinnedModelMatchesReferenceAndRequestContract() throws Exception {
        assumeTrue("Explicit public-fixture authorization required", "true".equals(
            InstrumentationRegistry.getArguments().getString("embeddingFixture")));
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("Development package only", app.getPackageName().endsWith(".dev"));
        JSONObject reference;
        try (InputStream stream = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("embedding-reference.json")) {
            reference = new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        JSONObject manifest;
        try (InputStream stream = app.getAssets().open("embedding/e5-small/manifest.json")) {
            manifest = new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(reference.getString("fingerprint"), manifest.getString("fingerprint"));
        File directory = new File(app.getCacheDir(), "embedding-fixture-" + UUID.randomUUID());
        assertTrue(directory.mkdir());
        long started = SystemClock.elapsedRealtime();
        try {
            JSONArray files = manifest.getJSONArray("files");
            for (int i = 0; i < files.length(); ++i) {
                JSONObject file = files.getJSONObject(i);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = app.getAssets().open("embedding/e5-small/" + file.getString("name"));
                     FileOutputStream output = new FileOutputStream(new File(directory, file.getString("name")))) {
                    byte[] bytes = new byte[65536]; int count;
                    while ((count = input.read(bytes)) >= 0) { digest.update(bytes, 0, count); output.write(bytes, 0, count); }
                }
                StringBuilder hash = new StringBuilder();
                for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                assertEquals(file.getString("sha256"), hash.toString());
            }
            try (NativeEmbeddingEngine engine = new NativeEmbeddingEngine(directory.getAbsolutePath())) {
                JSONArray fixtures = reference.getJSONArray("fixtures");
                for (int i = 0; i < fixtures.length(); ++i) {
                    JSONObject fixture = fixtures.getJSONObject(i);
                    String text = fixture.getString("text");
                    JSONArray expectedTokens = fixture.getJSONArray("tokens");
                    int[] actualTokens = engine.tokens(text);
                    assertEquals("Token count " + i, expectedTokens.length(), actualTokens.length);
                    for (int token = 0; token < actualTokens.length; ++token)
                        assertEquals("Token " + i + ":" + token, expectedTokens.getInt(token), actualTokens[token]);
                    if (fixture.has("vector")) {
                        float[] vector = engine.embed(text);
                        JSONArray expected = fixture.getJSONArray("vector");
                        assertEquals(384, vector.length);
                        double norm = 0, referenceNorm = 0, dot = 0;
                        for (int j = 0; j < vector.length; ++j) {
                            assertTrue(Float.isFinite(vector[j]));
                            double target = expected.getDouble(j);
                            norm += vector[j] * vector[j]; referenceNorm += target * target; dot += vector[j] * target;
                        }
                        assertEquals(1, norm, 0.0001);
                        assertTrue("Vector equivalence " + i, dot / Math.sqrt(norm * referenceNorm) > 0.995);
                    }
                }
            }
        } finally {
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) assertTrue(file.delete());
            assertTrue(directory.delete());
        }
        EmbeddingRuntime runtime = EmbeddingRuntime.get(app);
        JSONObject status = runtime.request(new JSONObject().put("operation", "status"));
        assertTrue(status.getBoolean("ready"));
        assertEquals(reference.getString("fingerprint"), status.getString("fingerprint"));
        JSONObject request = new JSONObject().put("operation", "embed").put("requestId", "fixture-query")
            .put("memorySpaceId", "fixture-space").put("epoch", 7).put("role", "query")
            .put("texts", new JSONArray().put("我喜欢喝不加糖的咖啡。"))
            .put("deadlineAt", System.currentTimeMillis() + 120000);
        JSONObject result = runtime.request(request);
        assertEquals("fixture-space", result.getString("memorySpaceId"));
        assertEquals(7, result.getLong("epoch"));
        assertEquals(384, result.getJSONArray("vectors").getJSONArray(0).length());
        JSONObject boundary = new JSONObject(request.toString()).put("requestId", "fixture-boundary")
            .put("role", "document").put("texts", new JSONArray().put("a".repeat(16384)));
        assertEquals(384, runtime.request(boundary).getJSONArray("vectors").getJSONArray(0).length());
        boundary.put("texts", new JSONArray().put("a".repeat(16385)));
        try { runtime.request(boundary); fail("Oversized source must be rejected before inference"); }
        catch (IllegalArgumentException expected) { assertEquals("EMBEDDING_INVALID_INPUT", expected.getMessage()); }
        request.put("modelPath", "/untrusted/model.onnx");
        try { runtime.request(request); fail("Caller must not choose a model path"); }
        catch (IllegalArgumentException expected) { assertEquals("EMBEDDING_INVALID_INPUT", expected.getMessage()); }
        Bundle report = new Bundle();
        report.putString("embeddingFixtureModel", EmbeddingRuntime.MODEL);
        report.putLong("embeddingFixtureElapsedMs", SystemClock.elapsedRealtime() - started);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
    }
}
