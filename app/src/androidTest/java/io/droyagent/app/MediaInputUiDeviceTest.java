package io.droyagent.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.app.media.MediaInputStore;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real media menus and shared-content drafts in an isolated emulator profile. */
@RunWith(AndroidJUnit4.class)
public final class MediaInputUiDeviceTest {
    private static final String EDITOR = "[data-composer-input][contenteditable=true]";
    private static final String DIALOG = "[data-droy-media-dialog]";
    private static final String[] PREFERENCES = {"petEnabled", "petExternalDisplay", "petInternalDisplay", "backgroundRuntimeEnabled",
        "themePreference", "themeColorScheme", "themeBackgroundColor", "resetTheme", "darkTheme"};
    private final Map<String, Object> savedPreferences = new HashMap<>();
    private final List<Instrumentation.ActivityMonitor> monitors = new ArrayList<>();
    private final List<Uri> mediaFixtures = new ArrayList<>();
    private final List<JSONObject> nativeTouchEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences preferences;
    private MainActivity activity;
    private DeviceUi ui;
    private MediaInputStore imports;
    private File home, homeBackup, mediaRoot, mediaBackup;
    private Map<String, String> originalHome, originalMedia;
    private boolean owned, hadHome, hadMedia, rotated;
    private int activeTouchDeviceId = Integer.MIN_VALUE;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Media UI fixtures require a dedicated emulator", Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk_gphone"));
        assertTrue("Stop the existing engine before media UI tests", RuntimeService.canManageHistory());
        // DeviceUi uses this default connection too; connecting only on the
        // first injected touch can otherwise restore a saved display rotation.
        UiAutomation automation = instrumentation.getUiAutomation();
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        Map<String, ?> current = preferences.getAll();
        for (String key : PREFERENCES) if (current.containsKey(key)) savedPreferences.put(key, current.get(key));
        home = new File(context.getFilesDir(), "dsh-home");
        mediaRoot = new File(context.getFilesDir(), "media-input");
        homeBackup = new File(context.getFilesDir(), "droy-media-home-backup-" + UUID.randomUUID());
        mediaBackup = new File(context.getFilesDir(), "droy-media-import-backup-" + UUID.randomUUID());
        originalHome = snapshot(home.toPath());
        originalMedia = snapshot(mediaRoot.toPath());
        hadHome = home.exists(); hadMedia = mediaRoot.exists();
        if (hadHome) assertTrue("Could not preserve DSH home", home.renameTo(homeBackup));
        if (hadMedia && !mediaRoot.renameTo(mediaBackup)) {
            if (hadHome) assertTrue(homeBackup.renameTo(home));
            fail("Could not preserve pending media imports");
        }
        owned = true;
        assertTrue(home.mkdirs());
        assertTrue(mediaRoot.mkdirs());
        assertTrue(preferences.edit().putBoolean("petEnabled", false).putString("petExternalDisplay", "off")
            .putString("petInternalDisplay", "off").putBoolean("backgroundRuntimeEnabled", false).commit());
        imports = new MediaInputStore(context);
        boolean landscape = "true".equals(InstrumentationRegistry.getArguments().getString("layoutLandscape"));
        // Rotate the physical display through the same automation connection
        // used for injection; a resized letterboxed display has a different input transform.
        assertTrue("UiAutomation did not accept the initial rotation", automation.setRotation(UiAutomation.ROTATION_FREEZE_0));
        rotated = true;
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity);
        awaitChat();
        assertTrue("UiAutomation did not orient the ready chat", automation.setRotation(landscape
            ? UiAutomation.ROTATION_FREEZE_90 : UiAutomation.ROTATION_FREEZE_0));
        try {
            DeviceUi.await("The real WebView did not reach the requested orientation", 5000,
                () -> ui.truth(landscape ? "innerWidth>innerHeight" : "innerHeight>innerWidth"));
        } catch (AssertionError failure) {
            org.json.JSONObject geometry = new org.json.JSONObject();
            android.webkit.WebView nativeWeb = ui.web();
            instrumentation.runOnMainSync(() -> {
                try { geometry.put("decorWidth", activity.getWindow().getDecorView().getWidth()).put("decorHeight", activity.getWindow().getDecorView().getHeight())
                    .put("webWidth", nativeWeb.getWidth()).put("webHeight", nativeWeb.getHeight()).put("rotation", activity.getDisplay().getRotation()); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            geometry.put("browser", ui.evaluate("({innerWidth,innerHeight,outerWidth,outerHeight,dpr:devicePixelRatio,screenWidth:screen.width,screenHeight:screen.height,rootWidth:document.documentElement.clientWidth,rootHeight:document.documentElement.clientHeight,viewport:document.querySelector('meta[name=viewport]')?.content})"));
            Bundle diagnostic = new Bundle(); diagnostic.putString("stream", "\nDROY_MEDIA_ORIENTATION " + geometry + "\n"); instrumentation.sendStatus(2, diagnostic);
            throw failure;
        }
        configureLayout();
    }

    @After public void tearDown() throws Exception {
        try {
            for (Instrumentation.ActivityMonitor monitor : monitors) instrumentation.removeMonitor(monitor);
            if (owned && imports != null) {
                JSONArray pending = imports.list();
                for (int i = 0; i < pending.length(); i++) {
                    JSONObject row = pending.getJSONObject(i);
                    if ("camera".equals(row.optString("source")) && row.has("albumUri")) {
                        Uri saved = Uri.parse(row.getString("albumUri"));
                        if (!mediaFixtures.contains(saved)) mediaFixtures.add(saved);
                    }
                }
            }
            for (Uri uri : mediaFixtures) {
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                context.getContentResolver().delete(uri, null, null);
            }
            if (!owned) return;
            try {
                if (activity != null && !activity.isDestroyed()) instrumentation.runOnMainSync(activity::finish);
                instrumentation.runOnMainSync(RuntimeService::requestStop);
                DeviceUi.await("Media UI cleanup left an engine running", 20000, RuntimeService::canManageHistory);
                PetOverlay.runtimeStopped();
                assertNoModelOrToolRequests();
            } finally {
                assertTrue("Preserve the original data until the fixture runtime stops", RuntimeService.canManageHistory());
                HistoryData.deleteTree(home.toPath());
                HistoryData.deleteTree(mediaRoot.toPath());
                if (hadHome) assertTrue("Could not restore DSH home", homeBackup.renameTo(home));
                if (hadMedia) assertTrue("Could not restore pending imports", mediaBackup.renameTo(mediaRoot));
                assertEquals("Original DSH data changed", originalHome, snapshot(home.toPath()));
                assertEquals("Original pending imports changed", originalMedia, snapshot(mediaRoot.toPath()));
                SharedPreferences.Editor restore = preferences.edit();
                for (String key : PREFERENCES) {
                    Object value = savedPreferences.get(key);
                    if (value instanceof String text) restore.putString(key, text);
                    else if (value instanceof Boolean flag) restore.putBoolean(key, flag);
                    else if (value instanceof Integer number) restore.putInt(key, number);
                    else restore.remove(key);
                }
                assertTrue(restore.commit());
                owned = false;
            }
        } finally {
            if (rotated) {
                assertTrue("Could not unfreeze display rotation", instrumentation.getUiAutomation().setRotation(UiAutomation.ROTATION_UNFREEZE));
                rotated = false;
            }
        }
    }

    @Test public void actualAlbumAndCameraMenuTouchesCanCancelWithoutChangingDraft() throws Exception {
        typeDraft("Existing draft stays");
        Instrumentation.ActivityMonitor photos = cancelPicker(MediaStore.ACTION_PICK_IMAGES, true);
        Instrumentation.ActivityMonitor documents = cancelPicker(Intent.ACTION_OPEN_DOCUMENT, true);
        openMenu();
        markCommand("相册"); assertReadable("[data-media-test-command]");
        capture("album-menu");
        tap("[data-media-test-command]");
        DeviceUi.await("The album touch did not reach an Android picker", 5000,
            () -> photos.getHits() + documents.getHits() == 1);
        awaitChat();
        assertDraft("Existing draft stays");
        assertEquals("Cancelling album selection created an import", 0, imports.list().length());
        instrumentation.removeMonitor(photos); monitors.remove(photos);
        instrumentation.removeMonitor(documents); monitors.remove(documents);

        Instrumentation.ActivityMonitor camera = cancelPicker(MediaStore.ACTION_IMAGE_CAPTURE, false);
        openMenu();
        markCommand("拍照"); assertReadable("[data-media-test-command]");
        capture("camera-menu");
        tap("[data-media-test-command]");
        DeviceUi.await("The camera touch did not reach the system camera", 5000, () -> camera.getHits() == 1);
        awaitChat();
        assertDraft("Existing draft stays");
        assertEquals("Cancelling camera selection created an import", 0, imports.list().length());
        capture("cancelled-camera-draft");
    }

    @Test public void successfulSystemResultsReachAttachmentsAndSaveCameraOriginalToAlbum() throws Exception {
        typeDraft("System result draft");
        String albumName = "system-picker-fixture.png";
        Uri selected = createPhoto(albumName);
        Bitmap bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xffdc7f35);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, encoded)); bitmap.recycle();
        byte[] cameraOriginal = encoded.toByteArray();
        AtomicInteger albumRequests = new AtomicInteger(), cameraRequests = new AtomicInteger();
        AtomicReference<Throwable> resultFailure = new AtomicReference<>();
        List<String> observedActions = new java.util.concurrent.CopyOnWriteArrayList<>();
        Instrumentation.ActivityMonitor syntheticResults = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                String action = intent.getAction();
                observedActions.add(String.valueOf(action));
                if (MediaStore.ACTION_PICK_IMAGES.equals(action) || Intent.ACTION_OPEN_DOCUMENT.equals(action)) {
                    albumRequests.incrementAndGet();
                    Intent result = new Intent().setData(selected).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    result.setClipData(ClipData.newUri(context.getContentResolver(), "Fictional selected image", selected));
                    return new Instrumentation.ActivityResult(Activity.RESULT_OK, result);
                }
                if (!MediaStore.ACTION_IMAGE_CAPTURE.equals(action)) return null;
                cameraRequests.incrementAndGet();
                try {
                    Uri destination = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri.class)
                        : intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
                    if (destination == null) throw new AssertionError("The camera intent omitted full-size EXTRA_OUTPUT");
                    // This is a synthetic camera result. It exercises Android's
                    // result callback and original-file handoff, not a real sensor.
                    try (java.io.OutputStream output = context.getContentResolver().openOutputStream(destination, "wt")) {
                        if (output == null) throw new AssertionError("The camera output URI is not writable");
                        output.write(cameraOriginal);
                    }
                    return new Instrumentation.ActivityResult(Activity.RESULT_OK, null);
                } catch (Throwable failure) {
                    resultFailure.set(failure);
                    return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
                }
            }
        };
        instrumentation.addMonitor(syntheticResults); monitors.add(syntheticResults);
        openMenu(); markCommand("相册");
        ui.evaluate("(()=>{window.__mediaTestClick=null;const e=document.querySelector('[data-media-test-command]');e.addEventListener('click',event=>{window.__mediaTestClick={trusted:event.isTrusted,prevented:event.defaultPrevented}},{once:true})})()");
        tap("[data-media-test-command]");
        try {
            DeviceUi.await("The album menu did not start its system result path", 5000, () -> albumRequests.get() == 1);
        } catch (AssertionError failure) {
            reportMediaState("system-result-album-failure", albumRequests.get(), cameraRequests.get(), observedActions);
            capture("system-result-album-failure");
            throw failure;
        }
        DeviceUi.await("The selected system image did not reach the DSH attachment rail", 15000,
            () -> findImport("album") != null && hasImage(albumName));
        assertDraft("System result draft"); assertImageCount(albumName, 1);
        capture("system-picker-success-draft");

        openMenu(); markCommand("拍照"); tap("[data-media-test-command]");
        DeviceUi.await("The camera menu did not start its system result path", 5000, () -> cameraRequests.get() == 1);
        assertNull("The synthetic camera could not write EXTRA_OUTPUT", resultFailure.get());
        DeviceUi.await("The camera result was not saved and attached", 20000, () -> {
            JSONObject row = findImport("camera");
            return row != null && row.optBoolean("albumSaved") && row.getJSONArray("items").length() == 1
                && hasImage(row.getJSONArray("items").getJSONObject(0).getString("name"));
        });
        JSONObject camera = findImport("camera");
        assertNotNull(camera);
        Uri saved = Uri.parse(camera.getString("albumUri"));
        mediaFixtures.add(saved);
        ByteArrayOutputStream restored = new ByteArrayOutputStream();
        try (java.io.InputStream input = context.getContentResolver().openInputStream(saved)) {
            assertNotNull("The published original is unavailable from MediaStore", input);
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) restored.write(buffer, 0, count);
        }
        assertTrue("The album original differs from the synthetic full-size camera output", Arrays.equals(cameraOriginal, restored.toByteArray()));
        try (var cursor = context.getContentResolver().query(saved, new String[]{MediaStore.Images.Media.IS_PENDING}, null, null, null)) {
            assertNotNull(cursor); assertTrue(cursor.moveToFirst());
            assertEquals("The camera original is still hidden as a pending MediaStore entry", 0, cursor.getInt(0));
        }
        assertDraft("System result draft");
        assertImageCount(albumName, 1);
        assertImageCount(camera.getJSONArray("items").getJSONObject(0).getString("name"), 1);
        assertNoModelOrToolRequests();
        capture("synthetic-camera-success-draft");
    }

    @Test public void shareReviewCanReopenAndAppendToExistingDraftWithoutDuplicateImages() throws Exception {
        typeDraft("Existing draft stays");
        String name = "shared-photo-" + UUID.randomUUID().toString().substring(0, 8) + ".png";
        Uri photo = createPhoto(name);
        JSONObject imported = imports.importUris("share", null, List.of(photo), "Shared caption", false, 10);
        notifyImports();
        awaitReview();
        assertEquals("new", ui.evaluate("document.querySelector('[data-droy-media-target]').value"));
        assertTrue("The original session is unavailable as an import target", ui.truth("document.querySelector('[data-droy-media-target]').options.length>=2"));
        assertTrue("The shared filename is missing", ui.truth("document.querySelector('[data-droy-media-item]').textContent.includes(" + JSONObject.quote(name) + ")"));
        assertReadable("[data-droy-media-target]");
        assertReadable("[data-droy-media-import]");
        assertReadable("[data-droy-media-cancel]");
        assertNoHorizontalOverflow(DIALOG);
        capture("share-preview-default-new");

        ui.pressBack();
        DeviceUi.await("Back did not close the import preview", 5000, () -> !ui.truth("!!document.querySelector('" + DIALOG + "')"));
        assertEquals("Closing the preview lost pending input", imported.getString("id"), imports.list().getJSONObject(0).getString("id"));
        assertDraft("Existing draft stays");
        openMenu(); markCommand("待导入内容"); tap("[data-media-test-command]");
        awaitReview();
        chooseExistingTargetByTouch();
        capture("share-preview-existing-target");
        tap("[data-droy-media-import]");
        DeviceUi.await("Import did not return to the existing draft", 10000,
            () -> !ui.truth("!!document.querySelector('" + DIALOG + "')") && hasImage(name)
                && editorText().contains("Existing draft stays") && editorText().contains("Shared caption"));
        awaitChat();
        assertImageCount(name, 1);
        for (int i = 0; i < 3; i++) notifyImports();
        DeviceUi.await("Imported image disappeared after duplicate notifications", 5000, () -> hasImage(name));
        assertImageCount(name, 1);
        capture("share-existing-draft");

        JSONObject beforeReload = findImport("share");
        String originalTargetSessionId = beforeReload == null ? "" : beforeReload.optString("targetSessionId");
        android.webkit.WebView web = ui.web();
        instrumentation.runOnMainSync(web::reload);
        awaitChat();
        try {
            DeviceUi.await("The imported attachment was not recovered after reload", 10000, () -> hasImage(name));
        } catch (AssertionError failure) {
            reportMediaState("share-reload-failure", -1, -1, List.of());
            JSONObject recovery = (JSONObject) ui.evaluate("(()=>{const s=window.DroyMediaInput?.getSnapshot();return {"
                + "imports:(s?.imports||[]).map(r=>({id:r.id,targetSessionId:r.targetSessionId||null,source:r.source,items:r.items?.length||0,"
                + "textApplied:r.textApplied===true,textPrepared:r.textPrepared===true,error:!!r.error})),dialogId:s?.dialogId||null,pending:s?.pending===true,"
                + "sessions:[...document.querySelectorAll('[data-conversation-session]')].map(e=>({id:e.getAttribute('data-conversation-session'),"
                + "phase:e.getAttribute('data-content-phase'),visible:e.getClientRects().length>0&&getComputedStyle(e).visibility!=='hidden',"
                + "editorCount:e.querySelectorAll('[data-composer-input]').length})),"
                + "editors:[...document.querySelectorAll('[data-composer-input]')].map(e=>({visible:e.getClientRects().length>0,"
                + "sessionId:e.closest('[data-conversation-session]')?.getAttribute('data-conversation-session')||null,characters:e.textContent.length}))}})()");
            JSONArray nativeRows = imports.list(), nativeMetadata = new JSONArray();
            for (int i = 0; i < nativeRows.length(); i++) {
                JSONObject row = nativeRows.getJSONObject(i);
                nativeMetadata.put(new JSONObject().put("id", row.optString("id")).put("targetSessionId", row.optString("targetSessionId"))
                    .put("source", row.optString("source")).put("items", row.optJSONArray("items") == null ? 0 : row.getJSONArray("items").length())
                    .put("textApplied", row.optBoolean("textApplied")).put("textPrepared", row.optBoolean("textPrepared")).put("error", row.has("error")));
            }
            recovery.put("nativeImports", nativeMetadata).put("originalImportId", imported.getString("id"))
                .put("originalTargetSessionId", originalTargetSessionId);
            Bundle status = new Bundle(); status.putString("stream", "\nDROY_MEDIA_RECOVERY_STATE " + recovery + "\n");
            instrumentation.sendStatus(2, status);
            capture("share-reload-failure");
            throw failure;
        }
        assertImageCount(name, 1);
        assertTrue(editorText().contains("Existing draft stays"));
        assertTrue(editorText().contains("Shared caption"));
        assertEquals("Shared text was appended a second time after reload", 1,
            editorText().split("Shared caption", -1).length - 1);
        capture("share-draft-restored");
    }

    @Test public void defaultShareCreatesANewUnsentDraft() throws Exception {
        typeDraft("Original draft stays");
        String name = "new-share-photo.png";
        JSONObject imported = imports.importUris("share", null, List.of(createPhoto(name)), "New share caption", false, 10);
        notifyImports(); awaitReview();
        assertEquals("new", ui.evaluate("document.querySelector('[data-droy-media-target]').value"));
        tap("[data-droy-media-import]");
        try {
            DeviceUi.await("The default share did not become a new draft", 15000,
                () -> !ui.truth("!!document.querySelector('" + DIALOG + "')") && hasImage(name)
                    && editorText().contains("New share caption"));
        } catch (AssertionError failure) {
            reportMediaState("new-share-adoption-failure", -1, -1, List.of());
            capture("new-share-adoption-failure");
            throw failure;
        }
        awaitChat();
        assertFalse("The share reused the original draft", editorText().contains("Original draft stays"));
        JSONObject bound = null;
        JSONArray pending = imports.list();
        for (int i = 0; i < pending.length(); i++) if (pending.getJSONObject(i).getString("id").equals(imported.getString("id"))) bound = pending.getJSONObject(i);
        assertNotNull("The unsent import must remain recoverable", bound);
        assertFalse("The shared input was not bound to its new session", bound.optString("targetSessionId").isEmpty());
        assertImageCount(name, 1);
        capture("share-new-draft");
    }

    @Test public void systemSendAndMultipleResolveReceiverAndOpenNewTargetPreview() throws Exception {
        typeDraft("Original draft stays");
        Uri first = createPhoto("receiver-first.png");
        Uri second = createPhoto("receiver-second.png");
        String[] originalSessionId = {null};
        for (String action : List.of(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            boolean multiple = Intent.ACTION_SEND_MULTIPLE.equals(action);
            String caption = multiple ? "Fictional multiple-share caption" : "Fictional single-share caption";
            Intent incoming = new Intent(action).setType("image/png").setPackage(context.getPackageName())
                .putExtra(Intent.EXTRA_TEXT, caption)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ClipData clips = new ClipData("Fictional media fixture", new String[]{"image/png"}, new ClipData.Item(first));
            if (multiple) {
                // The same URI can occur in both payload forms and more than once.
                // A receiving app must import the ordered unique items only once.
                incoming.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(List.of(first, second, first)));
                clips.addItem(new ClipData.Item(second));
            } else incoming.putExtra(Intent.EXTRA_STREAM, first);
            incoming.setClipData(clips);
            assertNull("The fixture must exercise Manifest resolution", incoming.getComponent());
            PackageManager packages = context.getPackageManager();
            ComponentName resolved = incoming.resolveActivity(packages);
            assertNotNull("The system cannot resolve the share action", resolved);
            assertEquals(context.getPackageName(), resolved.getPackageName());
            assertEquals("io.droyagent.app.media.ShareReceiveActivity", resolved.getClassName());
            ActivityInfo receiver = packages.getActivityInfo(resolved, 0);
            assertTrue("The share receiver must be reachable from other apps", receiver.exported);
            String appLabel = context.getApplicationInfo().loadLabel(packages).toString();
            assertEquals("The receiver overrides the installed application name", appLabel, receiver.loadLabel(packages).toString());
            ResolveInfo entry = packages.resolveActivity(incoming, PackageManager.MATCH_DEFAULT_ONLY);
            assertNotNull(entry);
            assertEquals("The share filter overrides the installed application name", appLabel, entry.loadLabel(packages).toString());

            Instrumentation.ActivityMonitor newChat = null;
            if (multiple) {
                MainActivity previous = activity;
                instrumentation.runOnMainSync(previous::finish);
                DeviceUi.await("The old chat Activity did not finish before cold sharing", 10000, () -> {
                    boolean[] destroyed = {false};
                    instrumentation.runOnMainSync(() -> destroyed[0] = previous.isDestroyed());
                    return destroyed[0];
                });
                instrumentation.runOnMainSync(RuntimeService::requestStop);
                DeviceUi.await("The old engine did not stop before cold sharing", 20000, RuntimeService::canManageHistory);
                PetOverlay.runtimeStopped();
                newChat = new Instrumentation.ActivityMonitor(MainActivity.class.getName(), null, false);
                instrumentation.addMonitor(newChat); monitors.add(newChat);
            }
            // Instrumentation runs in the target process UID. This exercises the
            // system resolver and exported Activity, without claiming a WeChat UID.
            instrumentation.getContext().startActivity(incoming);
            if (newChat != null) {
                Activity reopened = instrumentation.waitForMonitorWithTimeout(newChat, 90000);
                assertTrue("Cold sharing did not create a new chat Activity", reopened instanceof MainActivity);
                activity = (MainActivity) reopened;
                ui = new DeviceUi(instrumentation, activity);
                instrumentation.removeMonitor(newChat); monitors.remove(newChat);
                // The incoming preview deliberately makes the page root inert;
                // require the real ready frame without dismissing that preview.
                DeviceUi.await("The cold share did not start the engine and ready chat", 90000,
                    () -> RuntimeService.ready && ui.web() != null
                        && ui.truth("typeof window.DroyMobile?.mediaInput==='function'&&document.documentElement.dataset.droyUiReady==='true'")
                        && ui.nativeUiReady() && !ui.loadingOverlayShown());
                ui.hideKeyboard();
                dismissModelOnboarding();
            }
            awaitReview();
            DeviceUi.await("The receiver did not return to the existing chat Activity", 5000,
                () -> ui.nativeNavigationState().getBoolean("activityFocus") && ui.nativeUiReady());
            JSONArray pending = imports.list();
            assertEquals("A single system share created duplicate batches", 1, pending.length());
            JSONObject imported = pending.getJSONObject(0);
            assertEquals("share", imported.getString("source"));
            assertFalse("The receiver failed to read its granted content", imported.has("error"));
            assertEquals(caption, imported.getString("text"));
            assertEquals("EXTRA_STREAM and ClipData should be deduplicated", multiple ? 2 : 1, imported.getJSONArray("items").length());
            assertEquals("The share must default to a new draft", "new",
                ui.evaluate("document.querySelector('[data-droy-media-target]').value"));
            if (!multiple) {
                originalSessionId[0] = String.valueOf(ui.evaluate("[...document.querySelector('[data-droy-media-target]').options].find(e=>e.value!=='new')?.value||''"));
                assertFalse("The original draft session was not offered", originalSessionId[0].isEmpty());
            } else assertTrue("Cold sharing lost the original draft's available target", ui.truth("[...document.querySelector('[data-droy-media-target]').options].some(e=>e.value==="
                + JSONObject.quote(originalSessionId[0]) + ")"));
            assertTrue("The preview lost the caption", ui.truth("document.querySelector('[data-droy-media-text]').textContent===" + JSONObject.quote(caption)));
            assertTrue("The preview lost the first filename", ui.truth("[...document.querySelectorAll('[data-droy-media-item]')].some(e=>e.textContent.includes('receiver-first.png'))"));
            if (multiple) assertTrue("The preview lost the second filename", ui.truth("[...document.querySelectorAll('[data-droy-media-item]')].some(e=>e.textContent.includes('receiver-second.png'))"));
            assertReadable("[data-droy-media-import]");
            assertNoHorizontalOverflow(DIALOG);
            if (!multiple) assertDraft("Original draft stays");
            assertNoModelOrToolRequests();
            capture(multiple ? "system-share-cold-multiple-preview" : "system-share-single-preview");
            tap("[data-droy-media-discard]");
            DeviceUi.await("Discarding the synthetic share did not clear its import", 5000,
                () -> imports.list().length() == 0 && !ui.truth("!!document.querySelector('" + DIALOG + "')"));
            if (!multiple) assertDraft("Original draft stays");
        }
    }

    private void awaitChat() throws Exception {
        ui.awaitPage();
        ui.hideKeyboard();
        DeviceUi.await("The media bridge/composer did not become ready", 10000, () -> ui.truth("(()=>{"
            + "for(const title of document.querySelectorAll('[role=dialog] h2')){if(!['开始使用','Get started'].includes(title.textContent.trim()))continue;"
            + "const modal=title.closest('[role=dialog]'),close=modal.querySelector('button[aria-label=关闭],button[aria-label=Close]');if(close&&!close.disabled){close.click();return false}}"
            + "return typeof window.DroyMobile?.mediaInput==='function'&&!!document.querySelector('" + EDITOR + "')})()"));
    }

    private void typeDraft(String text) throws Exception {
        tap(EDITOR);
        instrumentation.sendStringSync(text);
        DeviceUi.await("Trusted Android input did not populate the draft", 5000, () -> editorText().equals(text));
        ui.hideKeyboard();
    }

    private String editorText() throws Exception { return String.valueOf(ui.evaluate("document.querySelector('" + EDITOR + "')?.textContent||''")); }
    private void assertDraft(String text) throws Exception { assertEquals(text, editorText()); }
    private JSONObject findImport(String source) throws Exception {
        JSONArray pending = imports.list();
        for (int i = 0; i < pending.length(); i++) if (source.equals(pending.getJSONObject(i).optString("source"))) return pending.getJSONObject(i);
        return null;
    }
    private boolean hasImage(String name) throws Exception {
        return ui.truth("[...document.querySelectorAll('img')].some(e=>e.alt===" + JSONObject.quote(name) + "&&e.getClientRects().length>0)");
    }
    private void assertImageCount(String name, int count) throws Exception {
        assertEquals(count, ((Number) ui.evaluate("[...document.querySelectorAll('img')].filter(e=>e.alt==="
            + JSONObject.quote(name) + "&&e.getClientRects().length>0).length")).intValue());
    }

    private void notifyImports() throws Exception { ui.evaluate("window.dispatchEvent(new Event('droy-media-input-change'))"); }

    private void dismissModelOnboarding() throws Exception {
        long[] quietSince = {0};
        boolean[] tapped = {false};
        DeviceUi.await("The known API-key onboarding did not dismiss", 10000, () -> {
            JSONObject current = (JSONObject) ui.evaluate("(()=>{document.querySelector('[data-media-onboarding-skip]')?.removeAttribute('data-media-onboarding-skip');"
                + "for(const heading of document.querySelectorAll('[role=dialog] h2')){if(!['添加一个 API Key 开始使用','Add an API key to get started'].includes(heading.textContent.trim()))continue;"
                + "if(!heading.getClientRects().length||heading.closest('[inert]'))continue;const r=heading.getBoundingClientRect(),top=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);"
                + "if(!top||!heading.contains(top))continue;const modal=heading.closest('[role=dialog]'),button=[...modal.querySelectorAll('button')].find(e=>['稍后配置','Configure later'].includes(e.textContent.trim()));"
                + "if(!button||button.disabled)return {blocking:true,button:false};const b=button.getBoundingClientRect(),hit=document.elementFromPoint(b.left+b.width/2,b.top+b.height/2);"
                + "if(hit&&(button===hit||button.contains(hit))){button.setAttribute('data-media-onboarding-skip','');return {blocking:true,button:true}}return {blocking:true,button:false}}"
                + "return {blocking:false,button:false}})()");
            if (current.getBoolean("button") && !tapped[0]) {
                tap("[data-media-onboarding-skip]"); tapped[0] = true; quietSince[0] = 0; return false;
            }
            if (current.getBoolean("blocking")) { quietSince[0] = 0; return false; }
            if (quietSince[0] == 0) quietSince[0] = SystemClock.elapsedRealtime();
            return SystemClock.elapsedRealtime() - quietSince[0] >= 600;
        });
    }

    private void chooseExistingTargetByTouch() throws Exception {
        JSONObject option = (JSONObject) ui.evaluate("(()=>{const e=[...document.querySelector('[data-droy-media-target]').options].find(e=>e.value!=='new');return e?{value:e.value,label:e.textContent}:null})()");
        assertNotNull("No existing session can receive the fixture", option);
        tap("[data-droy-media-target]");
        Rect[] target = {null};
        DeviceUi.await("The native target popup did not expose the existing session", 5000, () -> {
            target[0] = nativeChoiceBounds(option.getString("label"));
            return target[0] != null && !target[0].isEmpty();
        });
        systemTap(target[0].centerX(), target[0].centerY());
        DeviceUi.await("The real target-option touch did not choose the existing session", 5000,
            () -> option.getString("value").equals(ui.evaluate("document.querySelector('[data-droy-media-target]').value")));
    }

    private Rect nativeChoiceBounds(String label) {
        AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        if (root == null) return null;
        try {
            List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(label);
            try {
                for (AccessibilityNodeInfo node : matches) {
                    if (!node.isVisibleToUser() || !node.isEnabled() || !label.contentEquals(node.getText() == null ? "" : node.getText())) continue;
                    if (!"android.widget.CheckedTextView".equals(String.valueOf(node.getClassName()))) continue;
                    Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                    if (!bounds.isEmpty()) return bounds;
                }
            } finally { for (AccessibilityNodeInfo node : matches) node.recycle(); }
            return null;
        } finally { root.recycle(); }
    }

    private void reportMediaState(String stage, int albumRequests, int cameraRequests, List<String> observedActions) throws Exception {
        JSONObject state = (JSONObject) ui.evaluate("(()=>{const s=window.DroyMediaInput?.getSnapshot(),pluses=[...document.querySelectorAll('[data-droy-composer-add]')];return {bridge:typeof window.DroyMobile?.mediaInput,"
            + "pending:s?.pending,error:s?.error||null,notice:s?.notice||null,dialog:!!s?.dialogId,"
            + "imports:(s?.imports||[]).map(r=>({source:r.source,error:!!r.error,bound:!!r.targetSessionId,items:r.items?.length||0})),click:window.__mediaTestClick||null,"
            + "commandEvents:window.__mediaCommandEvents||[],commandPaint:window.__mediaCommandFrame===true,menu:!!document.querySelector('[data-trigger-menu]'),"
            + "plusBefore:window.__mediaPlusBefore||null,plusEvents:window.__mediaPlusEvents||[],plusDocumentEvents:window.__mediaPlusDocumentEvents||[],"
            + "touchInteraction:window.DroyTouch?.isTouchInteraction?.()??null,editorCount:document.querySelectorAll('[data-composer-input]').length,"
            + "visibleEditorCount:[...document.querySelectorAll('[data-composer-input]')].filter(e=>e.getClientRects().length>0&&getComputedStyle(e).visibility!=='hidden').length,"
            + "pluses:pluses.map((e,index)=>{const r=e.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return {index,"
            + "phase:e.closest('[data-content-phase]')?.getAttribute('data-content-phase')||null,session:e.closest('[data-conversation-session]')?.getAttribute('data-conversation-session')||null,"
            + "disabled:!!e.disabled,inert:!!e.closest('[inert]'),rect:r.toJSON(),hit:hit?{tag:hit.tagName,role:hit.getAttribute('role'),own:e===hit||e.contains(hit),"
            + "plusIndex:pluses.findIndex(button=>button===hit||button.contains(hit))}:null}})}})()");
        state.put("albumRequests", albumRequests).put("cameraRequests", cameraRequests).put("observedActions", new JSONArray(observedActions));
        File pending = new File(mediaRoot, "pending.json");
        state.put("nativePending", pending.isFile());
        if (pending.isFile()) {
            JSONObject metadata = new JSONObject(new String(Files.readAllBytes(pending.toPath()), StandardCharsets.UTF_8));
            state.put("nativeSource", metadata.optString("source")).put("nativeResultReady", metadata.optBoolean("resultReady"));
        }
        state.put("navigation", ui.nativeNavigationState());
        state.put("nativeTouchEvents", new JSONArray(nativeTouchEvents));
        Bundle status = new Bundle(); status.putString("stream", "\nDROY_MEDIA_STATE " + stage + " " + state + "\n");
        instrumentation.sendStatus(2, status);
    }
    private void awaitReview() throws Exception {
        try {
            DeviceUi.await("Shared content did not open the import preview", 10000,
                () -> ui.truth("!!document.querySelector('" + DIALOG + "')&&!document.querySelector('[data-droy-media-import]').disabled"));
        } catch (AssertionError failure) {
            reportMediaState("review-failure", -1, -1, List.of());
            capture("review-failure");
            throw failure;
        }
    }

    private void openMenu() throws Exception {
        ui.hideKeyboard();
        try {
            JSONObject[] previous = {null};
            DeviceUi.await("The composer add button is still moving after IME dismissal", 5000, () -> {
                JSONObject current = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector('[data-droy-composer-add]');if(!e)return {ready:false};"
                    + "const r=e.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return {"
                    + "rect:r.toJSON(),ime:parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--droy-ime-bottom'))||0,"
                    + "ready:!e.disabled&&!e.closest('[inert]')&&r.width>0&&r.height>0&&!!hit&&(e===hit||e.contains(hit))}})()");
                boolean stable = previous[0] != null && current.toString().equals(previous[0].toString());
                previous[0] = current;
                return stable && current.getBoolean("ready") && current.getDouble("ime") <= 1 && !ui.keyboardVisible();
            });
            ui.evaluate("window.__mediaMenuFrame=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__mediaMenuFrame=true))");
            DeviceUi.await("The add-button layout did not reach two frames", 5000, () -> ui.truth("window.__mediaMenuFrame"));
            java.util.concurrent.CountDownLatch painted = new java.util.concurrent.CountDownLatch(1);
            android.webkit.WebView web = ui.web();
            instrumentation.runOnMainSync(() -> web.postVisualStateCallback(0, new android.webkit.WebView.VisualStateCallback() {
                @Override public void onComplete(long requestId) { painted.countDown(); }
            }));
            assertTrue("The add-button layout was not committed to the WebView surface", painted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            ui.evaluate("(()=>{const e=document.querySelector('[data-droy-composer-add]'),pluses=[...document.querySelectorAll('[data-droy-composer-add]')];"
                + "window.__mediaPlusEvents=[];window.__mediaPlusDocumentEvents=[];window.__mediaPlusBefore=e?{index:pluses.indexOf(e),"
                + "phase:e.closest('[data-content-phase]')?.getAttribute('data-content-phase')||null,session:e.closest('[data-conversation-session]')?.getAttribute('data-conversation-session')||null,"
                + "rect:e.getBoundingClientRect().toJSON(),touchInteraction:window.DroyTouch?.isTouchInteraction?.()??null,editorCount:document.querySelectorAll('[data-composer-input]').length}:null;"
                + "for(const type of ['pointerdown','pointerup','click']){e?.addEventListener(type,event=>{const record={type,trusted:event.isTrusted,defaultPrevented:event.defaultPrevented,"
                + "clientX:event.clientX,clientY:event.clientY,pointerType:event.pointerType||null,touchInteraction:window.DroyTouch?.isTouchInteraction?.()??null,"
                + "rect:e.getBoundingClientRect().toJSON()};window.__mediaPlusEvents.push(record);queueMicrotask(()=>record.defaultPreventedAfter=event.defaultPrevented)},{once:true});"
                + "document.addEventListener(type,event=>{const actual=event.target?.closest?.('[data-droy-composer-add]');window.__mediaPlusDocumentEvents.push({type,trusted:event.isTrusted,"
                + "defaultPrevented:event.defaultPrevented,clientX:event.clientX,clientY:event.clientY,tag:event.target?.tagName||null,role:event.target?.getAttribute?.('role')||null,"
                + "plusIndex:[...document.querySelectorAll('[data-droy-composer-add]')].indexOf(actual),touchInteraction:window.DroyTouch?.isTouchInteraction?.()??null})},{capture:true,once:true})}})()");
            Rect addBounds = ui.visibleWebBounds("[data-droy-composer-add]");
            JSONObject touchGeometry = new JSONObject().put("bounds", addBounds.toShortString());
            android.webkit.WebView touchWeb = ui.web();
            instrumentation.runOnMainSync(() -> {
                try { int[] location = new int[2]; touchWeb.getLocationOnScreen(location);
                    touchGeometry.put("webX", location[0]).put("webY", location[1]).put("width", touchWeb.getWidth()).put("height", touchWeb.getHeight())
                        .put("scrollX", touchWeb.getScrollX()).put("scrollY", touchWeb.getScrollY()).put("scale", touchWeb.getScale()); }
                catch (Exception failure) { throw new AssertionError(failure); }
            });
            touchGeometry.put("browser", ui.evaluate("({innerWidth,innerHeight,dpr:devicePixelRatio,scrollX,scrollY,visual:{width:visualViewport.width,height:visualViewport.height,scale:visualViewport.scale,x:visualViewport.offsetLeft,y:visualViewport.offsetTop}})"));
            Bundle touchDiagnostic = new Bundle(); touchDiagnostic.putString("stream", "\nDROY_MEDIA_TOUCH_GEOMETRY " + touchGeometry + "\n"); instrumentation.sendStatus(2, touchDiagnostic);
            nativeTouchEvents.clear();
            instrumentation.runOnMainSync(() -> touchWeb.setOnTouchListener((view, event) -> {
                try {
                    nativeTouchEvents.add(new JSONObject().put("action", event.getActionMasked()).put("x", event.getX()).put("y", event.getY())
                        .put("rawX", event.getRawX()).put("rawY", event.getRawY()).put("source", event.getSource()).put("deviceId", event.getDeviceId()));
                } catch (Exception failure) { throw new AssertionError(failure); }
                return false;
            }));
            systemTap(addBounds.centerX(), addBounds.centerY());
            DeviceUi.await("The real add-menu touch did not open", 5000,
                () -> ui.truth("!!document.querySelector('[data-trigger-menu] [role=option]')"));
        } catch (AssertionError failure) {
            reportMediaState("open-menu-failure", -1, -1, List.of());
            capture("open-menu-failure");
            throw failure;
        }
    }

    private void markCommand(String label) throws Exception {
        DeviceUi.await("The add menu omitted " + label, 5000, () -> ui.truth("(()=>{"
            + "document.querySelector('[data-media-test-command]')?.removeAttribute('data-media-test-command');"
            + "const row=[...document.querySelectorAll('[data-trigger-menu] [role=option]')].find(e=>[...e.querySelectorAll('span')].some(s=>s.textContent.trim()==="
            + JSONObject.quote(label) + ")||e.textContent.trim()===" + JSONObject.quote(label) + ");"
            + "if(!row)return false;row.setAttribute('data-media-test-command','');return true})()"));
        reachCommandByTouch("[data-media-test-command]");
        awaitCommandPaint();
        JSONObject[] previous = {null};
        try {
            DeviceUi.await("The command is moving or does not own its touch point: " + label, 5000, () -> {
                if (ui.truth("(()=>{const e=document.querySelector('[data-media-test-command]'),c=document.querySelector('[data-trigger-menu] [role=listbox]');"
                    + "if(!e||!c)return false;const r=e.getBoundingClientRect(),b=c.getBoundingClientRect();return r.top<b.top-1||r.bottom>b.bottom+1})()")) {
                    // Opening a menu can finish restoring the IME after its first
                    // layout; scroll against the current menu viewport again.
                    reachCommandByTouch("[data-media-test-command]"); awaitCommandPaint(); previous[0] = null; return false;
                }
                JSONObject current = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector('[data-media-test-command]');if(!e)return {ready:false};"
                    + "const r=e.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return {rect:r.toJSON(),"
                    + "ready:!e.closest('[inert]')&&r.width>0&&r.height>0&&!!hit&&(e===hit||e.contains(hit))}})()");
                boolean stable = previous[0] != null && current.toString().equals(previous[0].toString());
                previous[0] = current;
                return stable && current.getBoolean("ready");
            });
        } catch (AssertionError failure) {
            JSONObject geometry = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector('[data-media-test-command]'),r=e?.getBoundingClientRect(),"
                + "hit=r?document.elementFromPoint(r.left+r.width/2,r.top+r.height/2):null,describe=n=>n?{tag:n.tagName,role:n.getAttribute('role'),"
                + "text:(n.textContent||'').trim().slice(0,120),inert:!!n.closest('[inert]'),rect:n.getBoundingClientRect().toJSON()}:null,ancestors=[];"
                + "for(let n=e?.parentElement,depth=0;n&&depth<14;n=n.parentElement,depth++){const s=getComputedStyle(n);ancestors.push({tag:n.tagName,role:n.getAttribute('role'),"
                + "rect:n.getBoundingClientRect().toJSON(),scrollTop:n.scrollTop,scrollLeft:n.scrollLeft,clientHeight:n.clientHeight,clientWidth:n.clientWidth,"
                + "scrollHeight:n.scrollHeight,scrollWidth:n.scrollWidth,overflow:s.overflow,overflowX:s.overflowX,overflowY:s.overflowY,"
                + "position:s.position,zIndex:s.zIndex,pointerEvents:s.pointerEvents,opacity:s.opacity,maxHeight:s.maxHeight})}return {target:describe(e),hit:describe(hit),ancestors,"
                + "ime:parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--droy-ime-bottom'))||0,viewport:{width:innerWidth,height:innerHeight,"
                + "visualTop:visualViewport?.offsetTop||0,visualLeft:visualViewport?.offsetLeft||0,visualHeight:visualViewport?.height||innerHeight},scrollY}})()");
            geometry.put("command", label).put("navigation", ui.nativeNavigationState());
            Bundle status = new Bundle();
            status.putString("stream", "\nDROY_MEDIA_COMMAND_GEOMETRY " + geometry + "\n"); instrumentation.sendStatus(2, status);
            capture("command-hit-failure");
            throw failure;
        }
        ui.evaluate("(()=>{window.__mediaCommandEvents=[];const e=document.querySelector('[data-media-test-command]');"
            + "for(const type of ['pointerdown','pointerup','click'])e.addEventListener(type,event=>window.__mediaCommandEvents.push({type,trusted:event.isTrusted,prevented:event.defaultPrevented}),{once:true})})()");
    }

    private void tap(String selector) throws Exception {
        Rect bounds = ui.visibleWebBounds(selector);
        systemTap(bounds.centerX(), bounds.centerY());
    }

    private void systemTap(int x, int y) throws Exception {
        long downTime = SystemClock.uptimeMillis();
        injectVirtualTouch(downTime, android.view.MotionEvent.ACTION_DOWN, x, y);
        try { SystemClock.sleep(40); }
        finally { injectVirtualTouch(downTime, android.view.MotionEvent.ACTION_UP, x, y); }
    }

    private void systemSwipe(float fromX, float fromY, float toX, float toY, long duration) {
        long downTime = SystemClock.uptimeMillis();
        injectVirtualTouch(downTime, android.view.MotionEvent.ACTION_DOWN, fromX, fromY);
        try {
            int steps = Math.max(2, (int) (duration / 16));
            for (int step = 1; step < steps; step++) {
                long target = downTime + duration * step / steps;
                if (target > SystemClock.uptimeMillis()) SystemClock.sleep(target - SystemClock.uptimeMillis());
                float fraction = (float) step / steps;
                injectVirtualTouch(downTime, android.view.MotionEvent.ACTION_MOVE,
                    fromX + (toX - fromX) * fraction, fromY + (toY - fromY) * fraction);
            }
        } finally { injectVirtualTouch(downTime, android.view.MotionEvent.ACTION_UP, toX, toY); }
    }

    private void injectVirtualTouch(long downTime, int action, float x, float y) {
            int deviceId = activeTouchDevice();
            android.view.MotionEvent.PointerProperties pointer = new android.view.MotionEvent.PointerProperties();
            pointer.id = 0; pointer.toolType = android.view.MotionEvent.TOOL_TYPE_FINGER;
            android.view.MotionEvent.PointerCoords coords = new android.view.MotionEvent.PointerCoords();
            coords.x = x; coords.y = y; coords.pressure = action == android.view.MotionEvent.ACTION_UP ? 0 : 1; coords.size = 1;
            android.view.MotionEvent event = android.view.MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                1, new android.view.MotionEvent.PointerProperties[]{pointer}, new android.view.MotionEvent.PointerCoords[]{coords},
                0, 0, 1, 1, deviceId, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
            try { assertTrue("Android rejected the active touchscreen event", instrumentation.getUiAutomation().injectInputEvent(event, true)); }
            finally { event.recycle(); }
    }

    private int activeTouchDevice() {
        if (activeTouchDeviceId != Integer.MIN_VALUE) return activeTouchDeviceId;
        List<Integer> active = new ArrayList<>();
        JSONArray devices = new JSONArray();
        for (int id : android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice device = android.view.InputDevice.getDevice(id);
            if (device == null || !device.supportsSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)) continue;
            android.view.InputDevice.MotionRange x = device.getMotionRange(android.view.MotionEvent.AXIS_X);
            android.view.InputDevice.MotionRange y = device.getMotionRange(android.view.MotionEvent.AXIS_Y);
            boolean valid = x != null && y != null && x.getRange() > 0 && y.getRange() > 0;
            try {
                devices.put(new JSONObject().put("id", id).put("name", device.getName()).put("validRanges", valid)
                    .put("xRange", x == null ? 0 : x.getRange()).put("yRange", y == null ? 0 : y.getRange()));
            } catch (Exception failure) { throw new AssertionError(failure); }
            if (valid) active.add(id);
        }
        Bundle status = new Bundle(); status.putString("stream", "\nDROY_MEDIA_TOUCH_DEVICES " + devices + "\n"); instrumentation.sendStatus(2, status);
        assertEquals("The emulator must expose one active touchscreen with valid X/Y ranges", 1, active.size());
        activeTouchDeviceId = active.get(0);
        return activeTouchDeviceId;
    }

    private void awaitCommandPaint() throws Exception {
        ui.evaluate("window.__mediaCommandFrame=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__mediaCommandFrame=true))");
        DeviceUi.await("The scrolled command did not reach two frames", 5000, () -> ui.truth("window.__mediaCommandFrame"));
        java.util.concurrent.CountDownLatch painted = new java.util.concurrent.CountDownLatch(1);
        android.webkit.WebView web = ui.web();
        instrumentation.runOnMainSync(() -> web.postVisualStateCallback(0, new android.webkit.WebView.VisualStateCallback() {
            @Override public void onComplete(long requestId) { painted.countDown(); }
        }));
        assertTrue("The scrolled command was not committed to the WebView surface", painted.await(5, java.util.concurrent.TimeUnit.SECONDS));
    }

    private void reachCommandByTouch(String selector) throws Exception {
        String viewport = "[data-trigger-menu] [role=listbox]";
        JSONObject position = null;
        for (int attempt = 0; attempt < 24; attempt++) {
            Object measurement = ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
                + "),c=document.querySelector(" + JSONObject.quote(viewport) + ");if(!e||!c)return {targetPresent:!!e,viewportPresent:!!c};"
                + "const r=e.getBoundingClientRect(),b=c.getBoundingClientRect();"
                + "return {targetPresent:true,viewportPresent:true,targetRect:r.toJSON(),viewportRect:b.toJSON(),visible:r.top>=b.top-1&&r.bottom<=b.bottom+1,above:r.top<b.top,height:r.height,available:b.height,"
                + "delta:r.top<b.top?r.top-b.top:r.bottom-b.bottom,scrollTop:c.scrollTop,scrollHeight:c.scrollHeight}})()");
            if (!(measurement instanceof JSONObject measured) || !measured.optBoolean("targetPresent") || !measured.optBoolean("viewportPresent")) {
                reportMediaState("command-scroll-state-failure", -1, -1, List.of());
                Bundle status = new Bundle(); status.putString("stream", "\nDROY_MEDIA_SCROLL_STATE selector=" + selector
                    + " measurement=" + String.valueOf(measurement) + "\n"); instrumentation.sendStatus(2, status);
                capture("command-scroll-state-failure");
                fail("The command or its viewport disappeared during actual scrolling: " + selector + " " + measurement);
            }
            position = (JSONObject) measurement;
            assertTrue("A command row cannot fit in the current menu viewport: " + position,
                position.getDouble("height") <= position.getDouble("available") + 1);
            if (position.getBoolean("visible")) return;
            Rect bounds = ui.webBounds(viewport);
            boolean above = position.getBoolean("above");
            float scale = (float) (bounds.height() / position.getDouble("available"));
            float distance = Math.min(bounds.height() * 0.6f,
                (float) Math.abs(position.getDouble("delta")) * scale + ViewConfiguration.get(context).getScaledTouchSlop() + 8);
            float start = above ? bounds.top + bounds.height() * 0.2f : bounds.bottom - bounds.height() * 0.2f;
            systemSwipe(bounds.centerX(), start, bounds.centerX(), start + (above ? distance : -distance), 500);
        }
        capture("command-scroll-failure");
        fail("The command cannot be reached by actual menu scrolling: " + selector + " " + position);
    }

    private Instrumentation.ActivityMonitor cancelPicker(String action, boolean mime) throws Exception {
        IntentFilter filter = new IntentFilter(action);
        if (mime) filter.addDataType("*/*");
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor(filter,
            new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        instrumentation.addMonitor(monitor); monitors.add(monitor);
        return monitor;
    }

    private Uri createPhoto(String name) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DroyMediaUiFixtures");
        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        assertNotNull(uri); mediaFixtures.add(uri);
        Bitmap bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff1b867a);
        try (java.io.OutputStream output = context.getContentResolver().openOutputStream(uri)) {
            assertNotNull(output); assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
        context.grantUriPermission(context.getPackageName(), uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return uri;
    }

    private void configureLayout() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        if (!args.containsKey("layoutTheme") && !"true".equals(args.getString("layoutMaxTextFont"))) return;
        ui.evaluate("window.dispatchEvent(new CustomEvent('droy-mobile-open-settings',{detail:{section:'general'}}))");
        DeviceUi.await("General settings did not open for the layout condition", 10000,
            () -> ui.truth("!!document.querySelector('[data-droy-theme-choice=light]')"));
        String theme = args.getString("layoutTheme", "light");
        assertTrue(theme.equals("light") || theme.equals("dark"));
        tap("[data-droy-theme-choice=" + theme + "]");
        DeviceUi.await("The real theme control did not apply", 10000,
            () -> theme.equals(preferences.getString("themePreference", "")));
        if ("true".equals(args.getString("layoutMaxTextFont"))) {
            tap("[data-droy-font-expander]");
            String increase = "[data-droy-font-row=size][data-droy-font-role=text] [data-droy-font-step=increase]";
            for (int step = 0; step < 60 && !ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"); step++) {
                Object before = ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()");
                tap(increase);
                DeviceUi.await("The maximum-font preparation tap did not apply", 5000,
                    () -> !before.equals(ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()")));
            }
            assertTrue(ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"));
        }
        tap("[data-droy-settings-back]");
        DeviceUi.await("Layout preparation did not return to chat", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
    }

    private void assertReadable(String selector) throws Exception {
        ui.visibleWebBounds(selector);
        assertTrue("A media control or label clips: " + selector, ui.truth("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
            + ");if(!e)return false;const r=e.getBoundingClientRect();return r.width>0&&r.height>0&&r.left>=-1&&r.right<=innerWidth+1"
            + "&&e.scrollWidth<=e.clientWidth+2&&e.scrollHeight<=e.clientHeight+2})()"));
    }

    private void assertNoHorizontalOverflow(String selector) throws Exception {
        assertTrue("The import dialog has horizontal overflow", ui.truth("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
            + "),r=e.getBoundingClientRect();return r.left>=-1&&r.right<=innerWidth+1&&e.scrollWidth<=e.clientWidth+2})()"));
    }

    private void capture(String stage) throws Exception {
        DeviceUi.await("The media screenshot still has a running finite animation", 5000,
            () -> ui.truth("document.getAnimations().every(a=>!((a.playState==='running'||a.pending)&&a.effect?.getTiming().iterations!==Infinity&&!/caret|cursor/i.test(a.animationName||'')))"));
        ui.evaluate("window.__mediaCapture=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__mediaCapture=true))");
        DeviceUi.await("The media screenshot did not reach a painted frame", 5000, () -> ui.truth("window.__mediaCapture"));
        java.util.concurrent.CountDownLatch painted = new java.util.concurrent.CountDownLatch(1);
        android.webkit.WebView web = ui.web();
        instrumentation.runOnMainSync(() -> web.postVisualStateCallback(0, new android.webkit.WebView.VisualStateCallback() {
            @Override public void onComplete(long requestId) { painted.countDown(); }
        }));
        assertTrue("The media screenshot was not committed to the WebView surface", painted.await(5, java.util.concurrent.TimeUnit.SECONDS));
        JSONObject conditions = (JSONObject) ui.evaluate("({cssWidth:innerWidth,cssHeight:innerHeight,theme:document.body.hasAttribute('data-ds-dark-theme')?'dark':'light',"
            + "dshTextFont:getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim(),"
            + "finiteAnimationCount:document.getAnimations().filter(a=>(a.playState==='running'||a.pending)&&a.effect?.getTiming().iterations!==Infinity&&!/caret|cursor/i.test(a.animationName||'')).length,"
            + "modals:[...document.querySelectorAll('[role=dialog][aria-modal=true]')].filter(e=>e.getClientRects().length>0).map(e=>({"
            + "label:e.getAttribute('aria-label'),backgroundColor:getComputedStyle(e).backgroundColor,opacity:getComputedStyle(e).opacity}))})");
        assertEquals("A finite animation restarted before the screenshot", 0, conditions.getInt("finiteAnimationCount"));
        conditions.put("systemFontScale", context.getResources().getConfiguration().fontScale);
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try {
            File directory = new File(context.getExternalFilesDir(null), "media-input-ui-evidence");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            String label = InstrumentationRegistry.getArguments().getString("mediaEvidenceLabel", "media").replaceAll("[^A-Za-z0-9_-]", "_");
            File output = File.createTempFile(label + "-" + stage + "-", ".png", directory);
            try (FileOutputStream stream = new FileOutputStream(output)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
            Bundle status = new Bundle();
            status.putString("stream", "\nDROY_MEDIA_EVIDENCE " + output.getAbsolutePath() + "\nDROY_MEDIA_CONDITIONS " + conditions + "\n");
            instrumentation.sendStatus(2, status);
        } finally { bitmap.recycle(); }
    }

    private void assertNoModelOrToolRequests() throws Exception {
        if (!home.isDirectory()) return;
        try (java.util.stream.Stream<Path> files = Files.walk(home.toPath())) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!file.getFileName().toString().endsWith(".jsonl") || !Files.isRegularFile(file)) continue;
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    String type = new JSONObject(line).optString("type");
                    assertFalse("Media intake must not call a model or tool", List.of("request/header", "assistant/attempt", "assistant/message", "tool/call").contains(type));
                }
            }
        }
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> result = new TreeMap<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return result;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                String relative = root.relativize(path).toString();
                if (Files.isSymbolicLink(path)) result.put(relative, "link:" + Files.readSymbolicLink(path));
                else if (Files.isDirectory(path)) result.put(relative, "directory");
                else result.put(relative, RuntimeInstaller.hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            }
        }
        return result;
    }
}
