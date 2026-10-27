package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.media.AudioManager;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.DeviceSettingsTools;
import io.droyagent.androiduse.FileGrantStore;
import io.droyagent.androiduse.SelectedFileTools;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Native state changes are bounded to media volume and an isolated folder selected in Android's picker. */
@RunWith(AndroidJUnit4.class)
public class SettingsFilesDeviceTest {
    private static final Cancellation ACTIVE = () -> {};
    private Instrumentation instrumentation;
    private Context context;
    private UiAutomation automation;
    private SharedPreferences restorePreferences;
    private String originalRecords;
    private DeviceSettingsTools settings;
    private MainActivity pickerOwner;
    private boolean ownsRuntime;

    @Before public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        settings = new DeviceSettingsTools(context);
        restorePreferences = context.getSharedPreferences("droy_setting_restore", Context.MODE_PRIVATE);
        originalRecords = restorePreferences.getString("records", "[]");
    }

    @After public void tearDown() throws Exception {
        assertTrue(restorePreferences.edit().putString("records", originalRecords).commit());
        if (pickerOwner != null) instrumentation.runOnMainSync(pickerOwner::finish);
        if (ownsRuntime) {
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("File picker test left its automatic engine startup active", 15000, RuntimeService::canManageHistory);
        }
    }

    @Test public void mediaVolumeWritesReadBackAndExplicitlyRestores() throws Exception {
        AudioManager audio = context.getSystemService(AudioManager.class);
        JSONObject before = settings.execute("settings_get", new JSONObject().put("name", "media_volume"), ACTIVE);
        assumeTrue(before.getBoolean("writable"));
        int original = before.getInt("value");
        int target = adjacent(before);
        try {
            JSONObject changed = settings.execute("settings_set", new JSONObject().put("name", "media_volume").put("value", target), ACTIVE);
            assertEquals("setting_verified", changed.getString("status"));
            assertEquals(original, changed.getInt("before"));
            assertEquals(target, changed.getInt("after"));
            JSONObject restored = settings.execute("settings_restore", new JSONObject().put("recordId", changed.getString("restoreRecordId")), ACTIVE);
            assertEquals("restored", restored.getString("status"));
            assertTrue(restored.getBoolean("verified"));
            assertEquals(original, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        } finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0); }
    }

    @Test public void userChangedVolumeIsNotOverwrittenByAnOldRestore() throws Exception {
        AudioManager audio = context.getSystemService(AudioManager.class);
        JSONObject before = settings.execute("settings_get", new JSONObject().put("name", "media_volume"), ACTIVE);
        assumeTrue(before.getBoolean("writable"));
        int original = before.getInt("value");
        try {
            JSONObject changed = settings.execute("settings_set", new JSONObject().put("name", "media_volume").put("value", adjacent(before)), ACTIVE);
            assertTrue(changed.getBoolean("verified"));
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0);
            expectAction("restore_conflict", () -> settings.execute("settings_restore",
                    new JSONObject().put("recordId", changed.getString("restoreRecordId")), ACTIVE));
            assertEquals(original, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        } finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0); }
    }

    @Test public void cancellationAfterSavingPendingRecordCannotRestoreALaterUserValue() throws Exception {
        AudioManager audio = context.getSystemService(AudioManager.class);
        JSONObject before = settings.execute("settings_get", new JSONObject().put("name", "media_volume"), ACTIVE);
        assumeTrue(before.getBoolean("writable"));
        int original = before.getInt("value");
        int intended = adjacent(before);
        try {
            Cancellation stopAfterSave = () -> {
                if (!restorePreferences.getString("records", "[]").equals(originalRecords))
                    throw new InterruptedException("Stopped after persisting intent and before Android write");
            };
            assertThrows(InterruptedException.class, () -> settings.execute("settings_set",
                    new JSONObject().put("name", "media_volume").put("value", intended), stopAfterSave));
            assertEquals(original, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
            JSONObject cancelled = settings.listRestoreRecords().getJSONObject(0);
            assertEquals("not_applied", cancelled.getString("state"));
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, intended, 0);
            expectAction("restore_not_confirmed", () -> settings.execute("settings_restore",
                    new JSONObject().put("recordId", cancelled.getString("recordId")), ACTIVE));
            assertEquals("The user's subsequent matching value must remain untouched", intended,
                    audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        } finally { audio.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0); }
    }

    @Test public void pendingAndUnknownRecordsCannotAuthorizeRestoration() throws Exception {
        AudioManager audio = context.getSystemService(AudioManager.class);
        int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        for (String state : new String[]{"pending", "unconfirmed", "not_applied", "restoring", "restore_unconfirmed", "unknown"}) {
            String id = UUID.randomUUID().toString();
            JSONObject record = new JSONObject().put("recordId", id).put("name", "media_volume")
                    .put("before", current == 0 ? 1 : 0).put("written", current).put("state", state);
            assertTrue(restorePreferences.edit().putString("records", new JSONArray().put(record).toString()).commit());
            expectAction("restore_not_confirmed", () -> settings.execute("settings_restore", new JSONObject().put("recordId", id), ACTIVE));
            assertEquals(current, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        }
    }

    @Test public void brightnessWithoutSystemPermissionIsReportedAndRejected() throws Exception {
        assumeFalse("This test requires WRITE_SETTINGS to remain denied", Settings.System.canWrite(context));
        JSONObject before = settings.execute("settings_get", new JSONObject().put("name", "brightness"), ACTIVE);
        assumeTrue(before.getBoolean("available"));
        assertFalse(before.getBoolean("writable"));
        expectAction("setting_not_writable", () -> settings.execute("settings_set",
                new JSONObject().put("name", "brightness").put("value", before.getInt("value")), ACTIVE));
        assertEquals(before.getInt("value"), settings.execute("settings_get", new JSONObject().put("name", "brightness"), ACTIVE).getInt("value"));
    }

    @Test public void brightnessWriteAndRestoreWithSystemGrant() throws Exception {
        assertTrue("Run this test after granting WRITE_SETTINGS", Settings.System.canWrite(context));
        JSONObject before = settings.execute("settings_get", new JSONObject().put("name", "brightness"), ACTIVE);
        assertTrue("The test device must expose brightness", before.getBoolean("available"));
        assertFalse("Select manual brightness before this test", before.getBoolean("automatic"));
        assertTrue(before.getBoolean("writable"));
        int original = before.getInt("value");
        int target = original < before.getInt("max") ? original + 1 : original - 1;
        try {
            JSONObject changed = settings.execute("settings_set", new JSONObject().put("name", "brightness").put("value", target), ACTIVE);
            assertEquals("setting_verified", changed.getString("status"));
            assertEquals(target, changed.getInt("after"));
            JSONObject restored = settings.execute("settings_restore", new JSONObject().put("recordId", changed.getString("restoreRecordId")), ACTIVE);
            assertEquals("restored", restored.getString("status"));
            assertTrue(restored.getBoolean("verified"));
            assertEquals(original, Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS));
        } finally {
            assertTrue(Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, original));
        }
    }

    @Test public void unselectedFilesAndPathsAreRejected() throws Exception {
        SelectedFileTools files = new SelectedFileTools(context);
        expectAction("file_reference_missing", () -> files.execute("file_read", new JSONObject().put("fileId", UUID.randomUUID().toString()), ACTIVE));
        expectAction("file_grant_missing", () -> files.execute("file_export", new JSONObject().put("grantId", UUID.randomUUID().toString())
                .put("name", "unselected.txt").put("text", "test"), ACTIVE));
        expectAction("invalid_argument", () -> files.execute("file_read", new JSONObject().put("fileId", "/data/data/private"), ACTIVE));
    }

    @Test public void systemPickerGrantSupportsReadExportConflictAndRevocation() throws Exception {
        String folder = "DroyFileTest_" + UUID.randomUUID().toString().replace("-", "");
        String path = "/sdcard/Download/" + folder;
        String fixture = "你好，Droy 文件授权验证。\nOnly selected files.\n";
        SharedPreferences filePreferences = context.getSharedPreferences("droy_selected_files", Context.MODE_PRIVATE);
        String previousGrants = filePreferences.getString("grants", "[]");
        String previousReferences = filePreferences.getString("references", "[]");
        FileGrantStore grants = new FileGrantStore(context);
        io.droyagent.androiduse.DeviceDataStore deviceData = new io.droyagent.androiduse.DeviceDataStore(context);
        String binaryOwner = "binary-file-fixture:" + folder;
        String importedReference = null;
        String grantId = null;
        try {
            shell("mkdir -p " + path);
            ownsRuntime = RuntimeService.canManageHistory();
            pickerOwner = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Uri initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/" + folder);
            instrumentation.runOnMainSync(() -> pickerOwner.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 502));
            assertTrue("System picker must show our isolated folder before granting access", waitForText(folder, 10000));
            assertTrue("Select the isolated test folder", clickViewIdOrText("com.android.documentsui:id/action_menu_select",
                    new String[]{"USE THIS FOLDER", "Use this folder", "使用此文件夹", "选择此文件夹"}, 5000));
            long deadline = SystemClock.uptimeMillis() + 12000;
            while (SystemClock.uptimeMillis() < deadline && grantId == null) {
                // DocumentsUI presents its confirmation asynchronously after selecting the tree.
                // Match the confirmation text, rather than a stale button ID from the picker.
                clickViewIdOrText(null, new String[]{"ALLOW", "Allow", "允许"}, 200);
                JSONArray list = grants.listGrants();
                for (int i = 0; i < list.length(); i++) {
                    JSONObject grant = list.getJSONObject(i);
                    if (folder.equals(grant.getString("name"))) grantId = grant.getString("grantId");
                }
                if (grantId == null) SystemClock.sleep(100);
            }
            assertNotNull("Picker result must persist the explicit directory grant", grantId);
            Uri selectedTree = null;
            for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
                Uri candidate = permission.getUri();
                if (DocumentsContract.isTreeUri(candidate)
                        && ("primary:Download/" + folder).equals(DocumentsContract.getTreeDocumentId(candidate))) {
                    assertTrue("The selected fixture directory must retain read access", permission.isReadPermission());
                    assertTrue("The selected fixture directory must retain write access", permission.isWritePermission());
                    selectedTree = candidate;
                }
            }
            assertNotNull("Android must retain the actual selected test tree URI", selectedTree);
            Uri fixtureUri = DocumentsContract.createDocument(context.getContentResolver(),
                    DocumentsContract.buildDocumentUriUsingTree(selectedTree, DocumentsContract.getTreeDocumentId(selectedTree)),
                    "text/plain", "fixture.txt");
            assertNotNull("Create the UTF-8 fixture through the authorized system documents provider", fixtureUri);
            try (OutputStream output = context.getContentResolver().openOutputStream(fixtureUri, "w")) {
                assertNotNull(output);
                output.write(fixture.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            SelectedFileTools files = new SelectedFileTools(context);
            JSONArray children = files.execute("file_list", new JSONObject().put("grantId", grantId), ACTIVE).getJSONArray("items");
            String fixtureId = null;
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.getJSONObject(i);
                if (child.getString("name").equals("fixture.txt")) fixtureId = child.getString("fileId");
            }
            assertNotNull(fixtureId);
            assertEquals(fixture, files.execute("file_read", new JSONObject().put("fileId", fixtureId), ACTIVE).getString("text"));
            JSONObject export = files.execute("file_export", new JSONObject().put("grantId", grantId)
                    .put("name", "export.txt").put("text", fixture), ACTIVE);
            assertEquals("export_verified", export.getString("status"));
            assertTrue(export.getBoolean("createdNewDocument"));
            assertEquals(fixture, files.execute("file_read", new JSONObject().put("fileId", export.getString("fileId")), ACTIVE).getString("text"));
            byte[] binary = new byte[512];
            for (int index = 0; index < binary.length; index++) binary[index] = (byte) index;
            JSONObject source = deviceData.put(binaryOwner, binary, new JSONObject().put("source", "synthetic_fixture"), false, "Binary fixture");
            JSONObject binaryExport = deviceData.execute(new JSONObject().put("operation", "export").put("dataRef", source.getString("dataRef"))
                .put("grantId", grantId).put("name", "binary.bin"), binaryOwner, ACTIVE);
            assertEquals("export_verified", binaryExport.getString("status"));
            assertTrue(binaryExport.getBoolean("verified"));
            JSONObject imported = deviceData.execute(new JSONObject().put("operation", "import").put("fileId", binaryExport.getString("fileId"))
                .put("name", "Synthetic imported binary"), binaryOwner, ACTIVE);
            importedReference = imported.getString("dataRef");
            assertArrayEquals(binary, deviceData.readPayload(new JSONObject().put("dataRef", importedReference), binaryOwner, binary.length));
            String selected = grantId;
            expectAction("file_name_conflict", () -> files.execute("file_export", new JSONObject().put("grantId", selected)
                    .put("name", "export.txt").put("text", "must not overwrite"), ACTIVE));
            assertEquals(fixture, files.execute("file_read", new JSONObject().put("fileId", export.getString("fileId")), ACTIVE).getString("text"));
            expectAction("file_name_conflict", () -> deviceData.execute(new JSONObject().put("operation", "export")
                .put("dataRef", source.getString("dataRef")).put("grantId", selected).put("name", "binary.bin"), binaryOwner, ACTIVE));
            grants.revokeGrant(grantId);
            grantId = null;
            expectAction("file_reference_missing", () -> files.execute("file_read", new JSONObject().put("fileId", export.getString("fileId")), ACTIVE));
            expectAction("file_reference_missing", () -> deviceData.execute(new JSONObject().put("operation", "import")
                .put("fileId", binaryExport.getString("fileId")), binaryOwner, ACTIVE));
        } finally {
            if (importedReference != null) deviceData.execute(new JSONObject().put("operation", "delete").put("dataRef", importedReference), binaryOwner, ACTIVE);
            deviceData.closeOwner(binaryOwner);
            JSONArray remaining = grants.listGrants();
            for (int i = 0; i < remaining.length(); i++) {
                JSONObject item = remaining.getJSONObject(i);
                if (folder.equals(item.getString("name"))) grants.revokeGrant(item.getString("grantId"));
            }
            assertTrue(filePreferences.edit().putString("grants", previousGrants).putString("references", previousReferences).commit());
            shell("rm -rf " + path);
        }
    }

    private static int adjacent(JSONObject before) throws Exception {
        int original = before.getInt("value"), min = before.getInt("min"), max = before.getInt("max");
        assumeTrue(max > min);
        return original < max ? original + 1 : original - 1;
    }

    private boolean waitForText(String text, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            if (findNode(null, new String[]{text}, false)) return true;
            SystemClock.sleep(100);
        }
        return false;
    }

    private boolean clickViewIdOrText(String id, String[] text, long timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            if (findNode(id, text, true)) return true;
            SystemClock.sleep(100);
        }
        return false;
    }

    private boolean findNode(String id, String[] texts, boolean click) {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null) return false;
        root.refresh();
        ArrayDeque<AccessibilityNodeInfo> nodes = new ArrayDeque<>();
        nodes.add(root);
        boolean matched = false;
        try {
            while (!nodes.isEmpty()) {
                AccessibilityNodeInfo node = nodes.removeFirst();
                try {
                    boolean match = id != null && id.equals(node.getViewIdResourceName());
                    for (String text : texts) match |= text.contentEquals(node.getText() == null ? "" : node.getText())
                            || text.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription());
                    if (match && node.isEnabled() && (!click || node.performAction(AccessibilityNodeInfo.ACTION_CLICK))) {
                        matched = true; break;
                    }
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) nodes.add(child);
                    }
                } finally { node.recycle(); }
            }
        } finally { while (!nodes.isEmpty()) nodes.removeFirst().recycle(); }
        return matched;
    }

    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = automation.executeShellCommand(command);
             InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            input.readAllBytes();
        }
    }

    private static void expectAction(String code, CheckedAction action) throws Exception {
        ActionException failure = assertThrows(ActionException.class, action::run);
        assertEquals(code, failure.getCode());
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
