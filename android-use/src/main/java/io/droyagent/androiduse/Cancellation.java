package io.droyagent.androiduse;

/** Supplied by the product service; checks both task cancellation and authorization. */
public interface Cancellation {
    void check() throws InterruptedException;

    /** Selects the actual privilege before executing, including during fallback. */
    default void usingRoot(boolean root) throws InterruptedException { check(); }

    default long remainingMillis(long maximumMs) throws Exception {
        check();
        return maximumMs;
    }

    /** Last cancellation/deadline check immediately before an external side effect. */
    default void dispatched() throws InterruptedException {
        check();
    }
}
