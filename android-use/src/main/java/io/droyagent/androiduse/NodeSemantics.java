package io.droyagent.androiduse;

import java.util.Locale;
import java.util.regex.Pattern;

/** Roles stated by Android semantics, rather than inferred from changing business text. */
final class NodeSemantics {
    private NodeSemantics() {}

    private static final String TIMER = "(?<![0-9])(?<!:)[0-9]{1,3}:[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,3})?(?![0-9]|:|\\.)";
    private static final Pattern PREFIX_TIMER = Pattern.compile(
            "((?:倒计时|剩余|还剩|倒数|countdown|remaining|starts in|ends in)\\s*[:：]?\\s*)" + TIMER,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SUFFIX_TIMER = Pattern.compile(TIMER + "(\\s*后(?:开抢|开始|结束|截止|开奖))");

    /** Only the digits of a labelled countdown are volatile, never prices or scheduled clock times. */
    static String stableCountdownText(String text) {
        return SUFFIX_TIMER.matcher(PREFIX_TIMER.matcher(text).replaceAll("$1<countdown>"))
                .replaceAll("<countdown>$1");
    }

    static boolean searchEntry(String className, String resourceId, String description) {
        String label = description == null ? "" : description.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (label.equals("搜索栏") || label.equals("搜索框") || label.equals("searchbar")
                || label.equals("searchfield") || label.equals("searchbox")) return true;
        if (className != null && className.endsWith(".SearchView")) return true;
        String id = resourceId == null ? "" : resourceId.toLowerCase(Locale.ROOT);
        return id.endsWith("/search_bar") || id.endsWith("/search_view") || id.endsWith("/search_box")
                || id.endsWith("/search_container");
    }
}
