package io.droyagent.androiduse;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Selection uses action support; an old channel argument is only a preference. */
final class ActionChannels {
    private static final Set<String> SHELL_ACTIONS = Set.of("screenshot", "tap", "swipe", "back");

    static List<String> candidates(String action, boolean node, boolean natural, String preferred,
                                   boolean accessibility, boolean shizuku, boolean root) throws ActionException {
        if (!Set.of("auto", "accessibility", "shizuku", "root").contains(preferred))
            throw new ActionException("invalid_argument", "channel must be auto, accessibility, shizuku or root");
        List<String> result = new ArrayList<>();
        if (accessibility) result.add("accessibility");
        if (SHELL_ACTIONS.contains(action) && !node && !natural) {
            if (shizuku) result.add("shizuku");
            if (root) result.add("root");
        }
        if (result.remove(preferred)) result.add(0, preferred);
        return result;
    }

    static boolean mayFallback(String code, boolean dispatched) {
        return code.equals("root_unavailable") || !dispatched && Set.of(
                "accessibility_unavailable", "shizuku_unavailable", "screenshot_failed", "screenshot_rate_limited").contains(code);
    }
}
