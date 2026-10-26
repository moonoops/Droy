package io.droyagent.app;

import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;

/** Owns the device independently from the model's text or tool arguments. */
final class ControlPolicy {
    private final LongSupplier clock;
    private long generation;
    private boolean stopped = true;
    private String owner;
    private final Map<String, Ticket> calls = new HashMap<>();
    private record CallKey(String task, String call) {}
    private final Set<CallKey> cancelledCalls = new HashSet<>();
    private boolean cancellationCapacityExceeded;

    ControlPolicy() { this(() -> System.nanoTime() / 1_000_000L); }
    ControlPolicy(LongSupplier clock) { this.clock = clock; }

    synchronized void enable() { generation++; stopped = false; owner = null; calls.clear(); }
    synchronized void stop() { generation++; stopped = true; owner = null; calls.clear(); }
    synchronized boolean isStopped() { return stopped; }

    synchronized Ticket begin(String task, String call) {
        return begin(task, call, Long.MAX_VALUE);
    }

    synchronized Ticket begin(String task, String call, long budgetMs) {
        if (cancellationCapacityExceeded) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("取消记录已达上限，请重启引擎", "The cancellation record limit was reached. Please restart the engine"));
        if (stopped) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("Android Use 操作已暂停，请在权限设置中恢复", "Android Use operations are paused. Please resume in Permissions"));
        if (task == null || task.isBlank() || task.length() > 256 || call == null || call.isBlank() || call.length() > 256)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("无效的任务或调用标识", "Invalid task or call ID"));
        if (cancelledCalls.contains(new CallKey(task, call))) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("这次手机操作已取消", "This phone operation was cancelled"));
        if (owner != null && !owner.equals(task)) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("另一项任务正在使用手机，请先停止它", "Another task is using the phone. Please stop it first"));
        if (calls.containsKey(call)) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("拒绝重复的手机操作调用", "Duplicate phone operation call rejected"));
        if (!calls.isEmpty()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("手机动作必须逐个执行", "Phone actions must run one at a time"));
        owner = task;
        long now = clock.getAsLong();
        long deadline = budgetMs == Long.MAX_VALUE ? Long.MAX_VALUE : now + budgetMs;
        Ticket ticket = new Ticket(task, call, generation, deadline);
        calls.put(call, ticket);
        return ticket;
    }

    synchronized void cancel(String task, String call) {
        if (task == null || task.isBlank() || task.length() > 256 || call == null || call.isBlank() || call.length() > 256)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("无效的任务或调用标识", "Invalid task or call ID"));
        // HTTP cancellation may arrive before the corresponding /tool request.
        // Keep its identity across enable/end, so a late request cannot revive it.
        if (cancelledCalls.size() >= 4096) { cancellationCapacityExceeded = true; stop(); }
        else cancelledCalls.add(new CallKey(task, call));
        Ticket ticket = calls.get(call);
        if (ticket != null && ticket.task.equals(task)) ticket.cancelled = true;
    }

    synchronized void end(String task) {
        for (Ticket ticket : calls.values()) if (ticket.task.equals(task)) ticket.cancelled = true;
        if (task.equals(owner)) owner = null;
    }

    synchronized void finish(Ticket ticket) {
        // Ending the waiter must also revoke a main-thread callback that was
        // already running when its local wait timed out.
        ticket.cancelled = true;
        calls.remove(ticket.call, ticket);
    }

    final class Ticket {
        final String task, call;
        final long epoch;
        final long deadline;
        boolean cancelled;
        private boolean dispatched;
        Ticket(String task, String call, long epoch, long deadline) {
            this.task = task; this.call = call; this.epoch = epoch; this.deadline = deadline;
        }
        void check() throws InterruptedException {
            synchronized (ControlPolicy.this) {
                if (cancelled || stopped || epoch != generation || Thread.currentThread().isInterrupted())
                    throw new InterruptedException(io.droyagent.app.NativeLocale.text("手机操作已停止", "Phone operation stopped"));
                if (clock.getAsLong() >= deadline) throw new DeadlineExceeded(dispatched);
            }
        }
        long remainingMillis(long maximumMs) throws InterruptedException {
            synchronized (ControlPolicy.this) {
                check();
                return Math.min(maximumMs, deadline - clock.getAsLong());
            }
        }
        void dispatched() throws InterruptedException {
            synchronized (ControlPolicy.this) { check(); dispatched = true; }
        }
        boolean wasDispatched() { synchronized (ControlPolicy.this) { return dispatched; } }
    }

    static final class DeadlineExceeded extends InterruptedException {
        final boolean dispatched;
        DeadlineExceeded(boolean dispatched) {
            super(dispatched ? io.droyagent.app.NativeLocale.text("手机操作超过本次时间预算；动作已派发，请观察结果后继续，不要直接重放", "The phone operation exceeded its time budget after dispatch. Observe the result before continuing; do not replay directly")
                    : io.droyagent.app.NativeLocale.text("手机操作超过本次时间预算；尚未派发动作，可重新观察后继续", "The phone operation exceeded its time budget before dispatch. You may observe again and continue"));
            this.dispatched = dispatched;
        }
    }
}

