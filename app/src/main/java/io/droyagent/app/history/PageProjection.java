package io.droyagent.app.history;

import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import org.json.JSONObject;

final class PageProjection {
    private static final int MAX_NODES = 256;
    private PageProjection() {}

    static JSONObject project(AccessibilityNodeInfo root) throws Exception {
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        pending.add(AccessibilityNodeInfo.obtain(root));
        int visited = 0;
        boolean limited = false, input = false, web = false, textLimited = false;
        try {
            while (!pending.isEmpty() && visited < MAX_NODES) {
                AccessibilityNodeInfo node = pending.removeFirst(); visited++;
                try {
                    if (!node.isVisibleToUser()) continue;
                    if ("android.webkit.WebView".contentEquals(node.getClassName() == null ? "" : node.getClassName())) { web = true; break; }
                    if (node.isEditable() || node.isPassword() || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive())) {
                        input = true; break;
                    }
                    for (CharSequence value : new CharSequence[]{node.getText(), node.getContentDescription()}) {
                        if (value == null) continue;
                        String original = value.toString(), bounded = HistoryPolicy.boundedUtf8(original, HistoryPolicy.PAGE_BYTES);
                        textLimited |= !original.equals(bounded); lines.add(bounded);
                    }
                    for (int i = 0; i < node.getChildCount(); i++) {
                        if (pending.size() + visited >= MAX_NODES) { limited = true; break; }
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) pending.add(child);
                    }
                } finally { node.recycle(); }
            }
            limited |= !pending.isEmpty();
        } finally { while (!pending.isEmpty()) pending.removeFirst().recycle(); }
        // Ancestor descriptions can repeat input contents. Reject the whole
        // projection if an input is present or the inspection was incomplete.
        if (input || web || limited) return new JSONObject().put("text", "").put("suppressed", input ? "input_present" : web ? "web_origin_unknown" : "inspection_incomplete");
        String full = String.join("\n", lines).trim();
        String text = HistoryPolicy.boundedUtf8(full, HistoryPolicy.PAGE_BYTES);
        return new JSONObject().put("text", text).put("truncated", textLimited || !full.equals(text)).put("projection", "visible_non_input_text");
    }
}
