package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class PetPositionTest {
    @Test public void savedHeightFractionRestoresInsideChangedSafeArea() {
        float fraction = PetPosition.fraction(460, 40, 1000, 80);
        assertEquals(460, PetPosition.restoreY(fraction, 40, 1000, 80));
        int rotated = PetPosition.restoreY(fraction, 24, 560, 80);
        assertTrue(rotated >= 24 && rotated + 80 <= 560);
        assertEquals(fraction, PetPosition.fraction(rotated, 24, 560, 80), .002f);
    }

    @Test public void outOfScreenSavedPositionsLandAtNearestSafeEdge() {
        assertEquals(32, PetPosition.restoreY(-.4f, 32, 900, 72));
        assertEquals(828, PetPosition.restoreY(1.4f, 32, 900, 72));
        assertEquals(0f, PetPosition.fraction(-20, 32, 900, 72), 0f);
        assertEquals(1f, PetPosition.fraction(980, 32, 900, 72), 0f);
        assertEquals(8, PetPosition.clamp(-60, 8, 920));
        assertEquals(920, PetPosition.clamp(1200, 8, 920));
    }

    @Test public void smallerThanPetUsableAreaKeepsTheTopAnchor() {
        assertEquals(40, PetPosition.restoreY(.8f, 40, 90, 72));
        assertEquals(40, PetPosition.clamp(80, 40, 90 - 72));
        assertEquals(0f, PetPosition.fraction(40, 40, 90, 72), 0f);
    }

    @Test public void bubbleOpensTowardScreenInteriorAndStaysInsideMargins() {
        assertEquals(80, PetPosition.bubbleX(8, 72, 276, 400, 8));
        assertEquals(44, PetPosition.bubbleX(320, 72, 276, 400, 8));
        assertEquals(116, PetPosition.bubbleX(180, 72, 276, 400, 8));
        assertEquals(8, PetPosition.bubbleX(220, 72, 276, 400, 8));
        for (int petX = 8; petX <= 320; petX++) {
            int x = PetPosition.bubbleX(petX, 72, 276, 400, 8);
            assertTrue(x >= 8);
            assertTrue(x + 276 <= 392);
        }
    }

    @Test public void aNarrowPhoneStillFitsItsWidthLimitedBubble() {
        int screen = 240, bubble = screen - 32;
        assertEquals(24, PetPosition.bubbleX(8, 72, bubble, screen, 8));
        assertEquals(8, PetPosition.bubbleX(screen - 80, 72, bubble, screen, 8));
    }
}
