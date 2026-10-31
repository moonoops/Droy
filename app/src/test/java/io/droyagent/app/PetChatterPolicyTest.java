package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class PetChatterPolicyTest {
    @Test public void taskStartIsImmediateAndTheNextUpdateWaitsThirtySeconds() {
        PetChatterPolicy policy = new PetChatterPolicy();
        assertEquals("started", policy.observe("task", "started", 100));
        assertEquals("", policy.observe("task", "thinking", 200));
        assertEquals(29_900, policy.remaining(200));
        assertEquals("started", policy.current());
        assertEquals("", policy.observe("task", "thinking", 30_099));
        assertEquals(1, policy.remaining(30_099));
        assertEquals("thinking", policy.observe("task", "thinking", 30_100));
        assertEquals(0, policy.remaining(30_100));
    }

    @Test public void rapidStagesCoalesceWithoutShowingAnUnstableFirstUpdate() {
        PetChatterPolicy policy = new PetChatterPolicy();
        assertEquals("", policy.observe("task", "thinking", 0));
        assertEquals("", policy.observe("task", "observing", 300));
        assertEquals("", policy.observe("task", "controlling", 1000));
        assertEquals(1500, policy.remaining(1000));
        assertEquals("", policy.observe("task", "controlling", 2499));
        assertEquals(1, policy.remaining(2499));
        assertEquals("controlling", policy.observe("task", "controlling", 2500));
        assertEquals("controlling", policy.current());
        assertEquals(0, policy.remaining(2500));
    }

    @Test public void crossingTheIntervalStillRequiresTheLatestStageToSettle() {
        PetChatterPolicy policy = new PetChatterPolicy();
        policy.observe("task", "started", 0);
        policy.observe("task", "thinking", 100);
        assertEquals("", policy.observe("task", "observing", 29_900));
        assertEquals("", policy.observe("task", "observing", 30_000));
        assertEquals(1400, policy.remaining(30_000));
        assertEquals("", policy.observe("task", "controlling", 30_500));
        assertEquals("controlling", policy.observe("task", "controlling", 32_000));
    }

    @Test public void alreadyAnnouncedStagesNeverRepeatOrLeaveAnOutdatedCandidate() {
        PetChatterPolicy policy = new PetChatterPolicy();
        policy.observe("task", "started", 0);
        policy.observe("task", "thinking", 100);
        assertEquals("thinking", policy.observe("task", "thinking", 30_000));
        policy.observe("task", "observing", 31_000);
        assertEquals("", policy.observe("task", "started", 32_000));
        assertEquals(0, policy.remaining(32_000));
        assertEquals("", policy.observe("task", "thinking", 100_000));
        assertEquals(0, policy.remaining(100_000));
        assertEquals("thinking", policy.current());
    }

    @Test public void taskBudgetStopsAfterThreeOrdinaryMessagesAndSchedulesNothingMore() {
        PetChatterPolicy policy = new PetChatterPolicy();
        policy.observe("task", "started", 0);
        policy.observe("task", "thinking", 100);
        assertEquals("thinking", policy.observe("task", "thinking", 30_000));
        policy.observe("task", "observing", 30_100);
        assertEquals("observing", policy.observe("task", "observing", 60_000));
        for (long now : new long[] {60_001, 90_000, 300_000}) {
            assertEquals("", policy.observe("task", "controlling", now));
            assertEquals("No suppressed candidate may keep scheduling refreshes", 0, policy.remaining(now));
        }
        assertEquals("observing", policy.current());
        assertEquals("started", policy.observe("next-task", "started", 300_001));
    }

    @Test public void temporaryQuestionsOrErrorsDiscardCandidatesWithoutRenewingTheBudget() {
        PetChatterPolicy policy = new PetChatterPolicy();
        policy.observe("task", "started", 0);
        policy.observe("task", "thinking", 100);
        assertEquals("", policy.observe("task", "", 200));
        assertEquals("started", policy.current());
        assertEquals(0, policy.remaining(5000));
        assertEquals("", policy.observe("task", "started", 6000));
        policy.observe("task", "thinking", 7000);
        assertEquals(23_000, policy.remaining(7000));
        assertEquals("thinking", policy.observe("task", "thinking", 30_000));
        policy.observe("task", "observing", 30_100);
        assertEquals("observing", policy.observe("task", "observing", 60_000));
        policy.observe("task", "", 60_100);
        assertEquals("", policy.observe("task", "controlling", 100_000));
        assertEquals(0, policy.remaining(100_000));
    }

    @Test public void completionInterruptionAndExplicitResetClearTheWholeTurn() {
        PetChatterPolicy policy = new PetChatterPolicy();
        for (String terminal : new String[] {"completed", "interrupted"}) {
            assertEquals("started", policy.observe("task", "started", 0));
            policy.observe("task", "thinking", 100);
            assertEquals("", policy.observe("task", terminal, 200));
            assertEquals("", policy.current());
            assertEquals(0, policy.remaining(30_000));
        }
        assertEquals("started", policy.observe("task", "started", 0));
        policy.reset();
        assertEquals("", policy.current());
        assertEquals(0, policy.remaining(30_000));
        assertEquals("started", policy.observe("task", "started", 0));
        assertEquals("", policy.observe("", "thinking", 100));
        assertEquals("", policy.current());
        assertEquals(0, policy.remaining(30_000));
    }
}
