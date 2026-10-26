package io.droyagent.app;

/** Coalesces quick stage changes while leaving questions and failures immediate. */
final class PetChatterPolicy {
    static final long SETTLE_MS = 600, MIN_VISIBLE_MS = 4000;
    private String task = "", shown = "", candidate = "";
    private long candidateAt, shownAt;

    String observe(String taskId, String kind, long now) {
        if (taskId.isEmpty() || kind.isEmpty()) { reset(); return ""; }
        boolean newTask = !task.equals(taskId);
        if (newTask) { reset(); task = taskId; }
        if (shown.equals(kind)) { candidate = ""; return ""; }
        if (!candidate.equals(kind)) { candidate = kind; candidateAt = now; }
        if (newTask && kind.equals("started") || remaining(now) == 0) {
            shown = kind; shownAt = now; candidate = "";
            return kind;
        }
        return "";
    }

    String current() { return shown; }

    long remaining(long now) {
        if (candidate.isEmpty()) return 0;
        long due = candidateAt + SETTLE_MS;
        if (!shown.isEmpty()) due = Math.max(due, shownAt + MIN_VISIBLE_MS);
        return Math.max(0, due - now);
    }

    void reset() { task = shown = candidate = ""; candidateAt = shownAt = 0; }
}
