package io.droyagent.androiduse;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DeviceDataStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private DeviceDataStore store() {
        return new DeviceDataStore(temporary.getRoot().toPath().resolve("saved"), temporary.getRoot().toPath().resolve("cache"));
    }

    @Test public void stopAtDispatchPreventsSavingOrDeletingARecord() throws Exception {
        DeviceDataStore data = store();
        Cancellation stopped = new Cancellation() {
            public void check() {}
            public void dispatched() throws InterruptedException { throw new InterruptedException(); }
        };
        assertThrows(InterruptedException.class, () -> data.execute(new JSONObject().put("operation", "save").put("hex", "0102"), "task-a", stopped));
        assertEquals(0, data.execute(new JSONObject().put("operation", "list"), "task-a", () -> {}).getJSONArray("items").length());
        JSONObject record = data.put("task-a", new byte[]{1, 2}, new JSONObject(), true, "fixture");
        assertThrows(InterruptedException.class, () -> data.execute(new JSONObject().put("operation", "delete").put("dataRef", record.getString("dataRef")), "task-a", stopped));
        assertArrayEquals(new byte[]{1, 2}, data.readPayload(new JSONObject().put("dataRef", record.getString("dataRef")), "task-a", 2));
    }

    @Test public void binaryRoundTripsWithoutTextConversionAcrossRestart() throws Exception {
        DeviceDataStore first = store();
        byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        JSONObject item = first.put("task-a", bytes, new JSONObject().put("source", "synthetic"), true, "测试卡");
        DeviceDataStore restarted = store();
        assertArrayEquals(bytes, restarted.readPayload(new JSONObject().put("dataRef", item.getString("dataRef")), "task-b", 256));
        JSONObject page = restarted.execute(new JSONObject().put("operation", "read").put("dataRef", item.getString("dataRef"))
                .put("offset", 250).put("limit", 10), "task-b", () -> {});
        assertEquals("fafbfcfdfeff", page.getString("hex"));
        assertEquals(256, page.getInt("bytes"));
        assertEquals(6, page.getInt("returnedBytes"));
        assertFalse(page.getBoolean("hasMore"));
        assertTrue(page.getBoolean("integrityVerified"));
    }

    @Test public void temporaryReferencesCannotCrossTaskAndCleanupKeepsSavedRecords() throws Exception {
        DeviceDataStore store = store();
        JSONObject transientItem = store.put("task-a", new byte[]{0, 1, -1}, new JSONObject(), false, "temporary");
        JSONObject savedItem = store.put("task-a", new byte[]{2}, new JSONObject(), true, "saved");
        JSONObject reference = new JSONObject().put("dataRef", transientItem.getString("dataRef"));
        assertThrows(ActionException.class, () -> store.readPayload(reference, "task-b", 10));
        assertEquals(1, store.execute(new JSONObject().put("operation", "list"), "task-b", () -> {}).getJSONArray("items").length());
        store.closeOwner("task-a");
        assertThrows(ActionException.class, () -> store.readPayload(reference, "task-a", 10));
        assertArrayEquals(new byte[]{2}, store.readPayload(new JSONObject().put("dataRef", savedItem.getString("dataRef")), "task-b", 10));
    }

    @Test public void savingTemporaryDataPreservesCoverageAndExplicitDeletionSurvivesRestart() throws Exception {
        DeviceDataStore store = store();
        JSONObject item = store.put("task-a", new byte[]{9, 8}, new JSONObject().put("complete", false)
                .put("readRanges", "0..1"), false, "partial");
        JSONObject saved = store.execute(new JSONObject().put("operation", "save").put("dataRef", item.getString("dataRef"))
                .put("metadata", new JSONObject().put("complete", true).put("note", "user label")), "task-a", () -> {});
        assertFalse(saved.getJSONObject("metadata").getBoolean("complete"));
        assertEquals("0..1", saved.getJSONObject("metadata").getString("readRanges"));
        store.closeOwner("task-a");
        assertArrayEquals(new byte[]{9, 8}, store.readPayload(new JSONObject().put("dataRef", saved.getString("dataRef")), "task-b", 20));
        store.execute(new JSONObject().put("operation", "delete").put("dataRef", saved.getString("dataRef")), "task-b", () -> {});
        assertThrows(ActionException.class, () -> store().readPayload(new JSONObject().put("dataRef", saved.getString("dataRef")), "task-a", 20));
    }

    @Test public void payloadAndReferenceValidationRejectsAmbiguityTraversalAndOversize() throws Exception {
        DeviceDataStore store = store();
        assertArrayEquals(new byte[]{0, -1, 16}, store.readPayload(new JSONObject().put("hex", "00ff10"), "task-a", 3));
        for (String invalid : new String[]{"0", "gg", "０１"})
            assertThrows(ActionException.class, () -> store.readPayload(new JSONObject().put("hex", invalid), "task-a", 10));
        for (String invalid : new String[]{"../saved", "/data/data/app", "content://provider/document/1", "dd_../../x"})
            assertThrows(ActionException.class, () -> store.readPayload(new JSONObject().put("dataRef", invalid), "task-a", 10));
        assertThrows(ActionException.class, () -> store.readPayload(new JSONObject().put("hex", "00").put("dataRef", "dd_any"), "task-a", 10));
        assertThrows(ActionException.class, () -> store.readPayload(new JSONObject().put("hex", "0011"), "task-a", 1));
        assertThrows(ActionException.class, () -> store.put("", new byte[0], new JSONObject(), true, ""));
    }

    @Test public void integrityFailureNeverReturnsCorruptPayloadOrMissingZeros() throws Exception {
        DeviceDataStore store = store();
        JSONObject item = store.put("task-a", new byte[]{1, 2, 3, 4}, new JSONObject(), true, "record");
        Path path = temporary.getRoot().toPath().resolve("saved").resolve(item.getString("dataRef") + ".data");
        byte[] corrupt = Files.readAllBytes(path); corrupt[corrupt.length - 1] = 99; Files.write(path, corrupt);
        ActionException changed = assertThrows(ActionException.class, () -> store.readPayload(new JSONObject().put("dataRef", item.getString("dataRef")), "task-a", 20));
        assertEquals("device_data_corrupt", changed.getCode());
        Files.write(path, Arrays.copyOf(corrupt, corrupt.length - 1));
        assertThrows(ActionException.class, () -> store.readPayload(new JSONObject().put("dataRef", item.getString("dataRef")), "task-a", 20));
    }

    @Test public void cancellationPreventsMutationsAndPaginationIsExplicit() throws Exception {
        DeviceDataStore store = store();
        assertThrows(InterruptedException.class, () -> store.execute(new JSONObject().put("operation", "save").put("hex", "000102"), "task-a", () -> { throw new InterruptedException(); }));
        assertEquals(0, store.execute(new JSONObject().put("operation", "list"), "task-a", () -> {}).getJSONArray("items").length());
        JSONObject item = store.put("task-a", new byte[]{1, 2, 3}, new JSONObject(), true, "record");
        JSONObject page = store.execute(new JSONObject().put("operation", "read").put("dataRef", item.getString("dataRef")).put("limit", 2), "task-a", () -> {});
        assertEquals("0102", page.getString("hex")); assertTrue(page.getBoolean("hasMore")); assertEquals(2, page.getInt("nextOffset"));
        assertThrows(ActionException.class, () -> store.execute(new JSONObject().put("operation", "read").put("dataRef", item.getString("dataRef")).put("offset", 4), "task-a", () -> {}));
    }

    @Test public void metadataIsCopiedAndHexCodecPreservesEveryByte() throws Exception {
        JSONObject metadata = new JSONObject().put("source", "fixture");
        JSONObject item = store().put("task-a", new byte[]{0}, metadata, true, "record");
        metadata.put("source", "changed");
        JSONObject page = store().execute(new JSONObject().put("operation", "read").put("dataRef", item.getString("dataRef")), "task-a", () -> {});
        assertEquals("fixture", page.getJSONObject("metadata").getString("source"));
        byte[] bytes = new byte[256]; for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        assertArrayEquals(bytes, HardwareSupport.unhex(HardwareSupport.hex(bytes), 256));
    }
}
