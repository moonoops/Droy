package io.droyagent.app;

import java.util.function.IntUnaryOperator;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LauncherIconRotationTest {
    private static final long HOUR = LauncherIconPolicy.MIN_SWITCH_INTERVAL_MILLIS;

    @Test public void exactlyTwoOfOneHundredProbabilityTicketsSwitch() {
        int changes = 0;
        for (int ticket = 0; ticket < 100; ticket++) {
            final int selected = ticket;
            if (LauncherIconPolicy.nextIcon(0, 100, -1, bound -> bound == 100 ? selected : 0) != 0) changes++;
        }
        assertEquals(2, changes);
    }

    @Test public void eachCurrentIconHasExactlyEightDistinctEquallyAddressableAlternatives() {
        for (int current = 0; current < 9; current++) {
            boolean[] seen = new boolean[9];
            for (int ticket = 0; ticket < 8; ticket++) {
                final int choice = ticket;
                int next = LauncherIconPolicy.nextIcon(current, 100, -1, bound -> bound == 100 ? 0 : choice);
                assertNotEquals(current, next);
                assertFalse(seen[next]);
                seen[next] = true;
            }
            for (int i = 0; i < 9; i++) assertEquals(i != current, seen[i]);
        }
    }

    @Test public void cooldownAndClockRollbackDoNotEvenDrawARandomTicket() {
        IntUnaryOperator forbidden = bound -> { throw new AssertionError("Cooldown must not draw"); };
        assertEquals(4, LauncherIconPolicy.nextIcon(4, 100 + HOUR - 1, 100, forbidden));
        assertEquals(4, LauncherIconPolicy.nextIcon(4, 99, 100, forbidden));
        assertEquals(0, LauncherIconPolicy.nextIcon(4, 100 + HOUR, 100, bound -> 0));
    }

    @Test public void probabilityMissDoesNotConsumeCooldownOrWriteState() {
        MemoryStore store = new MemoryStore();
        FakeComponents icons = new FakeComponents(1 << 3);
        assertFalse(LauncherIconRotation.maybeSwitch(icons, store, HOUR, bound -> 2));
        assertEquals(-1, store.last);
        assertNull(store.pending);
        assertEquals(0, store.writes);
        assertEquals(0, icons.changes);
    }

    @Test public void successfulSwitchPersistsTheHourAcrossNewControllerCalls() {
        MemoryStore store = new MemoryStore();
        FakeComponents icons = new FakeComponents(1);
        assertTrue(LauncherIconRotation.maybeSwitch(icons, store, 500, bound -> 0));
        assertEquals(1 << 1, icons.mask);
        assertEquals(500, store.last);
        assertNull(store.pending);
        assertFalse(LauncherIconRotation.maybeSwitch(icons, store, 500 + HOUR - 1,
            bound -> { throw new AssertionError("Persisted cooldown ignored"); }));
        assertTrue(LauncherIconRotation.maybeSwitch(icons, store, 500 + HOUR, bound -> 0));
        assertEquals(1, icons.mask);
    }

    @Test public void partialPlatformFailureRestoresOneOldEntryAndDoesNotConsumeCooldown() {
        MemoryStore store = new MemoryStore();
        FakeComponents icons = new FakeComponents(1 << 2);
        icons.failNext = true;
        assertThrows(IllegalStateException.class, () -> LauncherIconRotation.maybeSwitch(icons, store, HOUR, bound -> 0));
        assertEquals(1 << 2, icons.mask);
        assertEquals(-1, store.last);
        assertNull(store.pending);
    }

    @Test public void timestampCommitFailureRestoresPreviousTimestampAndIcon() {
        MemoryStore store = new MemoryStore();
        store.last = 200;
        store.failComplete = true;
        FakeComponents icons = new FakeComponents(1 << 4);
        assertThrows(IllegalStateException.class,
            () -> LauncherIconRotation.maybeSwitch(icons, store, 200 + HOUR, bound -> 0));
        assertEquals(1 << 4, icons.mask);
        assertEquals(200, store.last);
        assertNull(store.pending);
    }

    @Test public void interruptedSuccessfulSwitchRecoversItsTimestampBeforeAnyNewDraw() {
        MemoryStore store = new MemoryStore();
        store.pending = new LauncherIconRotation.Pending(0, 5, 100);
        FakeComponents icons = new FakeComponents(1 << 5);
        assertFalse(LauncherIconRotation.maybeSwitch(icons, store, 200,
            bound -> { throw new AssertionError("Recovered cooldown ignored"); }));
        assertEquals(100, store.last);
        assertNull(store.pending);
        assertEquals(1 << 5, icons.mask);
    }

    @Test public void interruptedPartialSwitchRepairsDuplicateEntriesBeforeContinuing() {
        MemoryStore store = new MemoryStore();
        store.pending = new LauncherIconRotation.Pending(2, 6, 100);
        FakeComponents icons = new FakeComponents((1 << 2) | (1 << 6));
        assertFalse(LauncherIconRotation.maybeSwitch(icons, store, 200, bound -> 99));
        assertEquals(1 << 2, icons.mask);
        assertEquals(-1, store.last);
        assertNull(store.pending);
    }

    @Test public void failedRollbackRetainsPendingRecoveryRecordAndSurfacesBothFailures() {
        MemoryStore store = new MemoryStore();
        FakeComponents icons = new FakeComponents(1);
        icons.alwaysFail = true;
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> LauncherIconRotation.maybeSwitch(icons, store, 100, bound -> 0));
        assertEquals(1, failure.getSuppressed().length);
        assertNotNull(store.pending);
        assertEquals(-1, store.last);
    }

    @Test public void aMissingOrDuplicateEntryCanBeRepairedWithoutAProbabilityHit() {
        for (int broken : new int[]{0, 3}) {
            MemoryStore store = new MemoryStore();
            FakeComponents icons = new FakeComponents(broken);
            assertFalse(LauncherIconRotation.maybeSwitch(icons, store, 100, bound -> 99));
            assertEquals(1, icons.mask);
            assertEquals(-1, store.last);
        }
    }

    private static final class FakeComponents implements LauncherIconRotation.Components {
        int mask, changes;
        boolean failNext, alwaysFail;
        FakeComponents(int mask) { this.mask = mask; }
        @Override public int enabledMask() { return mask; }
        @Override public void select(int next) {
            changes++;
            if (failNext || alwaysFail) {
                failNext = false;
                mask |= 1 << next;
                throw new IllegalStateException("Fixture component failure");
            }
            mask = 1 << next;
        }
    }

    private static final class MemoryStore implements LauncherIconRotation.Store {
        long last = -1;
        LauncherIconRotation.Pending pending;
        int writes;
        boolean failComplete;
        @Override public long lastSwitchMillis() { return last; }
        @Override public LauncherIconRotation.Pending pending() { return pending; }
        @Override public void begin(LauncherIconRotation.Pending pending) { this.pending = pending; writes++; }
        @Override public void complete(long when) {
            last = when;
            pending = null;
            writes++;
            if (failComplete) { failComplete = false; throw new IllegalStateException("Fixture disk failure"); }
        }
        @Override public void restore(long lastSwitch) { last = lastSwitch; pending = null; writes++; }
    }
}
