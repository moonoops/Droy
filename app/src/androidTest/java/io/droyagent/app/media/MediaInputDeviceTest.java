package io.droyagent.app.media;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.app.TestPageActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

/** Synthetic fixtures exercise import boundaries without a camera or model request. */
@RunWith(AndroidJUnit4.class)
public final class MediaInputDeviceTest {
    private final List<Uri> media = new ArrayList<>();
    private final List<String> imports = new ArrayList<>();
    private Context context;
    private MediaInputStore store;
    private Activity activity;
    private MediaInputController controller;
    private Instrumentation instrumentation;
    private Instrumentation.ActivityMonitor picker;
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aPZkAAAAASUVORK5CYII=");

    @Before public void setup() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext(); store = new MediaInputStore(context);
    }

    @After public void cleanup() throws Exception {
        if (picker != null) instrumentation.removeMonitor(picker);
        if (activity != null) instrumentation.runOnMainSync(() -> { if (controller != null) controller.close(); activity.finish(); });
        for (String id : imports) try { store.complete(id); } catch (Exception ignored) { }
        for (Uri uri : media) {
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.getContentResolver().delete(uri, null, null);
        }
    }

    @Test public void tenImagesAreAdmittedAndElevenAreRejectedAsWholeBatch() throws Exception {
        ArrayList<Uri> selected = new ArrayList<>();
        for (int i = 0; i < 11; i++) selected.add(picture());
        JSONObject admitted = store.importUris("album", "fixture-session", selected.subList(0, 10), "", true, 10);
        imports.add(admitted.getString("id")); assertEquals(10, admitted.getJSONArray("items").length());
        int before = store.list().length();
        try { store.importUris("album", "fixture-session", selected, "", true, 10); fail(); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("10")); }
        assertEquals(before, store.list().length());
    }

    @Test public void sameOperationReplaysOneManifestAndMixedClipPayloadDeduplicatesUris() throws Exception {
        Uri image = picture();
        Intent incoming = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, image);
        incoming.setClipData(ClipData.newUri(context.getContentResolver(), "fixture", image));
        assertEquals(1, MediaInputController.collectUris(incoming).size());
        assertTrue(MediaInputController.collectUris(new Intent(Intent.ACTION_SEND)
            .setData(Uri.parse("https://example.invalid/shared")).putExtra(Intent.EXTRA_TEXT, "Shared link")).isEmpty());
        String operationId = UUID.randomUUID().toString();
        JSONObject first = store.importUrisForOperation(operationId, "album", "fixture-session", List.of(image), "", true, 10);
        imports.add(first.getString("id"));
        JSONObject repeated = store.importUrisForOperation(operationId, "album", "fixture-session", List.of(image), "", true, 10);
        assertEquals(first.toString(), repeated.toString());
    }

    @Test public void textOnlyShareBindsAndPreservesTextAppliedAcrossStoreInstances() throws Exception {
        JSONObject shared = store.importUris("share", null, List.of(), "Synthetic title\nhttps://example.invalid/", false, 10);
        imports.add(shared.getString("id"));
        store.bind(shared.getString("id"), "fixture-session", null, true, "abcdef0123456789".repeat(4));
        JSONObject prepared = find(new MediaInputStore(context).list(), shared.getString("id"));
        assertTrue(prepared.getBoolean("textPrepared"));
        assertEquals("abcdef0123456789".repeat(4), prepared.getString("textBeforeHash"));
        assertFalse(prepared.getBoolean("textApplied"));
        store.bind(shared.getString("id"), "fixture-session", true);
        JSONArray restored = new MediaInputStore(context).list();
        JSONObject found = find(restored, shared.getString("id"));
        assertTrue(found.getBoolean("textApplied")); assertEquals("fixture-session", found.getString("targetSessionId"));
        assertTrue(found.getBoolean("textPrepared")); assertEquals(prepared.getString("textBeforeHash"), found.getString("textBeforeHash"));
        assertEquals("Synthetic title\nhttps://example.invalid/", found.getString("text"));
        try { store.bind(shared.getString("id"), "other-session", true); fail(); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("其他对话")); }
    }

    @Test public void invalidTextRecoveryHashCannotPartiallyBindImport() throws Exception {
        JSONObject shared = store.importUris("share", null, List.of(), "Synthetic shared text", false, 10);
        String id = shared.getString("id"); imports.add(id);
        for (String invalid : new String[]{"short", "F".repeat(64)}) {
            try { store.bind(id, "fixture-session", null, true, invalid); fail(); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("校验值")); }
        }
        try { store.bind(id, "fixture-session", null, null, "a".repeat(64)); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("准备标记")); }
        JSONObject restored = find(new MediaInputStore(context).list(), id);
        assertFalse(restored.has("targetSessionId")); assertFalse(restored.has("textPrepared")); assertFalse(restored.has("textBeforeHash"));
        assertFalse(restored.getBoolean("textApplied"));
    }

    @Test public void cameraOriginalIsSavedOnceAndSurvivesDraftRemoval() throws Exception {
        File captures = new File(context.getFilesDir(), "media-input/captures"); assertTrue(captures.isDirectory() || captures.mkdirs());
        String captureId = UUID.randomUUID().toString(); File photo = new File(captures, captureId + ".jpg"); Files.write(photo.toPath(), PNG);
        JSONObject result = store.importCapture(photo, "fixture-session", 10, "DroyMediaInputTests");
        imports.add(result.getString("id")); assertTrue(result.optString("albumError"), result.getBoolean("albumSaved"));
        assertEquals("image/png", result.getString("originalMediaType"));
        assertTrue(result.getString("originalName").endsWith(".png"));
        assertTrue(result.getJSONArray("items").getJSONObject(0).getString("name").endsWith(".png"));
        assertEquals("image/png", result.getJSONArray("items").getJSONObject(0).getString("mediaType"));
        Uri album = Uri.parse(result.getString("albumUri")); media.add(album);
        try (var cursor = context.getContentResolver().query(album,
                new String[]{MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE}, null, null, null)) {
            assertNotNull(cursor); assertTrue(cursor.moveToFirst());
            assertTrue(cursor.getString(0).endsWith(".png")); assertEquals("image/png", cursor.getString(1));
        }
        try (var input = context.getContentResolver().openInputStream(album)) { assertArrayEquals(PNG, input.readAllBytes()); }
        assertEquals(album.toString(), store.saveAlbum(result.getString("id")).getString("albumUri"));
        JSONArray ids = new JSONArray().put(result.getJSONArray("items").getJSONObject(0).getString("id"));
        store.removeItems(result.getString("id"), ids); store.complete(result.getString("id")); imports.remove(result.getString("id"));
        try (var input = context.getContentResolver().openInputStream(album)) { assertArrayEquals(PNG, input.readAllBytes()); }
    }

    @Test public void ownPrivateFileProviderAndMissingExternalGrantAreRejected() throws Exception {
        File captures = new File(context.getFilesDir(), "media-input/captures"); assertTrue(captures.isDirectory() || captures.mkdirs());
        File privatePhoto = new File(captures, UUID.randomUUID() + ".jpg"); Files.write(privatePhoto.toPath(), PNG);
        Uri privateUri = FileProvider.getUriForFile(context, context.getPackageName() + ".media-input-files", privatePhoto);
        try { store.importUris("share", null, List.of(privateUri), "", false, 10); fail(); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("文件提供方")); }
        Files.delete(privatePhoto.toPath());
        Uri image = picture(); context.revokeUriPermission(image, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // The target owns MediaStore rows, so use a nonexistent ungranted external URI.
        Uri ungranted = Uri.parse("content://com.android.providers.media.documents/document/image%3A2147483647");
        try { store.importUris("share", null, List.of(ungranted), "", false, 10); fail(); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("授权") || expected.getMessage().contains("提供方")); }
    }

    @Test public void albumRetryRecoversMissingRowAndDiscardOnlyDeletesUnpublishedRow() throws Exception {
        File captures = new File(context.getFilesDir(), "media-input/captures"); assertTrue(captures.isDirectory() || captures.mkdirs());
        File photo = new File(captures, UUID.randomUUID() + ".jpg"); Files.write(photo.toPath(), PNG);
        JSONObject result = store.importCapture(photo, "fixture-session", 10, "DroyMediaInputTests");
        String id = result.getString("id"); imports.add(id);
        Uri first = Uri.parse(result.getString("albumUri"));
        context.getContentResolver().delete(first, null, null);
        File record = new File(store.root(), id + "/manifest.json");
        result.put("albumSaved", false); MediaInputStore.writeJson(record, result);
        JSONObject retried = store.saveAlbum(id); assertTrue(retried.getBoolean("albumSaved"));
        Uri published = Uri.parse(retried.getString("albumUri")); media.add(published); assertNotEquals(first, published);

        ContentValues values = new ContentValues(); values.put(MediaStore.Images.Media.DISPLAY_NAME, "unpublished-fixture-" + UUID.randomUUID() + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png"); values.put(MediaStore.Images.Media.IS_PENDING, 1);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DroyMediaInputTests");
        Uri unfinished = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values); assertNotNull(unfinished);
        media.add(unfinished); retried.put("albumSaved", false).put("albumUri", unfinished.toString()); MediaInputStore.writeJson(record, retried);
        store.complete(id); imports.remove(id);
        try (var cursor = context.getContentResolver().query(unfinished, new String[]{MediaStore.Images.Media._ID}, null, null, null)) {
            assertTrue(cursor == null || cursor.getCount() == 0);
        }
        media.remove(unfinished);
        try (var input = context.getContentResolver().openInputStream(published)) { assertArrayEquals(PNG, input.readAllBytes()); }
    }

    @Test public void cancelledPickerRestoresItsOwnerAndReleasesSensitiveUiGuard() throws Exception {
        activity = instrumentation.startActivitySync(new Intent(context, TestPageActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        IntentFilter filter = new IntentFilter(); filter.addAction(MediaStore.ACTION_PICK_IMAGES); filter.addAction(Intent.ACTION_OPEN_DOCUMENT);
        filter.addDataType("image/*");
        picker = new Instrumentation.ActivityMonitor(filter, new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        instrumentation.addMonitor(picker);
        Exception[] failure = {null};
        instrumentation.runOnMainSync(() -> {
            try {
                controller = new MediaInputController(activity, () -> {});
                controller.request(new JSONObject().put("operation", "pick").put("source", "album").put("remaining", 10).put("targetSessionId", "fixture-session"));
                assertTrue(MediaInputController.isPickerOpen());
                Bundle state = new Bundle(); controller.onSaveInstanceState(state); controller.close();
                controller = new MediaInputController(activity, () -> {}); controller.restore(state);
                assertTrue(MediaInputController.isPickerOpen());
                assertTrue(controller.onActivityResult(MediaInputController.PICK_IMAGES, Activity.RESULT_CANCELED, null));
                assertFalse(MediaInputController.isPickerOpen());
            } catch (Exception problem) { failure[0] = problem; }
        });
        assertNull(failure[0]);
    }

    @Test public void companionImportRetainsItsOwnerAndWorkCannotConsumeOrRebindIt() throws Exception {
        String id = UUID.randomUUID().toString();
        Uri image = picture();
        try {
            JSONObject result = store.importUrisForOperation(id, "album", "im:kaka", List.of(image), "", true, 10, "companion");
            assertEquals("companion", result.getString("consumer"));
            JSONObject restored = find(new MediaInputStore(context).list(), id);
            assertEquals("companion", restored.getString("consumer"));
            assertEquals("im:kaka", restored.getString("targetSessionId"));
            assertThrows(java.io.IOException.class, () -> store.bind(id, "fixture-work", null));
            assertThrows(java.io.IOException.class, () -> store.removeItems(id, new JSONArray()));
            assertThrows(java.io.IOException.class, () -> store.complete(id));
            assertThrows(java.io.IOException.class, () -> store.bind(id, "im:maimai", null, null, null, "companion"));
            assertThrows(java.io.IOException.class, () -> store.importUrisForOperation(id, "album", "im:maimai", List.of(image), "", true, 10, "companion"));
            assertEquals("im:kaka", find(store.list(), id).getString("targetSessionId"));
            store.bind(id, "im:kaka", true, null, null, "companion");
            assertTrue(find(store.list(), id).getBoolean("textApplied"));
        } finally {
            if (new File(store.root(), id + "/manifest.json").isFile()) store.complete(id, "companion");
        }
    }

    private Uri picture() throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "fixture-" + UUID.randomUUID() + ".png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png"); values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DroyMediaInputTests");
        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values); assertNotNull(uri); media.add(uri);
        try (var output = context.getContentResolver().openOutputStream(uri)) { output.write(PNG); }
        context.grantUriPermission(context.getPackageName(), uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); return uri;
    }

    private static JSONObject find(JSONArray values, String id) throws Exception {
        for (int i = 0; i < values.length(); i++) if (values.getJSONObject(i).getString("id").equals(id)) return values.getJSONObject(i);
        throw new AssertionError("Missing import " + id);
    }
}
