package io.droyagent.androiduse;

final class SnapshotGuard {
    static final long MAX_AGE_MS = 15000;
    private SnapshotGuard() {}

    static void check(String expected, String actual, long savedGeneration, long generation,
                      long createdMs, long nowMs) throws ActionException {
        checkIdentity(expected, actual, savedGeneration, generation);
        if (nowMs < createdMs || nowMs - createdMs > MAX_AGE_MS)
            throw stale("snapshot_expired", "The coordinate snapshot expired; observe again before coordinate input");
    }

    static void checkNode(String expected, String actual, long savedHardGeneration, long hardGeneration,
                          long createdMs, long nowMs) throws ActionException {
        // Callers must also refresh and validate the original node. Coordinate
        // callers use check(), retaining the age limit even when anchored to a node.
        checkIdentity(expected, actual, savedHardGeneration, hardGeneration);
        if (nowMs < createdMs) throw stale("snapshot_expired", "The snapshot clock changed; observe again");
        // Age alone cannot change the identity of a saved Android node. The
        // executor refreshes that exact node and its content before dispatch;
        // coordinates remain bound to the short-lived complete screen above.
    }

    private static void checkIdentity(String expected, String actual, long savedGeneration, long generation) throws ActionException {
        if (expected == null || expected.isEmpty()) throw stale("snapshot_missing", "snapshotId is required");
        if (actual == null) throw stale("snapshot_consumed", "The previous action consumed its snapshot; use its afterObservation or observe again");
        if (!expected.equals(actual)) throw stale("snapshot_superseded", "A newer observation replaced this snapshot; use the newer references");
        if (savedGeneration != generation) throw stale("window_changed", "The screen/window changed; observe the current target again");
    }

    private static ActionException stale(String reason, String message) { return new ActionException("stale_snapshot", reason, message); }
}
