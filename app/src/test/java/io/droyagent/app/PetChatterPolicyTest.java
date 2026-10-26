package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class PetChatterPolicyTest {
    @Test public void taskStartIsImmediateAndRapidStagesCoalesceToTheLatestStableStage() {
        PetChatterPolicy policy = new PetChatterPolicy();
        assertEquals("started", policy.observe("task", "started", 100));
        assertEquals("", policy.observe("task", "observing", 200));
        assertEquals("", policy.observe("task", "controlling", 300));
        assertEquals("", policy.observe("task", "observing", 500));
        assertEquals(3600, policy.remaining(500));
        assertEquals("started", policy.current());
        assertEquals("", policy.observe("task", "observing", 4099));
        assertEquals("observing", policy.observe("task", "observing", 4100));
        assertEquals("", policy.observe("task", "observing", 9000));
        assertEquals(0, policy.remaining(9000));
    }

    @Test public void intermediateStagesCannotResurfaceAndNewTasksHaveIndependentTiming() {
        PetChatterPolicy policy = new PetChatterPolicy();
        policy.observe("old", "controlling", 0);
        assertEquals("", policy.observe("old", "observing", 400));
        assertEquals("observing", policy.observe("old", "observing", 1000));
        policy.observe("old", "controlling", 1100);
        assertEquals("", policy.observe("old", "observing", 1200));
        assertEquals(0, policy.remaining(1200));
        assertEquals("started", policy.observe("new", "started", 1200));
    }

    @Test public void pendingQuestionsErrorsAndCompletionDiscardBufferedChatter() {
        PetChatterPolicy policy = new PetChatterPolicy();
        policy.observe("task", "started", 0);
        policy.observe("task", "thinking", 100);
        assertEquals("", policy.observe("task", "", 200));
        assertEquals("", policy.current());
        assertEquals(0, policy.remaining(5000));
        assertEquals("", policy.observe("task", "observing", 6000));
        assertEquals("observing", policy.observe("task", "observing", 6600));
        policy.reset();
        assertEquals("", policy.current());
    }
}
