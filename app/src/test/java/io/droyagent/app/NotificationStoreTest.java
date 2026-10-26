package io.droyagent.app;

import io.droyagent.androiduse.ActionException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationStoreTest {
    private static NotificationStore.Entry item(String key, String pkg, String title, long time) {
        return new NotificationStore.Entry(key, pkg, title, "Fictional parcel is arriving", "", time, false);
    }

    @Test public void removedAndDisconnectedNotificationsAreNotHistory() throws Exception {
        NotificationStore store = new NotificationStore();
        store.put(item("fixture-1", "example.parcel", "示例快递", 100));
        assertEquals(1, store.query(new JSONObject(), () -> {}).getInt("count"));
        store.remove("fixture-1");
        assertEquals(0, store.query(new JSONObject(), () -> {}).getInt("count"));
        store.put(item("fixture-2", "example.parcel", "示例快递", 200));
        store.clear();
        JSONObject empty = store.query(new JSONObject(), () -> {});
        assertEquals(0, empty.getInt("count"));
        assertFalse(empty.getBoolean("historyStored"));
    }

    @Test public void queryFiltersAndReportsTruncationWithoutExposingNotificationKeys() throws Exception {
        NotificationStore store = new NotificationStore();
        store.put(item("internal-key-1", "example.parcel", "示例快递 A", 100));
        store.put(item("internal-key-2", "example.parcel", "示例快递 B", 200));
        store.put(item("internal-key-3", "example.other", "示例消息", 300));
        JSONObject response = store.query(new JSONObject().put("packageName", "example.parcel").put("query", "快递").put("limit", 1), () -> {});
        assertEquals(1, response.getInt("count"));
        assertTrue(response.getBoolean("truncated"));
        assertEquals("示例快递 B", response.getJSONArray("items").getJSONObject(0).getString("title"));
        assertFalse(response.toString().contains("internal-key"));
    }

    @Test public void activeCacheAndTextAreBounded() throws Exception {
        NotificationStore store = new NotificationStore();
        List<NotificationStore.Entry> entries = new ArrayList<>();
        for (int i = 0; i <= NotificationStore.MAX_ACTIVE; i++) entries.add(item("fixture-" + i, "example.parcel", "x".repeat(1200), i));
        store.replace(entries);
        JSONObject response = store.query(new JSONObject().put("limit", 100), () -> {});
        assertTrue(response.getBoolean("sourceTruncated"));
        assertEquals(1000, response.getJSONArray("items").getJSONObject(0).getString("title").length());
    }

    @Test public void cancellationNeverReturnsAResult() throws Exception {
        NotificationStore store = new NotificationStore();
        store.put(item("fixture", "example.parcel", "示例快递", 100));
        try { store.query(new JSONObject(), () -> { throw new InterruptedException("fixture-stop"); }); fail(); }
        catch (InterruptedException expected) { assertEquals("fixture-stop", expected.getMessage()); }
    }

    @Test public void invalidFiltersAreRejected() throws Exception {
        NotificationStore store = new NotificationStore();
        for (JSONObject args : new JSONObject[]{new JSONObject().put("limit", 1.5), new JSONObject().put("limit", 101),
                new JSONObject().put("packageName", "not a package"), new JSONObject().put("query", "x".repeat(201))}) {
            try { store.query(args, () -> {}); fail(); }
            catch (ActionException expected) { assertEquals("invalid_argument", expected.getCode()); }
        }
    }

    @Test public void manyNotificationsShareOneTextBudget() throws Exception {
        NotificationStore store = new NotificationStore();
        for (int i = 0; i < 20; i++) store.put(new NotificationStore.Entry("fixture-" + i, "example.parcel",
                "t".repeat(1000), "b".repeat(4000), "s".repeat(1000), i, false));
        JSONObject response = store.query(new JSONObject().put("limit", 100), () -> {});
        int total = 0;
        for (int i = 0; i < response.getJSONArray("items").length(); i++) {
            JSONObject entry = response.getJSONArray("items").getJSONObject(i);
            total += entry.getString("packageName").length() + entry.getString("title").length()
                    + entry.getString("text").length() + entry.getString("subtext").length();
        }
        assertTrue(response.getInt("count") < 20);
        assertTrue(total <= 24000);
        assertEquals(total, response.getInt("returnedTextChars"));
        assertTrue(response.getBoolean("truncated"));
        assertTrue(response.getBoolean("sourceTextTruncated"));
        assertTrue(response.getJSONArray("items").getJSONObject(response.getInt("count") - 1).getBoolean("contentTruncated"));
    }

    @Test public void anUnmatchedClippedSourceDoesNotClaimCompleteAbsence() throws Exception {
        NotificationStore store = new NotificationStore();
        store.put(item("fixture-hidden-suffix", "example.parcel", "x".repeat(1000) + "needle", 100));
        JSONObject response = store.query(new JSONObject().put("query", "needle"), () -> {});
        assertEquals(0, response.getInt("count"));
        assertTrue(response.getBoolean("truncated"));
        assertTrue(response.getBoolean("sourceTextTruncated"));
        JSONObject otherPackage = store.query(new JSONObject().put("packageName", "example.other"), () -> {});
        assertEquals(0, otherPackage.getInt("count"));
        assertTrue(otherPackage.getBoolean("sourceTextTruncated"));
        store.clear();
        assertFalse(store.query(new JSONObject(), () -> {}).getBoolean("sourceTextTruncated"));
    }
}
