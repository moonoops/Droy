package io.droyagent.app;

import java.util.LinkedHashMap;

/** Exiting navigation content cannot hide a newer foreground conversation. */
final class NativeConversationVisibility {
    private final LinkedHashMap<Object, String> owners = new LinkedHashMap<>();

    synchronized String update(Object owner, String sessionId, boolean visible) {
        if (visible && sessionId != null) owners.put(owner, sessionId);
        else owners.remove(owner);
        return current();
    }

    synchronized String current() {
        String current = null;
        for (String value : owners.values()) current = value;
        return current;
    }
}
