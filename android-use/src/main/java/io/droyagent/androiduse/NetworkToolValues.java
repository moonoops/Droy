package io.droyagent.androiduse;

import org.json.JSONObject;

/** Shared wire conversions; unavailable radio measurements must never become real zeroes. */
final class NetworkToolValues {
    private NetworkToolValues() {}

    static Object radio(int value) {
        return value == Integer.MAX_VALUE || value == Integer.MIN_VALUE ? JSONObject.NULL : value;
    }

    static Object asu(int value, boolean gsm) {
        return value < 0 || value == 99 || gsm && value > 31 ? JSONObject.NULL : radio(value);
    }

    static Object identity(long value) {
        return value == Long.MAX_VALUE || value == Integer.MAX_VALUE || value < 0 ? JSONObject.NULL : value;
    }

    static String host(String value) {
        if (value == null || value.isBlank() || value.length() > 253 || value.contains("://")
                || value.contains("/") || value.contains("@") || value.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("host");
        return value;
    }

    static boolean ownedBy(String expected, String actual) {
        return expected != null && !expected.isBlank() && expected.equals(actual);
    }

    static boolean matchesTransport(String requested, boolean wifi, boolean cellular, boolean vpn) {
        return requested.equals("default") || !vpn && (requested.equals("wifi") && wifi || requested.equals("cellular") && cellular);
    }

    static boolean validWifiPassword(String password) {
        return password != null && !password.isEmpty() && password.length() <= 63
                && password.chars().allMatch(value -> value >= 32 && value <= 126);
    }
}
