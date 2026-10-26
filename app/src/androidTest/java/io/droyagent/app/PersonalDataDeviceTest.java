package io.droyagent.app;

import android.Manifest;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.Cancellation;
import io.droyagent.androiduse.PersonalDataTools;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Emulator only. Run the denied-read test in a separate instrumentation invocation
 * before granting READ_CALENDAR/READ_CONTACTS; revoking a runtime grant can kill the
 * instrumentation process. Other methods require those grants, usage access, and
 * an enabled notification listener. The host posts the fictional notification.
 */
@RunWith(AndroidJUnit4.class)
public class PersonalDataDeviceTest {
    private static final Cancellation ACTIVE = () -> {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Personal data test stopped");
    };
    private Context context;
    private UiAutomation automation;
    private PersonalDataTools tools;

    @Before public void setUp() {
        String fingerprint = Build.FINGERPRINT.toLowerCase(Locale.ROOT);
        String hardware = Build.HARDWARE.toLowerCase(Locale.ROOT);
        assumeTrue("Synthetic personal-data fixtures are restricted to the emulator",
                (hardware.contains("ranchu") || hardware.contains("goldfish"))
                        && (fingerprint.contains("generic") || fingerprint.contains("emulator") || fingerprint.contains("sdk_gphone")));
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        tools = new PersonalDataTools(context, new PersonalDataTools.NotificationSource() {
            @Override public JSONObject status() throws Exception { return DroyNotificationService.status(context); }
            @Override public JSONObject query(JSONObject args, Cancellation cancellation) throws Exception {
                return DroyNotificationService.query(context, args, cancellation);
            }
        });
    }

    @Test public void missingReadPermissionsAreNotEmptyResults() throws Exception {
        assumeTrue("Run separately after revoking both read permissions",
                context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED
                        && context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED);
        JSONObject status = tools.permissionStatus();
        assertFalse(status.getBoolean("calendar"));
        assertFalse(status.getBoolean("contacts"));
        long now = System.currentTimeMillis();
        expectAction("calendar_permission_required", () -> tools.execute("calendar_query", new JSONObject()
                .put("startMs", now).put("endMs", now + 60000), ACTIVE));
        expectAction("contacts_permission_required", () -> tools.execute("contacts_query", new JSONObject()
                .put("query", "Droy fictional denied fixture"), ACTIVE));
    }

    @Test public void grantedCalendarAndContactsQueryOwnFixtures() throws Exception {
        assertEquals("Calendar read grant is required", PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.READ_CALENDAR));
        assertEquals("Contacts read grant is required", PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.READ_CONTACTS));
        String marker = "DroyFixture" + UUID.randomUUID().toString().replace("-", "");
        Uri calendar = null;
        List<Uri> contacts = new ArrayList<>();
        long start = System.currentTimeMillis() + 60 * 60 * 1000L;
        try {
            automation.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
                    Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS);
            try {
                calendar = createCalendar(marker);
                long calendarId = ContentUris.parseId(calendar);
                insertEvent(calendarId, marker + " A", start, start + 60000);
                insertEvent(calendarId, marker + " B", start + 120000, start + 180000);
                contacts.add(createContact(marker + " A", "2025550101"));
                contacts.add(createContact(marker + " B", "2025550102"));
            } finally { automation.dropShellPermissionIdentity(); }

            // These calls use the app's actual read grants, without shell identity.
            long calendarId = ContentUris.parseId(calendar);
            JSONObject calendarArgs = new JSONObject().put("calendarId", calendarId)
                    .put("startMs", start - 1000).put("endMs", start + 240000).put("limit", 1);
            JSONObject events = awaitCount("calendar_query", calendarArgs, 1);
            assertEquals("android_calendar_instances", events.getString("source"));
            assertEquals(1, events.getInt("count"));
            assertTrue(events.getBoolean("truncated"));
            assertTrue(events.getBoolean("readOnly"));
            assertTrue(events.getLong("observedAtMs") > 0);
            assertEquals(start - 1000, events.getJSONObject("range").getLong("startMs"));
            assertTrue(events.getJSONArray("items").getJSONObject(0).getString("title").startsWith(marker));

            JSONObject people = awaitCount("contacts_query", new JSONObject().put("query", marker).put("limit", 1), 1);
            assertEquals("android_contacts", people.getString("source"));
            assertEquals(1, people.getInt("count"));
            assertTrue(people.getBoolean("truncated"));
            JSONObject person = people.getJSONArray("items").getJSONObject(0);
            assertTrue(person.getString("displayName").startsWith(marker));
            assertEquals(1, person.getJSONArray("phones").length());
            assertTrue(person.getJSONArray("phones").getJSONObject(0).getString("number").startsWith("20255501"));
            assertFalse(person.has("email"));

            JSONObject empty = tools.execute("contacts_query", new JSONObject().put("query", marker + "Unmatched"), ACTIVE);
            assertEquals(0, empty.getInt("count"));
            assertFalse(empty.getBoolean("truncated"));
        } finally { cleanupFixtures(calendar, contacts); }
    }

    @Test public void providerTextFieldsAndWholeResultsHaveBounds() throws Exception {
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.READ_CALENDAR));
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.READ_CONTACTS));
        String marker = "DroyLongFixture" + UUID.randomUUID().toString().replace("-", "");
        Uri calendar = null;
        List<Uri> contacts = new ArrayList<>();
        long start = System.currentTimeMillis() + 60 * 60 * 1000L;
        try {
            automation.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
                    Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS);
            try {
                calendar = createCalendar(marker);
                long calendarId = ContentUris.parseId(calendar);
                for (int i = 0; i < 20; i++) insertEvent(calendarId, marker + " Fictional title ".repeat(200),
                        start + i * 120000L, start + i * 120000L + 60000, "Fictional location ".repeat(200));
                contacts.add(createContact(marker + " Fictional name ".repeat(200), "2025550101" + "0".repeat(300),
                        "Fictional label ".repeat(200)));
            } finally { automation.dropShellPermissionIdentity(); }

            JSONObject events = tools.execute("calendar_query", new JSONObject().put("calendarId", ContentUris.parseId(calendar))
                    .put("startMs", start - 1000).put("endMs", start + 41 * 60000L).put("limit", 100), ACTIVE);
            assertTrue(events.getBoolean("truncated"));
            assertTrue(events.getBoolean("sourceTextTruncated"));
            assertTrue(events.getInt("count") > 0 && events.getInt("count") < 20);
            assertTrue(events.getInt("returnedTextChars") <= 24000);
            for (int i = 0; i < events.getJSONArray("items").length(); i++) {
                JSONObject event = events.getJSONArray("items").getJSONObject(i);
                assertTrue(event.getString("title").length() <= 1000);
                assertTrue(event.getString("location").length() <= 1000);
                assertTrue(event.getString("timeZone").length() <= 100);
                assertTrue(event.getBoolean("contentTruncated"));
            }

            JSONObject people = awaitCount("contacts_query", new JSONObject().put("query", marker).put("limit", 50), 1);
            assertEquals(1, people.getInt("count"));
            assertTrue(people.getBoolean("truncated"));
            assertTrue(people.getBoolean("sourceTextTruncated"));
            JSONObject person = people.getJSONArray("items").getJSONObject(0);
            assertTrue(person.getString("displayName").length() <= 1000);
            assertTrue(person.getBoolean("contentTruncated"));
            assertEquals(1, person.getJSONArray("phones").length());
            assertTrue(person.getJSONArray("phones").getJSONObject(0).getString("number").length() <= 200);
            assertTrue(person.getJSONArray("phones").getJSONObject(0).getString("label").length() <= 200);
        } finally { cleanupFixtures(calendar, contacts); }
    }

    @Test public void activeNotificationsContainShellFixture() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (!DroyNotificationService.status(context).getBoolean("available") && SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(100);
        assertTrue("Notification listener must be granted and connected", DroyNotificationService.status(context).getBoolean("available"));
        JSONObject response = tools.execute("notifications_query", new JSONObject()
                .put("query", "Droy batch notification fixture").put("limit", 1), ACTIVE);
        assertEquals("android_active_notifications", response.getString("source"));
        assertEquals("current_active_only", response.getString("scope"));
        assertFalse(response.getBoolean("historyStored"));
        assertTrue(response.getBoolean("readOnly"));
        assertEquals("Host must post the synthetic notification before running this test", 1, response.getInt("count"));
        JSONObject notification = response.getJSONArray("items").getJSONObject(0);
        assertNotEquals(context.getPackageName(), notification.getString("packageName"));
        assertTrue((notification.getString("title") + notification.getString("text")).contains("Droy batch notification fixture"));
        assertFalse(notification.has("key"));
    }

    @Test public void usageQueryStatesDailyBucketPrecision() throws Exception {
        assertTrue("Usage access app-op must be allowed", tools.permissionStatus().getBoolean("usage"));
        long end = System.currentTimeMillis() - 1000;
        JSONObject response = tools.execute("usage_query", new JSONObject().put("startMs", end - 60 * 60 * 1000L)
                .put("endMs", end).put("limit", 1), ACTIVE);
        assertEquals("android_usage_stats", response.getString("source"));
        assertEquals("daily_buckets_may_extend_beyond_requested_range", response.getString("rangePrecision"));
        assertEquals(end, response.getJSONObject("range").getLong("endMs"));
        assertTrue(response.getBoolean("readOnly"));
        assertTrue(response.getInt("count") <= 1);
        JSONArray items = response.getJSONArray("items");
        if (items.length() > 0) {
            assertTrue(items.getJSONObject(0).has("bucketStartMs"));
            assertTrue(items.getJSONObject(0).has("bucketEndMs"));
        }
    }

    private Uri createCalendar(String marker) {
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Calendars.ACCOUNT_NAME, marker);
        values.put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
        values.put(CalendarContract.Calendars.NAME, marker);
        values.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, marker);
        values.put(CalendarContract.Calendars.OWNER_ACCOUNT, marker);
        values.put(CalendarContract.Calendars.CALENDAR_COLOR, 0xff3377aa);
        values.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER);
        values.put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC");
        values.put(CalendarContract.Calendars.VISIBLE, 1);
        values.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
        Uri target = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, marker)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build();
        Uri result = context.getContentResolver().insert(target, values);
        assertNotNull("Calendar fixture insert returned no URI", result);
        return result.buildUpon().appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, marker)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build();
    }

    private void insertEvent(long calendarId, String title, long begin, long end) {
        insertEvent(calendarId, title, begin, end, "");
    }

    private void insertEvent(long calendarId, String title, long begin, long end, String location) {
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, title);
        values.put(CalendarContract.Events.DTSTART, begin);
        values.put(CalendarContract.Events.DTEND, end);
        values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
        values.put(CalendarContract.Events.EVENT_LOCATION, location);
        assertNotNull(context.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values));
    }

    private Uri createContact(String name, String number) {
        return createContact(name, number, null);
    }

    private Uri createContact(String name, String number, String label) {
        ContentValues raw = new ContentValues();
        raw.putNull(ContactsContract.RawContacts.ACCOUNT_TYPE);
        raw.putNull(ContactsContract.RawContacts.ACCOUNT_NAME);
        Uri uri = context.getContentResolver().insert(ContactsContract.RawContacts.CONTENT_URI, raw);
        assertNotNull("Contact fixture insert returned no URI", uri);
        try {
            long id = ContentUris.parseId(uri);
            ContentValues displayName = new ContentValues();
            displayName.put(ContactsContract.Data.RAW_CONTACT_ID, id);
            displayName.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE);
            displayName.put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name);
            assertNotNull(context.getContentResolver().insert(ContactsContract.Data.CONTENT_URI, displayName));
            ContentValues phone = new ContentValues();
            phone.put(ContactsContract.Data.RAW_CONTACT_ID, id);
            phone.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
            phone.put(ContactsContract.CommonDataKinds.Phone.NUMBER, number);
            phone.put(ContactsContract.CommonDataKinds.Phone.TYPE, label == null
                    ? ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE : ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM);
            if (label != null) phone.put(ContactsContract.CommonDataKinds.Phone.LABEL, label);
            assertNotNull(context.getContentResolver().insert(ContactsContract.Data.CONTENT_URI, phone));
            return uri;
        } catch (RuntimeException | Error failed) {
            context.getContentResolver().delete(syncUri(uri), null, null);
            throw failed;
        }
    }

    private JSONObject awaitCount(String action, JSONObject args, int count) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        JSONObject response;
        do {
            response = tools.execute(action, args, ACTIVE);
            if (response.getInt("count") == count && response.getBoolean("truncated")) return response;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        return response;
    }

    private static Uri syncUri(Uri uri) {
        return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true").build();
    }

    private void cleanupFixtures(Uri calendar, List<Uri> contacts) {
        automation.adoptShellPermissionIdentity(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
                Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS);
        try {
            RuntimeException cleanupFailure = null;
            for (Uri contact : contacts) {
                try { context.getContentResolver().delete(syncUri(contact), null, null); }
                catch (RuntimeException failed) {
                    if (cleanupFailure == null) cleanupFailure = failed;
                    else cleanupFailure.addSuppressed(failed);
                }
            }
            try { if (calendar != null) context.getContentResolver().delete(syncUri(calendar), null, null); }
            catch (RuntimeException failed) {
                if (cleanupFailure == null) cleanupFailure = failed;
                else cleanupFailure.addSuppressed(failed);
            }
            if (cleanupFailure != null) throw cleanupFailure;
        } finally { automation.dropShellPermissionIdentity(); }
    }

    private interface Checked { void run() throws Exception; }
    private static void expectAction(String code, Checked action) throws Exception {
        try { action.run(); fail("Expected " + code); }
        catch (ActionException expected) { assertEquals(code, expected.getCode()); }
    }
}
