package io.droyagent.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;
import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AttachmentImageCodecDeviceTest {
    private Context context;
    private Path root;
    private Path directory;

    @Before public void setUp() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        root = Files.createTempDirectory(target.getCacheDir().toPath(), "attachment-codec-");
        context = new ContextWrapper(target) {
            @Override public java.io.File getFilesDir() { return root.toFile(); }
        };
        directory = context.getFilesDir().toPath().resolve("dsh-home/attachments/native");
        Files.createDirectories(directory);
    }

    @After public void cleanUp() throws Exception {
        if (root != null) try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
        }
    }

    @Test public void decodesSupportedFormatsAndProducesStandardPng() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(0xff3f7fa1);
            Bitmap.CompressFormat[] formats = { Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.WEBP_LOSSLESS };
            String[] types = { "image/png", "image/jpeg", "image/webp" };
            for (int i = 0; i < formats.length; i++) {
                String id = stage(bitmap, formats[i]);
                JSONObject result = AttachmentImageCodec.normalize(context, request(id, types[i]));
                assertEquals(60, result.getInt("width"));
                assertEquals(40, result.getInt("height"));
                assertPng(id, result);
            }
            String gif = newId();
            Files.write(directory.resolve(gif + ".source"), Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", Base64.DEFAULT));
            JSONObject result = AttachmentImageCodec.normalize(context, request(gif, "image/gif"));
            assertEquals(1, result.getInt("width"));
            assertPng(gif, result);
        } finally { bitmap.recycle(); }
    }

    @Test public void appliesExifOrientationBeforeReportingDimensions() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888);
        String id;
        try { id = stage(bitmap, Bitmap.CompressFormat.JPEG); }
        finally { bitmap.recycle(); }
        ExifInterface exif = new ExifInterface(directory.resolve(id + ".source").toString());
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
        exif.saveAttributes();
        JSONObject result = AttachmentImageCodec.normalize(context, request(id, "image/jpeg"));
        assertEquals(40, result.getInt("originalWidth"));
        assertEquals(80, result.getInt("originalHeight"));
        assertEquals(40, result.getInt("width"));
        assertEquals(80, result.getInt("height"));
    }

    @Test public void shrinksNoisyImagesToMeetEncodedByteLimit() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        Random random = new Random(71);
        int[] pixels = new int[4096];
        for (int i = 0; i < pixels.length; i++) pixels[i] = random.nextInt() | 0xff000000;
        bitmap.setPixels(pixels, 0, 64, 0, 0, 64, 64);
        String id;
        try { id = stage(bitmap, Bitmap.CompressFormat.PNG); }
        finally { bitmap.recycle(); }
        JSONObject request = request(id, "image/png").put("target", new JSONObject().put("maxBytes", 512));
        JSONObject result = AttachmentImageCodec.normalize(context, request);
        assertTrue(result.getInt("bytes") <= 512);
        assertTrue(result.getInt("width") < 64);
        assertEquals(64, result.getInt("originalWidth"));
        assertPng(id, result);
    }

    @Test public void rejectsMismatchCorruptionOversizeAndSymlinks() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        String id;
        try { id = stage(bitmap, Bitmap.CompressFormat.PNG); }
        finally { bitmap.recycle(); }
        assertThrows(IllegalArgumentException.class, () -> AttachmentImageCodec.normalize(context, request(id, "image/jpeg")));
        JSONObject limited = request(id, "image/png").put("limits", new JSONObject().put("maxImageBytes", 4));
        assertThrows(IllegalArgumentException.class, () -> AttachmentImageCodec.normalize(context, limited));
        JSONObject dimensions = request(id, "image/png").put("limits", new JSONObject().put("maxImagePixels", 1));
        assertThrows(IllegalArgumentException.class, () -> AttachmentImageCodec.normalize(context, dimensions));
        String corrupt = newId();
        Files.write(directory.resolve(corrupt + ".source"), new byte[] { 1, 2, 3 });
        assertThrows(IllegalArgumentException.class, () -> AttachmentImageCodec.normalize(context, request(corrupt, "image/png")));
        String link = newId();
        Files.createSymbolicLink(directory.resolve(link + ".source"), directory.resolve(id + ".source"));
        assertThrows(IllegalArgumentException.class, () -> AttachmentImageCodec.normalize(context, request(link, "image/png")));
        byte[] sentinel = { 7, 8, 9 };
        Files.write(directory.resolve(id + ".png"), sentinel);
        assertThrows(java.io.IOException.class, () -> AttachmentImageCodec.normalize(context, request(id, "image/png")));
        assertArrayEquals(sentinel, Files.readAllBytes(directory.resolve(id + ".png")));
    }

    @Test public void rejectsSymlinkStagingDirectory() throws Exception {
        Path alternate = Files.createDirectory(root.resolve("alternate"));
        Files.delete(directory);
        Files.createSymbolicLink(directory, alternate);
        String id = newId();
        assertThrows(IllegalArgumentException.class, () -> AttachmentImageCodec.normalize(context, request(id, "image/png")));
    }

    private String newId() { return UUID.randomUUID().toString(); }

    private String stage(Bitmap bitmap, Bitmap.CompressFormat format) throws Exception {
        String id = newId();
        try (OutputStream output = Files.newOutputStream(directory.resolve(id + ".source"))) {
            assertTrue(bitmap.compress(format, 90, output));
        }
        return id;
    }

    private JSONObject request(String id, String type) throws Exception {
        return new JSONObject().put("id", id).put("mediaType", type);
    }

    private void assertPng(String id, JSONObject result) throws Exception {
        byte[] png = Files.readAllBytes(directory.resolve(id + ".png"));
        assertEquals("image/png", result.getString("mediaType"));
        assertEquals(png.length, result.getInt("bytes"));
        assertEquals("8-bit PNG", 8, png[24]);
        assertTrue("RGB or RGBA PNG", png[25] == 2 || png[25] == 6);
        assertEquals("Non-interlaced PNG", 0, png[28]);
        Bitmap decoded = BitmapFactory.decodeByteArray(png, 0, png.length);
        assertNotNull(decoded);
        try {
            assertEquals(result.getInt("width"), decoded.getWidth());
            assertEquals(result.getInt("height"), decoded.getHeight());
        } finally { decoded.recycle(); }
    }
}
