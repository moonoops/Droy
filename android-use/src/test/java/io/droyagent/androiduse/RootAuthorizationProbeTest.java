package io.droyagent.androiduse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RootAuthorizationProbeTest {
    private static CommandRunner.Result result(int exit, String output) {
        return new CommandRunner.Result(exit, output.getBytes(StandardCharsets.US_ASCII));
    }

    @Test public void usesOnlyTheFixedReadOnlyCommandAndBoundedBudget() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        assertTrue(RootAuthorizationProbe.verify(checks::incrementAndGet, (command, limit, timeout, cancellation) -> {
            assertEquals(List.of("su", "-c", "id -u"), command);
            assertEquals(64, limit);
            assertEquals(45000, timeout);
            cancellation.check();
            return result(0, "0\n");
        }));
        assertTrue(checks.get() >= 3);
    }

    @Test public void requiresUidZeroAndSuccessfulExitWithoutExtraOutput() throws Exception {
        for (String output : new String[]{"0", "0\n", "0\r\n"})
            assertTrue(RootAuthorizationProbe.verify(() -> {}, (command, limit, timeout, cancellation) -> result(0, output)));
        for (String output : new String[]{"", "2000\n", "permission denied", "0\nextra", "0\u0000", " 0\n"})
            assertFalse(output, RootAuthorizationProbe.verify(() -> {}, (command, limit, timeout, cancellation) -> result(0, output)));
        assertFalse(RootAuthorizationProbe.verify(() -> {}, (command, limit, timeout, cancellation) -> result(1, "0\n")));
    }

    @Test public void cancelledAuthorizationNeverStartsAProcess() {
        AtomicInteger invocations = new AtomicInteger();
        assertThrows(InterruptedException.class, () -> RootAuthorizationProbe.verify(
            () -> { throw new InterruptedException("fictional cancellation"); },
            (command, limit, timeout, cancellation) -> { invocations.incrementAndGet(); return result(0, "0\n"); }));
        assertEquals(0, invocations.get());
    }

    @Test public void revocationAfterUidReplyCannotBecomeSuccess() {
        AtomicInteger checks = new AtomicInteger();
        assertThrows(InterruptedException.class, () -> RootAuthorizationProbe.verify(() -> {
            if (checks.incrementAndGet() >= 2) throw new InterruptedException("fictional revocation");
        }, (command, limit, timeout, cancellation) -> result(0, "0\n")));
    }

    @Test public void propagatesTimeoutAndMissingRootInsteadOfReturningSuccess() {
        ActionException timeout = assertThrows(ActionException.class, () -> RootAuthorizationProbe.verify(() -> {},
            (command, limit, budget, cancellation) -> { throw new ActionException("root_timeout", "fictional timeout"); }));
        assertEquals("root_timeout", timeout.getCode());
        assertThrows(java.io.IOException.class, () -> RootAuthorizationProbe.verify(() -> {},
            (command, limit, budget, cancellation) -> { throw new java.io.IOException("fictional missing executable"); }));
    }
}
