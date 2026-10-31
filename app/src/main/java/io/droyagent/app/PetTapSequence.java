package io.droyagent.app;

/** A tap burst opens once; a single is committed only after the burst has settled. */
final class PetTapSequence {
    enum Result { NONE, SINGLE, OPEN }
    private final long intervalMs;
    private long deadline;
    private int taps;

    PetTapSequence(long intervalMs) {
        if (intervalMs <= 0) throw new IllegalArgumentException("invalid_pet_tap_interval");
        this.intervalMs = intervalMs;
    }

    Result tap(long eventTime) {
        if (taps == 0 || eventTime >= deadline) {
            taps = 1;
            deadline = eventTime + intervalMs;
            return Result.NONE;
        }
        deadline = eventTime + intervalMs;
        if (taps == 1) {
            taps = 2;
            return Result.OPEN;
        }
        return Result.NONE;
    }

    Result settle(long now) {
        if (taps == 0 || now < deadline) return Result.NONE;
        boolean single = taps == 1;
        cancel();
        return single ? Result.SINGLE : Result.NONE;
    }

    void cancel() { taps = 0; deadline = 0; }
}
