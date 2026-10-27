package io.droyagent.androiduse;

import java.util.ArrayDeque;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded notifications retain a sequence gap instead of silently losing history. */
final class BluetoothEventBuffer {
    private final int capacity;
    private final ArrayDeque<JSONObject> events = new ArrayDeque<>();
    private long sequence, dropped;

    BluetoothEventBuffer(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }

    synchronized void add(JSONObject event) {
        try {
            event.put("sequence", ++sequence);
            if (events.size() == capacity) { events.removeFirst(); dropped++; }
            events.addLast(event);
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }

    synchronized JSONObject read(long after, int limit) throws org.json.JSONException {
        if (after < 0 || after > sequence || limit < 1 || limit > 32) throw new IllegalArgumentException("cursor");
        JSONArray rows = new JSONArray();
        long next = after;
        for (JSONObject event : events) {
            if (event.getLong("sequence") <= after) continue;
            rows.put(new JSONObject(event.toString())); next = event.getLong("sequence");
            if (rows.length() == limit) break;
        }
        long oldest = events.isEmpty() ? sequence + 1 : events.getFirst().getLong("sequence");
        return new JSONObject().put("events", rows).put("nextCursor", next)
                .put("latestCursor", sequence).put("droppedTotal", dropped)
                .put("coverageGap", after < oldest - 1).put("hasMore", next < sequence);
    }
}
