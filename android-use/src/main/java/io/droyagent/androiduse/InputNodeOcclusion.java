package io.droyagent.androiduse;

/** Passive system edge bars do not cover the exposed portion of a known target. */
final class InputNodeOcclusion {
    record Bounds(int left, int top, int right, int bottom) {
        boolean valid() { return left < right && top < bottom; }
        boolean contains(Bounds other) {
            return valid() && other.valid() && left <= other.left && top <= other.top
                    && right >= other.right && bottom >= other.bottom;
        }
        boolean intersects(Bounds other) {
            return valid() && other.valid() && left < other.right && other.left < right
                    && top < other.bottom && other.top < bottom;
        }
        boolean containsCenter(Bounds other) {
            long x = (long) other.left + other.right, y = (long) other.top + other.bottom;
            return valid() && other.valid() && left * 2L <= x && x < right * 2L && top * 2L <= y && y < bottom * 2L;
        }
    }

    static boolean mayIgnoreSystemBar(boolean textInput, boolean editable, boolean systemWindow, boolean focused, boolean active,
                                      Bounds screen, Bounds content, Bounds target, Bounds covering) {
        return textInput && editable && content.containsCenter(target) && !covering.containsCenter(target)
                && mayIgnorePassiveSystemBar(systemWindow, focused, active, screen, content, target, covering);
    }

    static boolean mayIgnorePassiveSystemBar(boolean systemWindow, boolean focused, boolean active,
                                             Bounds screen, Bounds content, Bounds target, Bounds covering) {
        return systemWindow && !focused && !active
                && screen.contains(content) && screen.contains(target) && screen.contains(covering)
                && target.intersects(covering) && hasVisibleHit(content, target, covering)
                && coversDeclaredBarEdge(screen, content, covering);
    }

    private static boolean hasVisibleHit(Bounds content, Bounds target, Bounds covering) {
        Bounds visible = new Bounds(Math.max(content.left, target.left), Math.max(content.top, target.top),
                Math.min(content.right, target.right), Math.min(content.bottom, target.bottom));
        return visible.valid() && !covering.contains(visible);
    }

    private static boolean coversDeclaredBarEdge(Bounds screen, Bounds content, Bounds window) {
        // Navigation touch windows can extend inward beyond their declared insets.
        // Only full-edge, non-active system windows qualify; popup windows do not.
        boolean horizontal = window.left == screen.left && window.right == screen.right;
        boolean vertical = window.top == screen.top && window.bottom == screen.bottom;
        return horizontal && (content.top > screen.top && window.top == screen.top && window.bottom >= content.top
                    || content.bottom < screen.bottom && window.bottom == screen.bottom && window.top <= content.bottom)
                || vertical && (content.left > screen.left && window.left == screen.left && window.right >= content.left
                    || content.right < screen.right && window.right == screen.right && window.left <= content.right);
    }
}
