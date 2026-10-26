package io.droyagent.app;

import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.Cancellation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** A bounded in-memory view of current notifications; never written to disk. */
final class NotificationStore {
    static final int MAX_ACTIVE = 256;
    static final int MAX_QUERY_TEXT_CHARS = 24000;
    private final Map<String, Entry> active = new LinkedHashMap<>();
    private boolean sourceTruncated;
    private boolean omittedSourceTextTruncated;

    static final class Entry {
        final String key, packageName, title, text, subtext;
        final long postedAtMs;
        final boolean ongoing, contentTruncated;

        Entry(String key, String packageName, String title, String text, String subtext, long postedAtMs, boolean ongoing) {
            this.key = key; this.packageName = packageName;
            this.title = bounded(title, 1000); this.text = bounded(text, 4000); this.subtext = bounded(subtext, 1000);
            this.postedAtMs = postedAtMs; this.ongoing = ongoing;
            contentTruncated = (title != null && title.length() > 1000) || (text != null && text.length() > 4000)
                    || (subtext != null && subtext.length() > 1000);
        }
    }

    synchronized void replace(List<Entry> entries) {
        active.clear();
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingLong((Entry entry) -> entry.postedAtMs).reversed());
        sourceTruncated = sorted.size() > MAX_ACTIVE;
        omittedSourceTextTruncated = false;
        for (Entry entry : sorted) {
            if (active.size() == MAX_ACTIVE) omittedSourceTextTruncated |= entry.contentTruncated;
            else active.put(entry.key, entry);
        }
    }

    synchronized void put(Entry entry) {
        active.put(entry.key, entry);
        if (active.size() > MAX_ACTIVE) {
            Entry oldest = active.values().stream().min(Comparator.comparingLong(value -> value.postedAtMs)).orElseThrow();
            active.remove(oldest.key);
            omittedSourceTextTruncated |= oldest.contentTruncated;
            sourceTruncated = true;
        }
    }

    synchronized void remove(String key) { active.remove(key); }
    synchronized void clear() { active.clear(); sourceTruncated = false; omittedSourceTextTruncated = false; }

    JSONObject query(JSONObject args, Cancellation cancellation) throws Exception {
        cancellation.check();
        int limit = 50;
        if (args.has("limit")) {
            Object selected = args.get("limit");
            if (!(selected instanceof Number) || !Double.isFinite(((Number) selected).doubleValue())
                    || ((Number) selected).doubleValue() != Math.rint(((Number) selected).doubleValue())
                    || ((Number) selected).doubleValue() < 1 || ((Number) selected).doubleValue() > 100)
                throw new ActionException("invalid_argument", "limit must be an integer between 1 and 100");
            limit = ((Number) selected).intValue();
        }
        String packageName = argument(args, "packageName", 255);
        if (!packageName.isEmpty() && !packageName.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"))
            throw new ActionException("invalid_argument", "packageName must be an Android package name");
        String search = argument(args, "query", 200).toLowerCase(Locale.ROOT);
        List<Entry> snapshot;
        boolean limited, sourceTextTruncated;
        synchronized (this) {
            snapshot = new ArrayList<>(active.values()); limited = sourceTruncated;
            sourceTextTruncated = omittedSourceTextTruncated;
        }
        snapshot.sort(Comparator.comparingLong((Entry entry) -> entry.postedAtMs).reversed());
        // Filtering searches only the cached text. A clipped entry can hide a
        // match in its omitted suffix, even when no returned entry was clipped.
        for (Entry entry : snapshot) sourceTextTruncated |= entry.contentTruncated;
        JSONArray items = new JSONArray();
        boolean truncated = limited || sourceTextTruncated;
        TextBudget text = new TextBudget();
        for (Entry entry : snapshot) {
            cancellation.check();
            if (!packageName.isEmpty() && !entry.packageName.equals(packageName)) continue;
            if (!search.isEmpty() && !(entry.title + "\n" + entry.text + "\n" + entry.subtext).toLowerCase(Locale.ROOT).contains(search)) continue;
            if (items.length() == limit) { truncated = true; break; }
            if (MAX_QUERY_TEXT_CHARS - text.used < entry.packageName.length()) { text.truncated = true; break; }
            text.itemTruncated = false;
            items.put(new JSONObject().put("packageName", text.take(entry.packageName)).put("title", text.take(entry.title))
                    .put("text", text.take(entry.text)).put("subtext", text.take(entry.subtext)).put("postedAtMs", entry.postedAtMs)
                    .put("ongoing", entry.ongoing).put("contentTruncated", entry.contentTruncated || text.itemTruncated));
        }
        cancellation.check();
        return new JSONObject().put("source", "android_active_notifications").put("observedAtMs", System.currentTimeMillis())
                .put("scope", "current_active_only").put("historyStored", false).put("items", items)
                .put("count", items.length()).put("limit", limit).put("truncated", truncated || text.truncated)
                .put("sourceTruncated", limited).put("packageNameFilter", packageName).put("query", search)
                .put("sourceTextTruncated", sourceTextTruncated || text.truncated)
                .put("returnedTextChars", text.used).put("textLimitChars", MAX_QUERY_TEXT_CHARS)
                .put("textScope", "title_text_subtext_only_no_attachments_or_message_history")
                .put("textMayBeRedactedByAndroid", true).put("readOnly", true);
    }

    private static String argument(JSONObject args, String name, int max) throws Exception {
        if (!args.has(name)) return "";
        Object value = args.get(name);
        if (!(value instanceof String) || ((String) value).length() > max || ((String) value).indexOf('\0') >= 0)
            throw new ActionException("invalid_argument", name + " must be a string of at most " + max + " characters");
        return ((String) value).trim();
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "";
        int length = Math.min(value.length(), limit);
        if (length > 0 && length < value.length() && Character.isHighSurrogate(value.charAt(length - 1))
                && Character.isLowSurrogate(value.charAt(length))) length--;
        return length == value.length() ? value : value.substring(0, length);
    }

    private static final class TextBudget {
        int used;
        boolean truncated, itemTruncated;

        String take(String source) {
            int length = Math.min(source.length(), MAX_QUERY_TEXT_CHARS - used);
            if (length > 0 && length < source.length() && Character.isHighSurrogate(source.charAt(length - 1))
                    && Character.isLowSurrogate(source.charAt(length))) length--;
            if (length < source.length()) { truncated = true; itemTruncated = true; }
            used += length;
            return length == source.length() ? source : source.substring(0, length);
        }
    }
}
