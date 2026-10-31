package io.droyagent.app;

/** Bridges the asynchronous service dispatch; the service owns startup after acknowledgement. */
final class NativeRuntimeStartRequest {
    private long deadline;

    synchronized boolean pending(long now) { return deadline > now; }

    synchronized void launch(long now, Runnable dispatch) {
        if (pending(now)) return;
        // A missing Android service callback must not leave the UI permanently starting.
        deadline = now + 10_000;
        try { dispatch.run(); }
        catch (RuntimeException failure) { deadline = 0; throw failure; }
    }

    synchronized void acknowledge() { deadline = 0; }
}
