package io.droyagent.app.embedding;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public class EmbeddingQueueTest {
    @Test public void cancelOnlyAffectsTheMatchingSpace() throws Exception {
        try (EmbeddingQueue queue = new EmbeddingQueue()) {
            var caller = Executors.newSingleThreadExecutor();
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            try {
                var result = caller.submit(() -> {
                    try { return queue.execute("space-a", "same-id", 3000, () -> { started.countDown(); release.await(); return "done"; }); }
                    catch (Exception error) { return error.getMessage(); }
                });
                assertTrue(started.await(1, TimeUnit.SECONDS));
                assertFalse(queue.cancel("space-b", "same-id"));
                assertTrue(queue.cancel("space-a", "same-id"));
                assertEquals("EMBEDDING_CANCELLED", result.get(1, TimeUnit.SECONDS));
            } finally { release.countDown(); caller.shutdownNow(); }
        }
    }

    @Test public void deadlineDiscardsANativeResultEvenIfNativeCodeIgnoresInterrupt() throws Exception {
        try (EmbeddingQueue queue = new EmbeddingQueue()) {
            CountDownLatch release = new CountDownLatch(1);
            try {
                queue.execute("space", "late", 30, () -> {
                    while (true) { try { release.await(); break; } catch (InterruptedException ignored) {} }
                    return "must-not-return";
                });
                fail("Expected deadline");
            } catch (IllegalStateException expected) { assertEquals("EMBEDDING_DEADLINE", expected.getMessage()); }
            finally { release.countDown(); }
        }
    }

    @Test public void expiredQueuedWorkDoesNotRunLater() throws Exception {
        try (EmbeddingQueue queue = new EmbeddingQueue()) {
            var caller = Executors.newSingleThreadExecutor();
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicBoolean ran = new AtomicBoolean();
            try {
                var first = caller.submit(() -> queue.execute("space-a", "first", 3000, () -> { started.countDown(); release.await(); return 1; }));
                assertTrue(started.await(1, TimeUnit.SECONDS));
                try { queue.execute("space-b", "expired", 30, () -> { ran.set(true); return 2; }); fail("Expected deadline"); }
                catch (IllegalStateException expected) { assertEquals("EMBEDDING_DEADLINE", expected.getMessage()); }
                release.countDown(); assertEquals(1, (int) first.get(1, TimeUnit.SECONDS));
                assertEquals(3, (int) queue.execute("space-c", "next", 3000, () -> 3));
                assertFalse(ran.get());
            } finally { release.countDown(); caller.shutdownNow(); }
        }
    }

    @Test public void duplicateInflightRequestsAreNotRunTwice() throws Exception {
        try (EmbeddingQueue queue = new EmbeddingQueue()) {
            var caller = Executors.newSingleThreadExecutor();
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            try {
                var first = caller.submit(() -> queue.execute("a", "same", 3000, () -> { started.countDown(); release.await(); return 1; }));
                assertTrue(started.await(1, TimeUnit.SECONDS));
                try { queue.execute("a", "same", 1000, () -> 2); fail("Expected duplicate rejection"); }
                catch (IllegalStateException expected) { assertEquals("EMBEDDING_DUPLICATE_REQUEST", expected.getMessage()); }
                release.countDown(); assertEquals(1, (int) first.get(1, TimeUnit.SECONDS));
            } finally { release.countDown(); caller.shutdownNow(); }
        }
    }
}
