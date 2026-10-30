package io.droyagent.app.nativeui.media;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.app.media.MediaInputStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class NativeDraftMediaStoreDeviceTest {
    @Test public void nativeNamespaceAndControlledReaderCannotAdoptLegacyOrForeignManifestMembers() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(target.getPackageName().endsWith(".dev"));
        File home = new File(target.getCacheDir(), "native-draft-test-" + UUID.randomUUID());
        assertTrue(home.mkdirs());
        Context isolated = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return home; }
        };
        try {
            MediaInputStore nativeStore = MediaInputStore.nativeDrafts(isolated);
            MediaInputStore legacyStore = new MediaInputStore(isolated);
            MediaInputStore creationStore = MediaInputStore.nativeCompanionCreation(isolated);
            assertNotEquals(nativeStore.root(), legacyStore.root());
            assertNotEquals(nativeStore.root(), creationStore.root());
            assertNotEquals(legacyStore.root(), creationStore.root());
            String id = UUID.randomUUID().toString(), item = UUID.randomUUID().toString();
            File folder = new File(nativeStore.root(), id); assertTrue(folder.mkdirs());
            byte[] content = "test".getBytes(StandardCharsets.UTF_8);
            Files.write(new File(folder, item + ".blob").toPath(), content);
            JSONObject manifest = new JSONObject().put("id", id).put("source", "share").put("consumer", "work")
                .put("text", "fixture").put("items", new JSONArray().put(new JSONObject().put("id", item)
                    .put("name", "fixture.txt").put("mediaType", "text/plain").put("bytes", content.length)));
            Files.write(new File(folder, "manifest.json").toPath(), manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(0, legacyStore.list().length());
            assertEquals(0, creationStore.list().length());
            assertThrows(Exception.class, () -> creationStore.openItem(id, item, "work"));
            try (var input = nativeStore.openItem(id, item, "work")) { assertArrayEquals(content, input.readAllBytes()); }
            assertThrows(Exception.class, () -> nativeStore.openItem(id, UUID.randomUUID().toString(), "work"));
            assertThrows(Exception.class, () -> nativeStore.openItem(id, item, "companion"));
            assertThrows(Exception.class, () -> nativeStore.openItem(id, "../outside", "work"));
            File blob = new File(folder, item + ".blob"); Files.delete(blob.toPath());
            File outside = new File(home, "outside"); Files.write(outside.toPath(), content);
            Files.createSymbolicLink(blob.toPath(), outside.toPath());
            assertThrows(Exception.class, () -> nativeStore.openItem(id, item, "work"));
        } finally {
            try (var paths = Files.walk(home.toPath())) {
                var iterator = paths.sorted(Comparator.reverseOrder()).iterator();
                while (iterator.hasNext()) Files.delete(iterator.next());
            }
        }
    }
}
