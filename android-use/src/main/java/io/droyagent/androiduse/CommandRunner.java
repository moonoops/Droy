package io.droyagent.androiduse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Only the executor's fixed command builder is exposed to agent requests. */
final class CommandRunner {
    static final class Result {
        final int exitCode;
        final byte[] stdout;
        Result(int exitCode, byte[] stdout) {
            this.exitCode = exitCode;
            this.stdout = stdout;
        }
    }

    static Result run(List<String> command, int outputLimit, long timeoutMs,
                      Cancellation cancellation) throws Exception {
        return run(command, outputLimit, timeoutMs, cancellation, () -> {});
    }

    static Result run(List<String> command, int outputLimit, long timeoutMs,
                      Cancellation cancellation, Runnable started) throws Exception {
        cancellation.check();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cancellation.remainingMillis(timeoutMs));
        java.util.concurrent.CompletableFuture<Void> settled = new java.util.concurrent.CompletableFuture<>();
        cancellation.track("root_process", java.util.UUID.randomUUID().toString(), settled);
        Process process;
        try { process = new ProcessBuilder(command).start(); }
        catch (IOException | RuntimeException failure) { settled.complete(null); throw failure; }
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Thread out = drain(process.getInputStream(), stdout, outputLimit, readFailure, process);
        Thread err = drain(process.getErrorStream(), new ByteArrayOutputStream(), 8192, readFailure, process);
        try {
            started.run();
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                cancellation.remainingMillis(1);
                checkDeadline(deadline);
            }
            settled.complete(null);
            while (out.isAlive() || err.isAlive()) {
                cancellation.remainingMillis(1);
                checkDeadline(deadline);
                out.join(25);
                err.join(25);
            }
            cancellation.remainingMillis(1);
            // A process may exit after the deadline inside waitFor's polling window.
            checkDeadline(deadline);
            if (readFailure.get() != null) {
                throw new ActionException("root_output_error", "Root command output could not be read safely", readFailure.get());
            }
            return new Result(process.exitValue(), stdout.toByteArray());
        } finally {
            // Killing this invocation cannot undo an input already delivered to Android.
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
            // Killing su's waiter does not prove that its privileged child stopped.
            // Keep cancelled invocations pending unless normal exit was observed above.
            close(process.getInputStream());
            close(process.getErrorStream());
            close(process.getOutputStream());
        }
    }

    private static void checkDeadline(long deadline) throws ActionException {
        if (System.nanoTime() >= deadline) {
            throw new ActionException("root_timeout", "Root command timed out; any already issued action has an unknown outcome");
        }
    }

    private static Thread drain(InputStream input, ByteArrayOutputStream output, int limit,
                                AtomicReference<IOException> failure, Process process) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if ((long) output.size() + count > limit) {
                        throw new IOException("Command output exceeded its byte limit");
                    }
                    output.write(buffer, 0, count);
                }
            } catch (IOException error) {
                failure.compareAndSet(null, error);
                process.destroy();
            }
        }, "droy-root-output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void close(java.io.Closeable stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Cleanup cannot replace the command's actual failure or cancellation.
        }
    }
}
