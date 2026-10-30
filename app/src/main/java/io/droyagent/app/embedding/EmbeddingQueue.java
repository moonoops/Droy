package io.droyagent.app.embedding;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounds work even when a native inference cannot be interrupted immediately. */
public final class EmbeddingQueue implements AutoCloseable {
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(2), job -> { Thread thread = new Thread(job, "DroyEmbedding"); thread.setDaemon(true); return thread; });
    private final Map<String, FutureTask<?>> requests = new ConcurrentHashMap<>();

    public <T> T execute(String space, String requestId, long timeoutMs, Callable<T> operation) throws Exception {
        if (timeoutMs < 1 || timeoutMs > 120000) throw new IllegalArgumentException("EMBEDDING_DEADLINE");
        String key = space + ":" + requestId;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        FutureTask<T> task = new FutureTask<>(() -> {
            checkDeadline(deadline);
            T result = operation.call();
            checkDeadline(deadline);
            return result;
        });
        if (requests.putIfAbsent(key, task) != null) throw new IllegalStateException("EMBEDDING_DUPLICATE_REQUEST");
        try {
            worker.execute(task);
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException expired) {
            throw new IllegalStateException("EMBEDDING_DEADLINE");
        } catch (CancellationException cancelled) {
            throw new IllegalStateException("EMBEDDING_CANCELLED");
        } catch (RejectedExecutionException full) {
            throw new IllegalStateException("EMBEDDING_BUSY");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof LinkageError) throw new IllegalStateException("EMBEDDING_RUNTIME_UNAVAILABLE");
            throw new IllegalStateException("EMBEDDING_INFERENCE_FAILED");
        } finally {
            task.cancel(true);
            worker.remove(task);
            requests.remove(key, task);
        }
    }
    public boolean cancel(String space, String requestId) {
        FutureTask<?> task = requests.get(space + ":" + requestId);
        if (task == null) return false;
        boolean cancelled = task.cancel(true);
        worker.remove(task);
        return cancelled;
    }
    public void cancelAll() { requests.values().forEach(task -> task.cancel(true)); worker.purge(); }
    private static void checkDeadline(long deadline) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("EMBEDDING_CANCELLED");
        if (System.nanoTime() >= deadline) throw new IllegalStateException("EMBEDDING_DEADLINE");
    }
    @Override public void close() { cancelAll(); worker.shutdownNow(); }
}
