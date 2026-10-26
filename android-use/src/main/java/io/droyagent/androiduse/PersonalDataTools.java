package io.droyagent.androiduse;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only personal data. Product consent and task ownership must wrap every call. */
public final class PersonalDataTools {
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    static final long MAX_RANGE_MS = 31L * DAY_MS;
    static final int MAX_QUERY_TEXT_CHARS = 24000;
    private final Context context;
    private final NotificationSource notifications;

    /** The host owns the listener lifecycle; this module does not retain notification history. */
    public interface NotificationSource {
        JSONObject status() throws Exception;
        JSONObject query(JSONObject args, Cancellation cancellation) throws Exception;
    }

    public PersonalDataTools(Context context) { this(context, null); }

    public PersonalDataTools(Context context, NotificationSource notifications) {
        this.context = context.getApplicationContext();
        this.notifications = notifications;
    }

    public JSONObject permissionStatus() throws Exception {
        return new JSONObject()
                .put("calendar", permission(Manifest.permission.READ_CALENDAR))
                .put("contacts", permission(Manifest.permission.READ_CONTACTS))
                .put("usage", usageAllowed())
                .put("notifications", notifications == null
                        ? new JSONObject().put("available", false).put("reason", "listener_not_configured")
                        : notifications.status());
    }

    public JSONObject execute(String action, JSONObject args, Cancellation cancellation) throws Exception {
        if (args == null || cancellation == null) throw new IllegalArgumentException("args and cancellation are required");
        cancellation.check();
        JSONObject result;
        switch (action == null ? "" : action) {
            case "calendar_query": result = calendar(args, cancellation); break;
            case "contacts_query": result = contacts(args, cancellation); break;
            case "usage_query": result = usage(args, cancellation); break;
            case "notifications_query":
                if (notifications == null) throw new ActionException("notification_listener_unavailable", "Notification listener is not configured");
                result = notifications.query(args, cancellation);
                break;
            default: throw new ActionException("unsupported_action", "Unsupported personal data query: " + action);
        }
        cancellation.check();
        return result;
    }

    private JSONObject calendar(JSONObject args, Cancellation cancellation) throws Exception {
        requirePermission(Manifest.permission.READ_CALENDAR, "calendar_permission_required", "Allow calendar access in Android settings");
        long start = timestamp(args.opt("startMs"), "startMs");
        long end = timestamp(args.opt("endMs"), "endMs");
        validateRange(start, end, MAX_RANGE_MS);
        int limit = limit(args.opt("limit"), 100, 100);
        String selection = null;
        String[] selectionArgs = null;
        if (args.has("calendarId")) {
            long id = nonNegativeLong(args.opt("calendarId"), "calendarId");
            selection = CalendarContract.Instances.CALENDAR_ID + " = ?";
            selectionArgs = new String[]{Long.toString(id)};
        }
        Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(uri, start);
        ContentUris.appendId(uri, end);
        String[] columns = {CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.EVENT_TIMEZONE};
        JSONArray items = new JSONArray();
        boolean truncated = false;
        TextBudget text = new TextBudget();
        QueryCancellation query = new QueryCancellation(cancellation);
        try (query;
             Cursor cursor = context.getContentResolver().query(uri.build(), columns, selection, selectionArgs,
                     CalendarContract.Instances.BEGIN + " ASC", query.signal)) {
            if (cursor == null) throw new ActionException("calendar_unavailable", "Calendar provider did not return a result");
            while (cursor.moveToNext()) {
                query.check();
                if (items.length() == limit) { truncated = true; break; }
                if (text.exhausted()) { truncated = true; text.omitted(); break; }
                text.beginItem();
                items.put(new JSONObject().put("eventId", cursor.getLong(0)).put("calendarId", cursor.getLong(1))
                        .put("title", text.take(value(cursor, 2), 1000)).put("startMs", cursor.getLong(3)).put("endMs", cursor.getLong(4))
                        .put("allDay", cursor.getInt(5) != 0).put("location", text.take(value(cursor, 6), 1000))
                        .put("timeZone", text.take(value(cursor, 7), 100)).put("contentTruncated", text.itemTruncated));
            }
            query.check();
        } catch (OperationCanceledException stopped) { query.check(); throw stopped; }
        return textMetadata(result("android_calendar_instances", items, limit, truncated || text.truncated), text)
                .put("range", range(start, end)).put("allDayDatesUseUtc", true).put("readOnly", true);
    }

    private JSONObject contacts(JSONObject args, Cancellation cancellation) throws Exception {
        requirePermission(Manifest.permission.READ_CONTACTS, "contacts_permission_required", "Allow contacts access in Android settings");
        String search = search(args.opt("query"), true);
        int limit = limit(args.opt("limit"), 20, 50);
        JSONArray items = new JSONArray();
        boolean truncated = false;
        TextBudget text = new TextBudget();
        Uri uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(search));
        String[] columns = {ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.HAS_PHONE_NUMBER};
        QueryCancellation query = new QueryCancellation(cancellation);
        try (query;
             Cursor cursor = context.getContentResolver().query(uri, columns, null, null,
                     ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC", query.signal)) {
            if (cursor == null) throw new ActionException("contacts_unavailable", "Contacts provider did not return a result");
            while (cursor.moveToNext()) {
                query.check();
                if (items.length() == limit) { truncated = true; break; }
                if (text.exhausted()) { truncated = true; text.omitted(); break; }
                text.beginItem();
                long id = cursor.getLong(0);
                JSONObject item = new JSONObject().put("contactId", id).put("displayName", text.take(value(cursor, 1), 1000));
                JSONArray phones = new JSONArray();
                boolean phonesTruncated = false;
                if (cursor.getInt(2) != 0 && text.exhausted()) { phonesTruncated = true; text.omitted(); }
                if (cursor.getInt(2) != 0 && !text.exhausted()) {
                    String[] phoneColumns = {ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.LABEL};
                    try (Cursor phone = context.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            phoneColumns, ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                            new String[]{Long.toString(id)}, ContactsContract.CommonDataKinds.Phone.IS_PRIMARY + " DESC", query.signal)) {
                        if (phone == null) throw new ActionException("contacts_unavailable", "Contact phone provider did not return a result");
                        while (phone.moveToNext()) {
                            query.check();
                            if (phones.length() == 10) { phonesTruncated = true; break; }
                            if (text.exhausted()) { phonesTruncated = true; text.omitted(); break; }
                            phones.put(new JSONObject().put("number", text.take(value(phone, 0), 200)).put("type", phone.getInt(1))
                                    .put("label", text.take(value(phone, 2), 200)));
                        }
                    }
                }
                item.put("phones", phones).put("phonesTruncated", phonesTruncated)
                        .put("contentTruncated", phonesTruncated || text.itemTruncated);
                truncated |= phonesTruncated;
                items.put(item);
            }
            query.check();
        } catch (OperationCanceledException stopped) { query.check(); throw stopped; }
        return textMetadata(result("android_contacts", items, limit, truncated || text.truncated), text)
                .put("query", search).put("readOnly", true);
    }

    private JSONObject usage(JSONObject args, Cancellation cancellation) throws Exception {
        if (!usageAllowed()) throw new ActionException("usage_permission_required", "Allow usage access in Android settings");
        long start = timestamp(args.opt("startMs"), "startMs");
        long end = timestamp(args.opt("endMs"), "endMs");
        validateRange(start, end, MAX_RANGE_MS);
        if (end > System.currentTimeMillis()) throw new ActionException("invalid_argument", "Usage range must not end in the future");
        int limit = limit(args.opt("limit"), 50, 100);
        String mode = DiagnosticsValidation.usageMode(args.opt("mode"));
        String target = args.has("packageName") ? DiagnosticsValidation.packageName(args.get("packageName")) : "";
        UsageStatsManager manager = context.getSystemService(UsageStatsManager.class);
        if (manager == null) throw new ActionException("usage_unavailable", "Android usage service is unavailable");
        if (mode.equals("events")) return usageEvents(manager, start, end, target, limit, cancellation);
        cancellation.check();
        List<UsageStats> found = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end);
        cancellation.check();
        if (!usageAllowed()) throw new ActionException("usage_permission_required", "Usage access was revoked while reading");
        // Android returns whole reporting buckets. Keep their actual boundaries so
        // callers cannot mistake a daily aggregate for an exact minute-by-minute total.
        if (found == null) throw new ActionException("usage_unavailable", "Android did not return usage data; unlock the device and retry");
        Map<String, UsageStats> grouped = new LinkedHashMap<>();
        for (UsageStats item : found) {
            cancellation.check();
            if (!target.isEmpty() && !target.equals(item.getPackageName())) continue;
            UsageStats previous = grouped.get(item.getPackageName());
            if (previous == null) grouped.put(item.getPackageName(), new UsageStats(item));
            else previous.add(item);
        }
        List<UsageStats> sorted = new ArrayList<>(grouped.values());
        sorted.sort(Comparator.comparingLong(UsageStats::getTotalTimeInForeground).reversed());
        JSONArray items = new JSONArray();
        TextBudget text = new TextBudget();
        for (UsageStats item : sorted) {
            cancellation.check();
            if (item.getTotalTimeInForeground() == 0 && item.getLastTimeUsed() == 0) continue;
            if (items.length() == limit) break;
            String packageName = item.getPackageName();
            if (MAX_QUERY_TEXT_CHARS - text.used < packageName.length()) { text.omitted(); break; }
            items.put(new JSONObject().put("packageName", text.take(packageName, packageName.length()))
                    .put("foregroundMs", item.getTotalTimeInForeground()).put("lastUsedMs", item.getLastTimeUsed())
                    .put("bucketStartMs", item.getFirstTimeStamp()).put("bucketEndMs", item.getLastTimeStamp())
                    .put("contentTruncated", false));
        }
        int matching = 0;
        for (UsageStats item : sorted) if (item.getTotalTimeInForeground() != 0 || item.getLastTimeUsed() != 0) matching++;
        return textMetadata(result("android_usage_stats", items, limit, matching > items.length()), text).put("range", range(start, end))
                .put("mode", "summary").put("packageName", target.isEmpty() ? JSONObject.NULL : target)
                .put("rangePrecision", "daily_buckets_may_extend_beyond_requested_range").put("readOnly", true);
    }

    private JSONObject usageEvents(UsageStatsManager manager, long start, long end, String target, int limit,
                                   Cancellation cancellation) throws Exception {
        cancellation.check();
        UsageEvents events = manager.queryEvents(start, end);
        cancellation.check();
        if (events == null) throw new ActionException("usage_unavailable", "Android did not return events; unlock the Android user after restart before querying");
        JSONArray items = new JSONArray();
        boolean truncated = false;
        TextBudget text = new TextBudget();
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            cancellation.check();
            if (!events.getNextEvent(event)) throw new ActionException("usage_unavailable", "Android could not read a usage event");
            String kind = usageEventName(event.getEventType());
            if (kind == null || (!target.isEmpty() && !target.equals(event.getPackageName()))) continue;
            if (items.length() == limit || text.exhausted()) { truncated = true; break; }
            text.beginItem();
            items.put(new JSONObject().put("timestampMs", event.getTimeStamp()).put("eventType", event.getEventType())
                    .put("event", kind).put("packageName", text.take(event.getPackageName(), 255))
                    .put("className", text.take(event.getClassName(), 500)).put("contentTruncated", text.itemTruncated));
        }
        if (!usageAllowed()) throw new ActionException("usage_permission_required", "Usage access was revoked while reading");
        return textMetadata(result("android_usage_events", items, limit, truncated || text.truncated), text)
                .put("mode", "events").put("range", range(start, end)).put("packageName", target.isEmpty() ? JSONObject.NULL : target)
                .put("order", "oldest_first").put("retention", "limited_system_history_no_fixed_days_guaranteed")
                .put("meaning", "Recorded activity, foreground-service and screen events; activity lifecycle does not prove continuous visibility or whole-process background state, and missing events do not prove no activity")
                .put("readOnly", true);
    }

    static String usageEventName(int type) {
        switch (type) {
            case UsageEvents.Event.ACTIVITY_RESUMED: return "activity_resumed";
            case UsageEvents.Event.ACTIVITY_PAUSED: return "activity_paused";
            case UsageEvents.Event.ACTIVITY_STOPPED: return "activity_stopped";
            case UsageEvents.Event.FOREGROUND_SERVICE_START: return "foreground_service_start";
            case UsageEvents.Event.FOREGROUND_SERVICE_STOP: return "foreground_service_stop";
            case UsageEvents.Event.SCREEN_INTERACTIVE: return "screen_interactive";
            case UsageEvents.Event.SCREEN_NON_INTERACTIVE: return "screen_non_interactive";
            default: return null;
        }
    }

    private boolean permission(String name) { return context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED; }

    private void requirePermission(String name, String code, String message) throws ActionException {
        if (!permission(name)) throw new ActionException(code, message);
    }

    private boolean usageAllowed() {
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        return ops != null && ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    private static JSONObject result(String source, JSONArray items, int limit, boolean truncated) throws Exception {
        return new JSONObject().put("source", source).put("observedAtMs", System.currentTimeMillis())
                .put("items", items).put("count", items.length()).put("limit", limit).put("truncated", truncated);
    }

    private static JSONObject range(long start, long end) throws Exception {
        return new JSONObject().put("startMs", start).put("endMs", end);
    }

    private static JSONObject textMetadata(JSONObject result, TextBudget text) throws Exception {
        return result.put("sourceTextTruncated", text.truncated).put("returnedTextChars", text.used)
                .put("textLimitChars", MAX_QUERY_TEXT_CHARS);
    }

    private static String value(Cursor cursor, int column) { return cursor.isNull(column) ? "" : cursor.getString(column); }

    /** Counts emitted text across all fields, rather than only limiting row count. */
    static final class TextBudget {
        int used;
        boolean truncated, itemTruncated;

        void beginItem() { itemTruncated = false; }
        boolean exhausted() { return used == MAX_QUERY_TEXT_CHARS; }
        void omitted() { truncated = true; itemTruncated = true; }

        String take(String source, int fieldLimit) {
            if (source == null) return "";
            int length = Math.min(source.length(), Math.min(fieldLimit, MAX_QUERY_TEXT_CHARS - used));
            // Avoid splitting a supplementary character into an invalid UTF-16 pair.
            if (length > 0 && length < source.length() && Character.isHighSurrogate(source.charAt(length - 1))
                    && Character.isLowSurrogate(source.charAt(length))) length--;
            if (length < source.length()) { truncated = true; itemTruncated = true; }
            used += length;
            return length == source.length() ? source : source.substring(0, length);
        }
    }

    static String search(Object value, boolean required) throws ActionException {
        if (value == null && !required) return "";
        if (!(value instanceof String)) throw new ActionException("invalid_argument", "query must be a string");
        String text = ((String) value).trim();
        if ((required && text.isEmpty()) || text.length() > 200 || text.indexOf('\0') >= 0)
            throw new ActionException("invalid_argument", "query must contain 1 to 200 characters");
        return text;
    }

    static int limit(Object value, int fallback, int maximum) throws ActionException {
        if (value == null) return fallback;
        int number = ActionValidation.integer(value, "limit");
        if (number < 1 || number > maximum) throw new ActionException("invalid_argument", "limit must be between 1 and " + maximum);
        return number;
    }

    private static long timestamp(Object value, String name) throws ActionException { return nonNegativeLong(value, name); }

    private static long nonNegativeLong(Object value, String name) throws ActionException {
        if (!(value instanceof Number)) throw new ActionException("invalid_argument", name + " must be an integer");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < 0 || number > 253402300799999L)
            throw new ActionException("invalid_argument", name + " must be a non-negative integer within the supported date range");
        return ((Number) value).longValue();
    }

    static void validateRange(long start, long end, long maximum) throws ActionException {
        if (start < 0 || end <= start || end - start > maximum)
            throw new ActionException("invalid_argument", "Date range must be increasing and at most 31 days");
    }

    /** Propagates cancellation to a provider while its query is blocked. */
    private static final class QueryCancellation implements AutoCloseable {
        final CancellationSignal signal = new CancellationSignal();
        final Cancellation cancellation;
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicReference<Throwable> stopped = new AtomicReference<>();
        final Thread watcher;

        QueryCancellation(Cancellation cancellation) throws Exception {
            this.cancellation = cancellation;
            cancellation.check();
            watcher = new Thread(() -> {
                while (!closed.get()) {
                    try { cancellation.check(); Thread.sleep(50); }
                    catch (InterruptedException interrupted) {
                        if (!closed.get()) { stopped.compareAndSet(null, interrupted); signal.cancel(); }
                        return;
                    } catch (RuntimeException failure) {
                        stopped.compareAndSet(null, failure); signal.cancel(); return;
                    }
                }
            }, "droy-personal-query-cancel");
            watcher.setDaemon(true);
            watcher.start();
        }

        void check() throws Exception {
            Throwable failure = stopped.get();
            if (failure instanceof InterruptedException) throw (InterruptedException) failure;
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            cancellation.check();
        }

        @Override public void close() { closed.set(true); watcher.interrupt(); }
    }
}
