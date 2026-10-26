package io.droyagent.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Identifies only DSH's authority-scoped browser credentials, without retaining their values. */
final class ChatCookiePolicy {
    private ChatCookiePolicy() {}

    static List<String> authCookieNames(String header) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (header != null) for (String segment : header.split(";")) {
            int equals = segment.indexOf('=');
            if (equals < 0) continue;
            String name = segment.substring(0, equals).trim();
            if (name.matches("dsh-auth-[A-Za-z0-9_-]{43}")) names.add(name);
        }
        return new ArrayList<>(names);
    }
}
