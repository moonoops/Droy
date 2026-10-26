package io.droyagent.androiduse;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;

public class MemoryReceiptStoreTest {
    private MemoryReceiptStore store() throws Exception {
        return new MemoryReceiptStore(new File(Files.createTempDirectory("droy-memory-receipts").toFile(), "state.json"));
    }
    private JSONObject unknown(String id, String owner, boolean exited) throws Exception {
        return new JSONObject().put("writeReceiptId", id).put("owner", owner).put("callId", "call-" + id)
            .put("readId", "read-" + id).put("status", "outcome_unknown").put("helperExited", exited)
            .put("writeResultKnown", false).put("bytesWritten", JSONObject.NULL)
            .put("helper", new JSONObject().put("pid", 123).put("startTime", "456").put("bootId", "boot"));
    }
    @Test public void endedUnknownReceiptIsReclaimedOnlyAfterHelperExitIsConfirmed() throws Exception {
        MemoryReceiptStore store = store();
        store.save(unknown("one", "old-task", false));
        store.end("old-task");
        assertEquals(1, store.unresolvedHelpers().size());
        JSONObject stillPending = store.find("old-task", "one");
        assertNotNull(stillPending);
        assertTrue(stillPending.getBoolean("taskEnded"));
        store.save(stillPending.put("helperExited", true));
        assertNull(store.find("old-task", "one"));
        assertTrue(store.unresolvedHelpers().isEmpty());
    }
    @Test public void finishedOwnerCanReclaimUnknownExitedRecordsWithoutLosingActiveDeduplication() throws Exception {
        MemoryReceiptStore store = store();
        store.save(unknown("one", "active-task", true));
        assertNotNull(store.duplicate("active-task", "call-one", "read-one"));
        store.end("unrelated-task");
        assertNotNull(store.find("active-task", "one"));
        store.end("active-task");
        assertNull(store.find("active-task", "one"));
        assertTrue(store.forOwner("active-task").length() == 0);
    }
    @Test public void endedFlagSurvivesRestartUntilExitCheck() throws Exception {
        File file = new File(Files.createTempDirectory("droy-memory-restart").toFile(), "state.json");
        MemoryReceiptStore first = new MemoryReceiptStore(file);
        first.save(unknown("one", "old", false)); first.end("old");
        MemoryReceiptStore resumed = new MemoryReceiptStore(file);
        assertEquals(1, resumed.unresolvedHelpers().size());
        JSONObject receipt = resumed.find("old", "one");
        assertTrue(receipt.getBoolean("taskEnded"));
        resumed.save(receipt.put("helperExited", true));
        assertTrue(new MemoryReceiptStore(file).unresolvedHelpers().isEmpty());
    }

    @Test public void taskEndCannotBeOverwrittenByAPreparedReceiptSnapshot() throws Exception {
        MemoryReceiptStore store = store();
        JSONObject preparedSnapshot = unknown("one", "old-task", false).put("status", "pending");
        store.save(preparedSnapshot);
        store.end("old-task");

        // The in-flight operation still owns the JSON captured before task end.
        store.save(preparedSnapshot.put("status", "outcome_unknown"));
        assertTrue(store.find("old-task", "one").getBoolean("taskEnded"));
        assertEquals(1, store.unresolvedHelpers().size());

        store.save(preparedSnapshot.put("status", "written_verified").put("helperExited", true)
            .put("writeResultKnown", true).put("bytesWritten", 4));
        assertNull(store.find("old-task", "one"));
        assertTrue(store.unresolvedHelpers().isEmpty());
    }
}
