package io.droyagent.androiduse;

import java.util.List;
import java.util.regex.Pattern;

/** Matching for explicitly requested navigation, never for submitting a transaction. */
final class NavigationTarget {
    private static final Pattern PRICE = Pattern.compile("(?<=\\s)(?:(?:[¥￥]\\s*)?\\d+(?:\\.\\d+)?\\s*元|[¥￥]\\s*\\d+(?:\\.\\d+)?)(?=\\s|$)");
    private static final Pattern SALES = Pattern.compile("(?<=\\s)\\d+(?:\\.\\d+)?[万千]?[+＋]?人(?:付款|看过|购买)(?=\\s|$)");

    record Candidate(String key, String bounds, int clarity) {}

    static String label(String value) {
        String text = NodeSemantics.stableCountdownText(value == null ? "" : value).trim().replaceAll("\\s+", " ");
        text = SALES.matcher(" " + text).replaceAll("");
        return PRICE.matcher(text).replaceAll("").trim().replaceAll("\\s+", " ");
    }

    static String key(String text, String description, String resource) {
        String value = label(description);
        String visible = label(text);
        if (value.isEmpty()) value = visible;
        else if (!visible.isEmpty() && !visible.equals(value)) value += "\u001f" + visible;
        return !value.isEmpty() ? "label:" + value : resource == null || resource.isEmpty() ? "" : "id:" + resource;
    }

    /** Equal rectangles may represent a container and content, but not different named actions. */
    static int select(List<Candidate> candidates, String key) {
        int selected = -1;
        String bounds = null;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate item = candidates.get(i);
            if (!item.key.equals(key)) continue;
            if (bounds != null && !bounds.equals(item.bounds)) return -1;
            bounds = item.bounds;
            if (selected < 0 || item.clarity > candidates.get(selected).clarity) selected = i;
        }
        if (selected < 0) return -1;
        for (Candidate item : candidates) {
            if (item.bounds.equals(bounds) && !item.key.isEmpty() && !item.key.equals(key)
                    && (item.key.startsWith("label:") || key.startsWith("id:"))) return -1;
        }
        return selected;
    }
}
