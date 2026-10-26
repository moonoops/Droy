package io.droyagent.androiduse;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The saved authorization is independent of Android removing a stopped package. */
final class AccessibilityRecoveryRules {
    private AccessibilityRecoveryRules() {}

    static boolean authorized(boolean previous, boolean enabled, String writer, String settingsPackage) {
        if (enabled) return true;
        if ("com.android.settings".equals(writer) || (!settingsPackage.isEmpty() && settingsPackage.equals(writer))) return false;
        return previous;
    }

    static Set<String> components(String raw) {
        Set<String> result = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty() || "null".equals(raw)) return result;
        for (String entry : raw.split(":")) {
            if (!entry.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+"))
                throw new IllegalArgumentException("Invalid accessibility service list");
            result.add(entry);
        }
        return result;
    }

    static String expanded(String component) {
        int slash = component.indexOf('/');
        if (slash < 1) throw new IllegalArgumentException("Invalid accessibility component");
        return component.charAt(slash + 1) == '.'
            ? component.substring(0, slash + 1) + component.substring(0, slash) + component.substring(slash + 1)
            : component;
    }

    static boolean contains(String raw, String component) {
        return components(raw).stream().anyMatch(entry -> expanded(entry).equals(expanded(component)));
    }

    static String withService(String raw, String component, boolean enabled) {
        Set<String> entries = components(raw);
        if (enabled && contains(raw, component)) return String.join(":", entries);
        entries.removeIf(entry -> expanded(entry).equals(expanded(component)));
        if (enabled) entries.add(component);
        return String.join(":", entries);
    }

    static String writer(String dump, String key, String currentValue) {
        Pattern row = Pattern.compile("(?m)^_id:[^\\r\\n ]+ name:" + Pattern.quote(key)
            + "(?: pkg:([^\\r\\n ]+))? value:(.*)$");
        Matcher match = row.matcher(dump);
        if (!match.find()) return "";
        String value = match.group(2).split(" (?:default:|defaultSystemSet:|tag:|notPreservedInRestore)", 2)[0];
        // A concurrent change makes the writer stale; never assign it to another value.
        if (!value.equals(currentValue == null ? "{null}" : currentValue)) return "";
        return match.group(1) == null ? "" : match.group(1);
    }
}
