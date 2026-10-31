package io.droyagent.app;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Process-local authority for a voice operation, independent of recording and Android permissions. */
final class VoiceSessionOwnership {
    static final class Failure extends IllegalStateException {
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    static final class Lease {
        final String owner, session;
        private boolean active = true;
        Lease(String owner, String session) { this.owner = owner; this.session = session; }
    }
    private final Map<String, String> admitted = new HashMap<>();
    private Lease current;
    private long startingEvent;
    private String startingOwner;

    static String owner(Object value) {
        if (value == null) return null;
        if (!(value instanceof String)) throw new Failure("VOICE_OPERATION_INVALID");
        String text = (String) value;
        try { if (!UUID.fromString(text).toString().equals(text)) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException invalid) { throw new Failure("VOICE_OPERATION_INVALID"); }
        return text;
    }
    synchronized Lease begin(String owner, String session) {
        if (startingEvent != 0 && !Objects.equals(startingOwner, owner)) throw mismatch(owner);
        if (current != null && current.active) {
            if (!Objects.equals(current.owner, owner) || !current.session.equals(session)) throw mismatch(owner);
            return current;
        }
        if (owner != null && admitted.containsKey(owner)) {
            if (current != null && Objects.equals(current.owner, owner) && current.session.equals(session)) return current;
            throw mismatch(owner);
        }
        if (owner != null) admitted.put(owner, session);
        current = new Lease(owner, session);
        return current;
    }
    synchronized void require(String owner) {
        if (current == null && owner == null) return;
        if (current == null || !Objects.equals(current.owner, owner)) throw mismatch(owner);
    }
    synchronized void requireOutput(String owner) {
        if (current != null && current.active) { require(owner); return; }
        // Legacy standalone readout/preview is available outside a voice call.
        // A retired native operation has no authority over that playback.
        if (owner != null) throw mismatch(owner);
    }
    synchronized boolean end(Lease lease) {
        if (lease == null || current != lease || !lease.active) return false;
        lease.active = false; return true;
    }
    synchronized void endCurrent() { if (current != null) current.active = false; }
    synchronized void retire(String owner) { if (owner != null) admitted.putIfAbsent(owner, ""); }
    synchronized boolean retired(String owner) { return owner != null && admitted.containsKey(owner)
        && !(current != null && current.active && Objects.equals(current.owner, owner)); }
    synchronized boolean active(Lease lease) { return current == lease && lease != null && lease.active; }
    synchronized boolean active() { return current != null && current.active; }
    synchronized String owner() { return current == null ? null : current.owner; }
    synchronized Lease current() { return current; }
    synchronized boolean release(String owner) {
        if (active()) require(owner);
        else if (startingEvent != 0 && !Objects.equals(startingOwner, owner)) throw mismatch(owner);
        else if (startingEvent == 0) require(owner);
        boolean changed = active();
        if (changed) current.active = false;
        if (startingEvent != 0 && Objects.equals(startingOwner, owner)) { clearEvent(); changed = true; }
        return changed;
    }
    synchronized void bindEvent(long id, String owner) {
        if (id <= 0) throw new Failure("VOICE_OWNER_MISMATCH");
        if (active()) require(owner);
        if (startingEvent != 0 && (startingEvent != id || !Objects.equals(startingOwner, owner))) throw mismatch(owner);
        startingEvent = id; startingOwner = owner;
    }
    synchronized void requireEvent(long id, String owner) {
        if (id <= 0 || startingEvent != id || !Objects.equals(startingOwner, owner)) throw mismatch(owner);
    }
    synchronized void clearEvent() { startingEvent = 0; startingOwner = null; }
    synchronized void clearEventFor(String owner) { if (Objects.equals(startingOwner, owner)) clearEvent(); }
    synchronized long eventId() { return startingEvent; }
    synchronized String eventOwner() { return startingOwner; }
    private Failure mismatch(String owner) { return new Failure(owner == null ? "VOICE_OPERATION_REQUIRED" : "VOICE_OWNER_MISMATCH"); }
}
