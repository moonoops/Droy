package io.droyagent.app;

/** A disconnected listener gets one rebind attempt; missing permission is never repaired by enabling it. */
final class NotificationAccessPolicy {
    static final long REBIND_DELAY_MS = 3000;
    static final long CONNECTION_GRACE_MS = 15000;
    record Result(String state, boolean actionRequired, String action, boolean requestRebind, long checkAfterMs) { }
    private long disconnectedSince = -1;
    private boolean rebindAttempted;

    Result observe(boolean enabled, boolean paused, boolean readGranted, boolean connected, boolean postGranted, long now) {
        if (!enabled || paused || !readGranted || connected) {
            disconnectedSince = -1;
            rebindAttempted = false;
            if (!enabled) return new Result("off", false, "", false, 0);
            if (paused) return new Result("paused", false, "", false, 0);
            if (!readGranted) return new Result("read_permission_required", true, "notificationAccess", false, 0);
            return postGranted ? new Result("ready", false, "", false, 0)
                    : new Result("post_permission_required", true, "notifications", false, 0);
        }
        if (disconnectedSince < 0) disconnectedSince = now;
        long elapsed = Math.max(0, now - disconnectedSince);
        boolean rebind = !rebindAttempted && elapsed >= REBIND_DELAY_MS;
        if (rebind) rebindAttempted = true;
        long next = !rebindAttempted ? REBIND_DELAY_MS - elapsed : elapsed < CONNECTION_GRACE_MS ? CONNECTION_GRACE_MS - elapsed : 0;
        if (!postGranted) return new Result("post_permission_required", true, "notifications", rebind, next);
        boolean expired = elapsed >= CONNECTION_GRACE_MS;
        return new Result(expired ? "listener_unavailable" : "connecting", expired,
                expired ? "notificationAccess" : "", rebind, next);
    }
}
