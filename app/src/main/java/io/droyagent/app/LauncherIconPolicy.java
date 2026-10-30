package io.droyagent.app;

import java.util.function.IntUnaryOperator;

/** Probability and cooldown are independent of Android lifecycle and launcher caches. */
final class LauncherIconPolicy {
    static final int ICON_COUNT = 9;
    static final long MIN_SWITCH_INTERVAL_MILLIS = 60L * 60L * 1000L;

    private LauncherIconPolicy() {}

    static int nextIcon(int current, long now, long lastSwitch, IntUnaryOperator random) {
        if (current < 0 || current >= ICON_COUNT) throw new IllegalArgumentException("Unknown launcher icon");
        if (now < 0 || lastSwitch >= 0 && (now < lastSwitch || now - lastSwitch < MIN_SWITCH_INTERVAL_MILLIS))
            return current;
        if (random.applyAsInt(100) >= 2) return current;
        int other = random.applyAsInt(ICON_COUNT - 1);
        return other >= current ? other + 1 : other;
    }
}
