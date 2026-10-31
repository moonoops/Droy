package io.droyagent.app;

import java.util.HashSet;
import java.util.Set;

/** Ordinary task updates share a small budget; questions and failures bypass this policy. */
final class PetChatterPolicy {
    static final long SETTLE_MS = 1500, MIN_INTERVAL_MS = 30_000;
    static final int MAX_MESSAGES_PER_TASK = 3;
    private String task = "", shown = "", candidate = "";
    private final Set<String> announced = new HashSet<>();
    private long candidateAt, shownAt;

    /** Empty kind pauses this turn; empty task, completion or interruption ends and resets it. */
    String observe(String taskId, String kind, long now) {
        if (taskId.isEmpty() || kind.equals("completed") || kind.equals("interrupted")) { reset(); return ""; }
        if (!task.equals(taskId)) { reset(); task = taskId; }
        // A temporary question or error clears buffered chatter without renewing this task's budget.
        if (kind.isEmpty() || announced.contains(kind) || announced.size() >= MAX_MESSAGES_PER_TASK) {
            candidate = "";
            return "";
        }
        if (!candidate.equals(kind)) { candidate = kind; candidateAt = now; }
        if ((announced.isEmpty() && kind.equals("started")) || remaining(now) == 0) {
            shown = kind; shownAt = now; candidate = "";
            announced.add(kind);
            return kind;
        }
        return "";
    }

    String current() { return shown; }

    long remaining(long now) {
        if (candidate.isEmpty()) return 0;
        long due = candidateAt + SETTLE_MS;
        if (!announced.isEmpty()) due = Math.max(due, shownAt + MIN_INTERVAL_MS);
        return Math.max(0, due - now);
    }

    void reset() { task = shown = candidate = ""; announced.clear(); candidateAt = shownAt = 0; }
}
