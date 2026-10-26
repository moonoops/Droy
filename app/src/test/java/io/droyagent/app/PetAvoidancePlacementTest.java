package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class PetAvoidancePlacementTest {
    @Test public void rightEdgeInputLetsCatStayVisibleJustAboveTheTarget() {
        var position = PetAvoidancePlacement.choose(314, 400, 72, 72, 8, 24, 386, 820,
            306, 392, 386, 480, 8);
        assertNotNull(position);
        assertEquals(314, position.x());
        assertEquals(312, position.y());
    }

    @Test public void tallRightEdgeGestureMovesCatToTheOtherSide() {
        var position = PetAvoidancePlacement.choose(314, 400, 72, 72, 8, 24, 386, 820,
            300, 24, 386, 820, 8);
        assertNotNull(position);
        assertEquals(8, position.x());
        assertEquals(400, position.y());
    }

    @Test public void fullWidthSwipeUsesRoomAboveInsteadOfHiding() {
        var position = PetAvoidancePlacement.choose(150, 400, 72, 72, 8, 24, 386, 820,
            8, 350, 386, 650, 8);
        assertNotNull(position);
        assertEquals(150, position.x());
        assertEquals(270, position.y());
    }

    @Test public void screenWideBlockedRegionHasNoSafeVisibleDestination() {
        assertNull(PetAvoidancePlacement.choose(314, 400, 72, 72, 8, 24, 386, 820,
            0, 0, 394, 844, 8));
    }
}
