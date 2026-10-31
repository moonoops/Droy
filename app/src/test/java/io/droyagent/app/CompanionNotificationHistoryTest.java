package io.droyagent.app;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CompanionNotificationHistoryTest {
    @Test public void readingOldNotificationLeavesLaterMessagesAndOtherCompanionsUnread() throws Exception {
        ProactiveNoticeStore store = store(row("a", "old", 2), row("a", "new", 7), row("b", "other", 3));
        CompanionNotificationHistory history = new CompanionNotificationHistory(store, new JSONObject());
        history.read("a", 2, 1);
        assertEquals(List.of("new"), ids(history.pending("a")));
        assertEquals(List.of("other"), ids(history.pending("b")));
        assertEquals(2, history.readThrough("a"));
        assertEquals(1, history.count("a"));
        assertEquals("read", store.find("a:old").getString("status"));
        CompanionNotificationHistory restored = new CompanionNotificationHistory(store, history.snapshot());
        assertEquals(2, restored.readThrough("a"));
        restored.read("a", 1, 10);
        assertEquals(2, restored.readThrough("a"));
        assertEquals("A late read cannot restore an old unread count", 1, restored.count("a"));
    }

    @Test public void swipeDismissalDoesNotAdvanceReadCursorOrConsumeAnArrivingMessage() throws Exception {
        ProactiveNoticeStore store = store(row("a", "old", 1), row("a", "snapshot", 4), row("a", "arrived", 9));
        CompanionNotificationHistory history = new CompanionNotificationHistory(store, new JSONObject());
        history.count("a", 3);
        history.dismiss("a", "snapshot");
        assertEquals(List.of("arrived"), ids(history.pending("a")));
        assertEquals(0, history.readThrough("a"));
        assertEquals(3, history.count("a"));
    }

    @Test public void removingOneMessageDoesNotReadOrDeleteOtherUnreadMessages() throws Exception {
        ProactiveNoticeStore store = store(row("a", "first", 1), row("a", "removed", 2), row("a", "third", 3));
        CompanionNotificationHistory history = new CompanionNotificationHistory(store, new JSONObject());
        history.remove("a", "removed", 2);
        assertEquals(List.of("first", "third"), ids(history.pending("a")));
        assertEquals(0, history.readThrough("a"));
    }

    @Test public void historyUsesSequenceAndKeepsDisplayBoundSeparateFromUnreadCount() throws Exception {
        ProactiveNoticeStore store = store();
        for (int i = 15; i > 0; i--) store.add(row("a", "message-" + i, i).put("sentAt", 100000 - i));
        CompanionNotificationHistory history = new CompanionNotificationHistory(store, new JSONObject());
        history.count("a", 25);
        assertEquals(15, history.pending("a").size());
        assertEquals(CompanionNotificationHistory.DISPLAY_LIMIT, history.recent("a").size());
        assertEquals("message-15", history.recent("a").get(9).getString("messageId"));
        assertEquals(25, history.count("a"));
    }

    @Test public void clearRemovesOnlyRetiredCompanionAndItsWatermark() throws Exception {
        ProactiveNoticeStore store = store(row("a", "old", 1), row("b", "other", 2));
        CompanionNotificationHistory history = new CompanionNotificationHistory(store, new JSONObject());
        history.read("a", 1, 0); history.clear("a");
        assertNull(store.find("a:old"));
        assertEquals(0, history.readThrough("a"));
        assertEquals(List.of("other"), ids(history.pending("b")));
    }

    @Test public void terminalReceiptsNeverReappearInUnreadHistory() throws Exception {
        ProactiveNoticeStore store = store(row("a", "visible", 1).put("status", "suppressed"),
            row("a", "read", 2).put("status", "read"), row("a", "dismissed", 3).put("status", "dismissed"),
            row("a", "removed", 4).put("status", "removed"), row("a", "pending", 5).put("status", "dispatching"));
        CompanionNotificationHistory history = new CompanionNotificationHistory(store, new JSONObject());
        assertEquals(List.of("pending"), ids(history.pending("a")));
    }

    @Test public void legacyMessagesWithoutSequenceCanBeClearedByAnAuthoritativeRead() throws Exception {
        ProactiveNoticeStore store = store(row("a", "legacy", 0), row("a", "new", 9));
        CompanionNotificationHistory history = new CompanionNotificationHistory(store, new JSONObject());
        history.read("a", 0, 1);
        assertEquals(List.of("new"), ids(history.pending("a")));
    }

    @Test public void protocolNumbersRejectLossyAndNegativeValues() throws Exception {
        for (Object invalid : List.of(-1, 1.5, "1", 9007199254740992d)) {
            try { ProactiveNotifications.integer(new JSONObject().put("sequence", invalid), "sequence", 0, 0); fail("Accepted " + invalid); }
            catch (IllegalArgumentException expected) { }
        }
        assertEquals(0, ProactiveNotifications.integer(new JSONObject(), "sequence", 1, 0));
        assertEquals(7, ProactiveNotifications.integer(new JSONObject().put("sequence", 7), "sequence", 1, 0));
    }

    @Test public void bundledAvatarSelectionCannotEscapePublicCompanionAssets() {
        assertEquals("kaka-v2.png", CompanionNotificationPresentation.assetName("/droy/companions/assets/kaka-v2.png?v=20261030"));
        for (String invalid : List.of("https://example.invalid/avatar.png", "/droy/companions/assets/../private.png",
                "/droy/companions/assets/%2e%2e/private.png", "file:///private.png", "/droy/companions/assets/private.png?token=secret"))
            assertNull(CompanionNotificationPresentation.assetName(invalid));
    }

    private static List<String> ids(List<JSONObject> rows) { return rows.stream().map(row -> row.optString("messageId")).toList(); }
    private static ProactiveNoticeStore store(JSONObject... rows) throws Exception { return new ProactiveNoticeStore(new JSONArray(List.of(rows))); }
    private static JSONObject row(String companion, String message, long sequence) throws Exception {
        return new JSONObject().put("noticeId", companion + ":" + message).put("channel", "companion").put("companionId", companion)
            .put("messageId", message).put("sequence", sequence).put("sentAt", sequence + 100).put("text", "Synthetic content").put("status", "published");
    }
}
