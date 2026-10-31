package io.droyagent.app;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceSessionOwnershipTest {
    private final String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
    private static void rejected(String code, Runnable action) {
        try { action.run(); fail("Expected owner refusal"); }
        catch (VoiceSessionOwnership.Failure failure) { assertEquals(code, failure.code); }
    }
    @Test public void aSessionIdDoesNotAllowAnotherOperationToAdoptTheCapture() {
        VoiceSessionOwnership owner = new VoiceSessionOwnership();
        VoiceSessionOwnership.Lease first = owner.begin(a, "session-r");
        assertSame(first, owner.begin(a, "session-r"));
        rejected("VOICE_OWNER_MISMATCH", () -> owner.begin(b, "session-r"));
        rejected("VOICE_OPERATION_REQUIRED", () -> owner.begin(null, "session-r"));
        assertEquals(a, owner.owner()); assertTrue(owner.active(first));
    }
    @Test public void onlyOneConcurrentClientCanAtomicallyOwnTheSameSession() throws Exception {
        VoiceSessionOwnership owner = new VoiceSessionOwnership();
        CountDownLatch gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> claim(owner, gate, a));
            Future<Boolean> second = pool.submit(() -> claim(owner, gate, b));
            gate.countDown(); assertNotEquals(first.get(), second.get()); assertTrue(owner.active());
        } finally { pool.shutdownNow(); }
    }
    private static boolean claim(VoiceSessionOwnership owner, CountDownLatch gate, String id) throws Exception {
        gate.await();
        try { owner.begin(id, "session-r"); return true; }
        catch (VoiceSessionOwnership.Failure expected) { return false; }
    }
    @Test public void aLateFailedStartCannotReleaseTheNextOwnersLease() {
        VoiceSessionOwnership owner = new VoiceSessionOwnership();
        VoiceSessionOwnership.Lease old = owner.begin(a, "session-r"); owner.end(old);
        VoiceSessionOwnership.Lease current = owner.begin(b, "session-r");
        assertFalse(owner.end(old)); assertTrue(owner.active(current));
        rejected("VOICE_OWNER_MISMATCH", () -> owner.release(a));
        rejected("VOICE_OPERATION_REQUIRED", () -> owner.release(null));
        assertTrue(owner.active(current));
    }
    @Test public void aRetiredNativeOperationNeverStartsAgainEvenAfterAnotherOperationEnds() {
        VoiceSessionOwnership owner = new VoiceSessionOwnership();
        VoiceSessionOwnership.Lease old = owner.begin(a, "session-r"); owner.end(old);
        assertSame(old, owner.begin(a, "session-r")); assertFalse(owner.active(old));
        VoiceSessionOwnership.Lease next = owner.begin(b, "session-r"); owner.end(next);
        rejected("VOICE_OWNER_MISMATCH", () -> owner.begin(a, "session-r"));
        rejected("VOICE_OWNER_MISMATCH", () -> owner.begin(b, "different-session"));
        assertFalse(owner.active()); assertEquals(b, owner.owner());
    }
    @Test public void stopIntentBeforeStartRetiresOnlyThatOperationWithoutAffectingAnotherOwner() {
        VoiceSessionOwnership owner = new VoiceSessionOwnership();
        VoiceSessionOwnership.Lease other = owner.begin(b, "session-r"); owner.retire(a);
        assertTrue(owner.active(other)); assertEquals(b, owner.owner());
        rejected("VOICE_OWNER_MISMATCH", () -> owner.begin(a, "session-r"));
        owner.end(other);
        rejected("VOICE_OWNER_MISMATCH", () -> owner.begin(a, "session-r"));
        assertFalse(owner.active()); assertEquals(b, owner.owner());
    }
    @Test public void stopBeforeAnyLeaseStillPreventsTheLateStart() {
        VoiceSessionOwnership owner = new VoiceSessionOwnership(); owner.retire(a);
        rejected("VOICE_OWNER_MISMATCH", () -> owner.begin(a, "session-r"));
        assertNull(owner.owner()); assertFalse(owner.active());
        assertTrue(owner.retired(a)); assertFalse(owner.retired(b));
    }
    @Test public void pollControlAndOutputAreFencedFromLegacyAndRetiredOperations() {
        VoiceSessionOwnership owner = new VoiceSessionOwnership();
        VoiceSessionOwnership.Lease first = owner.begin(a, "session-r");
        owner.require(a); owner.requireOutput(a);
        rejected("VOICE_OPERATION_REQUIRED", () -> owner.require(null));
        rejected("VOICE_OWNER_MISMATCH", () -> owner.requireOutput(b));
        owner.end(first); owner.requireOutput(null);
        rejected("VOICE_OWNER_MISMATCH", () -> owner.requireOutput(a));
        owner.begin(b, "session-r");
        rejected("VOICE_OWNER_MISMATCH", () -> owner.require(a));
        rejected("VOICE_OPERATION_REQUIRED", () -> owner.requireOutput(null));
    }
    @Test public void legacyCaptureCanRestartButCannotAdoptNativeCapture() {
        VoiceSessionOwnership owner = new VoiceSessionOwnership();
        VoiceSessionOwnership.Lease old = owner.begin(null, "session-r"); owner.end(old);
        VoiceSessionOwnership.Lease next = owner.begin(null, "session-r"); assertNotSame(old, next);
        rejected("VOICE_OWNER_MISMATCH", () -> owner.begin(a, "session-r"));
        owner.end(next); owner.begin(a, "session-r");
        rejected("VOICE_OPERATION_REQUIRED", () -> owner.require(null));
    }
    @Test public void consumedWakeEventBindsTheOperationBeforeItClaimsTheMicrophone() {
        VoiceSessionOwnership owner = new VoiceSessionOwnership(); owner.bindEvent(17, a);
        owner.requireEvent(17, a);
        rejected("VOICE_OWNER_MISMATCH", () -> owner.begin(b, "voice"));
        rejected("VOICE_OPERATION_REQUIRED", () -> owner.begin(null, "voice"));
        rejected("VOICE_OWNER_MISMATCH", () -> owner.requireEvent(17, b));
        rejected("VOICE_OWNER_MISMATCH", () -> owner.bindEvent(18, b));
        assertEquals(17, owner.eventId()); assertEquals(a, owner.eventOwner());
    }
    @Test public void consumedButUnclaimedWakeCanBeReleasedByItsOwnerOnly() {
        VoiceSessionOwnership owner = new VoiceSessionOwnership(); owner.bindEvent(17, a);
        rejected("VOICE_OWNER_MISMATCH", () -> owner.release(b));
        assertTrue(owner.release(a)); assertEquals(0, owner.eventId());
        owner.bindEvent(18, b); VoiceSessionOwnership.Lease next = owner.begin(b, "voice");
        rejected("VOICE_OWNER_MISMATCH", () -> owner.release(a));
        assertTrue(owner.active(next)); assertEquals(18, owner.eventId());
    }
    @Test public void releasingAConsumedWakeDoesNotDependOnAnOlderRetiredLease() {
        VoiceSessionOwnership owner = new VoiceSessionOwnership(); owner.end(owner.begin(b, "voice"));
        owner.bindEvent(17, a); assertTrue(owner.release(a)); assertEquals(0, owner.eventId());
        assertEquals(b, owner.owner()); assertFalse(owner.active());
    }
    @Test public void realGlobalStopEndsTheCurrentLeaseAndWakeBinding() {
        VoiceSessionOwnership owner = new VoiceSessionOwnership(); owner.bindEvent(17, a); owner.begin(a, "voice");
        owner.endCurrent(); owner.clearEvent(); assertFalse(owner.active()); assertEquals(0, owner.eventId());
        assertEquals(a, owner.owner());
    }
    @Test public void operationIdsAreCanonicalUuidsAndNullOnlyMeansLegacyWhenOmitted() {
        assertNull(VoiceSessionOwnership.owner(null)); assertEquals(a, VoiceSessionOwnership.owner(a));
        for (Object value : new Object[]{"", "../../capture", 17, "12345678-1234-1234-ABCD-123456789ABC", "12345678-1234-1234-1234-12345678"})
            rejected("VOICE_OPERATION_INVALID", () -> VoiceSessionOwnership.owner(value));
    }
}
