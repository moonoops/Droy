package io.droyagent.androiduse;

import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;

/** The small public action vocabulary supported by the executor. */
final class NodeActions {
    static final List<String> OPERATIONS = List.of("long_click", "scroll_forward", "scroll_backward",
            "scroll_up", "scroll_down", "scroll_left", "scroll_right");

    private NodeActions() {}

    static String operation(Object value) throws ActionException {
        if (!(value instanceof String) || !OPERATIONS.contains(value))
            throw new ActionException("invalid_argument", "operation must be long_click or a supported scroll direction");
        return (String) value;
    }

    static int id(String operation) throws ActionException {
        return switch (operation) {
            case "long_click" -> AccessibilityNodeInfo.ACTION_LONG_CLICK;
            case "scroll_forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            case "scroll_backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            case "scroll_up" -> android.R.id.accessibilityActionScrollUp;
            case "scroll_down" -> android.R.id.accessibilityActionScrollDown;
            case "scroll_left" -> android.R.id.accessibilityActionScrollLeft;
            case "scroll_right" -> android.R.id.accessibilityActionScrollRight;
            default -> throw new ActionException("invalid_argument", "Unsupported node operation");
        };
    }

    static List<String> supported(List<Integer> actionIds) {
        List<String> result = new ArrayList<>();
        if (actionIds.contains(AccessibilityNodeInfo.ACTION_CLICK)) result.add("click");
        if (actionIds.contains(AccessibilityNodeInfo.ACTION_SET_TEXT)) result.add("set_text");
        for (String operation : OPERATIONS) {
            try { if (actionIds.contains(id(operation))) result.add(operation); }
            catch (ActionException impossible) { throw new IllegalStateException(impossible); }
        }
        return result;
    }
}
