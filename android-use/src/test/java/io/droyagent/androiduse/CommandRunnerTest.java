package io.droyagent.androiduse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.*;

public class CommandRunnerTest {
    @Test public void observedExitRetiresLeaseEvenWhenPostExitCancellationRejectsTheResult() throws Exception {
        AtomicReference<CompletableFuture<?>> lease = new AtomicReference<>();
        Cancellation cancellation = new Cancellation() {
            public void check() throws InterruptedException {
                if (lease.get() != null && lease.get().isDone()) throw new InterruptedException("cancelled after exit");
            }
            public void track(String kind, String id, CompletableFuture<?> settled) { lease.set(settled); }
        };
        assertThrows(InterruptedException.class, () ->
                CommandRunner.run(List.of("/bin/sh", "-c", "exit 0"), 1024, 1000, cancellation));
        assertTrue("Confirmed process exit must retire the recovery claim", lease.get().isDone());
    }

    @Test public void cancelledWaitDoesNotClaimThatThePrivilegedChildExited() throws Exception {
        AtomicReference<CompletableFuture<?>> lease = new AtomicReference<>();
        AtomicInteger checks = new AtomicInteger();
        Cancellation cancellation = new Cancellation() {
            public void check() throws InterruptedException {
                if (checks.incrementAndGet() >= 3) throw new InterruptedException("cancelled");
            }
            public void track(String kind, String id, CompletableFuture<?> settled) { lease.set(settled); }
        };
        assertThrows(InterruptedException.class, () ->
                CommandRunner.run(List.of("/bin/sh", "-c", "exec sleep 10"), 1024, 10000, cancellation));
        assertFalse("Destroying the local waiter is not a privileged-child exit receipt", lease.get().isDone());
    }

    @Test public void cancelledTaskNeverStartsAProcess() throws Exception {
        Path marker = Files.createTempDirectory("droy-command-test").resolve("executed");
        try {
            CommandRunner.run(List.of("/usr/bin/touch", marker.toString()), 1024, 1000,
                    () -> { throw new InterruptedException("cancelled"); });
            fail("Expected cancellation");
        } catch (InterruptedException expected) {
            assertFalse(Files.exists(marker));
        } finally {
            Files.deleteIfExists(marker);
            Files.deleteIfExists(marker.getParent());
        }
    }

    @Test public void cancellationInterruptsAnActiveCommand() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        long started = System.nanoTime();
        try {
            CommandRunner.run(List.of("/bin/sh", "-c", "exec sleep 10"), 1024, 10000, () -> {
                if (checks.incrementAndGet() >= 3) throw new InterruptedException("cancelled");
            });
            fail("Expected cancellation");
        } catch (InterruptedException expected) {
            assertTrue("Cancellation should be polled promptly", (System.nanoTime() - started) / 1000000 < 1500);
        }
    }

    @Test public void timeoutIsReportedInsteadOfSuccess() throws Exception {
        try {
            CommandRunner.run(List.of("/bin/sh", "-c", "exec sleep 10"), 1024, 120, () -> {});
            fail("Expected timeout");
        } catch (ActionException error) {
            assertEquals("root_timeout", error.getCode());
        }
    }

    @Test public void exitDuringThePollingWindowCannotBypassTheDeadline() throws Exception {
        try {
            CommandRunner.run(List.of("/bin/sh", "-c", "exec sleep 0.02"), 1024, 5, () -> {});
            fail("A process that exits after its deadline must not return success");
        } catch (ActionException error) {
            assertEquals("root_timeout", error.getCode());
        }
    }

    @Test public void outputLimitStopsUnboundedOutput() throws Exception {
        try {
            CommandRunner.run(List.of("/bin/sh", "-c", "printf '1234567890'"), 5, 1000, () -> {});
            fail("Expected output limit");
        } catch (ActionException error) {
            assertEquals("root_output_error", error.getCode());
        }
    }

    @Test public void exitCodeIsNotRewrittenAsSuccess() throws Exception {
        CommandRunner.Result result = CommandRunner.run(List.of("/bin/sh", "-c", "printf 'test'; exit 77"), 1024, 1000, () -> {});
        assertEquals(77, result.exitCode);
        assertArrayEquals(new byte[]{'t', 'e', 's', 't'}, result.stdout);
    }
}
