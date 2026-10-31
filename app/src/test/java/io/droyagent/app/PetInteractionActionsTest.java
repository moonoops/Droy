package io.droyagent.app;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class PetInteractionActionsTest {
    @Test public void everySpeciesSignatureIsDeliberatelyReachableWithoutTaskOrDirectionAliases() {
        for (Set<String> states : List.of(Set.of("knead", "tailCurl", "sleep"), Set.of("waveHands", "scream", "startle"),
                Set.of("neckStretch", "spitForward", "spitScreen"))) {
            assertEquals(states, Set.copyOf(PetInteractionActions.available(states::contains)));
        }
        List<String> all = PetInteractionActions.available(id -> true);
        assertFalse(all.contains("completed")); assertFalse(all.contains("working")); assertFalse(all.contains("peekLeft"));
        assertEquals(all.size(), Set.copyOf(all).size());
        assertTrue(PetInteractionActions.available(id -> false).isEmpty());
    }
    @Test public void anAnimationReturnsToTheCurrentTaskStateWithQuestionPriority() {
        assertEquals("working", PetInteractionActions.restingState("executing", false, id -> true));
        assertEquals("working", PetInteractionActions.restingState("thinking", false, id -> true));
        assertEquals("waiting", PetInteractionActions.restingState("executing", true, id -> true));
        assertEquals("stopped", PetInteractionActions.restingState("interrupted", false, id -> true));
        assertEquals("blocked", PetInteractionActions.restingState("blocked", false, id -> true));
        assertEquals("idle", PetInteractionActions.restingState("completed", false, id -> false));
        assertEquals("waiting", PetInteractionActions.restingState("blocked", false, id -> false));
        for (String phase : List.of("pausing", "paused", "stopping"))
            assertEquals("waiting", PetInteractionActions.restingState(phase, false, id -> true));
        assertEquals("working", PetInteractionActions.restingState("resuming", false, id -> true));
        assertEquals("waiting", PetInteractionActions.restingState("resuming", true, id -> true));
    }

    @Test public void idleSingleTapUsesSpeciesSignaturesForExactlySeventyOfOneHundredPoolDraws() {
        for (Set<String> signature : List.of(Set.of("knead", "tailCurl", "sleep"), Set.of("waveHands", "scream", "startle"),
                Set.of("neckStretch", "spitForward", "spitScreen"))) {
            Set<String> states = new HashSet<>(signature);
            states.addAll(Set.of("tapTilt", "pet", "idleStretch"));
            int signatureCount = 0;
            for (int roll = 0; roll < 100; roll++) {
                int poolDraw = roll;
                var selector = new PetInteractionActions.IdleSelector(bound -> bound == 100 ? poolDraw : 0);
                String selected = selector.next(states::contains);
                assertTrue(states.contains(selected));
                assertEquals("The 69/70 boundary must separate signature and other pools", roll < 70, signature.contains(selected));
                if (signature.contains(selected)) signatureCount++;
            }
            assertEquals(70, signatureCount);
        }
    }

    @Test public void idleTapNeverRepeatsAnAvailableAlternativeEvenWhenItsChosenPoolOnlyContainsThePreviousAction() {
        Set<String> states = Set.of("knead", "tapTilt");
        var selector = new PetInteractionActions.IdleSelector(bound -> 0);
        assertEquals("knead", selector.next(states::contains));
        assertEquals("tapTilt", selector.next(states::contains));
        assertEquals("knead", selector.next(states::contains));
        var otherFirst = new PetInteractionActions.IdleSelector(bound -> bound == 100 ? 99 : 0);
        assertEquals("tapTilt", otherFirst.next(states::contains));
        assertEquals("knead", otherFirst.next(states::contains));
    }

    @Test public void idleTapHandlesMissingPoolsAndSingleOrAbsentActionsWithoutInventingAState() {
        var selector = new PetInteractionActions.IdleSelector(bound -> bound == 100 ? 99 : 0);
        Set<String> signatureOnly = Set.of("neckStretch", "spitForward");
        String first = selector.next(signatureOnly::contains), next = selector.next(signatureOnly::contains);
        assertTrue(signatureOnly.contains(first)); assertTrue(signatureOnly.contains(next)); assertNotEquals(first, next);
        assertEquals("pet", selector.next("pet"::equals));
        assertEquals("pet", selector.next("pet"::equals));
        assertNull(selector.next(id -> false));
        var noSignatures = new PetInteractionActions.IdleSelector(bound -> 0);
        assertEquals("tapWave", noSignatures.next("tapWave"::equals));
        assertNull(noSignatures.next("working"::equals));
    }
    @Test public void screenSpitWaitsThroughAllSixEffectFramesAndRespectsReducedMotion() {
        assertEquals(1680, PetInteractionActions.duration(1340, 640 + 1040, true));
        assertEquals(2000, PetInteractionActions.duration(2000, 1680, true));
        assertEquals(0, PetInteractionActions.duration(1340, 1680, false));
    }
    @Test public void pendingStopAndReplacementTasksInterruptPlayWithoutBlockingIdlePlay() {
        assertTrue(PetInteractionActions.interrupted(false, "task", "task", "completed", true));
        assertTrue(PetInteractionActions.interrupted(true, "task", "task", "interrupted", false));
        assertTrue(PetInteractionActions.interrupted(true, "task", "task", "blocked", false));
        assertTrue(PetInteractionActions.interrupted(true, "task", "next", "thinking", false));
        for (String phase : List.of("pausing", "paused", "resuming", "stopping"))
            assertTrue(PetInteractionActions.interrupted(true, "task", "task", phase, false));
        assertFalse(PetInteractionActions.interrupted(false, "task", "task", "interrupted", false));
        assertFalse(PetInteractionActions.interrupted(true, "task", "task", "thinking", false));
    }
    @Test public void fallbackInteractionLabelsArePairedAndStatusIdentityIsDroy() {
        assertEquals("Droy", PetMessages.identity());
        String before = NativeLocale.active();
        try {
            NativeLocale.mirrorProcessLanguage("zh");
            assertEquals("朝屏幕吐口水", PetInteractionActions.fallbackLabel("spitScreen"));
            assertTrue(new PetMessages(bound -> 0).activity("observing", PetMessages.identity()).startsWith("Droy正在"));
            NativeLocale.mirrorProcessLanguage("en");
            assertEquals("Spit toward viewer", PetInteractionActions.fallbackLabel("spitScreen"));
            assertTrue(new PetMessages(bound -> 0).activity("observing", PetMessages.identity()).startsWith("Droy is"));
        } finally { NativeLocale.mirrorProcessLanguage(before); }
    }
}
