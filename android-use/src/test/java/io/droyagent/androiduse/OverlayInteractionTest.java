package io.droyagent.androiduse;

import android.graphics.Rect;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.*;

public class OverlayInteractionTest {
    private static final class Overlay implements OverlayInteraction.Listener {
        final CompletableFuture<Void> frameCommitted = new CompletableFuture<>();
        boolean capture;
        int finishes;
        @Override public CompletableFuture<Void> prepare(Rect bounds, boolean capture) {
            this.capture = capture;
            return frameCommitted;
        }
        @Override public void finish() { finishes++; }
    }

    @Test public void captureWaitsForTheOverlayFrameAndCleanupIsOnce() {
        Overlay overlay = new Overlay();
        OverlayInteraction.addListener(overlay);
        try {
            OverlayInteraction.Preparation preparation = OverlayInteraction.prepare(null, true);
            assertTrue(overlay.capture);
            assertFalse(preparation.ready.isDone());
            overlay.frameCommitted.complete(null);
            assertTrue(preparation.ready.isDone());
            preparation.finish();
            preparation.finish();
            assertEquals(1, overlay.finishes);
        } finally { OverlayInteraction.removeListener(overlay); }
    }

    @Test public void replacingOverlayDoesNotRedirectAnInFlightCleanup() {
        Overlay first = new Overlay(), second = new Overlay();
        OverlayInteraction.addListener(first);
        OverlayInteraction.Preparation preparation = OverlayInteraction.prepare(null, true);
        OverlayInteraction.addListener(second);
        try {
            OverlayInteraction.removeListener(first);
            preparation.finish();
            assertEquals(1, first.finishes);
            assertEquals(0, second.finishes);
            OverlayInteraction.Preparation next = OverlayInteraction.prepare(null, false);
            assertFalse(next.ready.isDone());
            second.frameCommitted.complete(null);
            assertTrue(next.ready.isDone());
            next.finish();
        } finally { OverlayInteraction.removeListener(second); }
    }

    @Test public void failedFrameCommitIsNotReportedReady() {
        Overlay overlay = new Overlay();
        OverlayInteraction.addListener(overlay);
        try {
            OverlayInteraction.Preparation preparation = OverlayInteraction.prepare(null, true);
            overlay.frameCommitted.completeExceptionally(new IllegalStateException("window removed"));
            assertTrue(preparation.ready.isCompletedExceptionally());
            preparation.finish();
            assertEquals(1, overlay.finishes);
        } finally { OverlayInteraction.removeListener(overlay); }
    }

    @Test public void petAndScreenGlowBothClearBeforeCapture() {
        Overlay pet = new Overlay(), glow = new Overlay();
        OverlayInteraction.addListener(pet); OverlayInteraction.addListener(glow);
        try {
            OverlayInteraction.Preparation preparation = OverlayInteraction.prepare(null, true);
            assertTrue(pet.capture); assertTrue(glow.capture);
            pet.frameCommitted.complete(null);
            assertFalse(preparation.ready.isDone());
            glow.frameCommitted.complete(null);
            assertTrue(preparation.ready.isDone());
            preparation.finish(); preparation.finish();
            assertEquals(1, pet.finishes); assertEquals(1, glow.finishes);
        } finally { OverlayInteraction.removeListener(pet); OverlayInteraction.removeListener(glow); }
    }

    @Test public void synchronousPrepareFailureStillReturnsCleanupForPreparedOwners() {
        Overlay pet = new Overlay();
        int[] finishes = {0};
        OverlayInteraction.Listener failing = new OverlayInteraction.Listener() {
            public CompletableFuture<Void> prepare(Rect bounds, boolean capture) { throw new IllegalStateException("window lost"); }
            public void finish() { finishes[0]++; }
        };
        OverlayInteraction.addListener(pet); OverlayInteraction.addListener(failing);
        try {
            pet.frameCommitted.complete(null);
            OverlayInteraction.Preparation preparation = OverlayInteraction.prepare(null, true);
            assertTrue(preparation.ready.isCompletedExceptionally());
            preparation.finish();
            assertEquals(1, pet.finishes); assertEquals(1, finishes[0]);
        } finally { OverlayInteraction.removeListener(pet); OverlayInteraction.removeListener(failing); }
    }

    @Test public void oneCleanupFailureCannotLeaveTheOtherOverlayHidden() {
        Overlay glow = new Overlay();
        OverlayInteraction.Listener failing = new OverlayInteraction.Listener() {
            public CompletableFuture<Void> prepare(Rect bounds, boolean capture) { return CompletableFuture.completedFuture(null); }
            public void finish() { throw new IllegalStateException("pet removed"); }
        };
        OverlayInteraction.addListener(failing); OverlayInteraction.addListener(glow);
        try {
            OverlayInteraction.Preparation preparation = OverlayInteraction.prepare(null, true);
            assertThrows(IllegalStateException.class, preparation::finish);
            assertEquals(1, glow.finishes);
            preparation.finish();
            assertEquals(1, glow.finishes);
        } finally { OverlayInteraction.removeListener(failing); OverlayInteraction.removeListener(glow); }
    }

    @Test public void onlyRegisteredWindowIdsAreExcludedIncludingQueuedRemoval() {
        int own = 510001, other = 510002;
        assertFalse(OverlayInteraction.ownsWindow(own));
        OverlayInteraction.unregisterWindow(other);
        OverlayInteraction.registerWindow(-1);
        OverlayInteraction.registerWindow(own);
        assertTrue(OverlayInteraction.ownsWindow(own));
        assertFalse(OverlayInteraction.ownsWindow(other));
        assertFalse(OverlayInteraction.ownsWindow(-1));
        OverlayInteraction.unregisterWindow(own);
        assertTrue(OverlayInteraction.ownsWindow(own));
        for (int id = 510100; id < 510117; id++) {
            OverlayInteraction.registerWindow(id);
            OverlayInteraction.unregisterWindow(id);
        }
        assertFalse(OverlayInteraction.ownsWindow(own));
        assertFalse(OverlayInteraction.ownsWindow(other));
    }
}
