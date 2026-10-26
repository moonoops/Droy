package io.droyagent.app.history;

/** A queued callback must still belong to the active consent/lifecycle epoch. */
public final class HistoryEpoch {
    private long epoch;
    private boolean active;
    public synchronized long replace(boolean active) { this.active = active; return ++epoch; }
    public synchronized long ticket() { return active ? epoch : -1; }
    public synchronized boolean accepts(long ticket) { return active && ticket == epoch; }
}
