package io.droyagent.app;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class ControlPolicyTest {
    @Test public void cancellationBeforeRegistrationRejectsLateRequestEvenAfterReenable() {
        ControlPolicy policy = new ControlPolicy(); policy.enable();
        policy.cancel("task", "late"); policy.end("task"); policy.enable();
        assertThrows(IllegalStateException.class, () -> policy.begin("task", "late"));
        assertNotNull(policy.begin("task", "fresh"));
    }
    @Test public void stopCannotBeUndoneByAnOldTicket() throws Exception {
        ControlPolicy policy = new ControlPolicy(); policy.enable();
        ControlPolicy.Ticket ticket = policy.begin("task1", "call1");
        policy.stop(); policy.enable();
        assertThrows(InterruptedException.class, ticket::check);
    }
    @Test public void cancelOnlyAffectsMatchingTaskAndCall() throws Exception {
        ControlPolicy policy = new ControlPolicy(); policy.enable();
        ControlPolicy.Ticket ticket = policy.begin("task1", "call1");
        policy.cancel("task2", "call1"); ticket.check();
        policy.cancel("task1", "call1"); assertThrows(InterruptedException.class, ticket::check);
    }
    @Test public void deviceOwnershipExcludesAnotherSessionUntilEnd() {
        ControlPolicy policy = new ControlPolicy(); policy.enable();
        ControlPolicy.Ticket ticket = policy.begin("task1", "call1"); policy.finish(ticket);
        assertThrows(IllegalStateException.class, () -> policy.begin("task2", "call2"));
        policy.end("task1"); assertNotNull(policy.begin("task2", "call2"));
    }
    @Test public void stoppedStateRejectsAllNewActions() {
        ControlPolicy policy = new ControlPolicy();
        assertThrows(IllegalStateException.class, () -> policy.begin("task1", "call1"));
    }
    @Test public void oneDeadlineClampsWaitsAndRejectsLateDispatch() throws Exception {
        AtomicLong now = new AtomicLong(100);
        ControlPolicy policy = new ControlPolicy(now::get); policy.enable();
        ControlPolicy.Ticket ticket = policy.begin("task", "call", 1000);
        assertEquals(1000, ticket.remainingMillis(5000));
        now.set(900);
        assertEquals(200, ticket.remainingMillis(5000));
        now.set(1100);
        ControlPolicy.DeadlineExceeded timeout = assertThrows(ControlPolicy.DeadlineExceeded.class, ticket::dispatched);
        assertFalse(timeout.dispatched);
        assertFalse(ticket.wasDispatched());
    }
    @Test public void timeoutAfterDispatchPreservesUnknownOutcomeAndExpiresOldWork() throws Exception {
        AtomicLong now = new AtomicLong(100);
        ControlPolicy policy = new ControlPolicy(now::get); policy.enable();
        ControlPolicy.Ticket old = policy.begin("task", "old", 1000);
        old.dispatched(); now.set(1100);
        assertTrue(assertThrows(ControlPolicy.DeadlineExceeded.class, old::check).dispatched);
        policy.finish(old);
        ControlPolicy.Ticket next = policy.begin("task", "new", 1000);
        next.check();
        assertThrows(InterruptedException.class, old::dispatched);
    }
    @Test public void finishedWaiterCannotDispatchLaterEvenBeforeItsTotalDeadline() throws Exception {
        AtomicLong now = new AtomicLong(100);
        ControlPolicy policy = new ControlPolicy(now::get); policy.enable();
        ControlPolicy.Ticket ticket = policy.begin("task", "old", 10000);
        now.set(5100);
        policy.finish(ticket);
        assertThrows(InterruptedException.class, ticket::dispatched);
        assertFalse(ticket.wasDispatched());
    }
}
