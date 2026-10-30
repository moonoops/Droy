package io.droyagent.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProactiveNoticeStoreTest {
    @Test public void sameConversationAndNoticeInAnotherTurnRemainIndependent() throws Exception {
        String first = ProactiveNoticeStore.identity("conversation", "10", "result", "system", "", "");
        assertEquals(first, ProactiveNoticeStore.identity("conversation", "10", "result", "system", "", ""));
        assertNotEquals(first, ProactiveNoticeStore.identity("conversation", "20", "result", "system", "", ""));
        assertNotEquals(first, ProactiveNoticeStore.identity("conversation", "10", "question", "system", "", ""));
        assertNotEquals(first, ProactiveNoticeStore.identity("conversation", "10", "result", "app", "example.messages", "Self"));
    }

    @Test public void interruptionNeverRestoresAnAppSendAsExecutable() throws Exception {
        JSONArray saved = new JSONArray()
            .put(new JSONObject().put("noticeId", "app").put("status", "requires_action"))
            .put(new JSONObject().put("noticeId", "system").put("status", "dispatching"))
            .put(new JSONObject().put("noticeId", "finished").put("status", "sent_in_app"));
        ProactiveNoticeStore store = new ProactiveNoticeStore(saved);
        assertEquals("unknown", store.find("app").getString("status"));
        assertEquals("unknown", store.find("system").getString("status"));
        assertEquals("sent_in_app", store.find("finished").getString("status"));
        assertEquals("requires_action", saved.getJSONObject(0).getString("status"));
    }

    @Test public void receiptsStayBoundedAndSnapshotsDoNotShareMutations() throws Exception {
        ProactiveNoticeStore store = new ProactiveNoticeStore(new JSONArray());
        for (int i = 0; i < ProactiveNoticeStore.LIMIT + 3; i++) store.add(new JSONObject().put("noticeId", "id-" + i).put("status", "published"));
        assertEquals(ProactiveNoticeStore.LIMIT, store.snapshot().length());
        assertNull(store.find("id-0"));
        JSONArray snapshot = store.snapshot();
        snapshot.getJSONObject(0).put("status", "changed");
        assertEquals("published", store.find("id-3").getString("status"));
    }
    @Test public void companionRetirementRemovesOnlyItsMessageReceipts() throws Exception {
        ProactiveNoticeStore store = new ProactiveNoticeStore(new JSONArray()
            .put(new JSONObject().put("noticeId", "a").put("channel", "companion").put("companionId", "retiring"))
            .put(new JSONObject().put("noticeId", "b").put("channel", "companion").put("companionId", "other"))
            .put(new JSONObject().put("noticeId", "task").put("channel", "system").put("companionId", "retiring")));
        store.removeCompanion("retiring");
        assertNull(store.find("a"));
        assertNotNull(store.find("b"));
        assertNotNull(store.find("task"));
        store.removeCompanion("retiring");
        assertEquals(2, store.snapshot().length());
    }

}
