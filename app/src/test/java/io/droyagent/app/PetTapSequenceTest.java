package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class PetTapSequenceTest {
    @Test public void aSingleTapWaitsForTheFullIntervalAndIsDeliveredOnce() {
        var taps = new PetTapSequence(300);
        assertEquals(PetTapSequence.Result.NONE, taps.tap(100));
        assertEquals(PetTapSequence.Result.NONE, taps.settle(399));
        assertEquals(PetTapSequence.Result.SINGLE, taps.settle(400));
        assertEquals(PetTapSequence.Result.NONE, taps.settle(401));
    }

    @Test public void slowTapsAreIndependentSingles() {
        var taps = new PetTapSequence(300);
        assertEquals(PetTapSequence.Result.NONE, taps.tap(100));
        assertEquals(PetTapSequence.Result.SINGLE, taps.settle(400));
        assertEquals(PetTapSequence.Result.NONE, taps.tap(700));
        assertEquals(PetTapSequence.Result.SINGLE, taps.settle(1000));
    }

    @Test public void doubleTripleAndQuadrupleBurstsOpenOnlyOnceAndNeverConfirmASingle() {
        for (int count : new int[] {2, 3, 4}) {
            var taps = new PetTapSequence(300);
            for (int index = 0; index < count; index++)
                assertEquals(index == 1 ? PetTapSequence.Result.OPEN : PetTapSequence.Result.NONE, taps.tap(100 + index * 100));
            assertEquals(PetTapSequence.Result.NONE, taps.settle(100 + (count - 1) * 100 + 299));
            assertEquals(PetTapSequence.Result.NONE, taps.settle(100 + (count - 1) * 100 + 300));
            assertEquals(PetTapSequence.Result.NONE, taps.settle(2000));
            assertEquals(PetTapSequence.Result.NONE, taps.tap(2100));
            assertEquals(PetTapSequence.Result.SINGLE, taps.settle(2400));
        }
    }

    @Test public void theLastTapExtendsTheBurstPastAnEarlierPendingSettle() {
        var taps = new PetTapSequence(300);
        taps.tap(100);
        assertEquals(PetTapSequence.Result.OPEN, taps.tap(350));
        assertEquals(PetTapSequence.Result.NONE, taps.settle(400));
        assertEquals(PetTapSequence.Result.NONE, taps.tap(600));
        assertEquals(PetTapSequence.Result.NONE, taps.settle(900));
    }

    @Test public void cancelledDragsHoldsAndHiddenWindowsCannotDeliverALateSingle() {
        var taps = new PetTapSequence(300);
        taps.tap(100); taps.cancel();
        assertEquals(PetTapSequence.Result.NONE, taps.settle(1000));
        assertEquals(PetTapSequence.Result.NONE, taps.tap(1100));
        assertEquals(PetTapSequence.Result.SINGLE, taps.settle(1400));
        taps.tap(2000); taps.tap(2100); taps.cancel();
        assertEquals(PetTapSequence.Result.NONE, taps.settle(3000));
        assertEquals(PetTapSequence.Result.NONE, taps.tap(3100));
    }

    @Test public void anInvalidIntervalCannotCreateAnUnboundedOrImmediateSequence() {
        assertThrows(IllegalArgumentException.class, () -> new PetTapSequence(0));
        assertThrows(IllegalArgumentException.class, () -> new PetTapSequence(-1));
    }
}
