package io.droyagent.androiduse;

import org.junit.Test;
import static org.junit.Assert.*;

public class SnapshotGuardTest {
    @Test public void matchingFreshSnapshotIsAccepted() throws Exception {
        SnapshotGuard.check("snapshot-a", "snapshot-a", 2, 2, 1000, 1001);
    }

    @Test public void windowMutationRevocationAndExpiredSnapshotsAreRejected() throws Exception {
        stale("snapshot-a", "snapshot-a", 2, 3, 1000, 1001);
        stale("snapshot-a", "snapshot-b", 2, 2, 1000, 1001);
        stale(null, null, 2, 2, 1000, 1001);
        stale("snapshot-a", "snapshot-a", 2, 2, 1000, 16001);
        stale("snapshot-a", "snapshot-a", 2, 2, 1000, 999);
    }

    @Test public void nodeBasisUsesHardChangesWhileCoordinatesStillRejectContentChanges() throws Exception {
        SnapshotGuard.checkNode("snapshot-a", "snapshot-a", 7, 7, 1000, 1100);
        stale("snapshot-a", "snapshot-a", 20, 21, 1000, 1100);
        try {
            SnapshotGuard.checkNode("snapshot-a", "snapshot-a", 7, 8, 1000, 1100);
            fail("A window/lifecycle change must revoke the node basis");
        } catch (ActionException expected) {
            assertEquals("stale_snapshot", expected.getCode());
        }
    }

    @Test public void nodeBasisRequiresCurrentIdentityButAgeIsRevalidatedByExecutor() throws Exception {
        for (String actual : new String[]{null, "newer-snapshot"}) {
            try {
                SnapshotGuard.checkNode("snapshot-a", actual, 7, 7, 1000, 1100);
                fail("Consumed or superseded node basis was accepted");
            } catch (ActionException expected) { assertEquals("stale_snapshot", expected.getCode()); }
        }
        SnapshotGuard.checkNode("snapshot-a", "snapshot-a", 7, 7, 1000, 16001);
        stale("snapshot-a", "snapshot-a", 7, 7, 1000, 16001);
    }

    @Test public void rejectionReasonsDistinguishInvalidBasisFromChangedScreen() throws Exception {
        reason("snapshot_missing", null, null, 1, 1, 1000, 1001);
        reason("snapshot_consumed", "old", null, 1, 1, 1000, 1001);
        reason("snapshot_superseded", "old", "new", 1, 1, 1000, 1001);
        reason("window_changed", "same", "same", 1, 2, 1000, 1001);
        reason("snapshot_expired", "same", "same", 1, 1, 1000, 16001);
    }

    private static void reason(String reason, String expected, String actual, long before, long after, long created, long now) throws Exception {
        try { SnapshotGuard.check(expected, actual, before, after, created, now); fail("Expected rejection"); }
        catch (ActionException error) { assertEquals(reason, error.getReason()); }
    }

    private static void stale(String expected, String actual, long savedGeneration,
                              long generation, long created, long now) throws Exception {
        try {
            SnapshotGuard.check(expected, actual, savedGeneration, generation, created, now);
            fail("Expected stale snapshot rejection");
        } catch (ActionException error) {
            assertEquals("stale_snapshot", error.getCode());
        }
    }
}
