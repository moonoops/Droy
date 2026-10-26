package io.droyagent.androiduse;

import static org.junit.Assert.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class CancellableFileIoTest {
    @Test public void revokingOnlyFileAuthorizationClosesBlockedReadAndWithholdsItsResult() throws Exception {
        BlockingInputStream input = new BlockingInputStream();
        AtomicBoolean filePermission = new AtomicBoolean(true);
        CountDownLatch workerFinished = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            // The task stays active throughout: revocation of this specific file grant must stop its I/O.
            Future<?> result = caller.submit(() -> CancellableFileIo.run(() -> {}, 5000, guard -> {
                guard.onCheck(() -> {
                    if (!filePermission.get()) throw new InterruptedException("selected file grant revoked");
                });
                try (InputStream stream = guard.track(input)) {
                    return SelectedFileTools.readUtf8(stream, 100, guard);
                } finally { workerFinished.countDown(); }
            }));
            assertTrue(input.readEntered.await(2, TimeUnit.SECONDS));
            filePermission.set(false);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof InterruptedException);
            assertEquals("selected file grant revoked", failure.getCause().getMessage());
            assertTrue(input.closed.await(2, TimeUnit.SECONDS));
            assertTrue(workerFinished.await(2, TimeUnit.SECONDS));
        } finally {
            input.close();
            caller.shutdownNow();
        }
    }

    @Test public void grantRevocationAtDeliveryBoundaryWithholdsAnAlreadyComputedResult() throws Exception {
        AtomicBoolean filePermission = new AtomicBoolean(true);
        CountDownLatch workerComputed = new CountDownLatch(1);
        CountDownLatch letWorkerFinish = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = caller.submit(() -> CancellableFileIo.run(() -> {}, 5000, guard -> {
                guard.onCheck(() -> {
                    if (!filePermission.get()) throw new InterruptedException("selected file grant revoked");
                });
                workerComputed.countDown();
                letWorkerFinish.await();
                return "private file content must not be delivered";
            }));
            assertTrue(workerComputed.await(2, TimeUnit.SECONDS));
            filePermission.set(false);
            letWorkerFinish.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof InterruptedException);
        } finally {
            letWorkerFinish.countDown();
            caller.shutdownNow();
        }
    }

    @Test public void stopClosesAnAlreadyBlockedReadEvenIfRemoteSignalCancellationHangs() throws Exception {
        BlockingInputStream input = new BlockingInputStream();
        AtomicBoolean stopped = new AtomicBoolean();
        CountDownLatch releaseSignal = new CountDownLatch(1);
        CountDownLatch workerFinished = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = caller.submit(() -> CancellableFileIo.run(() -> {
                if (stopped.get()) throw new InterruptedException("user stopped");
            }, 5000, guard -> {
                guard.onCancel(() -> releaseSignal.await());
                try (InputStream stream = guard.track(input)) {
                    return SelectedFileTools.readUtf8(stream, 100, guard);
                } finally { workerFinished.countDown(); }
            }));
            assertTrue("The test must reach the blocking read", input.readEntered.await(2, TimeUnit.SECONDS));
            stopped.set(true);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof InterruptedException);
            assertTrue("Stop must close the active stream, not just set a flag", input.closed.await(2, TimeUnit.SECONDS));
            assertTrue(workerFinished.await(2, TimeUnit.SECONDS));
        } finally {
            releaseSignal.countDown();
            input.close();
            caller.shutdownNow();
        }
    }

    @Test public void providerTimeoutClosesBlockedStreamAndReturnsAnExplicitFailure() throws Exception {
        BlockingInputStream input = new BlockingInputStream();
        CountDownLatch workerFinished = new CountDownLatch(1);
        try {
            ActionException timeout = assertThrows(ActionException.class, () -> CancellableFileIo.run(() -> {}, 200, guard -> {
                try (InputStream stream = guard.track(input)) {
                    return SelectedFileTools.readUtf8(stream, 100, guard);
                } finally { workerFinished.countDown(); }
            }));
            assertEquals("file_operation_timeout", timeout.getCode());
            assertEquals(0, input.readEntered.getCount());
            assertTrue(input.closed.await(2, TimeUnit.SECONDS));
            assertTrue(workerFinished.await(2, TimeUnit.SECONDS));
        } finally { input.close(); }
    }

    @Test public void lateDescriptorAfterStopIsClosedAndCannotTriggerAnyFollowUpAction() throws Exception {
        AtomicBoolean stopped = new AtomicBoolean();
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch providerReturn = new CountDownLatch(1);
        CountDownLatch resourceClosed = new CountDownLatch(1);
        CountDownLatch workerFinished = new CountDownLatch(1);
        AtomicInteger followUps = new AtomicInteger();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> result = caller.submit(() -> CancellableFileIo.run(() -> {
                if (stopped.get()) throw new InterruptedException("stop");
            }, 5000, guard -> {
                providerEntered.countDown();
                // Simulate a provider that ignores thread interruption and returns its handle later.
                while (providerReturn.getCount() != 0) {
                    try { providerReturn.await(); }
                    catch (InterruptedException ignoredByProvider) { /* Deliberately hostile provider fixture. */ }
                }
                try {
                    guard.track((AutoCloseable) resourceClosed::countDown);
                    followUps.incrementAndGet();
                    return "must not continue";
                } finally { workerFinished.countDown(); }
            }));
            assertTrue(providerEntered.await(2, TimeUnit.SECONDS));
            stopped.set(true);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof InterruptedException);
            providerReturn.countDown();
            assertTrue(resourceClosed.await(2, TimeUnit.SECONDS));
            assertTrue(workerFinished.await(2, TimeUnit.SECONDS));
            assertEquals(0, followUps.get());
        } finally {
            providerReturn.countDown();
            caller.shutdownNow();
        }
    }

    private static final class BlockingInputStream extends InputStream {
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        @Override public int read() throws IOException {
            readEntered.countDown();
            while (closed.getCount() != 0) {
                try { closed.await(); }
                catch (InterruptedException ignored) { /* Only close(), not interrupt(), releases this pipe fixture. */ }
            }
            throw new IOException("stream closed while read blocked");
        }
        @Override public void close() { closed.countDown(); }
    }
}
