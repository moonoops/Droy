package io.droyagent.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationManagementRulesTest {
    @Test public void takeoverDefaultsIncludeEveryEligibleAppAndOnlyPersistExceptions() throws Exception {
        JSONObject fresh = NotificationManagementRules.configuration(null);
        assertTrue(fresh.getBoolean("cloudAnalysisEnabled"));
        assertEquals(0, fresh.getJSONObject("exceptions").length());
        assertFalse(fresh.has("packages"));
        JSONObject old = NotificationManagementRules.configuration(new JSONObject().put("revision", 2)
                .put("cloudAnalysisEnabled", false).put("packages", new JSONArray().put("example.parcel")));
        assertFalse(old.getBoolean("cloudAnalysisEnabled"));
        assertFalse(old.has("packages"));
        assertEquals(3, old.getLong("revision"));
        for (String mode : new String[]{"managed", "allow", "block"}) assertEquals(mode, NotificationManagementRules.appMode(mode));
        try { NotificationManagementRules.appMode("unknown"); fail(); }
        catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        JSONObject exceptions = NotificationManagementRules.exceptions(new JSONArray()
                .put(new JSONObject().put("packageName", "example.parcel").put("mode", "allow"))
                .put(new JSONObject().put("packageName", "example.ads").put("mode", "block"))
                .put(new JSONObject().put("packageName", "example.default").put("mode", "managed")));
        assertEquals(2, exceptions.length());
        assertEquals("allow", exceptions.getString("example.parcel"));
        assertEquals("block", exceptions.getString("example.ads"));
        assertEquals(0, NotificationManagementRules.exceptions(new JSONArray()).length());
    }

    @Test public void installedUserAppsAndVisibleSystemAppsAreEligibleButCoreServicesAreNot() {
        java.util.Set<String> protectedApps = java.util.Set.of("example.dialer");
        assertTrue(NotificationManagementRules.eligibleApplication("example.parcel", 10123, false, false, "example.droy", protectedApps));
        assertTrue(NotificationManagementRules.eligibleApplication("example.sms", 10124, true, true, "example.droy", protectedApps));
        assertTrue(NotificationManagementRules.eligibleApplication("example.profile", 1010123, false, true, "example.droy", protectedApps));
        assertFalse(NotificationManagementRules.eligibleApplication("example.droy", 10123, false, true, "example.droy", protectedApps));
        assertFalse(NotificationManagementRules.eligibleApplication("example.dialer", 10123, true, true, "example.droy", protectedApps));
        assertFalse(NotificationManagementRules.eligibleApplication("android", 1000, true, true, "example.droy", protectedApps));
        assertFalse(NotificationManagementRules.eligibleApplication("example.framework", 10123, true, false, "example.droy", protectedApps));
    }
    private JSONObject rule() throws Exception {
        return new JSONObject().put("packageName", "example.parcel").put("instruction", "只有送达时提醒")
                .put("entries", new JSONArray().put(new JSONObject().put("containsAll", new JSONArray().put("送达")).put("action", "notify")))
                .put("defaultAction", "collect");
    }

    @Test public void normalizedRulesKeepExplicitPolicyAndDoNotInventTimers() throws Exception {
        JSONObject result = NotificationManagementRules.rule(rule());
        assertEquals("只有送达时提醒", result.getString("instruction"));
        assertEquals("notify", result.getJSONArray("entries").getJSONObject(0).getString("action"));
        assertEquals(0, result.getInt("repeatMinutes"));
        assertEquals(0, result.getInt("clearAfterHours"));
        assertEquals("*", NotificationManagementRules.rule(rule().put("packageName", "*")).getString("packageName"));
    }

    @Test public void unsupportedActionsUnboundedTimersAndEmptyConditionsAreRejected() throws Exception {
        for (JSONObject invalid : new JSONObject[]{rule().put("defaultAction", "execute"), rule().put("repeatMinutes", 1441),
                rule().put("clearAfterHours", 1.5), rule().put("entries", new JSONArray().put(new JSONObject().put("action", "clear"))),
                rule().put("packageName", "not a package")}) {
            try { NotificationManagementRules.rule(invalid); fail(); }
            catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        }
        try { NotificationManagementRules.decision(new JSONObject().put("action", "launch_app")); fail(); }
        catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
    }

    @Test public void staleChangedDisabledRevokedAndPausedDecisionsCannotApply() {
        assertTrue(NotificationManagementRules.current(2, 2, "same", "same", true, true, true, true, false));
        assertFalse(NotificationManagementRules.current(3, 2, "same", "same", true, true, true, true, false));
        assertFalse(NotificationManagementRules.current(2, 2, "changed", "same", true, true, true, true, false));
        assertFalse(NotificationManagementRules.current(2, 2, "same", "same", false, true, true, true, false));
        assertFalse(NotificationManagementRules.current(2, 2, "same", "same", true, false, true, true, false));
        assertFalse(NotificationManagementRules.current(2, 2, "same", "same", true, true, false, true, false));
        assertFalse(NotificationManagementRules.current(2, 2, "same", "same", true, true, true, false, false));
        assertFalse(NotificationManagementRules.current(2, 2, "same", "same", true, true, true, true, true));
    }

    @Test public void dismissalOnlySuppressesTheSameNotificationInstance() throws Exception {
        JSONObject item = new JSONObject().put("key", "fixture-a").put("fingerprint", "identical-content").put("postedAtMs", 100);
        JSONObject receipt = new JSONObject(item.toString()).put("dismissed", true).put("applied", true).put("revision", 2);
        assertTrue(NotificationManagementRules.alreadyHandled(receipt, item, 2));
        assertFalse(NotificationManagementRules.alreadyHandled(receipt, new JSONObject(item.toString()).put("key", "fixture-b"), 2));
        assertFalse(NotificationManagementRules.alreadyHandled(receipt, new JSONObject(item.toString()).put("postedAtMs", 200), 2));
        receipt.put("dismissed", false);
        assertTrue(NotificationManagementRules.alreadyHandled(receipt, item, 2));
        assertFalse(NotificationManagementRules.alreadyHandled(receipt, new JSONObject(item.toString()).put("postedAtMs", 200), 2));
        assertFalse(NotificationManagementRules.alreadyHandled(receipt, item, 3));
        receipt.put("applied", false);
        assertFalse(NotificationManagementRules.alreadyHandled(receipt, item, 2));
    }

    @Test public void replacingAnAlertCancelsItUnlessSameGroupWillBeUpdated() throws Exception {
        JSONObject previous = new JSONObject().put("key", "fixture-a").put("action", "notify").put("notificationId", 123);
        assertTrue(NotificationManagementRules.obsoleteAlert(previous, "fixture-a", "retain", 123));
        assertTrue(NotificationManagementRules.obsoleteAlert(previous, "fixture-a", "clear", 123));
        assertTrue(NotificationManagementRules.obsoleteAlert(previous, "fixture-a", "collect", 123));
        assertTrue(NotificationManagementRules.obsoleteAlert(previous, "fixture-a", "notify", 456));
        assertFalse(NotificationManagementRules.obsoleteAlert(previous, "fixture-a", "notify", 123));
        assertFalse(NotificationManagementRules.obsoleteAlert(previous, "fixture-b", "notify", 456));
        for (String action : new String[]{"retain", "clear", "collect"})
            assertTrue(NotificationManagementRules.obsoleteAlert(previous, "fixture-b", action, 123));
        assertTrue(NotificationManagementRules.superseded(previous, "fixture-b", 123));
        assertFalse(NotificationManagementRules.superseded(previous, "fixture-b", 456));
    }

    @Test public void supersededOrOldPolicyReceiptsNeverRunTimers() throws Exception {
        JSONObject receipt = new JSONObject().put("revision", 2).put("applied", true).put("dismissed", false);
        assertTrue(NotificationManagementRules.scheduled(receipt, 2, true));
        assertFalse(NotificationManagementRules.scheduled(receipt, 3, true));
        assertFalse(NotificationManagementRules.scheduled(receipt, 2, false));
        receipt.put("dismissed", true);
        assertFalse(NotificationManagementRules.scheduled(receipt, 2, true));
    }

    @Test public void classificationFlagsRequireBooleansAndCompatibleActions() throws Exception {
        JSONObject important = NotificationManagementRules.decision(new JSONObject().put("action", "retain").put("important", true));
        assertTrue(important.getBoolean("important"));
        JSONObject advertisement = NotificationManagementRules.decision(new JSONObject().put("action", "clear").put("advertisement", true));
        assertTrue(advertisement.getBoolean("advertisement"));
        JSONObject ordinary = NotificationManagementRules.decision(new JSONObject().put("action", "retain"));
        assertFalse(ordinary.optBoolean("important"));
        assertFalse(ordinary.optBoolean("advertisement"));
        for (String flag : new String[]{"important", "advertisement"}) {
            String compatibleAction = flag.equals("important") ? "retain" : "clear";
            for (Object invalid : new Object[]{"true", "false", 1, 0, JSONObject.NULL, new JSONObject(), new JSONArray()}) {
                try {
                    NotificationManagementRules.decision(new JSONObject().put("action", compatibleAction).put(flag, invalid));
                    fail("Accepted non-boolean " + flag + ": " + invalid);
                } catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
            }
            for (String action : new String[]{"retain", "clear", "collect", "notify"}) {
                assertFalse(NotificationManagementRules.decision(new JSONObject().put("action", action).put(flag, false)).getBoolean(flag));
                if (action.equals(compatibleAction)) continue;
                try {
                    NotificationManagementRules.decision(new JSONObject().put("action", action).put(flag, true));
                    fail("Accepted " + flag + " for " + action);
                } catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
            }
        }
    }

    @Test public void reminderConfigurationDefaultsToFiveMinutesAndOnlyAcceptsFiveOrTen() throws Exception {
        assertEquals(5, NotificationManagementRules.configuration(null).getInt("reminderMinutes"));
        for (int minutes : new int[]{5, 10}) {
            assertEquals(minutes, NotificationManagementRules.reminderMinutes(minutes));
            assertEquals(minutes, NotificationManagementRules.configuration(new JSONObject().put("reminderMinutes", minutes)).getInt("reminderMinutes"));
        }
        for (Object invalid : new Object[]{0, -5, 1, 6, 15, 1440, 5.5, "5", "10", true, JSONObject.NULL}) {
            try {
                NotificationManagementRules.reminderMinutes(invalid);
                fail("Accepted invalid reminder delay: " + invalid);
            } catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
            try {
                NotificationManagementRules.configuration(new JSONObject().put("reminderMinutes", invalid));
                fail("Persisted invalid reminder delay: " + invalid);
            } catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        }
    }

    @Test public void onlyImportantRetainedNotificationsScheduleFromTheirOriginalPostTime() throws Exception {
        JSONObject source = new JSONObject().put("postedAtMs", 123456789L).put("savedAtMs", 123999999L);
        for (int minutes : new int[]{5, 10}) {
            assertEquals(123456789L + minutes * 60000L, NotificationManagementRules.reminderAt(source,
                    new JSONObject().put("action", "retain").put("important", true), minutes));
            for (String action : new String[]{"clear", "collect", "notify"})
                assertEquals(0, NotificationManagementRules.reminderAt(source,
                        new JSONObject().put("action", action).put("important", true), minutes));
            assertEquals(0, NotificationManagementRules.reminderAt(source, new JSONObject().put("action", "retain"), minutes));
            assertEquals(0, NotificationManagementRules.reminderAt(source,
                    new JSONObject().put("action", "retain").put("important", false), minutes));
        }
    }

    @Test public void sameSourceRequiresTheSameKeyContentAndPostedInstance() throws Exception {
        JSONObject source = new JSONObject().put("key", "fixture-a").put("fingerprint", "same-content").put("postedAtMs", 100);
        assertTrue(NotificationManagementRules.sameSource(source, new JSONObject(source.toString())));
        assertTrue(NotificationManagementRules.sameSource(source, new JSONObject(source.toString()).put("remindedAt", 500)));
        assertFalse(NotificationManagementRules.sameSource(source, new JSONObject(source.toString()).put("key", "fixture-b")));
        assertFalse(NotificationManagementRules.sameSource(source, new JSONObject(source.toString()).put("fingerprint", "changed-content")));
        assertFalse(NotificationManagementRules.sameSource(source, new JSONObject(source.toString()).put("postedAtMs", 200)));
    }

    @Test public void contentFingerprintChangesWithMessageAndSource() throws Exception {
        JSONObject item = new JSONObject().put("packageName", "example.parcel").put("title", "示例包裹").put("text", "配送中");
        String first = NotificationManagementRules.fingerprint(item);
        assertEquals(first, NotificationManagementRules.fingerprint(new JSONObject(item.toString()).put("postedAtMs", 200)));
        assertNotEquals(first, NotificationManagementRules.fingerprint(new JSONObject(item.toString()).put("text", "已送达")));
        assertNotEquals(first, NotificationManagementRules.fingerprint(new JSONObject(item.toString()).put("packageName", "example.other")));
    }
    @Test public void reminderPreviewUsesOnlyTheCurrentSourceFactsAndBoundsItsLength() throws Exception {
        assertEquals("包裹已到 · 放在驿站", NotificationManagementRules.reminderSummary(new JSONObject().put("title", "包裹已到").put("text", "放在驿站")));
        assertEquals("包裹已到", NotificationManagementRules.reminderSummary(new JSONObject().put("title", "包裹已到").put("text", "包裹已到")));
        assertEquals("第一行 第二行", NotificationManagementRules.reminderSummary(new JSONObject().put("text", "第一行\n第二行")));
        assertEquals("", NotificationManagementRules.reminderSummary(new JSONObject()));
        String summary = NotificationManagementRules.reminderSummary(new JSONObject().put("title", "A".repeat(200)).put("text", "B".repeat(400)));
        assertEquals(285, summary.length());
        assertTrue(summary.startsWith("A".repeat(100) + "…"));
        assertTrue(summary.endsWith("B".repeat(180) + "…"));
    }

}
