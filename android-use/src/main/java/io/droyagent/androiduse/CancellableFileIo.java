package io.droyagent.androiduse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps a remote provider's blocking Binder or pipe I/O out of the product's stop path. */
final class CancellableFileIo {
    static final long TIMEOUT_MS = 15000;
    private static final long CHECK_MS = 50;
    private static final ThreadPoolExecutor OPERATIONS = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), threads("droy-file-io"));
    // Signal cancellation may itself wait on a remote provider, so it must not prevent descriptor close.
    private static final ThreadPoolExecutor CLEANUP = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), threads("droy-file-close"));

    static { CLEANUP.allowCoreThreadTimeOut(true); }

    private CancellableFileIo() {}

    static <T> T run(Cancellation cancellation, long timeoutMs, Work<T> work) throws Exception {
        cancellation.check();
        if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be positive");
        Guard guard = new Guard(cancellation);
        Future<T> future;
        try {
            future = OPERATIONS.submit(() -> {
                try { guard.check(); return work.run(guard); }
                finally { guard.finished(); }
            });
        } catch (RejectedExecutionException busy) {
            throw new ActionException("file_provider_busy", "Earlier file provider operations have not stopped yet; try after the provider responds", busy);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        try {
            while (true) {
                guard.check();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new ActionException("file_operation_timeout", "File provider did not finish within the operation time limit");
                try {
                    T result = future.get(Math.min(TimeUnit.MILLISECONDS.toNanos(CHECK_MS), remaining), TimeUnit.NANOSECONDS);
                    guard.check();
                    return result;
                } catch (TimeoutException waiting) {
                    // Continue checking the product's authorization and stop token while provider I/O blocks.
                } catch (ExecutionException failure) {
                    Throwable cause = failure.getCause();
                    if (cause instanceof Exception) throw (Exception) cause;
                    if (cause instanceof Error) throw (Error) cause;
                    throw new IllegalStateException(cause);
                }
            }
        } catch (Exception failure) {
            guard.abort(failure);
            future.cancel(true);
            throw failure;
        }
    }

    @FunctionalInterface interface Work<T> { T run(Guard guard) throws Exception; }
    @FunctionalInterface interface Cleanup { void run() throws Exception; }

    static final class Guard implements Cancellation {
        private final Cancellation cancellation;
        private final List<AutoCloseable> resources = new ArrayList<>();
        private final List<Cleanup> cancellations = new ArrayList<>();
        private final List<Cancellation> validations = new ArrayList<>();
        private volatile boolean abandoned;
        private boolean finished;

        Guard(Cancellation cancellation) { this.cancellation = cancellation; }

        @Override public void check() throws InterruptedException {
            if (abandoned || Thread.currentThread().isInterrupted())
                throw new InterruptedException("File operation was stopped");
            cancellation.check();
            List<Cancellation> current;
            synchronized (this) { current = new ArrayList<>(validations); }
            for (Cancellation validation : current) validation.check();
        }

        <T extends AutoCloseable> T track(T resource) throws Exception {
            if (resource == null) return null;
            synchronized (this) {
                if (!abandoned) { resources.add(resource); return resource; }
            }
            // A provider may return a descriptor after cancellation. Close it before any later operation.
            resource.close();
            throw new InterruptedException("File provider returned after the operation was stopped");
        }

        synchronized void onCancel(Cleanup callback) throws InterruptedException {
            if (abandoned) throw new InterruptedException("File operation was stopped");
            cancellations.add(callback);
        }

        synchronized void onCheck(Cancellation validation) throws InterruptedException {
            if (abandoned) throw new InterruptedException("File operation was stopped");
            validations.add(validation);
        }

        synchronized void finished() {
            finished = true;
            resources.clear();
            cancellations.clear();
            // Keep authorization validations until the caller's final result-delivery check.
        }

        void abort(Exception reason) {
            List<AutoCloseable> active;
            List<Cleanup> signals;
            synchronized (this) {
                abandoned = true;
                if (finished) return;
                active = new ArrayList<>(resources);
                signals = new ArrayList<>(cancellations);
            }
            submitCleanup(() -> {
                for (int i = active.size() - 1; i >= 0; i--) {
                    try { active.get(i).close(); }
                    catch (Exception closeFailure) { reason.addSuppressed(closeFailure); }
                }
            }, reason);
            submitCleanup(() -> {
                for (Cleanup signal : signals) {
                    try { signal.run(); }
                    catch (Exception signalFailure) { reason.addSuppressed(signalFailure); }
                }
            }, reason);
        }
    }

    private static void submitCleanup(Runnable cleanup, Exception reason) {
        try { CLEANUP.execute(cleanup); }
        catch (RejectedExecutionException busy) { reason.addSuppressed(busy); }
    }

    private static ThreadFactory threads(String prefix) {
        AtomicInteger ids = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + ids.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
