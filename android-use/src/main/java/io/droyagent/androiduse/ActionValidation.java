package io.droyagent.androiduse;

import java.util.regex.Pattern;

final class ActionValidation {
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    private ActionValidation() {}

    static int integer(Object value, String name) throws ActionException {
        if (!(value instanceof Number)) {
            throw new ActionException("invalid_argument", name + " must be an integer");
        }
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new ActionException("invalid_argument", name + " must be an integer");
        }
        return (int) number;
    }

    static int coordinate(Object value, String name, int limit) throws ActionException {
        int coordinate = integer(value, name);
        if (limit <= 0 || coordinate < 0 || coordinate >= limit) {
            throw new ActionException("invalid_argument", name + " is outside the current display");
        }
        return coordinate;
    }

    static int duration(Object value) throws ActionException {
        int duration = integer(value, "durationMs");
        if (duration < 50 || duration > 2000) {
            throw new ActionException("invalid_argument", "durationMs must be between 50 and 2000");
        }
        return duration;
    }

    static int holdDuration(Object value) throws ActionException {
        int duration = integer(value, "holdMs");
        if (duration < 450 || duration > 2000)
            throw new ActionException("invalid_argument", "holdMs must be between 450 and 2000");
        return duration;
    }

    static String packageName(Object value) throws ActionException {
        if (!(value instanceof String) || ((String) value).length() > 255
                || !PACKAGE.matcher((String) value).matches()) {
            throw new ActionException("invalid_argument", "packageName is not an Android package name");
        }
        return (String) value;
    }

    static String text(Object value) throws ActionException {
        if (!(value instanceof String) || ((String) value).length() > 10000
                || ((String) value).indexOf('\0') >= 0) {
            throw new ActionException("invalid_argument", "text must be a string of at most 10000 characters without NUL");
        }
        return (String) value;
    }

    static void rootAllowed(boolean rootEnabled, String action) throws ActionException {
        if (!rootEnabled) {
            throw new ActionException("root_not_enabled", "Root actions are disabled in DroyAgent settings");
        }
        if (!action.equals("screenshot") && !action.equals("tap")
                && !action.equals("swipe") && !action.equals("back")) {
            throw new ActionException("unsupported_root_action", "Root channel supports only screenshot, tap, swipe and back");
        }
    }
}
