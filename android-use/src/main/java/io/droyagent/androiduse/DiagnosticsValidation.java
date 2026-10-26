package io.droyagent.androiduse;

import java.util.Set;

final class DiagnosticsValidation {
    private DiagnosticsValidation() {}

    static String packageName(Object value) throws ActionException {
        return "android".equals(value) ? "android" : ActionValidation.packageName(value);
    }

    static String choice(Object value, String name, String fallback, Set<String> values) throws ActionException {
        if (value == null) return fallback;
        if (!(value instanceof String) || !values.contains(value))
            throw new ActionException("invalid_argument", name + " must be one of " + values);
        return (String) value;
    }

    static boolean bool(Object value, String name, boolean fallback) throws ActionException {
        if (value == null) return fallback;
        if (!(value instanceof Boolean)) throw new ActionException("invalid_argument", name + " must be a boolean");
        return (Boolean) value;
    }

    static long timestamp(Object value, String name) throws ActionException {
        if (!(value instanceof Number)) throw new ActionException("invalid_argument", name + " must be an integer");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < 0 || number > 253402300799999L)
            throw new ActionException("invalid_argument", name + " must be a non-negative timestamp");
        return ((Number) value).longValue();
    }

    static String permission(Object value) throws ActionException {
        if (value == null) return "";
        if (!(value instanceof String) || !((String) value).matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
                || ((String) value).length() > 255)
            throw new ActionException("invalid_argument", "permission must be an Android permission name");
        return (String) value;
    }

    static boolean requestsPermission(String[] requested, String filter) {
        if (filter.isEmpty()) return true;
        if (requested != null) for (String name : requested) if (filter.equals(name)) return true;
        return false;
    }

    static String usageMode(Object value) throws ActionException {
        return choice(value, "mode", "summary", Set.of("summary", "events"));
    }
}
