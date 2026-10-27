package io.droyagent.androiduse;

import static org.junit.Assert.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class NetworkDownloadTest {
    private static final URL URL;
    static { try { URL = new URL("https://example.invalid/test"); } catch (Exception invalid) { throw new AssertionError(invalid); } }

    @Test public void totalDeadlineIncludesBlockedResponseHeaders() throws Exception {
        TestConnection connection = new TestConnection(true);
        NetworkDownload download = new NetworkDownload("task-a");
        long started = System.nanoTime();
        NetworkDownload.Result result = download.measure(ignored -> connection, URL, 1000, 100, () -> {});
        assertEquals("deadline_reached", result.completion);
        assertTrue(connection.disconnected.get());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000);
        assertNotEquals(Thread.currentThread(), connection.headerThread);
    }

    @Test public void totalDeadlineStopsAStreamThatNeverTimesOutBetweenBytes() throws Exception {
        TestConnection connection = new TestConnection(false);
        NetworkDownload download = new NetworkDownload("task-a");
        NetworkDownload.Result result = download.measure(ignored -> connection, URL, 100000, 120, () -> {});
        assertEquals("deadline_reached", result.completion);
        assertTrue(result.bytes > 0);
        assertTrue(result.bytes < 100000);
        assertTrue(connection.disconnected.get());
    }

    @Test public void cancellationClosesARequestWhileHeadersAreBlocked() throws Exception {
        TestConnection connection = new TestConnection(true);
        NetworkDownload download = new NetworkDownload("task-a");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(80);
        assertThrows(InterruptedException.class, () -> download.measure(ignored -> connection, URL, 1000, 5000, () -> {
            if (System.nanoTime() >= deadline) throw new InterruptedException();
        }));
        assertTrue(connection.disconnected.get());
    }

    @Test public void closeCancelsAnActiveOwnerOperationWithoutWaitingForReadTimeout() throws Exception {
        TestConnection connection = new TestConnection(true);
        NetworkDownload download = new NetworkDownload("task-a");
        java.util.concurrent.ExecutorService runner = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> pending = runner.submit(() -> {
                try { download.measure(ignored -> connection, URL, 1000, 5000, () -> {}); fail("must stop"); }
                catch (InterruptedException stopped) { }
                catch (Exception failed) { throw new AssertionError(failed); }
            });
            assertTrue(connection.headerStarted.await(1, TimeUnit.SECONDS));
            download.close(); pending.get(1, TimeUnit.SECONDS);
            assertTrue(connection.disconnected.get());
        } finally { download.close(); runner.shutdownNow(); }
    }

    private static final class TestConnection extends HttpURLConnection {
        final boolean blockHeaders;
        final AtomicBoolean disconnected = new AtomicBoolean();
        final CountDownLatch headerStarted = new CountDownLatch(1), released = new CountDownLatch(1);
        volatile Thread headerThread;
        TestConnection(boolean blockHeaders) { super(URL); this.blockHeaders = blockHeaders; }
        @Override public int getResponseCode() throws IOException {
            headerThread = Thread.currentThread(); headerStarted.countDown();
            if (blockHeaders) {
                try { released.await(); } catch (InterruptedException stopped) { throw new java.io.InterruptedIOException(); }
                throw new IOException("closed");
            }
            return 200;
        }
        @Override public InputStream getInputStream() {
            return new InputStream() {
                @Override public int read() throws IOException {
                    if (disconnected.get()) throw new IOException("closed");
                    try { Thread.sleep(10); } catch (InterruptedException stopped) { throw new java.io.InterruptedIOException(); }
                    return 1;
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException { bytes[offset] = (byte) read(); return 1; }
            };
        }
        @Override public void disconnect() { disconnected.set(true); released.countDown(); }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
