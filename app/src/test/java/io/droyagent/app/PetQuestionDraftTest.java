package io.droyagent.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PetQuestionDraftTest {
    private PetQuestionDraft draft(boolean multiple) throws Exception {
        return new PetQuestionDraft("task:request", new JSONArray().put(new JSONObject().put("id", "app")
            .put("question", "Choose an app").put("multiSelect", multiple)));
    }
    @Test public void singleChoiceAndCustomReplaceEachOtherLikeTheDshComposer() throws Exception {
        PetQuestionDraft draft = draft(false);
        var answer = draft.answers.get(0);
        assertFalse(draft.complete());
        answer.choose("Application A", true); answer.choose("Application B", true);
        assertEquals("[\"Application B\"]", draft.encode().getJSONObject(0).getJSONArray("selected").toString());
        answer.type("  My application  ");
        assertTrue(draft.complete());
        assertEquals(0, draft.encode().getJSONObject(0).getJSONArray("selected").length());
        assertEquals("My application", draft.encode().getJSONObject(0).getString("custom"));
        answer.choose("Application A", true);
        assertFalse(draft.encode().getJSONObject(0).has("custom"));
    }
    @Test public void unicodeWhitespaceMatchesTheDshCustomAnswerRules() throws Exception {
        PetQuestionDraft draft = draft(false);
        draft.answers.get(0).type("\u3000\u00a0\ufeff");
        assertFalse("Whitespace must not silently act as an explicit skip", draft.complete());
        draft.answers.get(0).type("\u3000\u00a0回答\ufeff");
        assertTrue(draft.complete());
        assertEquals("回答", draft.encode().getJSONObject(0).getString("custom"));
    }
    @Test public void multipleChoicePreservesCustomAndDeselection() throws Exception {
        PetQuestionDraft draft = draft(true);
        var answer = draft.answers.get(0);
        answer.choose("A", true); answer.choose("B", true); answer.type("And another one");
        answer.choose("A", false);
        JSONObject result = draft.encode().getJSONObject(0);
        assertEquals("[\"B\"]", result.getJSONArray("selected").toString());
        assertEquals("And another one", result.getString("custom"));
    }
    @Test public void explicitSkipIsAnEmptyAnswerAndEachQuestionMustBeCompleted() throws Exception {
        PetQuestionDraft draft = new PetQuestionDraft("task:request", new JSONArray()
            .put(new JSONObject().put("id", "first")).put(new JSONObject().put("id", "second")));
        draft.answers.get(0).skip();
        assertFalse(draft.complete());
        draft.answers.get(1).type("  "); assertFalse(draft.complete());
        draft.answers.get(1).type("回答"); assertTrue(draft.complete());
        JSONObject skipped = draft.encode().getJSONObject(0);
        assertEquals("first", skipped.getString("id"));
        assertEquals(0, skipped.getJSONArray("selected").length());
        assertFalse(skipped.has("custom"));
    }
}
