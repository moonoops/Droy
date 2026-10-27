package io.droyagent.androiduse;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** A single bounded download, including DNS, response headers and slow body reads. */
final class NetworkDownload implements AutoCloseable {
    interface Opener { HttpURLConnection open(URL url) throws IOException; }
    final String owner;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "droy-network-download"); thread.setDaemon(true); return thread;
    });
    private final AtomicInteger bytes = new AtomicInteger(), responseCode = new AtomicInteger(-1);
    private volatile HttpURLConnection connection;
    private volatile Future<String> pending;
    private volatile boolean closed;

    NetworkDownload(String owner) { this.owner = owner; }

    Result measure(Opener opener, URL url, int maximum, long timeoutMs, Cancellation cancellation) throws Exception {
        long started = System.nanoTime(), deadline = started + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        cancellation.check();
        if (closed) throw new InterruptedException();
        Future<String> future;
        try { future = worker.submit(() -> receive(opener, url, maximum, timeoutMs)); }
        catch (java.util.concurrent.RejectedExecutionException stopped) {
            if (closed) throw new InterruptedException();
            throw stopped;
        }
        pending = future;
        if (closed) future.cancel(true);
        String completion = "deadline_reached", failure = null;
        try {
            while (System.nanoTime() < deadline) {
                cancellation.check();
                if (closed) throw new InterruptedException();
                long remaining = deadline - System.nanoTime();
                try {
                    completion = future.get(Math.max(1, Math.min(TimeUnit.MILLISECONDS.toNanos(50), remaining)), TimeUnit.NANOSECONDS);
                    break;
                } catch (TimeoutException waiting) { }
                catch (java.util.concurrent.ExecutionException error) {
                    if (error.getCause() instanceof IOException io) { completion = "failed"; failure = io.getClass().getSimpleName(); break; }
                    if (error.getCause() instanceof Exception cause) throw cause;
                    throw error;
                } catch (java.util.concurrent.CancellationException cancelled) { throw new InterruptedException(); }
            }
            cancellation.check();
            if (closed) throw new InterruptedException();
        } finally { close(); }
        return new Result(bytes.get(), responseCode.get(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), completion, failure);
    }

    private String receive(Opener opener, URL url, int maximum, long timeoutMs) throws IOException {
        HttpURLConnection opened = opener.open(url);
        connection = opened;
        try {
            ensureOpen();
            opened.setInstanceFollowRedirects(false);
            opened.setConnectTimeout((int) timeoutMs); opened.setReadTimeout((int) timeoutMs);
            opened.setRequestProperty("Accept-Encoding", "identity");
            opened.setRequestProperty("Range", "bytes=0-" + (maximum - 1));
            int code = opened.getResponseCode(); responseCode.set(code);
            ensureOpen();
            if (code < 200 || code >= 300) return "http_response";
            try (InputStream input = opened.getInputStream()) {
                byte[] buffer = new byte[Math.min(maximum, 8192)];
                while (bytes.get() < maximum) {
                    ensureOpen();
                    int read = input.read(buffer, 0, Math.min(buffer.length, maximum - bytes.get()));
                    ensureOpen();
                    if (read < 0) return "completed";
                    bytes.addAndGet(read);
                }
            }
            return "byte_limit_reached";
        } finally { opened.disconnect(); }
    }

    private void ensureOpen() throws java.io.InterruptedIOException {
        if (closed || Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
    }

    @Override public void close() {
        closed = true;
        Future<?> future = pending; if (future != null) future.cancel(true);
        HttpURLConnection opened = connection; if (opened != null) opened.disconnect();
        worker.shutdownNow();
    }

    static final class Result {
        final int bytes, responseCode; final long elapsedMs; final String completion, errorType;
        Result(int bytes, int responseCode, long elapsedMs, String completion, String errorType) {
            this.bytes = bytes; this.responseCode = responseCode; this.elapsedMs = elapsedMs; this.completion = completion; this.errorType = errorType;
        }
    }
}
