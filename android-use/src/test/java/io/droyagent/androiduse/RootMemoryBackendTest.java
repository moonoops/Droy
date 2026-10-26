package io.droyagent.androiduse;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class RootMemoryBackendTest {
    @Test public void cancellationDoesNotWaitForBlockedInputWriter() throws Exception {
        java.lang.Process child = new ProcessBuilder("sleep", "30").start();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        long started = System.nanoTime();
        try {
            Future<?> operation = worker.submit(() -> {
                try {
                    RootMemoryBackend.exchange(child, new byte[2 * 1024 * 1024], "refine", 10000,
                        () -> { if (System.nanoTime() - started > TimeUnit.MILLISECONDS.toNanos(100)) throw new InterruptedException("cancelled"); }, null);
                    fail("Expected cancellation");
                } catch (InterruptedException expected) { /* Cancellation must not hang in cleanup. */ }
                catch (Exception failure) { throw new RuntimeException(failure); }
            });
            operation.get(3, TimeUnit.SECONDS);
            assertFalse("The blocked wrapper must be stopped", child.isAlive());
        } finally {
            child.destroyForcibly(); worker.shutdownNow();
        }
    }

    @Test public void deadlineDoesNotWaitForBlockedInputWriter() throws Exception {
        java.lang.Process child = new ProcessBuilder("sleep", "30").start();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> operation = worker.submit(() -> {
                try {
                    RootMemoryBackend.exchange(child, new byte[2 * 1024 * 1024], "refine", 100, () -> {}, null);
                    fail("Expected deadline");
                } catch (ActionException expected) { assertEquals("memory_timeout", expected.getCode()); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            });
            operation.get(3, TimeUnit.SECONDS);
            assertFalse(child.isAlive());
        } finally { child.destroyForcibly(); worker.shutdownNow(); }
    }
}
