package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class PetBubblePlacementTest {
    @Test public void rightEdgeCatHasAnAdjacentBubbleWithATailTowardItsHead() {
        var p = PetBubblePlacement.place(314, 400, 72, 72, 196, 58, 8, 24, 386, 820);
        assertEquals(118, p.x());
        assertEquals(314, p.x() + 196);
        assertEquals(PetBubblePlacement.Side.RIGHT, p.side());
        assertEquals(428, p.y() + p.tailOffset());
    }
    @Test public void leftEdgeCatPointsBackFromTheLeftSideOfItsBubble() {
        var p = PetBubblePlacement.place(8, 400, 72, 72, 180, 58, 8, 24, 386, 820);
        assertEquals(80, p.x());
        assertEquals(PetBubblePlacement.Side.LEFT, p.side());
    }
    @Test public void expandedBubbleAboveCatOnNarrowScreenKeepsAllItsWidthVisible() {
        var p = PetBubblePlacement.place(240, 400, 72, 72, 244, 210, 8, 24, 312, 620);
        assertEquals(PetBubblePlacement.Side.BOTTOM, p.side());
        assertEquals(68, p.x());
        assertEquals(400, p.y() + p.height());
        assertEquals(276, p.x() + p.tailOffset());
    }
    @Test public void topCatUsesSpaceBelowInsteadOfCoveringSystemBar() {
        var p = PetBubblePlacement.place(120, 24, 72, 72, 244, 210, 8, 24, 312, 620);
        assertEquals(PetBubblePlacement.Side.TOP, p.side());
        assertEquals(96, p.y());
        assertTrue(p.x() >= 8 && p.x() + 244 <= 312);
    }
    @Test public void longConfirmationIsBoundedAboveKeyboardForScrolling() {
        var p = PetBubblePlacement.place(240, 298, 72, 72, 244, 800, 8, 24, 312, 370);
        assertEquals(24, p.y());
        assertEquals(274, p.height());
        assertEquals(PetBubblePlacement.Side.BOTTOM, p.side());
    }
}
